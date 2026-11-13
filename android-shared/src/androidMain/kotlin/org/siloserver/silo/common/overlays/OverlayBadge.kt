package org.siloserver.silo.common.overlays

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.DrawResult
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.size.Size as CoilSize
import org.siloserver.silo.common.i18n.UiText
import org.siloserver.silo.common.i18n.text
import org.siloserver.silo.common.i18n.uppercaseForUi
import org.siloserver.silo.overlays.CardOverlayPrefs
import org.siloserver.silo.overlays.OverlayBadgePaint
import org.siloserver.silo.overlays.OverlayColor
import org.siloserver.silo.overlays.OverlayData
import org.siloserver.silo.overlays.OverlayDef
import org.siloserver.silo.overlays.OverlayIconId
import org.siloserver.silo.overlays.OverlayId
import org.siloserver.silo.overlays.OverlayPreset
import org.siloserver.silo.overlays.OverlayRadius
import org.siloserver.silo.overlays.OverlayShadow
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// MARK: - Resolution

/**
 * What one badge draws for an item (web `ResolvedBadge`), resolved once per
 * item and prefs. A badge with no value is never resolved. [label] and
 * [score] are resolved in composition, so they follow a language change.
 */
@Immutable
internal class ResolvedBadge(
    val id: OverlayId,
    val label: UiText,
    val paint: OverlayBadgePaint,
    /** The accent the badge is painted with: the item's, else the registry default. */
    val accent: OverlayColor?,
    val iconId: OverlayIconId?,
    /** Android-only: draw the icon without the label ([OverlayDef.iconOnly]). */
    val iconOnly: Boolean,
    /** URL of an uploaded image in the icon's place, when listed. */
    val imageUrl: String?,
    /** URL of an uploaded image that replaces the whole badge for its value, when listed. */
    val valueImageUrl: String?,
    /** The rating's source mark, set only together with [score]. */
    val sourceMark: UiText?,
    val score: UiText?,
) {
    companion object {
        fun resolve(
            def: OverlayDef,
            data: OverlayData,
            prefs: CardOverlayPrefs,
            preset: OverlayPreset,
            imageUrl: (String) -> String?,
        ): ResolvedBadge? {
            val label = def.getValue(data) ?: return null
            val config = prefs.items[def.id]
            val candidateIcon = def.getIcon?.invoke(data) ?: def.iconId
            val showIcon = def.iconCapable && candidateIcon != null && (config?.showIcon ?: preset.preferIcon)
            val score = def.getScore?.invoke(data)
            val accent = OverlayColor.parse(config?.accentColor ?: def.defaultAccent)
            val valueImageRef = config?.valueImages
                ?.takeIf { it.isNotEmpty() }
                ?.let { images -> def.getValueKey?.invoke(data)?.let(images::get) }
            return ResolvedBadge(
                id = def.id,
                label = label,
                paint = preset.paint(accent),
                accent = accent,
                iconId = if (showIcon) candidateIcon else null,
                iconOnly = def.iconOnly,
                imageUrl = config?.image?.let(imageUrl),
                valueImageUrl = valueImageRef?.let(imageUrl),
                sourceMark = if (score != null) def.sourceMark else null,
                score = score,
            )
        }
    }
}

/** A rail badge is a tab, flush against this card edge. */
internal enum class OverlayRail { Left, Right }

// MARK: - Metrics

/**
 * One card's badge lengths in pixels: the preset's reference lengths times
 * [unit], the pixels per reference unit at the card's [OverlayScale]
 * (`cardWidth / 185` times the text factor on posters).
 */
