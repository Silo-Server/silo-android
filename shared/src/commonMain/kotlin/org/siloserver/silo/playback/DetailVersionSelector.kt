package org.siloserver.silo.playback

import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.model.catalog.LeafItemUserData
import org.siloserver.silo.model.catalog.PlaybackEdition
import org.siloserver.silo.model.catalog.PlaybackVariant
import org.siloserver.silo.model.catalog.playbackEditions
import org.siloserver.silo.model.settings.QualityPresets

/**
 * The file a detail page shows, and that its Play and Download use. Edition is
 * the cut; Version Auto picks a file within it and never changes it. Web,
 * Apple and Roku follow the same rule, so every client picks the same file.
 *
 * - [pinnedFileId], an explicit Version pick, wins. Picking an edition clears
 *   it, so it is normally inside the current edition.
 * - The edition is [editionId] (the viewer's pick), else the one last played
 *   ([lastEditionKey], where a present "" names the unnamed edition, then the
 *   edition of [lastFileId]), else the one whose Auto file compares best
 *   across cuts ([openingEdition]).
 * - Auto is the edition's best first-part file at or below [preferredQuality],
 *   else the closest file above it, else a file of unknown resolution. History
 *   picks only the edition, with one exception: while the item is
 *   [inProgress], a later part of a multi-part item the viewer stopped in, so
 *   Resume continues that part. Without a resume point Auto is part 1, so a
 *   rewatch starts at the beginning.
 */
fun selectDetailPlaybackVersion(
    versions: List<FileVersion>,
    variants: List<PlaybackVariant>,
    pinnedFileId: Int?,
    editionId: String?,
    lastFileId: Int?,
    lastEditionKey: String?,
    inProgress: Boolean,
    preferredQuality: String?,
): FileVersion? {
    if (versions.isEmpty()) return null
    versions.firstOrNull { it.fileId == pinnedFileId }?.let { return it }
    val cap = qualityCap(preferredQuality)
    val editions = playbackEditions(versions, variants)
    if (editions.isEmpty()) return autoFile(versions.firstParts(versions), cap)
    val edition = editionId?.let { id -> editions.firstOrNull { it.id == id } }
        ?: lastPlayedEdition(editions, lastFileId, lastEditionKey)
        ?: openingEdition(editions, cap)
    val lastFile = versions.firstOrNull { it.fileId == lastFileId }
    if (inProgress && lastFile != null && lastFile.fileId in edition.fileIds &&
        firstPlaybackPart(versions, lastFile).fileId != lastFile.fileId
    ) {
        return lastFile
    }
    return edition.autoFile(cap)
}

/**
 * Whether the item has a resume point: a saved position above 0. The played
 * flag is ignored, because the server resets the position to 0 when a title
 * finishes and keeps played set during a rewatch.
 */
val LeafItemUserData?.hasResumePoint: Boolean
    get() = (this?.positionSeconds ?: 0.0) > 0.0

/** The file Version Auto picks in this edition, which picking the edition keeps. */
fun PlaybackEdition.autoVersion(preferredQuality: String?): FileVersion = autoFile(qualityCap(preferredQuality))

private fun PlaybackEdition.autoFile(cap: Int?): FileVersion = autoFile(versions.firstParts(versions), cap)

/** Auto starts a multi-part item at its first part; later parts are reached by playing on. */
private fun List<FileVersion>.firstParts(all: List<FileVersion>): List<FileVersion> =
    filter { firstPlaybackPart(all, it).fileId == it.fileId }.ifEmpty { this }

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

/** The last-played edition: [lastEditionKey] when it names one, else the edition of [lastFileId]. */
private fun lastPlayedEdition(
    editions: List<PlaybackEdition>,
    lastFileId: Int?,
    lastEditionKey: String?,
): PlaybackEdition? {
    lastEditionKey?.trim()?.let { key -> editions.firstOrNull { it.id == key } }?.let { return it }
    return lastFileId?.let { id -> editions.firstOrNull { id in it.fileIds } }
}

/**
 * The edition a page opens on with no history: each edition's Auto file
 * compared across cuts by fit (within the cap, above it, unknown resolution),
 * then resolution (higher within the cap, lower above it), then HDR, then
 * edition preference, then server order. File size is not compared across cuts.
 */
private fun openingEdition(editions: List<PlaybackEdition>, cap: Int?): PlaybackEdition =
    editions.mapIndexed { index, edition -> OpeningCandidate(index, edition, edition.autoFile(cap), cap) }
        .minWith(
            compareBy<OpeningCandidate> { it.fit }
                .thenBy { if (it.fit == 1) it.rank else -it.rank }
                .thenByDescending { it.auto.hdr }
                .thenBy { it.edition.preference }
                .thenBy { it.index },
        ).edition

private class OpeningCandidate(val index: Int, val edition: PlaybackEdition, val auto: FileVersion, cap: Int?) {
    val rank = resolutionRank(auto.resolution)
    val fit = fitClass(rank, cap)
}

/** 0 fits the cap (or there is none), 1 is above it, 2 is an unknown resolution. */
private fun fitClass(rank: Int, cap: Int?): Int = when {
    rank == 0 -> 2
    cap == null || rank <= cap -> 0
    else -> 1
}

/** 0 for the unnamed or standard edition, 1 for a theatrical one, 2 for any other cut. */
private val PlaybackEdition.preference: Int
    get() {
        val names = listOfNotNull(id, raw).map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        return when {
            names.isEmpty() -> 0
            names.any { it == "standard" || it == "default" || it.startsWith("standard ") || it.endsWith(" standard") } -> 0
            names.any { "theatrical" in it } -> 1
            else -> 2
        }
    }

/**
 * Version Auto within one edition's first-part [candidates]: the best file at
 * or below [cap] (null is no cap), else the closest file above it. A file of
 * unknown resolution is picked only when no file's resolution is known.
 */
private fun autoFile(candidates: List<FileVersion>, cap: Int?): FileVersion {
    val ordered = candidates.sortedWith(SERVER_ORDER)
    val known = ordered.filter { resolutionRank(it.resolution) > 0 }
    if (known.isEmpty()) return ordered.first()
    if (cap == null) return known.first()
    known.firstOrNull { resolutionRank(it.resolution) <= cap }?.let { return it }
    val closest = known.minOf { resolutionRank(it.resolution) }
    return known.first { resolutionRank(it.resolution) == closest }
}

/** The server's version order: resolution, then HDR before SDR, then larger file; stable otherwise. */
private val SERVER_ORDER: Comparator<FileVersion> =
    compareByDescending<FileVersion> { resolutionRank(it.resolution) }
        .thenByDescending { it.hdr }
        .thenByDescending { it.fileSize }

/** The resolution [preferredQuality] caps Auto at, or null for no cap (Auto, Original, or no resolution). */
private fun qualityCap(preferredQuality: String?): Int? =
    resolutionRank(QualityPresets.normalizeResolution(preferredQuality)).takeIf { it > 0 }

/** The server's resolution rank (`access` quality order), with the 4K and 8K aliases; 0 is unknown. */
private fun resolutionRank(value: String?): Int = when (value?.trim()?.lowercase()) {
    "480p" -> 1
    "720p" -> 2
    "1080p" -> 3
    "2160p", "4k" -> 4
    "4320p", "8k" -> 5
    else -> 0
}
