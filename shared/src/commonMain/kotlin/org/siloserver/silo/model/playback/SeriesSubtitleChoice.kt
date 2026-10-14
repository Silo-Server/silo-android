package org.siloserver.silo.model.playback

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.catalog.SubtitleTrack
import org.siloserver.silo.model.catalog.WatchDetail
import org.siloserver.silo.playback.decodeSubtitleIdentityPreference

/**
 * How playback recognizes a chosen subtitle track when indexes shift between
 * the episodes of a series. Same members as the server's
 * `SubtitleTrackSignature` and the web and Apple clients, so a choice made on
 * one client is found again by every other.
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

/**
 * A manual subtitle choice remembered for a whole series (or one movie):
 * the body of `PUT /api/v2/subtitle-prefs/{series_id}`.
 *
 * `show_forced_subtitles` is deliberately absent: sending it would pin the
 * profile's forced-subtitle toggle on this series, so a later change to that
 * toggle would never reach it. The server keeps what the series already had.
 */
@Serializable
data class SeriesSubtitleChoice(
    @SerialName("subtitle_language") val subtitleLanguage: String,
    @SerialName("subtitle_track_index") val subtitleTrackIndex: Int,
    @SerialName("subtitle_mode") val subtitleMode: String,
    @SerialName("track_signature") val trackSignature: SubtitleTrackSignature? = null,
) {
    companion object {
        /** Subtitles off for the series — the web and Apple spelling. */
        val Off = SeriesSubtitleChoice(
            subtitleLanguage = "",
            subtitleTrackIndex = -1,
            subtitleMode = "off",
        )

        /** One track, chosen from the session's subtitle inventory. */
        fun of(track: PlayerSubtitleInfo): SeriesSubtitleChoice = SeriesSubtitleChoice(
            subtitleLanguage = track.language?.trim().orEmpty(),
            subtitleTrackIndex = track.index.coerceAtLeast(-1),
            subtitleMode = "always",
            trackSignature = track.subtitleTrackSignature(),
        )

        /**
         * The choice a committed player [identity] stands for, described from
         * the session inventory [rows] where the track is listed there. Null
         * when the identity names a track that cannot be described.
         */
        fun of(identity: SubtitleIdentity, rows: List<PlayerSubtitleInfo>): SeriesSubtitleChoice? {
            val row = when (identity) {
                SubtitleIdentity.Off -> return Off
                is SubtitleIdentity.ServerSidecar -> rows.firstOrNull { it.index == identity.serverIndex }
                is SubtitleIdentity.ServerBurnIn -> rows.firstOrNull { it.index == identity.serverIndex }
                is SubtitleIdentity.Embedded -> rows.firstOrNull { it.index == identity.serverIndex }
                is SubtitleIdentity.Downloaded -> rows.firstOrNull { it.downloadId == identity.downloadId }
                is SubtitleIdentity.LocalMedia3 -> null
            }
            if (row != null) return of(row)
            val media = when (identity) {
                is SubtitleIdentity.Downloaded -> identity.media
                is SubtitleIdentity.LocalMedia3 -> identity.media
                else -> return null
            }
            if (media.language.isNullOrBlank() && media.label.isNullOrBlank()) return null
            return SeriesSubtitleChoice(
                subtitleLanguage = media.language?.trim().orEmpty(),
                subtitleTrackIndex = -1,
                subtitleMode = "always",
                trackSignature = SubtitleTrackSignature(
                    source = if (identity is SubtitleIdentity.Downloaded) "downloaded" else null,
                    language = media.language?.trim()?.takeIf { it.isNotBlank() },
                    codec = media.codecFamily?.trim()?.takeIf { it.isNotBlank() },
                    label = media.label?.trim()?.takeIf { it.isNotBlank() },
                    forced = media.forced == true,
                    hearingImpaired = media.hearingImpaired == true,
                ),
            )
        }

        /** One track, chosen from the catalog listing (the detail page). */
        fun of(track: SubtitleTrack, selectionIndex: Int): SeriesSubtitleChoice = SeriesSubtitleChoice(
            subtitleLanguage = track.language?.trim().orEmpty(),
            subtitleTrackIndex = selectionIndex.coerceAtLeast(-1),
            subtitleMode = "always",
            trackSignature = track.subtitleTrackSignature(),
        )
    }
}

