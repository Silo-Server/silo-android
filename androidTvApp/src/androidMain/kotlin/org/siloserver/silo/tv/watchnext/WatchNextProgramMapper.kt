package org.siloserver.silo.tv.watchnext

import org.siloserver.silo.model.catalog.isAudiobookItemType
import org.siloserver.silo.model.section.SectionItem
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.settings.EpisodeSpoilerPrefs
import org.siloserver.silo.model.settings.EpisodeSpoilers
import java.net.URLEncoder
import java.time.Instant
import java.time.format.DateTimeParseException

data class WatchNextProgramFields(
    val externalId: String,
    val title: String,
    val watchNextType: Int,
    val programType: Int,
    val posterArtUri: String,
    val posterArtAspectRatio: Int,
    // Null when the item has no usable engagement timestamp — the repository
    // then omits the column so the provider keeps whatever it had (see below).
    val lastEngagementTimeMs: Long?,
    val intentUri: String,
)

object WatchNextProgramMapper {

    fun map(
        item: SectionItem,
        sectionType: String,
        spoilerPrefs: EpisodeSpoilerPrefs = EpisodeSpoilerPrefs.NONE,
        seriesArtwork: ItemDetail? = null,
    ): WatchNextProgramFields? {
        val protect = item.type.equals("episode", ignoreCase = true) &&
            spoilerPrefs.hidesImage(EpisodeSpoilers.isUnwatched(item))
        val backdrop = item.backdropUrl?.takeIf { it.isNotBlank() && (!protect || item.backdropIsEpisodeStill == false) }
        val portrait = item.posterUrl?.takeIf { it.isNotBlank() && (!protect || item.posterIsEpisodeStill == false) }
        val seriesBackdrop = seriesArtwork?.takeIf { it.type == "series" }?.backdropUrl?.takeIf { it.isNotBlank() }
        val seriesPoster = seriesArtwork?.takeIf { it.type == "series" }?.posterUrl?.takeIf { it.isNotBlank() }
        val poster = backdrop ?: seriesBackdrop ?: portrait ?: seriesPoster ?: return null
        val watchNextType = when (sectionType) {
            "continue_watching" -> WATCH_NEXT_TYPE_CONTINUE
            "next_up" -> WATCH_NEXT_TYPE_NEXT
            else -> return null
        }
        val isAudiobook = isAudiobookItemType(item.type)
        val programType = when {
            item.type == "movie" -> PROGRAM_TYPE_MOVIE
            // tvprovider 1.0.0 has no dedicated audiobook program type; ALBUM
            // (a bounded, cover-arted, multi-track work) is the closest analog.
            isAudiobook -> PROGRAM_TYPE_ALBUM
            else -> PROGRAM_TYPE_TV_EPISODE
        }
        // Safe parent posters keep their portrait aspect ratio.
        val aspectRatio = when {
            isAudiobook -> ASPECT_RATIO_1_1
            protect && backdrop == null && seriesBackdrop == null -> ASPECT_RATIO_2_3
            else -> ASPECT_RATIO_16_9
        }
        val intentUri = when (watchNextType) {
            // Contract with the deep-link handler: tag the play intent with the
            // item type (URL-encoded) so it can route audiobooks to the audio
            // player instead of the video player.
            WATCH_NEXT_TYPE_CONTINUE ->
                "silo://play/${item.contentId}?type=" + URLEncoder.encode(item.type, Charsets.UTF_8.name())
            else -> "silo://item/${item.contentId}"
        }
        return WatchNextProgramFields(
            externalId = "$sectionType:${item.contentId}",
            title = item.title,
            watchNextType = watchNextType,
            programType = programType,
            posterArtUri = poster,
            posterArtAspectRatio = aspectRatio,
            // Never re-stamp to "now": an item with no parseable progress time
            // gets a null engagement time so a never-started tile can't
            // perpetually outrank genuinely recent content on every sync.
            lastEngagementTimeMs = parseProgressTimestamp(item.progressUpdatedAt),
            intentUri = intentUri,
        )
    }

    private fun parseProgressTimestamp(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        return try {
            Instant.parse(iso).toEpochMilli()
        } catch (e: DateTimeParseException) {
            null
        }
    }

    const val WATCH_NEXT_TYPE_CONTINUE = 0
    const val WATCH_NEXT_TYPE_NEXT = 1
    const val PROGRAM_TYPE_MOVIE = 0
    const val PROGRAM_TYPE_TV_EPISODE = 3
    // Mirrors TvContractCompat.PreviewPrograms.TYPE_ALBUM (no AUDIOBOOK type exists).
    const val PROGRAM_TYPE_ALBUM = 8
    const val ASPECT_RATIO_16_9 = 0
    const val ASPECT_RATIO_1_1 = 3
    // Mirrors TvContractCompat.WatchNextPrograms.ASPECT_RATIO_2_3.
    const val ASPECT_RATIO_2_3 = 4
}
