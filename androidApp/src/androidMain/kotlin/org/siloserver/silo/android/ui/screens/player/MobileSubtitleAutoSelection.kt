package org.siloserver.silo.android.ui.screens.player

import org.siloserver.silo.common.player.isBitmapSubtitleCodecOrMime
import org.siloserver.silo.common.player.downloadedSubtitleArtifactTrackId
import org.siloserver.silo.common.player.subtitleLabelIndicatesHearingImpaired
import org.siloserver.silo.model.catalog.AudioTrack
import org.siloserver.silo.model.catalog.SubtitleTrack
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.model.playback.SubtitleIdentity
import org.siloserver.silo.model.playback.SubtitleMediaIdentity
import org.siloserver.silo.model.playback.isLocalDownloadedSubtitle
import org.siloserver.silo.playback.canonicalSubtitleCodecFamily
import org.siloserver.silo.playback.isClientMountableBitmapCodecFamily
import org.siloserver.silo.playback.canonicalSubtitleLanguage

private val hearingImpairedSubtitleTokenRegex = Regex(
    pattern = """(^|[^a-z0-9])(cc|sdh|hi)([^a-z0-9]|$)""",
    option = RegexOption.IGNORE_CASE,
)

internal sealed class MobileSubtitleAutoSelection {
    data object NoChange : MobileSubtitleAutoSelection()
    data object Disable : MobileSubtitleAutoSelection()
    data class Select(val ordinal: Int) : MobileSubtitleAutoSelection()
}

internal fun mobileSubtitleIdentity(subtitle: PlayerSubtitleInfo): SubtitleIdentity {
    val source = subtitle.source?.trim()?.lowercase()
    val catalogSource = subtitle.catalogSource?.trim()?.lowercase()
    val downloaded = subtitle.isLocalDownloadedSubtitle()
    val media = SubtitleMediaIdentity(
        trackId = subtitle.downloadId?.let(::downloadedSubtitleArtifactTrackId)
            ?: subtitle.mediaTrackId,
        label = subtitle.catalogLabel ?: subtitle.label,
        language = canonicalSubtitleLanguage(subtitle.language),
        codecFamily = canonicalSubtitleCodecFamily(
            subtitle.codec ?: subtitleCodecFromUrl(subtitle.url),
        ),
        forced = subtitle.forced,
        hearingImpaired = subtitleLabelIndicatesHearingImpaired(
            subtitle.catalogLabel ?: subtitle.label,
        ).takeIf { it },
    )
    when (subtitle.serverDelivery) {
        "burn_in_only" -> return SubtitleIdentity.ServerBurnIn(subtitle.index, media)
        "sidecar" -> return SubtitleIdentity.ServerSidecar(subtitle.index, media)
    }
    if (downloaded) {
        val downloadId = subtitle.downloadId
        return if (downloadId != null) {
            SubtitleIdentity.Downloaded(downloadId, media)
        } else {
            SubtitleIdentity.LocalMedia3(media)
        }
    }

    val embedded = (source == "embedded" && subtitle.url.isBlank()) ||
        (source == null && catalogSource == "embedded" && subtitle.url.isBlank())
    if (embedded) {
        // PGS stays client-mounted (the server sidecars it as `.sup`); VobSub
        // and DVB have no sidecar route and always burn in.
        return if (
            isBitmapSubtitleCodecOrMime(media.codecFamily) &&
            !isClientMountableBitmapCodecFamily(media.codecFamily)
        ) {
            SubtitleIdentity.ServerBurnIn(subtitle.index, media)
        } else {
            SubtitleIdentity.Embedded(
                serverIndex = subtitle.index,
                media = media,
            )
        }
    }

    val external = source == "external" ||
        catalogSource == "external" ||
        source == "server_artifact" ||
        subtitle.url.isNotBlank()
    val mountableBitmapArtifact = subtitle.url.isNotBlank() &&
        isClientMountableBitmapCodecFamily(media.codecFamily)
    return if (
        external &&
        isBitmapSubtitleCodecOrMime(media.codecFamily) &&
        !mountableBitmapArtifact
    ) {
        SubtitleIdentity.ServerBurnIn(subtitle.index, media)
    } else {
        SubtitleIdentity.ServerSidecar(subtitle.index, media)
    }
}

