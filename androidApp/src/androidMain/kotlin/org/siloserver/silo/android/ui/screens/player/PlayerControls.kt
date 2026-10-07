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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.siloserver.silo.android.ui.components.SeekIntervalIcon

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
    position: Double,
    duration: Double,
    bufferedPosition: Double,
    hasChapters: Boolean,
    hasTracks: Boolean,
    // Quality lives on the HUD; hidden when the item has a single version.
    hasMultipleVersions: Boolean,
    chapters: List<org.siloserver.silo.model.catalog.VersionChapter> = emptyList(),
    intro: org.siloserver.silo.model.catalog.TimeRange? = null,
    credits: org.siloserver.silo.model.catalog.TimeRange? = null,
    recap: org.siloserver.silo.model.catalog.TimeRange? = null,
    preview: org.siloserver.silo.model.catalog.TimeRange? = null,
    isOrientationLocked: Boolean,
    orientationLockSupported: Boolean = true,
    tabletopMode: Boolean = false,
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
    // Google Cast (Chromecast) button — sits in the top bar alongside the other
    // controls. Provided by PlayerScreen; empty by default so this stateless
    // composable stays test-friendly and decoupled from the Cast SDK. The
    // modifier dresses it as a player disc.
    castSlot: @Composable (Modifier) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var isScrubbing by remember { mutableStateOf(false) }
    val chromeAlpha by animateFloatAsState(
        targetValue = if (isScrubbing) 0.12f else 1f,
        animationSpec = tween(180),
        label = "playerChromeAlpha",
    )
    val scrimBoost by animateFloatAsState(
        targetValue = if (isScrubbing) 0.18f else 0f,
        animationSpec = tween(180),
        label = "playerScrimBoost",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
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
            val density = LocalDensity.current
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

        val topBar: @Composable () -> Unit = {
            PlayerTopBar(
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
        val transport: @Composable () -> Unit = {
            PlayerTransportControls(
                isPlaying = isPlaying,
                isPaused = isPaused,
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
        val bottom: @Composable () -> Unit = {
            Column(modifier = Modifier.padding(horizontal = 4.dp)) {
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
                    onScrubbingChange = { isScrubbing = it },
                )
                PlayerActionPillRow(
                    hasTracks = hasTracks,
                    hasChapters = hasChapters,
                    hasMultipleVersions = hasMultipleVersions,
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
                    topBar()
                    transport()
                    bottom()
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
            Column(modifier = contentModifier) {
                topBar()
                Spacer(modifier = Modifier.weight(1f))
                bottom()
            }
            // The insets are asymmetric in landscape, so a transport row inside
            // the padded column lands off-centre. Anchor it to the true centre.
            Box(
                modifier = Modifier.align(Alignment.Center),
                contentAlignment = Alignment.Center,
            ) {
                transport()
            }
        }
    }
}

/**
 * Gradients instead of a flat dim: dark enough at the top and bottom edges for
 * the chrome to read over any frame, while a paused picture stays visible in
 * the middle. [boost] adds an even dim while scrubbing.
 */
private fun Modifier.playerScrim(boost: Float): Modifier = drawBehind {
    drawRect(Color.Black.copy(alpha = 0.12f + boost))
    val top = 116.dp.toPx()
    drawRect(
        brush = Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.6f), 1f to Color.Transparent, endY = top),
        size = Size(size.width, top),
    )
    val bottom = 200.dp.toPx()
    drawRect(
        brush = Brush.verticalGradient(
            0f to Color.Transparent,
            1f to Color.Black.copy(alpha = 0.78f),
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
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PlayerIconDisc(
            icon = Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = "Back",
            onClick = onBack,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
        ) {
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
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
        if (pictureInPictureAvailable) {
            PlayerIconDisc(
                icon = Icons.Rounded.PictureInPictureAlt,
                contentDescription = "Picture in picture",
                onClick = onEnterPictureInPicture,
            )
        }
        castSlot(Modifier.playerDiscSurface())
        if (orientationLockSupported) {
            PlayerIconDisc(
                icon = if (isOrientationLocked) Icons.Rounded.ScreenLockRotation else Icons.Rounded.ScreenRotation,
                contentDescription = if (isOrientationLocked) "Landscape locked" else "Rotate freely",
                onClick = onToggleOrientationLock,
            )
        }
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
    isPlaying: Boolean,
    isPaused: Boolean,
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
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(46.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerDisc(
            contentDescription = "Skip back $skipBackSeconds seconds",
            onClick = onSkipBackward,
            size = 54.dp,
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
        PlayerDisc(
            contentDescription = if (showPlay) "Play" else "Pause",
            onClick = onPlayPause,
            size = 70.dp,
            enabled = playPauseEnabled,
            fill = PlayerChrome.Paper,
            contentColor = PlayerChrome.Ink,
            stroke = Color.Transparent,
        ) {
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
        PlayerDisc(
            contentDescription = "Skip forward $skipForwardSeconds seconds",
            onClick = onSkipForward,
            size = 54.dp,
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

/** How much of each action pill fits: label and value, label, or icon only. */
private enum class ActionPillDensity { Full, Labels, Icons }

@Composable
private fun PlayerActionPillRow(
    hasTracks: Boolean,
    hasChapters: Boolean,
    hasMultipleVersions: Boolean,
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PlayerActionPill(
                icon = Icons.Rounded.Subtitles,
                label = "Audio & Subtitles",
                value = tracksValue.takeIf { density == ActionPillDensity.Full },
                compact = density == ActionPillDensity.Icons,
                enabled = hasTracks,
                onClick = onOpenTracks,
            )
            if (hasChapters) {
                PlayerActionPill(
                    icon = Icons.AutoMirrored.Rounded.FormatListBulleted,
                    label = "Chapters",
                    value = chapterValue.takeIf { density == ActionPillDensity.Full },
                    compact = density == ActionPillDensity.Icons,
                    onClick = onOpenChapters,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            if (hasMultipleVersions) {
                PlayerActionPill(
                    icon = Icons.Rounded.Tune,
                    label = "Quality",
                    value = qualityValue.takeIf { density != ActionPillDensity.Icons },
                    compact = density == ActionPillDensity.Icons,
                    onClick = onOpenQuality,
                )
            }
            PlayerIconDisc(
                icon = Icons.Rounded.MoreHoriz,
                contentDescription = "Playback settings",
                onClick = onOpenSettings,
                size = 36.dp,
                iconSize = 21.dp,
            )
        }
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
            contentDescription = "Player brightness",
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
            contentDescription = "Media volume",
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
                label = "Next S${episode.seasonNumber}·E${episode.episodeNumber}",
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

internal fun playbackSpeedLabel(speed: Double): String {
    return "${formatPlaybackSpeed(speed)}× Speed"
}
