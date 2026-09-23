package org.siloserver.silo.android.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import org.koin.compose.koinInject
import org.siloserver.silo.common.settings.SeekIntervalStore
import org.siloserver.silo.model.settings.SeekIntervalPair

/**
 * The profile-wide video seek intervals for a surface without its own view
 * model (cast overlay, mini bar, cast remote). On a server without the
 * revision-9 keys, or before discovery answers, the surface keeps [legacy]:
 * the fixed pair it used before the setting existed.
 */
@Composable
fun rememberVideoSeekIntervals(legacy: SeekIntervalPair): SeekIntervalPair {
    val store: SeekIntervalStore = koinInject()
    val state by store.state.collectAsState()
    return state.video(legacy)
}
