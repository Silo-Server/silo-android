package org.siloserver.silo.tv.ui.components

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import org.siloserver.silo.domain.MediaActionsCoordinator
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.catalog.MediaItemUserState
import org.siloserver.silo.network.ApiResult
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * TV-side counterpart to
 * [org.siloserver.silo.android.ui.components.rememberBrowseItemCardActions].
 * Builds a [TvMediaCardActions] backed by [MediaActionsCoordinator] for grids
 * (catalog, library detail, search) whose ViewModels don't manage these
 * actions directly. Optimistic state is held in Compose; failures roll back.
 */
@Composable
fun rememberTvBrowseItemCardActions(
    item: BrowseItem,
): Pair<TvMediaCardActions, MediaItemUserState> {
    val coordinator: MediaActionsCoordinator = koinInject()
    val scope = rememberCoroutineScope()

    // Key on userState too (phone parity): when refreshed data re-emits with a
    // changed userState, the card must reflect new watched/favorite/watchlist
    // badges instead of keeping the stale snapshot.
    var state by remember(item.contentId, item.userState) {
        mutableStateOf(item.userState ?: MediaItemUserState())
    }

    val actions = remember(item.contentId, coordinator, scope) {
        TvMediaCardActions(
            onSetWatched = { watched ->
                val writeIntent = coordinator.beginWatched(item.contentId, watched)
                val previous = state
                state = state.copy(played = watched)
                scope.launch {
                    if (coordinator.performPersonalWrite(writeIntent) !is ApiResult.Success) {
                        if (coordinator.isCurrent(writeIntent)) state = previous
                    }
                }
            },
            onToggleFavorite = { favorite ->
                val intent = coordinator.memberships.begin(item.contentId, org.siloserver.silo.repository.port.MembershipPort.Kind.FAVORITE, favorite)
                scope.launch { coordinator.memberships.perform(intent) }
            },
            onToggleWatchlist = { inWatchlist ->
                val intent = coordinator.memberships.begin(item.contentId, org.siloserver.silo.repository.port.MembershipPort.Kind.WATCHLIST, inWatchlist)
                scope.launch { coordinator.memberships.perform(intent) }
            },
        )
    }

    val membershipActions by coordinator.memberships.actions.collectAsState()
    var displayed = state
    membershipActions.values.filter { it.intent.key.itemId == item.contentId && it.baseline != null && coordinator.memberships.current(it.intent) }.forEach {
        displayed = if (it.intent.key.kind == org.siloserver.silo.repository.port.MembershipPort.Kind.FAVORITE)
            displayed.copy(isFavorite = it.baseline!!.present) else displayed.copy(inWatchlist = it.baseline!!.present)
    }
    return actions to displayed
}
