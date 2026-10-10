package org.siloserver.silo.android.ui.screens.watchparty

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.android.ui.theme.SiloBackground
import org.siloserver.silo.android.ui.theme.SiloOnSurface
import org.siloserver.silo.android.ui.theme.SiloSecondaryText
import org.siloserver.silo.android.ui.theme.SiloSurfaceElevated
import org.siloserver.silo.common.ui.components.ThumbhashImage
import org.siloserver.silo.model.watchtogether.RoomMember
import org.siloserver.silo.model.watchtogether.RoomPhase
import org.siloserver.silo.watchtogether.WatchPartySeatState
import org.siloserver.silo.watchtogether.watchPartyAvatarIndex
import org.siloserver.silo.watchtogether.watchPartyPresenceSummary
import org.siloserver.silo.watchtogether.watchPartySeatOrder
import org.siloserver.silo.watchtogether.watchPartySeatState
import org.siloserver.silo.watchtogether.watchPartySeatStatus

/*
 * The Watch Party look, after silo-apple's WatchPartyComponents (iOS values):
 * the film's artwork is the room, members sit over it as seats, and actions
 * are white-on-black pills.
 */

internal object WatchPartyMetrics {
    val seat = 56.dp
    val seatGap = 12.dp
    val pageInset = 20.dp
    const val HERO_TITLE = 28f
    const val BODY = 15f
    const val CAPTION = 13f
    const val EYEBROW = 11f
    const val CODE = 20f
    val ballotPosterWidth = 44.dp
}

internal object WatchPartyColors {
    val chromeFill = Color.White.copy(alpha = 0.10f)
    val chromeBorder = Color.White.copy(alpha = 0.14f)
    val chromeSelectedFill = Color.White.copy(alpha = 0.18f)
    val outline = Color.White.copy(alpha = 0.28f)
    val amber = Color(0xFFF59E0B)
    val amberText = Color(0xFFF5C563)
    val emerald = Color(0xFF10B981)
    val rose = Color(0xFFF43F5E)
}

@Composable
internal fun WatchPartyEyebrow(text: String, modifier: Modifier = Modifier, color: Color = SiloSecondaryText) {
    Text(
        text = text.uppercase(),
        fontSize = WatchPartyMetrics.EYEBROW.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (WatchPartyMetrics.EYEBROW * 0.12f).sp,
        color = color,
        modifier = modifier,
    )
}

/**
 * Full-bleed artwork under the room. A poster (when that is all there is) is
 * blurred and dimmed so it tints the page rather than competing with it.
 */
@Composable
internal fun WatchPartyBackdrop(
    url: String?,
    thumbhash: String? = null,
    isPoster: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().background(SiloBackground)) {
        Crossfade(targetState = url?.takeIf { it.isNotBlank() }, animationSpec = tween(400), label = "backdrop") { art ->
            if (art != null) {
                ThumbhashImage(
                    url = art,
                    thumbhash = thumbhash,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (isPoster) Modifier.blur(28.dp).alpha(0.7f) else Modifier),
                )
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.6f),
                        0.22f to Color.Black.copy(alpha = 0.25f),
                        0.45f to Color.Black.copy(alpha = 0.72f),
                        0.62f to Color.Black.copy(alpha = 0.94f),
                        0.78f to Color.Black,
                    ),
                ),
        )
    }
}

/** Transparent bar: a round Back and the centred page title. */
@Composable
internal fun WatchPartyTopBar(onBack: (() -> Unit)?, modifier: Modifier = Modifier, title: String = "Watch Party") {
    Box(modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp)) {
        if (onBack != null) {
            WatchPartyCircleButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Navigate back",
                onClick = onBack,
                size = 44.dp,
                modifier = Modifier.align(Alignment.CenterStart),
            )
        }
        Text(
            title,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = SiloOnSurface,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

@Composable
internal fun WatchPartyCircleButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    enabled: Boolean = true,
    shape: Shape = CircleShape,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(WatchPartyColors.chromeFill)
            .border(1.dp, WatchPartyColors.chromeBorder, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
    ) {
        Icon(icon, contentDescription = null, tint = SiloOnSurface, modifier = Modifier.size(size * 0.5f))
    }
}

