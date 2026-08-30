package org.siloserver.silo.android.ui.screens.cast

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.SettingsRemote
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.outlined.TvOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.koin.compose.koinInject
import org.siloserver.silo.android.R
import org.siloserver.silo.android.cast.RemoteControlBatteryOptimization
import org.siloserver.silo.android.cast.SiloCastController
import org.siloserver.silo.cast.SiloCastPlaybackState
import org.siloserver.silo.common.ui.components.ThumbhashImage

// Fixed dark palette: the remote renders over OLED black + blurred artwork
// regardless of the app theme, matching silo-apple's forced-dark remote.
private val RemoteOnSurface = Color(0xFFF2F2F5)
private val RemoteSecondary = Color(0xFFB9BAC3)
private val RemoteChipFill = Color(0x33FFFFFF)
private val RemoteSurfaceElevated = Color(0xFF23252E)
private val RemoteError = Color(0xFFB3261E)

/**
 * Native "now-playing" remote for controlling Silo playback on a TV.
 * Mirrors silo-apple's `SiloControlRemoteView`: blurred-artwork backdrop,
 * poster, scrubber with optimistic clock, transport, volume, and
 * capability-gated secondary menus — with distinct connecting /
 * reconnecting / idle-connected / playing / error states.
 */
@Composable
fun SiloCastRemoteScreen(
    onBack: () -> Unit,
    controller: SiloCastController = koinInject(),
) {
    val state by controller.state.collectAsState()
    val playback = state.playbackState
    val artwork = rememberSiloCastArtwork(playback?.contentId)
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var showTargetPicker by remember { mutableStateOf(false) }
    var showBatteryPrompt by remember { mutableStateOf(false) }
    var batteryExempt by remember {
        mutableStateOf(RemoteControlBatteryOptimization.isExempt(context))
    }

    DisposableEffect(controller) {
        controller.setRemoteScreenVisible(true)
        onDispose { controller.setRemoteScreenVisible(false) }
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryExempt = RemoteControlBatteryOptimization.isExempt(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state.hasActiveSession, batteryExempt) {
        if (state.hasActiveSession &&
            !batteryExempt &&
            RemoteControlBatteryOptimization.shouldShowPrompt(context)
        ) {
            showBatteryPrompt = true
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        RemoteArtworkBackground(artwork.backdropUrl ?: artwork.posterUrl)

        Column(modifier = Modifier.fillMaxSize()) {
            RemoteTopBar(
                playback = playback,
                onMinimize = onBack,
                onChooseTv = { showTargetPicker = true },
                onStopPlayback = { controller.stopPlayback() },
                onSetVideoGravity = controller::setVideoGravity,
                onSetHdrEnabled = controller::setHdrEnabled,
                onDisconnect = {
                    controller.disconnect()
                    onBack()
                },
                showBatterySettings = !batteryExempt,
                onBatterySettings = {
                    RemoteControlBatteryOptimization.openSettings(context)
                },
            )

            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                when {
                    state.isReconnecting -> RemoteStatus(title = "Reconnecting…", showSpinner = true)
                    playback == null -> RemoteConnecting(
                        targetName = state.connectedTarget?.name,
                        // A fully torn-down session (TV disconnected, reconnect
                        // exhausted) must not spin forever — offer a way out.
                        error = state.error
                            ?: "Not connected to a TV.".takeIf { state.connectedTarget == null && !state.isConnecting },
                        onChooseTv = { showTargetPicker = true },
                    )
                    playback.contentId == null && state.isLaunching -> RemoteStatus(
                        title = "Starting playback on ${state.connectedTarget?.name ?: "Silo TV"}…",
                        showSpinner = true,
                    )
                    playback.contentId == null -> RemoteIdleConnected(
                        targetName = state.connectedTarget?.name,
                    )
                    else -> RemoteNowPlaying(
                        playback = playback,
                        targetName = state.connectedTarget?.name,
                        posterUrl = artwork.posterUrl ?: artwork.backdropUrl,
                        posterThumbhash = artwork.posterThumbhash ?: artwork.backdropThumbhash,
                        error = state.error,
                        controller = controller,
                    )
                }
            }
        }
    }

    if (showTargetPicker) {
        SiloCastTargetPickerSheet(
            onDismiss = { showTargetPicker = false },
            controller = controller,
        )
    }

    if (showBatteryPrompt) {
        AlertDialog(
            onDismissRequest = {
                RemoteControlBatteryOptimization.markPromptShown(context)
                showBatteryPrompt = false
            },
            title = { Text(stringResource(R.string.remote_battery_title)) },
            text = { Text(stringResource(R.string.remote_battery_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        RemoteControlBatteryOptimization.markPromptShown(context)
                        showBatteryPrompt = false
                        RemoteControlBatteryOptimization.openSettings(context)
                    },
                ) {
                    Text(stringResource(R.string.remote_battery_settings))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        RemoteControlBatteryOptimization.markPromptShown(context)
                        showBatteryPrompt = false
                    },
                ) {
                    Text(stringResource(R.string.remote_battery_not_now))
                }
            },
        )
    }
}