internal fun resolveMobileSubtitleOrdinal(
    identity: SubtitleIdentity,
    subtitles: List<PlayerSubtitleInfo>,
): Int? {
    if (identity == SubtitleIdentity.Off) return -1
    if (identity is SubtitleIdentity.Downloaded) {
        return subtitles.indices
            .filter { index ->
                val row = subtitles[index]
                row.downloadId == identity.downloadId &&
                    mobileSubtitleIdentity(row) is SubtitleIdentity.Downloaded
            }
            .singleOrNull()
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
                    rowIdentity.media.matchesMobileIdentity(identity.media)
            }
            is SubtitleIdentity.Downloaded -> {
                val rowIdentity = mobileSubtitleIdentity(row)
                row.downloadId == identity.downloadId &&
                    rowIdentity is SubtitleIdentity.Downloaded &&
                    rowIdentity.media.matchesMobileIdentity(identity.media)
            }
            is SubtitleIdentity.LocalMedia3 -> {
                val rowIdentity = mobileSubtitleIdentity(row)
                identity.media.trackId != null &&
                    rowIdentity is SubtitleIdentity.LocalMedia3 &&
                    rowIdentity.media.matchesMobileIdentity(identity.media)
            }
        }
    }
    if (exactMatches.size == 1) return exactMatches.single()
    if (exactMatches.size > 1) return null

    val targetMedia = identity.mediaIdentityForMobileFallback() ?: return null
    if (!targetMedia.hasPositiveMobileDiscriminator()) return null
    val typedMatches = subtitles.indices.filter { index ->
        val rowIdentity = mobileSubtitleIdentity(subtitles[index])
        val rowMedia = rowIdentity.mediaIdentityForMobileFallback() ?: return@filter false
        identity::class == rowIdentity::class && rowMedia.matchesMobileIdentity(targetMedia)
    }
    return typedMatches.singleOrNull()
}

private fun SubtitleIdentity.mediaIdentityForMobileFallback(): SubtitleMediaIdentity? = when (this) {
    is SubtitleIdentity.ServerSidecar -> media
    is SubtitleIdentity.ServerBurnIn -> media
    is SubtitleIdentity.Embedded -> media
    is SubtitleIdentity.LocalMedia3 -> media
    SubtitleIdentity.Off,
    is SubtitleIdentity.Downloaded,
    -> null
}

private fun SubtitleMediaIdentity.matchesMobileIdentity(expected: SubtitleMediaIdentity): Boolean {
    val expectedTrackId = expected.trackId?.trim()?.takeIf(String::isNotBlank)
    if (expectedTrackId != null && trackId?.trim() != expectedTrackId) return false
    val expectedLabel = expected.label.normalizedMobileLabel()
    if (expectedLabel != null && label.normalizedMobileLabel() != expectedLabel) return false
    val expectedLanguage = canonicalSubtitleLanguage(expected.language)
    val expectedCodec = canonicalSubtitleCodecFamily(expected.codecFamily)
    if (
        expectedLanguage != null &&
        canonicalSubtitleLanguage(language) != expectedLanguage
    ) {
        return false
    }
    if (
        expectedCodec != null &&
        canonicalSubtitleCodecFamily(codecFamily) != expectedCodec
    ) {
        return false
    }
    if (expected.forced != null && forced != expected.forced) return false
    if (
        expected.hearingImpaired != null &&
        hearingImpaired != expected.hearingImpaired
    ) {
        return false
    }
    return true
}

private fun SubtitleMediaIdentity.hasPositiveMobileDiscriminator(): Boolean =
    !trackId.isNullOrBlank() ||
        !label.isNullOrBlank() ||
        canonicalSubtitleLanguage(language) != null ||
        !codecFamily.isNullOrBlank() ||
        forced == true ||
        hearingImpaired == true

private fun String?.normalizedMobileLabel(): String? =
    this?.trim()?.takeIf(String::isNotBlank)?.lowercase()

