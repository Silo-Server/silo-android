package org.siloserver.silo.tv.ui.screens.watchparty

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import org.siloserver.silo.common.ui.components.ThumbhashImage
import org.siloserver.silo.model.watchtogether.RoomMember
import org.siloserver.silo.model.watchtogether.RoomPhase
import org.siloserver.silo.tv.ui.components.rememberTvDialogInitialFocus
import org.siloserver.silo.tv.ui.focus.TvControlState
import org.siloserver.silo.tv.ui.focus.requestFocusUntilObserved
import org.siloserver.silo.tv.ui.focus.tvControlSemantics
import org.siloserver.silo.tv.ui.focus.tvModalFocusBoundary
import org.siloserver.silo.tv.ui.theme.DarkBackground
import org.siloserver.silo.tv.ui.theme.DarkSurfaceElevated
import org.siloserver.silo.tv.ui.theme.FocusedContainer
import org.siloserver.silo.tv.ui.theme.FocusedContent
import org.siloserver.silo.tv.ui.theme.SiloOnSurface
import org.siloserver.silo.tv.ui.theme.SiloSecondaryText
import org.siloserver.silo.watchtogether.WatchPartyConnectionTone
import org.siloserver.silo.watchtogether.watchPartyAvatarIndex
import org.siloserver.silo.watchtogether.WatchPartySeatState
import org.siloserver.silo.watchtogether.watchPartyPresenceSummary
import org.siloserver.silo.watchtogether.watchPartySeatOrder
import org.siloserver.silo.watchtogether.watchPartySeatState
import org.siloserver.silo.watchtogether.watchPartySeatStatus

/*
 * The Watch Party look, ported from the tvOS clients (`WatchPartyComponents`
 * in silo-apple). tvOS metrics are pixels at 1920×1080; this app lays out a
 * 960×540dp canvas and scales text by 0.86, so lengths are px / 2 and text is
 * px / 2 / 0.86, never below the TV app's 14sp reading floor (eyebrows,
 * captions, and badges land on the floor rather than at tvOS's smaller size).
 */

internal object TvPartyMetrics {
    /** tvOS page inset (88) plus the system's horizontal safe area (80). */
    val pageInsetX = 84.dp
    val headerTop = 58.dp
    val seat = 64.dp
    val seatGap = 18.dp
    val heroTitle = 50.sp
    val body = 15.sp
    val overview = 15.sp
    val caption = 14.sp
    val eyebrow = 14.sp
    val code = 17.sp
    val ballotPosterWidth = 104.dp
}

/** The TV app's smallest readable text size. */
private const val MIN_SP = 14f

internal val PartyAmber = Color(0xFFF5A524)
internal val PartyAmberText = Color(0xFFF5C563)
internal val PartyEmerald = Color(0xFF34D399)
internal val PartyRose = Color(0xFFFB7185)
internal val PartyDestructive = Color(0xFFF2667D)
internal val PartyDestructiveFocused = Color(0xFFC8243B)
internal val PartyChromeFill = Color.White.copy(alpha = 0.10f)
internal val PartyChromeBorder = Color.White.copy(alpha = 0.14f)

/**
 * Focus bookkeeping for a room screen: one requester per named control, and
 * which one holds focus now. Modals record [focusedKey] when they open and
 * hand focus back to it when they close.
 */
@Stable
internal class TvPartyFocus {
    private val requesters = mutableMapOf<String, FocusRequester>()
    var focusedKey by mutableStateOf<String?>(null)
        private set

    fun requester(key: String): FocusRequester = requesters.getOrPut(key) { FocusRequester() }

    fun isFocused(key: String?): Boolean = key != null && focusedKey == key

    fun onFocus(key: String, focused: Boolean) {
        if (focused) focusedKey = key else if (focusedKey == key) focusedKey = null
    }
}

@Composable
internal fun rememberTvPartyFocus(): TvPartyFocus = remember { TvPartyFocus() }

/** Attach [key]'s requester and report its focus to [focus]. */
internal fun Modifier.partyFocus(focus: TvPartyFocus, key: String): Modifier = this
    .focusRequester(focus.requester(key))
    .onFocusChanged { focus.onFocus(key, it.isFocused) }

// ---- Type ------------------------------------------------------------------

@Composable
internal fun TvPartyEyebrow(text: String, modifier: Modifier = Modifier, color: Color = SiloSecondaryText) {
    Text(
        text = text.uppercase(),
        fontSize = TvPartyMetrics.eyebrow,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.4.sp,
        color = color,
        maxLines = 1,
        modifier = modifier,
    )
}

