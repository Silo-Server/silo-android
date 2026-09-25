package org.siloserver.silo.tv.ui.screens.watchparty

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import org.koin.compose.koinInject
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.watchtogether.Suggestion
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.CatalogRepository
import org.siloserver.silo.viewmodel.WatchPartyItem
import java.util.concurrent.ConcurrentHashMap

/**
 * Title previews handed from the detail page or the picker to the lobby, so
 * the staged card can lay out once instead of waiting for a detail fetch.
 * Process memory only; nothing here is a secret or survives the process.
 */
internal object TvWatchPartyPreviews {
    private const val CAPACITY = 32
    private val items = ConcurrentHashMap<String, WatchPartyItem>()

    fun put(item: WatchPartyItem) {
        if (items.size >= CAPACITY) items.clear()
        items[item.contentId] = item
    }

    fun get(contentId: String): WatchPartyItem? = items[contentId]
}

/** What the room shows for the staged item. */
internal data class TvStagedPreview(
    val title: String,
    /** An episode's series and number; null for a movie. */
    val subtitle: String?,
    val posterUrl: String?,
    val posterThumbhash: String? = null,
    val backdropUrl: String? = null,
    val backdropThumbhash: String? = null,
    val overview: String? = null,
    /** "2021", "2h 35m". */
    val facts: List<String> = emptyList(),
    /** The chosen version's quality: "4K", "HDR". */
    val chips: List<String> = emptyList(),
)

/**
 * The staged item's hero content: a handed-over preview or a suggestion first,
 * then the catalog detail (cached, else fetched) for the full title, artwork,
 * overview, and the selected version's quality.
 */
@Composable
internal fun rememberTvStagedPreview(
    contentId: String?,
    fileId: Int?,
    libraryId: Int?,
    suggestions: List<Suggestion>,
    catalog: CatalogRepository = koinInject(),
): TvStagedPreview? {
    val detail by produceState<ItemDetail?>(initialValue = null, contentId, libraryId) {
        value = null
        val id = contentId?.takeIf { it.isNotBlank() } ?: return@produceState
        value = catalog.getCachedItemDetail(id, libraryId)
            ?: (catalog.getItemDetail(id, libraryId) as? ApiResult.Success)?.data
    }
    val id = contentId?.takeIf { it.isNotBlank() } ?: return null
    val loaded = detail?.takeIf { it.contentId == id }
    if (loaded != null) {
        val version = loaded.versions.firstOrNull { it.fileId == fileId } ?: loaded.versions.singleOrNull()
        return TvStagedPreview(
            title = loaded.title,
            subtitle = tvDetailSubtitle(loaded),
            posterUrl = loaded.posterUrl,
            posterThumbhash = loaded.posterThumbhash,
            backdropUrl = loaded.backdropUrl,
            backdropThumbhash = loaded.backdropThumbhash,
            overview = loaded.overview?.takeIf { it.isNotBlank() },
            facts = listOfNotNull(
                loaded.year.takeIf { it > 0 && loaded.type != "episode" }?.toString(),
                loaded.runtime.takeIf { it > 0 }?.let(::tvWatchPartyRuntime),
            ),
            chips = listOfNotNull(
                version?.resolution?.let(::tvWatchPartyResolutionChip),
                "HDR".takeIf { version?.hdr == true },
            ),
        )
    }
    TvWatchPartyPreviews.get(id)?.let { item ->
        return TvStagedPreview(item.title, item.subtitle, item.posterUrl)
    }
    suggestions.firstOrNull { it.contentId == id }?.let { suggestion ->
        return TvStagedPreview(
            title = suggestion.title,
            subtitle = suggestion.subtitle.ifBlank { null },
            posterUrl = suggestion.posterUrl.ifBlank { null },
        )
    }
    return TvStagedPreview(title = "Loading title…", subtitle = null, posterUrl = null)
}

private fun tvDetailSubtitle(detail: ItemDetail): String? = when (detail.type) {
    "episode" -> listOfNotNull(
        detail.seriesTitle?.takeIf { it.isNotBlank() },
        detail.seasonNumber?.let { season -> detail.episodeNumber?.let { "S$season:E$it" } },
    ).joinToString(" · ").ifBlank { null }
    else -> null
}

/** "2h 35m", "48m". */
internal fun tvWatchPartyRuntime(minutes: Int): String =
    if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"

private fun tvWatchPartyResolutionChip(resolution: String): String? = when (resolution.trim().lowercase()) {
    "" -> null
    "2160p", "4k", "uhd" -> "4K"
    else -> resolution.trim().uppercase()
}

/** A detail page's item as the party sees it. */
internal fun tvWatchPartyItem(
    contentId: String,
    contentType: String,
    title: String,
    subtitle: String?,
    posterUrl: String?,
    fileId: Int?,
    libraryId: Int?,
): WatchPartyItem = WatchPartyItem(
    contentId = contentId,
    contentType = contentType,
    title = title,
    subtitle = subtitle?.takeIf { it.isNotBlank() },
    posterUrl = posterUrl?.takeIf { it.isNotBlank() },
    fileId = fileId,
    libraryId = libraryId,
)
