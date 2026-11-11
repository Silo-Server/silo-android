package org.siloserver.silo.android.ui.screens.player

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.ScreenLockRotation
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.android.ui.components.LeftToRight
import org.siloserver.silo.android.ui.components.SeekIntervalIcon
import org.siloserver.silo.common.R as CommonR
import java.util.Locale

/**
 * Transport controls over the video, after iOS `MobilePlayerControls`:
 *
 * - Top: back, eyebrow + title, then utility discs (Picture in Picture, Cast,
 *   rotation lock).
 * - Center, on the true center of the screen: skip back, a Paper play disc,
 *   skip forward.
 * - Bottom: elapsed and remaining time, the seek bar, and a row of labeled
 *   actions that also say what they are set to (Audio & Subtitles · English,
 *   Chapters · Scene 10, Quality · 1080p), with the full settings menu behind ⋯.
 *
 * While the seek bar is being scrubbed everything except the bar fades to 12%
 * so the bubble and the picture carry the moment.
 *
 * Tabletop posture groups the same pieces into the lower pane and adds the
 * brightness, volume, speed, and next-episode shortcuts when they fit.
 */
@Composable
fun PlayerControls(
    title: String,
    subtitle: String,
    isPlaying: Boolean,
    isPaused: Boolean,
    // The loading spinner is up: it takes the play disc's place.
    isLoading: Boolean = false,
    position: Double,
    duration: Double,
    bufferedPosition: Double,
    hasChapters: Boolean,
    hasTracks: Boolean,
    // The plan's Quality menu; hidden only when there is no playback plan
    // (offline downloads). Version, for a title with several files, lives in
    // the settings menu behind ⋯.
    hasQualityMenu: Boolean,
    chapters: List<org.siloserver.silo.model.catalog.VersionChapter> = emptyList(),
    intro: org.siloserver.silo.model.catalog.TimeRange? = null,
    credits: org.siloserver.silo.model.catalog.TimeRange? = null,
    recap: org.siloserver.silo.model.catalog.TimeRange? = null,
    preview: org.siloserver.silo.model.catalog.TimeRange? = null,
    isOrientationLocked: Boolean,
    orientationLockSupported: Boolean = true,
    tabletopMode: Boolean = false,
    // Width / height of the fitted picture, or 0. In portrait the controls
    // dock to the picture's edges instead of the screen's.
    pictureAspect: Float = 0f,
    playbackSpeed: Double = 1.0,
    // False in a Watch Party, where speed is a session-only 1x.
    playbackSpeedEnabled: Boolean = true,
    nextEpisode: PlayerViewModel.NextEpisodeInfo? = null,
    brightnessFraction: Float = 0.5f,
    // Watch Party guest gate: when false the scrubber + skip buttons are
    // inert and dimmed (seek is host-only, so disabled for all guests).
    // Defaults true for solo playback.
    seekEnabled: Boolean = true,
    // Separate from [seekEnabled]: a guest under guest_play_pause keeps the
    // play/pause affordance but loses seek. Defaults true for solo playback.
    playPauseEnabled: Boolean = true,
    // Resolved video intervals the skip buttons use (profile-wide on a
    // revision-9 server, the legacy fixed pair otherwise).
    skipBackSeconds: Int = PlayerViewModel.LEGACY_VIDEO_SEEK_INTERVALS.backSeconds,
    skipForwardSeconds: Int = PlayerViewModel.LEGACY_VIDEO_SEEK_INTERVALS.forwardSeconds,
    // "MOVIE · 2026" or "SEVERANCE · S2:E4" above the title.
    eyebrow: String = subtitle,
    // Current values shown on the action pills.
    tracksValue: String? = null,
    chapterValue: String? = null,
    qualityValue: String? = null,
    pictureInPictureAvailable: Boolean = false,
    onEnterPictureInPicture: () -> Unit = {},
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Double) -> Unit,
    onSkipForward: () -> Unit,
    onSkipBackward: () -> Unit,
    onToggleOrientationLock: () -> Unit,
    onOpenChapters: () -> Unit,
    onOpenTracks: () -> Unit,
    onOpenQuality: () -> Unit,
    onOpenSettings: () -> Unit,
    onSetPlaybackSpeed: (Double) -> Unit = {},
    onPlayNextEpisode: () -> Unit = {},
    onSetBrightness: (Float) -> Unit = {},
    // True while the seek bar is being dragged, so the auto-hide can hold off.
    onScrubbingChange: (Boolean) -> Unit = {},
    // The server's seek-bar preview for the drag target, and where the drag
    // is (null when it ends). See PlayerProgressBar.
    scrubThumbnail: ImageBitmap? = null,
    onScrubTarget: (Double?) -> Unit = {},
    // Google Cast (Chromecast) button — sits in the top bar alongside the other
    // controls. Provided by PlayerScreen; empty by default so this stateless
    // composable stays test-friendly and decoupled from the Cast SDK. The
    // modifier dresses it as a player disc.
    castSlot: @Composable (Modifier) -> Unit = {},
    // How far the bottom group (timeline and actions) reaches up from the foot
    // of the controls, so the overlay's skip pill and credits card sit above it.
    onBottomGroupClearance: (Dp) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var isScrubbing by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    // Read by the bottom group's position callback. Set while placing, which
    // runs before any position callback.
    val controlsCoordinates = remember { mutableStateOf<LayoutCoordinates?>(null) }
    val chromeAlpha = scrubChromeAlpha(isScrubbing)
    val scrimBoost by animateFloatAsState(
        targetValue = if (isScrubbing) 0.18f else 0f,
        animationSpec = tween(180),
        label = "playerScrimBoost",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .onPlaced { controlsCoordinates.value = it }
            .playerScrim(boost = scrimBoost),
    ) {
        val contentModifier = if (tabletopMode) {
            Modifier
                .fillMaxSize()
                // The controls pane begins halfway down the window, so a status
                // bar inset here would create a fake gap below the hinge. Only
                // reserve the real bottom navigation/gesture inset.
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 20.dp, vertical = 10.dp)
        } else {
            // In landscape the safe-drawing insets are lopsided (camera cutout
            // on one edge, nothing on the other), so padding by them directly
            // pushes the toolbar and progress bar off the device's centre line.
            // Apply the larger horizontal inset to BOTH sides: the controls stay
            // clear of the camera and remain centred on the display.
            //
            // Deliberately NO vertical inset padding. PlayerScreen hides the
            // system bars for the whole lifetime of this screen, so there is
            // nothing at the top or bottom edge to avoid. Worse, when the
            // activity forces landscape while the display stays at its portrait
            // rotation, the window is handed the *portrait* inset set: on a
            // Pixel 9 Pro that is a 68dp "status bar" on top and a 36dp
            // "navigation bar" underneath, which dropped the toolbar a quarter
            // of the way down the screen and lifted the seek bar off the bottom.
            val layoutDirection = LocalLayoutDirection.current
            val safeDrawing = WindowInsets.safeDrawing
            val horizontalInset = with(density) {
                maxOf(
                    safeDrawing.getLeft(this, layoutDirection),
                    safeDrawing.getRight(this, layoutDirection),
                ).toDp()
            }
            Modifier
                .fillMaxSize()
                .padding(horizontal = horizontalInset)
                .padding(horizontal = 18.dp, vertical = 6.dp)
        }

        val topBar: @Composable (showTitle: Boolean) -> Unit = { showTitle ->
            PlayerTopBar(
                showTitle = showTitle,
                eyebrow = eyebrow,
                title = title,
                isOrientationLocked = isOrientationLocked,
                orientationLockSupported = orientationLockSupported,
                pictureInPictureAvailable = pictureInPictureAvailable,
                onBack = onBack,
                onEnterPictureInPicture = onEnterPictureInPicture,
                onToggleOrientationLock = onToggleOrientationLock,
                castSlot = castSlot,
                modifier = Modifier.alpha(chromeAlpha),
            )
        }
        val transport: @Composable (compact: Boolean) -> Unit = { compact ->
            PlayerTransportControls(
                compact = compact,
                isPlaying = isPlaying,
                isPaused = isPaused,
                isLoading = isLoading,
                seekEnabled = seekEnabled,
                playPauseEnabled = playPauseEnabled,
                skipBackSeconds = skipBackSeconds,
                skipForwardSeconds = skipForwardSeconds,
                onPlayPause = onPlayPause,
                onSkipForward = onSkipForward,
                onSkipBackward = onSkipBackward,
                modifier = Modifier.alpha(chromeAlpha),
            )
        }
        val bottom: @Composable (withTransport: Boolean) -> Unit = { withTransport ->
            Column(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .onGloballyPositioned { group ->
                        val controls = controlsCoordinates.value ?: return@onGloballyPositioned
                        if (!controls.isAttached) return@onGloballyPositioned
                        val top = controls.localPositionOf(group, Offset.Zero).y
                        onBottomGroupClearance(with(density) { (controls.size.height - top).toDp() })
                    },
            ) {
                PlayerProgressBar(
                    position = position,
                    duration = duration,
                    bufferedPosition = bufferedPosition,
                    onSeek = onSeek,
                    enabled = seekEnabled,
                    chapters = chapters,
                    intro = intro,
                    credits = credits,
                    recap = recap,
                    preview = preview,
                    onScrubbingChange = {
                        isScrubbing = it
                        onScrubbingChange(it)
                    },
                    thumbnail = scrubThumbnail,
                    onScrubTarget = onScrubTarget,
                )
                if (withTransport) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        transport(true)
                    }
                }
                PlayerActionPillRow(
                    hasTracks = hasTracks,
                    hasChapters = hasChapters,
                    hasQualityMenu = hasQualityMenu,
                    tracksValue = tracksValue,
                    chapterValue = chapterValue,
                    qualityValue = qualityValue,
                    onOpenTracks = onOpenTracks,
                    onOpenChapters = onOpenChapters,
                    onOpenQuality = onOpenQuality,
                    onOpenSettings = onOpenSettings,
                    modifier = Modifier.alpha(chromeAlpha),
                )
            }
        }

        if (tabletopMode) {
            BoxWithConstraints(modifier = contentModifier) {
                // Compact/asymmetric foldables may expose a shallower lower
                // pane. Keep brightness reachable everywhere, and add the
                // wider volume/speed/next controls when they fit comfortably.
                val showFullUtilityRow = maxWidth >= 600.dp && maxHeight >= 300.dp
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    topBar(true)
                    transport(false)
                    bottom(false)
                    TabletopUtilityRow(
                        playbackSpeed = playbackSpeed,
                        playbackSpeedEnabled = playbackSpeedEnabled,
                        nextEpisode = nextEpisode,
                        compact = !showFullUtilityRow,
                        brightnessFraction = brightnessFraction,
                        onSetPlaybackSpeed = onSetPlaybackSpeed,
                        onPlayNextEpisode = onPlayNextEpisode,
                        onSetBrightness = onSetBrightness,
                    )
                }
            }
        } else {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                // Portrait, fitted picture: the controls gather around the
                // picture instead of pinning to the ends of a tall, mostly
                // black screen. The bar and title sit on the picture's top
                // edge; the timeline, a compact transport and the labelled
                // actions sit under it, so nothing covers the small picture.
                val pictureHeight = if (maxHeight > maxWidth && pictureAspect > 0f) {
                    minOf(maxWidth / pictureAspect, maxHeight)
                } else {
                    null
                }
                val pictureTop = pictureHeight?.let { (maxHeight - it) / 2 }
                if (pictureHeight != null && pictureTop != null && pictureTop >= PortraitDockBelow) {
                    // One group on the picture's top edge: the bar (Back, Cast…),
                    // then the title, so nothing is stranded at the screen's top.
                    // Inside the padded column for its side insets, in a box as
                    // tall as the space above the picture, aligned to its foot.
                    Column(modifier = contentModifier) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(pictureTop - 18.dp),
                            contentAlignment = Alignment.BottomStart,
                        ) {
                            Column {
                                topBar(false)
                                PlayerTitleBlock(
                                    eyebrow = eyebrow,
                                    title = title,
                                    maxTitleLines = 2,
                                    modifier = Modifier
                                        .alpha(chromeAlpha)
                                        .padding(start = 6.dp, end = 6.dp, top = 10.dp),
                                )
                            }
                        }
                    }
                    Column(modifier = contentModifier) {
                        // With the column's 6dp padding the timeline starts 12dp
                        // under the picture: the gap the overlay keeps above it,
                        // so the skip pill and credits card stand on the picture's foot.
                        Spacer(modifier = Modifier.height(pictureTop + pictureHeight + 6.dp))
                        bottom(true)
                    }
                } else {
                    Column(modifier = contentModifier) {
                        topBar(true)
                        Spacer(modifier = Modifier.weight(1f))
                        bottom(false)
                    }
                    // The insets are asymmetric in landscape, so a transport
                    // row inside the padded column lands off-centre. Anchor it
                    // to the true centre.
                    Box(
                        modifier = Modifier.align(Alignment.Center),
                        contentAlignment = Alignment.Center,
                    ) {
                        transport(false)
                    }
                }
            }
        }
    }
}

