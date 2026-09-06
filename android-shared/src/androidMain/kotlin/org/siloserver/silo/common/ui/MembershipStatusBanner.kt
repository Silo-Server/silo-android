package org.siloserver.silo.common.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.siloserver.silo.repository.MembershipActions

/** Does not request focus or replace list content while a command is unresolved. */
@Composable
fun MembershipStatusBanner(actions: MembershipActions, modifier: Modifier = Modifier) {
    val states by actions.actions.collectAsState()
    val generation by actions.generation.collectAsState()
    val legacy by actions.legacyNotice.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(generation) { actions.observeChanges() }
    val pending = states.values.firstOrNull { !it.confirmed && actions.current(it.intent) }
    if (pending == null && !legacy) return
    Column(modifier.background(Color(0xF0222630)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val text = when {
            pending == null -> "Earlier unsent favorite changes were retained. Review your current Favorites before making a new choice."
            pending.completion?.disposition == org.siloserver.silo.repository.port.MembershipPort.Disposition.PAUSED -> "The server has a different membership state. Review the item and choose again, or check its status."
            pending.busy -> "Saving membership change…"
            else -> "Membership change is unconfirmed. Your current items are retained."
        }
        BasicText(text, style = TextStyle(color = Color.White, fontSize = 15.sp))
        if (pending != null && !pending.busy) {
            BasicText(if (pending.command != null) "Check status" else "Try again",
                modifier = Modifier.clickable(role = Role.Button) {
                    scope.launch {
                        if (pending.command != null) actions.checkStatus(pending.intent) else actions.perform(pending.intent)
                    }
                }.padding(8.dp), style = TextStyle(color = Color(0xFFAACFFF), fontSize = 16.sp))
        } else if (pending == null) {
            BasicText("Dismiss notice", modifier = Modifier.clickable(role = Role.Button) { actions.dismissLegacyNotice() }.padding(8.dp),
                style = TextStyle(color = Color(0xFFAACFFF), fontSize = 16.sp))
        }
    }
}
