package org.siloserver.silo.common.ui.marquee

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * The server a first-run screen is about: its name, address and branding.
 * Branding shows from the cache at once and refreshes from the server.
 */
@Stable
class SignInServer(val serverUrl: String, initial: ServerBranding?, private val savedName: String?) {
    var branding by mutableStateOf(initial)
        internal set

    /** Branded name, then the name saved at connect, then the host. */
    val name: String
        get() = branding?.serverName ?: savedName?.trim()?.takeIf { it.isNotEmpty() } ?: hostLabel

    val hostLabel: String get() = ServerBranding.hostLabel(serverUrl)

    val isSecure: Boolean get() = serverUrl.startsWith("https://", ignoreCase = true)
}

/**
 * Tints the backdrop for [serverUrl] from the saved branding, then reads
 * fresh branding. With [tintsBackdrop] false the screen only reads the name
 * (the profile picker tints for a person instead of the server).
 */
@Composable
fun rememberSignInServer(
    serverUrl: String,
    savedName: String?,
    loader: ServerBrandingLoader,
    tintsBackdrop: Boolean = true,
): SignInServer {
    val server = remember(serverUrl) { SignInServer(serverUrl, loader.cache.branding(serverUrl), savedName) }
    LaunchedEffect(serverUrl) {
        if (serverUrl.isBlank()) return@LaunchedEffect
        if (tintsBackdrop) MarqueeScene.showServer(server.branding)
        // Cancelled with the screen, so a late answer can't retint the next one.
        val fresh = loader.load(serverUrl) ?: return@LaunchedEffect
        server.branding = fresh
        if (tintsBackdrop) MarqueeScene.showServer(fresh)
    }
    return server
}
