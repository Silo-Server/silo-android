package org.siloserver.silo.android.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Lets the tab on screen hand its page dock to the tab bar's accessory slot
 * (iOS `PageDockHost`). The tab shell owns it.
 *
 * Now playing always keeps priority: while Remote Control or the audiobook bar
 * holds the slot ([nowPlayingHoldsSlot]), the shell shows that bar and the page
 * keeps its dock floating in the page, so both stay reachable.
 */
@Stable
class PageDockHost(private val nowPlaying: State<Boolean>) {
    /** The dock the page offers the slot; null when it offers none. */
    var dock: (@Composable (inline: Boolean) -> Unit)? by mutableStateOf(null)
        private set

    /** True while a now-playing bar holds the slot; the page then floats its dock. */
    val nowPlayingHoldsSlot: Boolean get() = nowPlaying.value

    internal fun offer(entry: @Composable (inline: Boolean) -> Unit) {
        dock = entry
    }

    internal fun withdraw(entry: @Composable (inline: Boolean) -> Unit) {
        if (dock === entry) dock = null
    }
}

/** The tab shell's [PageDockHost]; null outside it (no slot to offer a dock to). */
val LocalPageDockHost = staticCompositionLocalOf<PageDockHost?> { null }

/**
 * Offers [content] to the tab bar's accessory slot for as long as this is
 * composed. The offer stands while a now-playing bar holds the slot, so the
 * dock moves into the bar the moment the slot frees, in the same frame the page
 * stops floating it. [content] draws only what goes inside the card; `inline`
 * names the placement (above the expanded bar, or beside the minimized control).
 */
@Composable
fun OfferPageDock(host: PageDockHost, content: @Composable (inline: Boolean) -> Unit) {
    val latest by rememberUpdatedState(content)
    val entry: @Composable (Boolean) -> Unit = remember { { inline -> latest(inline) } }
    DisposableEffect(host, entry) {
        host.offer(entry)
        onDispose { host.withdraw(entry) }
    }
}
