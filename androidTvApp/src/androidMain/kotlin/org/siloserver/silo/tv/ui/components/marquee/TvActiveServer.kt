package org.siloserver.silo.tv.ui.components.marquee

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import org.koin.core.context.GlobalContext
import org.siloserver.silo.common.ui.marquee.ServerBrandingLoader
import org.siloserver.silo.common.ui.marquee.SignInServer
import org.siloserver.silo.common.ui.marquee.rememberSignInServer
import org.siloserver.silo.network.ServerRegistry

/**
 * The active server's name and branding for a TV first-run screen, tinting
 * the backdrop to its accent. Null where no Koin graph is running (screen
 * tests), so screens fall back to the name their view model already has.
 */
@Composable
fun rememberTvActiveServer(): SignInServer? {
    val koin = remember { GlobalContext.getOrNull() } ?: return null
    val registry = remember(koin) { koin.getOrNull<ServerRegistry>() } ?: return null
    val loader = remember(koin) { koin.getOrNull<ServerBrandingLoader>() } ?: return null
    val entry by registry.activeEntry.collectAsState()
    val url = entry?.url ?: return null
    return rememberSignInServer(serverUrl = url, savedName = entry?.fetchedName, loader = loader)
}