/** Full-bleed blurred-artwork backdrop, falling back to flat OLED black. */
@Composable
private fun RemoteArtworkBackground(urlString: String?) {
    if (!urlString.isNullOrBlank()) {
        ThumbhashImage(
            url = urlString,
            thumbhash = null,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            decodeSizePx = 480,
            modifier = Modifier
                .fillMaxSize()
                .blur(40.dp),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.62f)),
        )
    }
}

@Composable
private fun RemoteTopBar(
    playback: SiloCastPlaybackState?,
    onMinimize: () -> Unit,
    onChooseTv: () -> Unit,
    onStopPlayback: () -> Unit,
    onSetVideoGravity: (String) -> Unit,
    onSetHdrEnabled: (Boolean) -> Unit,
    onDisconnect: () -> Unit,
    showBatterySettings: Boolean,
    onBatterySettings: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var aspectMenuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onMinimize) {
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = "Minimize",
                tint = RemoteOnSurface,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    Icons.Filled.MoreHoriz,
                    contentDescription = "More options",
                    tint = RemoteOnSurface,
                )
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text("Choose a Different TV") },
                    leadingIcon = { Icon(Icons.Outlined.Tv, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onChooseTv()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Stop Playback") },
                    leadingIcon = { Icon(Icons.Filled.Stop, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onStopPlayback()
                    },
                )
                if (playback?.supportsVideoGravity == true) {
                    DropdownMenuItem(
                        text = { Text("Aspect Ratio") },
                        leadingIcon = {
                            Icon(Icons.Outlined.AspectRatio, contentDescription = null)
                        },
                        trailingIcon = {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                        },
                        onClick = {
                            menuExpanded = false
                            aspectMenuExpanded = true
                        },
                    )
                }
                if (playback?.supportsHDRToggle == true) {
                    DropdownMenuItem(
                        text = { Text("HDR") },
                        leadingIcon = {
                            Icon(Icons.Outlined.AspectRatio, contentDescription = null)
                        },
                        trailingIcon = {
                            if (playback.hdrEnabled) {
                                Icon(Icons.Filled.Check, contentDescription = "Enabled")
                            }
                        },
                        onClick = {
                            menuExpanded = false
                            onSetHdrEnabled(!playback.hdrEnabled)
                        },
                    )
                }
                if (showBatterySettings) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.remote_battery_settings)) },
                        leadingIcon = {
                            Icon(Icons.Outlined.SettingsRemote, contentDescription = null)
                        },
                        onClick = {
                            menuExpanded = false
                            onBatterySettings()
                        },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Disconnect", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.TvOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onDisconnect()
                    },
                )
            }
            DropdownMenu(
                expanded = aspectMenuExpanded && playback?.supportsVideoGravity == true,
                onDismissRequest = { aspectMenuExpanded = false },
            ) {
                Text(
                    "Aspect Ratio",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                listOf("fit" to "Fit", "fill" to "Fill", "stretch" to "Stretch")
                    .forEach { (id, label) ->
                        val selected = playback?.videoGravity == id ||
                            (id == "fill" && playback?.videoGravity in listOf("zoom", "crop"))
                        DropdownMenuItem(
                            text = { Text(label) },
                            leadingIcon = {
                                if (selected) {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = "Selected",
                                        modifier = Modifier.size(18.dp),
                                    )
                                } else {
                                    Spacer(modifier = Modifier.size(18.dp))
                                }
                            },
                            onClick = {
                                aspectMenuExpanded = false
                                onSetVideoGravity(id)
                            },
                        )
                    }
            }
        }
    }
}