/**
 * The chrome's opacity around the timeline. Scrubbing hides it outright: a
 * ghost of the transport was drawn across the preview it frames.
 */
@Composable
internal fun scrubChromeAlpha(isScrubbing: Boolean): Float {
    val alpha by animateFloatAsState(
        targetValue = if (isScrubbing) 0f else 1f,
        animationSpec = tween(180),
        label = "playerChromeAlpha",
    )
    return alpha
}

/** Room a docked portrait picture needs under it: timeline, transport and actions. */
private val PortraitDockBelow = 236.dp

// The full-size transport. Under a docked portrait picture it is smaller.
private val TransportSkipSize = 54.dp
private val TransportPlaySize = 70.dp
private val TransportGap = 46.dp

/** Width of the full-size transport row, which sits on the true centre of the screen. */
internal val PlayerTransportWidth = TransportSkipSize * 2 + TransportPlaySize + TransportGap * 2

/**
 * Gradients instead of a flat dim: dark enough at the top and bottom edges for
 * the chrome to read over any frame, while a paused picture stays visible in
 * the middle. [boost] adds an even dim while scrubbing.
 */
private fun Modifier.playerScrim(boost: Float): Modifier = drawBehind {
    drawRect(Color.Black.copy(alpha = 0.12f + boost))
    // Deep enough at both edges for the title, times and timeline to hold on
    // a bright, busy frame.
    val top = 132.dp.toPx()
    drawRect(
        brush = Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.7f), 1f to Color.Transparent, endY = top),
        size = Size(size.width, top),
    )
    val bottom = 220.dp.toPx()
    drawRect(
        brush = Brush.verticalGradient(
            0f to Color.Transparent,
            1f to Color.Black.copy(alpha = 0.85f),
            startY = size.height - bottom,
            endY = size.height,
        ),
        topLeft = Offset(0f, size.height - bottom),
        size = Size(size.width, bottom),
    )
}

