package org.siloserver.silo.android.ui.components

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
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Returns a [MediaCardActions] that delegates to a Koin-injected
 * [MediaActionsCoordinator]. Each invocation also exposes the *current* state
 * (after any optimistic update) via the trailing [view] block so callers can
 * render the up-to-date watched / favorite / watchlist state on the card.
 *
 * Use this for grid screens (search, catalog, library, person detail) whose
 * ViewModels don't yet manage these actions. The Home screen wires actions
 * through [org.siloserver.silo.viewmodel.HomeViewModel] instead so that
 * dismissing from Continue Watching mutates the resolved sections list.
 */
@Composable
fun rememberBrowseItemCardActions(
    item: BrowseItem,
): Pair<MediaCardActions, MediaItemUserState> {
    val coordinator: MediaActionsCoordinator = koinInject()
    val scope = rememberCoroutineScope()

    // Re-key on userState too: when the list re-emits with an overlaid userState
    // (local watched/favorite applied), the card must pick it up — keying on
    // contentId alone kept the stale state and hid the overlay. The card's own
    // optimistic toggles mutate `state` (not item.userState), so they aren't reset.
    var state by remember(item.contentId, item.userState) {
        mutableStateOf(item.userState ?: MediaItemUserState())
    }

    val actions = remember(item.contentId, coordinator, scope) {
        MediaCardActions(
            onSetWatched = { watched ->
                val writeIntent = coordinator.beginWatched(item.contentId, watched)
                val previous = state
                state = state.copy(played = watched)
                scope.launch {
                    if (coordinator.performPersonalWrite(writeIntent).isFailure()) {
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

private fun org.siloserver.silo.network.ApiResult<Unit>.isFailure(): Boolean =
    this !is org.siloserver.silo.network.ApiResult.Success