/** The room code is the invitation: one monospaced pill that opens it. */
@Composable
internal fun WatchPartyCodePill(code: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val size = WatchPartyMetrics.CODE
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy((size * 0.5f).dp),
        modifier = modifier
            .clip(CircleShape)
            .background(WatchPartyColors.chromeFill)
            .border(1.dp, WatchPartyColors.chromeBorder, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = "Party code ${code.toList().joinToString(" ")}. Invite friends" }
            .padding(horizontal = (size * 0.75f).dp, vertical = (size * 0.4f).dp),
    ) {
        Text(
            code,
            fontSize = size.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = (size * 0.18f).sp,
            color = SiloOnSurface,
            maxLines = 1,
        )
        Icon(
            Icons.Filled.QrCode2,
            contentDescription = null,
            tint = SiloSecondaryText,
            modifier = Modifier.size((size * 0.9f).dp),
        )
    }
}

private val avatarPalette = listOf(
    Color(0xFF5A4A8A) to Color(0xFF2B2450),
    Color(0xFF8A5A3A) to Color(0xFF4A2C1C),
    Color(0xFF3A7A6A) to Color(0xFF1C3F36),
    Color(0xFF7A3A5A) to Color(0xFF3F1C30),
    Color(0xFF4A6A8A) to Color(0xFF213546),
    Color(0xFF7A6A2A) to Color(0xFF3F3616),
)

/** The member's avatar colours, chosen as Apple chooses them so a member keeps one colour everywhere. */
internal fun watchPartyAvatarGradient(member: RoomMember): Brush {
    val (start, end) = avatarPalette[watchPartyAvatarIndex(member, avatarPalette.size)]
    return Brush.linearGradient(listOf(start, end))
}

/**
 * One member as a seat: an avatar disc whose ring encodes state. Dashed grey
 * is here but not ready, solid white with a tick is ready (or watching),
 * dotted and dimmed is reconnecting.
 */
