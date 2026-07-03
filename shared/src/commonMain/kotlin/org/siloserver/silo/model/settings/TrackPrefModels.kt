package org.siloserver.silo.model.settings

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire types for the per-series audio/subtitle track preference endpoints
 * (`PUT/DELETE /api/v1/audio-prefs/{key}` and `/api/v1/subtitle-prefs/{key}`).
 * Mirrors Apple's `PlaybackPrefsModels.swift` — the server folds saved prefs
 * back into `WatchDetail.effective_*`, so the clients only ever WRITE these.
 */

/**
 * Identifier used by the server to re-locate the same audio track across
 * episodes in a series when the stream index shifts. Layout + channels let it
 * prefer "5.1 English Atmos" over "stereo English commentary".
 */
@Serializable
data class AudioTrackSignature(
    val language: String? = null,
    val title: String? = null,
    @SerialName("embedded_title") val embeddedTitle: String? = null,
    val codec: String? = null,
    val layout: String? = null,
    val channels: Int? = null,
    @SerialName("default") val isDefault: Boolean = false,
)

/**
 * Subtitle counterpart to [AudioTrackSignature]. The server tries an exact
 * signature match first, then falls back to language match.
 */
@Serializable
data class SubtitleTrackSignature(
    val source: String? = null, // "embedded", "external", "downloaded"
    val language: String? = null,
    val codec: String? = null,
    val label: String? = null,
    val forced: Boolean = false,
    @SerialName("hearing_impaired") val hearingImpaired: Boolean = false,
)

/** PUT body for `/api/v1/audio-prefs/{key}`. */
@Serializable
data class AudioPrefRequest(
    @SerialName("audio_track_index") val audioTrackIndex: Int,
    @SerialName("audio_language") val audioLanguage: String,
    @SerialName("track_signature") val trackSignature: AudioTrackSignature?,
)

/** PUT body for `/api/v1/subtitle-prefs/{key}`. */
@Serializable
data class SubtitlePrefRequest(
    @SerialName("subtitle_language") val subtitleLanguage: String,
    @SerialName("subtitle_track_index") val subtitleTrackIndex: Int,
    @SerialName("external_subtitle_path") val externalSubtitlePath: String,
    @SerialName("subtitle_mode") val subtitleMode: String,
    @SerialName("track_signature") val trackSignature: SubtitleTrackSignature?,
    @SerialName("show_forced_subtitles") val showForcedSubtitles: Boolean?,
)
