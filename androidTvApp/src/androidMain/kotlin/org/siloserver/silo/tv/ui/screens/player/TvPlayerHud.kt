package org.siloserver.silo.tv.ui.screens.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.siloserver.silo.common.player.PlayerStatsSnapshot
import org.siloserver.silo.common.player.SleepTimerState
import org.siloserver.silo.model.catalog.VersionChapter
import org.siloserver.silo.model.playback.PlaybackExecutionPlan
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.model.settings.SubtitleAppearance
import org.siloserver.silo.model.settings.SubtitleBackgroundStylePreset
import org.siloserver.silo.model.settings.SubtitleFontSizePreset
import org.siloserver.silo.model.settings.SubtitlePositionPreset
import org.siloserver.silo.tv.ui.theme.DarkSurfaceElevated

private val HudMaxWidth = 680.dp
private val HudMinHeight = 290.dp
private val HudMaxHeight = 360.dp
private val HudPanelCorner = 18.dp
private val HudPanelPadding = 22.dp
private val HudContentGap = 14.dp
private val HudTabHeight = 40.dp
private val HudPaneBottomPadding = 14.dp
private val HudPaneColumnGap = 32.dp
private val HudTitleTextSize = 21.sp
private val HudTitleLineHeight = 25.sp
private val HudBodyTextSize = 16.sp
private val HudBodyLineHeight = 20.sp
private val HudMetaTextSize = 15.sp
private val HudMetaLineHeight = 19.sp
private val HudChipTextSize = 14.sp
private val HudChipLineHeight = 18.sp
private val HudTabTextSize = 16.sp
private val HudTabLineHeight = 20.sp

/**
 * Lets a [HudFocusedSettingRow] register its own focus requester as the row that
 * opened the shared picker dialog, so the HUD can return focus to it when the
 * picker closes (instead of snapping focus back to the tab pill). Provided by
 * [TvPlayerHud]; null when a row is used outside the HUD.
 */
private val LocalHudPickerReturnFocus =
    compositionLocalOf<((FocusRequester) -> Unit)?> { null }

/**
 * Floating top-center player HUD mirroring `iosApp/.../tvOS/TVPlayerInfoHUD.swift`.
 *
 * A frosted/opaque dark card with adaptive Android TV bounds drops on top of
 * the video with NO full-screen dim so playback stays visible. A
 * horizontal pill TAB BAR sits at the top of the card; the selected pane renders
 * below it.
 *
 * Tab set: Info · Stats · Video · Audio · Subtitles · Chapters. Info / Video are
 * always present; Stats / Audio / Subtitles / Chapters are hidden when their
 * backing data is empty (Infuse hides rather than disables, keeping the bar
 * tidy). The Subtitles tab folds in what used to be the separate subtitle drawer
 * + style dialog: track list, delay, appearance, plus the Android-only Search /
 * AI-Translate rows.
 *
 * Option controls follow the tvOS row→picker-dialog model: each editable option
 * renders as a [HudFocusedSettingRow] (label + current value + chevron, inverted
 * capsule focus). Activating a row opens a centered [HudPickerDialog] whose
 * scrollable option list shows a checkmark on the selected option, auto-focuses
 * + scrolls to the selection, commits on Select and closes, and dismisses on
 * Back.
 */