@Immutable
internal class OverlayMetrics(
    val preset: OverlayPreset,
    val unit: Float,
    density: Density,
) {
    fun px(reference: Double): Float = (reference * unit).toFloat()

    private val pixelsPerSp: Float = density.density * density.fontScale

    /**
     * A font size that renders at exactly [pixels]. Badge text is sized from
     * [unit], which already carries the text factor, so it must not scale
     * with the font scale again.
     */
    fun fixedSize(pixels: Float): TextUnit = (pixels / pixelsPerSp).sp

    /** The badge font size in pixels, the badge's `em`. */
    val fontPx: Float = px(preset.fontSize)

    /** The badge font size. */
    val fontSize: TextUnit = fixedSize(fontPx)

    val paddingInline: Float = px(preset.paddingInline)
    val paddingBlock: Float = px(preset.paddingBlock)
    val borderWidth: Float = px(preset.borderWidth ?: 0.0)
    val accentBarWidth: Float = px(preset.borderLeftWidth ?: 0.0)

    /** Corner radius, or `null` for fully round ends. */
    val radius: Float? = when (val r = preset.borderRadius) {
        OverlayRadius.Full -> null
        is OverlayRadius.Fixed -> px(r.value)
    }
    val iconSize: Int = px(preset.iconSize).roundToInt()
    val iconGap: Int = px(preset.iconGap).roundToInt()
    val stackGap: Int = px(preset.stackGap).roundToInt()
    val valueImageHeight: Int = px(preset.valueImageHeight).roundToInt()

    val fontWeight: FontWeight = FontWeight(preset.fontWeight.coerceIn(1, 1000))

    fun textShadow(shadow: OverlayShadow?): Shadow? = shadow?.let {
        Shadow(
            color = it.color.toCompose(),
            offset = Offset(px(it.x), px(it.y)),
            blurRadius = cssBlurToShadowRadius(px(it.blur)),
        )
    }

    /** A one-line text style with web's `leading-none`: the line is exactly as tall as the font. */
    fun textStyle(color: Color, size: TextUnit, weight: FontWeight, shadow: Shadow?, letterSpacingEm: Double): TextStyle =
        TextStyle(
            color = color,
            fontSize = size,
            fontWeight = weight,
            letterSpacing = letterSpacingEm.toFloat().em,
            lineHeight = size,
            lineHeightStyle = LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Center,
                trim = LineHeightStyle.Trim.Both,
            ),
            platformStyle = PlatformTextStyle(includeFontPadding = false),
            shadow = shadow,
        )
}

internal fun OverlayColor.toCompose(): Color = Color(
    red = red.toFloat().coerceIn(0f, 1f),
    green = green.toFloat().coerceIn(0f, 1f),
    blue = blue.toFloat().coerceIn(0f, 1f),
    alpha = alpha.toFloat().coerceIn(0f, 1f),
)

/**
 * A CSS shadow blur length as an Android shadow-layer radius. CSS draws a
 * Gaussian with sigma = blur / 2; Android's shadow layer uses
 * sigma = 0.57735 * radius + 0.5.
 */
internal fun cssBlurToShadowRadius(blur: Float): Float = max((blur / 2f - 0.5f) / 0.57735f, 0f)

// MARK: - Badge

/** Renders one resolved badge (web `Badge`). */
@Composable
internal fun OverlayBadge(badge: ResolvedBadge, metrics: OverlayMetrics, rail: OverlayRail? = null) {
    if (badge.imageUrl == null && badge.valueImageUrl == null) {
        // The common case: no image state or loader for a card without images.
        TextBadge(badge, metrics, rail, iconImageUrl = null, onIconImageFailed = {})
    } else {
        ImageBackedBadge(badge, metrics, rail)
    }
}

/**
 * A badge with an uploaded image. An image that does not load (deleted since
 * the library was listed) falls back to what the badge draws without it.
 */
@Composable
private fun ImageBackedBadge(badge: ResolvedBadge, metrics: OverlayMetrics, rail: OverlayRail?) {
    var failed by remember(badge.imageUrl, badge.valueImageUrl) { mutableStateOf(emptySet<String>()) }
    val valueImage = badge.valueImageUrl?.takeUnless { it in failed }
    if (valueImage != null) {
        UploadedImage(
            url = valueImage,
            height = metrics.valueImageHeight,
            contentDescription = badge.label.text(),
            onFailed = { failed = failed + valueImage },
        )
    } else {
        TextBadge(
            badge = badge,
            metrics = metrics,
            rail = rail,
            iconImageUrl = badge.imageUrl?.takeUnless { it in failed },
            onIconImageFailed = { url -> failed = failed + url },
        )
    }
}

