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

/** One instance per logical consumer/engagement; opening a socket does not reset it. */
class RealtimeBackoff(
    private val nowMs: () -> Long = { origin.elapsedNow().inWholeMilliseconds },
    private val jitterMs: () -> Long = { Random.nextLong(251) },
) {
    private var opaque = 0
    private var unavailable = 0
    private var shield = 0
    private var usefulSince: Long? = null
    var overloaded: Boolean = false
        private set

    fun useful() {
        val now = nowMs()
        val since = usefulSince ?: now.also { usefulSince = it }
        if (now - since >= 30_000) {
            opaque = 0; unavailable = 0; shield = 0; overloaded = false
        }
    }

    fun refused(code: String, minimumMs: Long): Long {
        usefulSince = null
        overloaded = true
        val base = if (code == "realtime_unavailable") {
            UNAVAILABLE[(unavailable++).coerceAtMost(UNAVAILABLE.lastIndex)]
        } else 0L
        return maxOf(minimumMs.coerceAtLeast(250), base) + jitterMs().coerceIn(0, 250)
    }

    fun disconnected(minimumMs: Long = 0, unavailableFailure: Boolean = false, inputShield: Boolean = false): Long {
        usefulSince = null
        if (inputShield) {
            overloaded = true
            return maxOf(minimumMs, SHIELD[(shield++).coerceAtMost(SHIELD.lastIndex)]) + jitterMs().coerceIn(0, 250)
        }
        if (unavailableFailure) return refused("realtime_unavailable", minimumMs)
        return maxOf(minimumMs, OPAQUE[(opaque++).coerceAtMost(OPAQUE.lastIndex)]) + jitterMs().coerceIn(0, 250)
    }

    companion object {
        private val origin = TimeSource.Monotonic.markNow()
        private val OPAQUE = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L)
        private val UNAVAILABLE = listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L)
        private val SHIELD = listOf(5_000L, 10_000L, 20_000L, 30_000L)
    }
}
