package org.siloserver.silo.playback

import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.model.playback.SUBTITLE_DELIVERY_BURN_IN_ONLY
import org.siloserver.silo.model.playback.SUBTITLE_DELIVERY_SIDECAR
import org.siloserver.silo.model.playback.SUBTITLE_SOURCE_DOWNLOADED
import org.siloserver.silo.model.playback.SubtitleIdentity
import org.siloserver.silo.model.playback.SubtitleMediaIdentity
import org.siloserver.silo.model.playback.isLocalDownloadedSubtitle

const val DOWNLOADED_SUBTITLE_ARTIFACT_TRACK_ID_PREFIX =
    "silo-downloaded-subtitle:"

private val HEARING_IMPAIRED_TOKEN_REGEX =
    Regex("""(^|[^a-z0-9])(cc|sdh)([^a-z0-9]|$)""")

/**
 * Builds the canonical typed identity for a subtitle row on both Android
 * clients. Protocol-v3 server identity wins over local download metadata.
 */
fun playbackSubtitleIdentity(subtitle: PlayerSubtitleInfo): SubtitleIdentity {
    val source = subtitle.source?.trim()?.lowercase()
    val catalogSource = subtitle.catalogSource?.trim()?.lowercase()
    val media = SubtitleMediaIdentity(
        trackId = subtitle.serverTrackId
            ?: subtitle.downloadId?.let(::downloadedSubtitleArtifactTrackId)
            ?: subtitle.mediaTrackId,
        label = subtitle.catalogLabel ?: subtitle.label,
        language = canonicalSubtitleLanguage(subtitle.language),
        codecFamily = canonicalSubtitleCodecFamily(
            subtitle.codec ?: subtitle.url.subtitleCodecFromUrl(),
        ),
        forced = subtitle.forced,
        hearingImpaired = subtitleLabelIndicatesHearingImpaired(
            subtitle.catalogLabel ?: subtitle.label,
        ).takeIf { it },
    )

    when (subtitle.serverDelivery) {
        SUBTITLE_DELIVERY_BURN_IN_ONLY ->
            return SubtitleIdentity.ServerBurnIn(subtitle.index, media)
        SUBTITLE_DELIVERY_SIDECAR ->
            return SubtitleIdentity.ServerSidecar(subtitle.index, media)
    }
    if (subtitle.isLocalDownloadedSubtitle()) {
        return subtitle.downloadId
            ?.let { SubtitleIdentity.Downloaded(it, media) }
            ?: SubtitleIdentity.LocalMedia3(media)
    }

    val embedded = subtitle.url.isBlank() &&
        (source == "embedded" || (source == null && catalogSource == "embedded"))
    if (embedded) {
        return if (
            isBitmapSubtitleCodecFamily(media.codecFamily) &&
            !isClientMountableBitmapCodecFamily(media.codecFamily)
        ) {
            SubtitleIdentity.ServerBurnIn(subtitle.index, media)
        } else {
            SubtitleIdentity.Embedded(subtitle.index, media)
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
        isBitmapSubtitleCodecFamily(media.codecFamily) &&
        !mountableBitmapArtifact
    ) {
        SubtitleIdentity.ServerBurnIn(subtitle.index, media)
    } else {
        SubtitleIdentity.ServerSidecar(subtitle.index, media)
    }
}

/**
 * Resolves a persisted local-download identity against the current row list.
 *
 * New rows retain the durable database id and match it exactly. A legacy
 * synthetic Media3 id is not a protocol identity, so it is removed before a
 * unique metadata match against authoritative downloaded inventory. Negative
 * booleans are also treated as unknown: by themselves they are not enough to
 * identify a track, and older projections did not preserve them consistently.
 */
fun resolveDownloadedSubtitlePreferenceOrdinal(
    identity: SubtitleIdentity.Downloaded,
    subtitles: List<PlayerSubtitleInfo>,
): Int? {
    val directMatches = subtitles.indices.filter { index ->
        subtitles[index].downloadId == identity.downloadId
    }
    if (directMatches.size == 1) return directMatches.single()
    if (directMatches.size > 1) return null

    val syntheticTrackId = downloadedSubtitleArtifactTrackId(identity.downloadId)
    val isLegacySyntheticIdentity = identity.media.trackId == syntheticTrackId
    val expected = identity.media.copy(
        trackId = identity.media.trackId.takeUnless { isLegacySyntheticIdentity },
        forced = identity.media.forced.takeUnless {
            isLegacySyntheticIdentity && it == false
        },
        hearingImpaired = identity.media.hearingImpaired.takeUnless {
            isLegacySyntheticIdentity && it == false
        },
    )
    if (!expected.hasPositiveSubtitleDiscriminator()) return null

    return subtitles.indices.filter { index ->
        val row = subtitles[index]
        if (
            row.serverTrackId.isNullOrBlank() ||
            row.serverDelivery.isNullOrBlank() ||
            !row.source.equals(SUBTITLE_SOURCE_DOWNLOADED, ignoreCase = true)
        ) {
            return@filter false
        }
        val actual = playbackSubtitleIdentity(row).subtitleMediaIdentityOrNull()
            ?: return@filter false
        actual.matchesSubtitleMediaIdentity(expected)
    }.singleOrNull()
}

fun SubtitleIdentity.subtitleMediaIdentityOrNull(): SubtitleMediaIdentity? = when (this) {
    is SubtitleIdentity.ServerSidecar -> media
    is SubtitleIdentity.ServerBurnIn -> media
    is SubtitleIdentity.Embedded -> media
    is SubtitleIdentity.Downloaded -> media
    is SubtitleIdentity.LocalMedia3 -> media
    SubtitleIdentity.Off -> null
}

fun SubtitleMediaIdentity.matchesSubtitleMediaIdentity(
    expected: SubtitleMediaIdentity,
): Boolean {
    val expectedTrackId = expected.trackId.normalizedSubtitleValue()
    if (expectedTrackId != null && trackId.normalizedSubtitleValue() != expectedTrackId) {
        return false
    }
    val expectedLabel = expected.label.normalizedSubtitleValue()?.lowercase()
    if (expectedLabel != null && label.normalizedSubtitleValue()?.lowercase() != expectedLabel) {
        return false
    }
    val expectedLanguage = canonicalSubtitleLanguage(expected.language)
    if (expectedLanguage != null && canonicalSubtitleLanguage(language) != expectedLanguage) {
        return false
    }
    val expectedCodec = canonicalSubtitleCodecFamily(expected.codecFamily)
    if (expectedCodec != null && canonicalSubtitleCodecFamily(codecFamily) != expectedCodec) {
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

fun SubtitleMediaIdentity.hasPositiveSubtitleDiscriminator(): Boolean =
    !trackId.isNullOrBlank() ||
        !label.isNullOrBlank() ||
        canonicalSubtitleLanguage(language) != null ||
        canonicalSubtitleCodecFamily(codecFamily) != null ||
        forced == true ||
        hearingImpaired == true

fun downloadedSubtitleArtifactTrackId(downloadId: Int): String =
    "$DOWNLOADED_SUBTITLE_ARTIFACT_TRACK_ID_PREFIX$downloadId"

private fun String.subtitleCodecFromUrl(): String? =
    substringBefore('?')
        .substringBefore('#')
        .substringAfterLast('.', missingDelimiterValue = "")
        .takeIf(String::isNotBlank)

fun subtitleLabelIndicatesHearingImpaired(label: String?): Boolean {
    val value = label?.lowercase() ?: return false
    if (
        value.contains("closed caption") ||
        value.contains("hearing impaired") ||
        value.contains("hearing-impaired")
    ) {
        return true
    }
    return HEARING_IMPAIRED_TOKEN_REGEX.containsMatchIn(value)
}

private fun String?.normalizedSubtitleValue(): String? =
    this?.trim()?.takeIf(String::isNotBlank)
