package org.siloserver.silo.model.playback

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.siloserver.silo.model.catalog.AudioTrack
import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.catalog.WatchDetail
import org.siloserver.silo.playback.resolveAudioTrackOrdinal

/**
 * How playback recognizes a chosen audio track when indexes shift between the
 * files of a series. Same members as the server's `AudioTrackSignature`,
 * built from the catalog track (not Media3's view of it) because the server
 * matches title, embedded title, codec and layout exactly. `default` is not
 * sent: it is not part of the match.
 */
@Serializable
data class AudioTrackSignature(
    val language: String? = null,
    val title: String? = null,
    @SerialName("embedded_title") val embeddedTitle: String? = null,
    val codec: String? = null,
    val layout: String? = null,
    val channels: Int? = null,
)

/**
 * A manual audio choice remembered for a whole series (or one movie): the
 * body of `PUT /api/v2/audio-prefs/{series_id}`.
 *
 * [audioTrackIndex] is the track's position in the file's audio list.
 * [audioLanguage] is omitted when the track's language is unknown (`und`) or
 * not a tag the server accepts; the server stores it as the series' audio
 * language too.
 */
@Serializable
data class SeriesAudioChoice(
    @SerialName("audio_track_index") val audioTrackIndex: Int,
    @SerialName("audio_language") val audioLanguage: String? = null,
    @SerialName("track_signature") val trackSignature: AudioTrackSignature? = null,
) {
    companion object {
        /** The track at [ordinal] of a file's catalog audio list. */
        fun of(track: AudioTrack, ordinal: Int): SeriesAudioChoice = SeriesAudioChoice(
            audioTrackIndex = ordinal.coerceAtLeast(-1),
            audioLanguage = serverLanguageTag(track.language),
            trackSignature = track.audioTrackSignature(),
        )

        /** [of] for the track at [ordinal] of [tracks], or null when there is none. */
        fun of(tracks: List<AudioTrack>, ordinal: Int): SeriesAudioChoice? =
            tracks.getOrNull(ordinal)?.let { of(it, ordinal) }
    }
}

/**
 * A series audio choice this device made that the server may not hold yet;
 * [choice] null is "back to automatic".
 */
data class PendingSeriesAudioChoice(val choice: SeriesAudioChoice?)

fun AudioTrack.audioTrackSignature(): AudioTrackSignature? {
    val signature = AudioTrackSignature(
        language = language.clean(),
        title = title.clean(),
        embeddedTitle = embeddedTitle.clean(),
        codec = codec.clean(),
        layout = channelLayout.clean(),
        channels = channels?.takeIf { it > 0 },
    )
    return signature.takeUnless { it == AudioTrackSignature() }
}

/**
 * Whether [this] is the very track [signature] describes, by the server's rule
 * (`audioTrackMatchesSignature`): a compatible language (ISO 639-1 and 639-2
 * forms are the same language), the same title, embedded title, codec and
 * layout ignoring case, and the same channel count.
 */
fun AudioTrack.matchesSignature(signature: AudioTrackSignature): Boolean {
    if (signature == AudioTrackSignature()) return false
    val trackLanguage = autoSubtitleLanguageKey(language)
    val signatureLanguage = autoSubtitleLanguageKey(signature.language)
    if (trackLanguage == null || trackLanguage != signatureLanguage) return false
    return title.sameText(signature.title) &&
        embeddedTitle.sameText(signature.embeddedTitle) &&
        codec.sameText(signature.codec) &&
        channelLayout.sameText(signature.layout) &&
        (channels ?: 0) == (signature.channels ?: 0)
}

/**
 * The position in [tracks] this choice plays, by the server's series rungs
 * (`SelectAudioTrack` steps 1-3), so an unsent choice names the track the
 * server will: among the tracks FFmpeg can decode (all of them when none or
 * every one can), the track its signature describes — the closest language
 * tag first —, else the remembered position while it still holds the
 * language, else the closest track in that language (exact tag, then the
 * bare language, then another regional variant; file order breaks ties).
 * Null when none applies: the server then follows the profile's language, as
 * the starters' fallback does.
 */
fun SeriesAudioChoice.resolveOn(tracks: List<AudioTrack>): Int? {
    if (tracks.isEmpty()) return null
    val decodable = tracks.indices.filter { tracks[it].isDecodable() }
    val pool = if (decodable.isEmpty() || decodable.size == tracks.size) tracks.indices.toList() else decodable
    trackSignature?.let { signature ->
        pool.filter { tracks[it].matchesSignature(signature) }
            .minByOrNull { audioLanguageRank(tracks[it].language, signature.language) }
            ?.let { return it }
    }
    val language = audioLanguage ?: return null
    audioTrackIndex.takeIf { it in pool && audioLanguageRank(tracks[it].language, language) >= 0 }?.let { return it }
    return pool.filter { audioLanguageRank(tracks[it].language, language) >= 0 }
        .minByOrNull { audioLanguageRank(tracks[it].language, language) }
}