@Composable
private fun TextBadge(
    badge: ResolvedBadge,
    metrics: OverlayMetrics,
    rail: OverlayRail?,
    iconImageUrl: String?,
    onIconImageFailed: (String) -> Unit,
) {
    val preset = metrics.preset
    val paint = badge.paint
    val label = badge.label.text()
    val score = badge.score?.text()
    val sourceMark = badge.sourceMark?.text()
    // An uploaded image beside a rating stands for its source, so the label
    // drops the mark; the plate preset draws the mark as a chip instead.
    val sourceChip = iconImageUrl == null && preset.sourceChips && badge.sourceMark != null
    val scoreOnly = (iconImageUrl != null || sourceChip) && score != null
    // An uploaded image or a chip takes the icon's place.
    val iconId = if (iconImageUrl == null && !sourceChip) badge.iconId else null
    val wordmark = iconId?.let(::overlayWordmark)
    // As on web, a label the resolved wordmark spells is dropped even when
    // an uploaded image stands in for the mark.
    val badgeWordmark = badge.iconId?.let(::overlayWordmark)
    val text = when {
        scoreOnly -> score
        badgeWordmark != null && badgeWordmark.equals(label.trim(), ignoreCase = true) -> null
        badge.iconOnly && iconId != null -> null
        else -> label
    }
    val textColor = remember(paint) { paint.text.toCompose() }
    val shadow = remember(metrics, paint) { metrics.textShadow(paint.textShadow) }
    val style = remember(metrics, textColor, shadow) {
        metrics.textStyle(textColor, metrics.fontSize, metrics.fontWeight, shadow, preset.letterSpacingEm)
    }
    val hasLeading = iconImageUrl != null || sourceChip || iconId != null
    val hasText = text != null

    val leftBorder = when {
        rail == OverlayRail.Left -> 0f
        paint.accentBar != null -> metrics.accentBarWidth
        else -> metrics.borderWidth
    }
    val rightBorder = if (rail == OverlayRail.Right) 0f else metrics.borderWidth
    val frame = remember(metrics, paint, rail) {
        BadgeFrame(
            background = paint.background.toCompose(),
            border = if (metrics.borderWidth > 0f) paint.border.toCompose() else null,
            accentBar = if (rail != OverlayRail.Left && metrics.accentBarWidth > 0f) paint.accentBar?.toCompose() else null,
            leftBorder = leftBorder,
            rightBorder = rightBorder,
            verticalBorder = metrics.borderWidth,
            radius = metrics.radius,
            rail = rail,
            boxShadow = preset.boxShadow?.let { shadow ->
                BoxShadow(
                    color = shadow.color.toCompose(),
                    dx = metrics.px(shadow.x),
                    dy = metrics.px(shadow.y),
                    radius = cssBlurToShadowRadius(metrics.px(shadow.blur)),
                )
            },
        )
    }
    val policy = BadgeMeasurePolicy(
        hasLeading = hasLeading,
        leadingIsImage = iconImageUrl != null,
        hasText = hasText,
        textNeverTruncates = scoreOnly,
        paddingLeft = (leftBorder + metrics.paddingInline).roundToInt(),
        paddingRight = (rightBorder + metrics.paddingInline).roundToInt(),
        paddingVertical = (metrics.borderWidth + metrics.paddingBlock).roundToInt(),
        gap = metrics.iconGap,
    )
    val shown = text?.let { if (preset.uppercase) it.uppercaseForUi() else it }

    Layout(
        content = {
            when {
                iconImageUrl != null -> UploadedImage(
                    url = iconImageUrl,
                    height = metrics.iconSize,
                    // The image stands for a rating's source while the text
                    // shows the bare score, so it carries the source's name.
                    contentDescription = if (scoreOnly) sourceMark else null,
                    onFailed = { onIconImageFailed(iconImageUrl) },
                )
                sourceChip -> SourceChip(
                    mark = badge.sourceMark!!.text().let { if (preset.uppercase) it.uppercaseForUi() else it },
                    color = badge.accent ?: OverlayColor.White,
                    metrics = metrics,
                    shadow = shadow,
                )
                iconId != null -> OverlayIcon(iconId, wordmark, textColor, metrics, shadow)
            }
            if (shown != null) {
                BasicText(
                    text = shown,
                    style = style,
                    maxLines = 1,
                    softWrap = false,
                    overflow = if (scoreOnly) TextOverflow.Visible else TextOverflow.Ellipsis,
                )
            }
        },
        modifier = Modifier.drawWithCache { frame.draw(this) },
        measurePolicy = policy,
    )
}

/**
 * A badge's row: an optional leading icon, chip or image, then the text,
 * `gap` apart, centred vertically inside the padding (which includes the
 * border). A glyph and a chip keep their own size (web `shrink-0`); an
 * uploaded image is at most the badge's content width (web `max-w-full` on
 * the `<img>`), so a wide logo cannot push the badge past its slot. The text
 * takes what width is left and truncates, unless it is a bare score, which
 * keeps its width.
 */
