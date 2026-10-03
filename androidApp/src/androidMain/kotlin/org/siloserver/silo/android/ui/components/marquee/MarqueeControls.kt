package org.siloserver.silo.android.ui.components.marquee

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.svg.SvgDecoder
import org.siloserver.silo.common.ui.marquee.MarqueeColors
import org.siloserver.silo.model.auth.SignInProvider

/** First-run sizes on the phone, matching the Apple apps' points. */
object MarqueeMetrics {
    val ButtonHeight = 52.dp
    val ButtonFont = 17.sp
    val ButtonPadding = 24.dp
    val FieldHeight = 52.dp
    val FieldFont = 17.sp
    val FieldCorner = 14.dp
    val HeroFont = 34.sp
    val LeadFont = 16.sp
}

/**
 * The detail page's grammar: one white pill for the primary action, glass
 * pills for alternatives, plain text for the way out.
 */
enum class MarqueeButtonKind { Primary, Glass, Plain }

/** A light tap the moment a first-run control is pressed, so it feels immediate. */
fun Modifier.marqueePressHaptic(
    interactionSource: MutableInteractionSource,
    feedback: Int = HapticFeedbackConstants.KEYBOARD_TAP,
): Modifier = composed {
    val view = LocalView.current
    val pressed by interactionSource.collectIsPressedAsState()
    LaunchedEffect(pressed) { if (pressed) view.performHapticFeedback(feedback) }
    this
}

@Composable
fun MarqueeButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: MarqueeButtonKind = MarqueeButtonKind.Primary,
    fullWidth: Boolean = true,
    isLoading: Boolean = false,
    enabled: Boolean = true,
    /** Smaller utility actions (change server, sign out). */
    compact: Boolean = false,
    icon: ImageVector? = null,
) {
    MarqueeButton(onClick, modifier, kind, fullWidth, isLoading, enabled, compact) { ink ->
        if (icon != null) Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(if (compact) 16.dp else 18.dp))
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun MarqueeButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: MarqueeButtonKind = MarqueeButtonKind.Primary,
    fullWidth: Boolean = true,
    isLoading: Boolean = false,
    enabled: Boolean = true,
    compact: Boolean = false,
    label: @Composable RowScope.(ink: Color) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val ink = when (kind) {
        MarqueeButtonKind.Primary -> Color.Black
        MarqueeButtonKind.Glass -> MarqueeColors.Ink
        MarqueeButtonKind.Plain -> MarqueeColors.InkSecondary
    }
    val height = when {
        compact -> MarqueeMetrics.ButtonHeight * 0.75f
        kind == MarqueeButtonKind.Plain -> MarqueeMetrics.ButtonHeight - 8.dp
        else -> MarqueeMetrics.ButtonHeight
    }
    val fontSize = if (compact) MarqueeMetrics.ButtonFont * 0.85f else MarqueeMetrics.ButtonFont
    Row(
        modifier = modifier
            .then(if (fullWidth) Modifier.fillMaxWidth() else Modifier)
            .height(height)
            .clip(CircleShape)
            .then(
                when (kind) {
                    MarqueeButtonKind.Primary -> Modifier.background(MarqueeColors.Ink)
                    MarqueeButtonKind.Glass -> Modifier
                        .background(MarqueeColors.GlassFill)
                        .border(1.dp, MarqueeColors.Hairline, CircleShape)
                    MarqueeButtonKind.Plain -> Modifier
                },
            )
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .marqueePressHaptic(
                interaction,
                if (kind == MarqueeButtonKind.Primary) HapticFeedbackConstants.VIRTUAL_KEY else HapticFeedbackConstants.KEYBOARD_TAP,
            )
            .alpha(if (!enabled) 0.45f else if (pressed) 0.7f else 1f)
            .padding(horizontal = if (compact) MarqueeMetrics.ButtonPadding * 0.7f else MarqueeMetrics.ButtonPadding),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isLoading) {
            CircularProgressIndicator(color = ink, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        }
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides ink,
            androidx.compose.material3.LocalTextStyle provides androidx.compose.ui.text.TextStyle(
                color = ink,
                fontSize = fontSize,
                fontWeight = if (kind == MarqueeButtonKind.Plain) FontWeight.Medium else FontWeight.SemiBold,
            ),
        ) {
            label(ink)
        }
    }
}

