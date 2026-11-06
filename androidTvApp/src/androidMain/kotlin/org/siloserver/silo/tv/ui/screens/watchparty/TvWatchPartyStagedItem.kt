package org.siloserver.silo.tv.ui.screens.watchparty

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.res.stringResource
import org.koin.compose.koinInject
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.common.watchparty.watchPartyEpisodeCode
import org.siloserver.silo.common.watchparty.watchPartyRuntimeText
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.watchtogether.Suggestion
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.CatalogRepository
import org.siloserver.silo.viewmodel.WatchPartyItem
import org.siloserver.silo.viewmodel.WatchPartySpoilers
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
    val spoilers: WatchPartySpoilers = WatchPartySpoilers.NONE,
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
    /** Re-reads the detail when it changes, such as the room's phase after playback. */
    refreshKey: Any? = null,
    catalog: CatalogRepository = koinInject(),
): TvStagedPreview? {
    val detail by produceState<ItemDetail?>(initialValue = null, contentId, libraryId, refreshKey) {
        val id = contentId?.takeIf { it.isNotBlank() }
        val shown = value?.takeIf { it.contentId == id }
        value = shown
        id ?: return@produceState
        if (shown == null) {
            value = catalog.getCachedItemDetail(id, libraryId)
                ?: (catalog.getItemDetail(id, libraryId) as? ApiResult.Success)?.data
        } else {
            // Same title, new phase: after the party plays it, a fresh read
            // carries the watch state spoiler protection needs.
            (catalog.getItemDetail(id, libraryId) as? ApiResult.Success)?.data?.let { value = it }
        }
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
                loaded.runtime.takeIf { it > 0 }?.let { watchPartyRuntimeText(it) },
            ),
            chips = listOfNotNull(
                version?.resolution?.let { tvWatchPartyResolutionChip(it, uhd = stringResource(CommonR.string.watchParty_facts_uhd)) },
                stringResource(CommonR.string.watchParty_facts_hdr).takeIf { version?.hdr == true },
            ),
            spoilers = WatchPartySpoilers.of(loaded),
        )
    }
    TvWatchPartyPreviews.get(id)?.let { item ->
        return TvStagedPreview(item.title, item.subtitle, item.posterUrl, spoilers = item.spoilers)
    }
    suggestions.firstOrNull { it.contentId == id }?.let { suggestion ->
        return TvStagedPreview(
            title = suggestion.title,
            subtitle = suggestion.subtitle.ifBlank { null },
            posterUrl = suggestion.posterUrl.ifBlank { null },
            spoilers = WatchPartySpoilers.of(suggestion),
        )
    }
    return TvStagedPreview(title = stringResource(CommonR.string.watchParty_hero_loading), subtitle = null, posterUrl = null)
}

@Composable
private fun tvDetailSubtitle(detail: ItemDetail): String? = when (detail.type) {
    "episode" -> listOfNotNull(
        detail.seriesTitle?.takeIf { it.isNotBlank() },
        detail.seasonNumber?.let { season -> detail.episodeNumber?.let { watchPartyEpisodeCode(season, it) } },
    ).joinToString(" · ").ifBlank { null }
    else -> null
}

/** [uhd] ("4K") for a 2160p version, else the server's resolution as sent. */
private fun tvWatchPartyResolutionChip(resolution: String, uhd: String): String? = when (resolution.trim().lowercase()) {
    "" -> null
    "2160p", "4k", "uhd" -> uhd
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
    spoilers: WatchPartySpoilers = WatchPartySpoilers.forType(contentType),
): WatchPartyItem = WatchPartyItem(
    contentId = contentId,
    contentType = contentType,
    title = title,
    subtitle = subtitle?.takeIf { it.isNotBlank() },
    posterUrl = posterUrl?.takeIf { it.isNotBlank() },
    fileId = fileId,
    libraryId = libraryId,
    spoilers = spoilers,
)
