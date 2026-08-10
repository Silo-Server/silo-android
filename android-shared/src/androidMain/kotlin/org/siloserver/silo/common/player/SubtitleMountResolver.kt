package org.siloserver.silo.common.player

import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.model.playback.SubtitleIdentity
import org.siloserver.silo.model.playback.SubtitleMediaIdentity
import org.siloserver.silo.model.playback.isLocalDownloadedSubtitle
import org.siloserver.silo.playback.canonicalSubtitleCodecFamily
import org.siloserver.silo.playback.canonicalSubtitleLanguage
import org.siloserver.silo.playback.downloadedSubtitleArtifactTrackId
import org.siloserver.silo.playback.isTextSubtitleCodecFamily
import org.siloserver.silo.playback.subtitleLabelIndicatesHearingImpaired

private const val SUBTITLE_ARTIFACT_TRACK_ID_PREFIX = "silo-subtitle:"

/**
 * Stable Media3 identity for a server-authored subtitle artifact.
 *
 * The index is the server's combined subtitle index, not a Media3 ordinal.
 */
fun subtitleArtifactTrackId(serverIndex: Int): String =
    "$SUBTITLE_ARTIFACT_TRACK_ID_PREFIX$serverIndex"

/**
 * True when a mounted Media3 `Format.id` denotes [expected].
 *
 * A sidecar merged with the primary stream comes back from Media3 carrying the
 * MergingMediaSource child index: the id we authored as `silo-subtitle:0` is
 * reported as `1:silo-subtitle:0`, while the primary stream's own tracks read
 * `0:3`, `0:4` and so on. Comparing with `==` therefore never matches a merged
 * sidecar, so the mount waits for a track that appears to be absent and the
 * whole subtitle transaction times out and rolls back.
 *
 * Only a purely numeric prefix is accepted, so this can never collide with an
 * authored id that happens to contain a colon.
 */
fun trackIdDenotes(actual: String?, expected: String): Boolean {
    if (actual == null) return false
    if (actual == expected) return true
    val separator = actual.indexOf(':')
    if (separator <= 0) return false
    if (!actual.substring(0, separator).all(Char::isDigit)) return false
    return actual.substring(separator + 1) == expected
}

/**
 * Complete Media3 subtitle metadata retained by both phone and TV adapters.
 */
data class MountedSubtitleTrack(
    val index: Int,
    val trackId: String?,
    val label: String?,
    val language: String?,
    val codec: String?,
    val forced: Boolean?,
    val hearingImpaired: Boolean?,
)

data class MountedSubtitleMatch(
    val track: MountedSubtitleTrack,
)

/**
 * Resolves a committed typed identity against a mounted Media3 track snapshot.
 *
 * Exact IDs are checked across the complete snapshot before any metadata
 * fallback. Server sidecars are exact-ID only. Other identity types may use a
 * complete typed metadata fallback, but may never fall through to a
 * server-authored sidecar ID.
 */
fun resolveMountedSubtitle(
    identity: SubtitleIdentity,
    tracks: List<MountedSubtitleTrack>,
): MountedSubtitleMatch? {
    val expectedTrackId = identity.expectedMediaTrackId()
    if (expectedTrackId != null) {
        tracks.firstOrNull { trackIdDenotes(it.trackId, expectedTrackId) }
            ?.let { return MountedSubtitleMatch(it) }
    }

    val media = identity.fallbackMediaIdentity() ?: return null
    if (!media.hasTypedFallback()) return null

    val typedMatches = tracks
        .asSequence()
        .filterNot { it.hasReservedArtifactId() }
        .filter { it.matchesTypedMetadata(media) }
        .toList()
    if (typedMatches.isEmpty()) return null

    val targetLabel = normalizedLabel(media.label)
    val labelMatches = if (targetLabel == null) {
        emptyList()
    } else {
        typedMatches.filter { normalizedLabel(it.label) == targetLabel }
    }
    return when {
        labelMatches.size == 1 -> MountedSubtitleMatch(labelMatches.single())
        typedMatches.size == 1 -> MountedSubtitleMatch(typedMatches.single())
        else -> null
    }
}

/**
 * Bridges current catalog rows to typed identity. Both phone and TV use this
 * while the coordinator adapters still receive [PlayerSubtitleInfo].
 */
fun resolveMountedSubtitle(
    subtitle: PlayerSubtitleInfo,
    tracks: List<MountedSubtitleTrack>,
): MountedSubtitleMatch? {
    val explicitSource = subtitle.effectiveSubtitleSource()
    val isEmbedded = when {
        subtitle.url.isNotBlank() -> false
        explicitSource.equals("embedded", ignoreCase = true) -> true
        explicitSource != null -> false
        else -> subtitle.url.isBlank()
    }
    val isDownloadedArtifact = subtitle.isDownloadedSubtitleArtifact()
    if (!isEmbedded) {
        if (isDownloadedArtifact) {
            // Modern downloaded rows are domain-ID exact only. Legacy rows
            // without that optional field continue below to non-reserved typed
            // metadata matching; no downloaded identity is synthesized.
            subtitle.downloadId?.let { downloadId ->
                return resolveMountedSubtitle(
                    SubtitleIdentity.Downloaded(
                        downloadId = downloadId,
                        media = SubtitleMediaIdentity(),
                    ),
                    tracks,
                )
            }
        } else {
            resolveMountedSubtitle(SubtitleIdentity.ServerSidecar(subtitle.index), tracks)
                ?.let { return it }
        }
        if (subtitle.url.isBlank()) return null
        if (!isDownloadedArtifact && tracks.any(MountedSubtitleTrack::hasReservedArtifactId)) {
            return null
        }
    }

    val codecFamilies = listOfNotNull(
        subtitle.url.substringBefore('?').substringBefore('#').substringAfterLast('.', "")
            .takeIf(String::isNotBlank),
        subtitle.codec,
    ).distinct()
    val familiesToTry: List<String?> =
        if (codecFamilies.isEmpty()) listOf(null) else codecFamilies
    for (codec in familiesToTry) {
        val media = SubtitleMediaIdentity(
            label = subtitle.label,
            language = subtitle.language,
            codecFamily = codec,
            forced = subtitle.forced,
            hearingImpaired = subtitle.label
                ?.takeIf(::subtitleLabelIndicatesHearingImpaired)
                ?.let { true },
        )
        val identity = if (isEmbedded) {
            SubtitleIdentity.Embedded(subtitle.index, media)
        } else {
            SubtitleIdentity.LocalMedia3(media)
        }
        resolveMountedSubtitle(identity, tracks)?.let { return it }
    }
    return null
}