// ---- Backdrop --------------------------------------------------------------

/**
 * Full-bleed artwork under a room screen: the film is the room. A poster
 * (all some titles have) is blurred and dimmed so it tints rather than shows.
 */
@Composable
internal fun TvPartyBackdrop(
    url: String?,
    thumbhash: String? = null,
    isPoster: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().background(DarkBackground)) {
        if (!url.isNullOrBlank() || !thumbhash.isNullOrBlank()) {
            ThumbhashImage(
                url = url,
                thumbhash = thumbhash,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (isPoster) Modifier.blur(14.dp).alpha(0.7f) else Modifier),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Color.Black.copy(alpha = 0.92f),
                        0.34f to Color.Black.copy(alpha = 0.78f),
                        0.62f to Color.Black.copy(alpha = 0.25f),
                        1f to Color.Black.copy(alpha = 0.05f),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.35f),
                        0.3f to Color.Transparent,
                        0.7f to Color.Black.copy(alpha = 0.4f),
                        1f to Color.Black.copy(alpha = 0.95f),
                    ),
                ),
        )
    }
}

// ---- Buttons ---------------------------------------------------------------

internal enum class TvPartyButtonKind { Primary, Secondary, Outlined }

/**
 * The room's pill button: white primary, chrome secondary, outlined. Focus
 * always inverts to the white capsule, as the rest of the TV app does. A
 * [state] gated by work in flight stays focusable and dims.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun TvPartyButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: TvPartyButtonKind = TvPartyButtonKind.Primary,
    icon: ImageVector? = null,
    state: TvControlState = TvControlState.structural(true),
    trailing: String? = null,
    fontSize: TextUnit = if (kind == TvPartyButtonKind.Primary) 17.sp else 15.sp,
    height: Dp = 40.dp,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(100.dp)
    val (restFill, restContent, restBorder) = when (kind) {
        TvPartyButtonKind.Primary -> Triple(SiloOnSurface, DarkBackground, Color.Transparent)
        TvPartyButtonKind.Secondary -> Triple(PartyChromeFill, SiloOnSurface, PartyChromeBorder)
        TvPartyButtonKind.Outlined -> Triple(Color.Transparent, SiloOnSurface, SiloOnSurface.copy(alpha = 0.8f))
    }
    val content = if (isFocused) FocusedContent else restContent
    Surface(
        onClick = { state.perform(onClick) },
        enabled = state.focusable,
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = restFill,
            contentColor = restContent,
            focusedContainerColor = FocusedContainer,
            focusedContentColor = FocusedContent,
            pressedContainerColor = FocusedContainer,
            pressedContentColor = FocusedContent,
            disabledContainerColor = restFill,
            disabledContentColor = restContent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, restBorder), shape = shape),
            focusedBorder = Border(BorderStroke(0.dp, Color.Transparent), shape = shape),
        ),
        glow = ClickableSurfaceDefaults.glow(
            focusedGlow = Glow(elevationColor = Color.White.copy(alpha = 0.22f), elevation = 16.dp),
        ),
        modifier = modifier
            .alpha(if (state.actionable) 1f else 0.45f)
            .tvControlSemantics(state),
    ) {
        Row(
            modifier = Modifier
                .height(height)
                .padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                Icon(imageVector = icon, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(10.dp))
            }
            Text(
                text = label,
                fontSize = fontSize,
                fontWeight = FontWeight.SemiBold,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (trailing != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = trailing, fontSize = TvPartyMetrics.caption, color = content.copy(alpha = 0.6f), maxLines = 1)
            }
        }
    }
}

/** The room code is the invitation: "JOIN WITH <CODE> [qr]". Opens the invitation page. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun TvPartyCodePill(code: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(100.dp)
    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = PartyChromeFill,
            contentColor = SiloOnSurface,
            focusedContainerColor = FocusedContainer,
            focusedContentColor = FocusedContent,
            pressedContainerColor = FocusedContainer,
            pressedContentColor = FocusedContent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, PartyChromeBorder), shape = shape),
            focusedBorder = Border(BorderStroke(0.dp, Color.Transparent), shape = shape),
        ),
        glow = ClickableSurfaceDefaults.glow(
            focusedGlow = Glow(elevationColor = Color.White.copy(alpha = 0.22f), elevation = 16.dp),
        ),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .height(34.dp)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text(
                text = "JOIN WITH",
                fontSize = TvPartyMetrics.caption,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.4.sp,
                color = if (isFocused) Color.Black.copy(alpha = 0.5f) else SiloSecondaryText,
            )
            Text(
                text = code,
                fontSize = TvPartyMetrics.code,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 3.sp,
                color = if (isFocused) FocusedContent else SiloOnSurface,
            )
            Icon(
                imageVector = Icons.Filled.QrCode2,
                contentDescription = "Invite friends",
                tint = if (isFocused) Color.Black.copy(alpha = 0.6f) else SiloSecondaryText,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** The "···" Options button beside the code pill. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun TvPartyOptionsButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(100.dp)
    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = PartyChromeFill,
            contentColor = SiloOnSurface,
            focusedContainerColor = FocusedContainer,
            focusedContentColor = FocusedContent,
            pressedContainerColor = FocusedContainer,
            pressedContentColor = FocusedContent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, PartyChromeBorder), shape = shape),
            focusedBorder = Border(BorderStroke(0.dp, Color.Transparent), shape = shape),
        ),
        glow = ClickableSurfaceDefaults.glow(
            focusedGlow = Glow(elevationColor = Color.White.copy(alpha = 0.22f), elevation = 16.dp),
        ),
        modifier = modifier,
    ) {
        Box(modifier = Modifier.size(width = 68.dp, height = 38.dp), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Filled.MoreHoriz,
                contentDescription = "Party options",
                tint = if (isFocused) FocusedContent else SiloOnSurface,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

// ---- Status ----------------------------------------------------------------

@Composable
internal fun TvPartyConnectionDot(tone: WatchPartyConnectionTone, text: String, modifier: Modifier = Modifier) {
    val color = when (tone) {
        WatchPartyConnectionTone.Good -> PartyEmerald
        WatchPartyConnectionTone.Warning -> PartyAmber
        WatchPartyConnectionTone.Neutral -> SiloSecondaryText
        WatchPartyConnectionTone.Bad -> PartyRose
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(modifier = Modifier.size(6.dp).background(color, CircleShape))
        Text(text = text, fontSize = TvPartyMetrics.caption, color = SiloSecondaryText, maxLines = 1)
    }
}

/** One banner for whatever the member should know. Warnings are amber. */
@Composable
internal fun TvPartyBanner(message: String, modifier: Modifier = Modifier, warning: Boolean = false) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = modifier
            .background(if (warning) PartyAmber.copy(alpha = 0.12f) else PartyChromeFill, shape)
            .border(1.dp, if (warning) PartyAmber.copy(alpha = 0.3f) else PartyChromeBorder, shape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(if (warning) PartyAmber else SiloSecondaryText, CircleShape),
        )
        Text(
            text = message,
            fontSize = TvPartyMetrics.caption,
            lineHeight = 17.sp,
            color = if (warning) PartyAmberText else SiloOnSurface,
        )
    }
}

