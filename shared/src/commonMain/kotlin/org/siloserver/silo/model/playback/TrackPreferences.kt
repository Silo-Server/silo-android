package org.siloserver.silo.model.playback

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.siloserver.silo.model.catalog.AudioTrack
import org.siloserver.silo.model.catalog.SubtitleTrack
import org.siloserver.silo.model.catalog.WatchDetail
import org.siloserver.silo.playback.subtitleLabelIndicatesHearingImpaired

/**
 * The profile's remembered subtitle and audio track for a series, stored by the
 * server at `/api/v2/{subtitle,audio}-prefs/{key}` and folded back into
 * `WatchDetail.effective_*` and `FileVersion.effective_audio_*`. The member
 * names are the server contract's (silo-server `internal/apiv2/preferences.go`)
 * and match Silo Apple's `TrackSelectionPersistence`.
 */
@Serializable
data class SubtitleTrackSignature(
    val source: String? = null,
    val language: String? = null,
    val codec: String? = null,
    val label: String? = null,
    val forced: Boolean = false,
    @SerialName("hearing_impaired") val hearingImpaired: Boolean = false,
)

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

/** `PUT /api/v2/audio-prefs/{key}`. The server replaces the whole row. */
@Serializable
data class AudioPreferenceUpdate(
    /** Ordinal into the source file's `audio_tracks`; -1 when only language and signature apply. */
    @SerialName("audio_track_index") val audioTrackIndex: Int,
    @SerialName("audio_language") val audioLanguage: String,
    @SerialName("track_signature") val trackSignature: AudioTrackSignature? = null,
)

/**
 * `PUT /api/v2/subtitle-prefs/{key}`. An omitted [showForcedSubtitles] keeps the
 * override the server already stores, so a track pick never resets it.
 */
@Serializable
data class SubtitlePreferenceUpdate(
    @SerialName("subtitle_track_index") val subtitleTrackIndex: Int,
    @SerialName("subtitle_language") val subtitleLanguage: String,
    @SerialName("external_subtitle_path") val externalSubtitlePath: String = "",
    @SerialName("subtitle_mode") val subtitleMode: String,
    @SerialName("track_signature") val trackSignature: SubtitleTrackSignature? = null,
    @SerialName("show_forced_subtitles") val showForcedSubtitles: Boolean? = null,
)

/**
 * The key the server keeps a title's track preference under: a movie's own id,
 * or the parent series id for an episode (specials included). Never an
 * episode's own id, which would strand the choice on one episode, and null for
 * anything else or an episode without its series.
 */
fun trackPreferenceKey(contentType: String?, contentId: String?, seriesId: String?): String? =
    when (contentType?.trim()?.lowercase()) {
        "movie" -> contentId
        "episode" -> seriesId
        else -> null
    }?.trim()?.takeIf { it.isNotEmpty() }

/**
 * Whether the server resolved a subtitle preference for this title at any
 * scope. When it did, that answer is authoritative and a per-file choice
 * remembered on this device must not override it.
 */
fun WatchDetail.hasSubtitlePreference(): Boolean =
    effectiveSubtitleTrackSignature != null || effectiveSubtitleLanguage != null || effectiveSubtitleMode != null

/**
 * An audio pick against the source file's catalog tracks. [ordinal] indexes
 * [tracks], the space the server's `audio_track_index` uses, and the
 * signature repeats the server's own probed fields so it matches exactly.
 */
fun audioPreferenceUpdate(tracks: List<AudioTrack>, ordinal: Int): AudioPreferenceUpdate? {
    val track = tracks.getOrNull(ordinal) ?: return null
    return AudioPreferenceUpdate(
        audioTrackIndex = ordinal,
        audioLanguage = preferenceLanguage(track.language),
        trackSignature = AudioTrackSignature(
            language = track.language,
            title = track.title,
            embeddedTitle = track.embeddedTitle,
            codec = track.codec,
            layout = track.channelLayout,
            channels = track.channels,
            isDefault = track.isDefault,
        ),
    )
}

/**
 * The preference for a subtitle pick the player addresses as [identity]. A
 * server track is described from [catalogTracks], the source file's own
 * metadata, with its combined selection index. A downloaded subtitle or a
 * track only the player found has no server index, so it sends -1 with the
 * language, flags and signature it carries. Off is index -1, mode `off` and no
 * signature, distinct from having no preference at all.
 */
fun subtitlePreferenceUpdate(identity: SubtitleIdentity, catalogTracks: List<SubtitleTrack>): SubtitlePreferenceUpdate? =
    when (identity) {
        SubtitleIdentity.Off ->
            SubtitlePreferenceUpdate(subtitleTrackIndex = -1, subtitleLanguage = "", subtitleMode = "off")
        is SubtitleIdentity.ServerSidecar ->
            catalogSubtitlePreferenceUpdate(catalogTracks, identity.serverIndex)
                ?: identity.media?.preferenceUpdate("external")
        is SubtitleIdentity.ServerBurnIn ->
            catalogSubtitlePreferenceUpdate(catalogTracks, identity.serverIndex) ?: identity.media?.preferenceUpdate(null)
        is SubtitleIdentity.Embedded ->
            catalogSubtitlePreferenceUpdate(catalogTracks, identity.serverIndex) ?: identity.media.preferenceUpdate("embedded")
        is SubtitleIdentity.Downloaded -> identity.media.preferenceUpdate(SUBTITLE_SOURCE_DOWNLOADED)
        is SubtitleIdentity.LocalMedia3 -> identity.media.preferenceUpdate("embedded")
    }

private fun catalogSubtitlePreferenceUpdate(catalogTracks: List<SubtitleTrack>, serverIndex: Int): SubtitlePreferenceUpdate? {
    val ordinal = combinedSubtitleSelectionIndexes(catalogTracks).indexOf(serverIndex)
    val track = catalogTracks.getOrNull(ordinal) ?: return null
    return SubtitlePreferenceUpdate(
        subtitleTrackIndex = serverIndex,
        subtitleLanguage = preferenceLanguage(track.language),
        externalSubtitlePath = if (track.external) track.externalPath.orEmpty() else "",
        subtitleMode = "always",
        trackSignature = SubtitleTrackSignature(
            source = if (track.external) "external" else "embedded",
            language = track.language,
            codec = track.codec,
            label = track.title,
            forced = track.forced,
            hearingImpaired = track.hearingImpaired || subtitleLabelIndicatesHearingImpaired(track.title),
        ),
    )
}

private fun SubtitleMediaIdentity.preferenceUpdate(source: String?): SubtitlePreferenceUpdate =
    SubtitlePreferenceUpdate(
        subtitleTrackIndex = -1,
        subtitleLanguage = preferenceLanguage(language),
        subtitleMode = "always",
        trackSignature = SubtitleTrackSignature(
            source = source,
            language = language,
            codec = codecFamily,
            label = label,
            forced = forced == true,
            hearingImpaired = hearingImpaired == true || subtitleLabelIndicatesHearingImpaired(label),
        ),
    )

/**
 * A track language the server's preference pattern accepts, or "" for none.
 * `und` names no language, and a tag the pattern would reject must not fail
 * the whole write.
 */
private fun preferenceLanguage(language: String?): String {
    val value = language?.trim().orEmpty()
    if (value.equals("und", ignoreCase = true)) return ""
    return value.takeIf { PREFERENCE_LANGUAGE.matches(it) }.orEmpty()
}

private val PREFERENCE_LANGUAGE = Regex("^[A-Za-z]{2,3}(?:[-_][0-9A-Za-z]{1,8})*$")
