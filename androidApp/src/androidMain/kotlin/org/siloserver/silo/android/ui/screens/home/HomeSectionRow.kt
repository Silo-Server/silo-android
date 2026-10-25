package org.siloserver.silo.android.ui.screens.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import org.siloserver.silo.android.ui.components.CardStyle
import org.siloserver.silo.android.ui.components.MediaCardActions
import org.siloserver.silo.android.ui.components.MediaRow
import org.siloserver.silo.model.section.ResolvedSection
import org.siloserver.silo.model.section.SECTION_ROW_MAX_ITEMS
import org.siloserver.silo.model.section.cappedForRow
import org.siloserver.silo.model.section.offersExploreAll
import org.siloserver.silo.model.section.SectionItem
import org.siloserver.silo.viewmodel.HomeDismissal

/**
 * Renders a single home screen section as a horizontal row of media cards.
 *
 * Wraps [MediaRow] with section-specific logic such as determining whether
 * to show progress bars (for "continue watching" type sections). A row with
 * a full page shows at most [SECTION_ROW_MAX_ITEMS] cards, and unless it is
 * featured it ends with an "Explore all" card that opens that page.
 *
 * @param section The resolved section containing items.
 * @param onItemClick Callback with content ID when a card is tapped.
 * @param onExploreAllClick Opens the row's full page. Null keeps every card
 *  and hides "Explore all".
 * @param modifier Compose modifier.
 */
@Composable
fun HomeSectionRow(
    section: ResolvedSection,
    onItemClick: (String) -> Unit,
    onItemPlay: ((SectionItem) -> Unit)? = null,
    onExploreAllClick: (() -> Unit)? = null,
    onSetWatched: ((String, Boolean) -> Unit)? = null,
    onToggleFavorite: ((String, Boolean) -> Unit)? = null,
    onToggleWatchlist: ((String, Boolean) -> Unit)? = null,
    /** Removes a Continue Watching or Next Up card; see [HomeDismissal]. */
    onRemoveFromHome: ((SectionItem) -> Unit)? = null,
    onCenteredContinueWatchingItemChanged: ((SectionItem?) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (section.items.isEmpty()) return
    // Rows with a full page (Home and library rows) cap their cards; the
    // page holds the rest. For You rows have no page, so they show all.
    val capped = onExploreAllClick != null
    val rowItems = remember(section.items, capped) { if (capped) section.cappedForRow().items else section.items }

    val isContinueWatching = section.sectionType == "continue_watching" ||
        section.sectionType == "in_progress"
    val showProgress = isContinueWatching
    val offersRemoval = onRemoveFromHome != null && section.sectionType in HomeDismissal.sectionTypes

    val useBackdrop = showProgress || section.sectionType == "next_up"
    val cardStyle = if (useBackdrop) CardStyle.Backdrop else CardStyle.Poster

    MediaRow(
        title = section.title,
        items = rowItems,
        onItemClick = onItemClick,
        onExploreAllClick = onExploreAllClick?.takeIf { section.offersExploreAll },
        // Direct resume only where a resume point exists — the CW/next-up rows.
        onItemPlay = if (useBackdrop) onItemPlay else null,
        showProgress = showProgress,
        cardStyle = cardStyle,
        icon = if (isContinueWatching) Icons.Filled.PlayCircle else null,
        modifier = modifier,
        cardActions = { item ->
            val dismissal = if (offersRemoval) HomeDismissal.of(item) else null
            MediaCardActions(
                onSetWatched = onSetWatched?.let { cb -> { watched -> cb(item.contentId, watched) } },
                onToggleFavorite = onToggleFavorite?.let { cb -> { fav -> cb(item.contentId, fav) } },
                onToggleWatchlist = onToggleWatchlist?.let { cb -> { wl -> cb(item.contentId, wl) } },
                onRemoveFromHome = dismissal?.let { { onRemoveFromHome?.invoke(item) } },
                removeFromHomeLabel = dismissal?.menuLabel.orEmpty(),
            )
        },
        onCenteredItemChanged = if (isContinueWatching) onCenteredContinueWatchingItemChanged else null,
    )
}