/**
 * The server's `AudioTrackDecodable` for a track list: a track whose probe
 * named no codec, or `unknown`, cannot be converted or copied.
 */
private fun AudioTrack.isDecodable(): Boolean {
    val probed = codec?.trim()?.lowercase().orEmpty()
    return probed.isNotEmpty() && probed != "unknown"
}

/**
 * The server's `langMatchRank`: 0 for the same tag, 1 for a bare candidate
 * of the same language, 2 for another regional or script variant, -1 for a
 * different (or unknown) language. ISO 639-2 primaries fold to 639-1.
 */
internal fun audioLanguageRank(candidate: String?, preferred: String?): Int {
    val a = comparableLanguageTag(candidate) ?: return -1
    val b = comparableLanguageTag(preferred) ?: return -1
    if (a == b) return 0
    if (a.substringBefore('-') != b.substringBefore('-')) return -1
    return if ('-' in a) 2 else 1
}

private fun comparableLanguageTag(value: String?): String? {
    val tag = value?.trim()?.lowercase()?.replace('_', '-')?.takeIf { it.isNotEmpty() } ?: return null
    val primary = autoSubtitleLanguageKey(tag.substringBefore('-')) ?: return null
    val rest = tag.substringAfter('-', missingDelimiterValue = "")
    return if (rest.isEmpty()) primary else "$primary-$rest"
}

/**
 * [this] with an unsent choice laid over every version's
 * `effective_audio_track_index`: a track resolves on each file; automatic (or
 * a track a file does not carry) clears it, since the answered index may be
 * the series choice being replaced, and the starters then fall back to the
 * profile's language.
 */
fun FileVersion.withPendingSeriesAudioChoice(pending: PendingSeriesAudioChoice?): FileVersion =
    if (pending == null) this else copy(effectiveAudioTrackIndex = pending.choice?.resolveOn(audioTracks.orEmpty()))

fun List<FileVersion>.withPendingSeriesAudioChoice(pending: PendingSeriesAudioChoice?): List<FileVersion> =
    if (pending == null) this else map { it.withPendingSeriesAudioChoice(pending) }

fun WatchDetail.withPendingSeriesAudioChoice(pending: PendingSeriesAudioChoice?): WatchDetail =
    if (pending == null) this else copy(versions = versions.withPendingSeriesAudioChoice(pending))

fun ItemDetail.withPendingSeriesAudioChoice(pending: PendingSeriesAudioChoice?): ItemDetail =
    if (pending == null) this else copy(versions = versions.withPendingSeriesAudioChoice(pending))

/**
 * The device's per-file audio memory, filtered by the server's answer.
 *
 * [resetToAuto]: the viewer set the series audio back to Auto and that has
 * not landed; the memory stays out so the profile's language decides.
 *
 * Online ([serverOrdinal] known: the server's effective track for this file,
 * which includes the profile's series choice) the memory only restores when it
 * names that same track; a different one is a pick the series choice has
 * since replaced, here or on another device. Offline, or when the server gave
 * no answer, the memory is all there is and restores as before.
 */
fun localAudioMemoryForSeriesChoice(
    localFingerprint: String?,
    tracks: List<AudioTrack>,
    serverOrdinal: Int?,
    resetToAuto: Boolean = false,
): String? {
    localFingerprint ?: return null
    // Auto chosen and not landed yet: the answered track is the series choice
    // being deleted, and the memory is an older pick — neither applies.
    if (resetToAuto) return null
    if (serverOrdinal == null || serverOrdinal !in tracks.indices) return localFingerprint
    return localFingerprint.takeIf { resolveAudioTrackOrdinal(tracks, it) == serverOrdinal }
}

/**
 * A track language as the server's `audio_language` and `subtitle_language`
 * accept it: a BCP 47 tag or ISO 639 code as recorded, an English language
 * name older files carry ("English") as its tag, and null for unknown (`und`)
 * or anything else ("Português"), which the server would refuse with a 422.
 */
internal fun serverLanguageTag(language: String?): String? {
    val value = language?.trim().orEmpty()
    if (value.isEmpty() || value.equals("und", ignoreCase = true)) return null
    if (SERVER_LANGUAGE.matches(value)) return value
    return org.siloserver.silo.model.settings.LanguageOptions.migrateLegacyValue(value)
        .takeIf { SERVER_LANGUAGE.matches(it) && !it.equals("und", ignoreCase = true) }
}

private val SERVER_LANGUAGE = Regex("^[A-Za-z]{2,3}(?:[-_][0-9A-Za-z]{1,8})*$")

private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

private fun String?.sameText(other: String?): Boolean =
    this?.trim().orEmpty().equals(other?.trim().orEmpty(), ignoreCase = true)