@Composable
internal fun TvPlayerHud(
    title: String,
    positionSec: Double,
    durationSec: Double,
    seasonNumber: Int?,
    episodeNumber: Int?,
    audioTracks: List<PlayerTrackEntry>,
    videoQualities: List<VideoQualityOption>,
    fileVersions: List<org.siloserver.silo.model.catalog.FileVersion> = emptyList(),
    selectedFileId: Int? = null,
    onSelectFileVersion: (Int) -> Unit = {},
    subtitleTracks: List<PlayerTrackEntry>,
    subtitleUrls: List<PlayerSubtitleInfo> = emptyList(),
    subtitlePresentation: TvSubtitleHudPresentation,
    stats: PlayerStatsSnapshot,
    playbackPlan: PlaybackExecutionPlan? = null,
    videoFillMode: VideoFillMode,
    onSelectAudio: (Int) -> Unit,
    onSelectVideoQuality: (String) -> Unit,
    onVideoFillModeChanged: (VideoFillMode) -> Unit,
    playbackSpeed: Double,
    onPlaybackSpeedChanged: (Double) -> Unit,
    sleepTimerState: SleepTimerState,
    onStartSleepTimer: (Int) -> Unit,
    onCancelSleepTimer: () -> Unit,
    autoSkipIntro: Boolean,
    onAutoSkipIntroChanged: (Boolean) -> Unit,
    autoPlayNext: Boolean,
    onAutoPlayNextChanged: (Boolean) -> Unit,
    audioDelayMs: Int,
    audioDelayEnabled: Boolean,
    onAudioDelayChanged: (Int) -> Unit,
    subtitleDelayMs: Int,
    subtitleDelayEnabled: Boolean,
    onSubtitleDelayChanged: (Int) -> Unit,
    subtitleAppearance: SubtitleAppearance,
    onSubtitleAppearanceChanged: (SubtitleAppearance) -> Unit,
    onSubtitlesPaneShown: () -> Unit,
    onSearchSubtitles: (() -> Unit)?,
    onTranslateWithAi: (() -> Unit)?,
    hdrEnabled: Boolean,
    onHdrEnabledChanged: (Boolean) -> Unit,
    dolbyVisionEnabled: Boolean,
    onDolbyVisionEnabledChanged: (Boolean) -> Unit,
    chapters: List<VersionChapter>,
    onSelectChapter: (Int) -> Unit,
    onDismiss: () -> Unit,
    initialTab: HudTab = HudTab.Info,
    modifier: Modifier = Modifier,
) {
    val tabs = visibleHudTabs(
        stats = stats,
        audioTracks = audioTracks,
        subtitleTracks = subtitleTracks,
        chapters = chapters,
    )
    var selectedTab by remember {
        mutableStateOf(initialTab.takeIf { it in tabs } ?: tabs.first())
    }

    // The active picker dialog, shared by every pane. Null = no dialog. While a
    // dialog is open the panes dim + disable so focus stays inside the modal,
    // matching the tvOS HUDPickerDialog presentation.
    var activePicker by remember { mutableStateOf<HudPickerPresentation?>(null) }

    // The setting row that opened the active picker. On picker close we return
    // focus here — not to the tab pill — so the user resumes on the row they were
    // editing instead of re-traversing the whole pane from the tab bar.
    val pickerReturnFocus = remember { mutableStateOf<FocusRequester?>(null) }
    val registerPickerReturnFocus = remember {
        { requester: FocusRequester -> pickerReturnFocus.value = requester }
    }

    val tabFocusRequesters = remember(tabs) { tabs.associateWith { FocusRequester() } }

    // Preserve the user's current tab when the visible-tabs list changes (Stats /
    // Audio / Chapters arriving asynchronously): only re-seed from initialTab when
    // the caller actually requests a different tab, or when the currently-selected
    // tab is no longer present. Re-seeding on every membership change would yank
    // the user off the tab they navigated to and snap focus back to the Info pill.
    var lastInitialTab by remember { mutableStateOf(initialTab) }
    LaunchedEffect(initialTab, tabs) {
        selectedTab = when {
            initialTab != lastInitialTab -> initialTab.takeIf { it in tabs } ?: tabs.first()
            selectedTab in tabs -> selectedTab
            else -> initialTab.takeIf { it in tabs } ?: tabs.first()
        }
        lastInitialTab = initialTab
    }

    // Seed focus on the active tab pill when the HUD first appears.
    LaunchedEffect(Unit) {
        tabFocusRequesters[selectedTab]?.let { runCatching { it.requestFocus() } }
    }

    // When a picker closes, return focus to the setting row that opened it rather
    // than the tab pill, so the user doesn't have to re-traverse the pane after
    // every picker interaction. Falls back to the tab pill if no row was recorded.
    LaunchedEffect(activePicker) {
        if (activePicker == null) {
            val target = pickerReturnFocus.value
            if (target != null) {
                pickerReturnFocus.value = null
                runCatching { target.requestFocus() }
            }
        }
    }

    // Keep an open subtitle picker synchronized with reducer state while
    // retaining the same stable focused row through Applying -> committed.
    LaunchedEffect(subtitlePresentation, activePicker?.title) {
        val current = activePicker
        if (current?.title == "Subtitle Track") {
            val checkedRow = subtitlePresentation.rows.firstOrNull { it.checked }
            val focusedRow = subtitlePresentation.rows.firstOrNull { it.focused }
            activePicker = current.copy(
                options = subtitlePresentation.rows.map { row ->
                    HudPickerOption(
                        id = row.stableId,
                        label = if (row.applying) "${row.label} · Applying…" else row.label,
                    )
                },
                selectedId = checkedRow?.stableId
                    ?: subtitlePresentation.rows.firstOrNull()?.stableId.orEmpty(),
                focusedId = focusedRow?.stableId
                    ?: current.focusedId,
            )
        }
    }

    val presentPicker: (HudPickerPresentation) -> Unit = { activePicker = it }
    val closePicker: () -> Unit = { activePicker = null }

    // Android 16 no longer dispatches KEYCODE_BACK to target-36 apps. Register
    // the picker as the most specific callback; when it is closed, the player
    // screen's callback remains responsible for dismissing the HUD itself.
    BackHandler(enabled = activePicker != null) { closePicker() }

    // Top-center card. No full-screen scrim — the video stays visible behind it.
    Box(
        modifier = modifier
            .widthIn(max = HudMaxWidth)
            .fillMaxWidth(0.72f)
            .heightIn(min = HudMinHeight, max = HudMaxHeight)
            .clip(RoundedCornerShape(HudPanelCorner))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
            .border(
                width = 0.5.dp,
                color = Color.White.copy(alpha = 0.14f),
                shape = RoundedCornerShape(HudPanelCorner),
            )
            .onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyUp &&
                    (ev.key == Key.Back || ev.key == Key.Escape)
                ) {
                    // Pre-Android-16 remote and keyboard fallback. System Back
                    // uses the callbacks above and on TvPlayerScreen.
                    if (activePicker != null) {
                        activePicker = null
                    } else {
                        onDismiss()
                    }
                    true
                } else {
                    false
                }
            }
            .padding(HudPanelPadding),
    ) {
        CompositionLocalProvider(LocalHudPickerReturnFocus provides registerPickerReturnFocus) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = if (activePicker != null) 0.28f else 1f },
            verticalArrangement = Arrangement.spacedBy(HudContentGap),
        ) {
            // Horizontal pill tab bar at the top.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                tabs.forEach { tab ->
                    HudTabPill(
                        label = tab.label,
                        isSelected = tab == selectedTab,
                        enabled = activePicker == null,
                        focusRequester = tabFocusRequesters[tab]
                            ?: remember(tab) { FocusRequester() },
                        onFocused = {
                            // Focus-driven selection — no Select press required.
                            selectedTab = tab
                        },
                    )
                }
            }

            // Content pane below the tab bar.
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                when (selectedTab) {
                    HudTab.Info -> HudPaneViewport {
                        HudInfoPane(
                            title = title,
                            positionSec = positionSec,
                            durationSec = durationSec,
                            seasonNumber = seasonNumber,
                            episodeNumber = episodeNumber,
                            stats = stats,
                            playbackPlan = playbackPlan,
                            subtitleTracks = subtitleTracks,
                            subtitleUrls = subtitleUrls,
                            chapters = chapters,
                        )
                    }
                    HudTab.Stats -> HudPaneViewport { HudStatsPane(stats) }
                    HudTab.Video -> HudVideoPane(
                        videoQualities = videoQualities,
                        onSelectVideoQuality = onSelectVideoQuality,
                        fileVersions = fileVersions,
                        selectedFileId = selectedFileId,
                        onSelectFileVersion = onSelectFileVersion,
                        hdrEnabled = hdrEnabled,
                        onHdrEnabledChanged = onHdrEnabledChanged,
                        dolbyVisionEnabled = dolbyVisionEnabled,
                        onDolbyVisionEnabledChanged = onDolbyVisionEnabledChanged,
                        fillMode = videoFillMode,
                        onFillModeChanged = onVideoFillModeChanged,
                        playbackSpeed = playbackSpeed,
                        onPlaybackSpeedChanged = onPlaybackSpeedChanged,
                        sleepTimerState = sleepTimerState,
                        onStartSleepTimer = onStartSleepTimer,
                        onCancelSleepTimer = onCancelSleepTimer,
                        autoSkipIntro = autoSkipIntro,
                        onAutoSkipIntroChanged = onAutoSkipIntroChanged,
                        autoPlayNext = autoPlayNext,
                        onAutoPlayNextChanged = onAutoPlayNextChanged,
                        enabled = activePicker == null,
                        onPresentPicker = presentPicker,
                    )
                    HudTab.Audio -> HudAudioPane(
                        audioTracks = audioTracks,
                        onSelectAudio = onSelectAudio,
                        audioDelayMs = audioDelayMs,
                        audioDelayEnabled = audioDelayEnabled,
                        onAudioDelayChanged = onAudioDelayChanged,
                        enabled = activePicker == null,
                        onPresentPicker = presentPicker,
                    )
                    HudTab.Subtitles -> HudSubtitlesPane(
                        presentation = subtitlePresentation,
                        subtitleDelayMs = subtitleDelayMs,
                        subtitleDelayEnabled = subtitleDelayEnabled,
                        onSubtitleDelayChanged = onSubtitleDelayChanged,
                        appearance = subtitleAppearance,
                        onAppearanceChanged = onSubtitleAppearanceChanged,
                        onPaneShown = onSubtitlesPaneShown,
                        onSearchSubtitles = onSearchSubtitles,
                        onTranslateWithAi = onTranslateWithAi,
                        enabled = activePicker == null,
                        onPresentPicker = presentPicker,
                    )
                    HudTab.Chapters -> HudPaneViewport {
                        HudChaptersPane(
                            chapters = chapters,
                            onSelectChapter = onSelectChapter,
                        )
                    }
                }
            }
        }
        }

        // Centered modal picker dialog, drawn on top of the dimmed panes.
        val picker = activePicker
        if (picker != null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                HudPickerDialog(
                    presentation = picker,
                    onClose = closePicker,
                )
            }
        }
    }
}

enum class HudTab(val label: String) {
    Info("Info"),
    Stats("Stats"),
    Video("Video"),
    Audio("Audio"),
    Subtitles("Subtitles"),
    Chapters("Chapters"),
}

/**
 * Tabs shown for the current session. Info + Video are always present; Stats,
 * Audio, Subtitles, Chapters appear only when their backing data exists, in a
 * stable order matching tvOS (Info · Stats · Video · Audio · Subtitles ·
 * Chapters).
 */
internal fun visibleHudTabs(
    stats: PlayerStatsSnapshot,
    audioTracks: List<PlayerTrackEntry>,
    subtitleTracks: List<PlayerTrackEntry>,
    chapters: List<VersionChapter>,
): List<HudTab> = buildList {
    add(HudTab.Info)
    if (stats.hasHudRows()) add(HudTab.Stats)
    add(HudTab.Video)
    if (audioTracks.isNotEmpty()) add(HudTab.Audio)
    // Subtitles is always available (unlike tvOS, which hides it when empty):
    // the pane hosts Android-only Search-subtitles / AI-Translate / style
    // controls that must stay reachable even when a title carries no tracks —
    // there is no longer a separate subtitles button to reach them.
    add(HudTab.Subtitles)
    if (chapters.isNotEmpty()) add(HudTab.Chapters)
}

@Composable
private fun HudTabPill(
    label: String,
    isSelected: Boolean,
    enabled: Boolean,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    LaunchedEffect(isFocused) {
        if (isFocused) onFocused()
    }

    val bg = when {
        isFocused -> Color.White.copy(alpha = 0.94f)
        isSelected -> Color.White.copy(alpha = 0.18f)
        else -> Color.White.copy(alpha = 0.06f)
    }
    val fg = when {
        isFocused -> Color.Black
        isSelected -> Color.White
        else -> Color.White.copy(alpha = 0.72f)
    }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.0f else 0.96f,
        animationSpec = tween(120),
        label = "hudTabScale",
    )

    Box(
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .height(HudTabHeight)
            .clip(RoundedCornerShape(25.dp))
            .background(bg)
            .focusRequester(focusRequester)
            .focusable(enabled = enabled, interactionSource = interactionSource)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = fg,
            style = MaterialTheme.typography.titleSmall.copy(
                fontSize = HudTabTextSize,
                lineHeight = HudTabLineHeight,
                fontWeight = FontWeight.SemiBold,
            ),
        )
    }
}

