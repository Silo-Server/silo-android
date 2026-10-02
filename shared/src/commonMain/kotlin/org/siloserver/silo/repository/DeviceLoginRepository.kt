package org.siloserver.silo.repository

import org.siloserver.silo.model.auth.DeviceLoginCapabilityResponse
import org.siloserver.silo.model.auth.DeviceLoginPollResponse
import org.siloserver.silo.model.auth.DeviceLoginStartResponse
import org.siloserver.silo.model.auth.DeviceLoginStatus
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.api.DeviceLoginApi
import org.siloserver.silo.network.apiv2.ApiV2Gate
import org.siloserver.silo.util.parseRfc3339ToEpochMillis
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.TimeSource

/** Monotonic milliseconds, injectable so tests can drive deadlines with virtual time. */
fun interface DeviceLoginClock {
    fun nowMs(): Long

    companion object {
        private val origin = TimeSource.Monotonic.markNow()
        val System = DeviceLoginClock { origin.elapsedNow().inWholeMilliseconds }
    }
}

/**
 * How a failed device-login call should be handled. Shared by the pairing
 * receiver's poll loop and the TV sign-in screen so both treat the same
 * server answer the same way.
 */
enum class DeviceLoginErrorKind {
    /** Network failure, timeout or 5xx: the request may still be live; back off and retry. */
    Transient,

    /** 429: back off and retry; tell the person if it persists. */
    RateLimited,

    /** 404 (or 501): the server has no such request (poll) or no device sign-in (start). */
    NotFound,

    /** 410, 426, or the v2 gate: the server or this app must be updated first. */
    UpdateRequired,

    /** Any other 4xx: retrying the same call won't help. */
    Rejected;

    companion object {
        fun of(result: ApiResult<*>): DeviceLoginErrorKind = when (result) {
            is ApiResult.NetworkError -> Transient
            is ApiResult.Error -> when {
                result.code == 0 && result.error == ApiV2Gate.UPDATE_REQUIRED_ERROR -> UpdateRequired
                result.code == 429 -> RateLimited
                result.code == 404 || result.code == 501 -> NotFound
                result.code == 410 || result.code == 426 -> UpdateRequired
                result.code == 408 || result.code >= 500 -> Transient
                // code 0 without the gate marker: the response could not be read.
                result.code == 0 -> Transient
                else -> Rejected
            }
            is ApiResult.Success -> error("not a failure")
        }
    }
}

/** Timing rules for device-login polling. */
object DeviceLoginTiming {
    /** Backoff ceiling for network errors, 5xx and 429. */
    const val MAX_BACKOFF_MS = 30_000L

    /** Next backoff after a failure: doubles from the poll interval up to [MAX_BACKOFF_MS]. */
    fun nextBackoffMs(current: Long, intervalMs: Long): Long =
        if (current <= 0L) intervalMs.coerceIn(1_000L, MAX_BACKOFF_MS)
        else (current * 2).coerceAtMost(MAX_BACKOFF_MS)
}

/**
 * One live device code's polling, shared by [DeviceSignInMachine] and
 * [DeviceLoginRepository]'s pairing loop so both read a server answer the
 * same way. [poll] makes one call and says what it means; the caller decides
 * what the screen shows and whether to wait, renew or stop.
 *
 * Deadline: starts at `expires_in` from the start answer. A pending answer
 * carries the request's current `expires_at`, which an approver's lookup
 * extends; the deadline moves to it (never earlier), converted from server
 * time to this device's clock through the start answer's own
 * `expires_at`/`expires_in` pair, so clock skew between the two cancels out.
 * Waits are clamped to the deadline, so the last call before a caller gives
 * up on a code is always a poll.
 */
