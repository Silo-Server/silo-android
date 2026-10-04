package org.siloserver.silo.model.subtitles

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * A subtitle's timing correction: original time `t` plays at
 * `t * scale + offsetMs`. The server applies it to every delivery path.
 */
@Serializable
data class SubtitleTiming(
    @SerialName("offset_ms") val offsetMs: Long = 0,
    val scale: Double = 1.0,
) {
    val isIdentity: Boolean get() = offsetMs == 0L && scale == 1.0
}

/** A subtitle's latest sync job (`SubtitleSyncJobState`). */
@Serializable
data class SubtitleSyncJob(
    val id: String = "",
    val status: String,
    /** `auto` for the sync of a new download or upload, `manual` for one someone asked for. */
    val trigger: String? = null,
    /** What an active job is doing: `queued`, `analyzing`, or `matching`. */
    val phase: String? = null,
    /** 0..1 while the job is active. */
    val progress: Double? = null,
    /** Why a failed job failed: `subtitle_changed`, `no_audio`, `unavailable`, or `error`. */
    val failure: String? = null,
    val confidence: Double? = null,
    /** The correction the job found, once it finished. */
    val result: SubtitleTiming? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("finished_at") val finishedAt: String? = null,
) {
    val inProgress: Boolean get() = status == PENDING || status == RUNNING

    companion object {
        const val PENDING = "pending"
        const val RUNNING = "running"
        const val SYNCED = "synced"
        const val ALREADY_SYNCED = "already_synced"
        const val NO_MATCH = "no_match"
        const val FAILED = "failed"

        const val PHASE_ANALYZING = "analyzing"
        const val PHASE_MATCHING = "matching"

        const val FAILURE_SUBTITLE_CHANGED = "subtitle_changed"
        const val FAILURE_NO_AUDIO = "no_audio"
        const val FAILURE_UNAVAILABLE = "unavailable"
    }
}

/**
 * One syncable subtitle of a media file (`SubtitleSyncState`): a stored
 * subtitle or a subtitle file next to the media (a sidecar), named by its
 * opaque sync [key], with its timing and latest job.
 */
@Serializable
data class SubtitleSyncState(
    val key: String,
    @SerialName("media_file_id") val mediaFileId: String = "",
    /** `downloaded` for a stored subtitle, `external` for a sidecar. */
    val source: String = "",
    @SerialName("stored_subtitle_id") val storedSubtitleId: String? = null,
    val language: String = "",
    val format: String = "",
    /** The release name, or the sidecar's file name. */
    val label: String = "",
    val timing: SubtitleTiming = SubtitleTiming(),
    val sync: SubtitleSyncJob? = null,
) {
    val isSidecar: Boolean get() = source == SOURCE_EXTERNAL

    companion object {
        const val SOURCE_EXTERNAL = "external"
        const val SOURCE_DOWNLOADED = "downloaded"

        /** The sync key of a stored subtitle; the server documents it as equivalent to the stored operations. */
        fun storedKey(subtitleId: Int): String = "stored-$subtitleId"
    }
}

/** The sync state a stored subtitle returned by a download or upload carries. */
fun DownloadedSubtitle.syncState(): SubtitleSyncState = SubtitleSyncState(
    key = SubtitleSyncState.storedKey(id),
    mediaFileId = mediaFileId.toString(),
    source = SubtitleSyncState.SOURCE_DOWNLOADED,
    storedSubtitleId = id.toString(),
    language = language,
    format = format,
    label = releaseName.ifBlank { provider },
    timing = timing,
    sync = sync,
)

/** GET /api/v2/subtitles/sync/status: whether this server can align subtitles to audio. */
@Serializable
data class SubtitleSyncCapability(
    val state: String = "",
    @SerialName("auto_sync") val autoSync: Boolean = false,
    /** Whether subtitle files next to the media (sidecars) can be synced too. */
    val external: Boolean = false,
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

/** "+2.3 s · 25→23.976 fps": a correction in a few characters. */
fun describeSyncTiming(timing: SubtitleTiming): String {
    val scale = describeSyncScale(timing.scale)
    val offset = if (timing.offsetMs != 0L || scale == null) formatSyncOffset(timing.offsetMs) else null
    return listOfNotNull(offset, scale).joinToString(" · ")
}

/** An active job's progress as a whole percentage, or null once it finished. */
fun syncProgressPercent(job: SubtitleSyncJob?): Int? {
    if (job == null || !job.inProgress) return null
    return ((job.progress ?: 0.0).coerceIn(0.0, 1.0) * 100).roundToInt()
}

/** What an active job is doing, in a few words. */
fun syncPhaseLabel(job: SubtitleSyncJob?): String? {
    if (job == null || !job.inProgress) return null
    return when (job.phase) {
        SubtitleSyncJob.PHASE_MATCHING -> "Matching lines to speech…"
        SubtitleSyncJob.PHASE_ANALYZING -> "Listening to the audio…"
        else -> "Waiting to start…"
    }
}

/** Why a failed job failed, in plain words. */
fun syncFailureMessage(failure: String?): String = when (failure) {
    SubtitleSyncJob.FAILURE_SUBTITLE_CHANGED -> "The subtitle changed while it was syncing. Try again."
    SubtitleSyncJob.FAILURE_NO_AUDIO -> "This video has no audio Silo can read."
    SubtitleSyncJob.FAILURE_UNAVAILABLE -> "The server is busy. Try again in a few minutes."
    else -> "Sync failed. Try again."
}

/**
 * One short line describing a subtitle's timing, or null when there is
 * nothing to say (never synced and never adjusted). Matches the web player.
 */
fun subtitleSyncStatusLabel(timing: SubtitleTiming, sync: SubtitleSyncJob?): String? {
    when (sync?.status) {
        SubtitleSyncJob.PENDING, SubtitleSyncJob.RUNNING -> {
            val percent = syncProgressPercent(sync)
            return if (percent != null && percent > 0) "Syncing… $percent%" else "Syncing…"
        }
        SubtitleSyncJob.NO_MATCH -> return "Doesn't match this video"
        SubtitleSyncJob.FAILED -> return "Sync failed"
        SubtitleSyncJob.ALREADY_SYNCED -> if (timing.isIdentity) return "Already in sync"
        SubtitleSyncJob.SYNCED -> {
            if (timing.isIdentity) return "Original timing"
            if (sync.result == timing) return "Synced ${describeSyncTiming(timing)}"
        }
    }
    return if (timing.isIdentity) null else "Timing adjusted ${describeSyncTiming(timing)}"
}

/**
 * The last finished job's result for the timing section, or null when there
 * is none to show; [warning] marks an outcome that left the timing unchanged
 * because the sync could not help.
 */
data class SubtitleSyncResultLine(val text: String, val warning: Boolean = false)

fun subtitleSyncResultLine(timing: SubtitleTiming, sync: SubtitleSyncJob?): SubtitleSyncResultLine? =
    when (sync?.status) {
        SubtitleSyncJob.SYNCED ->
            if (timing.isIdentity) null else SubtitleSyncResultLine("Synced to the audio: ${describeSyncTiming(timing)}")
        SubtitleSyncJob.ALREADY_SYNCED -> SubtitleSyncResultLine("Already matches the audio.")
        SubtitleSyncJob.NO_MATCH ->
            SubtitleSyncResultLine("Doesn't match this video's audio; probably for another release.", warning = true)
        SubtitleSyncJob.FAILED -> SubtitleSyncResultLine(syncFailureMessage(sync.failure), warning = true)
        else -> null
    }
