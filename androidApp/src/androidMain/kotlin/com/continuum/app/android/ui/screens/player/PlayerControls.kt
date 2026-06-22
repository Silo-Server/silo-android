package com.continuum.app.android.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Transport controls overlay for the video player. Top-bar icon layout
 * mirrors iOS phone's `MobilePlayerControls` (lock | chapters | tracks |
 * settings) — see `iosApp/Screens/Player/iOS/MobilePlayerControls.swift:73`.
 *
 * Three-row layout:
 * - Top: Back (chevron) · title · orientation lock toggle · chapters (when
 *   present) · tracks (audio + subs) · settings (gear)
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
    isOrientationLocked: Boolean,
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
    onOpenSettings: () -> Unit,
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
                .padding(16.dp),
        ) {
            // Top bar — iOS HStack(spacing: 16): back · spacer · title · spacer ·
            // lock · chapters · tracks · settings. Title is centered between the
            // two spacers, single-line, `.subheadline`, no subtitle.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ControlButton(
                    icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = "Back",
                    onClick = onBack,
                )

                Spacer(modifier = Modifier.weight(1f))

                Text(
                    text = title,
                    fontSize = 15.sp,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(modifier = Modifier.weight(1f))

                // Orientation lock toggle — mirrors iOS `lock.fill` / `lock.open`.
                ControlButton(
                    icon = if (isOrientationLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                    contentDescription = if (isOrientationLocked) "Landscape Locked" else "Rotate Freely",
                    onClick = onToggleOrientationLock,
                )

                // Chapters — only shown when the file has chapters (iOS parity).
                if (hasChapters) {
                    ControlButton(
                        icon = Icons.AutoMirrored.Filled.List,
                        contentDescription = "Chapters",
                        onClick = onOpenChapters,
                    )
                }

                // Tracks (audio + subtitles) — iOS uses `captions.bubble`. Dimmed
                // to 0.3 opacity when there's nothing to pick.
                ControlButton(
                    icon = Icons.Default.ClosedCaption,
                    contentDescription = "Audio and subtitles",
                    onClick = onOpenTracks,
                    enabled = hasTracks,
                )

                // Settings — iOS `gearshape`, opens the playback settings sheet.
                ControlButton(
                    icon = Icons.Default.Settings,
                    contentDescription = "Playback settings",
                    onClick = onOpenSettings,
                )
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
            )
        }
    }
}

/**
 * Top-bar control button matching iOS `controlButton`: a `size 20` icon inside
 * a 44x44 tap target, white, dimmed to 0.3 when disabled.
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
        modifier = Modifier.size(44.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) Color.White else Color.White.copy(alpha = 0.3f),
            modifier = Modifier.size(20.dp),
        )
    }
}
