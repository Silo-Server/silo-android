package org.siloserver.silo.common.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import org.siloserver.silo.network.AccessChangeSignals

/**
 * Runs [onChanged] each time the server reports that the viewer's access
 * changed (access group, permissions, or playback-quality override) while this
 * composable is on screen. Screens use it to refetch libraries, search results,
 * details, and collections the new policy may have added or removed.
 *
 * Inert against a server that never reports access changes.
 */
@Composable
fun OnViewerAccessChanged(signals: AccessChangeSignals, onChanged: () -> Unit) {
    val latest by rememberUpdatedState(onChanged)
    LaunchedEffect(signals) {
        signals.changes.collect { latest() }
    }
}

/**
 * A key that moves on each reported access change, for `produceState` and
 * `LaunchedEffect` loaders that should re-run under the new policy.
 */
@Composable
fun rememberViewerAccessKey(signals: AccessChangeSignals): Int {
    var key by remember(signals) { mutableIntStateOf(0) }
    OnViewerAccessChanged(signals) { key++ }
    return key
}
