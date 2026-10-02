package org.siloserver.silo.model.subtitles

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * A stored subtitle's timing correction: original time `t` plays at
 * `t * scale + offsetMs`. The server applies it to every delivery path.
 */
@Serializable
data class SubtitleTiming(
    @SerialName("offset_ms") val offsetMs: Long = 0,
    val scale: Double = 1.0,
) {
    val isIdentity: Boolean get() = offsetMs == 0L && scale == 1.0
}

/** The latest sync job of a stored subtitle. */
@Serializable
data class SubtitleSyncJob(
    val status: String,
    val confidence: Double? = null,
    /** The correction the job found, once it finished. */
    val result: SubtitleTiming? = null,
) {
    val inProgress: Boolean get() = status == PENDING || status == RUNNING

    companion object {
        const val PENDING = "pending"
        const val RUNNING = "running"
        const val SYNCED = "synced"
        const val ALREADY_SYNCED = "already_synced"
        const val NO_MATCH = "no_match"
        const val FAILED = "failed"
    }
}

/** GET /api/v2/subtitles/sync/status: whether this server can align subtitles to audio. */
@Serializable
data class SubtitleSyncCapability(
    val state: String = "",
    @SerialName("auto_sync") val autoSync: Boolean = false,
    val allowed: Boolean = false,
) {
    val available: Boolean get() = state == "available" && allowed
}

/** "+2.3 s" / "−0.4 s"; whole seconds keep one decimal so a list scans evenly. */
fun formatSyncOffset(offsetMs: Long): String {
    val tenths = (abs(offsetMs) + 50) / 100
    val sign = if (offsetMs < 0) "−" else "+"
    return "$sign${tenths / 10}.${tenths % 10} s"
}

private val frameRates = listOf(
    23.976 to "23.976", 24.0 to "24", 25.0 to "25", 29.97 to "29.97",
    30.0 to "30", 50.0 to "50", 59.94 to "59.94", 60.0 to "60",
)

// The server reports a frame-rate conversion as its exact ratio; anything else
// is drift between two cuts, which only looks like a nearby ratio.
private const val FRAME_RATE_TOLERANCE = 1e-6

/**
 * Names a scale correction as the frame-rate conversion it most likely is
 * ("25→23.976 fps"), or as a plain speed factor ("×1.0008 speed"). Null for
 * no scaling. A subtitle cut for source rate `from` stretched onto video rate
 * `to` has scale = from / to.
 */
fun describeSyncScale(scale: Double): String? {
    if (!scale.isFinite() || scale == 1.0) return null
    for ((from, fromLabel) in frameRates) {
        for ((to, toLabel) in frameRates) {
            if (from != to && abs(from / to - scale) <= FRAME_RATE_TOLERANCE) return "$fromLabel→$toLabel fps"
        }
    }
    // Up to four decimals without trailing zeros, like the web player's Number(scale.toFixed(4)).
    val tenThousandths = (scale * 10_000).roundToLong()
    val whole = tenThousandths / 10_000
    val fraction = (tenThousandths % 10_000).toString().padStart(4, '0').trimEnd('0')
    return if (fraction.isEmpty()) "×$whole speed" else "×$whole.$fraction speed"
}

private fun describeTiming(timing: SubtitleTiming): String {
    val scale = describeSyncScale(timing.scale)
    val offset = if (timing.offsetMs != 0L || scale == null) formatSyncOffset(timing.offsetMs) else null
    return listOfNotNull(offset, scale).joinToString(" · ")
}

/**
 * One short line describing a stored subtitle's timing, or null when there is
 * nothing to say (never synced and never adjusted). Matches the web player.
 */
fun subtitleSyncStatusLabel(timing: SubtitleTiming, sync: SubtitleSyncJob?): String? {
    when (sync?.status) {
        SubtitleSyncJob.PENDING, SubtitleSyncJob.RUNNING -> return "Syncing…"
        SubtitleSyncJob.NO_MATCH -> return "Doesn't match this video"
        SubtitleSyncJob.FAILED -> return "Sync failed"
        SubtitleSyncJob.ALREADY_SYNCED -> if (timing.isIdentity) return "Already in sync"
        SubtitleSyncJob.SYNCED -> {
            if (timing.isIdentity) return "Original timing"
            if (sync.result == timing) return "Synced ${describeTiming(timing)}"
        }
    }
    return if (timing.isIdentity) null else "Timing adjusted ${describeTiming(timing)}"
}