@Composable
private fun HudPaneViewport(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = HudPaneBottomPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
private fun HudTwoColumnPane(
    modifier: Modifier = Modifier,
    left: @Composable ColumnScope.() -> Unit,
    right: @Composable ColumnScope.() -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(HudPaneColumnGap),
    ) {
        PaneColumn("Title", modifier = Modifier.weight(1f), content = left)
        PaneColumn("Stream", modifier = Modifier.weight(1f), content = right)
    }
}

/**
 * Info pane — two-column Title + Stream layout mirroring tvOS. The Android
 * player state exposes far less metadata than the Apple PlayerViewModel, so we
 * omit rows we don't have (series eyebrow, year, overview, audio layout) rather
 * than invent data: Title = title + episode tag + runtime; Stream = HDR/route/
 * codec badges, current subtitle, current chapter.
 */
@Composable
private fun HudInfoPane(
    title: String,
    positionSec: Double,
    durationSec: Double,
    seasonNumber: Int?,
    episodeNumber: Int?,
    stats: PlayerStatsSnapshot,
    playbackPlan: PlaybackExecutionPlan?,
    subtitleTracks: List<PlayerTrackEntry>,
    subtitleUrls: List<PlayerSubtitleInfo> = emptyList(),
    chapters: List<VersionChapter>,
    modifier: Modifier = Modifier,
) {
    val episodeTag = if (seasonNumber != null && episodeNumber != null) {
        "S$seasonNumber · E$episodeNumber"
    } else {
        null
    }
    val metaBits = buildList {
        if (durationSec > 0) add(formatTime(durationSec))
    }
    val streamRows = buildList<Pair<String, String>> {
        stats.backendRoute?.let { add("Route" to it) }
        playbackPlan?.takeIf {
            it.requestedMediaFileId != null &&
                it.effectiveMediaFileId != null &&
                it.requestedMediaFileId != it.effectiveMediaFileId
        }?.let { add("Source" to "Alternate version") }
        stats.videoCodec?.let { add("Video" to it.uppercase()) }
        stats.audioCodec?.let { add("Audio" to it.uppercase()) }
        val sub = subtitleTracks.firstOrNull { it.isSelected }
        // Built label ("Danish SRT (External)") via the mounted row — the raw
        // Media3 displayLabel echoes sidecar filenames.
        val subLabel = sub?.let { sel ->
            resolveMountedSubtitleRow(sel, subtitleTracks, subtitleUrls)
                ?.let { row -> subtitleChoiceLabel(row, subtitleUrls.indexOf(row)) }
                ?: sel.displayLabel.ifBlank { "On" }
        } ?: "Off"
        add("Subtitles" to subLabel)
        currentChapterTitle(chapters, positionSec)?.let { add("Chapter" to it) }
    }
    val badges = buildList {
        playbackPlan.validatedHdrBadge()?.let { add(it) }
        stats.resolution?.let { add(it) }
    }

    HudTwoColumnPane(
        modifier = modifier,
        left = {
            Text(
                text = title.ifBlank { "Now Playing" },
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontSize = HudTitleTextSize,
                    lineHeight = HudTitleLineHeight,
                    fontWeight = FontWeight.SemiBold,
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (episodeTag != null) {
                Text(
                    text = episodeTag,
                    color = Color.White.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = HudBodyTextSize,
                        lineHeight = HudBodyLineHeight,
                    ),
                )
            }
            if (metaBits.isNotEmpty()) {
                Text(
                    text = metaBits.joinToString("  ·  "),
                    color = Color.White.copy(alpha = 0.65f),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = HudMetaTextSize,
                        lineHeight = HudMetaLineHeight,
                    ),
                )
            }
        },
        right = {
            if (badges.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    badges.forEach { badge ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .border(
                                    width = 0.5.dp,
                                    color = Color.White.copy(alpha = 0.35f),
                                    shape = RoundedCornerShape(50),
                                )
                                .padding(horizontal = 7.dp, vertical = 3.dp),
                        ) {
                            Text(
                                text = badge,
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontSize = HudChipTextSize,
                                    lineHeight = HudChipLineHeight,
                                    fontWeight = FontWeight.SemiBold,
                                ),
                            )
                        }
                    }
                }
            }
            streamRows.forEach { (label, value) ->
                LabelValueRow(label = label, value = value)
            }
        },
    )
}

private fun PlaybackExecutionPlan?.validatedHdrBadge(): String? {
    val claims = this?.claims?.video ?: return null
    return when {
        claims.dolbyVision -> "Dolby Vision"
        claims.hdr10Plus -> "HDR10+"
        claims.hdr10 -> "HDR10"
        claims.hlg -> "HLG"
        else -> null
    }
}

private fun currentChapterTitle(chapters: List<VersionChapter>, positionSec: Double): String? {
    if (chapters.isEmpty()) return null
    val current = chapters.lastOrNull { it.startSeconds <= positionSec } ?: return null
    return current.title.ifBlank { "Chapter ${current.index + 1}" }
}

@Composable
private fun PaneColumn(
    header: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = header.uppercase(),
            color = Color.White.copy(alpha = 0.5f),
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = HudChipTextSize,
                lineHeight = HudChipLineHeight,
                fontWeight = FontWeight.SemiBold,
            ),
        )
        content()
    }
}

