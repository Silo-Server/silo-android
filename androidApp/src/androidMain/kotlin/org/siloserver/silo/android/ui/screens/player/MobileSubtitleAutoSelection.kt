package org.siloserver.silo.android.ui.screens.player

import org.siloserver.silo.model.catalog.AudioTrack
import org.siloserver.silo.model.catalog.SubtitleTrack
import org.siloserver.silo.model.playback.AutoSubtitleCandidate
import org.siloserver.silo.model.playback.AutoSubtitleContext
import org.siloserver.silo.model.playback.AutoSubtitleResolution
import org.siloserver.silo.model.playback.SubtitleTrackSignature
import org.siloserver.silo.model.playback.resolveAutoSubtitle
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.model.playback.SubtitleIdentity
import org.siloserver.silo.model.playback.toAutoSubtitleCandidate
import org.siloserver.silo.playback.canonicalSubtitleLanguage
import org.siloserver.silo.playback.hasPositiveSubtitleDiscriminator
import org.siloserver.silo.playback.matchesSubtitleMediaIdentity
import org.siloserver.silo.playback.playbackSubtitleIdentity
import org.siloserver.silo.playback.resolveDownloadedSubtitlePreferenceOrdinal
import org.siloserver.silo.playback.subtitleLabelIndicatesHearingImpaired
import org.siloserver.silo.playback.subtitleMediaIdentityOrNull

internal sealed class MobileSubtitleAutoSelection {
    data object NoChange : MobileSubtitleAutoSelection()
    data object Disable : MobileSubtitleAutoSelection()
    data class Select(val ordinal: Int) : MobileSubtitleAutoSelection()
}

internal fun mobileSubtitleIdentity(subtitle: PlayerSubtitleInfo): SubtitleIdentity =
    playbackSubtitleIdentity(subtitle)

internal fun resolveMobileSubtitleOrdinal(
    identity: SubtitleIdentity,
    subtitles: List<PlayerSubtitleInfo>,
): Int? {
    if (identity == SubtitleIdentity.Off) return -1
    if (identity is SubtitleIdentity.Downloaded) {
        return resolveDownloadedSubtitlePreferenceOrdinal(identity, subtitles)
    }

    val exactMatches = subtitles.indices.filter { index ->
        val row = subtitles[index]
        when (identity) {
            SubtitleIdentity.Off -> false
            is SubtitleIdentity.ServerSidecar -> {
                val media = identity.media
                media == null &&
                    row.index == identity.serverIndex &&
                    mobileSubtitleIdentity(row) is SubtitleIdentity.ServerSidecar
            }
            is SubtitleIdentity.ServerBurnIn -> {
                val media = identity.media
                media == null &&
                    row.index == identity.serverIndex &&
                    mobileSubtitleIdentity(row) is SubtitleIdentity.ServerBurnIn
            }
            is SubtitleIdentity.Embedded -> {
                val rowIdentity = mobileSubtitleIdentity(row)
                identity.media.trackId != null &&
                    rowIdentity is SubtitleIdentity.Embedded &&
                    rowIdentity.media.matchesSubtitleMediaIdentity(identity.media)
            }
            is SubtitleIdentity.Downloaded -> {
                val rowIdentity = mobileSubtitleIdentity(row)
                row.downloadId == identity.downloadId &&
                    rowIdentity is SubtitleIdentity.Downloaded &&
                    rowIdentity.media.matchesSubtitleMediaIdentity(identity.media)
            }
            is SubtitleIdentity.LocalMedia3 -> {
                val rowIdentity = mobileSubtitleIdentity(row)
                identity.media.trackId != null &&
                    rowIdentity is SubtitleIdentity.LocalMedia3 &&
                    rowIdentity.media.matchesSubtitleMediaIdentity(identity.media)
            }
        }
    }
    if (exactMatches.size == 1) return exactMatches.single()
    if (exactMatches.size > 1) return null

    val targetMedia = identity.subtitleMediaIdentityOrNull() ?: return null
    if (!targetMedia.hasPositiveSubtitleDiscriminator()) return null
    val typedMatches = subtitles.indices.filter { index ->
        val rowIdentity = mobileSubtitleIdentity(subtitles[index])
        val rowMedia = rowIdentity.subtitleMediaIdentityOrNull() ?: return@filter false
        identity::class == rowIdentity::class &&
            rowMedia.matchesSubtitleMediaIdentity(targetMedia)
    }
    return typedMatches.singleOrNull()
}

/**
 * The phone's Auto pick, made by the shared [resolveAutoSubtitle] — the same
 * rule the TV player and the detail page apply, so the two apps start the same
 * file on the same track. In particular "Show forced subtitles" never lets a
 * forced track outrank the full-dialogue track when subtitles are wanted
 * (product owner call, 2026-08-16); the phone used to let a forced text track
 * jump the queue.
 *
 * [trackSignature] is the series choice saved on the server: the track it
 * describes is picked outright, else it breaks ties within the language.
 * Candidates keep the phone's own SDH reading (label, source and URL).
 */