/** Round glass icon button (back, close), as on the detail pages. */
@Composable
fun MarqueeIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(MarqueeColors.GlassFill)
            .border(1.dp, MarqueeColors.Hairline, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, interactionSource = interaction, indication = null, onClick = onClick)
            .marqueePressHaptic(interaction)
            .alpha(if (!enabled) 0.45f else if (pressed) 0.7f else 1f)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MarqueeColors.Ink, modifier = Modifier.size(20.dp))
    }
}

/** Big title with an optional lead line. */
@Composable
fun MarqueeHeadline(
    title: String,
    modifier: Modifier = Modifier,
    lead: String? = null,
    centered: Boolean = false,
) {
    val align = if (centered) TextAlign.Center else TextAlign.Start
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = title,
            modifier = Modifier.semantics { heading() },
            color = MarqueeColors.Ink,
            fontSize = MarqueeMetrics.HeroFont,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.85).sp,
            lineHeight = 38.sp,
            textAlign = align,
        )
        if (lead != null) {
            Text(
                text = lead,
                color = MarqueeColors.InkSecondary,
                fontSize = MarqueeMetrics.LeadFont,
                lineHeight = 22.sp,
                textAlign = align,
            )
        }
    }
}

/** Inline error under a control. Errors never become banners. */
@Composable
fun MarqueeErrorText(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = "Error: $text" },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = MarqueeColors.Error,
            modifier = Modifier.size(16.dp).offset(y = 1.dp),
        )
        Text(text, color = MarqueeColors.Error, fontSize = 14.sp, lineHeight = 19.sp)
    }
}

/** A thin line with a word in the middle ("or"). */
@Composable
fun MarqueeLabeledDivider(text: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(MarqueeColors.Separator))
        Text(text, Modifier.padding(horizontal = 12.dp), color = MarqueeColors.InkTertiary, fontSize = 13.sp)
        Box(Modifier.weight(1f).height(1.dp).background(MarqueeColors.Separator))
    }
}

/**
 * A provider's icon, or its initial on a warm gradient when it has none or
 * the icon can't be drawn.
 */
@Composable
fun MarqueeProviderMark(provider: SignInProvider, size: Dp = 22.dp) {
    val iconUrl = provider.iconUrl
    var failed by remember(iconUrl) { mutableStateOf(iconUrl == null) }
    val shape = RoundedCornerShape(size * 0.27f)
    Box(Modifier.size(size).clip(shape), contentAlignment = Alignment.Center) {
        if (failed) {
            Box(
                Modifier
                    .size(size)
                    .background(Brush.linearGradient(listOf(Color(0xFFFD4B2D), Color(0xFFA21D5C)))),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = provider.displayName.firstOrNull()?.lowercase() ?: "s",
                    color = Color.White,
                    fontSize = (size.value * 0.55f).sp,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
        } else {
            val context = LocalContext.current
            AsyncImage(
                model = remember(iconUrl) {
                    ImageRequest.Builder(context)
                        .data(iconUrl)
                        .apply {
                            // The app's image loader has no SVG decoder; plugin
                            // icons (the OIDC plugin's sso.svg) often are SVG.
                            if (iconUrl.orEmpty().substringBefore('?').endsWith(".svg", ignoreCase = true)) {
                                decoderFactory(SvgDecoder.Factory())
                            }
                        }
                        .build()
                },
                contentDescription = null,
                onError = { failed = true },
                modifier = Modifier.size(size),
            )
        }
    }
}

/**
 * An error buzz each time [trigger] changes to a failure (non-null, non-zero).
 * The value it starts with doesn't buzz, so returning to a screen doesn't
 * replay an old failure.
 */
@Composable
fun MarqueeErrorHaptic(trigger: Any?) {
    val view = LocalView.current
    val initial = remember { trigger }
    LaunchedEffect(trigger) {
        if (trigger != initial && trigger != null && trigger != 0) {
            view.performHapticFeedback(
                if (android.os.Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS,
            )
        }
    }
}
