package org.siloserver.silo.playback

import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.model.catalog.PlaybackEdition
import org.siloserver.silo.model.catalog.PlaybackVariant
import org.siloserver.silo.model.catalog.playbackEditions
import org.siloserver.silo.model.playback.PLAYBACK_QUALITY_AUTO
import org.siloserver.silo.model.playback.videoQualityPreference

/**
 * The file a detail page shows, and that its Play and Download use. Edition is
 * the cut; Version Auto picks a file within it and never changes it.
 *
 * - [pinnedFileId], an explicit Version pick, wins. Picking an edition clears
 *   it, so it is normally inside the current edition.
 * - The edition is [editionId] (the viewer's pick), else the one last played
 *   (the edition of [lastFileId], then [lastEditionKey]), else the one holding
 *   the best file for [preferredQuality].
 * - Auto is the best file in that edition for [preferredQuality], or the
 *   closest file above it when none fits. History picks only the edition, with
 *   one exception: a later part of a multi-part item the viewer stopped in, so
 *   Resume continues that part.
 */
fun selectDetailPlaybackVersion(
    versions: List<FileVersion>,
    variants: List<PlaybackVariant>,
    pinnedFileId: Int?,
    editionId: String?,
    lastFileId: Int?,
    lastEditionKey: String?,
    preferredQuality: String?,
): FileVersion? {
    if (versions.isEmpty()) return null
    val editions = playbackEditions(versions, variants)
    versions.firstOrNull { it.fileId == pinnedFileId }?.let { return it }
    if (editions.isEmpty()) return selectAutoVersion(versions, preferredQuality)
    val edition = editionId?.let { id -> editions.firstOrNull { it.id == id } }
        ?: lastPlayedEdition(editions, lastFileId, lastEditionKey)
        ?: selectAutoVersion(editions.flatMap { it.versions }, preferredQuality)
            .let { best -> editions.first { best.fileId in it.fileIds } }
    val lastFile = versions.firstOrNull { it.fileId == lastFileId }
    if (lastFile != null && lastFile.fileId in edition.fileIds &&
        firstPlaybackPart(versions, lastFile).fileId != lastFile.fileId
    ) {
        return lastFile
    }
    return edition.autoVersion(preferredQuality)
}

/** The file Version Auto picks in this edition, which picking the edition keeps. */
fun PlaybackEdition.autoVersion(preferredQuality: String?): FileVersion =
    selectAutoVersion(versions, preferredQuality)

/**
 * The file id Play sends for a detail page's [displayed] version, so Play
 * starts the file the page shows. A pin, or a file whose tracks were picked,
 * is always sent. An Auto file is sent unless it is the last-played file: the
 * player picks that one itself and keeps a start from the beginning on a
 * multi-part item's first part.
 */
fun detailPlaybackFileId(
    displayed: FileVersion?,
    pinned: Boolean,
    tracksPicked: Boolean,
    lastFileId: Int?,
): Int? = displayed?.fileId?.takeIf { pinned || tracksPicked || it != lastFileId }

private fun lastPlayedEdition(
    editions: List<PlaybackEdition>,
    lastFileId: Int?,
    lastEditionKey: String?,
): PlaybackEdition? {
    lastFileId?.let { id -> editions.firstOrNull { id in it.fileIds } }?.let { return it }
    val key = lastEditionKey?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return editions.firstOrNull { edition ->
        edition.versions.any { it.editionKey?.trim().equals(key, ignoreCase = true) }
    }
}

/** The best file at or below the preference, else the closest file above it. */
private fun selectAutoVersion(versions: List<FileVersion>, preferredQuality: String?): FileVersion {
    val target = videoQualityPreference(preferredQuality) ?: PLAYBACK_QUALITY_AUTO
    if (target == PLAYBACK_QUALITY_AUTO) return selectPlaybackVersion(versions, null, target)
    // A preference that names no resolution reads as Auto.
    val cap = playbackResolutionRank(target).takeIf { it > 0 }
        ?: return selectPlaybackVersion(versions, null, PLAYBACK_QUALITY_AUTO)
    val (fits, above) = versions.partition { playbackResolutionRank(it.resolution) <= cap }
    if (fits.isNotEmpty()) return selectPlaybackVersion(fits, null, target)
    val closest = above.minOf { playbackResolutionRank(it.resolution) }
    return selectPlaybackVersion(above.filter { playbackResolutionRank(it.resolution) == closest }, null, target)
}
