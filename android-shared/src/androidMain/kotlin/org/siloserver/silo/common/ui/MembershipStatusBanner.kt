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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import org.siloserver.silo.repository.MembershipActions
import org.siloserver.silo.repository.port.MembershipPort

/**
 * Briefly reports a Favorites or Watchlist change that still failed after automatic retries,
 * and shows the legacy retained-changes notice. Does not request focus or replace list content.
 */
@Composable
fun MembershipStatusBanner(actions: MembershipActions, modifier: Modifier = Modifier) {
    val generation by actions.generation.collectAsState()
    val legacy by actions.legacyNotice.collectAsState()
    var failed by remember { mutableStateOf<MembershipPort.Kind?>(null) }
    LaunchedEffect(generation) { actions.observeChanges() }
    LaunchedEffect(generation) {
        failed = null
        actions.failures.collectLatest { key ->
            failed = key.kind
            delay(FAILURE_NOTICE_MS)
            failed = null
        }
    }
    val notice = failed
    if (notice == null && !legacy) return
    Column(modifier.background(Color(0xF0222630)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val text = when (notice) {
            MembershipPort.Kind.FAVORITE -> "Couldn't save your change to Favorites. Try again."
            MembershipPort.Kind.WATCHLIST -> "Couldn't save your change to Watchlist. Try again."
            null -> "Earlier unsent favorite changes were retained. Review your current Favorites before making a new choice."
        }
        BasicText(text, style = TextStyle(color = Color.White, fontSize = 15.sp))
        if (notice == null) {
            BasicText("Dismiss notice", modifier = Modifier.clickable(role = Role.Button) { actions.dismissLegacyNotice() }.padding(8.dp),
                style = TextStyle(color = Color(0xFFAACFFF), fontSize = 16.sp))
        }
    }
}

private const val FAILURE_NOTICE_MS = 6_000L