@Composable
private fun LabelValueRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = HudBodyTextSize,
                lineHeight = HudBodyLineHeight,
                fontWeight = FontWeight.Medium,
            ),
        )
        Box(modifier = Modifier.weight(1f))
        Text(
            text = value,
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = HudBodyTextSize,
                lineHeight = HudBodyLineHeight,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Stats pane — renders the live [PlayerStatsSnapshot] populated by
 * [PlaybackAnalyticsListener] events. Fields populate as events arrive
 * (format change, decoder init, bandwidth estimate); the pane shows only
 * non-null rows.
 */
@Composable
private fun HudStatsPane(stats: PlayerStatsSnapshot, modifier: Modifier = Modifier) {
    val rows = stats.hudRows()

    if (rows.isEmpty()) {
        HudEmptyStatePane("Stats unavailable", modifier)
        return
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        rows.forEach { (label, value) ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = HudBodyTextSize,
                        lineHeight = HudBodyLineHeight,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = HudBodyTextSize,
                        lineHeight = HudBodyLineHeight,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/**
 * Playback-speed presets — aligned to tvOS (0.75 / 1.0 / 1.25 / 1.5 / 2.0).
 */
private val PLAYBACK_SPEED_OPTIONS = listOf(0.75, 1.0, 1.25, 1.5, 2.0)

/**
 * Locale-independent option id for a playback speed. `"%.2f".format(...)` uses
 * the default locale, which on comma-decimal locales emits "1,25" — that never
 * round-trips through `toDoubleOrNull()`, silently no-op'ing the selection.
 * Format with [Locale.ROOT] so the id always matches on commit.
 */
private fun speedOptionId(speed: Double): String =
    String.format(java.util.Locale.ROOT, "%.2f", speed)

/** 1.0 -> "1.0×", 1.25 -> "1.25×" (matches tvOS speed labels). */
private fun formatTvPlaybackSpeed(speed: Double): String {
    val text = if (speed % 1.0 == 0.0) {
        // Locale.ROOT so comma-decimal devices render "1.0×", not "1,0×",
        // matching the dot-formatted speedOptionId used to commit the choice.
        String.format(java.util.Locale.ROOT, "%.1f", speed)
    } else {
        speed.toString().trimEnd('0').trimEnd('.')
    }
    return "$text×"
}

/** Sleep-timer presets (minutes) — mirrors the phone SleepTimerSheet. */
private val SLEEP_TIMER_PRESETS = listOf(15, 30, 45, 60, 90)

private fun formatSleepRemaining(totalSeconds: Int): String {
    val s = totalSeconds.coerceAtLeast(0)
    val m = s / 60
    val sec = s % 60
    return if (m > 0) "${m}m ${sec}s" else "${sec}s"
}

private fun sleepPresetLabel(minutes: Int): String =
    if (minutes >= 60) {
        "${minutes / 60}h${if (minutes % 60 != 0) " ${minutes % 60}m" else ""}"
    } else {
        "${minutes}m"
    }

/** Aspect (video-gravity) option labels — mirrors tvOS VideoGravity. */
private fun fillModeLabel(mode: VideoFillMode): String = when (mode) {
    VideoFillMode.Fit -> "Letterbox"
    VideoFillMode.Zoom -> "Zoom (crop)"
    VideoFillMode.Stretch -> "Stretch"
}

private fun onOffLabel(value: Boolean): String = if (value) "On" else "Off"

/**
 * Video pane — drill-in setting rows opening picker dialogs: Quality, Speed,
 * Aspect, HDR (+ auto behaviors), and Sleep Timer.
 */
@Composable
private fun HudVideoPane(
    videoQualities: List<VideoQualityOption>,
    onSelectVideoQuality: (String) -> Unit,
    fileVersions: List<org.siloserver.silo.model.catalog.FileVersion>,
    selectedFileId: Int?,
    onSelectFileVersion: (Int) -> Unit,
    hdrEnabled: Boolean,
    onHdrEnabledChanged: (Boolean) -> Unit,
    dolbyVisionEnabled: Boolean,
    onDolbyVisionEnabledChanged: (Boolean) -> Unit,
    fillMode: VideoFillMode,
    onFillModeChanged: (VideoFillMode) -> Unit,
    playbackSpeed: Double,
    onPlaybackSpeedChanged: (Double) -> Unit,
    sleepTimerState: SleepTimerState,
    onStartSleepTimer: (Int) -> Unit,
    onCancelSleepTimer: () -> Unit,
    autoSkipIntro: Boolean,
    onAutoSkipIntroChanged: (Boolean) -> Unit,
    autoPlayNext: Boolean,
    onAutoPlayNextChanged: (Boolean) -> Unit,
    enabled: Boolean,
    onPresentPicker: (HudPickerPresentation) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(HudPaneColumnGap),
    ) {
        // Playback column — Quality / Speed / Aspect / HDR + auto toggles.
        PaneColumn(
            "Playback",
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // Quality — derived from the real per-format video variants
                // (resolution / bitrate) flattened from the video group. This is
                // a genuine Media3 track override (setOverrideForType on the
                // video group), not a no-op. When there is only one real variant
                // (Auto + 0 or 1 format) there is nothing to switch, so the row
                // is shown disabled with an "Auto" value rather than faking it.
                // (videoQualities, when present, always contains a synthetic
                // "Auto" entry, so a genuine choice means size > 2.)
                // Version — the server's file versions (4K / 1080p encodes).
                // Switching restarts the session on that file at the current
                // position (QA 2026-07-08 / tvOS parity).
                if (fileVersions.size > 1) {
                    val currentVersion = fileVersions.firstOrNull { it.fileId == selectedFileId }
                        ?: fileVersions.firstOrNull()
                    HudFocusedSettingRow(
                        label = "Version",
                        value = org.siloserver.silo.tv.ui.screens.detail.TvPlaybackFormatting
                            .versionShortLabel(currentVersion),
                        enabled = enabled,
                        onActivate = {
                            onPresentPicker(
                                HudPickerPresentation(
                                    title = "Version",
                                    options = fileVersions.map { version ->
                                        HudPickerOption(
                                            id = version.fileId.toString(),
                                            label = org.siloserver.silo.tv.ui.screens.detail
                                                .TvPlaybackFormatting.versionShortLabel(version),
                                        )
                                    },
                                    selectedId = (currentVersion?.fileId ?: -1).toString(),
                                    onSelect = { id ->
                                        id.toIntOrNull()?.let(onSelectFileVersion)
                                    },
                                ),
                            )
                        },
                    )
                }

                // The server-transcode quality ladder always offers at least
                // Auto + Original (plus downscale rungs below the source), so the
                // row is enabled whenever there is more than one option.
                val hasQualityChoice = videoQualities.size > 1
                val selectedQuality = videoQualities.firstOrNull { it.isSelected }
                val qualityValue = selectedQuality?.label ?: "Auto"
                HudFocusedSettingRow(
                    label = "Quality",
                    value = qualityValue,
                    enabled = enabled && hasQualityChoice,
                    onActivate = {
                        onPresentPicker(
                            HudPickerPresentation(
                                title = "Quality",
                                options = videoQualities.map {
                                    HudPickerOption(id = it.id, label = it.label)
                                },
                                selectedId = (selectedQuality?.id ?: VIDEO_QUALITY_AUTO_ID),
                                onSelect = { id -> onSelectVideoQuality(id) },
                            ),
                        )
                    },
                )

                HudFocusedSettingRow(
                    label = "Speed",
                    value = formatTvPlaybackSpeed(playbackSpeed),
                    enabled = enabled,
                    onActivate = {
                        onPresentPicker(
                            HudPickerPresentation(
                                title = "Playback Speed",
                                options = PLAYBACK_SPEED_OPTIONS.map {
                                    HudPickerOption(speedOptionId(it), formatTvPlaybackSpeed(it))
                                },
                                selectedId = speedOptionId(playbackSpeed),
                                onSelect = { id ->
                                    PLAYBACK_SPEED_OPTIONS.firstOrNull { speedOptionId(it) == id }
                                        ?.let(onPlaybackSpeedChanged)
                                },
                            ),
                        )
                    },
                )

                HudFocusedSettingRow(
                    label = "Aspect",
                    value = fillModeLabel(fillMode),
                    enabled = enabled,
                    onActivate = {
                        onPresentPicker(
                            HudPickerPresentation(
                                title = "Aspect",
                                options = VideoFillMode.entries.map {
                                    HudPickerOption(it.name, fillModeLabel(it))
                                },
                                selectedId = fillMode.name,
                                onSelect = { id ->
                                    VideoFillMode.entries.firstOrNull { it.name == id }
                                        ?.let(onFillModeChanged)
                                },
                            ),
                        )
                    },
                )

                HudFocusedSettingRow(
                    label = "HDR passthrough",
                    value = onOffLabel(hdrEnabled),
                    enabled = enabled,
                    onActivate = {
                        onPresentPicker(
                            boolPicker(
                                title = "HDR Passthrough",
                                value = hdrEnabled,
                                onSet = onHdrEnabledChanged,
                            ),
                        )
                    },
                )

                // Off plays DV sources as their base layer (HDR10) — some
                // users prefer HDR10 even on DV-capable displays. Profile 5
                // always plays as DV (no watchable base layer); applies from
                // the next playback start. Apple parity (silo-apple e9bd775).
                HudFocusedSettingRow(
                    label = "Dolby Vision",
                    value = onOffLabel(dolbyVisionEnabled),
                    enabled = enabled,
                    onActivate = {
                        onPresentPicker(
                            boolPicker(
                                title = "Dolby Vision",
                                value = dolbyVisionEnabled,
                                onSet = onDolbyVisionEnabledChanged,
                            ),
                        )
                    },
                )

                HudFocusedSettingRow(
                    label = "Auto-skip intro",
                    value = onOffLabel(autoSkipIntro),
                    enabled = enabled,
                    onActivate = {
                        onPresentPicker(
                            boolPicker(
                                title = "Auto-skip Intro",
                                value = autoSkipIntro,
                                onSet = onAutoSkipIntroChanged,
                            ),
                        )
                    },
                )

                HudFocusedSettingRow(
                    label = "Auto-play next",
                    value = onOffLabel(autoPlayNext),
                    enabled = enabled,
                    onActivate = {
                        onPresentPicker(
                            boolPicker(
                                title = "Auto-play Next",
                                value = autoPlayNext,
                                onSet = onAutoPlayNextChanged,
                            ),
                        )
                    },
                )
            }
        }

        // Sync / timing column.
        PaneColumn(
            "Timers",
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            val activeSleep = sleepTimerState as? SleepTimerState.Active
            HudFocusedSettingRow(
                label = "Sleep timer",
                value = activeSleep?.let { "Sleeping in ${formatSleepRemaining(it.remainingSeconds)}" } ?: "Off",
                enabled = enabled,
                onActivate = {
                    onPresentPicker(
                        HudPickerPresentation(
                            title = "Sleep Timer",
                            options = buildList {
                                if (activeSleep != null) {
                                    add(HudPickerOption("cancel", "Cancel timer"))
                                }
                                add(HudPickerOption("off", "Off"))
                                addAll(
                                    SLEEP_TIMER_PRESETS.map { minutes ->
                                        HudPickerOption(minutes.toString(), sleepPresetLabel(minutes))
                                    },
                                )
                            },
                            selectedId = if (activeSleep != null) "cancel" else "off",
                            onSelect = { id ->
                                when (id) {
                                    "cancel", "off" -> onCancelSleepTimer()
                                    else -> id.toIntOrNull()?.let(onStartSleepTimer)
                                }
                            },
                        ),
                    )
                },
            )
        }
    }
}

/**
 * Like [HudOptionChip] but commits on explicit Select (click), NOT on focus —
 * use for multi-option rows where focus-driven commit would change the value
 * while the user is just traversing chips.
 */
@Composable
private fun HudClickChip(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val bg = when {
        isFocused -> Color.White.copy(alpha = 0.94f)
        selected -> Color.White.copy(alpha = 0.18f)
        else -> Color.White.copy(alpha = 0.06f)
    }
    val fg = when {
        isFocused -> Color.Black
        selected -> Color.White
        else -> Color.White.copy(alpha = 0.72f)
    }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.0f else 0.96f,
        animationSpec = tween(120),
        label = "hudClickChipScale",
    )
    Box(
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
            ) { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = fg,
            style = MaterialTheme.typography.titleSmall.copy(
                fontSize = HudBodyTextSize,
                lineHeight = HudBodyLineHeight,
                fontWeight = FontWeight.SemiBold,
            ),
        )
    }
}

