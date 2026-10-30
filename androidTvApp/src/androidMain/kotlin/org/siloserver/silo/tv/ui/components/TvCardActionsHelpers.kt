package org.siloserver.silo.tv.ui.components

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import org.siloserver.silo.domain.MediaActionsCoordinator
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.catalog.withWatched
import org.siloserver.silo.model.catalog.MediaItemUserState
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.errorMessage
import org.siloserver.silo.repository.MembershipActions
import org.siloserver.silo.repository.port.MembershipPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * TV-side counterpart to
 * [org.siloserver.silo.android.ui.components.rememberBrowseItemCardActions].
 * Builds a [TvMediaCardActions] backed by [MediaActionsCoordinator] for grids
 * (catalog, library detail, search) whose ViewModels don't manage these
 * actions directly. Optimistic state is held in Compose; a failed watched
 * write rolls back and says so in a toast.
 */
@Composable
fun rememberTvBrowseItemCardActions(
    item: BrowseItem,
): Triple<TvMediaCardActions, MediaItemUserState, Double?> {
    val coordinator: MediaActionsCoordinator = koinInject()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current.applicationContext

    // Key on userState too (phone parity): when refreshed data re-emits with a
    // changed userState, the card must reflect new watched/favorite/watchlist
    // badges instead of keeping the stale snapshot.
    var state by remember(item.contentId, item.userState, item.positionSeconds) {
        mutableStateOf(item)
    }

    val actions = remember(item.contentId, item.userState, item.positionSeconds, coordinator, context, scope) {
        // The state before the latest watched change, put back if its write fails.
        var previous = state
        coordinator.tvCardActions(
            contentId = item.contentId,
            context = context,
            scope = scope,
            onWatchedChanged = { watched ->
                previous = state
                state = state.withWatched(watched)
            },
            onWatchedWriteFailed = { state = previous },
        )
    }

    val membershipActions by coordinator.memberships.actions.collectAsState()
    val displayed = coordinator.withMembershipChanges(item.contentId, state.userState, membershipActions)
    return Triple(actions, displayed, state.positionSeconds)
}

/** [userState] with the favorite and watchlist changes still current for [contentId]. */
fun MediaActionsCoordinator.withMembershipChanges(
    contentId: String,
    userState: MediaItemUserState?,
    membershipActions: Map<MembershipActions.Key, MembershipActions.Action>,
): MediaItemUserState {
    var displayed = userState ?: MediaItemUserState()
    membershipActions.values.filter { it.intent.key.itemId == contentId && it.baseline != null && memberships.current(it.intent) }.forEach {
        displayed = if (it.intent.key.kind == MembershipPort.Kind.FAVORITE)
            displayed.copy(isFavorite = it.baseline!!.present) else displayed.copy(inWatchlist = it.baseline!!.present)
    }
    return displayed
}

/**
 * Card actions for a row whose screen keeps the items' watched state itself,
 * such as a box set's movies: [onWatchedChanged] moves it at once and
 * [onWatchedWriteFailed] puts it back when the write fails, which a toast
 * on [context] then reports.
 */
fun MediaActionsCoordinator.tvCardActions(
    contentId: String,
    context: Context,
    scope: CoroutineScope,
    onWatchedChanged: (watched: Boolean) -> Unit,
    onWatchedWriteFailed: () -> Unit,
): TvMediaCardActions = TvMediaCardActions(
    onSetWatched = { watched ->
        val writeIntent = beginWatched(contentId, watched)
        onWatchedChanged(watched)
        scope.launch {
            val result = performPersonalWrite(writeIntent)
            if (result !is ApiResult.Success && isCurrent(writeIntent)) {
                onWatchedWriteFailed()
                showWatchedWriteFailed(context, watched, result)
            }
        }
    },
    onToggleFavorite = { favorite ->
        val intent = memberships.begin(contentId, MembershipPort.Kind.FAVORITE, favorite)
        scope.launch { memberships.perform(intent) }
    },
    onToggleWatchlist = { inWatchlist ->
        val intent = memberships.begin(contentId, MembershipPort.Kind.WATCHLIST, inWatchlist)
        scope.launch { memberships.perform(intent) }
    },
)

// The menu has already closed, so without this the rollback is the only sign
// that the write failed.
private fun showWatchedWriteFailed(context: Context, watched: Boolean, result: ApiResult<*>) {
    val action = if (watched) "watched" else "unwatched"
    Toast.makeText(
        context,
        "Couldn't mark as $action. ${result.errorMessage("Try again.")}",
        Toast.LENGTH_LONG,
    ).show()
}
