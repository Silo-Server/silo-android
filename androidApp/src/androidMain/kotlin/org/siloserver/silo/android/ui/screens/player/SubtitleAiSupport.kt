package org.siloserver.silo.android.ui.screens.player

import android.content.res.Resources
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.model.subtitles.SubtitleAiJobKind
import org.siloserver.silo.model.subtitles.SubtitleAiQuota

// Mirrors web/src/player/components/SubtitleTranslateModal.tsx: external /
// downloaded sources must be a server-parseable text format, while embedded
// non-bitmap tracks can be extracted to text via ffmpeg.
private val TRANSLATABLE_TEXT_CODECS = setOf("srt", "subrip", "vtt", "webvtt")
private val BITMAP_CODECS = setOf(
    "pgs", "hdmv_pgs_subtitle", "dvd_subtitle", "dvdsub", "dvb_subtitle", "dvbsub",
)

/** True when the server can use [track] as an AI translation source. */
internal fun isTranslatableSource(track: PlayerSubtitleInfo): Boolean {
    val codec = track.codec?.lowercase().orEmpty()
    return if (track.source == "embedded") {
        codec !in BITMAP_CODECS
    } else {
        codec in TRANSLATABLE_TEXT_CODECS
    }
}

/**
 * Audio-mode job kind: same target as the audio language → plain
 * transcription; otherwise transcribe-then-translate (web parity).
 */
internal fun transcribeKindFor(audioLanguage: String?, targetLanguage: String): String =
    if ((audioLanguage ?: "") == targetLanguage) {
        SubtitleAiJobKind.Transcribe
    } else {
        SubtitleAiJobKind.TranscribeTranslate
    }

/** Quota counter line, wording pinned to the web modal + its period labels. */
internal fun quotaLineText(quota: SubtitleAiQuota, resources: Resources): String {
    val window = when (quota.period) {
        "day" -> resources.getString(CommonR.string.player_aiTranslate_window_day)
        "week" -> resources.getString(CommonR.string.player_aiTranslate_window_week)
        "month" -> resources.getString(CommonR.string.player_aiTranslate_window_month)
        else -> quota.period
    }
    return if (quota.remaining <= 0) {
        resources.getQuantityString(
            CommonR.plurals.player_aiTranslate_quotaUsedUp,
            quota.limit,
            quota.limit,
            window,
            quota.limit.toString(),
        )
    } else {
        resources.getQuantityString(
            CommonR.plurals.player_aiTranslate_quotaLeftWindow,
            quota.limit,
            quota.remaining.toString(),
            quota.limit,
            window,
            quota.limit.toString(),
        )
    }
}
