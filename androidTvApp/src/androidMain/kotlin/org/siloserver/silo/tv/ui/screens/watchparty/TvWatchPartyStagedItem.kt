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

/** What the lobby shows for the staged item. */
internal data class TvStagedPreview(
    val title: String,
    val subtitle: String?,
    val posterUrl: String?,
    /** The chosen edition, when the title has more than one. */
    val edition: String?,
)

/**
 * The staged item's card content: a handed-over preview or a suggestion first,
 * then the catalog detail (cached, else fetched) for the full title and the
 * selected edition.
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
        val edition = loaded.versions
            .takeIf { it.size > 1 }
            ?.firstOrNull { it.fileId == fileId }
            ?.let { version ->
                listOfNotNull(version.resolution?.takeIf { it.isNotBlank() }, "HDR".takeIf { version.hdr })
                    .joinToString(" ")
                    .ifBlank { null }
            }
        return TvStagedPreview(
            title = loaded.title,
            subtitle = tvDetailSubtitle(loaded),
            posterUrl = loaded.posterUrl,
            edition = edition,
        )
    }
    TvWatchPartyPreviews.get(id)?.let { item ->
        return TvStagedPreview(item.title, item.subtitle, item.posterUrl, edition = null)
    }
    suggestions.firstOrNull { it.contentId == id }?.let { suggestion ->
        return TvStagedPreview(
            title = suggestion.title,
            subtitle = suggestion.subtitle.ifBlank { null },
            posterUrl = suggestion.posterUrl.ifBlank { null },
            edition = null,
        )
    }
    return TvStagedPreview(title = "Loading…", subtitle = null, posterUrl = null, edition = null)
}

private fun tvDetailSubtitle(detail: ItemDetail): String? = when (detail.type) {
    "episode" -> listOfNotNull(
        detail.seriesTitle?.takeIf { it.isNotBlank() },
        detail.seasonNumber?.let { season -> detail.episodeNumber?.let { "S${season}E$it" } },
    ).joinToString(" · ").ifBlank { null }
    else -> detail.year.takeIf { it > 0 }?.toString()
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
