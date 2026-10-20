package org.siloserver.silo.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.random.Random
import kotlin.time.TimeSource

/** Additive v2 capability. Unknown versions retain the existing client behaviour. */
@Serializable
data class RealtimeWorkAdmission(
    val version: Int = 0,
    val scope: String = "",
    val backend: String = "",
    val epoch: String = "",
) {
    val supported: Boolean get() = version == 1
}

@Serializable
data class RealtimeRefusal(
    val code: String = "",
    val message: String = "",
    @SerialName("request_id") val requestId: String? = null,
    @SerialName("retry_after_ms") val retryAfterMs: Long? = null,
    @SerialName("work_started") val workStarted: Boolean? = null,
) {
    val preWork: Boolean get() = workStarted == false &&
        code in setOf("realtime_rate_limited", "realtime_unavailable") && retryAfterMs != null && retryAfterMs >= 0
}

/** Opaque delivery failures never say that a one-shot operation is safe to replay. */
internal class RealtimeSocketClosed(val code: Short?, val reason: String?) : Exception("realtime_socket_closed") {
    val inputShield: Boolean get() = code?.toInt() == 1008 && reason == "realtime input shield"
}

/** Server plus login incarnation, deliberately independent of profile and refreshed credentials. */
data class RealtimeAdmissionOwner(val serverId: String, val serverUrl: String, val loginId: String? = null) {
    override fun toString(): String = "RealtimeAdmissionOwner(<redacted>)"
}

/** Capture the durable marker atomically; request scope equality remains a separate fence. */
internal suspend fun TokenManager.realtimeAdmissionOwner(scope: AuthScopeSnapshot): RealtimeAdmissionOwner? {
    val temporary = scope.credentialGenerationId
    if (temporary != null) return RealtimeAdmissionOwner(scope.serverId, scope.serverUrl, "temporary:$temporary")
    val authority = (this as? DurableLoginAuthorityProvider)?.snapshotDurableLoginAuthority()
    val login = if (authority != null) {
        if (authority.scope != scope) return null
        authority.loginId
    } else {
        // The durable profile authority may be absent before profile selection.
        // Capture the saved account marker between two identical request scopes.
        val marker = loginSessionId(scope.serverId)
        if (snapshotCurrentScope() != scope) return null
        marker // legacy/in-memory implementations may have no durable marker
    }
    return RealtimeAdmissionOwner(scope.serverId, scope.serverUrl, login)
}

/** One instance per logical consumer/engagement; opening a socket does not reset it. */
class RealtimeBackoff(
    private val nowMs: () -> Long = { origin.elapsedNow().inWholeMilliseconds },
    private val jitterMs: () -> Long = { Random.nextLong(251) },
) {
    private var opaque = 0
    private var unavailable = 0
    private var shield = 0
    private var usefulSince: Long? = null
    private var admissionNotBeforeMs = 0L
    private var admissionOwner: RealtimeAdmissionOwner? = null

    internal fun bindAdmissionOwner(owner: RealtimeAdmissionOwner) {
        if (admissionOwner != null && admissionOwner != owner) {
            admissionNotBeforeMs = 0
            opaque = 0; unavailable = 0; shield = 0; usefulSince = null; overloaded = false
        }
        admissionOwner = owner
    }

    internal fun remainingAdmissionMs(): Long = (admissionNotBeforeMs - nowMs()).coerceAtLeast(0)
    var overloaded: Boolean = false
        private set

    fun useful() {
        val now = nowMs()
        if (usefulSince == null) usefulSince = now
        resetIfHealthy(now)
    }

    private fun resetIfHealthy(now: Long) {
        val since = usefulSince ?: return
        if (now - since >= 30_000) {
            opaque = 0; unavailable = 0; shield = 0; overloaded = false
        }
    }

    /** Quiet accepted uptime qualifies too; no health carries across physical attempts. */
    internal fun endAttempt() {
        resetIfHealthy(nowMs())
        usefulSince = null
    }

    internal fun markOverloaded() {
        resetIfHealthy(nowMs())
        usefulSince = null
        overloaded = true
    }

    fun refused(code: String, minimumMs: Long, retainForAdmission: Boolean = false): Long {
        markOverloaded()
        val base = if (code == "realtime_unavailable") {
            UNAVAILABLE[(unavailable++).coerceAtMost(UNAVAILABLE.lastIndex)]
        } else 0L
        val wait = maxOf(minimumMs.coerceAtLeast(250), base) + jitterMs().coerceIn(0, 250)
        if (retainForAdmission) admissionNotBeforeMs = maxOf(admissionNotBeforeMs, nowMs() + wait)
        return wait
    }

    fun disconnected(minimumMs: Long = 0, unavailableFailure: Boolean = false, inputShield: Boolean = false): Long {
        endAttempt()
        if (inputShield) {
            overloaded = true
            return maxOf(remainingAdmissionMs(), minimumMs, SHIELD[(shield++).coerceAtMost(SHIELD.lastIndex)]) + jitterMs().coerceIn(0, 250)
        }
        if (unavailableFailure) return maxOf(remainingAdmissionMs(), refused("realtime_unavailable", minimumMs))
        return maxOf(remainingAdmissionMs(), minimumMs, OPAQUE[(opaque++).coerceAtMost(OPAQUE.lastIndex)]) + jitterMs().coerceIn(0, 250)
    }

    companion object {
        private val origin = TimeSource.Monotonic.markNow()
        private val OPAQUE = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L)
        private val UNAVAILABLE = listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L)
        private val SHIELD = listOf(5_000L, 10_000L, 20_000L, 30_000L)
    }
}