class DeviceLoginPoller(
    private val pollOnce: suspend (deviceCode: String) -> DeviceLoginRepository.PollOutcome,
    val session: DeviceLoginStartResponse,
    private val clock: DeviceLoginClock,
) {
    /** What one poll answer means. */
    sealed interface Step {
        /** Still pending: wait [waitMs] (already clamped to the deadline) before the next poll. */
        data class Pending(val waitMs: Long) : Step

        /** Approved with tokens; they are in [response] and nowhere else. */
        data class Approved(val response: DeviceLoginPollResponse) : Step

        /** Approved, but the answer carried no tokens. */
        data object MissingTokens : Step

        data object Denied : Step

        /**
         * The server has no live request for this code any more: [status] is
         * `expired`, `consumed` or `canceled`, or null for a 404 or another
         * 4xx that retrying won't change.
         */
        data class Gone(val status: DeviceLoginStatus?) : Step

        /** 410, 426 or the v2 gate: the server or this app must be updated. */
        data object UpdateRequired : Step

        /** A status this build doesn't know. */
        data class Unknown(val status: String) : Step

        /** Network error, 5xx or 429: the request may still be live. Wait [waitMs] (clamped to the deadline) and poll again. */
        data class Retry(val kind: DeviceLoginErrorKind, val waitMs: Long) : Step
    }

    private val startedAt = clock.nowMs()

    /** Local-clock ms minus server-clock ms, or null when the start answer's `expires_at` can't be read. */
    private val serverToLocalMs: Long? = parseRfc3339ToEpochMillis(session.expiresAt)
        ?.let { serverExpiry -> startedAt + session.expiresIn.coerceAtLeast(1) * 1_000L - serverExpiry }

    /** Local time after which the code is treated as expired. */
    var deadline: Long = startedAt + session.expiresIn.coerceAtLeast(1) * 1_000L
        private set

    /** True once an approver has looked the request up (the server's opened signal). */
    var opened: Boolean = false
        private set

    private var intervalMs = session.interval.coerceAtLeast(1) * 1_000L
    private var backoffMs = 0L

    /** Whether the deadline has passed. */
    val isExpired: Boolean
        get() = clock.nowMs() >= deadline

    suspend fun poll(): Step = when (val outcome = pollOnce(session.deviceCode)) {
        is DeviceLoginRepository.PollOutcome.Answered -> {
            backoffMs = 0L
            answered(outcome.response)
        }
        is DeviceLoginRepository.PollOutcome.Failed -> when (outcome.kind) {
            DeviceLoginErrorKind.NotFound, DeviceLoginErrorKind.Rejected -> Step.Gone(null)
            DeviceLoginErrorKind.UpdateRequired -> Step.UpdateRequired
            DeviceLoginErrorKind.Transient, DeviceLoginErrorKind.RateLimited -> {
                backoffMs = DeviceLoginTiming.nextBackoffMs(backoffMs, intervalMs)
                Step.Retry(outcome.kind, clampToDeadline(backoffMs))
            }
        }
    }

    private fun answered(response: DeviceLoginPollResponse): Step = when (val status = DeviceLoginStatus.fromWire(response.status)) {
        DeviceLoginStatus.Pending -> {
            if (response.opened) opened = true
            val serverExpiry = response.expiresAt?.let(::parseRfc3339ToEpochMillis)
            if (serverExpiry != null && serverToLocalMs != null) {
                deadline = maxOf(deadline, serverExpiry + serverToLocalMs)
            }
            response.pollAfter?.let { intervalMs = it.coerceAtLeast(1) * 1_000L }
            Step.Pending(clampToDeadline(intervalMs))
        }
        DeviceLoginStatus.Approved ->
            if (response.accessToken.isNullOrBlank() || response.refreshToken.isNullOrBlank()) {
                Step.MissingTokens
            } else {
                Step.Approved(response)
            }
        DeviceLoginStatus.Denied -> Step.Denied
        DeviceLoginStatus.Expired,
        DeviceLoginStatus.Consumed,
        DeviceLoginStatus.Canceled,
        -> Step.Gone(status)
        DeviceLoginStatus.Unknown -> Step.Unknown(response.status)
    }

    private fun clampToDeadline(waitMs: Long): Long =
        waitMs.coerceAtMost((deadline - clock.nowMs()).coerceAtLeast(0L))
}