internal fun resolveMobileAutoSubtitleSelection(
    audioTracks: List<AudioTrack>,
    selectedAudioIndex: Int,
    subtitles: List<PlayerSubtitleInfo>,
    preferredLanguage: String?,
    subtitleMode: String?,
    showForcedSubtitles: Boolean,
): MobileSubtitleAutoSelection {
    if (subtitles.isEmpty()) return MobileSubtitleAutoSelection.NoChange

    val mode = subtitleMode?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: "auto"
    if (mode == "off") return MobileSubtitleAutoSelection.Disable

    if (preferredLanguage != null && preferredLanguage.isBlank()) {
        return MobileSubtitleAutoSelection.Disable
    }
    val targetLanguage = canonicalSubtitleLanguage(preferredLanguage)
    if (targetLanguage == null) {
        if (mode == "always") {
            return bestAutoSubtitleOrdinal(
                subtitles = subtitles,
                targetLanguage = null,
                preferForced = showForcedSubtitles,
            )?.let(MobileSubtitleAutoSelection::Select)
                ?: MobileSubtitleAutoSelection.NoChange
        }
        return MobileSubtitleAutoSelection.NoChange
    }

    // An ORDINAL into audioTracks: audio carries no index on the wire, so the
    // index search that used to come first matched nothing above row zero and
    // only worked because of the ordinal fallback behind it.
    val selectedAudioLanguage = audioTracks.getOrNull(selectedAudioIndex)
    val selectedAudioMatches = canonicalSubtitleLanguage(selectedAudioLanguage?.language) == targetLanguage

    if (mode == "auto" && selectedAudioMatches) {
        if (showForcedSubtitles) {
            bestForcedAutoSubtitleOrdinal(
                subtitles = subtitles,
                targetLanguage = targetLanguage,
            )?.let { return MobileSubtitleAutoSelection.Select(it) }
        }
        return MobileSubtitleAutoSelection.Disable
    }

    val targetOrdinal = bestAutoSubtitleOrdinal(
        subtitles = subtitles,
        targetLanguage = targetLanguage,
        preferForced = showForcedSubtitles,
    ) ?: if (showForcedSubtitles) {
        subtitles.indexOfFirst { it.forced == true }.takeIf { it >= 0 }
    } else {
        null
    }

    return targetOrdinal
        ?.let(MobileSubtitleAutoSelection::Select)
        ?: MobileSubtitleAutoSelection.NoChange
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

private fun bestAutoSubtitleOrdinal(
    subtitles: List<PlayerSubtitleInfo>,
    targetLanguage: String?,
    preferForced: Boolean,
): Int? {
    val pool = subtitles.withIndex().filter { (_, subtitle) ->
        targetLanguage == null || canonicalSubtitleLanguage(subtitle.language) == targetLanguage
    }
    if (pool.isEmpty()) return null

    if (preferForced) {
        pool.firstOrNull { (_, subtitle) ->
            subtitle.forced == true && !subtitle.isEffectivelyHearingImpaired() && !subtitle.isBitmap()
        }?.let { return it.index }
    }
    pool.firstOrNull { (_, subtitle) ->
        subtitle.forced != true && !subtitle.isEffectivelyHearingImpaired() && !subtitle.isBitmap()
    }?.let { return it.index }
    pool.firstOrNull { (_, subtitle) ->
        subtitle.forced != true && !subtitle.isBitmap()
    }?.let { return it.index }
    pool.firstOrNull { (_, subtitle) -> !subtitle.isBitmap() }
        ?.let { return it.index }
    return pool.first().index
}

private fun bestForcedAutoSubtitleOrdinal(
    subtitles: List<PlayerSubtitleInfo>,
    targetLanguage: String?,
): Int? {
    val pool = subtitles.withIndex().filter { (_, subtitle) ->
        subtitle.forced == true &&
            (targetLanguage == null || canonicalSubtitleLanguage(subtitle.language) == targetLanguage)
    }
    if (pool.isEmpty()) return null

    pool.firstOrNull { (_, subtitle) -> !subtitle.isEffectivelyHearingImpaired() && !subtitle.isBitmap() }
        ?.let { return it.index }
    pool.firstOrNull { (_, subtitle) -> !subtitle.isEffectivelyHearingImpaired() }
        ?.let { return it.index }
    return pool.first().index
}

private fun PlayerSubtitleInfo.isEffectivelyHearingImpaired(): Boolean =
    label.indicatesHearingImpairedSubtitle() ||
        source.indicatesHearingImpairedSubtitle() ||
        url.indicatesHearingImpairedSubtitle()

private fun String?.indicatesHearingImpairedSubtitle(): Boolean {
    val value = this?.takeIf { it.isNotBlank() } ?: return false
    val lower = value.lowercase()
    return lower.contains("closed caption") ||
        lower.contains("hearing impaired") ||
        lower.contains("hearing-impaired") ||
        lower.contains("hearing") ||
        hearingImpairedSubtitleTokenRegex.containsMatchIn(value)
}

private fun PlayerSubtitleInfo.isBitmap(): Boolean =
    isBitmapSubtitleCodecOrMime(codec ?: subtitleCodecFromUrl(url))

private fun subtitleCodecFromUrl(url: String?): String? =
    url
        ?.substringBefore('?')
        ?.substringBefore('#')
        ?.substringAfterLast('.', missingDelimiterValue = "")
        ?.takeIf { it.isNotBlank() }