/**
 * Audio pane — track selection + audio delay rendered as the tvOS row→dialog
 * pattern. Both open a centered [HudPickerDialog] from a [HudFocusedSettingRow].
 */
@Composable
private fun HudAudioPane(
    audioTracks: List<PlayerTrackEntry>,
    onSelectAudio: (Int) -> Unit,
    audioDelayMs: Int,
    audioDelayEnabled: Boolean,
    onAudioDelayChanged: (Int) -> Unit,
    enabled: Boolean,
    onPresentPicker: (HudPickerPresentation) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        PaneColumn("Track") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val selectedTrack = audioTracks.firstOrNull { it.isSelected }
                HudFocusedSettingRow(
                    label = "Audio track",
                    // Built labels ("English DTS 5.1") — raw Media3 labels echo
                    // server identity strings or bare ISO codes.
                    value = selectedTrack?.let { audioChoiceLabel(it, audioTracks.indexOf(it)) }
                        ?: "Default",
                    enabled = enabled && audioTracks.size > 1,
                    onActivate = {
                        onPresentPicker(
                            HudPickerPresentation(
                                title = "Audio Track",
                                options = audioTracks.mapIndexed { idx, track ->
                                    HudPickerOption(
                                        id = track.index.toString(),
                                        label = audioChoiceLabel(track, idx),
                                    )
                                },
                                selectedId = (selectedTrack?.index ?: 0).toString(),
                                onSelect = { id -> id.toIntOrNull()?.let(onSelectAudio) },
                            ),
                        )
                    },
                )

                HudFocusedSettingRow(
                    label = "Delay (PCM only)",
                    value = if (audioDelayEnabled) delayLabel(audioDelayMs) else "Unavailable during passthrough",
                    enabled = enabled && audioDelayEnabled,
                    onActivate = {
                        onPresentPicker(
                            delayPicker(
                                title = "Audio Delay",
                                current = audioDelayMs,
                                from = -1_000,
                                to = 1_000,
                                step = 50,
                                onSet = onAudioDelayChanged,
                            ),
                        )
                    },
                )
            }
        }
    }
}

/**
 * Subtitles pane — track selection + delay + appearance rendered as tvOS
 * row→dialog rows, plus the Android-only Search / AI-Translate rows preserved
 * from the old drawer. Text-color / background-color stay as swatch chips since
 * tvOS draws color swatches inline in its picker too.
 */
