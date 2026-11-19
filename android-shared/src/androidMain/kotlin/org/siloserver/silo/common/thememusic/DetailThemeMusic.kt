package org.siloserver.silo.common.thememusic

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import org.siloserver.silo.model.catalog.ThemeSelection

/**
 * Plays [selection]'s theme while this detail page is on screen. The theme
 * fades out when the page leaves (another page, the player, or the app going
 * to the background) and continues on a next page with the same theme, such
 * as a season that inherits its series' set. [selection] is null while the
 * detail loads or when the server sent no themes.
 */
@Composable
fun DetailThemeMusic(service: ThemeMusicService, selection: ThemeSelection?) {
    val page = remember { Any() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by lifecycle.currentStateAsState()
    val visible = state.isAtLeast(Lifecycle.State.STARTED)
    LaunchedEffect(service, visible, selection) {
        if (visible && selection != null) service.show(page, selection) else service.hide(page)
    }
    DisposableEffect(service) {
        onDispose { service.hide(page) }
    }
}
