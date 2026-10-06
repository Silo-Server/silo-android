package org.siloserver.silo.common.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import org.siloserver.silo.domain.settings.TitleArtController

/**
 * Whether title surfaces may name a title with its logo artwork
 * (`ui.title_art`). When false they always show the text title.
 *
 * Published once near each app shell by [ProvideTitleArt]; anything rendered
 * outside it keeps the contract default (logos on). Not static: the value
 * flips once on a cold start (default, then the cached or server answer), and
 * only the title surfaces that read it should recompose.
 */
val LocalShowTitleArt: ProvidableCompositionLocal<Boolean> =
    compositionLocalOf { TitleArtController.DEFAULT_SHOW_TITLE_ART }

/**
 * The logo a title surface may draw for [logoUrl]: null when "Show title art"
 * is off or there is no logo, so the caller falls back to the text title.
 * Every surface that draws a logo goes through here; prefetch reads
 * [LocalShowTitleArt] directly.
 */
@Composable
@ReadOnlyComposable
fun titleLogoUrl(logoUrl: String?): String? {
    val show = LocalShowTitleArt.current
    return logoUrl?.takeIf { show && it.isNotBlank() }
}

/**
 * Hydrates [store] for [sessionKey] (the active profile id; null before sign-in)
 * and publishes its value via [LocalShowTitleArt] for [content]. Same shape as
 * `ProvideCardPresentation`.
 */
@Composable
fun ProvideTitleArt(
    store: TitleArtStore,
    sessionKey: Any? = null,
    content: @Composable () -> Unit,
) {
    LaunchedEffect(store, sessionKey) {
        if (sessionKey != null) store.hydrateIfNeeded()
    }
    val state by store.state.collectAsState()
    CompositionLocalProvider(LocalShowTitleArt provides state.showTitleArt, content = content)
}
