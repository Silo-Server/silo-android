package org.siloserver.silo.android.ui.screens.player

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.ScreenLockRotation
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.automirrored.filled.SpeakerNotes
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.android.ui.layout.useCompactPlayerToolbar

/**
 * Transport controls overlay for the video player. Top-bar icon layout
 * mirrors iOS phone's `MobilePlayerControls` (lock | chapters | tracks |
 * settings) — see `iosApp/Screens/Player/iOS/MobilePlayerControls.swift:73`.
 *
 * Fullscreen uses the existing three-row HUD; tabletop groups the same shared
 * controls into the lower pane, keeps brightness available at every size, and
 * adds volume, speed, and next-episode shortcuts when the pane is large enough.
 *
 * Core rows:
 * - Top: Back (chevron) · title · orientation lock toggle · chapters (when
 *   present) · tracks (audio + subs) · quality (when multiple versions) ·
 *   settings (gear)
 * - Center: Skip back · play/pause · skip forward
 * - Bottom: Seek bar with timestamps
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
    // Quality lives on the HUD (chapters + tracks + quality product decision);
    // hidden when the item has a single file version.
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
    nextEpisode: PlayerViewModel.NextEpisodeInfo? = null,
    brightnessFraction: Float = 0.5f,
    // Watch Together guest gate: when false the scrubber + skip buttons are
    // inert and dimmed (seek is host-only, so disabled for all guests).
    // Defaults true for solo playback.
    seekEnabled: Boolean = true,
    // Separate from [seekEnabled]: a guest under guest_play_pause keeps the
    // play/pause affordance but loses seek. Defaults true for solo playback.
    playPauseEnabled: Boolean = true,
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
    // composable stays test-friendly and decoupled from the Cast SDK.
    castSlot: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // iOS dims the entire screen with a flat `Color.black.opacity(0.4)`
    // backdrop (MobilePlayerControls) — no top/bottom gradients. The VStack
    // sits inside the dim with iOS's default 16pt edge padding.
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f)),
    ) {
        val contentModifier = if (tabletopMode) {
            Modifier
                .fillMaxSize()
                // The controls pane begins halfway down the window, so a status
                // bar inset here would create a fake gap below the hinge. Only
                // reserve the real bottom navigation/gesture inset.
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 24.dp, vertical = 12.dp)
        } else {
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp)
        }

        val toolbar: @Composable () -> Unit = {
            PlayerToolbar(
                title = title,
                subtitle = subtitle,
                isOrientationLocked = isOrientationLocked,
                orientationLockSupported = orientationLockSupported,
                hasChapters = hasChapters,
                hasTracks = hasTracks,
                hasMultipleVersions = hasMultipleVersions,
                onBack = onBack,
                onToggleOrientationLock = onToggleOrientationLock,
                onOpenChapters = onOpenChapters,
                onOpenTracks = onOpenTracks,
                onOpenQuality = onOpenQuality,
                onOpenSettings = onOpenSettings,
                castSlot = castSlot,
            )
        }
        val transportControls: @Composable () -> Unit = {
            PlayerTransportControls(
                isPlaying = isPlaying,
                isPaused = isPaused,
                seekEnabled = seekEnabled,
                playPauseEnabled = playPauseEnabled,
                onPlayPause = onPlayPause,
                onSkipForward = onSkipForward,
                onSkipBackward = onSkipBackward,
            )
        }
        val progressBar: @Composable () -> Unit = {
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
            )
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
                    toolbar()
                    transportControls()
                    progressBar()
                    TabletopUtilityRow(
                        playbackSpeed = playbackSpeed,
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
                toolbar()
                Spacer(modifier = Modifier.weight(1f))
                transportControls()
                Spacer(modifier = Modifier.weight(1f))
                progressBar()
            }
        }
    }
}

@Composable
private fun PlayerToolbar(
    title: String,
    subtitle: String,
    isOrientationLocked: Boolean,
    orientationLockSupported: Boolean,
    hasChapters: Boolean,
    hasTracks: Boolean,
    hasMultipleVersions: Boolean,
    onBack: () -> Unit,
    onToggleOrientationLock: () -> Unit,
    onOpenChapters: () -> Unit,
    onOpenTracks: () -> Unit,
    onOpenQuality: () -> Unit,
    onOpenSettings: () -> Unit,
    castSlot: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val trailingActionCount = 3 +
            (if (orientationLockSupported) 1 else 0) +
            (if (hasChapters) 1 else 0) +
            (if (hasMultipleVersions) 1 else 0)
        val compact = useCompactPlayerToolbar(
            availableWidthDp = maxWidth.value,
            trailingActionCount = trailingActionCount,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ControlButton(
                icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = "Back",
                onClick = onBack,
            )
            PlayerToolbarTitle(
                title = title,
                subtitle = subtitle,
                modifier = Modifier.weight(1f),
            )
            if (compact) {
                castSlot()
                PlayerToolbarOverflow(
                    isOrientationLocked = isOrientationLocked,
                    orientationLockSupported = orientationLockSupported,
                    hasChapters = hasChapters,
                    hasTracks = hasTracks,
                    hasMultipleVersions = hasMultipleVersions,
                    onToggleOrientationLock = onToggleOrientationLock,
                    onOpenChapters = onOpenChapters,
                    onOpenTracks = onOpenTracks,
                    onOpenQuality = onOpenQuality,
                    onOpenSettings = onOpenSettings,
                )
            } else {
                PlayerToolbarActions(
                    isOrientationLocked = isOrientationLocked,
                    orientationLockSupported = orientationLockSupported,
                    hasChapters = hasChapters,
                    hasTracks = hasTracks,
                    hasMultipleVersions = hasMultipleVersions,
                    onToggleOrientationLock = onToggleOrientationLock,
                    onOpenChapters = onOpenChapters,
                    onOpenTracks = onOpenTracks,
                    onOpenQuality = onOpenQuality,
                    onOpenSettings = onOpenSettings,
                    castSlot = castSlot,
                )
            }
        }
    }
}

@Composable
private fun PlayerTransportControls(
    isPlaying: Boolean,
    isPaused: Boolean,
    seekEnabled: Boolean,
    playPauseEnabled: Boolean,
    onPlayPause: () -> Unit,
    onSkipForward: () -> Unit,
    onSkipBackward: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(48.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onSkipBackward,
            enabled = seekEnabled,
            modifier = Modifier.size(52.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Replay10,
                contentDescription = "Skip back 10 seconds",
                tint = if (seekEnabled) Color.White else Color.White.copy(alpha = 0.3f),
                modifier = Modifier.size(32.dp),
            )
        }
        IconButton(
            onClick = onPlayPause,
            enabled = playPauseEnabled,
            modifier = Modifier
                .size(64.dp)
                .background(Color.White.copy(alpha = 0.14f), CircleShape),
        ) {
            Icon(
                imageVector = if (isPaused || !isPlaying) {
                    Icons.Default.PlayArrow
                } else {
                    Icons.Default.Pause
                },
                contentDescription = if (isPaused || !isPlaying) "Play" else "Pause",
                tint = if (playPauseEnabled) Color.White else Color.White.copy(alpha = 0.3f),
                modifier = Modifier.size(38.dp),
            )
        }
        IconButton(
            onClick = onSkipForward,
            enabled = seekEnabled,
            modifier = Modifier.size(52.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Forward10,
                contentDescription = "Skip forward 10 seconds",
                tint = if (seekEnabled) Color.White else Color.White.copy(alpha = 0.3f),
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

@Composable
private fun PlayerToolbarTitle(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            fontSize = 15.sp,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle.isNotBlank()) {
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.64f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TabletopUtilityRow(
    playbackSpeed: Double,
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
            icon = Icons.Default.Brightness6,
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
            icon = Icons.AutoMirrored.Filled.VolumeUp,
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
        TabletopActionButton(
            icon = Icons.Default.Speed,
            label = playbackSpeedLabel(playbackSpeed),
            onClick = { onSetPlaybackSpeed(nextTabletopPlaybackSpeed(playbackSpeed)) },
        )
        nextEpisode?.let { episode ->
            TabletopActionButton(
                icon = Icons.Default.SkipNext,
                label = "Next S${episode.seasonNumber}·E${episode.episodeNumber}",
                onClick = onPlayNextEpisode,
            )
        }
    }
}

@Composable
private fun TabletopSliderControl(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White.copy(alpha = 0.82f),
            modifier = Modifier.size(20.dp),
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TabletopActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .height(48.dp)
            .widthIn(min = 112.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.10f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(19.dp),
        )
        Text(
            text = label,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

internal fun nextTabletopPlaybackSpeed(current: Double): Double =
    listOf(1.0, 1.25, 1.5, 2.0).firstOrNull { it > current + 0.001 } ?: 1.0

internal fun playbackSpeedLabel(speed: Double): String {
    return "${formatPlaybackSpeed(speed)}× Speed"
}

@Composable
private fun PlayerToolbarActions(
    isOrientationLocked: Boolean,
    orientationLockSupported: Boolean,
    hasChapters: Boolean,
    hasTracks: Boolean,
    hasMultipleVersions: Boolean,
    onToggleOrientationLock: () -> Unit,
    onOpenChapters: () -> Unit,
    onOpenTracks: () -> Unit,
    onOpenQuality: () -> Unit,
    onOpenSettings: () -> Unit,
    castSlot: @Composable () -> Unit,
) {
    if (orientationLockSupported) {
        ControlButton(
            icon = if (isOrientationLocked) {
                Icons.Default.ScreenLockRotation
            } else {
                Icons.Default.ScreenRotation
            },
            contentDescription = if (isOrientationLocked) "Landscape Locked" else "Rotate Freely",
            onClick = onToggleOrientationLock,
        )
    }
    if (hasChapters) {
        ControlButton(
            icon = Icons.AutoMirrored.Filled.List,
            contentDescription = "Chapters",
            onClick = onOpenChapters,
        )
    }
    ControlButton(
        icon = Icons.AutoMirrored.Filled.SpeakerNotes,
        contentDescription = "Audio and subtitles",
        onClick = onOpenTracks,
        enabled = hasTracks,
    )
    if (hasMultipleVersions) {
        ControlButton(
            icon = Icons.Default.HighQuality,
            contentDescription = "Quality",
            onClick = onOpenQuality,
        )
    }
    castSlot()
    ControlButton(
        icon = Icons.Default.Settings,
        contentDescription = "Playback settings",
        onClick = onOpenSettings,
    )
}

@Composable
private fun PlayerToolbarOverflow(
    isOrientationLocked: Boolean,
    orientationLockSupported: Boolean,
    hasChapters: Boolean,
    hasTracks: Boolean,
    hasMultipleVersions: Boolean,
    onToggleOrientationLock: () -> Unit,
    onOpenChapters: () -> Unit,
    onOpenTracks: () -> Unit,
    onOpenQuality: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        ControlButton(
            icon = Icons.Default.MoreVert,
            contentDescription = "More playback controls",
            onClick = { expanded = true },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            if (orientationLockSupported) {
                DropdownMenuItem(
                    text = {
                        Text(if (isOrientationLocked) "Unlock orientation" else "Lock orientation")
                    },
                    onClick = {
                        expanded = false
                        onToggleOrientationLock()
                    },
                )
            }
            if (hasChapters) {
                DropdownMenuItem(
                    text = { Text("Chapters") },
                    onClick = {
                        expanded = false
                        onOpenChapters()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("Audio and subtitles") },
                enabled = hasTracks,
                onClick = {
                    expanded = false
                    onOpenTracks()
                },
            )
            if (hasMultipleVersions) {
                DropdownMenuItem(
                    text = { Text("Quality") },
                    onClick = {
                        expanded = false
                        onOpenQuality()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("Playback settings") },
                onClick = {
                    expanded = false
                    onOpenSettings()
                },
            )
        }
    }
}

/**
 * Top-bar control button matching iOS `controlButton`: a `size 20` icon inside
 * a 48x48 tap target (Android minimum touch target), white, dimmed to 0.3 when
 * disabled.
 */
@Composable
private fun ControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(48.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) Color.White else Color.White.copy(alpha = 0.3f),
            modifier = Modifier.size(22.dp),
        )
    }
}