/**
 * A series choice this device made that the server may not hold yet;
 * [choice] null is "back to automatic".
 */
data class PendingSeriesSubtitleChoice(val choice: SeriesSubtitleChoice?)

/** The effective subtitle fields a watch or item detail carries. */
data class EffectiveSubtitleFields(
    val language: String?,
    val mode: String?,
    val signature: SubtitleTrackSignature?,
)

/**
 * [this] with a not-yet-landed choice laid over it: a track becomes its
 * language, mode "always" and signature; Off becomes mode "off". Automatic
 * drops the series signature only — the lower-scope values it falls back to
 * are the server's to resolve, so the language and mode are left as answered.
 */
fun EffectiveSubtitleFields.withPending(pending: PendingSeriesSubtitleChoice?): EffectiveSubtitleFields {
    pending ?: return this
    val choice = pending.choice ?: return copy(signature = null)
    return if (choice.subtitleMode == "off") {
        copy(mode = "off", signature = null)
    } else {
        EffectiveSubtitleFields(
            language = choice.subtitleLanguage.ifBlank { null } ?: language,
            mode = choice.subtitleMode,
            signature = choice.trackSignature,
        )
    }
}

fun WatchDetail.withPendingSeriesSubtitleChoice(pending: PendingSeriesSubtitleChoice?): WatchDetail {
    pending ?: return this
    val fields = EffectiveSubtitleFields(effectiveSubtitleLanguage, effectiveSubtitleMode, effectiveSubtitleTrackSignature)
        .withPending(pending)
    return copy(
        effectiveSubtitleLanguage = fields.language,
        effectiveSubtitleMode = fields.mode,
        effectiveSubtitleTrackSignature = fields.signature,
    )
}

fun ItemDetail.withPendingSeriesSubtitleChoice(pending: PendingSeriesSubtitleChoice?): ItemDetail {
    pending ?: return this
    val fields = EffectiveSubtitleFields(effectiveSubtitleLanguage, effectiveSubtitleMode, effectiveSubtitleTrackSignature)
        .withPending(pending)
    return copy(
        effectiveSubtitleLanguage = fields.language,
        effectiveSubtitleMode = fields.mode,
        effectiveSubtitleTrackSignature = fields.signature,
    )
}

/**
 * The id a subtitle choice is remembered under: the series for an episode, so
 * one choice covers every episode, else the item's own content id (a movie).
 * Null when neither is known.
 */
fun seriesSubtitleChoiceKey(seriesId: String?, contentId: String?): String? =
    seriesId?.takeIf { it.isNotBlank() } ?: contentId?.takeIf { it.isNotBlank() }

fun PlayerSubtitleInfo.subtitleTrackSignature(): SubtitleTrackSignature {
    val candidate = toAutoSubtitleCandidate()
    return SubtitleTrackSignature(
        source = candidate.signatureSource(),
        language = language?.trim()?.takeIf { it.isNotBlank() },
        codec = codec?.trim()?.takeIf { it.isNotBlank() },
        label = candidate.signatureLabel?.trim()?.takeIf { it.isNotBlank() },
        forced = forced == true,
        hearingImpaired = candidate.isHearingImpaired(),
    )
}

fun SubtitleTrack.subtitleTrackSignature(): SubtitleTrackSignature = SubtitleTrackSignature(
    source = if (external) "external" else "embedded",
    language = language?.trim()?.takeIf { it.isNotBlank() },
    codec = codec?.trim()?.takeIf { it.isNotBlank() },
    label = title?.trim()?.takeIf { it.isNotBlank() },
    forced = forced,
    hearingImpaired = AutoSubtitleCandidate(selectionIndex = 0, title = title).isHearingImpaired(),
)