/**
 * Device-login operations and the single-attempt state machine used by LAN
 * companion pairing: start → poll → terminal (Approved / Failed). The TV
 * sign-in screen drives its own renewing session through [DeviceSignInMachine]
 * on top of the same operations.
 *
 * Both read each poll answer through [DeviceLoginPoller]:
 *  - polls immediately after start, then honors `interval` / `poll_after`
 *  - network errors, 5xx and 429 back off exponentially up to 30 s
 *  - a local deadline from `expires_in`, moved to a pending answer's
 *    `expires_at`, ends the attempt even while the server can't be reached;
 *    the last call before it ends is a poll
 *  - 404 (or another 4xx) on poll = the server has no such request → Failed(Expired)
 */
class DeviceLoginRepository(
    private val api: DeviceLoginApi,
    private val clock: DeviceLoginClock = DeviceLoginClock.System,
) {
    sealed class DeviceLoginState {
        object Idle : DeviceLoginState()
        object Initiating : DeviceLoginState()

        data class Awaiting(val session: DeviceLoginStartResponse) : DeviceLoginState()

        data class Approved(val response: DeviceLoginPollResponse) : DeviceLoginState()
        data class Failed(val reason: FailureReason, val message: String? = null) : DeviceLoginState()
    }

    enum class FailureReason {
        StartFailed,    // start answered with an error that retrying won't fix
        Unreachable,    // start could not reach the server
        RateLimited,    // start answered 429
        Unsupported,    // start answered 404: no device sign-in on this server
        UpdateRequired, // 410 / v1-only / app too old
        Expired,        // polled row gone (404), status=expired, or the local deadline passed
        Denied,         // status=denied
        Canceled,       // status=canceled
        Consumed,       // status=consumed (already used)
        MissingTokens,  // status=approved but no access_token returned
        UnknownStatus,  // server returned a status we don't recognize
    }

    /** Result of one start call. */
    sealed interface StartOutcome {
        data class Started(val session: DeviceLoginStartResponse) : StartOutcome
        data class Failed(val kind: DeviceLoginErrorKind) : StartOutcome
    }

    /** Result of one poll call. */
    sealed interface PollOutcome {
        data class Answered(val response: DeviceLoginPollResponse) : PollOutcome
        data class Failed(val kind: DeviceLoginErrorKind) : PollOutcome
    }

    private val _state = MutableStateFlow<DeviceLoginState>(DeviceLoginState.Idle)
    val state: StateFlow<DeviceLoginState> = _state.asStateFlow()

    /**
     * Begins a new device-login session. Suspends through initiate;
     * starts polling internally and returns when the state machine
     * reaches a terminal value (Approved or Failed).
     *
     * Call this from a cancellable coroutine — cancel to abort.
     */
    suspend fun begin(deviceName: String?, devicePlatform: String?) {
        beginAt(serverUrl = null, deviceName = deviceName, devicePlatform = devicePlatform)
    }

    /**
     * Begin against [serverUrl] without changing the globally active server.
     * Companion setup uses this persist-on-success path for candidate servers.
     */
    suspend fun beginAt(serverUrl: String?, deviceName: String?, devicePlatform: String?) {
        _state.value = DeviceLoginState.Initiating

        val session = when (val outcome = start(serverUrl, deviceName, devicePlatform)) {
            is StartOutcome.Started -> outcome.session
            is StartOutcome.Failed -> {
                _state.value = DeviceLoginState.Failed(
                    when (outcome.kind) {
                        DeviceLoginErrorKind.Transient -> FailureReason.Unreachable
                        DeviceLoginErrorKind.RateLimited -> FailureReason.RateLimited
                        DeviceLoginErrorKind.NotFound -> FailureReason.Unsupported
                        DeviceLoginErrorKind.UpdateRequired -> FailureReason.UpdateRequired
                        DeviceLoginErrorKind.Rejected -> FailureReason.StartFailed
                    },
                )
                return
            }
        }

        _state.value = DeviceLoginState.Awaiting(session)
        runPollLoop(session, serverUrl)
    }

    fun reset() {
        _state.value = DeviceLoginState.Idle
    }

    /** One start call against [serverUrl], or the active server when null. */
    suspend fun start(serverUrl: String?, deviceName: String?, devicePlatform: String?): StartOutcome {
        val result = if (serverUrl == null) {
            api.startDeviceLogin(deviceName, devicePlatform)
        } else {
            api.startDeviceLoginAt(serverUrl, deviceName, devicePlatform)
        }
        return when (result) {
            is ApiResult.Success -> StartOutcome.Started(result.data)
            else -> StartOutcome.Failed(DeviceLoginErrorKind.of(result))
        }
    }

    /** One poll call against [serverUrl], or the active server when null. */
    suspend fun poll(serverUrl: String?, deviceCode: String): PollOutcome {
        val result = if (serverUrl == null) {
            api.pollDeviceLogin(deviceCode)
        } else {
            api.pollDeviceLoginAt(serverUrl, deviceCode)
        }
        return when (result) {
            is ApiResult.Success -> PollOutcome.Answered(result.data)
            else -> PollOutcome.Failed(DeviceLoginErrorKind.of(result))
        }
    }

    /** The capability document at [serverUrl], or null when it can't be read. */
    suspend fun capability(serverUrl: String): DeviceLoginCapabilityResponse? =
        (api.deviceLoginCapabilityAt(serverUrl) as? ApiResult.Success)?.data

    /**
     * Withdraw [deviceCode] on the server so an abandoned code can't be
     * approved later. Best effort: a failure leaves the request to expire.
     */
    suspend fun cancel(serverUrl: String, deviceCode: String): Boolean =
        api.cancelDeviceLoginAt(serverUrl, deviceCode) is ApiResult.Success

    suspend fun lookup(token: String?, code: String?) =
        api.lookupDeviceLogin(token = token, code = code)

    suspend fun approve(token: String?, code: String?) =
        api.approveDeviceLogin(token = token, code = code)

    suspend fun deny(token: String?, code: String?) =
        api.denyDeviceLogin(token = token, code = code)

    suspend fun lookup(scope: AuthScopeSnapshot, code: String) =
        api.lookupDeviceLoginForScope(scope, code)

    suspend fun approve(scope: AuthScopeSnapshot, code: String) =
        api.approveDeviceLoginForScope(scope, code)

    suspend fun deny(scope: AuthScopeSnapshot, code: String) =
        api.denyDeviceLoginForScope(scope, code)

    private suspend fun runPollLoop(session: DeviceLoginStartResponse, serverUrl: String?) {
        val poller = DeviceLoginPoller(pollOnce = { poll(serverUrl, it) }, session = session, clock = clock)
        while (true) {
            when (val step = poller.poll()) {
                is DeviceLoginPoller.Step.Pending -> {
                    if (poller.isExpired) break
                    delay(step.waitMs)
                }
                is DeviceLoginPoller.Step.Retry -> {
                    if (poller.isExpired) break
                    delay(step.waitMs)
                }
                is DeviceLoginPoller.Step.Approved -> {
                    _state.value = DeviceLoginState.Approved(step.response)
                    return
                }
                DeviceLoginPoller.Step.MissingTokens -> {
                    fail(FailureReason.MissingTokens, "Server approved the session but did not return tokens.")
                    return
                }
                DeviceLoginPoller.Step.Denied -> {
                    fail(FailureReason.Denied, "Sign-in was declined on the other device.")
                    return
                }
                is DeviceLoginPoller.Step.Gone -> {
                    when (step.status) {
                        DeviceLoginStatus.Canceled -> fail(FailureReason.Canceled, "This sign-in request was canceled.")
                        DeviceLoginStatus.Consumed -> fail(FailureReason.Consumed, "This code has already been used.")
                        else -> fail(FailureReason.Expired, "This code expired before it was approved.")
                    }
                    return
                }
                DeviceLoginPoller.Step.UpdateRequired -> {
                    fail(FailureReason.UpdateRequired)
                    return
                }
                is DeviceLoginPoller.Step.Unknown -> {
                    fail(FailureReason.UnknownStatus, "Unexpected status: ${step.status}")
                    return
                }
            }
        }
        fail(FailureReason.Expired, "This code expired before it was approved.")
    }

    private fun fail(reason: FailureReason, message: String? = null) {
        _state.value = DeviceLoginState.Failed(reason, message)
    }
}