@Composable
private fun PlayerTopBar(
    eyebrow: String,
    title: String,
    isOrientationLocked: Boolean,
    orientationLockSupported: Boolean,
    pictureInPictureAvailable: Boolean,
    onBack: () -> Unit,
    onEnterPictureInPicture: () -> Unit,
    onToggleOrientationLock: () -> Unit,
    castSlot: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
    // False when the title sits elsewhere (on a docked portrait picture).
    showTitle: Boolean = true,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PlayerIconDisc(
            icon = Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = stringResource(CommonR.string.common_back),
            onClick = onBack,
        )
        if (showTitle) {
            PlayerTitleBlock(
                eyebrow = eyebrow,
                title = title,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
            )
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }
        if (pictureInPictureAvailable) {
            PlayerIconDisc(
                icon = Icons.Rounded.PictureInPictureAlt,
                contentDescription = stringResource(CommonR.string.player_controls_pictureInPicture),
                onClick = onEnterPictureInPicture,
            )
        }
        castSlot(Modifier.playerDiscSurface())
        if (orientationLockSupported) {
            PlayerIconDisc(
                icon = if (isOrientationLocked) Icons.Rounded.ScreenLockRotation else Icons.Rounded.ScreenRotation,
                contentDescription = stringResource(
                    if (isOrientationLocked) {
                        CommonR.string.player_controls_landscapeLocked
                    } else {
                        CommonR.string.player_orientation_rotateFreely
                    },
                ),
                onClick = onToggleOrientationLock,
            )
        }
    }
}

