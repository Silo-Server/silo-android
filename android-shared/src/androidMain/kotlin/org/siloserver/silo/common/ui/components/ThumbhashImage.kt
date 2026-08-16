package org.siloserver.silo.common.ui.components

import android.util.Base64
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.decode.DataSource
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private val DefaultPlaceholderColor = Color(0xFF1A1D27)

/**
 * Optional feed-scoped gate for presenting newly decoded full-size artwork.
 * The value is a [State] so a scrolling container can provide one stable object
 * without recomposing its entire subtree whenever motion starts or stops.
 */
val LocalImagePresentationDeferral = staticCompositionLocalOf<State<Boolean>?> { null }

/**
 * Process-wide cache of decoded ThumbHash placeholders, keyed by base64 hash.
 * Decoded placeholders are tiny (≤32×32 ARGB) but the decode is a per-pixel
 * cosine pass; caching keeps fast scrolling (and scroll-back) off that path.
 * Thread-safe ([LruCache] is internally synchronized).
 */
private val ThumbhashPainterCache = LruCache<String, BitmapPainter>(256)

private fun decodeThumbhashPainter(hash: String): BitmapPainter? =
    runCatching { ThumbHash.toImageBitmap(Base64.decode(hash, Base64.DEFAULT)) }
        .getOrNull()
        ?.let { BitmapPainter(it) }

/**
 * Async image with an instant ThumbHash blur-up placeholder.
 *
 * Decodes [thumbhash] (a tiny base64-encoded ThumbHash) into a small bitmap and
 * shows it immediately, crossfading to the full network image as it loads — so
 * posters/backdrops blur up instead of popping in from a flat color. Falls back
 * to a neutral placeholder when no thumbhash is supplied or decoding fails.
 *
 * @param url The remote image URL to load.
 * @param thumbhash Base64-encoded ThumbHash for the instant blurred preview.
 * @param contentDescription Accessibility description for the image.
 * @param modifier Compose modifier.
 * @param contentScale How the image content should be scaled.
 * @param transparent When true, skip the opaque placeholder fill (e.g. logos).
 * @param decodeSizePx Optional max decode size (px). Caps bitmap memory where
 *   full resolution would be wasted — e.g. a heavily-blurred full-screen
 *   backdrop. Null lets Coil size to the layout bounds.
 * @param crossfadeMillis Duration of the ThumbHash-to-image fade. Surfaces
 *   with a coordinated transition can supply their shared timing token.
 * @param onSuccess Optional signal that the full image has decoded. Useful for
 *   keeping a semantic fallback visible until transparent artwork is ready.
 * @param cacheKey Overrides the memory/disk cache key, which otherwise defaults
 *   to [url]. Needed when the URL is not stable for the same bytes — a
 *   presigned profile-avatar URL re-signs its query on every fetch, so keying
 *   by the URL would miss the cache every time. Must still be unique per image.
 * @param onError Optional signal that the fetch or decode failed, so the caller
 *   can retire the URL and fall back rather than leave an empty box.
 */
@Composable
fun ThumbhashImage(
    url: String?,
    thumbhash: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    /** Where a non-filling ([ContentScale.Fit]) image sits inside its bounds. */
    alignment: Alignment = Alignment.Center,
    transparent: Boolean = false,
    decodeSizePx: Int? = null,
    crossfadeMillis: Int = 300,
    onSuccess: (() -> Unit)? = null,
    cacheKey: String? = null,
    onError: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val deferPresentationWhile = LocalImagePresentationDeferral.current

    // Cached placeholders resolve synchronously (instant on scroll-back); a cold
    // hash decodes off the composition thread and blurs up a frame later, so the
    // per-cell cosine decode never blocks scrolling.
    val cachedPlaceholder = remember(thumbhash) {
        thumbhash?.takeIf { it.isNotBlank() }?.let { ThumbhashPainterCache.get(it) }
    }
    // Plain `val` (not `by`) so the null-checked `placeholder` smart-casts below.
    val placeholder = produceState(initialValue = cachedPlaceholder, thumbhash, cachedPlaceholder) {
        if (cachedPlaceholder != null) return@produceState
        val hash = thumbhash?.takeIf { it.isNotBlank() } ?: run {
            value = null
            return@produceState
        }
        val decoded = withContext(Dispatchers.Default) { decodeThumbhashPainter(hash) }
        if (decoded != null) ThumbhashPainterCache.put(hash, decoded)
        value = decoded
    }.value

    if (url.isNullOrBlank()) {
        when {
            placeholder != null -> Image(
                painter = placeholder,
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = modifier,
            )
            !transparent -> Box(modifier = modifier.background(DefaultPlaceholderColor))
        }
        return
    }

    val model = remember(url, decodeSizePx, crossfadeMillis, cacheKey) {
        ImageRequest.Builder(context)
            .data(url)
            .apply {
                if (crossfadeMillis > 0) crossfade(crossfadeMillis) else crossfade(false)
            }
            .apply { decodeSizePx?.let { size(it) } }
            .apply {
                cacheKey?.let {
                    memoryCacheKey(it)
                    diskCacheKey(it)
                }
            }
            .build()
    }

    if (deferPresentationWhile == null) {
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            contentScale = contentScale,
            alignment = alignment,
            placeholder = placeholder,
            onSuccess = { onSuccess?.invoke() },
            onError = { onError?.invoke() },
            modifier = when {
                transparent || placeholder != null -> modifier
                else -> modifier.background(DefaultPlaceholderColor)
            },
        )
        return
    }

    // Keep request/decode/cache work moving during a fling, but do not ask the
    // renderer to import a newly completed hardware bitmap until the feed is
    // idle. drawWithContent is important here: merely covering the AsyncImage
    // with its placeholder would still draw (and upload) the bitmap underneath.
    // Once committed, the artwork stays committed through later gestures.
    var fullImageReady by remember(url) { mutableStateOf(false) }
    var fullImagePresented by remember(url) { mutableStateOf(false) }
    val currentOnSuccess by rememberUpdatedState(onSuccess)
    val presentFullImage = {
        if (!fullImagePresented) {
            fullImagePresented = true
            currentOnSuccess?.invoke()
        }
    }

    LaunchedEffect(url, deferPresentationWhile, fullImageReady) {
        if (!fullImageReady || fullImagePresented) return@LaunchedEffect
        snapshotFlow { deferPresentationWhile.value }.first { isMoving -> !isMoving }
        presentFullImage()
    }

    Box(modifier = modifier) {
        if (!fullImagePresented) {
            when {
                placeholder != null -> Image(
                    painter = placeholder,
                    contentDescription = null,
                    contentScale = contentScale,
                    modifier = Modifier.fillMaxSize(),
                )
                !transparent -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(DefaultPlaceholderColor),
                )
            }
        }

        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            contentScale = contentScale,
            alignment = alignment,
            onSuccess = { state ->
                fullImageReady = true
                if (
                    state.result.dataSource == DataSource.MEMORY_CACHE ||
                    !deferPresentationWhile.value
                ) {
                    presentFullImage()
                }
            },
            onError = { onError?.invoke() },
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    if (fullImagePresented) drawContent()
                },
        )
    }
}
