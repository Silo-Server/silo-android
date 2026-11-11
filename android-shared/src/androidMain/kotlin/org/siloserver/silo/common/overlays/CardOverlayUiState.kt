package org.siloserver.silo.common.overlays

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import org.siloserver.silo.common.settings.OverlayPrefsStore
import org.siloserver.silo.overlays.CardOverlayPrefs
import org.siloserver.silo.overlays.OverlayImage
import org.siloserver.silo.overlays.OverlaySchema

/**
 * Ambient card-overlay configuration published once near the app shell and
 * consumed by every poster/still card. Keeps cards from each injecting the
 * [OverlayPrefsStore] (and re-hydrating it) per instance — the shell hydrates
 * once and provides the resolved [enabled], render prefs and image URLs
 * down the tree.
 *
 * Default is disabled with registry-default prefs, so any card rendered
 * outside a [ProvideCardOverlays] scope simply draws no overlay layer.
 */
@Immutable
data class CardOverlayUiState(
    val enabled: Boolean,
    /**
     * The document to render ([OverlayPrefsStore.cardPrefs]): every image ref
     * it holds is listed, so [imageUrl] resolves it.
     */
    val prefs: CardOverlayPrefs,
    /** Uploaded-image URLs by ref; see [imageUrl]. */
    private val imageUrls: Map<String, String> = emptyMap(),
) {
    /**
     * The URL of the uploaded image [ref] names, or null when the server's
     * library does not list it (draw the badge without the image then, as on
     * web).
     *
     * The URL is absolute, on the server that listed it
     * (`<origin>/api/v2/overlay-images/<ref>`). That endpoint needs no
     * credentials and its bytes never change for a ref, so load it like any
     * artwork URL: `AsyncImage(model = url, ...)` through the app's singleton
     * Coil loader (`buildSiloImageLoader`), which sends no Silo auth headers
     * and keeps it in the disk cache. The image is WebP, up to 600x300; size
     * it from the badge's text height, and fall back to the image-less badge
     * when the load fails (the image was deleted since the list was read).
     */
    fun imageUrl(ref: String): String? = imageUrls[ref]

    companion object {
        /** [imageUrl]'s lookup for [images]; the first of a repeated ref wins. */
        fun imageUrlsOf(images: List<OverlayImage>): Map<String, String> = buildMap {
            for (image in images) {
                if (image.url.isNotBlank() && image.ref !in this) put(image.ref, image.url)
            }
        }
    }
}

val LocalCardOverlayUiState: ProvidableCompositionLocal<CardOverlayUiState> =
    staticCompositionLocalOf {
        CardOverlayUiState(enabled = false, prefs = OverlaySchema.buildDefaults())
    }

/**
 * Hydrates the [store], collects `enabled`, `cardPrefs` and `images`, and
 * publishes them via
 * [LocalCardOverlayUiState] for the [content] subtree. Place this high in each
 * client (app shell / main screen) so the prefs are loaded once per session.
 * The caller injects the singleton [OverlayPrefsStore] (each client has Koin
 * compose support) and hands it in here.
 *
 * [sessionKey] is the authenticated identity that the prefs belong to — the
 * active profile id (or session token). The hydration [LaunchedEffect] is keyed
 * on it rather than on a one-shot `Unit`, because this composable mounts above
 * the whole nav graph and is first composed on the *unauthenticated* Login
 * screen, where the `/settings` calls fail and the store leaves
 * `hasHydrated=false`. When the user signs in / selects a profile the key flips
 * from `null` to the profile id, the effect re-runs, and `hydrateIfNeeded()`
 * retries and succeeds — so cards pick up the admin kill-switch and the user's
 * saved prefs instead of rendering registry defaults forever. A profile switch
 * (key changes to a different id) likewise re-hydrates for the new identity.
 */
@Composable
fun ProvideCardOverlays(
    store: OverlayPrefsStore,
    sessionKey: Any? = null,
    content: @Composable () -> Unit,
) {
    LaunchedEffect(store, sessionKey) {
        // Only attempt hydration once we have an authenticated identity; on the
        // Login screen `sessionKey` is null and the settings calls would 401.
        if (sessionKey != null) store.hydrateIfNeeded()
    }
    val enabled by store.enabled.collectAsState()
    val prefs by store.cardPrefs.collectAsState()
    val images by store.images.collectAsState()
    val imageUrls = remember(images) { CardOverlayUiState.imageUrlsOf(images) }
    val state = remember(enabled, prefs, imageUrls) {
        CardOverlayUiState(enabled = enabled, prefs = prefs, imageUrls = imageUrls)
    }
    CompositionLocalProvider(LocalCardOverlayUiState provides state, content = content)
}