@Composable
internal fun WatchPartySeat(member: RoomMember, state: WatchPartySeatState, size: Dp = WatchPartyMetrics.seat) {
    val ring = size * 1.14f
    val ready = state == WatchPartySeatState.Ready || state == WatchPartySeatState.Watching
    val away = state == WatchPartySeatState.Away
    val name = watchPartyMemberName(member)
    val status = watchPartySeatStatus(state)
    val description = buildList {
        add(name)
        if (member.isSelf) add("you")
        if (member.isHost) add("host")
        add(
            when (state) {
                WatchPartySeatState.Ready -> "ready"
                WatchPartySeatState.Watching -> "watching"
                else -> status.orEmpty()
            },
        )
    }.filter { it.isNotBlank() }.joinToString(", ")
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(size * 0.11f),
        modifier = Modifier.width(size * 1.4f).semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        Box(Modifier.padding(top = size * 0.1f).size(ring), contentAlignment = Alignment.Center) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(size)
                    .alpha(if (away) 0.45f else 1f)
                    .clip(CircleShape)
                    .background(watchPartyAvatarGradient(member)),
            ) {
                Text(
                    name.trim().take(1).uppercase(),
                    fontSize = (size.value * 0.36f).sp,
                    fontWeight = FontWeight.SemiBold,
                    color = SiloOnSurface,
                )
            }
            SeatRing(state = state, modifier = Modifier.size(ring))
            if (member.isHost) {
                Text(
                    "HOST",
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = SiloOnSurface,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = -(size * 0.12f))
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black)
                        .border(1.dp, WatchPartyColors.chromeBorder, RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
            if (ready) {
                // Sits on the ring at 4:30, cutting into it.
                val offset = ring / 2 * 0.7071f
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .offset(x = offset, y = offset)
                        .size(size * 0.34f)
                        .clip(CircleShape)
                        .background(Color.Black)
                        .padding(size * 0.03f)
                        .clip(CircleShape)
                        .background(SiloOnSurface),
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(size * 0.2f),
                    )
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                buildAnnotatedString {
                    append(name)
                    if (member.isSelf) {
                        withStyle(SpanStyle(color = SiloSecondaryText, fontWeight = FontWeight.Normal)) { append(" · you") }
                    }
                },
                fontSize = (size.value * 0.19f).sp,
                fontWeight = FontWeight.Medium,
                color = SiloOnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            if (status != null) {
                Text(
                    status,
                    fontSize = (size.value * 0.16f).sp,
                    color = SiloSecondaryText,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun SeatRing(state: WatchPartySeatState, modifier: Modifier) {
    Canvas(modifier) {
        val width = this.size.minDimension * 0.035f / 1.14f
        val radius = (this.size.minDimension - width) / 2
        when (state) {
            WatchPartySeatState.Ready, WatchPartySeatState.Watching ->
                drawCircle(SiloOnSurface, radius = radius, style = Stroke(width))
            WatchPartySeatState.Away -> drawCircle(
                SiloOnSurface.copy(alpha = 0.25f),
                radius = radius,
                style = Stroke(
                    width,
                    cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.1f, width * 3)),
                ),
            )
            else -> drawCircle(
                SiloOnSurface.copy(alpha = 0.38f),
                radius = radius,
                style = Stroke(width, pathEffect = PathEffect.dashPathEffect(floatArrayOf(width * 3, width * 2.2f))),
            )
        }
    }
}

/** The dashed "+" seat that opens the invitation. */
@Composable
private fun WatchPartyInviteSeat(onClick: () -> Unit, size: Dp = WatchPartyMetrics.seat) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(size * 0.11f),
        modifier = Modifier
            .width(size * 1.4f)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = "Invite friends" },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .padding(top = size * 0.1f)
                .size(size * 1.14f)
                .border(size * 0.035f, WatchPartyColors.outline, CircleShape),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = SiloSecondaryText, modifier = Modifier.size(size * 0.4f))
        }
        Text("Invite", fontSize = (size.value * 0.19f).sp, fontWeight = FontWeight.Medium, color = SiloSecondaryText)
    }
}

/** "Here now" with its presence summary, then the seats and the Invite seat. */
@Composable
internal fun WatchPartySeatsRow(
    members: List<RoomMember>,
    phase: RoomPhase,
    onInvite: () -> Unit,
    modifier: Modifier = Modifier,
    seatSize: Dp = WatchPartyMetrics.seat,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(seatSize * 0.25f)) {
        Row(horizontalArrangement = Arrangement.spacedBy(seatSize * 0.2f), verticalAlignment = Alignment.CenterVertically) {
            WatchPartyEyebrow("Here now")
            Text(
                watchPartyPresenceSummary(members, phase),
                fontSize = WatchPartyMetrics.CAPTION.sp,
                color = SiloSecondaryText.copy(alpha = 0.7f),
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(WatchPartyMetrics.seatGap),
            verticalAlignment = Alignment.Top,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 2.dp),
        ) {
            watchPartySeatOrder(members).forEach { member ->
                WatchPartySeat(member, watchPartySeatState(member, phase), seatSize)
            }
            WatchPartyInviteSeat(onClick = onInvite, size = seatSize)
        }
    }
}

internal enum class WatchPartyButtonKind { Primary, Secondary, Outlined }

