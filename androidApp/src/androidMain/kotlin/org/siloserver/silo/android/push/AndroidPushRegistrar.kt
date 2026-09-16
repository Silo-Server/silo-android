package org.siloserver.silo.android.push

import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val latestRequest = AtomicReference<Any>()
    private fun beginRequest(): Any = Any().also(latestRequest::set)
    private suspend fun capture() = authorities.snapshotDurableLoginAuthority()?.takeIf {
        it.loginId.isNotBlank() && !it.scope.profileId.isNullOrBlank() && it.scope.credentialGenerationId == null
    }
    private suspend fun current(owner: DurableLoginAuthority) = owner == capture()
    private fun failure(code: String) = ApiResult.Error(0, code, "Push registration requires its original installation and login authority.")

    suspend fun registerIfAvailable(): ApiResult<PushDeviceRegisterResponse>? {
        val attempt = beginRequest()
        val owner = capture() ?: return null
        val token = tokenProvider.token()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (!current(owner)) return failure("push_authority_changed")
        return register(token, owner, attempt)
    }
    suspend fun registerToken(token: String): ApiResult<PushDeviceRegisterResponse> {
        val attempt = beginRequest()
        val owner = capture() ?: return failure("push_authority_changed")
        return register(token.trim(), owner, attempt)
    }
    private suspend fun register(token: String, owner: DurableLoginAuthority, attempt: Any): ApiResult<PushDeviceRegisterResponse> {
        if (token.length !in 64..512) return failure("invalid_push_token")
        val result = apply(owner, PushDeviceRegisterRequest("android", token, deviceIdProvider()), attempt)
        return result.map { checkNotNull(it) }
    }
    suspend fun unregisterDevice(): ApiResult<Unit> {
        val attempt = beginRequest()
        val owner = capture() ?: return failure("push_authority_changed")
        return apply(owner, null, attempt).map { Unit }
    }

    private suspend fun apply(owner: DurableLoginAuthority, request: PushDeviceRegisterRequest?, attempt: Any): ApiResult<PushDeviceRegisterResponse?> =
        mutex.withLock { applyLocked(owner, request, attempt, mayReconcile = true) }

    private suspend fun applyLocked(owner: DurableLoginAuthority, request: PushDeviceRegisterRequest?, attempt: Any,
        mayReconcile: Boolean): ApiResult<PushDeviceRegisterResponse?> {
        try {
            if (!current(owner)) return failure("push_authority_changed")
            if (latestRequest.get() !== attempt) return failure("push_request_superseded")
            val device = request?.deviceId ?: deviceIdProvider()
            if (device.isBlank() || device.length > 128) return failure("invalid_push_device")
            val available = repository.available(owner.scope)
            if (available !is ApiResult.Success || !available.data) return when (available) {
                is ApiResult.Error -> available
                is ApiResult.NetworkError -> available
                else -> failure("push_registration_unavailable")
            }
            if (!current(owner)) return failure("push_authority_changed")
            val records = store.read()
            if (!current(owner)) return failure("push_authority_changed")
            if (latestRequest.get() !== attempt) return failure("push_request_superseded")
            val matches = records.filter { it.serverUrl == owner.scope.serverUrl && it.deviceId == device }
            check(matches.size <= 1)
            val previous = matches.singleOrNull()
            val intent = PushInstallationIntent(owner.scope.serverId, owner.loginId, requireNotNull(owner.scope.profileId), owner.scope.profileToken, request)
            val reconciling = previous != null && !previous.acknowledged && previous.intent != intent
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
            store.write(retained + entry)
            currentCoroutineContext().ensureActive()
            if (!current(owner)) return failure("push_authority_changed")
            val persistedRequest = entry.intent.registration
            val result: ApiResult<PushDeviceRegisterResponse?> = if (persistedRequest == null) {
                repository.unregisterDevice(device, entry.key, entry.generation, owner.scope).map { null }
            } else repository.registerAndroidDevice(persistedRequest, entry.key, entry.generation, owner.scope)
            if (!current(owner)) return failure("push_authority_changed")
            when (result) {
                is ApiResult.Success -> store.write(retained + entry.copy(acknowledged = true, receipt = result.data))
                is ApiResult.Error -> if (result.code in setOf(401, 403, 409, 422)) {
                    // No generation rebase or replacement secret after a definite refusal.
                    store.write(retained + entry.copy(refusal = "push_registration_refused"))
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
}
