package org.siloserver.silo.playback

import org.siloserver.silo.model.catalog.FileVersion

private val SEQUENTIAL_PART_KINDS = setOf("multipart_movie", "split_episode")

private fun FileVersion.isSequentialPart(): Boolean =
    presentationKind in SEQUENTIAL_PART_KINDS && presentationPartIndex != null

private fun FileVersion.isSamePresentationAs(other: FileVersion): Boolean =
    presentationKind == other.presentationKind &&
        presentationGroupKey == other.presentationGroupKey &&
        editionKey == other.editionKey

/**
 * The file that plays after [currentFileId] when it is one part of a
 * multi-part movie or split episode: the lowest-numbered later part of the
 * same presentation and edition, preferring the current file's resolution.
 * Null when [currentFileId] is not a part or is the last one.
 */
fun nextPlaybackPartFileId(versions: List<FileVersion>, currentFileId: Int?): Int? {
    val current = versions.firstOrNull { it.fileId == currentFileId } ?: return null
    if (!current.isSequentialPart()) return null
    val currentIndex = current.presentationPartIndex ?: return null
    val later = versions.filter {
        it.isSequentialPart() && it.isSamePresentationAs(current) && it.presentationPartIndex!! > currentIndex
    }
    val nextIndex = later.minOfOrNull { it.presentationPartIndex!! } ?: return null
    val candidates = later.filter { it.presentationPartIndex == nextIndex }
    return (candidates.firstOrNull { it.resolution == current.resolution } ?: candidates.first()).fileId
}

/**
 * The first part of [selected]'s presentation, for a start from the
 * beginning: a multi-part item starts at its lowest-numbered part, preferring
 * [selected]'s resolution. Anything that is not a later part is returned as is.
 */
fun firstPlaybackPart(versions: List<FileVersion>, selected: FileVersion): FileVersion {
    if (!selected.isSequentialPart()) return selected
    val parts = versions.filter { it.isSequentialPart() && it.isSamePresentationAs(selected) }
    val firstIndex = parts.minOfOrNull { it.presentationPartIndex!! } ?: return selected
    if (selected.presentationPartIndex == firstIndex) return selected
    val candidates = parts.filter { it.presentationPartIndex == firstIndex }
    return candidates.firstOrNull { it.resolution == selected.resolution } ?: candidates.first()
}
