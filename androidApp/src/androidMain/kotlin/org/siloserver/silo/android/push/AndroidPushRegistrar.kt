package org.siloserver.silo.android.push

import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.siloserver.silo.model.notifications.*
import org.siloserver.silo.network.*
import org.siloserver.silo.repository.PushRegistrationRepository

class AndroidPushRegistrar internal constructor(
    private val tokenProvider: AndroidPushTokenProvider,
    private val repository: PushRegistrationRepository,
    private val deviceIdProvider: () -> String,
    private val authorities: DurableLoginAuthorityProvider,
    private val store: PushInstallationStore,
    private val newKey: () -> String = {
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
    },
) {
    private val mutex = Mutex()
    /** The newest operation per saved server: a newer one supersedes an older one there, never another server's. */
    private val latestRequests = ConcurrentHashMap<String, Any>()
    private fun beginRequest(serverId: String): Any = Any().also { latestRequests[serverId] = it }
    private fun isLatest(owner: DurableLoginAuthority, attempt: Any) = latestRequests[owner.scope.serverId] === attempt
    private suspend fun capture() = authorities.snapshotDurableLoginAuthority()?.takeIf {
        it.loginId.isNotBlank() && !it.scope.profileId.isNullOrBlank() && it.scope.credentialGenerationId == null
    }
    /** A saved-login owner ([unregisterDevice]) is current while that server's login is, active or not. */
    private suspend fun current(owner: DurableLoginAuthority): Boolean {
        val live = if (owner.scope.isGenerationPinned) {
            authorities.savedLoginAuthority(owner.scope.serverId, requireNotNull(owner.scope.profileId), owner.scope.profileToken)
        } else capture()
        return owner == live
    }

    /** Last records read or written under [mutex], so a push never waits behind an in-flight registration. */
    @Volatile private var known: List<PushInstallation>? = null
    private suspend fun read() = store.read().also { known = it }
    private suspend fun write(records: List<PushInstallation>) { store.write(records); known = records }

    /** This device's installation records, one per server it has registered with. */
    internal suspend fun installations(): List<PushInstallation> = known ?: mutex.withLock { read() }
    private fun failure(code: String) = ApiResult.Error(0, code, "Push registration requires its original installation and login authority.")

    suspend fun registerIfAvailable(): ApiResult<PushDeviceRegisterResponse>? {
        val owner = capture() ?: return null
        val attempt = beginRequest(owner.scope.serverId)
        val token = tokenProvider.token()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (!current(owner)) return failure("push_authority_changed")
        return register(token, owner, attempt)
    }
    suspend fun registerToken(token: String): ApiResult<PushDeviceRegisterResponse> {
        val owner = capture() ?: return failure("push_authority_changed")
        val attempt = beginRequest(owner.scope.serverId)
        return register(token.trim(), owner, attempt)
    }
    /**
     * Registers a rotated FCM [token] with every saved server this device is
     * registered with: the active one for whoever is active there, then each
     * other one as the login and profile it was registered for. FCM allows this
     * little time, so it may stop short; [registerOtherServersIfBehind] finishes
     * the rest on the next foreground.
     */
    suspend fun registerRotatedToken(token: String) {
        val rotated = token.trim()
        val activeServerId = capture()?.scope?.serverId
        if (activeServerId != null) registerToken(rotated)
        registerOtherServers(rotated, activeServerId)
    }

    /**
     * Brings every saved server but the active one up to the current FCM token,
     * as the login and profile each was registered for: whatever a rotation ran
     * out of time for, or left uncertain, is finished here.
     */
    suspend fun registerOtherServersIfBehind() {
        val token = tokenProvider.token()?.trim()?.takeIf { it.isNotEmpty() } ?: return
        registerOtherServers(token, capture()?.scope?.serverId)
    }

    /** Each server's attempt is bounded on its own, so one that fails or stalls costs only itself. */
    private suspend fun registerOtherServers(token: String, activeServerId: String?) {
        val device = deviceIdProvider()
        val behind = installations().filter {
            val registration = it.intent.registration
            it.deviceId == device && it.intent.serverId != activeServerId && it.refusal == null &&
                registration != null && (registration.token != token || !it.acknowledged)
        }
        for (installation in behind) {
            withTimeoutOrNull(PUSH_WORK_BUDGET_MS) { registerAsRegisteredOwner(installation, token) }
        }
    }

    private suspend fun registerAsRegisteredOwner(installation: PushInstallation, token: String): ApiResult<PushDeviceRegisterResponse?> {
        val owner = registeredOwnerOf(installation) ?: return failure("push_authority_changed")
        if (token.length !in 64..512) return failure("invalid_push_token")
        // Joins that server's newest operation rather than starting a newer one:
        // bringing a token up to date must never supersede a removal under way there.
        val attempt = latestRequests.getOrPut(installation.intent.serverId) { Any() }
        return apply(owner, checkNotNull(installation.intent.registration).copy(token = token), attempt)
    }

    /** [installation]'s server's saved login acting as the profile it was registered for; null once that login is gone. */
    private suspend fun registeredOwnerOf(installation: PushInstallation): DurableLoginAuthority? =
        authorities.savedLoginAuthority(installation.intent.serverId, installation.intent.profileId, installation.intent.profileToken)
            ?.takeIf { it.loginId == installation.intent.loginId && it.scope.serverUrl == installation.serverUrl }

    private suspend fun register(token: String, owner: DurableLoginAuthority, attempt: Any): ApiResult<PushDeviceRegisterResponse> {
        if (token.length !in 64..512) return failure("invalid_push_token")
        val result = apply(owner, PushDeviceRegisterRequest("android", token, deviceIdProvider()), attempt)
        return result.map { checkNotNull(it) }
    }
    /**
     * Removes this device's registration on [serverId], active or not, before that
     * server's credentials are cleared. Acts as the login and profile it was
     * registered for: the server refuses a removal from any other profile, and a
     * refusal is kept for good. The record stays, as the server binds its key to
     * this device; a removal intent is no push target.
     *
     * Bounded so an unreachable server cannot hold up the sign-out or removal
     * waiting on it, and never throws, so unreadable records cannot abort it
     * either. A removal cut off mid-flight stays pending, like any uncertain one.
     */
    suspend fun unregisterDevice(serverId: String): ApiResult<Unit> = try {
        withTimeoutOrNull(UNREGISTER_BUDGET_MS) { removeAsRegisteredOwner(serverId) } ?: failure("push_unregister_timed_out")
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        failure("push_persistence_unavailable")
    }

    private suspend fun removeAsRegisteredOwner(serverId: String): ApiResult<Unit> {
        val attempt = beginRequest(serverId)
        val device = deviceIdProvider()
        val registered = installations().firstOrNull {
            it.intent.serverId == serverId && it.deviceId == device && it.intent.registration != null && it.refusal == null
        } ?: return failure("push_not_registered")
        markOwnerLeaving(registered)
        val owner = registeredOwnerOf(registered) ?: return failure("push_authority_changed")
        return apply(owner, null, attempt).map { Unit }
    }

    /**
     * Marks [installation] when it is a registration still uncertain, before the
     * removal meant to replace it can fail: its login is about to be cleared, and
     * then nobody can replay it exactly.
     */
    private suspend fun markOwnerLeaving(installation: PushInstallation) = mutex.withLock {
        val records = read()
        val uncertain = records.firstOrNull { it.serverUrl == installation.serverUrl && it.deviceId == installation.deviceId }
            ?.takeIf { !it.acknowledged && it.intent.registration != null && !it.ownerLeaving } ?: return@withLock
        write(records.map { if (it === uncertain) it.copy(ownerLeaving = true) else it })
    }

    private suspend fun apply(owner: DurableLoginAuthority, request: PushDeviceRegisterRequest?, attempt: Any): ApiResult<PushDeviceRegisterResponse?> =
        mutex.withLock { applyLocked(owner, request, attempt, mayReconcile = true) }

    private suspend fun applyLocked(owner: DurableLoginAuthority, request: PushDeviceRegisterRequest?, attempt: Any,
        mayReconcile: Boolean): ApiResult<PushDeviceRegisterResponse?> {
        try {
            if (!current(owner)) return failure("push_authority_changed")
            if (!isLatest(owner, attempt)) return failure("push_request_superseded")
            val device = request?.deviceId ?: deviceIdProvider()
            if (device.isBlank() || device.length > 128) return failure("invalid_push_device")
            val available = repository.available(owner.scope)
            if (available !is ApiResult.Success || !available.data) return when (available) {
                is ApiResult.Error -> available
                is ApiResult.NetworkError -> available
                else -> failure("push_registration_unavailable")
            }
            if (!current(owner)) return failure("push_authority_changed")
            val records = read()
            if (!current(owner)) return failure("push_authority_changed")
            if (!isLatest(owner, attempt)) return failure("push_request_superseded")
            val matches = records.filter { it.serverUrl == owner.scope.serverUrl && it.deviceId == device }
            check(matches.size <= 1)
            val previous = matches.singleOrNull()
            val intent = PushInstallationIntent(owner.scope.serverId, owner.loginId, requireNotNull(owner.scope.profileId), owner.scope.profileToken, request)
            // An uncertain removal, or an uncertain registration whose login was leaving,
            // is superseded rather than replayed: the server takes a registration at a
            // higher generation whether the uncertain intent landed or never arrived, and
            // the login that sent it is usually gone, as sign-out removes first and then
            // clears it. Any other uncertain registration is still replayed exactly.
            val supersedesUncertainty = request != null && previous != null && !previous.acknowledged &&
                (previous.intent.registration == null || previous.ownerLeaving)
            val reconciling = previous != null && !previous.acknowledged && previous.intent != intent && !supersedesUncertainty
            // A saved login only renews a registration this device still holds there,
            // never revives one: that server may just have been removed or signed out of.
            if (request != null && owner.scope.isGenerationPinned &&
                (previous == null || previous.intent.registration == null || previous.ownerLeaving)) {
                return failure("push_not_registered")
            }
            previous?.let {
                check(it.generation > 0 && it.key.matches(Regex("[A-Za-z0-9_-]{43}")))
                if (it.acknowledged && it.intent.registration != null) {
                    val receipt = checkNotNull(it.receipt)
                    check(receipt.generation == it.generation.toString() && receipt.id.isNotBlank() &&
                        receipt.serverDeviceId.isNotBlank() && receipt.pushMode == it.intent.registration.pushMode)
                }
                if (it.refusal != null) return failure(it.refusal)
                if (reconciling && (!mayReconcile || it.intent.serverId != intent.serverId || it.intent.loginId != intent.loginId ||
                        it.intent.profileId != intent.profileId || it.intent.profileToken != intent.profileToken)) {
                    return failure("push_previous_intent_uncertain")
                }
                if (it.acknowledged && it.intent == intent) return ApiResult.Success(it.receipt)
            }
            val entry = if (previous != null && (previous.intent == intent || reconciling)) previous else {
                if (previous?.generation == Long.MAX_VALUE) return failure("push_generation_exhausted")
                PushInstallation(owner.scope.serverUrl, device, previous?.key ?: newKey(),
                    (previous?.generation ?: 0) + 1, intent)
            }
            check(entry.key.matches(Regex("[A-Za-z0-9_-]{43}")))
            val retained = records.filterNot { it.serverUrl == entry.serverUrl && it.deviceId == entry.deviceId }
            if (!current(owner)) return failure("push_authority_changed")
            // Allocation reaches durable storage before any mutation can leave the process.
            write(retained + entry)
            currentCoroutineContext().ensureActive()
            if (!current(owner)) return failure("push_authority_changed")
            val persistedRequest = entry.intent.registration
            val result: ApiResult<PushDeviceRegisterResponse?> = if (persistedRequest == null) {
                repository.unregisterDevice(device, entry.key, entry.generation, owner.scope).map { null }
            } else repository.registerAndroidDevice(persistedRequest, entry.key, entry.generation, owner.scope)
            if (!current(owner)) return failure("push_authority_changed")
            when (result) {
                is ApiResult.Success -> write(retained + entry.copy(acknowledged = true, receipt = result.data))
                is ApiResult.Error -> if (result.code in setOf(401, 403, 409, 422)) {
                    // No generation rebase or replacement secret after a definite refusal.
                    write(retained + entry.copy(refusal = "push_registration_refused"))
                }
                is ApiResult.NetworkError -> Unit // Exact persisted intent is the only retry candidate.
            }
            if (!current(owner)) return failure("push_authority_changed")
            // At most one exact pending replay precedes the new desired intent. Never
            // substitute a new token, verb, login or generation into that replay.
            return if (reconciling && result is ApiResult.Success) {
                applyLocked(owner, request, attempt, mayReconcile = false)
            } else result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return failure("push_persistence_unavailable")
        }
    }

    private companion object {
        const val UNREGISTER_BUDGET_MS = 8_000L
    }
}