// ---- Seats -----------------------------------------------------------------

private val AvatarPalette = listOf(
    Color(0xFF5A4A8A) to Color(0xFF2B2450),
    Color(0xFF8A5A3A) to Color(0xFF4A2C1C),
    Color(0xFF3A7A6A) to Color(0xFF1C3F36),
    Color(0xFF7A3A5A) to Color(0xFF3F1C30),
    Color(0xFF4A6A8A) to Color(0xFF213546),
    Color(0xFF7A6A2A) to Color(0xFF3F3616),
)

/** The member's avatar colours, chosen as Apple chooses them so a member keeps one colour everywhere. */
internal fun tvPartyAvatarBrush(member: RoomMember): Brush {
    val (start, end) = AvatarPalette[watchPartyAvatarIndex(member, AvatarPalette.size)]
    return Brush.linearGradient(listOf(start, end))
}

/**
 * One member as a seat: an avatar disc whose ring says where they are. Dashed
 * grey is here but not ready, solid white with a tick is ready or watching,
 * dotted and dimmed is reconnecting.
 */
@Composable
internal fun TvPartySeat(member: RoomMember, state: WatchPartySeatState, size: Dp = TvPartyMetrics.seat) {
    val ring = size * 1.14f
    val ringColor = when (state) {
        WatchPartySeatState.Ready, WatchPartySeatState.Watching -> SiloOnSurface
        WatchPartySeatState.Away -> SiloOnSurface.copy(alpha = 0.25f)
        else -> SiloOnSurface.copy(alpha = 0.38f)
    }
    val done = state == WatchPartySeatState.Ready || state == WatchPartySeatState.Watching
    val nameSize = (size.value * 0.19f / 0.86f).coerceAtLeast(MIN_SP).sp
    val detailSize = (size.value * 0.16f / 0.86f).coerceAtLeast(MIN_SP).sp
    Column(
        modifier = Modifier.width(seatColumnWidth(size)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(size * 0.08f),
    ) {
        Box(
            modifier = Modifier
                .padding(top = size * 0.12f)
                .size(ring),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(size)
                    .alpha(if (state == WatchPartySeatState.Away) 0.45f else 1f)
                    .background(tvPartyAvatarBrush(member), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = tvWatchPartyMemberName(member).take(1).uppercase(),
                    fontSize = (size.value * 0.36f / 0.86f).sp,
                    fontWeight = FontWeight.SemiBold,
                    color = SiloOnSurface,
                )
            }
            Canvas(modifier = Modifier.fillMaxSize()) {
                val width = size.toPx() * 0.035f
                val effect = when (state) {
                    WatchPartySeatState.Ready, WatchPartySeatState.Watching -> null
                    WatchPartySeatState.Away -> PathEffect.dashPathEffect(floatArrayOf(0.1f, width * 3f))
                    else -> PathEffect.dashPathEffect(floatArrayOf(width * 3f, width * 2.2f))
                }
                drawCircle(
                    color = ringColor,
                    radius = this.size.minDimension / 2f - width / 2f,
                    style = Stroke(
                        width = width,
                        pathEffect = effect,
                        cap = if (state == WatchPartySeatState.Away) StrokeCap.Round else StrokeCap.Butt,
                    ),
                )
            }
            if (member.isHost) {
                Text(
                    text = "HOST",
                    fontSize = MIN_SP.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = SiloOnSurface,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        // Text keeps a 14sp floor, so on a small seat the tag is wider than the disc.
                        .wrapContentSize(unbounded = true)
                        .offset(y = -size * 0.16f)
                        .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(100.dp))
                        .border(1.dp, PartyChromeBorder, RoundedCornerShape(100.dp))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
            if (done) {
                // Sits on the ring at 4:30, cutting into it.
                val badge = size * 0.34f
                val offset = ring / 2 * 0.7071f
                Box(
                    modifier = Modifier
                        .offset(x = offset, y = offset)
                        .size(badge)
                        .background(Color.Black, CircleShape)
                        .padding(size * 0.03f)
                        .background(SiloOnSurface, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(size * 0.2f),
                    )
                }
            }
        }
        Text(
            text = tvWatchPartyMemberName(member),
            fontSize = nameSize,
            fontWeight = FontWeight.Medium,
            color = SiloOnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        // "you · not ready": the role rides with the state so long names keep their width.
        listOfNotNull("you".takeIf { member.isSelf }, watchPartySeatStatus(state))
            .takeIf { it.isNotEmpty() }
            ?.let { detail ->
                Text(text = detail.joinToString(" · "), fontSize = detailSize, color = SiloSecondaryText, maxLines = 1)
            }
    }
}

/** Seat columns never narrow past what a 14sp name needs, even when the discs shrink. */
private fun seatColumnWidth(size: Dp): Dp = maxOf(size * 1.5f, 88.dp)

/** The dashed "+" seat that opens the invitation. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun TvPartyInviteSeat(onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = TvPartyMetrics.seat) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    Column(
        modifier = Modifier.width(seatColumnWidth(size)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(size * 0.08f),
    ) {
        Surface(
            onClick = onClick,
            interactionSource = interactionSource,
            shape = ClickableSurfaceDefaults.shape(shape = CircleShape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                contentColor = SiloSecondaryText,
                focusedContainerColor = FocusedContainer,
                focusedContentColor = FocusedContent,
                pressedContainerColor = FocusedContainer,
                pressedContentColor = FocusedContent,
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
            border = ClickableSurfaceDefaults.border(
                border = Border(BorderStroke(size * 0.035f, Color.White.copy(alpha = 0.22f)), shape = CircleShape),
                focusedBorder = Border(BorderStroke(0.dp, Color.Transparent), shape = CircleShape),
            ),
            glow = ClickableSurfaceDefaults.glow(
                focusedGlow = Glow(elevationColor = Color.White.copy(alpha = 0.22f), elevation = 16.dp),
            ),
            modifier = modifier
                .padding(top = size * 0.12f)
                .size(size * 1.14f),
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "Invite friends",
                    tint = if (isFocused) FocusedContent else SiloSecondaryText,
                    modifier = Modifier.size(size * 0.42f),
                )
            }
        }
        Text(
            text = "Invite",
            fontSize = (size.value * 0.19f / 0.86f).coerceAtLeast(MIN_SP).sp,
            fontWeight = FontWeight.Medium,
            color = SiloSecondaryText,
        )
    }
}

/** "HERE NOW · 2 of 3 ready", then the seats and the invite seat. */
@Composable
internal fun TvPartySeatsRow(
    members: List<RoomMember>,
    phase: RoomPhase,
    onInvite: () -> Unit,
    inviteModifier: Modifier = Modifier,
    seatSize: Dp = TvPartyMetrics.seat,
) {
    Column(verticalArrangement = Arrangement.spacedBy(seatSize * 0.2f)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvPartyEyebrow("Here now")
            Text(
                text = watchPartyPresenceSummary(members, phase),
                fontSize = TvPartyMetrics.caption,
                color = SiloSecondaryText.copy(alpha = 0.7f),
                maxLines = 1,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(TvPartyMetrics.seatGap), verticalAlignment = Alignment.Top) {
            watchPartySeatOrder(members).forEach { member ->
                TvPartySeat(member = member, state = watchPartySeatState(member, phase), size = seatSize)
            }
            TvPartyInviteSeat(onClick = onInvite, modifier = inviteModifier, size = seatSize)
        }
    }
}

// ---- Menus -----------------------------------------------------------------

/**
 * A settings-pane row: title left, current value right. Destructive rows
 * read red. Rows gated by work in flight stay focusable.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun TvPartyOptionRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    value: String? = null,
    destructive: Boolean = false,
    state: TvControlState = TvControlState.structural(true),
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(10.dp)
    val titleColor = when {
        isFocused && destructive -> PartyDestructiveFocused
        isFocused -> FocusedContent
        destructive -> PartyDestructive
        else -> SiloOnSurface
    }
    Surface(
        onClick = { state.perform(onClick) },
        enabled = state.focusable,
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.04f),
            contentColor = SiloOnSurface,
            focusedContainerColor = FocusedContainer,
            focusedContentColor = FocusedContent,
            pressedContainerColor = FocusedContainer,
            pressedContentColor = FocusedContent,
            disabledContainerColor = Color.White.copy(alpha = 0.04f),
            disabledContentColor = SiloOnSurface,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)), shape = shape),
            focusedBorder = Border(BorderStroke(0.dp, Color.Transparent), shape = shape),
        ),
        glow = ClickableSurfaceDefaults.glow(
            focusedGlow = Glow(elevationColor = Color.White.copy(alpha = 0.18f), elevation = 12.dp),
        ),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 34.dp)
            .alpha(if (state.actionable) 1f else 0.5f)
            .tvControlSemantics(state),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (value != null) {
                Text(
                    text = value,
                    fontSize = 14.sp,
                    color = (if (isFocused) FocusedContent else SiloOnSurface).copy(alpha = 0.7f),
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * A dimmed-backdrop card for a short list of rows ("Party options", a
 * suggestion's actions), ending in Close. Close has focus on open, so a stray
 * Select never runs a destructive row, and Back dismisses.
 */
@Composable
internal fun TvPartyMenuOverlay(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 450.dp,
    content: @Composable () -> Unit,
) {
    val closeFocus = remember { FocusRequester() }
    var closeFocused by remember { mutableStateOf(false) }
    // The focusable popup hands focus to its first row on its own, and the
    // shared initial-focus helper then stands down, so move focus to Close
    // once the rows exist.
    LaunchedEffect(Unit) {
        requestFocusUntilObserved(
            maxAttempts = MENU_FOCUS_ATTEMPTS,
            awaitAttempt = { withFrameNanos { } },
            requestFocus = closeFocus::requestFocus,
            isFocused = { closeFocused },
        )
    }
    Popup(
        alignment = Alignment.Center,
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            clippingEnabled = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.62f)),
            contentAlignment = Alignment.Center,
        ) {
            val shape = RoundedCornerShape(15.dp)
            Column(
                modifier = modifier
                    .width(width)
                    .background(DarkSurfaceElevated, shape)
                    .border(1.dp, PartyChromeBorder, shape)
                    .padding(24.dp)
                    .tvModalFocusBoundary()
                    .then(rememberTvDialogInitialFocus(closeFocus)),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Text(
                    text = title,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = SiloOnSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
                content()
                TvPartyOptionRow(
                    title = "Close",
                    onClick = onDismiss,
                    modifier = Modifier
                        .focusRequester(closeFocus)
                        .onFocusChanged { closeFocused = it.isFocused },
                )
            }
        }
    }
}

/** Frames to keep asking for Close's initial focus. */
private const val MENU_FOCUS_ATTEMPTS = 6

/** A row of the room's buttons laid out right to left from the trailing edge. */
@Composable
internal fun TvPartyButtonRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(9.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