/** Apple's lobby button: 52dp, 14dp corners, white fill for the primary. */
@Composable
internal fun WatchPartyButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: WatchPartyButtonKind = WatchPartyButtonKind.Primary,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(14.dp)
    val foreground = if (kind == WatchPartyButtonKind.Primary) Color.Black else SiloOnSurface
    val background = when (kind) {
        WatchPartyButtonKind.Primary -> SiloOnSurface
        WatchPartyButtonKind.Secondary -> WatchPartyColors.chromeFill
        WatchPartyButtonKind.Outlined -> Color.Transparent
    }
    val border = when (kind) {
        WatchPartyButtonKind.Primary -> Color.Transparent
        WatchPartyButtonKind.Secondary -> WatchPartyColors.chromeBorder
        WatchPartyButtonKind.Outlined -> SiloOnSurface
    }
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .heightIn(min = 52.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(shape)
            .background(background)
            .border(BorderStroke(1.dp, border), shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = foreground, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        trailing?.invoke(this)
    }
}

internal enum class WatchPartyBannerTone { Warning, Neutral }

/** One banner for anything the member should know. [action] adds trailing buttons. */
@Composable
internal fun WatchPartyBanner(
    text: String,
    modifier: Modifier = Modifier,
    tone: WatchPartyBannerTone = WatchPartyBannerTone.Neutral,
    action: (@Composable () -> Unit)? = null,
) {
    val warning = tone == WatchPartyBannerTone.Warning
    val shape = RoundedCornerShape(12.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (warning) WatchPartyColors.amber.copy(alpha = 0.12f) else WatchPartyColors.chromeFill)
            .border(1.dp, if (warning) WatchPartyColors.amber.copy(alpha = 0.3f) else WatchPartyColors.chromeBorder, shape)
            .padding(start = 13.dp, end = if (action != null) 4.dp else 13.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Box(
            Modifier
                .size((WatchPartyMetrics.CAPTION * 0.6f).dp)
                .clip(CircleShape)
                .background(if (warning) WatchPartyColors.amber else SiloSecondaryText),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            fontSize = (WatchPartyMetrics.CAPTION + 1).sp,
            color = if (warning) WatchPartyColors.amberText else SiloOnSurface,
            modifier = Modifier.weight(1f),
        )
        action?.invoke()
    }
}

@Composable
internal fun WatchPartyPoster(
    url: String?,
    thumbhash: String?,
    width: Dp,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 8.dp,
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.width(width).height(width * 1.5f).clip(shape).background(SiloSurfaceElevated),
    ) {
        if (url.isNullOrBlank()) {
            Icon(Icons.Outlined.Movie, contentDescription = null, tint = SiloSecondaryText)
        } else {
            ThumbhashImage(
                url = url,
                thumbhash = thumbhash,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** A poster with Apple's lifted shadow, for the lobby hero and the confirmation page. */
@Composable
internal fun WatchPartyHeroPoster(url: String?, thumbhash: String?, width: Dp) {
    WatchPartyPoster(
        url = url,
        thumbhash = thumbhash,
        width = width,
        modifier = Modifier.shadow(16.dp, RoundedCornerShape(8.dp), ambientColor = Color.Black, spotColor = Color.Black),
    )
}

/** Soft black under pinned bottom actions. */
internal val WatchPartyBottomScrim = Brush.verticalGradient(
    0f to Color.Transparent,
    0.35f to Color.Black.copy(alpha = 0.9f),
    1f to Color.Black,
)

/** The quiet trailing hint line under the lobby's buttons. */
@Composable
internal fun WatchPartyHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        fontSize = WatchPartyMetrics.CAPTION.sp,
        color = SiloSecondaryText.copy(alpha = 0.7f),
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth(),
    )
}

/** Title-sized heading helper. */
@Composable
internal fun WatchPartyHeroTitle(text: String, color: Color = SiloOnSurface, size: Float = WatchPartyMetrics.HERO_TITLE, maxLines: Int = 3) {
    Text(
        text,
        fontSize = size.sp,
        lineHeight = (size * 1.12f).sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-size * 0.02f).sp,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}
