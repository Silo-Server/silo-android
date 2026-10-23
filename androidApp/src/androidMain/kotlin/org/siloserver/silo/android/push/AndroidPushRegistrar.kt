package org.siloserver.silo.android.push

import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.siloserver.silo.common.diagnostics.SiloLog
import org.siloserver.silo.common.diagnostics.SiloLogAttribute
import org.siloserver.silo.model.diagnostics.DiagnosticsLogCategory
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
    /**
     * Guards this device's installation records: each read, and each update of one
     * server's record, as a single read-modify-write. Never held across a request,
     * so marking a leaving login or answering a push never waits on a slow server.
     */
    private val recordsLock = Mutex()
    /** One command at a time per saved server, so its intents reach it in order; servers never wait on each other. */
    private val serverLocks = ConcurrentHashMap<String, Mutex>()
    private fun serverLock(serverUrl: String) = serverLocks.getOrPut(serverUrl) { Mutex() }
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

    /** Last records read or written under [recordsLock], so a push never waits on the store either. */
    @Volatile private var known: List<PushInstallation>? = null
    private suspend fun readLocked() = store.read().also { known = it }

    /** This device's installation records, one per server it has registered with. */
    internal suspend fun installations(): List<PushInstallation> = known ?: recordsLock.withLock { readLocked() }

    private fun List<PushInstallation>.recordOf(serverUrl: String, device: String): PushInstallation? =
        filter { it.serverUrl == serverUrl && it.deviceId == device }.also { check(it.size <= 1) }.singleOrNull()

    /** [serverUrl]'s record for [device], as stored now. */
    private suspend fun recordOf(serverUrl: String, device: String): PushInstallation? =
        recordsLock.withLock { readLocked() }.recordOf(serverUrl, device)

    /** Replaces [serverUrl]'s record for [device] with [change] of it, leaving every other server's as stored. */
    private suspend fun updateRecord(serverUrl: String, device: String, change: (PushInstallation?) -> PushInstallation?) =
        recordsLock.withLock {
            val records = readLocked()
            val stored = records.recordOf(serverUrl, device)
            val updated = change(stored)
            if (updated !== stored) {
                val next = records.filterNot { it === stored } + listOfNotNull(updated)
                store.write(next)
                known = next
            }
        }

    private fun failure(code: String) = ApiResult.Error(0, code, "Push registration requires its original installation and login authority.")

    suspend fun registerIfAvailable(): ApiResult<PushDeviceRegisterResponse>? {
        val owner = capture() ?: return null
        val attempt = beginRequest(owner.scope.serverId)
        val token = tokenProvider.token()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (!current(owner)) return failure("push_authority_changed")
        return register(token, owner, attempt)
    }
    /**
     * Registers [token] with the active server for [owner], the active login the
     * caller captured (it must still be active when the registration applies),
     * or whoever is active now.
     */
    suspend fun registerToken(token: String, owner: DurableLoginAuthority? = null): ApiResult<PushDeviceRegisterResponse> {
        val active = owner ?: capture() ?: return failure("push_authority_changed")
        return register(token.trim(), active, beginRequest(active.scope.serverId))
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
        // For whoever was active when the rotation began: should that change first,
        // the attempt stops, and the server is brought up to the new token below
        // like every other one that does not have it yet.
        capture()?.let { registerToken(rotated, it) }
        registerOtherServers(rotated, exceptServerId = null)
    }

    /**
     * Brings every saved server but the active one up to the current FCM token,
     * as the login and profile each was registered for: whatever a rotation ran
     * out of time for, or left uncertain, is finished here.
     */
    suspend fun registerOtherServersIfBehind() {
        val token = tokenProvider.token()?.trim()?.takeIf { it.isNotEmpty() } ?: return
        registerOtherServers(token, exceptServerId = capture()?.scope?.serverId)
    }

    /** Each server's attempt is bounded on its own, so one that fails or stalls costs only itself. */
    private suspend fun registerOtherServers(token: String, exceptServerId: String?) {
        val device = deviceIdProvider()
        val behind = installations().filter {
            val registration = it.intent.registration
            it.deviceId == device && it.intent.serverId != exceptServerId && it.refusal == null &&
                registration != null && (registration.token != token || !it.acknowledged)
        }
        for (installation in behind) {
            withTimeoutOrNull(PUSH_WORK_BUDGET_MS) { registerAsRegisteredOwner(installation, token) }
        }
    }

    private suspend fun registerAsRegisteredOwner(behind: PushInstallation, token: String): ApiResult<PushDeviceRegisterResponse?> {
        if (token.length !in 64..512) return failure("invalid_push_token")
        // Joins that server's newest operation rather than starting a newer one:
        // bringing a token up to date must never supersede a removal under way there.
        val attempt = latestRequests.getOrPut(behind.intent.serverId) { Any() }
        return serverLock(behind.serverUrl).withLock {
            // Renews only the registration found behind, never one a login on its way
            // out holds. Anything that replaced it since (another profile, a newer
            // token, a removal) leaves this stale.
            val record = recordOf(behind.serverUrl, behind.deviceId)
            if (record == null || record.generation != behind.generation || record.intent != behind.intent || record.ownerLeaving) {
                return@withLock failure("push_registration_changed")
            }
            val owner = registeredOwnerOf(record) ?: return@withLock failure("push_authority_changed")
            applyOnServer(owner, checkNotNull(record.intent.registration).copy(token = token), attempt, mayReconcile = true)
        }
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
     * server's credentials are cleared. The record stays, as the server binds its
     * key to this device; a removal intent is no push target.
     *
     * The removal takes the record's next generation and is persisted as a
     * pending retirement before anything is sent. A retirement needs only the
     * installation key, so it needs no login and is sent first, for at most
     * [RETIREMENT_FIRST_ATTEMPT_MS]. A server without the retirement route (404 or
     * 405), or one that has not answered it, also gets the session removal, at the
     * same key and generation, as the login and profile it was registered for.
     * A retirement the server does not answer for good stays queued and
     * [retryPendingRetirements] sends it again after the login is gone.
     *
     * Bounded so an unreachable server cannot hold up the sign-out or removal
     * waiting on it, and never throws, so unreadable records cannot abort it
     * either.
     */
    suspend fun unregisterDevice(serverId: String): PushRemoval {
        val attempt = beginRequest(serverId)
        val result = try {
            withTimeoutOrNull(UNREGISTER_BUDGET_MS) { removeAsRegisteredOwner(serverId, attempt) } ?: failure("push_unregister_timed_out")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failure("push_persistence_unavailable")
        }
        return PushRemoval(serverId, attempt, result)
    }

    /**
     * Undoes [removal] after the sign-out or server removal that made it stopped,
     * leaving that server's login in place: drops its pending retirement and
     * registers again as the login and profile it was registered for, with the
     * current token, at a newer generation that a retirement already sent loses
     * to. Only that removal is undone, and only while it is still the newest
     * operation on that server,
     * so a later removal (another sign-out, the server's removal) stands; and
     * nothing happens once that login is gone, so a removal whose login really
     * was cleared stays removed. Bounded and never throws, like [unregisterDevice].
     */
    suspend fun restoreRegistration(removal: PushRemoval): ApiResult<PushDeviceRegisterResponse?> = try {
        withTimeoutOrNull(UNREGISTER_BUDGET_MS) { restoreAsRegisteredOwner(removal) } ?: failure("push_restore_timed_out")
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        failure("push_persistence_unavailable")
    }

    private suspend fun restoreAsRegisteredOwner(removal: PushRemoval): ApiResult<PushDeviceRegisterResponse?> {
        val serverId = removal.serverId
        // Continues the removal's own operation instead of starting one, so anything
        // started on that server since supersedes this, here or under its lock.
        val attempt = removal.attempt
        if (latestRequests[serverId] !== attempt) return failure("push_request_superseded")
        val device = deviceIdProvider()
        val found = installations().firstOrNull { it.intent.serverId == serverId && it.deviceId == device && it.refusal == null }
            ?: return failure("push_not_registered")
        return serverLock(found.serverUrl).withLock {
            val record = recordOf(found.serverUrl, device)?.takeIf { it.intent.serverId == serverId && it.refusal == null }
                ?: return@withLock failure("push_not_registered")
            val owner = registeredOwnerOf(record) ?: return@withLock failure("push_authority_changed")
            // A removal started while this waited owns the retirement now.
            if (latestRequests[serverId] !== attempt) return@withLock failure("push_request_superseded")
            // The server stays saved and signed in, so its retirement must not go out.
            cancelRetirement(record, "restored")
            val token = tokenProvider.token()?.trim()?.takeIf { it.length in 64..512 }
                ?: return@withLock failure("invalid_push_token")
            // The registration it held, or the one register() sends, with the current token.
            val request = record.intent.registration?.copy(token = token) ?: PushDeviceRegisterRequest("android", token, device)
            applyOnServer(owner, request, attempt, mayReconcile = true)
        }
    }

    private suspend fun removeAsRegisteredOwner(serverId: String, attempt: Any): ApiResult<Unit> {
        val device = deviceIdProvider()
        val found = installations().firstOrNull { it.intent.serverId == serverId && it.deviceId == device && it.removable }
            ?: return failure("push_not_registered")
        // Before waiting on anything: should the removal not finish, whatever this
        // record then holds belongs to a login about to be cleared.
        markOwnerLeaving(found.serverUrl, device)
        return serverLock(found.serverUrl).withLock {
            if (latestRequests[serverId] !== attempt) return@withLock failure("push_request_superseded")
            // Read again under the server's lock, as a registration finishing first
            // changes the record: the removal is allocated over whatever it left.
            val retiring = queueRetirement(found.serverUrl, device, serverId) ?: return@withLock failure("push_not_registered")
            // Capped, so a slow server still leaves the session removal time to run
            // before the credentials are cleared. One cut off here stays queued.
            val retired = withTimeoutOrNull(RETIREMENT_FIRST_ATTEMPT_MS) { sendRetirement(retiring) }
            if (retired == null) noteRetirementFailure(retiring.serverUrl)
            if (retired != null && retired.outcome in setOf(RetirementOutcome.RETIRED, RetirementOutcome.DROPPED)) {
                return@withLock retired.result
            }
            // A server without the retirement route, or one that did not answer it,
            // gets the session removal while the login still exists, at the same
            // generation, from the profile that registered: the server refuses one
            // from any other profile, for good. Unless that removal lands, the
            // retirement stays queued and is sent again after the login is gone.
            val unanswered = retired?.result ?: failure("push_retirement_unanswered")
            val owner = registeredOwnerOf(retiring) ?: return@withLock unanswered
            val removed = applyOnServer(owner, null, attempt, mayReconcile = false).map { Unit }
            if (removed is ApiResult.Success) recordRetirement("cancelled", "session_removed", retiring.generation)
            removed
        }
    }

    /**
     * Allocates the removal of [serverUrl]'s registration for [device] and queues
     * it as a retirement, in one write, before anything is sent. A removal not yet
     * confirmed keeps its generation, so it is replayed exactly; a registration,
     * confirmed or not, is superseded at the next generation, which the server
     * orders above it whether or not it ever arrived. Null when nothing is left to
     * remove.
     */
    private suspend fun queueRetirement(serverUrl: String, device: String, serverId: String): PushInstallation? {
        var queued: PushInstallation? = null
        updateRecord(serverUrl, device) { stored ->
            if (stored == null || stored.intent.serverId != serverId || !stored.removable) return@updateRecord stored
            val at = clock()
            val next = if (stored.intent.registration == null) {
                // The lifetime counts from the first queueing.
                stored.copy(ownerLeaving = true, retirement = PushRetirement(stored.generation, stored.retirement?.queuedAtMs ?: at))
            } else {
                check(stored.generation < Long.MAX_VALUE) { "push_generation_exhausted" }
                val generation = stored.generation + 1
                stored.copy(generation = generation, intent = stored.intent.copy(registration = null), acknowledged = false,
                    receipt = null, ownerLeaving = true, retirement = PushRetirement(generation, at))
            }
            queued = next
            next
        }
        if (queued != null) retirementFailures.remove(serverUrl)
        return queued
    }

    /**
     * Sends the retirements still queued for removed or signed-out servers whose
     * back-off has passed, a few at a time, and queues one for a removal that
     * never got that far once its login is gone. They need no login, so this runs
     * on app start and foreground, before anyone signs in, and when a push arrives:
     * [afterPush] skips the back-off, as a push from a retired server is the sign
     * it is still sending them. Never throws.
     */
    suspend fun retryPendingRetirements(afterPush: Boolean = false) {
        val pending = try {
            installations().filter { it.retirement != null || it.unqueuedRemoval }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return
        }
        val at = clock()
        val due = pending.filter { record ->
            val failed = retirementFailures[record.serverUrl] ?: return@filter true
            at - failed.atMs >= if (afterPush) RETIREMENT_MIN_RETRY_MS else retirementBackOffMs(failed.count)
        }
        if (due.isEmpty()) return
        coroutineScope {
            for (record in due) launch {
                retirementPermits.withPermit {
                    // Out of time is a failure like any other, so the next push waits out the back-off.
                    withTimeoutOrNull(PUSH_WORK_BUDGET_MS) { retryRetirement(record) } ?: noteRetirementFailure(record.serverUrl)
                }
            }
        }
    }

    private suspend fun retryRetirement(found: PushInstallation) {
        val lock = serverLock(found.serverUrl)
        // Another command on that server is under way: it settles the record
        // (a registration supersedes the retirement), or the next pass retries.
        if (!lock.tryLock()) return
        try {
            val record = recordOf(found.serverUrl, found.deviceId) ?: return
            val retirement = record.retirement
            if (retirement == null) {
                // A removal that ran out of time before queueing its retirement, or one
                // an older version could not send: with the login gone, nothing else will.
                if (!record.unqueuedRemoval || registeredOwnerOf(record) != null) return
                queueRetirement(record.serverUrl, record.deviceId, record.intent.serverId)?.let { sendRetirement(it) }
                return
            }
            if (record.generation != retirement.generation || record.intent.registration != null) {
                cancelRetirement(record, "superseded")
                return
            }
            sendRetirement(record)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Unreadable records: nothing was settled, so the entry stays queued.
        } finally {
            lock.unlock()
        }
    }

    private enum class RetirementOutcome { RETIRED, DROPPED, UNSUPPORTED, KEPT }
    private class SentRetirement(val outcome: RetirementOutcome, val result: ApiResult<Unit>)
    private class RetirementFailure(val count: Int, val atMs: Long)
    /** Per server URL, in memory: failed retirements in a row and when the last one ran. A restart starts over. */
    private val retirementFailures = ConcurrentHashMap<String, RetirementFailure>()
    /** Shared by every pass, so a foreground and a push retrying at once still send only a few. */
    private val retirementPermits = Semaphore(RETIREMENT_CONCURRENCY)
    private val clock: () -> Long = System::currentTimeMillis

    /**
     * Sends [record]'s queued retirement; the caller holds that server's lock.
     *
     * - 200 naming the generation: the server holds no registration for this
     *   device at or below it. The removal is confirmed and the entry dropped.
     * - 403 (another key), 409 (a newer generation: the server was registered
     *   again), or another refusal of the request itself: dropped, as the same
     *   request gets the same answer.
     * - 404 or 405: a server without the route. Kept, and dropped once queued
     *   [RETIREMENT_UNSUPPORTED_LIFETIME_MS] ago.
     * - No answer, 5xx, 429 and the rest: kept for a later attempt after a
     *   back-off, and dropped unsent once queued [RETIREMENT_LIFETIME_MS] ago.
     */
    private suspend fun sendRetirement(record: PushInstallation): SentRetirement {
        val retirement = checkNotNull(record.retirement)
        val age = clock() - retirement.queuedAtMs
        if (age >= RETIREMENT_LIFETIME_MS) {
            return settleRetirement(record, RetirementOutcome.DROPPED, "expired", failure("push_retirement_expired"))
        }
        val result = repository.retireDevice(record.serverUrl, record.deviceId, record.key, retirement.generation)
        val (outcome, reason) = when (result) {
            is ApiResult.Success -> RetirementOutcome.RETIRED to null
            is ApiResult.Error -> when {
                result.code in RETIREMENT_REFUSALS -> RetirementOutcome.DROPPED to "http_${result.code}"
                result.code in RETIREMENT_UNSUPPORTED && age >= RETIREMENT_UNSUPPORTED_LIFETIME_MS ->
                    RetirementOutcome.DROPPED to "unsupported_expired"
                result.code in RETIREMENT_UNSUPPORTED -> RetirementOutcome.UNSUPPORTED to "http_${result.code}"
                // A 200 that does not name this generation: sending it again changes nothing.
                result.code == 0 && result.error == "invalid_response" -> RetirementOutcome.DROPPED to "invalid_receipt"
                else -> RetirementOutcome.KEPT to (if (result.code == 0) result.error else "http_${result.code}")
            }
            is ApiResult.NetworkError -> RetirementOutcome.KEPT to "network"
        }
        return settleRetirement(record, outcome, reason, result)
    }

    private suspend fun settleRetirement(record: PushInstallation, outcome: RetirementOutcome, reason: String?,
        result: ApiResult<Unit>): SentRetirement {
        when (outcome) {
            RetirementOutcome.RETIRED, RetirementOutcome.DROPPED -> {
                retirementFailures.remove(record.serverUrl)
                updateRecord(record.serverUrl, record.deviceId) { stored ->
                    stored?.takeIf { it.generation == record.generation && it.key == record.key && it.retirement != null }?.let {
                        if (outcome == RetirementOutcome.RETIRED) {
                            it.copy(acknowledged = true, receipt = null, ownerLeaving = false, retirement = null)
                        } else {
                            // Settled for good: no later retry may take it for a removal never queued.
                            it.copy(ownerLeaving = false, retirement = null)
                        }
                    } ?: stored
                }
            }
            RetirementOutcome.UNSUPPORTED, RetirementOutcome.KEPT -> noteRetirementFailure(record.serverUrl)
        }
        recordRetirement(outcome.name.lowercase(), reason, record.generation)
        return SentRetirement(outcome, result)
    }

    private fun noteRetirementFailure(serverUrl: String) {
        retirementFailures.compute(serverUrl) { _, failed -> RetirementFailure((failed?.count ?: 0) + 1, clock()) }
    }

    /** Drops [record]'s queued retirement while the store still holds it at that generation. */
    private suspend fun cancelRetirement(record: PushInstallation, reason: String) {
        if (record.retirement == null) return
        retirementFailures.remove(record.serverUrl)
        updateRecord(record.serverUrl, record.deviceId) { stored ->
            stored?.takeIf { it.generation == record.generation && it.retirement != null }?.copy(retirement = null) ?: stored
        }
        recordRetirement("cancelled", reason, record.generation)
    }

    /** One breadcrumb per outcome, so a report can say why a removed server's pushes kept arriving. */
    private fun recordRetirement(outcome: String, reason: String?, generation: Long) {
        val attributes = buildMap<String, SiloLogAttribute> {
            put("outcome", SiloLogAttribute.Text(outcome))
            reason?.let { put("reason", SiloLogAttribute.Text(it)) }
        }
        SiloLog.breadcrumb(DiagnosticsLogCategory.LIFECYCLE, "PushRetire", "push retirement $outcome at generation $generation", attributes)
    }

    /**
     * A registration, or a removal not yet confirmed, which is then sent again
     * exactly as persisted: one cut off before it reached the server must not
     * be taken for done.
     */
    private val PushInstallation.removable: Boolean
        get() = refusal == null && (intent.registration != null || !acknowledged)

    /** A removal began here ([markOwnerLeaving]) but queued no retirement. */
    private val PushInstallation.unqueuedRemoval: Boolean
        get() = ownerLeaving && retirement == null && removable

    /**
     * Marks [serverUrl]'s record as belonging to a login on its way out, so the
     * next registration may supersede whatever it leaves uncertain: once that
     * login is cleared, nobody can replay it exactly.
     */
    private suspend fun markOwnerLeaving(serverUrl: String, device: String) =
        updateRecord(serverUrl, device) { record -> record?.takeUnless { it.ownerLeaving }?.copy(ownerLeaving = true) ?: record }

    /**
     * A record acknowledged for a login that is still current is nobody's leftover:
     * catching up a rotated token must reach that server again.
     */
    private suspend fun clearOwnerLeaving(serverUrl: String, device: String, generation: Long) =
        updateRecord(serverUrl, device) { record -> record?.takeIf { it.generation == generation }?.copy(ownerLeaving = false) ?: record }

    private suspend fun apply(owner: DurableLoginAuthority, request: PushDeviceRegisterRequest?, attempt: Any): ApiResult<PushDeviceRegisterResponse?> =
        serverLock(owner.scope.serverUrl).withLock { applyOnServer(owner, request, attempt, mayReconcile = true) }

    /** Applies one intent to [owner]'s server; the caller holds that server's lock. */
    private suspend fun applyOnServer(owner: DurableLoginAuthority, request: PushDeviceRegisterRequest?, attempt: Any,
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
            val serverUrl = owner.scope.serverUrl
            val previous = recordOf(serverUrl, device)
            if (!current(owner)) return failure("push_authority_changed")
            if (!isLatest(owner, attempt)) return failure("push_request_superseded")
            val intent = PushInstallationIntent(owner.scope.serverId, owner.loginId, requireNotNull(owner.scope.profileId), owner.scope.profileToken, request)
            // An uncertain removal, or an uncertain registration whose login was leaving,
            // is superseded rather than replayed: the server takes a registration at a
            // higher generation whether the uncertain intent landed or never arrived, and
            // the login that sent it is usually gone, as sign-out removes first and then
            // clears it. Any other uncertain registration is still replayed exactly.
            val supersedesUncertainty = request != null && previous != null && !previous.acknowledged &&
                (previous.intent.registration == null || previous.ownerLeaving)
            val reconciling = previous != null && !previous.acknowledged && previous.intent != intent && !supersedesUncertainty
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
                if (it.acknowledged && it.intent == intent) {
                    if (it.ownerLeaving) clearOwnerLeaving(serverUrl, device, it.generation)
                    return ApiResult.Success(it.receipt)
                }
            }
            val entry = if (previous != null && (previous.intent == intent || reconciling)) previous else {
                if (previous?.generation == Long.MAX_VALUE) return failure("push_generation_exhausted")
                PushInstallation(owner.scope.serverUrl, device, previous?.key ?: newKey(),
                    (previous?.generation ?: 0) + 1, intent)
            }
            check(entry.key.matches(Regex("[A-Za-z0-9_-]{43}")))
            if (!current(owner)) return failure("push_authority_changed")
            // Allocation reaches durable storage before any mutation can leave the process.
            // A login marked leaving meanwhile stays marked on the intent it allocates next.
            if (entry !== previous) {
                updateRecord(serverUrl, device) { stored ->
                    entry.copy(ownerLeaving = stored?.ownerLeaving == true && stored.intent.loginId == entry.intent.loginId)
                }
                // The server, added or signed in to again, orders this generation above
                // the pending retirement, which the record no longer holds.
                if (previous?.retirement != null) recordRetirement("cancelled", "registered", previous.generation)
            }
            currentCoroutineContext().ensureActive()
            if (!current(owner)) return failure("push_authority_changed")
            val persistedRequest = entry.intent.registration
            val result: ApiResult<PushDeviceRegisterResponse?> = if (persistedRequest == null) {
                repository.unregisterDevice(device, entry.key, entry.generation, owner.scope).map { null }
            } else repository.registerAndroidDevice(persistedRequest, entry.key, entry.generation, owner.scope)
            if (!current(owner)) return failure("push_authority_changed")
            suspend fun settle(outcome: (PushInstallation) -> PushInstallation) = updateRecord(serverUrl, device) { stored ->
                stored?.takeIf { it.generation == entry.generation && it.key == entry.key }?.let(outcome) ?: stored
            }
            when (result) {
                is ApiResult.Success -> settle { it.copy(acknowledged = true, receipt = result.data, ownerLeaving = false, retirement = null) }
                is ApiResult.Error -> if (result.code in setOf(401, 403, 409, 422)) {
                    // No generation rebase or replacement secret after a definite refusal.
                    settle { it.copy(refusal = "push_registration_refused") }
                }
                is ApiResult.NetworkError -> Unit // Exact persisted intent is the only retry candidate.
            }
            if (!current(owner)) return failure("push_authority_changed")
            // At most one exact pending replay precedes the new desired intent. Never
            // substitute a new token, verb, login or generation into that replay.
            return if (reconciling && result is ApiResult.Success) {
                applyOnServer(owner, request, attempt, mayReconcile = false)
            } else result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return failure("push_persistence_unavailable")
        }
    }

    private companion object {
        const val UNREGISTER_BUDGET_MS = 8_000L
        /** The removal's first retirement attempt, leaving the session removal most of [UNREGISTER_BUDGET_MS]. */
        const val RETIREMENT_FIRST_ATTEMPT_MS = 2_000L
        const val DAY_MS = 24 * 60 * 60 * 1000L
        /** The contract's limit for a server without the retirement route. */
        const val RETIREMENT_UNSUPPORTED_LIFETIME_MS = 30 * DAY_MS
        /** A server that never answers is not tried forever. */
        const val RETIREMENT_LIFETIME_MS = 90 * DAY_MS
        const val RETIREMENT_CONCURRENCY = 3
        /** The first retry waits this long, doubling per failure up to [RETIREMENT_MAX_BACK_OFF_MS]. */
        const val RETIREMENT_INITIAL_BACK_OFF_MS = 30_000L
        const val RETIREMENT_MAX_BACK_OFF_MS = 6 * 60 * 60 * 1000L
        /** A push skips the back-off, but not this. */
        const val RETIREMENT_MIN_RETRY_MS = 30_000L
        val RETIREMENT_REFUSALS = setOf(400, 403, 409, 413, 415, 422)
        val RETIREMENT_UNSUPPORTED = setOf(404, 405)
        fun retirementBackOffMs(failures: Int): Long =
            (RETIREMENT_INITIAL_BACK_OFF_MS shl (failures - 1).coerceIn(0, 20)).coerceAtMost(RETIREMENT_MAX_BACK_OFF_MS)
    }
}

/** One removal [AndroidPushRegistrar.unregisterDevice] made, which [AndroidPushRegistrar.restoreRegistration] can undo. */
class PushRemoval internal constructor(
    val serverId: String,
    internal val attempt: Any,
    val result: ApiResult<Unit>,
)

/**
 * Runs a push registration step for a sign-out or server removal, which carries
 * on whatever the step throws. Cancellation still propagates and stops it.
 */
internal suspend fun tolerateFailure(step: suspend () -> Unit) {
    try {
        step()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
    }
}
