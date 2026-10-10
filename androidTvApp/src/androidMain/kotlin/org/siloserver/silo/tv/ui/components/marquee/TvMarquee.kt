package org.siloserver.silo.tv.ui.components.marquee

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.CircularProgressIndicator
import org.siloserver.silo.common.ui.marquee.MarqueeColors
import org.siloserver.silo.common.ui.marquee.MarqueeScrim
import org.siloserver.silo.common.ui.marquee.MarqueeScrimStyle
import org.siloserver.silo.common.ui.marquee.MarqueeServerMark
import org.siloserver.silo.common.ui.marquee.rememberReduceMotion
import org.siloserver.silo.tv.R

/**
 * First-run sizes on Android TV: the Apple TV values at half scale, since
 * the TV surface is about 960×540dp against Apple TV's 1920×1080 points.
 */
object TvMarqueeMetrics {
    val ButtonHeight = 38.dp
    val ButtonFont = 14.5.sp
    val ButtonPadding = 20.dp
    val FieldHeight = 42.dp
    val FieldFont = 15.sp
    val FieldCorner = 8.dp
    val HeroFont = 44.sp
    val LeadFont = 14.sp
    val CopyWidth = 450.dp
    val CardWidth = 320.dp
}

/**
 * Every TV first-run button rests as glass (plain ones as bare text) and
 * fills white when focused, like the rest of the TV app.
 */
enum class TvMarqueeButtonKind { Primary, Glass, Plain }

/**
 * Not disabled while work runs by default: on TV a disabled control loses
 * focus. Callers ignore repeat presses instead, and pass [enabled] false only
 * when the action truly can't run.
 */
@Composable
fun TvMarqueeButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: TvMarqueeButtonKind = TvMarqueeButtonKind.Primary,
    isLoading: Boolean = false,
    enabled: Boolean = true,
    compact: Boolean = false,
    focusRequester: FocusRequester? = null,
    label: @Composable RowScope.(ink: Color) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val reduceMotion = rememberReduceMotion()
    val scale by animateFloatAsState(if (focused && !reduceMotion) 1.06f else 1f, label = "tvMarqueeButtonScale")
    val ink = when {
        focused -> Color.Black
        kind == TvMarqueeButtonKind.Plain -> MarqueeColors.InkSecondary
        else -> MarqueeColors.Ink
    }
    val height = if (compact) TvMarqueeMetrics.ButtonHeight * 0.8f else TvMarqueeMetrics.ButtonHeight
    Row(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(if (focused) 12.dp else 0.dp, CircleShape, clip = false)
            .height(height)
            .clip(CircleShape)
            .then(
                when {
                    focused -> Modifier.background(MarqueeColors.Ink)
                    kind == TvMarqueeButtonKind.Plain -> Modifier
                    else -> Modifier.background(MarqueeColors.GlassFill).border(1.dp, MarqueeColors.Hairline, CircleShape)
                },
            )
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .alpha(if (enabled) 1f else 0.45f)
            .padding(horizontal = if (compact) TvMarqueeMetrics.ButtonPadding * 0.7f else TvMarqueeMetrics.ButtonPadding),
        horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isLoading) CircularProgressIndicator(color = ink, strokeWidth = 1.5.dp, modifier = Modifier.size(14.dp))
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.tv.material3.LocalTextStyle provides androidx.compose.ui.text.TextStyle(
                fontSize = if (compact) TvMarqueeMetrics.ButtonFont * 0.85f else TvMarqueeMetrics.ButtonFont,
                fontWeight = if (kind == TvMarqueeButtonKind.Plain && !focused) FontWeight.Medium else FontWeight.SemiBold,
            ),
        ) {
            label(ink)
        }
    }
}

@Composable
fun TvMarqueeButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: TvMarqueeButtonKind = TvMarqueeButtonKind.Primary,
    isLoading: Boolean = false,
    enabled: Boolean = true,
    compact: Boolean = false,
    icon: ImageVector? = null,
    focusRequester: FocusRequester? = null,
) {
    TvMarqueeButton(onClick, modifier, kind, isLoading, enabled, compact, focusRequester) { ink ->
        if (icon != null) Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(if (compact) 14.dp else 16.dp))
        Text(text, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Apple TV-style first-run screen: the backdrop full-bleed under a leading
 * scrim, copy on the left, the action card on the right. Each side is its
 * own focus group.
 */
@Composable
fun TvMarqueeScreen(
    modifier: Modifier = Modifier,
    /** The copy column scrolls only when it can't fit (large text, the keyboard up). */
    copyScroll: ScrollState = rememberScrollState(),
    accessory: @Composable RowScope.() -> Unit = {},
    copy: @Composable ColumnScope.() -> Unit,
    card: @Composable () -> Unit,
) {
    Box(modifier.fillMaxSize()) {
        MarqueeScrim(MarqueeScrimStyle.Leading)
        Column(Modifier.fillMaxSize().padding(horizontal = 45.dp, vertical = 30.dp)) {
            Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.silo_wordmark),
                    contentDescription = "Silo",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.width(75.dp),
                )
                Spacer(Modifier.weight(1f))
                accessory()
            }
            Row(
                Modifier.fillMaxWidth().weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(40.dp),
            ) {
                Column(
                    Modifier
                        .width(TvMarqueeMetrics.CopyWidth)
                        .verticalScroll(copyScroll)
                        .focusGroup(),
                    content = copy,
                )
                Spacer(Modifier.weight(1f))
                Box(Modifier.focusGroup()) { card() }
            }
        }
    }
}

