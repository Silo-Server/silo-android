package org.siloserver.silo.common.ui.components

import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

/**
 * [ThumbhashImage] that can hide its picture behind a blur, for the still of
 * an episode the profile has not started (spoiler protection). The frame, and
 * anything the caller draws over it (badges, progress, play button), is
 * unchanged; only the pixels are obscured.
 *
 * `Modifier.blur` does nothing below API 31 (Android 11 TVs such as the
 * NVIDIA Shield), and a sharp still there would defeat the setting. So on
 * those devices a hidden image shows only the decoded ThumbHash, which is
 * already a blurry colour field, or a neutral box when there is no hash. The
 * real still is never loaded in that case.
 */
@Composable
fun SpoilerImage(
    url: String?,
    thumbhash: String?,
    hidden: Boolean,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    decodeSizePx: Int? = null,
    crossfadeMillis: Int = 300,
    onSuccess: (() -> Unit)? = null,
    onError: (() -> Unit)? = null,
    colorFilter: ColorFilter? = null,
) {
    if (!hidden) {
        ThumbhashImage(
            url = url,
            thumbhash = thumbhash,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale,
            decodeSizePx = decodeSizePx,
            crossfadeMillis = crossfadeMillis,
            onSuccess = onSuccess,
            onError = onError,
            colorFilter = colorFilter,
        )
        return
    }
    if (!canBlurSpoilers || url.isNullOrBlank()) {
        ThumbhashImage(
            url = null,
            thumbhash = thumbhash,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale,
            colorFilter = colorFilter,
        )
        return
    }
    // Clip so the scaled, blurred layer stays inside the caller's frame.
    Box(modifier = modifier.clipToBounds()) {
        ThumbhashImage(
            url = url,
            thumbhash = thumbhash,
            contentDescription = contentDescription,
            contentScale = contentScale,
            // The detail is destroyed by the blur, so a small decode is enough.
            decodeSizePx = SpoilerDecodeSizePx,
            crossfadeMillis = crossfadeMillis,
            onSuccess = onSuccess,
            onError = onError,
            colorFilter = colorFilter,
            modifier = Modifier
                .fillMaxSize()
                // Scaled past the edges so the blur samples real pixels
                // instead of smearing the border inward.
                .graphicsLayer {
                    scaleX = SpoilerScale
                    scaleY = SpoilerScale
                }
                .blur(SpoilerBlurRadius, BlurredEdgeTreatment.Rectangle),
        )
    }
}

/** True where `Modifier.blur` renders (API 31+). */
@get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S)
val canBlurSpoilers: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

private const val SpoilerScale = 1.18f
private val SpoilerBlurRadius = 24.dp
private const val SpoilerDecodeSizePx = 256