private data class BadgeMeasurePolicy(
    val hasLeading: Boolean,
    val leadingIsImage: Boolean,
    val hasText: Boolean,
    val textNeverTruncates: Boolean,
    val paddingLeft: Int,
    val paddingRight: Int,
    val paddingVertical: Int,
    val gap: Int,
) : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val chrome = paddingLeft + paddingRight
        val leadConstraints = if (leadingIsImage && constraints.hasBoundedWidth) {
            Constraints(maxWidth = max(0, constraints.maxWidth - chrome))
        } else {
            Constraints()
        }
        val lead = if (hasLeading) measurables.getOrNull(0)?.measure(leadConstraints) else null
        val textMeasurable = if (hasText) measurables.getOrNull(if (hasLeading) 1 else 0) else null
        val gapWidth = if (lead != null && textMeasurable != null) gap else 0
        val leadWidth = lead?.width ?: 0
        val text = textMeasurable?.measure(
            if (textNeverTruncates || !constraints.hasBoundedWidth) {
                Constraints()
            } else {
                Constraints(maxWidth = max(0, constraints.maxWidth - chrome - leadWidth - gapWidth))
            },
        )
        val contentHeight = max(lead?.height ?: 0, text?.height ?: 0)
        val width = (chrome + leadWidth + gapWidth + (text?.width ?: 0))
            .coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = (contentHeight + paddingVertical * 2).coerceIn(constraints.minHeight, constraints.maxHeight)
        return layout(width, height) {
            lead?.place(paddingLeft, paddingVertical + (contentHeight - lead.height) / 2)
            text?.place(paddingLeft + leadWidth + gapWidth, paddingVertical + (contentHeight - text.height) / 2)
        }
    }
}

/** A CSS `box-shadow` (spread 0) in pixels. */
private class BoxShadow(val color: Color, val dx: Float, val dy: Float, val radius: Float)

/**
 * A badge's fill, border, accent bar and box shadow. Lengths in pixels; the
 * border sits inside the box, as CSS draws it. A rail tab has square corners
 * and no border on the edge side.
 */
private class BadgeFrame(
    val background: Color,
    val border: Color?,
    val accentBar: Color?,
    val leftBorder: Float,
    val rightBorder: Float,
    val verticalBorder: Float,
    val radius: Float?,
    val rail: OverlayRail?,
    val boxShadow: BoxShadow?,
) {
    fun draw(scope: CacheDrawScope): DrawResult = with(scope) {
        val w = size.width
        val h = size.height
        val r = min(radius ?: (h / 2f), min(w, h) / 2f)
        val left = if (rail == OverlayRail.Left) 0f else r
        val right = if (rail == OverlayRail.Right) 0f else r
        val outer = Path().apply {
            addRoundRect(
                RoundRect(
                    left = 0f, top = 0f, right = w, bottom = h,
                    topLeftCornerRadius = CornerRadius(left),
                    topRightCornerRadius = CornerRadius(right),
                    bottomRightCornerRadius = CornerRadius(right),
                    bottomLeftCornerRadius = CornerRadius(left),
                ),
            )
        }
        val hasRing = (border != null || accentBar != null) &&
            (leftBorder > 0f || rightBorder > 0f || verticalBorder > 0f)
        val ring = if (hasRing) {
            val inner = Path().apply {
                addRoundRect(
                    RoundRect(
                        left = leftBorder, top = verticalBorder, right = w - rightBorder, bottom = h - verticalBorder,
                        topLeftCornerRadius = CornerRadius(max(0f, left - max(leftBorder, verticalBorder))),
                        topRightCornerRadius = CornerRadius(max(0f, right - max(rightBorder, verticalBorder))),
                        bottomRightCornerRadius = CornerRadius(max(0f, right - max(rightBorder, verticalBorder))),
                        bottomLeftCornerRadius = CornerRadius(max(0f, left - max(leftBorder, verticalBorder))),
                    ),
                )
            }
            Path.combine(PathOperation.Difference, outer, inner)
        } else {
            null
        }
        // With no other border the ring is the bar; with one, the bar is its left strip.
        val bar = if (ring != null && accentBar != null) {
            if (border == null) {
                ring
            } else {
                val strip = Path().apply { addRect(Rect(0f, 0f, leftBorder, h)) }
                Path.combine(PathOperation.Intersect, ring, strip)
            }
        } else {
            null
        }
        // A hardware canvas draws a shadow layer under shapes only from API 28;
        // before that it draws text shadows only. On API 24-27 badges draw no
        // box shadow: the black shape the layer hangs off is clipped away
        // below, so nothing else shows. Text shadows draw on every level.
        val shadowPaint = boxShadow?.let { shadow ->
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.BLACK
                setShadowLayer(max(shadow.radius, Float.MIN_VALUE), shadow.dx, shadow.dy, shadow.color.toArgb())
            }
        }
        val androidOuter = if (shadowPaint != null) outer.asAndroidPath() else null
        onDrawBehind {
            if (shadowPaint != null && androidOuter != null) {
                // CSS draws a box shadow only outside the box.
                drawIntoCanvas { canvas ->
                    canvas.save()
                    canvas.clipPath(outer, ClipOp.Difference)
                    canvas.nativeCanvas.drawPath(androidOuter, shadowPaint)
                    canvas.restore()
                }
            }
            if (background.alpha > 0f) drawPath(outer, background)
            if (ring != null && border != null) drawPath(ring, border)
            if (bar != null && accentBar != null) drawPath(bar, accentBar)
        }
    }
}