@Composable
private fun HudSubtitlesPane(
    presentation: TvSubtitleHudPresentation,
    subtitleDelayMs: Int,
    subtitleDelayEnabled: Boolean,
    onSubtitleDelayChanged: (Int) -> Unit,
    appearance: SubtitleAppearance,
    onAppearanceChanged: (SubtitleAppearance) -> Unit,
    onPaneShown: () -> Unit,
    onSearchSubtitles: (() -> Unit)?,
    onTranslateWithAi: (() -> Unit)?,
    enabled: Boolean,
    onPresentPicker: (HudPickerPresentation) -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { onPaneShown() }

    val subtitleTrackFocus = remember { FocusRequester() }
    val subtitleTextColorFocus = remember { FocusRequester() }
    val subtitleBackgroundColorFocus = remember { FocusRequester() }
    val subtitleOutlineColorFocus = remember { FocusRequester() }

    Row(
        modifier = modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(HudPaneColumnGap),
    ) {
        // Tracks + sync column.
        PaneColumn(
            "Tracks",
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val checkedRow = presentation.rows.firstOrNull { row -> row.checked }
                val applyingRow = presentation.rows.firstOrNull { row -> row.applying }
                val focusedRow = presentation.rows.firstOrNull { row -> row.focused }
                HudFocusedSettingRow(
                    label = "Subtitles",
                    value = applyingRow?.let { "${it.label} · Applying…" }
                        ?: checkedRow?.label
                        ?: "Off",
                    enabled = enabled,
                    focusRequester = subtitleTrackFocus,
                    rightFocusRequester = subtitleTextColorFocus,
                    onActivate = {
                        onPresentPicker(
                            HudPickerPresentation(
                                title = "Subtitle Track",
                                options = presentation.rows.map { row ->
                                    HudPickerOption(
                                        id = row.stableId,
                                        label = if (row.applying) {
                                            "${row.label} · Applying…"
                                        } else {
                                            row.label
                                        },
                                    )
                                },
                                selectedId = checkedRow?.stableId
                                    ?: presentation.rows.firstOrNull()?.stableId.orEmpty(),
                                focusedId = focusedRow?.stableId
                                    ?: checkedRow?.stableId
                                    ?: presentation.rows.firstOrNull()?.stableId.orEmpty(),
                                closeOnSelect = false,
                                onFocused = presentation.onFocused,
                                onSelect = { stableId ->
                                    presentation.rows
                                        .firstOrNull { row -> row.stableId == stableId }
                                        ?.let { row -> presentation.onSelect(row.identity) }
                                },
                            ),
                        )
                    },
                )

                HudFocusedSettingRow(
                    label = "Delay",
                    value = if (subtitleDelayEnabled) {
                        delayLabel(subtitleDelayMs)
                    } else {
                        "Unavailable for burned-in subtitles"
                    },
                    enabled = enabled && subtitleDelayEnabled,
                    rightFocusRequester = subtitleTextColorFocus,
                    onActivate = {
                        onPresentPicker(
                            delayPicker(
                                title = "Subtitle Delay",
                                current = subtitleDelayMs,
                                from = -2_000,
                                to = 2_000,
                                step = 100,
                                onSet = onSubtitleDelayChanged,
                            ),
                        )
                    },
                )

                HudFocusedSettingRow(
                    label = "Size",
                    value = FONT_SIZES.firstOrNull { it.first == appearance.fontSize }?.second
                        ?: appearance.fontSize.name,
                    enabled = enabled,
                    rightFocusRequester = subtitleTextColorFocus,
                    onActivate = {
                        onPresentPicker(
                            HudPickerPresentation(
                                title = "Subtitle Size",
                                options = FONT_SIZES.map { HudPickerOption(it.first.name, it.second) },
                                selectedId = appearance.fontSize.name,
                                onSelect = { id ->
                                    FONT_SIZES.firstOrNull { it.first.name == id }?.let {
                                        onAppearanceChanged(appearance.copy(fontSize = it.first))
                                    }
                                },
                            ),
                        )
                    },
                )

                HudFocusedSettingRow(
                    label = "Font",
                    value = FONT_FAMILIES.firstOrNull { it.first == appearance.fontFamily }?.second
                        ?: appearance.fontFamily,
                    enabled = enabled,
                    rightFocusRequester = subtitleTextColorFocus,
                    onActivate = {
                        onPresentPicker(
                            HudPickerPresentation(
                                title = "Subtitle Font",
                                options = FONT_FAMILIES.map { HudPickerOption(it.first, it.second) },
                                selectedId = appearance.fontFamily,
                                onSelect = { id ->
                                    FONT_FAMILIES.firstOrNull { it.first == id }?.let {
                                        onAppearanceChanged(appearance.copy(fontFamily = it.first))
                                    }
                                },
                            ),
                        )
                    },
                )

                HudFocusedSettingRow(
                    label = "Background",
                    value = BACKGROUND_STYLES.firstOrNull { it.first == appearance.backgroundStyle }?.second
                        ?: appearance.backgroundStyle.name,
                    enabled = enabled,
                    rightFocusRequester = subtitleBackgroundColorFocus,
                    onActivate = {
                        onPresentPicker(
                            HudPickerPresentation(
                                title = "Subtitle Background",
                                options = BACKGROUND_STYLES.map { HudPickerOption(it.first.name, it.second) },
                                selectedId = appearance.backgroundStyle.name,
                                onSelect = { id ->
                                    BACKGROUND_STYLES.firstOrNull { it.first.name == id }?.let {
                                        onAppearanceChanged(appearance.copy(backgroundStyle = it.first))
                                    }
                                },
                            ),
                        )
                    },
                )

                HudFocusedSettingRow(
                    label = "Opacity",
                    value = "${appearance.backgroundOpacity}%",
                    enabled = enabled,
                    rightFocusRequester = subtitleTextColorFocus,
                    onActivate = {
                        onPresentPicker(
                            HudPickerPresentation(
                                title = "Background Opacity",
                                options = OPACITY_STEPS.map { HudPickerOption(it.toString(), "$it%") },
                                selectedId = appearance.backgroundOpacity.toString(),
                                onSelect = { id ->
                                    id.toIntOrNull()?.let {
                                        onAppearanceChanged(appearance.copy(backgroundOpacity = it))
                                    }
                                },
                            ),
                        )
                    },
                )

                HudFocusedSettingRow(
                    label = "Outline",
                    value = onOffLabel(appearance.textOutline),
                    enabled = enabled,
                    // The outline-color swatch (subtitleOutlineColorFocus) is only
                    // composed when textOutline is on. Right-nav must not target a
                    // detached requester when it's off, so gate the target on it.
                    rightFocusRequester = subtitleOutlineColorFocus.takeIf { appearance.textOutline },
                    onActivate = {
                        onPresentPicker(
                            boolPicker(
                                title = "Text Outline",
                                value = appearance.textOutline,
                                onSet = { onAppearanceChanged(appearance.copy(textOutline = it)) },
                            ),
                        )
                    },
                )

                HudFocusedSettingRow(
                    label = "Position",
                    value = POSITIONS.firstOrNull { it.first == appearance.position }?.second
                        ?: appearance.position.name,
                    enabled = enabled,
                    rightFocusRequester = subtitleTextColorFocus,
                    onActivate = {
                        onPresentPicker(
                            HudPickerPresentation(
                                title = "Subtitle Position",
                                options = POSITIONS.map { HudPickerOption(it.first.name, it.second) },
                                selectedId = appearance.position.name,
                                onSelect = { id ->
                                    POSITIONS.firstOrNull { it.first.name == id }?.let {
                                        onAppearanceChanged(appearance.copy(position = it.first))
                                    }
                                },
                            ),
                        )
                    },
                )
            }
        }

        // Colors + Android-only acquisition column.
        PaneColumn(
            "Style & sources",
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            HudSubtitlePreview(appearance = appearance)

            HudFocusedSettingRow(
                label = "No background",
                value = if (appearance.backgroundStyle == SubtitleBackgroundStylePreset.None) "On" else "Off",
                enabled = enabled,
                leftFocusRequester = subtitleTrackFocus,
                onActivate = {
                    // Toggle: turning it back off restores the default Box
                    // background (Apple parity) rather than staying stuck on None.
                    val target = if (appearance.backgroundStyle == SubtitleBackgroundStylePreset.None) {
                        SubtitleBackgroundStylePreset.Box
                    } else {
                        SubtitleBackgroundStylePreset.None
                    }
                    onAppearanceChanged(appearance.copy(backgroundStyle = target))
                },
            )

            // Color swatches stay inline — tvOS draws color swatches directly,
            // and a row→dialog of colors would lose the at-a-glance palette.
            StyleSection("Text color") {
                TEXT_COLOR_SWATCHES.forEachIndexed { index, hex ->
                    StyleColorSwatch(
                        hex = hex,
                        label = TvSubtitleAppearanceOptions.fontColorLabel(hex),
                        selected = appearance.fontColor.equals(hex, ignoreCase = true),
                        enabled = enabled,
                        focusRequester = if (index == 0) subtitleTextColorFocus else null,
                        leftFocusRequester = subtitleTrackFocus,
                    ) {
                        onAppearanceChanged(appearance.copy(fontColor = hex))
                    }
                }
            }
            StyleSection("Background color") {
                BACKGROUND_COLOR_SWATCHES.forEachIndexed { index, hex ->
                    StyleColorSwatch(
                        hex = hex,
                        label = TvSubtitleAppearanceOptions.backgroundColorLabel(hex),
                        selected = appearance.backgroundColor.equals(hex, ignoreCase = true),
                        enabled = enabled,
                        focusRequester = if (index == 0) subtitleBackgroundColorFocus else null,
                        leftFocusRequester = subtitleTrackFocus,
                    ) {
                        onAppearanceChanged(appearance.copy(backgroundColor = hex))
                    }
                }
            }
            if (appearance.textOutline) {
                StyleSection("Outline color") {
                    OUTLINE_COLOR_SWATCHES.forEachIndexed { index, hex ->
                        StyleColorSwatch(
                            hex = hex,
                            label = TvSubtitleAppearanceOptions.outlineColorLabel(hex),
                            selected = appearance.textOutlineColor.equals(hex, ignoreCase = true),
                            enabled = enabled,
                            focusRequester = if (index == 0) subtitleOutlineColorFocus else null,
                            leftFocusRequester = subtitleTrackFocus,
                        ) {
                            onAppearanceChanged(appearance.copy(textOutlineColor = hex))
                        }
                    }
                }
            }

            // Android-only sidecar acquisition rows — kept from the old drawer.
            if (onSearchSubtitles != null || onTranslateWithAi != null) {
                Column(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (onSearchSubtitles != null) {
                        HudActionRow(
                            label = "Search subtitles",
                            enabled = enabled,
                            leftFocusRequester = subtitleTrackFocus,
                            onClick = onSearchSubtitles,
                        )
                    }
                    if (onTranslateWithAi != null) {
                        HudActionRow(
                            label = "Translate with AI",
                            enabled = enabled,
                            leftFocusRequester = subtitleTrackFocus,
                            onClick = onTranslateWithAi,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HudSubtitlePreview(
    appearance: SubtitleAppearance,
    modifier: Modifier = Modifier,
) {
    val safe = appearance.sanitized()
    val shape = RoundedCornerShape(6.dp)
    val decoration = TvSubtitleAppearanceOptions.previewDecoration(safe)
    val fontSize = TvSubtitleAppearanceOptions.previewFontSizeSp(safe.fontSize).sp
    val fontFamily = TvSubtitleAppearanceOptions.previewFontFamily(safe.fontFamily)
    val foreground = hexToColor(safe.fontColor)
    val outline = hexToColor(safe.textOutlineColor)
    val backgroundColor = hexToColor(safe.backgroundColor).copy(
        alpha = if (safe.backgroundStyle == SubtitleBackgroundStylePreset.Box) {
            safe.backgroundOpacity.coerceIn(0, 100) / 100f
        } else {
            0f
        },
    )
    val textStyle = MaterialTheme.typography.bodyLarge.copy(
        fontSize = fontSize,
        lineHeight = fontSize * 1.18f,
        fontFamily = fontFamily,
        fontWeight = FontWeight.SemiBold,
        shadow = if (decoration.shadow) {
            Shadow(
                color = outline.copy(alpha = 0.9f),
                offset = Offset(1f, 2f),
                blurRadius = 5f,
            )
        } else {
            null
        },
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 5.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            text = "Example",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(76.dp)
                .clip(shape)
                .background(DarkSurfaceElevated.copy(alpha = 0.72f))
                .padding(horizontal = 10.dp, vertical = 7.dp),
            contentAlignment = TvSubtitleAppearanceOptions.previewAlignment(safe.position),
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(3.dp))
                    .background(backgroundColor)
                    .padding(
                        horizontal = if (safe.backgroundStyle == SubtitleBackgroundStylePreset.Box) 7.dp else 0.dp,
                        vertical = if (safe.backgroundStyle == SubtitleBackgroundStylePreset.Box) 2.dp else 0.dp,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (decoration.outline) {
                    listOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1).forEach { (x, y) ->
                        Text(
                            text = "Subtitle example",
                            color = outline,
                            style = textStyle.copy(shadow = null),
                            maxLines = 1,
                            modifier = Modifier.offset(x.dp, y.dp),
                        )
                    }
                }
                Text(
                    text = "Subtitle example",
                    color = foreground,
                    style = textStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StyleSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.padding(top = 5.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f),
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) { content() }
    }
}

@Composable
private fun StyleColorSwatch(
    hex: String,
    label: String,
    selected: Boolean,
    enabled: Boolean,
    focusRequester: FocusRequester? = null,
    leftFocusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val swatchColor = hexToColor(hex)
    val isLightSwatch = isLightHexColor(hex)
    val checkTint = if (isLightSwatch) Color.Black.copy(alpha = 0.78f) else Color.White
    val ring = when {
        isFocused && isLightSwatch -> Color.Black.copy(alpha = 0.78f)
        isFocused -> Color.White
        selected && isLightSwatch -> Color.Black.copy(alpha = 0.62f)
        selected -> Color.White.copy(alpha = 0.85f)
        else -> Color.White.copy(alpha = 0.25f)
    }
    Box(
        modifier = Modifier
            .size(24.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusProperties {
                if (leftFocusRequester != null) left = leftFocusRequester
            }
            .clip(CircleShape)
            .background(swatchColor)
            .border(width = if (isFocused || selected) 2.dp else 1.dp, color = ring, shape = CircleShape)
            .clickable(enabled = enabled, interactionSource = interactionSource, indication = null) { onClick() }
            .semantics {
                contentDescription = if (selected) "$label, selected" else label
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = checkTint,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

private fun hexToColor(hex: String): Color = try {
    val cleaned = if (hex.startsWith("#")) hex.drop(1) else hex
    Color(0xFF000000.toInt() or (cleaned.toLong(16).toInt() and 0x00FFFFFF))
} catch (_: NumberFormatException) {
    Color.White
}

private fun isLightHexColor(hex: String): Boolean {
    val cleaned = hex.removePrefix("#")
    val value = cleaned.toLongOrNull(16) ?: return false
    val red = ((value shr 16) and 0xFF) / 255.0
    val green = ((value shr 8) and 0xFF) / 255.0
    val blue = (value and 0xFF) / 255.0
    return (red * 0.299 + green * 0.587 + blue * 0.114) > 0.72
}

// Subtitle-appearance option sets are shared with the Settings → Subtitles
// Appearance block via [TvSubtitleAppearanceOptions] so the HUD and Settings
// always offer the identical choices.
private val FONT_SIZES = TvSubtitleAppearanceOptions.FONT_SIZES
private val FONT_FAMILIES = TvSubtitleAppearanceOptions.FONT_FAMILIES
private val BACKGROUND_STYLES = TvSubtitleAppearanceOptions.BACKGROUND_STYLES
private val POSITIONS = TvSubtitleAppearanceOptions.POSITIONS
private val OPACITY_STEPS = TvSubtitleAppearanceOptions.OPACITY_STEPS
private val TEXT_COLOR_SWATCHES = TvSubtitleAppearanceOptions.TEXT_COLOR_SWATCHES
private val BACKGROUND_COLOR_SWATCHES = TvSubtitleAppearanceOptions.BACKGROUND_COLOR_SWATCHES
private val OUTLINE_COLOR_SWATCHES = TvSubtitleAppearanceOptions.OUTLINE_COLOR_SWATCHES

/** Signed delay label — matches the phone's formatDelayMs (true minus sign). */
private fun delayLabel(valueMs: Int): String =
    when {
        valueMs > 0 -> "+$valueMs ms"
        valueMs < 0 -> "−${-valueMs} ms"
        else -> "0 ms"
    }

/**
 * Full-width action row for HUD panes — a true click target: an explicit Select
 * press is required (focus-driven commit would fire dialogs during plain
 * traversal).
 */
@Composable
private fun HudActionRow(
    label: String,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    leftFocusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val bg = if (isFocused) Color.White.copy(alpha = 0.94f) else Color.White.copy(alpha = 0.06f)
    val fg = if (isFocused) Color.Black else Color.White.copy(alpha = 0.86f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusProperties {
                if (leftFocusRequester != null) left = leftFocusRequester
            }
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable(enabled = enabled, interactionSource = interactionSource, indication = null) { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = fg,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.weight(1f),
        )
    }
}

private fun PlayerStatsSnapshot.hasHudRows(): Boolean = hudRows().isNotEmpty()

private fun PlayerStatsSnapshot.hudRows(): List<Pair<String, String>> = buildList {
    backendDisplayName?.let { add("Backend" to it) }
    backendRoute?.let { add("Route" to it) }
    subtitleRendering?.let { add("Subtitles" to it) }
    hardContainers?.let { add("Hard containers" to it) }
    videoCodec?.let { add("Video codec" to it) }
    resolution?.let { add("Resolution" to it) }
    frameRate?.let { add("Frame rate" to String.format(java.util.Locale.ROOT, "%.3f fps", it)) }
    hdrMode?.let { add("HDR mode" to it) }
    videoDecoderName?.let { add("Video decoder" to it) }
    audioCodec?.let { add("Audio codec" to it) }
    audioDecoderName?.let { add("Audio decoder" to it) }
    bitrateBps?.let { add("Bitrate" to formatBitrate(it)) }
    if (droppedFrames > 0) add("Dropped frames" to droppedFrames.toString())
    if (audioUnderruns > 0) add("Audio underruns" to audioUnderruns.toString())
}

private fun formatBitrate(bps: Long): String = when {
    // Locale.ROOT so the decimal separator is a dot everywhere, consistent with
    // the rest of the HUD's formatted numbers on comma-decimal locales.
    bps >= 1_000_000 -> String.format(java.util.Locale.ROOT, "%.1f Mbps", bps / 1_000_000.0)
    bps >= 1_000 -> String.format(java.util.Locale.ROOT, "%.0f Kbps", bps / 1_000.0)
    else -> "$bps bps"
}

/**
 * Empty pane used only for tabs that remain useful when their optional picker
 * data is empty, or for transient Stats rendering if analytics data disappears
 * between tab selection and composition.
 */
@Composable
private fun HudEmptyStatePane(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Chapters pane — renders [VersionChapter]s from the active FileVersion as a
 * focus-driven picker. Selecting a chapter seeks the player to its start time.
 */
@Composable
private fun HudChaptersPane(
    chapters: List<VersionChapter>,
    onSelectChapter: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (chapters.isEmpty()) {
        HudEmptyStatePane("No chapters in this title", modifier)
        return
    }
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 210.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(
            chapters,
            key = { _, chapter -> "${chapter.index}:${chapter.startSeconds}:${chapter.title}" },
            contentType = { _, _ -> "hud-chapter" },
        ) { idx, ch ->
            HudChapterRow(
                chapter = ch,
                onSelect = { onSelectChapter(idx) },
            )
        }
    }
}

@Composable
private fun HudChapterRow(
    chapter: VersionChapter,
    onSelect: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val bg = if (isFocused) Color.White.copy(alpha = 0.12f) else Color.Transparent
    val fg = if (isFocused) Color.White else Color.White.copy(alpha = 0.86f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable(enabled = true, interactionSource = interactionSource, indication = null) { onSelect() }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = formatTime(chapter.startSeconds),
            color = fg.copy(alpha = 0.72f),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = HudBodyTextSize,
                lineHeight = HudBodyLineHeight,
                fontWeight = FontWeight.Medium,
            ),
        )
        Text(
            text = chapter.title.ifBlank { "Chapter ${chapter.index + 1}" },
            color = fg,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = HudBodyTextSize,
                lineHeight = HudBodyLineHeight,
                fontWeight = FontWeight.SemiBold,
            ),
            modifier = Modifier.weight(1f),
        )
    }
}

// ---------------------------------------------------------------------------
// Reusable tvOS row→picker-dialog primitives
// ---------------------------------------------------------------------------

/** An option in a [HudPickerDialog]. [colorHex] draws an inline swatch. */
internal data class HudPickerOption(
    val id: String,
    val label: String,
    val colorHex: String? = null,
)

/**
 * A request to present a centered picker dialog. Built by a pane row and handed
 * to the HUD, which owns the single active-dialog slot.
 */
internal data class HudPickerPresentation(
    val title: String,
    val options: List<HudPickerOption>,
    val selectedId: String,
    val focusedId: String = selectedId,
    val closeOnSelect: Boolean = true,
    val onFocused: (String) -> Unit = {},
    val onSelect: (String) -> Unit,
)

private fun boolPicker(title: String, value: Boolean, onSet: (Boolean) -> Unit): HudPickerPresentation =
    HudPickerPresentation(
        title = title,
        options = listOf(HudPickerOption("on", "On"), HudPickerOption("off", "Off")),
        selectedId = if (value) "on" else "off",
        onSelect = { onSet(it.equals("on", ignoreCase = true)) },
    )

private fun delayPicker(
    title: String,
    current: Int,
    from: Int,
    to: Int,
    step: Int,
    onSet: (Int) -> Unit,
): HudPickerPresentation {
    val values = (generateSequence(from) { it + step }.takeWhile { it <= to } + current)
        .toSortedSet()
    return HudPickerPresentation(
        title = title,
        options = values.map { HudPickerOption(it.toString(), delayLabel(it)) },
        selectedId = current.toString(),
        onSelect = { id -> id.toIntOrNull()?.let(onSet) },
    )
}

/**
 * Drill-in setting row: label + current value + chevron, with an inverted
 * capsule on focus (white fill / dark text). Activating (Select) runs
 * [onActivate], which the caller wires to present a [HudPickerDialog]. Mirrors
 * tvOS `HUDFocusedSettingRow`.
 */
@Composable
internal fun HudFocusedSettingRow(
    label: String,
    value: String,
    enabled: Boolean = true,
    colorHex: String? = null,
    focusRequester: FocusRequester? = null,
    leftFocusRequester: FocusRequester? = null,
    rightFocusRequester: FocusRequester? = null,
    onActivate: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    // Own focus requester so the HUD can return focus to this row after the
    // picker it opens is dismissed (registered via LocalHudPickerReturnFocus).
    val selfFocusRequester = remember { FocusRequester() }
    val registerPickerReturnFocus = LocalHudPickerReturnFocus.current

    val bg = if (isFocused) Color.White else Color.Transparent
    val labelColor = if (isFocused) Color.Black else Color.White
    val valueColor = if (isFocused) Color.Black.copy(alpha = 0.78f) else Color.White.copy(alpha = 0.72f)
    val chevronColor = if (isFocused) Color.Black.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.45f)
    val rowAlpha = if (enabled) 1f else 0.35f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(selfFocusRequester)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusProperties {
                if (leftFocusRequester != null) left = leftFocusRequester
                if (rightFocusRequester != null) right = rightFocusRequester
            }
            .graphicsLayer { alpha = rowAlpha }
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
            ) {
                registerPickerReturnFocus?.invoke(selfFocusRequester)
                onActivate()
            }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = labelColor,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = HudBodyTextSize,
                lineHeight = HudBodyLineHeight,
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(modifier = Modifier.weight(1f))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (colorHex != null) {
                Box(
                    modifier = Modifier
                        .size(11.dp)
                        .clip(CircleShape)
                        .background(hexToColor(colorHex))
                        .border(0.5.dp, Color.White.copy(alpha = 0.45f), CircleShape),
                )
            }
            Text(
                text = value,
                color = valueColor,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = HudBodyTextSize,
                    lineHeight = HudBodyLineHeight,
                    fontWeight = FontWeight.SemiBold,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = chevronColor,
                modifier = Modifier.size(11.dp),
            )
        }
    }
}

/**
 * Centered modal option list mirroring tvOS `HUDPickerDialog`: a dark card with
 * a title and a scrollable list of options; the selected option shows a
 * checkmark, focus auto-lands on the selected option and scrolls it into view,
 * Select commits the option + closes, and Back closes (handled by the HUD's
 * key handler). Each option commits on explicit Select (click), not focus, so
 * D-pad traversal doesn't change the value.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun HudPickerDialog(
    presentation: HudPickerPresentation,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = presentation.options
    val selectedIndex = options.indexOfFirst { it.id == presentation.selectedId }
        .coerceAtLeast(0)
    val focusedIndex = options.indexOfFirst { it.id == presentation.focusedId }
        .takeIf { it >= 0 }
        ?: selectedIndex
    val focusRequester = remember { FocusRequester() }

    // Auto-focus the selected option on appear. Because every option is in the
    // focus graph, Compose's scroll container brings that focused row onscreen.
    LaunchedEffect(presentation.title) {
        runCatching { focusRequester.requestFocus() }
    }

    Box(
        modifier = modifier
            .width(360.dp)
            .heightIn(max = 220.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(DarkSurfaceElevated.copy(alpha = 0.98f))
            .border(0.5.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(14.dp))
            .focusGroup()
            .focusProperties { exit = { FocusRequester.Cancel } }
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = presentation.title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontSize = HudTitleTextSize,
                    lineHeight = HudTitleLineHeight,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            // Fully compose this small modal list so every D-pad destination is
            // present in the focus graph. LazyColumn made below-fold rows look
            // like the end of the modal and either trapped or leaked focus.
            Column(
                modifier = Modifier
                    .heightIn(max = 160.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                options.forEachIndexed { index, option ->
                    key(option.id) {
                        HudPickerOptionRow(
                            option = option,
                            isSelected = index == selectedIndex,
                            focusRequester = if (index == focusedIndex) focusRequester else null,
                            onFocused = { presentation.onFocused(option.id) },
                            onSelect = {
                                presentation.onSelect(option.id)
                                if (presentation.closeOnSelect) onClose()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HudPickerOptionRow(
    option: HudPickerOption,
    isSelected: Boolean,
    focusRequester: FocusRequester?,
    onFocused: () -> Unit,
    onSelect: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val bg = when {
        isFocused -> Color.White
        isSelected -> Color.White.copy(alpha = 0.14f)
        else -> Color.Transparent
    }
    val fg = when {
        isFocused -> Color.Black
        isSelected -> Color.White
        else -> Color.White
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .clickable(interactionSource = interactionSource, indication = null) { onSelect() }
            .semantics { this.selected = isSelected }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (option.colorHex != null) {
            Box(
                modifier = Modifier
                    .size(11.dp)
                    .clip(CircleShape)
                    .background(hexToColor(option.colorHex))
                    .border(0.5.dp, Color.White.copy(alpha = 0.45f), CircleShape),
            )
        }
        Text(
            text = option.label,
            color = fg,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = HudBodyTextSize,
                lineHeight = HudBodyLineHeight,
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (isSelected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Selected",
                tint = fg,
                modifier = Modifier.size(10.dp),
            )
        }
    }
}

private fun formatTime(seconds: Double): String {
    if (seconds <= 0 || seconds.isNaN()) return "0:00"
    val total = seconds.toInt()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