/**
 * Whether [this] is the very track [signature] describes (web parity:
 * `subtitleTrackMatchesSignature`): same source, language and codec, the same
 * label when the signature carries one, and the same forced and SDH flags.
 */
fun AutoSubtitleCandidate.matchesSignature(signature: SubtitleTrackSignature): Boolean =
    signature.source.norm() == signatureSource().norm() &&
        signature.language.norm() == language.norm() &&
        signature.codec.norm() == codec.norm() &&
        (signature.label.norm().isEmpty() || signature.label.norm() == signatureLabel.norm()) &&
        signature.forced == forced &&
        signature.hearingImpaired == isHearingImpaired()

/**
 * Tie-break score between tracks that already match the wanted language (web
 * parity: `scoreSignatureFallback`). Higher is closer to the remembered track.
 */
fun AutoSubtitleCandidate.signatureScore(signature: SubtitleTrackSignature): Int {
    var score = 0
    if (signature.source.norm() == signatureSource().norm()) score += 4
    if (signature.forced == forced) score += 2
    if (signature.hearingImpaired == isHearingImpaired()) score += 2
    if (signature.codec.norm() == codec.norm()) score += 1
    if (signature.label.norm() == signatureLabel.norm()) score += 1
    return score
}

private fun AutoSubtitleCandidate.signatureSource(): String? = when (source) {
    AutoSubtitleSource.EMBEDDED -> "embedded"
    AutoSubtitleSource.EXTERNAL -> "external"
    AutoSubtitleSource.DOWNLOADED -> "downloaded"
    null -> null
}

private fun String?.norm(): String = this?.trim()?.lowercase().orEmpty()

/**
 * The device's per-file subtitle memory, filtered by the server's series
 * choice.
 *
 * Online the server's choice is the truth — it is what every other device
 * sees, and "Auto" on another device deletes it — so the local memory may
 * only restore that same choice as it exists in this file (a downloaded or
 * AI track the signature alone cannot find again): a track whose language
 * and forced flag agree with [signature]. With no server track choice the
 * local memory is ignored, so a reset made elsewhere is not undone here.
 *
 * Offline ([serverChoiceKnown] false: no watch detail was read) the local
 * memory is the only memory there is and restores as before.
 */
fun localSubtitleMemoryForSeriesChoice(
    localPreference: String?,
    serverChoiceKnown: Boolean,
    signature: SubtitleTrackSignature?,
): String? {
    if (localPreference == null || !serverChoiceKnown) return localPreference
    if (signature == null) return null
    val (language, forced) = when (val identity = decodeSubtitleIdentityPreference(localPreference)) {
        SubtitleIdentity.Off -> return null
        null -> legacyFingerprintTraits(localPreference) ?: return null
        is SubtitleIdentity.ServerSidecar -> identity.media?.let { it.language to it.forced } ?: return null
        is SubtitleIdentity.ServerBurnIn -> identity.media?.let { it.language to it.forced } ?: return null
        is SubtitleIdentity.Embedded -> identity.media.language to identity.media.forced
        is SubtitleIdentity.Downloaded -> identity.media.language to identity.media.forced
        is SubtitleIdentity.LocalMedia3 -> identity.media.language to identity.media.forced
    }
    val sameLanguage = autoSubtitleLanguageKey(language) != null &&
        autoSubtitleLanguageKey(language) == autoSubtitleLanguageKey(signature.language)
    val sameForced = forced == null || forced == signature.forced
    return localPreference.takeIf { sameLanguage && sameForced }
}

/**
 * Language and forced flag of a legacy `index|language|codec|title|forced`
 * fingerprint, or null for anything else (including the "off" sentinel).
 */
private fun legacyFingerprintTraits(fingerprint: String): Pair<String?, Boolean?>? {
    val parts = fingerprint.split('|')
    if (parts.size < 5 || parts.first().toIntOrNull() == null) return null
    return parts[1] to parts.last().toBooleanStrictOrNull()
}
