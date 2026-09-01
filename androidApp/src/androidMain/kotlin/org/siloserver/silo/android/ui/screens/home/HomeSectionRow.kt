package org.siloserver.silo.android.ui.screens.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.siloserver.silo.android.ui.components.CardStyle
import org.siloserver.silo.android.ui.components.MediaCardActions
import org.siloserver.silo.android.ui.components.MediaRow
import org.siloserver.silo.model.section.ResolvedSection
import org.siloserver.silo.model.section.SectionItem

/**
 * Renders a single home screen section as a horizontal row of media cards.
 *
 * Wraps [MediaRow] with section-specific logic such as determining whether
 * to show progress bars (for "continue watching" type sections) and mapping
 * the section type to the appropriate "See All" navigation action.
 *
 * @param section The resolved section containing items.
 * @param onItemClick Callback with content ID when a card is tapped.
 * @param modifier Compose modifier.
 */
@Composable
fun HomeSectionRow(
    section: ResolvedSection,
    onItemClick: (String) -> Unit,
    onItemPlay: ((SectionItem) -> Unit)? = null,
    /** Optional See All affordance — used by Libraries/Reading hub rows;
     *  Home rows pass nothing (removed for iOS parity). */
    onSeeAllClick: (() -> Unit)? = null,
    onSetWatched: ((String, Boolean) -> Unit)? = null,
    onToggleFavorite: ((String, Boolean) -> Unit)? = null,
    onToggleWatchlist: ((String, Boolean) -> Unit)? = null,
    onDismissContinueWatching: ((SectionItem) -> Unit)? = null,
    onCenteredContinueWatchingItemChanged: ((SectionItem?) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (section.items.isEmpty()) return

    val isContinueWatching = section.sectionType == "continue_watching" ||
        section.sectionType == "in_progress"
    val showProgress = isContinueWatching

    val useBackdrop = showProgress || section.sectionType == "next_up"
    val cardStyle = if (useBackdrop) CardStyle.Backdrop else CardStyle.Poster

    MediaRow(
        title = section.title,
        items = section.items,
        onItemClick = onItemClick,
        onSeeAllClick = onSeeAllClick,
        // Direct resume only where a resume point exists — the CW/next-up rows.
        onItemPlay = if (useBackdrop) onItemPlay else null,
        showProgress = showProgress,
        cardStyle = cardStyle,
        icon = if (isContinueWatching) Icons.Filled.PlayCircle else null,
        modifier = modifier,
        cardActions = { item ->
            MediaCardActions(
                onSetWatched = onSetWatched?.let { cb -> { watched -> cb(item.contentId, watched) } },
                onToggleFavorite = onToggleFavorite?.let { cb -> { fav -> cb(item.contentId, fav) } },
                onToggleWatchlist = onToggleWatchlist?.let { cb -> { wl -> cb(item.contentId, wl) } },
                onRemoveFromContinueWatching = if (isContinueWatching && onDismissContinueWatching != null && item.progressUpdatedAt != null) {
                    { onDismissContinueWatching(item) }
                } else null,
            )
        },
        onCenteredItemChanged = if (isContinueWatching) onCenteredContinueWatchingItemChanged else null,
    )
}