/** Eyebrow ("MOVIE · 1985") over the title. */
@Composable
private fun PlayerTitleBlock(
    eyebrow: String,
    title: String,
    modifier: Modifier = Modifier,
    maxTitleLines: Int = 1,
) {
    Column(modifier = modifier) {
        if (eyebrow.isNotBlank()) {
            Text(
                text = eyebrow.uppercase(),
                style = PlayerType.Eyebrow,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = title,
            style = PlayerType.HudTitle,
            maxLines = maxTitleLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 1.dp),
        )
    }
}

/** Dresses a foreign button (Cast) as a player disc. */
private fun Modifier.playerDiscSurface(): Modifier = this
    .minimumInteractiveComponentSize()
    .size(PlayerChrome.DiscSize)
    .clip(CircleShape)
    .background(PlayerChrome.Disc)
    .border(1.dp, PlayerChrome.DiscStroke, CircleShape)

@Composable
private fun PlayerTransportControls(
    // Under a docked portrait picture rather than over it: smaller discs.
    compact: Boolean,
    isPlaying: Boolean,
    isPaused: Boolean,
    isLoading: Boolean,
    seekEnabled: Boolean,
    playPauseEnabled: Boolean,
    skipBackSeconds: Int,
    skipForwardSeconds: Int,
    onPlayPause: () -> Unit,
    onSkipForward: () -> Unit,
    onSkipBackward: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val showPlay = isPaused || !isPlaying
    // Transport stays left to right in every language: back on the left, as
    // on the seek bar.
    LeftToRight {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(if (compact) 36.dp else TransportGap, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val skipSize = if (compact) 48.dp else TransportSkipSize
            PlayerDisc(
                contentDescription = pluralStringResource(CommonR.plurals.player_controls_skipBack, skipBackSeconds, skipBackSeconds),
                onClick = onSkipBackward,
                size = skipSize,
                enabled = seekEnabled,
            ) {
                SeekIntervalIcon(
                    forward = false,
                    seconds = skipBackSeconds,
                    contentDescription = null,
                    tint = PlayerChrome.Paper,
                    modifier = Modifier.size(31.dp),
                )
            }
            // While loading, the spinner stands where the disc was; the slot still
            // plays or pauses, so a stall never takes the control away.
            PlayerDisc(
                contentDescription = stringResource(if (showPlay) CommonR.string.player_controls_play else CommonR.string.player_controls_pause),
                onClick = onPlayPause,
                size = if (compact) 60.dp else TransportPlaySize,
                enabled = playPauseEnabled,
                // Loading keeps the disc's shape (a dark plate and ring around
                // the spinner): transparent, it all but vanished on a dark frame.
                fill = if (isLoading) Color.Black.copy(alpha = 0.55f) else PlayerChrome.Paper,
                contentColor = PlayerChrome.Ink,
                stroke = if (isLoading) PlayerChrome.Paper.copy(alpha = 0.35f) else Color.Transparent,
                stateDescription = stringResource(CommonR.string.common_loading).takeIf { isLoading },
            ) {
                if (isLoading) {
                    PlayerLoadingSpinner(size = if (compact) 34.dp else 40.dp)
                } else {
                    Icon(
                        imageVector = if (showPlay) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                        contentDescription = null,
                        tint = PlayerChrome.Ink,
                        // The play triangle's visual centre sits left of its box.
                        modifier = Modifier
                            .size(44.dp)
                            .offset(x = if (showPlay) 2.dp else 0.dp),
                    )
                }
            }
            PlayerDisc(
                contentDescription = pluralStringResource(CommonR.plurals.player_controls_skipForward, skipForwardSeconds, skipForwardSeconds),
                onClick = onSkipForward,
                size = skipSize,
                enabled = seekEnabled,
            ) {
                SeekIntervalIcon(
                    forward = true,
                    seconds = skipForwardSeconds,
                    contentDescription = null,
                    tint = PlayerChrome.Paper,
                    modifier = Modifier.size(31.dp),
                )
            }
        }
    }
}

