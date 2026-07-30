package org.siloserver.silo.android.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.ScreenLockRotation
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.automirrored.filled.SpeakerNotes
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.android.ui.layout.useCompactPlayerToolbar

/**
 * Transport controls overlay for the video player. Top-bar icon layout
 * mirrors iOS phone's `MobilePlayerControls` (lock | chapters | tracks |
 * settings) — see `iosApp/Screens/Player/iOS/MobilePlayerControls.swift:73`.
 *
 * Three-row layout:
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
    isOrientationLocked: Boolean,
    orientationLockSupported: Boolean = true,
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Keep every HUD control clear of the display cutout and any
                // transient system bars (QA: portrait cutouts cropped the
                // top-right buttons); the 16dp is extra padding on top of the
                // safe insets, mirroring iOS's safe-area + 16pt edge padding.
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp),
        ) {
            // Top bar — iOS HStack(spacing: 16): back · spacer · title · spacer ·
            // lock · chapters · tracks · settings. Title is centered between the
            // two spacers, single-line, `.subheadline`, no subtitle.
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val trailingActionCount = 4 +
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
                        modifier = Modifier.weight(1f),
                    )

                    if (compact) {
                        // Keep Cast directly reachable; the SDK route button
                        // cannot be represented as a regular menu callback.
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

            Spacer(modifier = Modifier.weight(1f))

            // Center controls — iOS HStack(spacing: 48): skip back (32) ·
            // play/pause (48, no background) · skip forward (32). While
            // buffering, iOS swaps the play glyph for a spinner.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(48.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onSkipBackward,
                    enabled = seekEnabled,
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
                ) {
                    Icon(
                        imageVector = if (isPaused || !isPlaying) {
                            Icons.Default.PlayArrow
                        } else {
                            Icons.Default.Pause
                        },
                        contentDescription = if (isPaused || !isPlaying) "Play" else "Pause",
                        tint = if (playPauseEnabled) Color.White else Color.White.copy(alpha = 0.3f),
                        modifier = Modifier.size(48.dp),
                    )
                }

                IconButton(
                    onClick = onSkipForward,
                    enabled = seekEnabled,
                ) {
                    Icon(
                        imageVector = Icons.Default.Forward10,
                        contentDescription = "Skip forward 10 seconds",
                        tint = if (seekEnabled) Color.White else Color.White.copy(alpha = 0.3f),
                        modifier = Modifier.size(32.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Bottom bar — iOS VStack(spacing: 8): progress slider then a time
            // row. No gradient (the flat dim handles contrast).
            PlayerProgressBar(
                position = position,
                duration = duration,
                bufferedPosition = bufferedPosition,
                onSeek = onSeek,
                enabled = seekEnabled,
                chapters = chapters,
                intro = intro,
            )
        }
    }
}

@Composable
private fun PlayerToolbarTitle(
    title: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title,
            fontSize = 15.sp,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
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
    ControlButton(
        icon = if (isOrientationLocked && orientationLockSupported) {
            Icons.Default.ScreenLockRotation
        } else {
            Icons.Default.ScreenRotation
        },
        contentDescription = when {
            !orientationLockSupported -> "Orientation follows device on large screens"
            isOrientationLocked -> "Landscape Locked"
            else -> "Rotate Freely"
        },
        onClick = onToggleOrientationLock,
        enabled = orientationLockSupported,
    )
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
            DropdownMenuItem(
                text = {
                    Text(
                        when {
                            !orientationLockSupported -> "Orientation follows device"
                            isOrientationLocked -> "Unlock orientation"
                            else -> "Lock orientation"
                        },
                    )
                },
                enabled = orientationLockSupported,
                onClick = {
                    expanded = false
                    onToggleOrientationLock()
                },
            )
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
