package org.siloserver.silo.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.siloserver.silo.model.auth.DeviceLoginCapabilityResponse
import org.siloserver.silo.model.auth.DeviceLoginPollResponse
import org.siloserver.silo.model.auth.DeviceLoginStartResponse

/** What the TV sign-in screen shows for its device code. See [DeviceSignInMachine]. */
sealed interface DeviceSignInState {
    /** Asking the server for a code. */
    data object GettingCode : DeviceSignInState

    /**
     * A live code. [opened] is true once an approver has looked it up (the
     * server's opened signal); [renewed] is true when this code replaced an
     * expired one without the person asking.
     */
    data class ShowingCode(
        val session: DeviceLoginStartResponse,
        val opened: Boolean = false,
        val renewed: Boolean = false,
    ) : DeviceSignInState

    /** The approver signed this TV in; the tokens are in [response] and nowhere else. */
    data class Approved(val response: DeviceLoginPollResponse) : DeviceSignInState

    /** The approver declined. */
    data object Denied : DeviceSignInState

    /** Codes renewed for about an hour with no approval; waiting for the person. */
    data object Paused : DeviceSignInState

    /**
     * Calls keep failing with network errors or 5xx. Still retrying with
     * backoff. [session] is the last code, when it is still live.
     */
    data class Unreachable(val session: DeviceLoginStartResponse?) : DeviceSignInState

    /** The server has answered 429 for over a minute. Still retrying. */
    data class TooManyRequests(val session: DeviceLoginStartResponse?) : DeviceSignInState

    /** The server doesn't offer device sign-in; only a password works. */
    data object NoDeviceSignIn : DeviceSignInState

    /** The server or this app must be updated first. */
    data object UpdateRequired : DeviceSignInState

    /** The server answered in a way retrying won't fix (missing tokens, unknown status, 4xx on start). */
    data object Failed : DeviceSignInState
}

/** Timing for [DeviceSignInMachine]; shortened in tests. */
data class DeviceSignInConfig(
    /** Stop renewing codes after this long without the person doing anything. */
    val pauseAfterMs: Long = 60 * 60_000L,
    /** Show "can't reach" when getting a code has failed for this long. */
    val startUnreachableAfterMs: Long = 10_000L,
    /** Show "can't reach" when polling has failed for this long. */
    val pollUnreachableAfterMs: Long = 30_000L,
    /** Show "too many requests" when 429s have persisted this long. */
    val rateLimitedAfterMs: Long = 60_000L,
    /** Bound on the best-effort cancel call when the code is abandoned. */
    val cancelTimeoutMs: Long = 5_000L,
)

/**
 * The TV sign-in screen's device code: gets a code, polls it, renews it in
 * place when it expires while the screen is visible, pauses after about an
 * hour of renewals, and names the failure when the server can't be reached or
 * keeps answering 429. Silo-Server/silo-server TV sign-in spec, states table.
 * Each poll answer is read through [DeviceLoginPoller], the same step the
 * pairing receiver's loop uses.
 *
 * [run] drives the machine until it reaches a state that waits for the
 * person ([DeviceSignInState.Approved], Denied, Paused, NoDeviceSignIn,
 * UpdateRequired, Failed), or until [requestStop]. Stopping keeps the current
 * code, so the screen can stop polling when the app goes to the background and
 * call [run] again when it returns: the code is polled at once and replaced
 * only if the server no longer holds it.
 *
 * A code is replaced only after a poll: when the server says it is gone, or
 * when a poll finds the local deadline passed. A code replaced while the
 * server still holds it is withdrawn there first (when the server supports
 * cancel), so it can't be approved after the TV moved on.
 *
 * Each [run] is numbered. A new [run], [restart] or [abandon] supersedes the
 * run before it: when a call returns to a superseded run, the run drops the
 * answer and returns without publishing, replacing or starting anything, so
 * a run left blocked in a slow call can't bring back a withdrawn code or drop
 * the newer run's code.
 *
 * Not thread-safe: call everything from one coroutine context.
 */