private fun SubtitleIdentity.expectedMediaTrackId(): String? = when (this) {
    is SubtitleIdentity.ServerSidecar -> subtitleArtifactTrackId(serverIndex)
    is SubtitleIdentity.Embedded -> media.trackId.normalizedNonServerTrackId()
    is SubtitleIdentity.Downloaded -> downloadedSubtitleArtifactTrackId(downloadId)
    is SubtitleIdentity.LocalMedia3 -> media.trackId.normalizedNonServerTrackId()
    SubtitleIdentity.Off,
    is SubtitleIdentity.ServerBurnIn,
    -> null
}

private fun SubtitleIdentity.fallbackMediaIdentity(): SubtitleMediaIdentity? = when (this) {
    is SubtitleIdentity.Embedded -> media.takeUnless { it.trackId.isReservedArtifactTrackId() }
    is SubtitleIdentity.LocalMedia3 -> media.takeUnless { it.trackId.isReservedArtifactTrackId() }
    SubtitleIdentity.Off,
    is SubtitleIdentity.ServerSidecar,
    is SubtitleIdentity.ServerBurnIn,
    is SubtitleIdentity.Downloaded,
    -> null
}

private fun SubtitleMediaIdentity.hasTypedFallback(): Boolean =
    canonicalSubtitleLanguage(language) != null ||
        normalizedSubtitleCodecFamily(codecFamily) != null ||
        forced != null ||
        hearingImpaired != null

private fun MountedSubtitleTrack.matchesTypedMetadata(identity: SubtitleMediaIdentity): Boolean {
    val targetLanguage = canonicalSubtitleLanguage(identity.language)
    val targetCodec = normalizedSubtitleCodecFamily(identity.codecFamily)

    if (targetLanguage != null && canonicalSubtitleLanguage(language) != targetLanguage) return false
    if (targetCodec != null && normalizedSubtitleCodecFamily(codec) != targetCodec) {
        // A catalog row names its SOURCE format (subrip, ssa, …) but the server
        // serves the artifact it materialises for that row as WebVTT. Requiring
        // an exact family match meant a picked embedded text track could never
        // resolve to the sidecar the server had just produced for it: the mount
        // deadline fired and the selection rolled back to Off. Bitmap families
        // are never converted, so they stay strict.
        val mountedCodec = normalizedSubtitleCodecFamily(codec)
        if (!isTextSubtitleCodecFamily(targetCodec) || !isTextSubtitleCodecFamily(mountedCodec)) {
            return false
        }
    }
    if (identity.forced != null && forced != identity.forced) return false
    // A server artifact's hearing-impaired flag is only ever inferred from its
    // label, and the server labels artifacts generically. When the mount carries
    // no positive SDH signal it is unknown, not "not SDH" — treating it as the
    // latter meant an SDH pick never matched the sidecar the server had just
    // produced for it. The reserved artifact id already pins WHICH row this is,
    // so nothing is loosened for genuinely distinct local tracks.
    val hearingImpairedIsInferred = hasReservedArtifactId() && hearingImpaired != true
    if (
        identity.hearingImpaired != null &&
        !hearingImpairedIsInferred &&
        hearingImpaired != identity.hearingImpaired
    ) {
        return false
    }
    return true
}

private fun MountedSubtitleTrack.hasReservedArtifactId(): Boolean =
    trackId.isReservedArtifactTrackId()

private fun String?.normalizedValue(): String? =
    this?.trim()?.takeIf(String::isNotEmpty)

private fun String?.normalizedNonServerTrackId(): String? =
    normalizedValue()?.takeUnless(String::isReservedArtifactTrackId)

private fun String?.isReservedArtifactTrackId(): Boolean =
    this?.startsWith(SUBTITLE_ARTIFACT_TRACK_ID_PREFIX) == true ||
        this?.startsWith(
            org.siloserver.silo.playback.DOWNLOADED_SUBTITLE_ARTIFACT_TRACK_ID_PREFIX,
        ) == true

internal fun PlayerSubtitleInfo.isDownloadedSubtitleArtifact(): Boolean =
    isLocalDownloadedSubtitle()

private fun PlayerSubtitleInfo.effectiveSubtitleSource(): String? =
    source.normalizedValue() ?: catalogSource.normalizedValue()

private fun normalizedLabel(label: String?): String? =
    label.normalizedValue()?.lowercase()

fun normalizedSubtitleCodecFamily(codecOrMime: String?): String? {
    return canonicalSubtitleCodecFamily(codecOrMime)
}