/** A rating's source mark as an accent-coloured chip ahead of the score (plate preset). */
@Composable
private fun SourceChip(mark: String, color: OverlayColor, metrics: OverlayMetrics, shadow: Shadow?) {
    val em = metrics.fontPx
    val density = LocalDensity.current
    val shape = remember(em) { RoundedCornerShape(em * 0.2f) }
    val style = remember(metrics, color, shadow) {
        metrics.textStyle(
            color = color.readableText.toCompose(),
            size = metrics.fontSize,
            weight = metrics.fontWeight,
            shadow = shadow,
            letterSpacingEm = metrics.preset.letterSpacingEm,
        )
    }
    BasicText(
        text = mark,
        style = style,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .background(color.toCompose(), shape)
            .padding(
                horizontal = with(density) { (em * 0.35f).toDp() },
                vertical = with(density) { (em * 0.15f).toDp() },
            ),
    )
}

/**
 * An overlay icon at the preset's icon size: a wordmark as heavy text
 * whose line is the icon size tall (web draws it as SVG text on a
 * 12-unit-high view box: font 10, ATMOS font 9), any other icon as a glyph.
 */
@Composable
private fun OverlayIcon(iconId: OverlayIconId, wordmark: String?, tint: Color, metrics: OverlayMetrics, shadow: Shadow?) {
    val size = metrics.iconSize
    if (wordmark != null) {
        val style = remember(metrics, tint, shadow) {
            metrics.textStyle(
                color = tint,
                size = metrics.fixedSize(size * if (iconId == OverlayIconId.Atmos) ATMOS_FONT_SHARE else WORDMARK_FONT_SHARE),
                weight = FontWeight.Black,
                shadow = shadow,
                letterSpacingEm = if (iconId == OverlayIconId.Atmos) ATMOS_TRACKING else 0.0,
            ).copy(lineHeight = metrics.fixedSize(size.toFloat()))
        }
        BasicText(text = wordmark, style = style, maxLines = 1, softWrap = false)
    } else {
        val density = LocalDensity.current
        OverlayIconGlyph(iconId = iconId, size = with(density) { size.toDp() }, tint = tint)
    }
}

/** Web's HDR, HDR10 and AV1 marks: font size 10 on a view box 12 high. */
private const val WORDMARK_FONT_SHARE = 10f / 12f

/** Web's ATMOS mark: font size 9 on a view box 12 high. */
private const val ATMOS_FONT_SHARE = 9f / 12f

/** Web's ATMOS mark: letter spacing 0.5 view-box units at font size 9, in `em`. */
private const val ATMOS_TRACKING = 0.5 / 9.0

/**
 * An administrator's uploaded image, [height] pixels tall and as wide as its
 * aspect ratio makes it, at most the available width. As web's `<img>` with
 * `height` set, `width: auto`, `max-width: 100%` and `object-contain`, a
 * clamped box keeps its height and the image is scaled down to fit inside
 * it, centred. Zero wide until it loads.
 */
@Composable
private fun UploadedImage(url: String, height: Int, contentDescription: String?, onFailed: () -> Unit) {
    val context = LocalPlatformContext.current
    // A fixed request size: the image is at most 600x300, and a zero-wide
    // slot must not hold the load back.
    val request = remember(url, context) {
        ImageRequest.Builder(context).data(url).size(CoilSize.ORIGINAL).build()
    }
    var aspect by remember(url) { mutableFloatStateOf(0f) }
    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        onSuccess = { state ->
            val intrinsic = state.painter.intrinsicSize
            if (intrinsic.isSpecified && intrinsic.height > 0f) aspect = intrinsic.width / intrinsic.height
        },
        onError = { onFailed() },
        modifier = Modifier.layout { measurable, constraints ->
            val natural = (height * aspect).roundToInt()
            val width = if (constraints.hasBoundedWidth) min(natural, constraints.maxWidth) else natural
            val placeable = measurable.measure(Constraints.fixed(width, height))
            layout(width, height) { placeable.place(0, 0) }
        },
    )
}
