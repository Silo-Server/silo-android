package org.siloserver.silo.common.cards

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import org.siloserver.silo.common.settings.CardPresentationStore
import org.siloserver.silo.model.settings.CardPresentation

/**
 * Ambient card-presentation preference (poster size + caption style),
 * published once near each app shell and consumed by every poster/backdrop
 * card. Same pattern as [org.siloserver.silo.common.overlays.LocalCardOverlayUiState]:
 * the shell hydrates the store once and provides the resolved value down the
 * tree, so cards read `LocalCardPresentation.current.posterSize.posterScale`
 * and the caption flags without injecting the store per instance.
 *
 * Default is [CardPresentation.DEFAULT], so any card rendered outside a
 * [ProvideCardPresentation] scope draws at the standard size with full
 * captions.
 */
val LocalCardPresentation: ProvidableCompositionLocal<CardPresentation> =
    staticCompositionLocalOf { CardPresentation.DEFAULT }

/**
 * Hydrates the [store], collects its state, and publishes the resolved
 * [CardPresentation] via [LocalCardPresentation] for the [content] subtree.
 *
 * [sessionKey] is the authenticated identity the preference belongs to (the
 * active profile id). Hydration is keyed on it rather than a one-shot `Unit`
 * for the same reason as `ProvideCardOverlays`: this mounts above the whole
 * nav graph and is first composed on the unauthenticated Login screen, where
 * the settings calls fail; the key flipping from null to a profile id (or to
 * a different profile) re-runs hydration for the new identity.
 */
@Composable
fun ProvideCardPresentation(
    store: CardPresentationStore,
    sessionKey: Any? = null,
    content: @Composable () -> Unit,
) {
    LaunchedEffect(store, sessionKey) {
        if (sessionKey != null) store.hydrateIfNeeded()
    }
    val state by store.state.collectAsState()
    CompositionLocalProvider(LocalCardPresentation provides state.presentation, content = content)
}