@Composable
private fun RemoteStatus(title: String, showSpinner: Boolean) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.padding(32.dp),
    ) {
        if (showSpinner) CircularProgressIndicator(color = RemoteOnSurface)
        Text(title, style = MaterialTheme.typography.titleMedium, color = RemoteSecondary)
    }
}

/** Connected with nothing playing — mirrors Apple's idle remote. */
@Composable
private fun RemoteIdleConnected(targetName: String?) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.padding(32.dp),
    ) {
        Icon(
            Icons.Outlined.SettingsRemote,
            contentDescription = null,
            tint = RemoteOnSurface,
            modifier = Modifier.size(48.dp),
        )
        Text(
            "Connected to ${targetName ?: "Silo TV"}",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            color = RemoteOnSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            "Pick something from your library to start playing.",
            style = MaterialTheme.typography.bodyMedium,
            color = RemoteSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun RemoteConnecting(
    targetName: String?,
    error: String?,
    onChooseTv: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.padding(24.dp),
    ) {
        Box(
            modifier = Modifier
                .size(width = 150.dp, height = 216.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(RemoteSurfaceElevated),
        )
        if (!error.isNullOrEmpty()) {
            Text(
                error,
                style = MaterialTheme.typography.bodyMedium,
                color = RemoteOnSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(RemoteError.copy(alpha = 0.9f))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
            OutlinedButton(onClick = onChooseTv) {
                Icon(Icons.Outlined.Tv, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Choose a TV")
            }
        } else {
            CircularProgressIndicator(color = RemoteOnSurface)
            Text(
                "Connecting to ${targetName ?: "Silo TV"}…",
                style = MaterialTheme.typography.titleMedium,
                color = RemoteSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun RemoteNowPlaying(
    playback: SiloCastPlaybackState,
    targetName: String?,
    posterUrl: String?,
    posterThumbhash: String?,
    error: String?,
    controller: SiloCastController,
) {
    // 4 Hz tick drives the interpolated playhead between the TV's state
    // pushes (the clock itself extrapolates; this just recomposes the label).
    var clockTick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(playback.contentId) {
        while (isActive) {
            clockTick += 1
            delay(250)
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
    ) {
        // Portrait phones use Apple's flexible artwork region. Very short
        // windows and enlarged text retain a vertical-scroll fallback instead
        // of clipping transport or accessibility-sized labels.
        val useScrollableLayout = maxHeight < 520.dp || LocalDensity.current.fontScale > 1.2f
        val contentModifier = if (useScrollableLayout) {
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        } else {
            Modifier.fillMaxSize()
        }

        Column(
            modifier = contentModifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = if (useScrollableLayout) {
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp)
                } else {
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(vertical = 12.dp)
                },
            ) {
                RemotePoster(posterUrl = posterUrl, posterThumbhash = posterThumbhash)
            }

            Text(
                playback.title.ifEmpty { "Loading" },
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = RemoteOnSurface,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            playback.subtitle?.takeIf { it.isNotEmpty() }?.let {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = RemoteSecondary,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (!targetName.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(RemoteChipFill)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Icon(
                        Icons.Outlined.SettingsRemote,
                        contentDescription = null,
                        tint = RemoteSecondary,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        "Playing on $targetName",
                        style = MaterialTheme.typography.labelMedium,
                        color = RemoteSecondary,
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            RemoteScrubber(playback = playback, clockTick = clockTick, controller = controller)

            Spacer(modifier = Modifier.height(20.dp))
            RemoteTransport(playback = playback, clockTick = clockTick, controller = controller)

            Spacer(modifier = Modifier.height(20.dp))
            RemoteVolumeRow(playback = playback, controller = controller)

            Spacer(modifier = Modifier.height(20.dp))
            RemoteSecondaryControls(playback = playback, controller = controller)

            if (!error.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = RemoteOnSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(RemoteError.copy(alpha = 0.9f))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun RemotePoster(posterUrl: String?, posterThumbhash: String?) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = Modifier
            .heightIn(max = 300.dp)
            .aspectRatio(2f / 3f)
            .shadow(elevation = 18.dp, shape = shape, clip = false)
            .clip(shape)
            .background(RemoteSurfaceElevated),
    ) {
        if (!posterUrl.isNullOrBlank()) {
            ThumbhashImage(
                url = posterUrl,
                thumbhash = posterThumbhash,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                Icons.Outlined.Tv,
                contentDescription = null,
                tint = RemoteSecondary,
                modifier = Modifier
                    .size(40.dp)
                    .align(Alignment.Center),
            )
        }
    }
}

@Composable
private fun RemoteScrubber(
    playback: SiloCastPlaybackState,
    clockTick: Long,
    controller: SiloCastController,
) {
    val duration = playback.duration.coerceAtLeast(0.0)
    var scrubPreview by remember { mutableStateOf<Float?>(null) }
    // clockTick is the recomposition trigger for the interpolated playhead.
    @Suppress("UNUSED_EXPRESSION") clockTick
    val live = scrubPreview?.toDouble() ?: controller.displayTime().coerceIn(0.0, duration)

    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = live.toFloat(),
            onValueChange = { scrubPreview = it },
            onValueChangeFinished = {
                scrubPreview?.let { controller.seek(it.toDouble()) }
                scrubPreview = null
            },
            valueRange = 0f..duration.coerceAtLeast(1.0).toFloat(),
            enabled = duration > 0.0,
            colors = remoteSliderColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                formatHMS(live),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = RemoteSecondary,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                if (duration > 0.0) "-" + formatHMS((duration - live).coerceAtLeast(0.0)) else formatHMS(duration),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = RemoteSecondary,
            )
        }
    }
}

@Composable
private fun RemoteTransport(
    playback: SiloCastPlaybackState,
    clockTick: Long,
    controller: SiloCastController,
) {
    @Suppress("UNUSED_EXPRESSION") clockTick
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (playback.hasNextEpisode) {
            Arrangement.SpaceBetween
        } else {
            Arrangement.spacedBy(28.dp)
        },
        modifier = if (playback.hasNextEpisode) Modifier.fillMaxWidth() else Modifier,
    ) {
        if (playback.hasNextEpisode) {
            // Balance the trailing Next button so the core transport remains
            // centered instead of shifting left whenever an episode follows.
            Spacer(modifier = Modifier.size(48.dp))
        }

        IconButton(onClick = { controller.seek((controller.displayTime() - 10.0).coerceAtLeast(0.0)) }) {
            Icon(
                Icons.Filled.Replay10,
                contentDescription = "Back 10 seconds",
                tint = RemoteOnSurface,
                modifier = Modifier.size(34.dp),
            )
        }

        Surface(
            onClick = { controller.playPause() },
            shape = CircleShape,
            color = RemoteOnSurface,
            modifier = Modifier.size(64.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (playback.isLoading || playback.isBuffering) {
                    CircularProgressIndicator(
                        color = Color.Black,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(26.dp),
                    )
                } else {
                    Icon(
                        if (controller.isPlaying()) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (controller.isPlaying()) "Pause" else "Play",
                        tint = Color.Black,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
        }

        IconButton(onClick = {
            val base = controller.displayTime()
            val target = if (playback.duration > 0.0) (base + 30.0).coerceAtMost(playback.duration) else base + 30.0
            controller.seek(target)
        }) {
            Icon(
                Icons.Filled.Forward30,
                contentDescription = "Forward 30 seconds",
                tint = RemoteOnSurface,
                modifier = Modifier.size(34.dp),
            )
        }

        if (playback.hasNextEpisode) {
            IconButton(onClick = { controller.playNext() }) {
                Icon(
                    Icons.Filled.SkipNext,
                    contentDescription = playback.nextEpisodeTitle?.let { "Next: $it" } ?: "Next episode",
                    tint = RemoteOnSurface,
                    modifier = Modifier.size(30.dp),
                )
            }
        }
    }
}

@Composable
private fun RemoteVolumeRow(
    playback: SiloCastPlaybackState,
    controller: SiloCastController,
) {
    var volumePreview by remember { mutableStateOf<Float?>(null) }
    val shown = volumePreview ?: (if (playback.isMuted) 0f else playback.volume.toFloat())

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .widthIn(max = 280.dp)
            .fillMaxWidth(),
    ) {
        IconButton(onClick = { controller.setMuted(!playback.isMuted) }) {
            Icon(
                if (playback.isMuted || playback.volume <= 0.001) {
                    Icons.AutoMirrored.Filled.VolumeOff
                } else {
                    Icons.AutoMirrored.Filled.VolumeDown
                },
                contentDescription = if (playback.isMuted) "Unmute" else "Mute",
                tint = RemoteOnSurface,
            )
        }
        Slider(
            value = shown.coerceIn(0f, 1f),
            onValueChange = { volumePreview = it },
            onValueChangeFinished = {
                volumePreview?.let { controller.setVolume(it.toDouble()) }
                volumePreview = null
            },
            colors = remoteSliderColors(),
            modifier = Modifier.weight(1f),
        )
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(width = 28.dp, height = 48.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = null,
                tint = RemoteOnSurface.copy(alpha = 0.55f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun remoteSliderColors() = SliderDefaults.colors(
    thumbColor = RemoteOnSurface,
    activeTrackColor = RemoteOnSurface,
    inactiveTrackColor = RemoteChipFill,
)

// --- Secondary controls: horizontal row of icon-caption chips, each opening
// --- a dropdown menu (Apple's Menu equivalents).

private data class MenuEntry(
    val label: String,
    val selected: Boolean,
    val enabled: Boolean = true,
    val isSectionHeader: Boolean = false,
    val onClick: () -> Unit = {},
)

@Composable
private fun RemoteSecondaryControls(
    playback: SiloCastPlaybackState,
    controller: SiloCastController,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (playback.qualityOptions.isNotEmpty()) {
            RemoteChipMenu(
                icon = Icons.Outlined.Tune,
                caption = "Quality",
                enabled = !playback.isQualitySwitching,
                entries = playback.qualityOptions.map { option ->
                    MenuEntry(
                        label = option.label,
                        selected = playback.activeQualityId == option.id,
                        onClick = { controller.selectQuality(option.id) },
                    )
                },
                modifier = Modifier.weight(1f),
            )
        }

        if (playback.audioTracks.isNotEmpty()) {
            RemoteChipMenu(
                icon = Icons.Outlined.GraphicEq,
                caption = "Audio",
                entries = playback.audioTracks.map { track ->
                    MenuEntry(
                        label = track.title,
                        selected = playback.selectedAudioTrackId == track.trackId,
                        onClick = { controller.selectAudioTrack(track.trackId) },
                    )
                },
                modifier = Modifier.weight(1f),
            )
        }

        val hasSubtitleControls = playback.subtitleTracks.isNotEmpty() ||
            playback.supportsSubtitleDelay == true ||
            playback.supportsSubtitlePosition == true
        if (hasSubtitleControls) {
            RemoteChipMenu(
                icon = Icons.Outlined.ClosedCaption,
                caption = "Subtitles",
                entries = subtitleMenuEntries(playback, controller),
                modifier = Modifier.weight(1f),
            )
        }

        RemoteChipMenu(
            icon = Icons.Outlined.Speed,
            caption = "Speed",
            entries = listOf(0.75, 1.0, 1.25, 1.5, 2.0).map { speed ->
                MenuEntry(
                    label = "${speed}×",
                    selected = kotlin.math.abs(playback.playbackSpeed - speed) < 0.01,
                    onClick = { controller.setPlaybackSpeed(speed) },
                )
            },
            modifier = Modifier.weight(1f),
        )
    }
}

private fun subtitleMenuEntries(
    playback: SiloCastPlaybackState,
    controller: SiloCastController,
): List<MenuEntry> = buildList {
    if (playback.subtitleTracks.isNotEmpty()) {
        add(MenuEntry(label = "Track", selected = false, isSectionHeader = true))
        add(
            MenuEntry(
                label = "Off",
                selected = playback.selectedSubtitleTrackId == null,
                onClick = { controller.selectSubtitleTrack(null) },
            ),
        )
        playback.subtitleTracks.forEach { track ->
            add(
                MenuEntry(
                    label = track.title,
                    selected = playback.selectedSubtitleTrackId == track.trackId,
                    onClick = { controller.selectSubtitleTrack(track.trackId) },
                ),
            )
        }
    }
    if (playback.supportsSubtitleDelay == true) {
        add(MenuEntry(label = "Delay", selected = false, isSectionHeader = true))
        val presets = listOf(-2_000, -1_500, -1_000, -500, -250, 0, 250, 500, 1_000, 1_500, 2_000)
        val current = playback.subtitleSyncMs ?: 0
        val options = if (presets.contains(current)) presets else (presets + current).sorted()
        options.forEach { ms ->
            add(
                MenuEntry(
                    label = subtitleDelayLabel(ms),
                    selected = current == ms,
                    onClick = { controller.setSubtitleSyncMs(ms) },
                ),
            )
        }
    }
    if (playback.supportsSubtitlePosition == true) {
        add(MenuEntry(label = "Position", selected = false, isSectionHeader = true))
        listOf("standard" to "Bottom", "lower-third" to "Lower Third", "top" to "Top").forEach { (id, label) ->
            add(
                MenuEntry(
                    label = label,
                    selected = playback.subtitlePosition == id ||
                        (id == "standard" && playback.subtitlePosition == "bottom"),
                    onClick = { controller.setSubtitlePosition(id) },
                ),
            )
        }
    }
}

@Composable
private fun RemoteChipMenu(
    icon: ImageVector,
    caption: String,
    entries: List<MenuEntry>,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .then(if (enabled) Modifier.clickable { expanded = true } else Modifier)
                .padding(vertical = 8.dp, horizontal = 4.dp),
        ) {
            Icon(
                icon,
                contentDescription = caption,
                tint = if (enabled) RemoteOnSurface.copy(alpha = 0.9f) else RemoteSecondary.copy(alpha = 0.5f),
                modifier = Modifier.size(22.dp),
            )
            Text(
                caption,
                style = MaterialTheme.typography.labelSmall,
                color = if (enabled) RemoteOnSurface.copy(alpha = 0.9f) else RemoteSecondary.copy(alpha = 0.5f),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            entries.forEach { entry ->
                if (entry.isSectionHeader) {
                    Text(
                        entry.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                } else {
                    DropdownMenuItem(
                        text = { Text(entry.label) },
                        enabled = entry.enabled,
                        leadingIcon = {
                            if (entry.selected) {
                                Icon(Icons.Filled.Check, contentDescription = "Selected", modifier = Modifier.size(18.dp))
                            } else {
                                Spacer(modifier = Modifier.size(18.dp))
                            }
                        },
                        onClick = {
                            expanded = false
                            entry.onClick()
                        },
                    )
                }
            }
        }
    }
}

private fun subtitleDelayLabel(milliseconds: Int): String {
    if (milliseconds == 0) return "0.0s"
    return String.format(java.util.Locale.US, "%+.1fs", milliseconds / 1000.0)
}

private fun formatHMS(seconds: Double): String {
    val total = seconds.toInt().coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) {
        "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    } else {
        "$m:${s.toString().padStart(2, '0')}"
    }
}
