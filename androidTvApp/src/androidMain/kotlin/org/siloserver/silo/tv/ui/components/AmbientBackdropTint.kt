package org.siloserver.silo.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import org.siloserver.silo.model.section.SectionItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shared "ambient backdrop accent" published by the focused hero on Home and
 * consumed by [TvRootHeroBackdrop] (and eventually A.6's card glow). Mirrors
 * the tvOS TVRootHeroBackdrop tint behavior — a single accent extracted from
 * the active hero's backdrop image, tinted at low alpha across the page.
 *
 * Default is [Empty] (no item, no accent) — components reading [accent]
 * should render an untinted variant in that case.
 */
@Stable
class AmbientBackdropTintState internal constructor(
    initialAccent: Color? = null,
) {
    private val _accent = mutableStateOf(initialAccent)
    val accent: Color? get() = _accent.value

    private val _currentItem = mutableStateOf<SectionItem?>(null)
    val currentItem: SectionItem? get() = _currentItem.value

    internal val pendingItem: MutableState<SectionItem?> = mutableStateOf(null)

    /** The EFFECTIVE backdrop URL the tint samples — the §9-enriched hero
     *  backdrop when present, else the item's own. The sampler keys on this so
     *  the tint re-samples when an episode upgrades to its series backdrop. */
    internal val pendingUrl: MutableState<String?> = mutableStateOf(null)

    /**
     * Publish the focused [item] plus the EFFECTIVE backdrop [url] to sample.
     * When [url] is null the item's own backdrop (or poster) is used, matching
     * the prior behaviour; Home passes the enriched hero URL so an episode's
     * tint tracks its series backdrop once enrichment lands.
     */
    fun set(item: SectionItem?, url: String? = null) {
        _currentItem.value = item
        pendingItem.value = item
        pendingUrl.value = url ?: item?.let { it.backdropUrl ?: it.posterUrl }
    }

    internal fun acceptAccent(url: String?, accent: Color?) {
        // Guard against a stale extraction completing after the user has moved
        // on, or after the backdrop upgraded under enrichment.
        if (pendingUrl.value == url) {
            _accent.value = accent
        }
    }

    companion object {
        val Empty: AmbientBackdropTintState = AmbientBackdropTintState()
    }
}

/**
 * Composition-scope publisher for the ambient backdrop tint. Default value is
 * [AmbientBackdropTintState.Empty] so consumers don't crash outside Home.
 */
val LocalAmbientBackdropTint = compositionLocalOf { AmbientBackdropTintState.Empty }

/**
 * Creates a remembered [AmbientBackdropTintState] that re-extracts the
 * accent color whenever the published item changes. Use exactly once per
 * page that wants to publish a tint — typically wrap the page content in
 * a `CompositionLocalProvider(LocalAmbientBackdropTint provides …) { … }`.
 */
@Composable
fun rememberAmbientBackdropTintState(): AmbientBackdropTintState {
    val context = LocalContext.current
    val state = remember { AmbientBackdropTintState() }

    LaunchedEffect(state.pendingUrl.value) {
        val url = state.pendingUrl.value
        if (url.isNullOrBlank()) {
            state.acceptAccent(url, null)
            return@LaunchedEffect
        }

        // Memoized accent (tvOS HeroBackdropPalette.tintCache parity): a
        // re-visit or feed recreation publishes the wash without re-decoding,
        // so the tint paints alongside the restored artwork instead of
        // lagging it by an extraction round-trip.
        AmbientTintCache.get(url)?.let { cached ->
            state.acceptAccent(url, cached)
            return@LaunchedEffect
        }

        val accent: Color? = withContext(Dispatchers.IO) {
            runCatching {
                val loader: ImageLoader = SingletonImageLoader.get(context)
                val result = loader.execute(
                    ImageRequest.Builder(context)
                        .data(url)
                        .allowHardware(false) // Palette requires a software bitmap.
                        // Cap the decode at 128px: this request runs per hero
                        // focus change and allowHardware(false) forces a
                        // software bitmap, so an unconstrained request decodes
                        // the full-res backdrop on every D-pad move. Palette
                        // resizes to a small bitmap before quantizing anyway,
                        // so 128px loses nothing for accent extraction.
                        .size(128)
                        .build(),
                )
                val bitmap = (result as? SuccessResult)?.image?.toBitmap()
                    ?: return@runCatching null
                // Match tvOS HeroBackdropPalette: a luminance-normalized AVERAGE
                // color, not the vibrant swatch. The vibrant swatch is the most
                // saturated colour in the art, which produced a heavy over-tinted
                // wash; the average (clamped to ~0.22 luminance) is the subtle,
                // consistent tint tvOS renders.
                averageTint(bitmap)
            }.getOrNull()
        }
        if (accent != null) AmbientTintCache.put(url, accent)
        state.acceptAccent(url, accent)
    }
    return state
}

/**
 * Process-wide memo of extracted accents keyed by sampled URL. Bounded small —
 * entries are a boxed color; 256 covers several screens of heroes.
 */
private val AmbientTintCache = LruCache<String, Color>(256)

/**
 * Average-colour tint, luminance-normalized — the Android port of tvOS
 * `HeroBackdropPalette.sampleTint` + `normalize`. Averages the (already
 * downsampled) backdrop bitmap to a single RGB, then clamps its luminance
 * to ~0.22 so bright art is dimmed and very dark art lifted, giving a
 * subtle, consistent wash over the OLED-black page rather than the heavy,
 * over-saturated tint the vibrant swatch produced.
 */
private fun averageTint(bitmap: Bitmap): Color? {
    val w = bitmap.width
    val h = bitmap.height
    if (w <= 0 || h <= 0) return null
    // Sample on a coarse grid (cap ~64x36 like tvOS's resize) so a large
    // bitmap doesn't cost a full per-pixel pass.
    val stepX = (w / 64).coerceAtLeast(1)
    val stepY = (h / 36).coerceAtLeast(1)
    var rSum = 0.0
    var gSum = 0.0
    var bSum = 0.0
    var count = 0
    var y = 0
    while (y < h) {
        var x = 0
        while (x < w) {
            val p = bitmap.getPixel(x, y)
            rSum += ((p shr 16) and 0xFF)
            gSum += ((p shr 8) and 0xFF)
            bSum += (p and 0xFF)
            count++
            x += stepX
        }
        y += stepY
    }
    if (count == 0) return null
    return normalizeTint(
        r = rSum / count / 255.0,
        g = gSum / count / 255.0,
        b = bSum / count / 255.0,
    )
}

/** Clamp luminance to a target of 0.22 (tvOS `HeroBackdropPalette.normalize`). */
private fun normalizeTint(r: Double, g: Double, b: Double): Color {
    val luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b
    val target = 0.22
    val scale = when {
        luminance <= 0.001 -> 0.0
        luminance > target -> target / luminance
        else -> maxOf(1.0, target / maxOf(luminance, 0.05))
    }
    return Color(
        red = (r * scale).coerceIn(0.0, 1.0).toFloat(),
        green = (g * scale).coerceIn(0.0, 1.0).toFloat(),
        blue = (b * scale).coerceIn(0.0, 1.0).toFloat(),
    )
}
