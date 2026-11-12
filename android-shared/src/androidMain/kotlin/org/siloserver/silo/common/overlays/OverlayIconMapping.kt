package org.siloserver.silo.common.overlays

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import org.siloserver.silo.overlays.OverlayIconId

/**
 * The overlay icons. The generic glyphs are the Lucide icons web maps them to
 * (`web/src/lib/overlays/icons.tsx`), drawn from lucide-react 0.576.0's icon
 * data with Lucide's defaults. The wordmarks (HDR, HDR10, ATMOS, AV1) are
 * text, drawn by the badge from [overlayWordmark]; the Dolby Vision mark is
 * web's own glyph.
 */

/**
 * The text a wordmark icon spells (web `WORDMARK_TEXT`), or `null` for an
 * icon drawn as a glyph by [OverlayIconGlyph]. A badge whose label says the
 * same thing drops the label, so it doesn't read "HDR10 HDR10".
 */
internal fun overlayWordmark(iconId: OverlayIconId): String? =
    when (iconId) {
        OverlayIconId.Hdr10 -> "HDR10"
        OverlayIconId.Hdr -> "HDR"
        OverlayIconId.Atmos -> "ATMOS"
        OverlayIconId.Av1 -> "AV1"
        else -> null
    }

/** Web's `DolbyVisionMark` on its 16-unit grid: a ring with its right half filled. */
private val dolbyVisionPath: Path by lazy {
    PathParser()
        .parsePathString(
            "M8 2c3.3 0 6 2.7 6 6s-2.7 6-6 6-6-2.7-6-6 2.7-6 6-6zm0 1.5A4.5 4.5 0 1 0 8 12.5" +
                "a4.5 4.5 0 0 0 0-9zm0 0v9a4.5 4.5 0 0 0 0-9z",
        )
        .toPath()
}

/** Lucide's view box edge. */
private const val LUCIDE_GRID = 24f

/** Lucide's defaults: stroke width 2 on the 24-unit grid, round caps and joins, no fill. */
private val LucideStroke = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round)

/** One element of a Lucide icon, in its 24-unit grid. */
private sealed interface LucideElement {
    class P(val d: String) : LucideElement
    class Circle(val cx: Float, val cy: Float, val r: Float) : LucideElement
    class Rect(val x: Float, val y: Float, val width: Float, val height: Float, val rx: Float = 0f) : LucideElement
    class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float) : LucideElement
}

/**
 * Each icon's elements, copied from lucide-react 0.576.0
 * (`dist/esm/icons/<name>.js`; `Subtitles` is an alias of `captions`).
 */