internal fun resolveMobileAutoSubtitleSelection(
    audioTracks: List<AudioTrack>,
    selectedAudioIndex: Int,
    subtitles: List<PlayerSubtitleInfo>,
    preferredLanguage: String?,
    subtitleMode: String?,
    showForcedSubtitles: Boolean,
    trackSignature: SubtitleTrackSignature? = null,
): MobileSubtitleAutoSelection {
    if (subtitles.isEmpty()) return MobileSubtitleAutoSelection.NoChange
    // An ORDINAL into audioTracks: audio carries no index on the wire.
    val audioLanguage = audioTracks.getOrNull(selectedAudioIndex)?.language
    val resolution = resolveAutoSubtitle(
        candidates = subtitles.mapIndexed { ordinal, subtitle ->
            subtitle.toMobileAutoSubtitleCandidate().copy(selectionIndex = ordinal)
        },
        context = AutoSubtitleContext(
            preferredLanguage = preferredLanguage,
            mode = subtitleMode,
            showForced = showForcedSubtitles,
            audioLanguage = audioLanguage,
            trackSignature = trackSignature,
        ),
    )
    return when (resolution) {
        AutoSubtitleResolution.NoChange -> MobileSubtitleAutoSelection.NoChange
        AutoSubtitleResolution.Disable -> MobileSubtitleAutoSelection.Disable
        is AutoSubtitleResolution.Select -> MobileSubtitleAutoSelection.Select(resolution.candidate.selectionIndex)
    }
}

/**
 * Maps the detail screen's pre-playback subtitle pick — an ordinal into the
 * catalog `FileVersion.subtitleTracks` list — onto the mounted subtitle list
 * the player actually selects from (TV `resolveInitialSubtitleTrackIndex`
 * parity). The two lists use different index spaces and orderings, so the
 * raw ordinal previously either fell out of range (subtitles silently stayed
 * off) or hit the wrong track. Matches by label first, then by
 * language + forced flag + codec; unmatched picks fall back to the raw
 * ordinal when mountable (pre-fix behavior), else null so persisted/auto
 * selection decides.
 */
internal fun resolveInitialMobileSubtitleOrdinal(
    requestedOrdinal: Int,
    catalogTracks: List<SubtitleTrack>,
    mountedSubtitles: List<PlayerSubtitleInfo>,
): Int? {
    if (requestedOrdinal == -1) return -1
    // V3 uses the server's combined subtitle ordinal as PlayerSubtitleInfo.index.
    // Embedded bitmap rows can intentionally omit label/language because the
    // primary media carries those properties; resolve their stable identity
    // before attempting descriptive metadata matching.
    mountedSubtitles.indexOfFirst {
        it.source == "embedded" &&
            it.index == requestedOrdinal &&
            it.label.isNullOrBlank() &&
            it.language.isNullOrBlank()
    }
        .takeIf { it >= 0 }
        ?.let { return it }
    val requested = catalogTracks.getOrNull(requestedOrdinal)
        ?: return requestedOrdinal.takeIf { it in mountedSubtitles.indices }
    mountedSubtitles.indexOfFirst { it.matchesCatalogSubtitle(requested) }
        .takeIf { it >= 0 }
        ?.let { return it }
    return requestedOrdinal.takeIf { it in mountedSubtitles.indices }
}

private fun PlayerSubtitleInfo.matchesCatalogSubtitle(track: SubtitleTrack): Boolean {
    val targetLabel = track.title?.trim()?.takeIf { it.isNotBlank() }
    val mountedLabel = label?.trim()?.takeIf { it.isNotBlank() }
    if (targetLabel != null && mountedLabel != null &&
        mountedLabel.equals(targetLabel, ignoreCase = true)
    ) {
        // Duplicate titles ("English" full + "English" forced/SDH) are only
        // told apart by the forced flag — a bare label match must not cross
        // that boundary when the mounted order differs from the catalog's.
        return (forced == true) == track.forced
    }
    val targetLanguage = canonicalSubtitleLanguage(track.language) ?: return false
    if (canonicalSubtitleLanguage(language) != targetLanguage) return false
    if ((forced == true) != track.forced) return false
    val targetCodec = normalizedSubtitleCodec(track.codec)
    val mountedCodec = normalizedSubtitleCodec(codec ?: subtitleCodecFromUrl(url))
    return targetCodec == null || mountedCodec == null || targetCodec == mountedCodec
}

private fun normalizedSubtitleCodec(codecOrMime: String?): String? {
    val normalized = codecOrMime
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.filter { it.isLetterOrDigit() }
        ?.lowercase()
        ?: return null
    return when {
        normalized == "ass" || normalized == "ssa" || normalized.contains("xssa") -> "ssa"
        normalized == "srt" || normalized.contains("subrip") -> "srt"
        normalized == "vtt" || normalized == "textvtt" || normalized.contains("webvtt") -> "vtt"
        normalized.contains("pgs") -> "pgs"
        normalized.contains("dvd") || normalized.contains("vobsub") -> "vobsub"
        normalized.contains("dvbsub") -> "dvbsub"
        else -> normalized
    }
}

/**
 * The phone's SDH predicate reads the server's flag, then the runtime label,
 * source and URL rather than the catalog title, so it replaces the shared
 * title check.
 */
internal fun PlayerSubtitleInfo.toMobileAutoSubtitleCandidate(): AutoSubtitleCandidate =
    toAutoSubtitleCandidate().copy(title = label, hearingImpaired = isEffectivelyHearingImpaired())

private fun PlayerSubtitleInfo.isEffectivelyHearingImpaired(): Boolean =
    hearingImpaired == true ||
        subtitleLabelIndicatesHearingImpaired(label) ||
        subtitleLabelIndicatesHearingImpaired(source) ||
        subtitleLabelIndicatesHearingImpaired(url)

private fun subtitleCodecFromUrl(url: String?): String? =
    url
        ?.substringBefore('?')
        ?.substringBefore('#')
        ?.substringAfterLast('.', missingDelimiterValue = "")
        ?.takeIf { it.isNotBlank() }
