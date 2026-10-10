package org.siloserver.silo.common.cards

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import org.siloserver.silo.common.settings.EpisodeSpoilerStore
import org.siloserver.silo.model.settings.EpisodeSpoilerPrefs

/**
 * The profile's spoiler protection for unwatched episodes, published once
 * near each app shell. Surfaces pair it with
 * [org.siloserver.silo.model.settings.EpisodeSpoilers.isUnwatched] to decide
 * per episode. Outside a [ProvideEpisodeSpoilerPrefs] scope nothing is hidden.
 */
val LocalEpisodeSpoilerPrefs: ProvidableCompositionLocal<EpisodeSpoilerPrefs> =
    staticCompositionLocalOf { EpisodeSpoilerPrefs.NONE }

/**
 * Hydrates the [store] for [sessionKey] (the active profile id) and publishes
 * its resolved prefs via [LocalEpisodeSpoilerPrefs]. Keyed on the session for
 * the same reason as [ProvideCardPresentation]: the provider is first
 * composed on the unauthenticated Login screen.
 */
@Composable
fun ProvideEpisodeSpoilerPrefs(
    store: EpisodeSpoilerStore,
    sessionKey: Any? = null,
    content: @Composable () -> Unit,
) {
    LaunchedEffect(store, sessionKey) {
        if (sessionKey != null) store.hydrateIfNeeded()
    }
    val state by store.state.collectAsState()
    CompositionLocalProvider(LocalEpisodeSpoilerPrefs provides state.prefs, content = content)
}