private fun lucideElements(iconId: OverlayIconId): List<LucideElement>? = when (iconId) {
    OverlayIconId.Star -> listOf(
        LucideElement.P(
            "M11.525 2.295a.53.53 0 0 1 .95 0l2.31 4.679a2.123 2.123 0 0 0 1.595 1.16l5.166.756a.53.53 0 0 1 " +
                ".294.904l-3.736 3.638a2.123 2.123 0 0 0-.611 1.878l.882 5.14a.53.53 0 0 1-.771.56l-4.618-2.428a2.122 " +
                "2.122 0 0 0-1.973 0L6.396 21.01a.53.53 0 0 1-.77-.56l.881-5.139a2.122 2.122 0 0 0-.611-1.879L2.16 " +
                "9.795a.53.53 0 0 1 .294-.906l5.165-.755a2.122 2.122 0 0 0 1.597-1.16z",
        ),
    )
    OverlayIconId.Clock -> listOf(
        LucideElement.Circle(12f, 12f, 10f),
        LucideElement.P("M12 6v6l4 2"),
    )
    OverlayIconId.Tv -> listOf(
        LucideElement.P("m17 2-5 5-5-5"),
        LucideElement.Rect(x = 2f, y = 7f, width = 20f, height = 15f, rx = 2f),
    )
    OverlayIconId.Film -> listOf(
        LucideElement.Rect(x = 3f, y = 3f, width = 18f, height = 18f, rx = 2f),
        LucideElement.P("M7 3v18"),
        LucideElement.P("M3 7.5h4"),
        LucideElement.P("M3 12h18"),
        LucideElement.P("M3 16.5h4"),
        LucideElement.P("M17 3v18"),
        LucideElement.P("M17 7.5h4"),
        LucideElement.P("M17 16.5h4"),
    )
    OverlayIconId.Award -> listOf(
        LucideElement.P(
            "m15.477 12.89 1.515 8.526a.5.5 0 0 1-.81.47l-3.58-2.687a1 1 0 0 0-1.197 0l-3.586 2.686a.5.5 0 0 " +
                "1-.81-.469l1.514-8.526",
        ),
        LucideElement.Circle(12f, 8f, 6f),
    )
    OverlayIconId.Ribbon -> listOf(
        LucideElement.P("M12 11.22C11 9.997 10 9 10 8a2 2 0 0 1 4 0c0 1-.998 2.002-2.01 3.22"),
        LucideElement.P("m12 18 2.57-3.5"),
        LucideElement.P("M6.243 9.016a7 7 0 0 1 11.507-.009"),
        LucideElement.P("M9.35 14.53 12 11.22"),
        LucideElement.P(
            "M9.35 14.53C7.728 12.246 6 10.221 6 7a6 5 0 0 1 12 0c-.005 3.22-1.778 5.235-3.43 7.5l3.557 " +
                "4.527a1 1 0 0 1-.203 1.43l-1.894 1.36a1 1 0 0 1-1.384-.215L12 18l-2.679 3.593a1 1 0 0 " +
                "1-1.39.213l-1.865-1.353a1 1 0 0 1-.203-1.422z",
        ),
    )
    OverlayIconId.Subtitles -> listOf(
        LucideElement.Rect(x = 3f, y = 5f, width = 18f, height = 14f, rx = 2f),
        LucideElement.P("M7 15h4M15 15h2M7 11h2M13 11h4"),
    )
    OverlayIconId.Languages -> listOf(
        LucideElement.P("m5 8 6 6"),
        LucideElement.P("m4 14 6-6 2-3"),
        LucideElement.P("M2 5h12"),
        LucideElement.P("M7 2h1"),
        LucideElement.P("m22 22-5-10-5 10"),
        LucideElement.P("M14 18h6"),
    )
    OverlayIconId.Building -> listOf(
        LucideElement.P("M10 12h4"),
        LucideElement.P("M10 8h4"),
        LucideElement.P("M14 21v-3a2 2 0 0 0-4 0v3"),
        LucideElement.P("M6 10H4a2 2 0 0 0-2 2v7a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2h-2"),
        LucideElement.P("M6 21V5a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v16"),
    )
    OverlayIconId.Shield -> listOf(
        LucideElement.P(
            "M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 " +
                "6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z",
        ),
    )
    OverlayIconId.Layout -> listOf(
        LucideElement.Rect(x = 3f, y = 3f, width = 18f, height = 7f, rx = 1f),
        LucideElement.Rect(x = 3f, y = 14f, width = 9f, height = 7f, rx = 1f),
        LucideElement.Rect(x = 16f, y = 14f, width = 5f, height = 7f, rx = 1f),
    )
    OverlayIconId.Monitor -> listOf(
        LucideElement.Rect(x = 2f, y = 3f, width = 20f, height = 14f, rx = 2f),
        LucideElement.Line(8f, 21f, 16f, 21f),
        LucideElement.Line(12f, 17f, 12f, 21f),
    )
    OverlayIconId.Volume -> listOf(
        LucideElement.P(
            "M11 4.702a.705.705 0 0 0-1.203-.498L6.413 7.587A1.4 1.4 0 0 1 5.416 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 " +
                "1 1h2.416a1.4 1.4 0 0 1 .997.413l3.383 3.384A.705.705 0 0 0 11 19.298z",
        ),
        LucideElement.P("M16 9a5 5 0 0 1 0 6"),
        LucideElement.P("M19.364 18.364a9 9 0 0 0 0-12.728"),
    )
    OverlayIconId.Calendar -> listOf(
        LucideElement.P("M8 2v4"),
        LucideElement.P("M16 2v4"),
        LucideElement.Rect(x = 3f, y = 4f, width = 18f, height = 18f, rx = 2f),
        LucideElement.P("M3 10h18"),
    )
    OverlayIconId.Globe -> listOf(
        LucideElement.Circle(12f, 12f, 10f),
        LucideElement.P("M12 2a14.5 14.5 0 0 0 0 20 14.5 14.5 0 0 0 0-20"),
        LucideElement.P("M2 12h20"),
    )
    else -> null
}

/** All of an icon's elements as one path on the 24-unit grid. */
private fun lucidePath(elements: List<LucideElement>): Path {
    val path = Path()
    val parser = PathParser()
    for (element in elements) {
        when (element) {
            is LucideElement.P -> path.addPath(parser.parsePathString(element.d).toPath())
            is LucideElement.Circle -> path.addOval(
                Rect(element.cx - element.r, element.cy - element.r, element.cx + element.r, element.cy + element.r),
            )
            is LucideElement.Rect -> path.addRoundRect(
                RoundRect(
                    Rect(element.x, element.y, element.x + element.width, element.y + element.height),
                    CornerRadius(element.rx),
                ),
            )
            is LucideElement.Line -> {
                path.moveTo(element.x1, element.y1)
                path.lineTo(element.x2, element.y2)
            }
        }
    }
    return path
}

/** Parsed once, on first use; the paths are never changed after. */
private val lucidePaths: Map<OverlayIconId, Path> by lazy {
    OverlayIconId.entries.mapNotNull { id -> lucideElements(id)?.let { id to lucidePath(it) } }.toMap()
}

/**
 * Draws a generic overlay glyph at [size], tinted [tint]. Wordmark ids
 * ([overlayWordmark]) are drawn as text by the badge; if one slips through
 * it falls back to a dot.
 */
@Composable
internal fun OverlayIconGlyph(
    iconId: OverlayIconId,
    size: Dp,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val lucide = lucidePaths[iconId]
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        when {
            lucide != null ->
                scale(w / LUCIDE_GRID, h / LUCIDE_GRID, pivot = Offset.Zero) {
                    drawPath(lucide, tint, style = LucideStroke)
                }

            iconId == OverlayIconId.DolbyVision ->
                scale(w / 16f, h / 16f, pivot = Offset.Zero) { drawPath(dolbyVisionPath, tint) }

            // No web counterpart.
            iconId == OverlayIconId.Tomato ->
                drawCircle(tint, radius = w * 0.42f, center = Offset(w / 2f, h / 2f))

            // Wordmarks render as text; if routed here, draw a dot.
            else -> drawCircle(tint, radius = w * 0.18f, center = Offset(w / 2f, h / 2f))
        }
    }
}
