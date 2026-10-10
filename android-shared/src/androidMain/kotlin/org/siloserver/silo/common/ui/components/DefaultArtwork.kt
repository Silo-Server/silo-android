package org.siloserver.silo.common.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.siloserver.silo.common.ui.marquee.MarqueeColors
import org.siloserver.silo.model.catalog.isAudiobookItemType
import org.siloserver.silo.model.catalog.isBookDisplayType
import org.siloserver.silo.model.catalog.isPodcastItemType
import org.siloserver.silo.model.catalog.isTvItemType
import kotlin.math.max

/** Which mark [DefaultArtwork] centres on its glow. */
enum class DefaultArtworkKind(internal val mark: ImageVector?) {
    Video(Icons.Outlined.Movie),
    Tv(Icons.Outlined.Tv),
    /** Audiobooks and podcasts. */
    Audiobook(Icons.Outlined.Headphones),
    Book(Icons.AutoMirrored.Outlined.MenuBook),

    /** The glow alone, for cards that centre their own play button over
     *  the artwork; the mark's edges would show around it. */
    GlowOnly(null),
    ;

    companion object {
        /** Series, seasons and episodes get the TV mark, audiobooks and
         *  podcasts the headphones, ebooks, comics and manga the book;
         *  anything else, including an unknown type, the film. */
        fun forItemType(type: String?): DefaultArtworkKind = when {
            isTvItemType(type) -> Tv
            isAudiobookItemType(type) || isPodcastItemType(type) -> Audiobook
            isBookDisplayType(type) -> Book
            else -> Video
        }
    }
}

/**
 * Largest the centred mark grows. Phone and tablet keep the default; the TV
 * theme raises it so the mark holds up on a big poster across the room.
 */
val LocalDefaultArtworkMarkMaxSize: ProvidableCompositionLocal<Dp> =
    staticCompositionLocalOf { 40.dp }

private val DefaultArtworkBase = Color(0xFF16171C)
private val DefaultArtworkMarkTint = ColorFilter.tint(Color.White.copy(alpha = 0.13f))
private val DefaultArtworkMarkMinSize = 14.dp
private const val DefaultArtworkMarkWidthFraction = 0.24f

/**
 * What a poster, cover or still shows when the title has no artwork: a faint
 * glow in the Silo brand colours with a small mark for the kind of title. The
 * Apple and web clients draw the same artwork, so keep these values in step
 * with them.
 *
 * [contentDescription] stands in for the missing image's own, so a card whose
 * caption is hidden keeps an accessible name. The mark is decoration only.
 */
@Composable
fun DefaultArtwork(
    kind: DefaultArtworkKind,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val described = if (contentDescription != null) {
        Modifier.semantics {
            this.contentDescription = contentDescription
            role = Role.Image
        }
    } else {
        Modifier
    }
    val mark = kind.mark?.let { rememberVectorPainter(it) }
    val markMaxSize = LocalDefaultArtworkMarkMaxSize.current
    Box(
        // Drawn in one pass, no subcomposition: these sit in lazy rows.
        modifier = modifier.then(described).drawWithCache {
            // Sized from the longer side so a poster and a still get the same
            // glow, only cropped differently.
            val side = max(size.width, size.height)
            val glows = if (side > 0f) {
                listOf(
                    glow(MarqueeColors.BrandBlue, alpha = 0.176f, center = Offset.Zero, radius = side * 0.75f),
                    glow(MarqueeColors.BrandOrange, alpha = 0.144f, center = Offset(size.width, size.height), radius = side * 0.6f),
                    glow(MarqueeColors.BrandRed, alpha = 0.112f, center = Offset(size.width * 0.7f, size.height * 0.3f), radius = side * 0.5f),
                )
            } else {
                emptyList()
            }
            val minMark = DefaultArtworkMarkMinSize.toPx()
            val markSide = (size.width * DefaultArtworkMarkWidthFraction)
                .coerceIn(minMark, max(minMark, markMaxSize.toPx()))
            onDrawBehind {
                drawRect(DefaultArtworkBase)
                glows.forEach { drawRect(it) }
                if (mark != null && size.width > 0f && size.height > 0f) {
                    translate((size.width - markSide) / 2f, (size.height - markSide) / 2f) {
                        with(mark) { draw(Size(markSide, markSide), colorFilter = DefaultArtworkMarkTint) }
                    }
                }
            }
        },
    )
}

private fun glow(color: Color, alpha: Float, center: Offset, radius: Float): Brush =
    Brush.radialGradient(
        colors = listOf(color.copy(alpha = alpha), color.copy(alpha = 0f)),
        center = center,
        radius = radius,
    )