/** The right-hand glass card on TV first-run screens. */
@Composable
fun TvMarqueeCard(
    modifier: Modifier = Modifier,
    width: Dp = TvMarqueeMetrics.CardWidth,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .width(width)
            .shadow(20.dp, shape, clip = false)
            .clip(shape)
            .background(Color(0xFF1F1F1F).copy(alpha = 0.72f))
            .border(1.dp, MarqueeColors.Hairline, shape)
            .padding(26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

/** Big TV title. */
@Composable
fun TvMarqueeHeadline(text: String, modifier: Modifier = Modifier, maxLines: Int = 3) {
    Text(
        text,
        modifier = modifier.semantics { heading() },
        color = MarqueeColors.Ink,
        fontSize = TvMarqueeMetrics.HeroFont,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-1).sp,
        lineHeight = 48.sp,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Body copy at TV size, secondary ink. */
@Composable
fun TvMarqueeBody(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = TvMarqueeMetrics.LeadFont,
    color: Color = MarqueeColors.InkSecondary,
    textAlign: TextAlign? = null,
) {
    Text(text, modifier = modifier, color = color, fontSize = size, lineHeight = size * 1.35f, textAlign = textAlign)
}

/** Inline error under a control. */
@Composable
fun TvMarqueeErrorText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.clearAndSetSemantics { contentDescription = "Error: $text" },
        color = MarqueeColors.Error,
        fontSize = 14.sp,
        lineHeight = 16.sp,
    )
}

/** Small status capsule ("Looking for a phone or tablet…"). */
@Composable
fun TvMarqueeStatusChip(text: String, modifier: Modifier = Modifier, showsSpinner: Boolean = false, icon: ImageVector? = null) {
    Row(
        modifier
            .height(30.dp)
            .clip(CircleShape)
            .background(MarqueeColors.GlassFill)
            .border(1.dp, MarqueeColors.Hairline, CircleShape)
            .padding(horizontal = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showsSpinner) {
            CircularProgressIndicator(color = MarqueeColors.InkSecondary, strokeWidth = 2.dp, modifier = Modifier.size(13.dp))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, tint = MarqueeColors.InkSecondary, modifier = Modifier.size(12.dp))
        }
        Text(text, color = MarqueeColors.InkSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** A large symbol in a glass circle, for card states without a QR code or code. */
@Composable
fun TvMarqueeCardSymbol(icon: ImageVector, tint: Color = MarqueeColors.Ink, size: Dp = 56.dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(MarqueeColors.GlassFill)
            .border(1.dp, MarqueeColors.Hairline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.46f))
    }
}

/**
 * The card beside TV text entry: a nearby phone can still finish setup, so
 * nothing has to be typed with the remote.
 */
@Composable
fun TvUsePhoneCard(text: String) {
    TvMarqueeCard {
        TvMarqueeCardSymbol(androidx.compose.material.icons.Icons.Outlined.PhoneAndroid)
        Text(
            "Easier with a phone",
            color = MarqueeColors.Ink,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 17.dp),
        )
        TvMarqueeBody(text, size = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 7.dp))
    }
}

/**
 * "Now showing" card: which server this TV is signing in to, with its mark,
 * address, and whether the connection is encrypted.
 */
@Composable
fun TvMarqueeServerCard(name: String, address: String, markUrl: String?, secure: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(15.dp)
    Row(
        modifier
            .clip(shape)
            .background(Color.White.copy(alpha = 0.07f))
            .border(1.dp, MarqueeColors.Hairline, shape)
            .padding(12.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "$name, $address, ${if (secure) "Secure" else "HTTP"}"
            },
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MarqueeServerMark(name = name, imageUrl = markUrl, size = 40.dp)
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(name, color = MarqueeColors.Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(address, color = MarqueeColors.InkTertiary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val warning = !secure
        Row(
            Modifier
                .height(19.dp)
                .clip(CircleShape)
                .background(if (warning) MarqueeColors.Warning.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.45f))
                .border(1.dp, if (warning) MarqueeColors.Warning.copy(alpha = 0.35f) else MarqueeColors.Hairline, CircleShape)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (secure) "SECURE" else "HTTP",
                color = if (warning) MarqueeColors.Warning else MarqueeColors.Ink,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp,
            )
        }
    }
}

/**
 * The code a person compares between the TV and their phone. Codes are
 * server-generated and may be longer than eight characters, so tiles shrink
 * to keep the row inside the card.
 */
@Composable
fun TvMarqueeCodeTiles(code: String, modifier: Modifier = Modifier, maxWidth: Dp = 260.dp) {
    val characters = code.uppercase().toList()
    val gap = 7.dp
    val tileWidth = minOf(42.dp, (maxWidth - gap * (characters.size - 1).coerceAtLeast(0)) / characters.size.coerceAtLeast(1))
    Row(
        modifier.clearAndSetSemantics { contentDescription = "Code: " + characters.joinToString(", ") },
        horizontalArrangement = Arrangement.spacedBy(gap),
    ) {
        characters.forEach { character ->
            val separator = character == '-' || character == ' '
            Box(
                Modifier
                    .width(if (separator) tileWidth * 0.45f else tileWidth)
                    .height(tileWidth * 1.24f)
                    .then(
                        if (separator) {
                            Modifier
                        } else {
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(MarqueeColors.GlassFill)
                                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (character == '-') "–" else character.toString(),
                    color = if (separator) MarqueeColors.InkTertiary else MarqueeColors.Ink,
                    fontSize = (tileWidth.value * 0.66f).sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