/** How much of each action fits: a pill with label and value, a pill with the label, or icon-over-name tiles. */
private enum class ActionPillDensity { Full, Labels, Icons }

@Composable
private fun PlayerActionPillRow(
    hasTracks: Boolean,
    hasChapters: Boolean,
    hasQualityMenu: Boolean,
    tracksValue: String?,
    chapterValue: String?,
    qualityValue: String?,
    onOpenTracks: () -> Unit,
    onOpenChapters: () -> Unit,
    onOpenQuality: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val density = when {
            maxWidth >= 680.dp -> ActionPillDensity.Full
            maxWidth >= 480.dp -> ActionPillDensity.Labels
            else -> ActionPillDensity.Icons
        }
        if (density == ActionPillDensity.Icons) {
            // Too narrow for labelled pills (a portrait phone): labelled
            // buttons, icon over name, rather than a row of bare icons.
            Row(modifier = Modifier.fillMaxWidth()) {
                PlayerActionTile(
                    Icons.Rounded.Subtitles,
                    stringResource(CommonR.string.player_tracks_title),
                    onOpenTracks,
                    enabled = hasTracks,
                )
                if (hasChapters) {
                    PlayerActionTile(
                        Icons.AutoMirrored.Rounded.FormatListBulleted,
                        stringResource(CommonR.string.options_chapters),
                        onOpenChapters,
                    )
                }
                if (hasQualityMenu) {
                    PlayerActionTile(Icons.Rounded.Tune, stringResource(CommonR.string.options_quality), onOpenQuality)
                }
                PlayerActionTile(
                    Icons.Rounded.MoreHoriz,
                    stringResource(CommonR.string.detail_more),
                    onOpenSettings,
                    description = stringResource(CommonR.string.player_controls_settings),
                )
            }
            return@BoxWithConstraints
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // The left pills share what Quality and ⋯ leave, so a long track or
            // chapter name ellipsizes instead of pushing them off the row.
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PlayerActionPill(
                    icon = Icons.Rounded.Subtitles,
                    label = stringResource(CommonR.string.player_tracks_title),
                    value = tracksValue.takeIf { density == ActionPillDensity.Full },
                    enabled = hasTracks,
                    onClick = onOpenTracks,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (hasChapters) {
                    PlayerActionPill(
                        icon = Icons.AutoMirrored.Rounded.FormatListBulleted,
                        label = stringResource(CommonR.string.options_chapters),
                        value = chapterValue.takeIf { density == ActionPillDensity.Full },
                        onClick = onOpenChapters,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
            if (hasQualityMenu) {
                PlayerActionPill(
                    icon = Icons.Rounded.Tune,
                    label = stringResource(CommonR.string.options_quality),
                    value = qualityValue,
                    onClick = onOpenQuality,
                )
            }
            PlayerIconDisc(
                icon = Icons.Rounded.MoreHoriz,
                contentDescription = stringResource(CommonR.string.player_controls_settings),
                onClick = onOpenSettings,
                size = 36.dp,
                iconSize = 21.dp,
            )
        }
    }
}

/** One labelled action in the narrow (portrait) action row: icon over name. */
@Composable
private fun RowScope.PlayerActionTile(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    description: String = label,
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = description, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .alpha(if (enabled) 1f else 0.32f)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(PlayerChrome.Disc)
                .border(1.dp, PlayerChrome.DiscStroke, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = PlayerChrome.Paper, modifier = Modifier.size(20.dp))
        }
        Text(
            text = label,
            style = PlayerType.PillLabel.copy(fontSize = 12.sp),
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TabletopUtilityRow(
    playbackSpeed: Double,
    playbackSpeedEnabled: Boolean,
    nextEpisode: PlayerViewModel.NextEpisodeInfo?,
    compact: Boolean,
    brightnessFraction: Float,
    onSetPlaybackSpeed: (Double) -> Unit,
    onPlayNextEpisode: () -> Unit,
    onSetBrightness: (Float) -> Unit,
) {
    val context = LocalContext.current

    val brightnessControl: @Composable (Modifier) -> Unit = { modifier ->
        TabletopSliderControl(
            icon = Icons.Rounded.Brightness6,
            contentDescription = stringResource(CommonR.string.player_controls_brightness),
            value = brightnessFraction,
            onValueChange = onSetBrightness,
            modifier = modifier,
        )
    }

    if (compact) {
        brightnessControl(Modifier.fillMaxWidth())
        return
    }

    val audioManager = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    val maxVolume = remember(audioManager) {
        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    }
    var volumeFraction by remember(audioManager, maxVolume) {
        mutableFloatStateOf(
            audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume,
        )
    }
    val contentResolver = context.contentResolver
    DisposableEffect(audioManager, maxVolume, contentResolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                volumeFraction =
                    audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume
            }
        }
        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
        onDispose { contentResolver.unregisterContentObserver(observer) }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TabletopSliderControl(
            icon = Icons.AutoMirrored.Rounded.VolumeUp,
            contentDescription = stringResource(CommonR.string.player_controls_volume),
            value = volumeFraction,
            onValueChange = { fraction ->
                volumeFraction = fraction
                audioManager.setStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    (fraction * maxVolume).toInt().coerceIn(0, maxVolume),
                    0,
                )
            },
            modifier = Modifier.weight(1f),
        )
        brightnessControl(Modifier.weight(1f))
        if (playbackSpeedEnabled) {
            TabletopActionButton(
                icon = Icons.Rounded.Speed,
                label = playbackSpeedLabel(playbackSpeed),
                onClick = { onSetPlaybackSpeed(nextTabletopPlaybackSpeed(playbackSpeed)) },
            )
        }
        nextEpisode?.let { episode ->
            TabletopActionButton(
                icon = Icons.Rounded.SkipNext,
                // A `%d` code read without arguments: resources would print the app's digits (D75), codes stay ASCII (D78).
                label = stringResource(CommonR.string.player_controls_nextEpisode).format(Locale.ROOT, episode.seasonNumber, episode.episodeNumber),
                onClick = onPlayNextEpisode,
            )
        }
    }
}

@Composable
private fun TabletopSliderControl(
    icon: ImageVector,
    contentDescription: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(PlayerChrome.Raised)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = PlayerChrome.Paper.copy(alpha = 0.82f),
            modifier = Modifier.size(20.dp),
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            colors = SliderDefaults.colors(
                thumbColor = PlayerChrome.Paper,
                activeTrackColor = PlayerChrome.Paper,
                inactiveTrackColor = Color.White.copy(alpha = 0.22f),
            ),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TabletopActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .height(48.dp)
            .widthIn(min = 112.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(PlayerChrome.Raised)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = PlayerChrome.Paper,
            modifier = Modifier.size(19.dp),
        )
        Text(text = label, style = PlayerType.PillLabel, maxLines = 1)
    }
}

internal fun nextTabletopPlaybackSpeed(current: Double): Double =
    listOf(1.0, 1.25, 1.5, 2.0).firstOrNull { it > current + 0.001 } ?: 1.0

@Composable
internal fun playbackSpeedLabel(speed: Double): String =
    stringResource(CommonR.string.player_controls_speedButton, formatPlaybackSpeed(speed))
