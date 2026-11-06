package org.siloserver.silo.common.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.siloserver.silo.repository.MembershipActions
import org.siloserver.silo.repository.port.MembershipPort

/** What a Favorites or Watchlist change that didn't land tells the viewer; each app draws it as its own dialog. */
data class MembershipNotice(
    val title: String,
    val message: String,
    /** "Check Status" once a command was recorded, otherwise "Try Again". */
    val actionLabel: String,
    val onAction: () -> Unit,
    val onDismiss: () -> Unit,
)

/**
 * Raises a notice when one of the viewer's own membership changes finishes
 * unconfirmed, and keeps background results flowing into [actions]. Commands
 * restored from an earlier session do not open a notice by themselves; the
 * viewer meets them again by choosing on the item. Closing a notice never
 * touches the retained command. While [deferred] (playback), a raised notice
 * waits instead of covering the player.
 *
 * The legacy-quarantine notice keeps its non-modal banner at [legacyModifier].
 */
@Composable
fun MembershipNoticeHost(
    actions: MembershipActions,
    deferred: Boolean,
    legacyModifier: Modifier,
    show: @Composable (MembershipNotice) -> Unit,
) {
    val states by actions.actions.collectAsState()
    val generation by actions.generation.collectAsState()
    val legacy by actions.legacyNotice.collectAsState()
    val scope = rememberCoroutineScope()
    // Oldest first, one per field: a later failure waits instead of replacing an open notice.
    var raised by remember { mutableStateOf(emptyList<MembershipActions.Intent>()) }
    LaunchedEffect(generation) { actions.observeChanges() }
    LaunchedEffect(actions) {
        actions.unconfirmedAttempts.collect { new -> raised = raised.filterNot { it.key == new.key } + new }
    }

    val shown = raised.firstNotNullOfOrNull { intent ->
        states[intent.key]
            ?.takeIf { it.intent == intent && actions.current(intent) && !it.busy && !it.confirmed }
            ?.let { intent to it }
    }
    if (shown != null) {
        if (deferred) return
        val (intent, action) = shown
        show(unconfirmedNotice(action, onDismiss = { raised = raised - intent }) {
            raised = raised - intent
            scope.launch {
                if (action.command != null) actions.checkStatus(intent) else actions.perform(intent)
            }
        })
    } else if (legacy) {
        LegacyMembershipBanner(onDismiss = actions::dismissLegacyNotice, modifier = legacyModifier)
    }
}

private fun unconfirmedNotice(
    action: MembershipActions.Action,
    onDismiss: () -> Unit,
    onAction: () -> Unit,
): MembershipNotice {
    val list = if (action.intent.key.kind == MembershipPort.Kind.FAVORITE) "Favorites" else "Watchlist"
    val change = if (action.intent.present) "adding this title to your $list" else "removing this title from your $list"
    val recorded = action.command != null
    if (action.completion?.disposition == MembershipPort.Disposition.PAUSED) {
        return MembershipNotice(
            title = "Change Not Saved",
            message = "The server doesn't match your change, so $change wasn't saved. Choose again if you still want it.",
            actionLabel = "Check Status",
            onAction = onAction,
            onDismiss = onDismiss,
        )
    }
    return MembershipNotice(
        title = "Change Not Confirmed",
        message = "Silo couldn't confirm $change with the server, so it isn't shown as saved. " +
            if (recorded) "Check its status once you're connected." else "Try again in a moment.",
        actionLabel = if (recorded) "Check Status" else "Try Again",
        onAction = onAction,
        onDismiss = onDismiss,
    )
}

/** Unchanged from the earlier status banner: a non-modal notice that never requests TV focus. */
@Composable
private fun LegacyMembershipBanner(onDismiss: () -> Unit, modifier: Modifier) {
    Column(modifier.background(Color(0xF0222630)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicText(
            "Earlier unsent favorite changes were retained. Review your current Favorites before making a new choice.",
            style = TextStyle(color = Color.White, fontSize = 15.sp),
        )
        BasicText(
            "Dismiss notice",
            modifier = Modifier.clickable(role = Role.Button, onClick = onDismiss).padding(8.dp),
            style = TextStyle(color = Color(0xFFAACFFF), fontSize = 16.sp),
        )
    }
}
