package org.siloserver.silo.common.overlays

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.siloserver.silo.overlays.CardOverlayPrefs
import org.siloserver.silo.overlays.OverlayData
import org.siloserver.silo.overlays.OverlayDef
import org.siloserver.silo.overlays.OverlayIconId

/**
 * Resolved values needed to render one badge — the Android port of
 * Apple's `OverlayBadgeRenderState`. The live card path uses [resolve]
 * and treats the `null` return as "do not render"; settings preview can
 * use [resolveForPreview] to force a chip.
 */
internal data class OverlayBadgeRenderState(
    val id: org.siloserver.silo.overlays.OverlayId,
    val label: String,
    val iconId: OverlayIconId?,
    val iconOnly: Boolean,
    /** [Color.Unspecified] when the badge has no accent override/default. */
    val accentColor: Color,
) {
    companion object {
        fun resolve(
            def: OverlayDef,
            data: OverlayData,
            prefs: CardOverlayPrefs,
            preset: OverlayPresetStyle,
        ): OverlayBadgeRenderState? {
            val label = def.getValue(data) ?: return null
            return build(def, label, data, prefs, preset)
        }

        fun resolveForPreview(
            def: OverlayDef,
            data: OverlayData,
            prefs: CardOverlayPrefs,
            preset: OverlayPresetStyle,
        ): OverlayBadgeRenderState {
            val label = def.getValue(data) ?: def.label.uppercase()
            return build(def, label, data, prefs, preset)
        }

        private fun build(
            def: OverlayDef,
            label: String,
            data: OverlayData,
            prefs: CardOverlayPrefs,
            preset: OverlayPresetStyle,
        ): OverlayBadgeRenderState {
            val cfg = prefs.items[def.id]
            val dynamicIcon = def.getIcon?.invoke(data)
            val iconId = dynamicIcon ?: def.iconId
            val accentHex = cfg?.accentColor ?: def.defaultAccent
            val showIcon = iconId != null && def.iconCapable && (cfg?.showIcon ?: preset.preferIcon)
            return OverlayBadgeRenderState(
                id = def.id,
                label = label,
                iconId = if (showIcon) iconId else null,
                iconOnly = def.iconOnly,
                accentColor = accentHex?.let { overlayColorFromHex(it) } ?: Color.Unspecified,
            )
        }
    }
}

/**
 * Renders one resolved badge. Android port of Apple's `OverlayBadgeView`.
 * Presentation-only: it draws nothing data-dependent beyond [state].
 */
@Composable
internal fun OverlayBadge(
    state: OverlayBadgeRenderState,
    preset: OverlayPresetStyle,
    modifier: Modifier = Modifier,
    forceOpaqueBackground: Boolean = false,
) {
    val shape = preset.shape()
    val background = preset.background(state.accentColor)
    val paintedBackground = if (forceOpaqueBackground &&
        background != Color.Transparent &&
        background != Color.Unspecified
    ) {
        background.copy(alpha = 1f)
    } else {
        background
    }
    val border = preset.border(state.accentColor)
    val foreground = preset.foreground(state.accentColor)

    var box = modifier.clip(shape)
    if (paintedBackground != Color.Transparent && paintedBackground != Color.Unspecified) {
        box = box.background(paintedBackground, shape)
    }
    if (border != Color.Unspecified) {
        box = box.border(1.dp * preset.scale, border, shape)
    }
    box = box
        .padding(horizontal = preset.horizontalPadding, vertical = preset.verticalPadding)

    Row(
        modifier = box,
        horizontalArrangement = Arrangement.spacedBy(4.dp * preset.scale),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val iconId = state.iconId
        var renderedIcon = false
        if (iconId != null) {
            val token = overlayBrandToken(iconId)
            if (token != null) {
                BadgeText(
                    text = token,
                    preset = preset,
                    color = brandTint(iconId, foreground),
                )
                renderedIcon = true
            } else {
                OverlayIconGlyph(
                    iconId = iconId,
                    size = preset.iconSize,
                    tint = brandTint(iconId, foreground),
                )
                renderedIcon = true
            }
        }
        // A brand token (HDR10, ATMOS, …) already spells its text as the
        // mark itself; when the label says the same thing, showing both
        // reads "HDR10 HDR10". Mirrors web's `labelRedundantWithIcon`.
        val labelRedundantWithIcon = renderedIcon &&
            iconId != null &&
            overlayBrandToken(iconId)?.equals(state.label.trim(), ignoreCase = true) == true
        // Apple: render text unless icon-only AND an icon was shown.
        if ((!state.iconOnly || !renderedIcon) && !labelRedundantWithIcon) {
            BadgeText(text = state.label, preset = preset, color = foreground)
        }
    }
}

private fun brandTint(iconId: OverlayIconId, foreground: Color): Color =
    when (iconId) {
        // Tomato keeps its official red unless the preset paints everything
        // in the accent/foreground; here we always pass the preset
        // foreground, matching Apple's `tint` path inside a real card.
        OverlayIconId.Tomato -> foreground
        else -> foreground
    }

@Composable
private fun BadgeText(
    text: String,
    preset: OverlayPresetStyle,
    color: Color,
) {
    val shown = if (preset.uppercase) text.uppercase() else text
    BasicText(
        text = shown,
        style = TextStyle(
            color = color,
            fontSize = preset.fontSize,
            fontWeight = preset.fontWeight,
            letterSpacing = preset.letterSpacing,
            textAlign = TextAlign.Center,
            shadow = if (preset.textShadow) {
                Shadow(
                    color = Color.Black.copy(alpha = 0.85f),
                    offset = Offset(0f, preset.scale),
                    blurRadius = preset.scale,
                )
            } else {
                null
            },
        ),
    )
}
