package org.siloserver.silo.playback

import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.model.settings.AudioPrefRequest
import org.siloserver.silo.model.settings.AudioTrackSignature
import org.siloserver.silo.model.settings.SubtitlePrefRequest
import org.siloserver.silo.model.settings.SubtitleTrackSignature

/** Server-side `subtitle_mode` values used by the pref writers. */
const val SUBTITLE_MODE_ALWAYS = "always"
const val SUBTITLE_MODE_OFF = "off"

/**
 * Pure builders for the per-series audio/subtitle preference PUT bodies, so
 * explicit track picks survive exiting the player and revisiting the item
 * (web-app parity). Ports Apple's `TrackSelectionPersistence.swift`.
 *
 * Key semantics mirror the web's `seriesContext?.seriesId ?? contentId`:
 * episodes persist under their series id (one choice applies to the whole
 * series), movies under their own content id.
 *
 * Writes are best-effort fire-and-forget (done at call sites via
 * [org.siloserver.silo.network.api.TrackPrefsApi] with failures logged):
 * a failed PUT costs the user a remembered preference, never playback.
 * Reads never happen client-side — the server folds saved prefs into
 * `WatchDetail.effective_*`, which the players already consume.
 */
object TrackSelectionPersistence {

    /**
     * Server pref key: the series id for episodes, the item's own content id
     * otherwise. Null when neither is known (e.g. offline playback with no
     * server-backed identity).
     */
    fun prefKey(seriesId: String?, contentId: String?): String? {
        if (!seriesId.isNullOrBlank()) return seriesId
        if (!contentId.isNullOrBlank()) return contentId
        return null
    }

    /**
     * Audio pick against server file metadata. [ordinal] indexes
     * [FileVersion.audioTracks] — the same space the server's
     * `audio_track_index` uses. Building the signature from the server's own
     * probed fields guarantees an exact match when the pref is re-resolved.
     * Null when [ordinal] is out of bounds.
     */
    fun audioRequest(version: FileVersion, ordinal: Int): AudioPrefRequest? {
        val track = version.audioTracks?.getOrNull(ordinal) ?: return null
        return AudioPrefRequest(
            audioTrackIndex = ordinal,
            audioLanguage = track.language.orEmpty(),
            trackSignature = AudioTrackSignature(
                language = track.language,
                title = track.title,
                embeddedTitle = track.title,
                codec = track.codec,
                layout = track.channelLayout,
                channels = track.channels,
                isDefault = track.isDefault,
            ),
        )
    }

    /**
     * Audio pick from a live player track, for selections the watch detail
     * can't describe. The server matches signature first and treats a
     * negative index as "no index" — language and signature still apply.
     */
    fun audioRequest(
        ordinal: Int,
        language: String?,
        title: String?,
        codec: String?,
        layout: String? = null,
        channels: Int? = null,
        isDefault: Boolean = false,
    ): AudioPrefRequest = AudioPrefRequest(
        audioTrackIndex = ordinal,
        audioLanguage = language.orEmpty(),
        trackSignature = AudioTrackSignature(
            language = language,
            title = title,
            embeddedTitle = title,
            codec = codec,
            layout = layout,
            channels = channels,
            isDefault = isDefault,
        ),
    )

    /**
     * Subtitle pick against server file metadata. [ffIndex] matches
     * `version.subtitleTracks[].index`; a negative value is an explicit
     * "Off". Null when no track carries [ffIndex].
     */
    fun subtitleRequest(
        version: FileVersion,
        ffIndex: Int,
        showForced: Boolean?,
    ): SubtitlePrefRequest? {
        if (ffIndex < 0) return subtitleOffRequest(showForced)
        val track = version.subtitleTracks?.firstOrNull { it.index == ffIndex } ?: return null
        return SubtitlePrefRequest(
            subtitleLanguage = track.language.orEmpty(),
            subtitleTrackIndex = ffIndex,
            externalSubtitlePath = if (track.external) track.externalPath.orEmpty() else "",
            subtitleMode = SUBTITLE_MODE_ALWAYS,
            trackSignature = SubtitleTrackSignature(
                source = if (track.external) "external" else "embedded",
                language = track.language,
                codec = track.codec,
                label = track.title,
                forced = track.forced,
                hearingImpaired = false,
            ),
            showForcedSubtitles = showForced,
        )
    }

    /**
     * Subtitle pick from a live session track (sidecar / mounted rows the
     * watch detail can't describe). [PlayerSubtitleInfo.index] is the
     * session's combined subtitle index.
     */
    fun subtitleRequest(track: PlayerSubtitleInfo, showForced: Boolean?): SubtitlePrefRequest =
        SubtitlePrefRequest(
            subtitleLanguage = track.language.orEmpty(),
            subtitleTrackIndex = track.index,
            externalSubtitlePath = "",
            subtitleMode = SUBTITLE_MODE_ALWAYS,
            trackSignature = SubtitleTrackSignature(
                source = track.source ?: "external",
                language = track.language,
                codec = track.codec,
                label = track.label,
                forced = track.forced ?: false,
                hearingImpaired = false,
            ),
            showForcedSubtitles = showForced,
        )

    /**
     * Explicit "subtitles off" — same payload the web writes for a null
     * selection: empty language, index -1, mode "off", no signature.
     */
    fun subtitleOffRequest(showForced: Boolean?): SubtitlePrefRequest = SubtitlePrefRequest(
        subtitleLanguage = "",
        subtitleTrackIndex = -1,
        externalSubtitlePath = "",
        subtitleMode = SUBTITLE_MODE_OFF,
        trackSignature = null,
        showForcedSubtitles = showForced,
    )
}