class DeviceSignInMachine(
    private val repository: DeviceLoginRepository,
    val serverUrl: String,
    private val deviceName: String?,
    private val devicePlatform: String?,
    private val clock: DeviceLoginClock = DeviceLoginClock.System,
    private val config: DeviceSignInConfig = DeviceSignInConfig(),
) {
    private val _state = MutableStateFlow<DeviceSignInState>(DeviceSignInState.GettingCode)
    val state: StateFlow<DeviceSignInState> = _state.asStateFlow()

    private var capability: DeviceLoginCapabilityResponse? = null
    private var poller: DeviceLoginPoller? = null
    private var renewed = false

    /**
     * [Runs.current] numbers the run that owns the machine; [Runs.stopped] is
     * the run [requestStop] asked to return at its next wait or before its
     * next call.
     */
    private data class Runs(val current: Long = 0L, val stopped: Long = -1L)

    private val runs = MutableStateFlow(Runs())

    /** Start of the current run of automatic renewals; reset by [restart] and [noteActivity]. */
    private var renewalBudgetStart: Long? = null

    /** Whether the machine is waiting for the person rather than working. */
    val isSettled: Boolean
        get() = when (_state.value) {
            is DeviceSignInState.Approved,
            DeviceSignInState.Denied,
            DeviceSignInState.Paused,
            DeviceSignInState.NoDeviceSignIn,
            DeviceSignInState.UpdateRequired,
            DeviceSignInState.Failed,
            -> true
            else -> false
        }

    /** The person is here (screen came back to the foreground): renewals start their hour again. */
    fun noteActivity() {
        renewalBudgetStart = clock.nowMs()
    }

    /**
     * Ask [run] to return. A wait between calls ends at once; a call already
     * in flight finishes first and its answer is handled, so a poll the server
     * already answered with the one-time tokens is never dropped.
     */
    fun requestStop() {
        runs.update { it.copy(stopped = it.current) }
    }

    /**
     * Forget the current code and start from "Getting a sign-in code". Call
     * [abandon] first when the old code may still be live on the server.
     */
    fun restart() {
        supersede()
        poller = null
        renewed = false
        renewalBudgetStart = null
        _state.value = DeviceSignInState.GettingCode
    }

    /**
     * Withdraw the current code on the server (when it supports cancel) so it
     * can't be approved after the TV moved on. The call runs in [scope], which
     * must outlive the screen; the code is forgotten immediately and any run
     * still in a call is superseded.
     */
    fun abandon(scope: CoroutineScope) {
        supersede()
        val abandoned = poller?.session ?: return
        poller = null
        if (!mayWithdraw) return
        scope.launch { withdraw(abandoned) }
    }

    suspend fun run() {
        if (isSettled) return
        val mine = runs.updateAndGet { it.copy(current = it.current + 1) }.current
        fun superseded() = runs.value.current != mine
        fun stopped() = runs.value.let { it.current != mine || it.stopped == mine }
        val budgetStart = renewalBudgetStart ?: clock.nowMs().also { renewalBudgetStart = it }

        if (capability == null) capability = repository.capability(serverUrl)
        if (superseded()) return
        if (capability?.deviceLoginAvailable == false) {
            publish(DeviceSignInState.NoDeviceSignIn)
            return
        }

        var failingSince: Long? = null
        var rateLimitedSince: Long? = null
        var startBackoffMs = 0L

        fun noteFailure(kind: DeviceLoginErrorKind, starting: Boolean) {
            val now = clock.nowMs()
            if (kind == DeviceLoginErrorKind.RateLimited) {
                val since = rateLimitedSince ?: now.also { rateLimitedSince = it }
                if (now - since >= config.rateLimitedAfterMs) {
                    publish(DeviceSignInState.TooManyRequests(liveSession()))
                }
            } else {
                val since = failingSince ?: now.also { failingSince = it }
                val threshold = if (starting) config.startUnreachableAfterMs else config.pollUnreachableAfterMs
                if (now - since >= threshold) {
                    publish(DeviceSignInState.Unreachable(liveSession()))
                }
            }
        }

        fun recovered() {
            failingSince = null
            rateLimitedSince = null
            startBackoffMs = 0L
        }

        while (true) {
            // A cancelled run (the screen left, or a password sign-in won) must
            // not reach the server again. A wait that times out as it is
            // cancelled can return normally, and the next steps may not
            // suspend before they ask for a new code.
            currentCoroutineContext().ensureActive()
            if (stopped()) return
            val live = poller
            if (live == null) {
                // About an hour without the person doing anything: stop asking
                // the server for codes nobody is looking at.
                if (clock.nowMs() - budgetStart >= config.pauseAfterMs) {
                    publish(DeviceSignInState.Paused)
                    return
                }
                if (_state.value !is DeviceSignInState.Unreachable &&
                    _state.value !is DeviceSignInState.TooManyRequests &&
                    _state.value !is DeviceSignInState.ShowingCode
                ) {
                    publish(DeviceSignInState.GettingCode)
                }
                val outcome = repository.start(serverUrl, deviceName, devicePlatform)
                if (superseded()) {
                    // Nobody will show or poll this code: withdraw it.
                    if (outcome is DeviceLoginRepository.StartOutcome.Started && mayWithdraw) {
                        withdraw(outcome.session)
                    }
                    return
                }
                when (outcome) {
                    is DeviceLoginRepository.StartOutcome.Started -> {
                        recovered()
                        val started = DeviceLoginPoller(
                            pollOnce = { repository.poll(serverUrl, it) },
                            session = outcome.session,
                            clock = clock,
                        )
                        poller = started
                        publish(DeviceSignInState.ShowingCode(started.session, opened = false, renewed = renewed))
                        // Poll at once: fall through.
                    }
                    is DeviceLoginRepository.StartOutcome.Failed -> when (outcome.kind) {
                        DeviceLoginErrorKind.NotFound -> {
                            publish(DeviceSignInState.NoDeviceSignIn)
                            return
                        }
                        DeviceLoginErrorKind.UpdateRequired -> {
                            publish(DeviceSignInState.UpdateRequired)
                            return
                        }
                        DeviceLoginErrorKind.Rejected -> {
                            publish(DeviceSignInState.Failed)
                            return
                        }
                        DeviceLoginErrorKind.Transient, DeviceLoginErrorKind.RateLimited -> {
                            noteFailure(outcome.kind, starting = true)
                            startBackoffMs = DeviceLoginTiming.nextBackoffMs(startBackoffMs, 1_000L)
                            if (!pause(startBackoffMs, mine)) return
                            continue
                        }
                    }
                }
                if (stopped()) return
            }

            val current = poller ?: continue
            val step = current.poll()
            // Restarted or abandoned during the call: this answer is for a
            // code the screen no longer shows.
            if (superseded()) return
            when (step) {
                is DeviceLoginPoller.Step.Pending -> {
                    recovered()
                    publish(DeviceSignInState.ShowingCode(current.session, opened = current.opened, renewed = renewed))
                    if (current.isExpired) {
                        replace(current, stillLive = true, mine)
                    } else if (!pause(step.waitMs, mine)) {
                        return
                    }
                }
                is DeviceLoginPoller.Step.Approved -> {
                    poller = null
                    publish(DeviceSignInState.Approved(step.response))
                    return
                }
                DeviceLoginPoller.Step.MissingTokens, is DeviceLoginPoller.Step.Unknown -> {
                    poller = null
                    publish(DeviceSignInState.Failed)
                    return
                }
                DeviceLoginPoller.Step.Denied -> {
                    poller = null
                    publish(DeviceSignInState.Denied)
                    return
                }
                // Gone on the server: replace it in place.
                is DeviceLoginPoller.Step.Gone -> replace(current, stillLive = false, mine)
                DeviceLoginPoller.Step.UpdateRequired -> {
                    poller = null
                    publish(DeviceSignInState.UpdateRequired)
                    return
                }
                is DeviceLoginPoller.Step.Retry -> {
                    noteFailure(step.kind, starting = false)
                    // Past the deadline with the server unreachable: a cancel
                    // wouldn't arrive either, so just ask for a new code.
                    if (current.isExpired) {
                        replace(current, stillLive = false, mine)
                    } else if (!pause(step.waitMs, mine)) {
                        return
                    }
                }
            }
        }
    }

    /**
     * Drop [current] for a new code. [stillLive]: the server still holds it
     * (the local deadline passed first), so withdraw it before moving on.
     *
     * [current] stays the machine's code until it has been withdrawn, so an
     * [abandon] that lands meanwhile still finds and withdraws it; the
     * withdraw itself can't be cut short by cancelling the run.
     */
    private suspend fun replace(current: DeviceLoginPoller, stillLive: Boolean, mine: Long) {
        // A capability read that failed earlier gets another chance, so a
        // server that does support cancel gets its codes withdrawn.
        if (capability == null) capability = repository.capability(serverUrl)
        // Restarted or abandoned meanwhile: abandon took the code.
        if (runs.value.current != mine) return
        if (stillLive && mayWithdraw) withdraw(current.session)
        if (runs.value.current != mine) return
        poller = null
        renewed = true
    }

    /**
     * Whether a code may be withdrawn on the server: every withdraw decision
     * uses this one rule. Unknown support (the capability read failed) still
     * gets the cancel: a server without it answers 404, which is harmless.
     */
    private val mayWithdraw: Boolean
        get() = capability?.cancel != false

    /** Best-effort, bounded cancel of [session] that survives cancelling the caller. */
    private suspend fun withdraw(session: DeviceLoginStartResponse) {
        withContext(NonCancellable) {
            withTimeoutOrNull(config.cancelTimeoutMs) {
                repository.cancel(serverUrl, session.deviceCode)
            }
        }
    }

    /** Retire the current run: it returns at its next check without publishing. */
    private fun supersede() {
        runs.update { it.copy(current = it.current + 1) }
    }

    /** Wait [ms]; false when [requestStop], [restart] or [abandon] ended the wait for run [mine]. */
    private suspend fun pause(ms: Long, mine: Long): Boolean {
        fun Runs.ends() = current != mine || stopped == mine
        if (ms <= 0L) return !runs.value.ends()
        return withTimeoutOrNull(ms) { runs.first { it.ends() } } == null
    }

    private fun liveSession(): DeviceLoginStartResponse? =
        poller?.takeIf { !it.isExpired }?.session

    private fun publish(state: DeviceSignInState) {
        _state.value = state
    }
}
