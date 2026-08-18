package org.siloserver.silo.tv.ui.screens.player

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import org.siloserver.silo.common.player.PlayerStatsSnapshot
import org.siloserver.silo.domain.player.IntroSkipMode
import org.siloserver.silo.tv.R
import org.siloserver.silo.common.player.SleepTimerState
import org.siloserver.silo.model.catalog.VersionChapter
import org.siloserver.silo.model.playback.PlaybackExecutionPlan
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.model.settings.SubtitleAppearance
import org.siloserver.silo.model.settings.SubtitleBackgroundStylePreset
import org.siloserver.silo.model.settings.SubtitleFontSizePreset
import org.siloserver.silo.model.settings.SubtitlePositionPreset
import org.siloserver.silo.tv.ui.focus.TvContentInitialFocusMaxAttempts
import org.siloserver.silo.tv.ui.focus.TvFocusLog
import org.siloserver.silo.tv.ui.focus.TvFrameRelocationMaxAttempts
import org.siloserver.silo.tv.ui.focus.rememberTvContentInitialFocus
import org.siloserver.silo.tv.ui.focus.requestFocusUntilObserved
import org.siloserver.silo.tv.ui.theme.DarkSurfaceElevated

// Geometry follows tvOS TVPlayerInfoHUD at the 0.5x point→dp map, adjusted for
// Android's larger body type: the card is WIDE and SHORT (tvOS 1100×380pt on a
// 1920×1080 canvas — 57% × 35%), with the tab rail floating above it on the
// video rather than inside it. The previous card was 51% × 67% as rendered — a
// portrait slab on a landscape screen, sitting on faces.
private val HudWidthFraction = 0.74f
private val HudMaxWidth = 720.dp
/**
 * Card height wraps the pane between these bounds. A fixed 360dp height left
 * Audio (two rows) and Stats (nine) as the same 60%-empty slab; wrapping lets
 * a two-row pane be a two-row card. The max keeps long panes scrolling inside
 * the card rather than growing it down over the transport.
 */
private val HudCardMinHeight = 156.dp
private val HudCardMaxHeight = 300.dp
private val HudPanelCorner = 16.dp
private val HudPanelPadding = 20.dp
private val HudTabCardGap = 12.dp
private val HudTabHeight = 38.dp
private val HudPaneBottomPadding = 4.dp
private val HudPaneColumnGap = 36.dp
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
@OptIn(ExperimentalComposeUiApi::class) // focusProperties enter/exit
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
    desiredAudioOrdinal: Int? = null,
    desiredAudioConfirmed: Boolean = false,
    videoFillMode: VideoFillMode,
    onSelectAudio: (Int) -> Unit,
    onSelectVideoQuality: (String) -> Unit,
    onVideoFillModeChanged: (VideoFillMode) -> Unit,
    playbackSpeed: Double,
    onPlaybackSpeedChanged: (Double) -> Unit,
    sleepTimerState: SleepTimerState,
    onStartSleepTimer: (Int) -> Unit,
    onCancelSleepTimer: () -> Unit,
    introSkipMode: IntroSkipMode,
    onIntroSkipModeChanged: (IntroSkipMode) -> Unit,
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
    /** True while a DV toggle's in-place session restart is still pending. */
    dolbyVisionSwitchInFlight: Boolean = false,
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

    // The pane's entry point: each pane attaches this to its first focusable
    // row, and the card's custom `enter` sends a Down from the rail there.
    // Only one pane is composed at a time, so one requester serves them all.
    val paneEntryFocus = remember { FocusRequester() }
    val activeVersion = fileVersions.firstOrNull { it.fileId == selectedFileId }
        ?: fileVersions.firstOrNull()
    // Whether the selected pane has a row the entry requester is attached to.
    // Redirecting `enter` to an unattached requester cancels the move (and logs
    // a Compose warning), so read-only panes and an all-disabled Audio pane
    // fall back to the default search instead.
    val paneEntryAvailable = when (selectedTab) {
        HudTab.Video, HudTab.Subtitles -> true
        HudTab.Audio -> activeVersion?.audioTracks.orEmpty().size > 1 || audioDelayEnabled
        HudTab.Chapters -> chapters.isNotEmpty()
        HudTab.Info, HudTab.Stats -> false
    }

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

    var hudHasFocus by remember { mutableStateOf(false) }

    // Seed focus on the active tab pill when the HUD first appears.
    LaunchedEffect(Unit) {
        tabFocusRequesters[selectedTab]?.let { requester ->
            val claimed = requestFocusUntilObserved(
                maxAttempts = TvContentInitialFocusMaxAttempts,
                awaitAttempt = { withFrameNanos { } },
                requestFocus = requester::requestFocus,
                isFocused = { hudHasFocus },
            )
            TvFocusLog.d { "hud initial focus claim tab=$selectedTab claimed=$claimed" }
        }
    }
    LaunchedEffect(hudHasFocus) {
        TvFocusLog.d { "hud hasFocus=$hudHasFocus" }
    }

    // When a picker closes, return focus to the setting row that opened it rather
    // than the tab pill, so the user doesn't have to re-traverse the pane after
    // every picker interaction. Falls back to the tab pill if no row was recorded.
    LaunchedEffect(activePicker) {
        if (activePicker == null) {
            val target = pickerReturnFocus.value
            if (target != null) {
                pickerReturnFocus.value = null
                // Relocation: the picker has closed and focus is coming back to
                // the row that opened it, which is being recomposed underneath.
                requestFocusUntilObserved(
                    maxAttempts = TvFrameRelocationMaxAttempts,
                    awaitAttempt = { withFrameNanos { } },
                    requestFocus = target::requestFocus,
                    isFocused = { hudHasFocus },
                )
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

    // Top-center: a floating tab rail over the video, and a card beneath it
    // holding only the pane — the TVPlayerInfoHUD composition. No full-screen
    // scrim; the picture stays visible.
    //
    // fillMaxWidth BEFORE widthIn. Chained the other way round, fillMaxWidth
    // sees the already-capped max and takes its fraction of THAT: 0.72 × 680 =
    // 490dp, which is what actually rendered — narrow enough to clip the tab
    // rail ("Chap…") and cramp every two-column pane.
    Box(
        modifier = modifier
            .onFocusChanged { hudHasFocus = it.hasFocus }
            .fillMaxWidth(HudWidthFraction)
            .widthIn(max = HudMaxWidth)
            .onPreviewKeyEvent { ev ->
                if (ev.key != Key.Back && ev.key != Key.Escape) return@onPreviewKeyEvent false
                TvFocusLog.d { "hud key BACK type=${ev.type} picker=${activePicker != null}" }
                when (ev.type) {
                    // Consume the DOWN too, not just the UP. Compose maps an
                    // unconsumed Back/Escape KeyDown to FocusDirection.Exit
                    // (FocusInteropUtils.toFocusDirection) and the root
                    // AndroidComposeView runs a focus search on it — which
                    // moves focus out of the HUD before the UP arrives. Key
                    // events only route to the focused subtree, so the UP then
                    // never reached this handler: the panel stayed up with no
                    // focused pill, and it took a second press (unconsumed →
                    // onBackPressed → BackHandler) to close it.
                    KeyEventType.KeyDown -> true
                    KeyEventType.KeyUp -> {
                        // Pre-Android-16 remote and keyboard fallback. System
                        // Back uses the callbacks above and on TvPlayerScreen.
                        if (activePicker != null) {
                            activePicker = null
                        } else {
                            TvFocusLog.d { "hud key BACK -> onDismiss" }
                            onDismiss()
                        }
                        true
                    }
                    else -> false
                }
            },
    ) {
        CompositionLocalProvider(LocalHudPickerReturnFocus provides registerPickerReturnFocus) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { alpha = if (activePicker != null) 0.28f else 1f },
            verticalArrangement = Arrangement.spacedBy(HudTabCardGap),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Floating tab rail. Centred like the tvOS HStack; the scroll is a
            // safety net for very long localised labels — at this width the six
            // English tabs fit with room.
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
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

            // The card: wraps its pane between the height bounds, so a
            // two-row Audio pane is a two-row card and a nine-row Stats pane
            // scrolls inside a full one. Shadow sits outside the clip.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = HudCardMinHeight, max = HudCardMaxHeight)
                    .animateContentSize(animationSpec = tween(160))
                    // The card is a focus group so the rail↔pane hand-offs are
                    // deliberate rather than geometric. Compose picks a 2D
                    // candidate first and only then consults these on the
                    // groups being entered/left, so both redirects apply to
                    // every row regardless of which control was "nearest".
                    .focusProperties {
                        // Down from a pill lands on the pane's FIRST row — the
                        // top-left control — not whichever swatch or row happens
                        // to sit under that pill.
                        enter = { direction ->
                            if (direction == FocusDirection.Down && paneEntryAvailable) {
                                paneEntryFocus
                            } else {
                                FocusRequester.Default
                            }
                        }
                        // Up out of the pane returns to the SELECTED pill. With
                        // focus-driven selection, the nearest pill would switch
                        // panes as a side effect of leaving (tvOS: defaultFocus
                        // on activeTab, for the same reason).
                        exit = { direction ->
                            if (direction == FocusDirection.Up) {
                                tabFocusRequesters[selectedTab] ?: FocusRequester.Default
                            } else {
                                FocusRequester.Default
                            }
                        }
                    }
                    .focusGroup()
                    .shadow(
                        elevation = 14.dp,
                        shape = RoundedCornerShape(HudPanelCorner),
                        ambientColor = Color.Black.copy(alpha = 0.6f),
                        spotColor = Color.Black.copy(alpha = 0.6f),
                    )
                    .clip(RoundedCornerShape(HudPanelCorner))
                    // Near-opaque. The picture showing through the card read as
                    // "glass" but cost legibility over bright or busy frames —
                    // and this is a settings surface people squint at from the
                    // sofa. Keep the video visible AROUND the card, not through it.
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
                    .border(
                        width = 0.5.dp,
                        color = Color.White.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(HudPanelCorner),
                    )
                    .padding(HudPanelPadding),
            ) {
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
                            subtitleLabel = subtitlePresentation.rows
                                .firstOrNull { row -> row.checked }
                                ?.label
                                ?: "Off",
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
                        dolbyVisionSwitchInFlight = dolbyVisionSwitchInFlight,
                        fillMode = videoFillMode,
                        onFillModeChanged = onVideoFillModeChanged,
                        playbackSpeed = playbackSpeed,
                        onPlaybackSpeedChanged = onPlaybackSpeedChanged,
                        sleepTimerState = sleepTimerState,
                        onStartSleepTimer = onStartSleepTimer,
                        onCancelSleepTimer = onCancelSleepTimer,
                        introSkipMode = introSkipMode,
                        onIntroSkipModeChanged = onIntroSkipModeChanged,
                        autoPlayNext = autoPlayNext,
                        onAutoPlayNextChanged = onAutoPlayNextChanged,
                        entryFocusRequester = paneEntryFocus,
                        enabled = activePicker == null,
                        onPresentPicker = presentPicker,
                    )
                    HudTab.Audio -> HudAudioPane(
                        audioTracks = audioTracks,
                        // The catalog decides WHICH tracks exist. Media3 only
                        // shows what this stream delivered, which a transcode
                        // collapses to one -- that disabled the row outright and
                        // made audio unswitchable for the whole session.
                        activeVersion = activeVersion,
                        // A locally-confirmed choice is the viewer's answer;
                        // the plan only names what the server last delivered.
                        planAudioOrdinal = desiredAudioOrdinal
                            ?: playbackPlan?.selectedTracks?.audioIndex,
                        // Only an unconfirmed intent renders as pending.
                        pendingLocalAudioOrdinal = desiredAudioOrdinal
                            ?.takeUnless { desiredAudioConfirmed },
                        onSelectAudio = onSelectAudio,
                        audioDelayMs = audioDelayMs,
                        audioDelayEnabled = audioDelayEnabled,
                        onAudioDelayChanged = onAudioDelayChanged,
                        stats = stats,
                        entryFocusRequester = paneEntryFocus,
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
                        entryFocusRequester = paneEntryFocus,
                        enabled = activePicker == null,
                        onPresentPicker = presentPicker,
                    )
                    HudTab.Chapters -> HudPaneViewport {
                        HudChaptersPane(
                            chapters = chapters,
                            onSelectChapter = onSelectChapter,
                            entryFocusRequester = paneEntryFocus,
                        )
                    }
                }
            }
        }
        }

        // Centered modal picker dialog, drawn on top of the dimmed rail + card.
        // matchParentSize, not fillMaxSize: the HUD box now wraps its content,
        // so a fillMaxSize child would see an unbounded height and not stretch.
        val picker = activePicker
        if (picker != null) {
            Box(
                modifier = Modifier.matchParentSize(),
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

    // The rail floats on the video, so an idle pill needs its own ground:
    // tvOS HUDTabPillBody — black@0.45 fill with a white@0.18 hairline idle;
    // solid white when selected; white@0.9 when merely focused. A white@0.06
    // fill (the old idle) vanishes over a bright frame.
    //
    // Selection follows focus, so a focused pill is always the selected one.
    // A selected pill that is NOT focused means focus is down in the pane —
    // it dims to a marker so the one solid-white element on screen is the
    // control you're actually on. (Deliberate departure from tvOS, which
    // keeps the selected pill white throughout.)
    val bg = when {
        isFocused -> Color.White
        isSelected -> Color.White.copy(alpha = 0.22f)
        // Firmer than tvOS's black@0.45: white type on 0.45 loses contrast
        // over a bright frame, and the rail has no card behind it.
        else -> Color.Black.copy(alpha = 0.62f)
    }
    val fg = if (isFocused) Color.Black else Color.White
    val stroke = if (isFocused || isSelected) Color.Transparent else Color.White.copy(alpha = 0.18f)
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.0f else 0.96f,
        animationSpec = tween(120),
        label = "hudTabScale",
    )

    Box(
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .height(HudTabHeight)
            .clip(RoundedCornerShape(50))
            .background(bg)
            .border(width = 0.5.dp, color = stroke, shape = RoundedCornerShape(50))
            .focusRequester(focusRequester)
            .focusable(enabled = enabled, interactionSource = interactionSource)
            .padding(horizontal = 18.dp),
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
            .fillMaxWidth()
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
    subtitleLabel: String,
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
        // Names people know, not shouted mimes: "H.264" rather than
        // "AVC1.640029", "DTS-HD" rather than "AUDIO/VND.DTS.HD". Stats keeps
        // the raw strings for anyone who needs them.
        videoCodecShortName(stats.videoCodec)?.let { add("Video" to it) }
        audioFormatShortName(stats.audioCodec)?.let { add("Audio" to it) }
        // The adapter's COMMITTED identity, exactly as the Subtitles tab reads
        // it. This used to ask Media3 which text track was selected, which is a
        // different authority — so the two tabs could and did disagree.
        add("Subtitles" to subtitleLabel)
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

/** Media3 video codec ids / mimes → the short names users know. */
private fun videoCodecShortName(codecOrMime: String?): String? {
    val raw = codecOrMime?.trim()?.lowercase(java.util.Locale.US)?.takeIf { it.isNotBlank() } ?: return null
    val id = raw.substringAfterLast('/')
    return when {
        id.startsWith("avc") || id == "h264" -> "H.264"
        id.startsWith("hev") || id.startsWith("hvc") || id == "hevc" || id == "h265" -> "HEVC"
        id.startsWith("dvh") || id.startsWith("dva") -> "Dolby Vision"
        id.startsWith("av01") || id == "av1" -> "AV1"
        id.startsWith("vp09") || id == "vp9" || id == "x-vnd.on2.vp9" -> "VP9"
        id.startsWith("vp08") || id == "vp8" -> "VP8"
        id.startsWith("mp4v") || id == "mpeg4" -> "MPEG-4"
        id == "mpeg2" || id == "mpeg2video" -> "MPEG-2"
        else -> id.substringBefore('.').uppercase(java.util.Locale.US).take(12)
    }
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
        // Fixed gap, weighted value — same reasoning as HudFocusedSettingRow: a
        // lone weighted spacer collapses to 0dp once the two texts fill the row,
        // which is how "SubtitlesArabic — SRT · Exter…" rendered.
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = HudBodyTextSize,
                lineHeight = HudBodyLineHeight,
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = HudBodyTextSize,
                lineHeight = HudBodyLineHeight,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
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

    // Two columns, filled top-to-bottom then across, so nine rows read as a
    // 5+4 grid instead of a single column stretched over the full card width
    // with each value 500dp from its label.
    val split = (rows.size + 1) / 2
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(HudPaneColumnGap),
    ) {
        listOf(rows.take(split), rows.drop(split)).forEach { column ->
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                column.forEach { (label, value) -> LabelValueRow(label = label, value = value) }
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
    dolbyVisionSwitchInFlight: Boolean,
    fillMode: VideoFillMode,
    onFillModeChanged: (VideoFillMode) -> Unit,
    playbackSpeed: Double,
    onPlaybackSpeedChanged: (Double) -> Unit,
    sleepTimerState: SleepTimerState,
    onStartSleepTimer: (Int) -> Unit,
    onCancelSleepTimer: () -> Unit,
    introSkipMode: IntroSkipMode,
    onIntroSkipModeChanged: (IntroSkipMode) -> Unit,
    autoPlayNext: Boolean,
    onAutoPlayNextChanged: (Boolean) -> Unit,
    entryFocusRequester: FocusRequester,
    enabled: Boolean,
    onPresentPicker: (HudPickerPresentation) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Which row carries the pane's entry requester: the first row that is
    // actually focusable. A disabled row is not focusable, so pointing the
    // requester at it would cancel the move in from the rail.
    val hasVersionRow = fileVersions.size > 1
    val hasQualityRow = videoQualities.size > 1
    val entryRow = when {
        hasVersionRow -> "version"
        hasQualityRow -> "quality"
        else -> "speed"
    }
    Row(
        modifier = modifier.fillMaxWidth(),
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
                        entryFocusRequester = entryFocusRequester.takeIf { entryRow == "version" },
                        onActivate = {
                            onPresentPicker(
                                HudPickerPresentation(
                                    title = "Version",
                                    // Disambiguated as a set: two 4K DV files
                                    // otherwise render as two identical rows.
                                    options = org.siloserver.silo.tv.ui.screens.detail
                                        .TvPlaybackFormatting.versionPickerLabels(fileVersions)
                                        .mapIndexed { index, label ->
                                            HudPickerOption(
                                                id = fileVersions[index].fileId.toString(),
                                                label = label,
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
                    entryFocusRequester = entryFocusRequester.takeIf { entryRow == "quality" },
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
                    entryFocusRequester = entryFocusRequester.takeIf { entryRow == "speed" },
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
            }
        }

        // Right column: what the device does with the picture, then what the
        // player does on its own. Previously the left column carried eight
        // rows against a lone Sleep timer here — the pane scrolled while
        // half the card sat empty.
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PaneColumn("Output") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    HudFocusedSettingRow(
                        label = "HDR passthrough",
                        value = onOffLabel(hdrEnabled),
                        enabled = enabled,
                        showsChevron = false,
                        onActivate = { onHdrEnabledChanged(!hdrEnabled) },
                    )

                    // Off plays DV sources as their base layer (HDR10) — some
                    // users prefer HDR10 even on DV-capable displays. Profile 5
                    // always plays as DV (no watchable base layer); applies from
                    // the next playback start. Apple parity (silo-apple e9bd775).
                    HudFocusedSettingRow(
                        label = "Dolby Vision",
                        // A toggle on a DV file restarts the session so the
                        // server can re-plan the layer; say so on the row (the
                        // subtitle track row's idiom) and swallow presses until
                        // the replacement is playing, so a second press can't
                        // queue a second restart behind the first. Swallow, not
                        // disable: a disabled row is not focusable, and taking
                        // focus off the row the viewer just pressed left the
                        // next press landing on nothing.
                        value = if (dolbyVisionSwitchInFlight) {
                            "${onOffLabel(dolbyVisionEnabled)} · Applying…"
                        } else {
                            onOffLabel(dolbyVisionEnabled)
                        },
                        enabled = enabled,
                        showsChevron = false,
                        onActivate = {
                            if (!dolbyVisionSwitchInFlight) {
                                onDolbyVisionEnabledChanged(!dolbyVisionEnabled)
                            }
                        },
                    )
                }
            }

            PaneColumn("Automation") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Three values, so Select cycles rather than toggles —
                    // the same one-press shape as the rows around it, without
                    // a picker sheet over the picture. Settings has the list.
                    HudFocusedSettingRow(
                        label = stringResource(R.string.settings_intro_skip_title),
                        value = stringResource(introSkipModeLabel(introSkipMode)),
                        enabled = enabled,
                        showsChevron = false,
                        onActivate = { onIntroSkipModeChanged(introSkipMode.next()) },
                    )

                    HudFocusedSettingRow(
                        label = "Auto-play next",
                        value = onOffLabel(autoPlayNext),
                        enabled = enabled,
                        showsChevron = false,
                        onActivate = { onAutoPlayNextChanged(!autoPlayNext) },
                    )

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
    activeVersion: org.siloserver.silo.model.catalog.FileVersion?,
    planAudioOrdinal: Int?,
    pendingLocalAudioOrdinal: Int?,
    onSelectAudio: (Int) -> Unit,
    audioDelayMs: Int,
    audioDelayEnabled: Boolean,
    onAudioDelayChanged: (Int) -> Unit,
    stats: PlayerStatsSnapshot,
    entryFocusRequester: FocusRequester,
    enabled: Boolean,
    onPresentPicker: (HudPickerPresentation) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Two columns like every other pane. A lone full-width column put the
    // value 500dp from its label — "Audio track ……… English · DTS · 5.1" —
    // and left the card two-thirds empty. The right column is read-only
    // output facts the viewer would otherwise have to dig out of Stats.
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(HudPaneColumnGap),
    ) {
        PaneColumn(
            "Track",
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val selectedTrack = audioTracks.firstOrNull { it.isSelected }
                val catalogAudio = activeVersion?.audioTracks.orEmpty()
                val formatting = org.siloserver.silo.tv.ui.screens.detail.TvPlaybackFormatting
                val effectiveOrdinal = formatting.effectiveAudioOrdinal(
                    tracks = catalogAudio,
                    planOrdinal = planAudioOrdinal,
                    version = activeVersion,
                )
                // Entry lands on the first row that can take focus: the track
                // row when there is a choice, else the delay row when PCM.
                val trackSelectable = catalogAudio.size > 1
                HudFocusedSettingRow(
                    label = "Audio track",
                    entryFocusRequester = entryFocusRequester.takeIf { trackSelectable },
                    // SOURCE identity, from the catalog row the plan selected.
                    // The mounted Media3 track is the delivered representation,
                    // so a transcode showed "UND AAC Stereo" for what every
                    // other surface calls "English · DTS · 5.1". Media3 is only
                    // the fallback when there is no catalog audio metadata.
                    //
                    // A request in flight shows the requested track as pending
                    // rather than as fact: the row must not claim Dutch before
                    // the player confirms it actually switched.
                    value = pendingLocalAudioOrdinal
                        ?.let { pending ->
                            formatting.audioSummaryForOrdinal(
                                version = activeVersion,
                                ordinal = pending,
                                tracks = catalogAudio,
                            )?.let { "$it …" }
                        }
                        ?: formatting.audioSummaryForOrdinal(
                            version = activeVersion,
                            ordinal = effectiveOrdinal,
                            tracks = catalogAudio,
                        )
                        ?: selectedTrack?.let { audioChoiceLabel(it, audioTracks.indexOf(it)) }
                        ?: "Default",
                    // Gated on the CATALOG, not on what this stream delivered.
                    enabled = enabled && catalogAudio.size > 1,
                    onActivate = {
                        onPresentPicker(
                            HudPickerPresentation(
                                title = "Audio Track",
                                // Ids are catalog ordinals, the server's audio
                                // contract, so an undelivered row stays
                                // selectable and survives the round trip.
                                options = catalogAudio.indices.map { ordinal ->
                                    HudPickerOption(
                                        id = ordinal.toString(),
                                        label = formatting.audioChoiceLabelForOrdinal(catalogAudio, ordinal)
                                            ?: "Track ${ordinal + 1}",
                                    )
                                },
                                selectedId = effectiveOrdinal?.toString().orEmpty(),
                                onSelect = { id -> id.toIntOrNull()?.let(onSelectAudio) },
                            ),
                        )
                    },
                )

                HudFocusedSettingRow(
                    label = "Delay (PCM only)",
                    // Output → Mode says why; the row itself just says it can't.
                    value = if (audioDelayEnabled) delayLabel(audioDelayMs) else "Unavailable",
                    enabled = enabled && audioDelayEnabled,
                    entryFocusRequester = entryFocusRequester.takeIf { !trackSelectable && audioDelayEnabled },
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

        // Output — what the device is actually doing with the track. Mode is
        // the fact behind the delay row's "Unavailable during passthrough":
        // bitstream passthrough hands the codec to the receiver untouched, so
        // there is no PCM to delay.
        val outputRows = buildList<Pair<String, String>> {
            audioFormatShortName(stats.audioCodec)?.let { add("Codec" to it) }
            add("Mode" to if (audioDelayEnabled) "Decoded to PCM" else "Passthrough")
            stats.audioDecoderName
                ?.takeIf { audioDelayEnabled }
                ?.let { add("Decoder" to it.removePrefix("OMX.").removePrefix("c2.")) }
        }
        PaneColumn(
            "Output",
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                outputRows.forEach { (label, value) ->
                    // Same row metrics as the setting rows on the left, so the
                    // two columns rule up; not focusable, nothing to open.
                    HudReadOnlyRow(label = label, value = value)
                }
            }
        }
    }
}

/**
 * A label/value row on the setting-row grid — same padding and type as
 * [HudFocusedSettingRow], no focus, no chevron. For facts that sit beside
 * settings and should line up with them.
 */
@Composable
private fun HudReadOnlyRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = HudBodyTextSize,
                lineHeight = HudBodyLineHeight,
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            color = Color.White.copy(alpha = 0.72f),
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = HudBodyTextSize,
                lineHeight = HudBodyLineHeight,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
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
    entryFocusRequester: FocusRequester,
    enabled: Boolean,
    onPresentPicker: (HudPickerPresentation) -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { onPaneShown() }

    // Image (PGS/DVB) and burned-in tracks ignore most of the appearance block —
    // say so instead of offering rows that silently do nothing.
    val applicability = tvSubtitleAppearanceApplicability(
        presentation.rows.firstOrNull { row -> row.checked }?.identity,
    )
    val geometryEnabled = enabled && applicability.geometryApplies
    val stylingEnabled = enabled && applicability.stylingApplies

    val subtitleTrackFocus = remember { FocusRequester() }
    val subtitleTextColorFocus = remember { FocusRequester() }
    val subtitleBackgroundColorFocus = remember { FocusRequester() }
    val subtitleOutlineColorFocus = remember { FocusRequester() }

    Row(
        modifier = modifier.fillMaxWidth(),
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
                    entryFocusRequester = entryFocusRequester,
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
                    enabled = geometryEnabled,
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
                    enabled = stylingEnabled,
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
                    enabled = stylingEnabled,
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
                    enabled = stylingEnabled,
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
                    enabled = stylingEnabled,
                    // The outline-color swatch (subtitleOutlineColorFocus) is only
                    // composed when textOutline is on. Right-nav must not target a
                    // detached requester when it's off, so gate the target on it.
                    rightFocusRequester = subtitleOutlineColorFocus.takeIf { appearance.textOutline },
                    showsChevron = false,
                    onActivate = { onAppearanceChanged(appearance.copy(textOutline = !appearance.textOutline)) },
                )

                HudFocusedSettingRow(
                    label = "Position",
                    value = POSITIONS.firstOrNull { it.first == appearance.position }?.second
                        ?: appearance.position.name,
                    enabled = geometryEnabled,
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

                applicability.note?.let { note ->
                    Text(
                        text = note,
                        color = Color.White.copy(alpha = 0.62f),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
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

            // There is deliberately no "No background" toggle here. It was a
            // second control over backgroundStyle, which the Background picker
            // in the left column already exposes as "No background" — and
            // toggling it off could not know what the style had been, so it
            // hard-coded Box and silently destroyed the user's choice
            // (Drop Shadow -> On -> Off left you on Box, persisted immediately).
            // tvOS has no such toggle either: TVPlayerInfoHUD offers a single
            // Style picker plus a Background color row.

            // Color swatches stay inline — tvOS draws color swatches directly,
            // and a row→dialog of colors would lose the at-a-glance palette.
            StyleSection("Text color", dimmed = !applicability.stylingApplies) {
                TEXT_COLOR_SWATCHES.forEachIndexed { index, hex ->
                    StyleColorSwatch(
                        hex = hex,
                        label = TvSubtitleAppearanceOptions.fontColorLabel(hex),
                        selected = appearance.fontColor.equals(hex, ignoreCase = true),
                        enabled = stylingEnabled,
                        focusRequester = if (index == 0) subtitleTextColorFocus else null,
                        leftFocusRequester = subtitleTrackFocus,
                    ) {
                        onAppearanceChanged(appearance.copy(fontColor = hex))
                    }
                }
            }
            StyleSection("Background color", dimmed = !applicability.stylingApplies) {
                BACKGROUND_COLOR_SWATCHES.forEachIndexed { index, hex ->
                    StyleColorSwatch(
                        hex = hex,
                        label = TvSubtitleAppearanceOptions.backgroundColorLabel(hex),
                        selected = appearance.backgroundColor.equals(hex, ignoreCase = true),
                        enabled = stylingEnabled,
                        focusRequester = if (index == 0) subtitleBackgroundColorFocus else null,
                        leftFocusRequester = subtitleTrackFocus,
                    ) {
                        onAppearanceChanged(appearance.copy(backgroundColor = hex))
                    }
                }
            }
            if (appearance.textOutline) {
                StyleSection("Outline color", dimmed = !applicability.stylingApplies) {
                    OUTLINE_COLOR_SWATCHES.forEachIndexed { index, hex ->
                        StyleColorSwatch(
                            hex = hex,
                            label = TvSubtitleAppearanceOptions.outlineColorLabel(hex),
                            selected = appearance.textOutlineColor.equals(hex, ignoreCase = true),
                            enabled = stylingEnabled,
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
private fun StyleSection(
    title: String,
    dimmed: Boolean = false,
    content: @Composable () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier
            .padding(top = 5.dp)
            // Same 0.35 alpha HudFocusedSettingRow uses for a disabled row.
            .graphicsLayer { alpha = if (dimmed) 0.35f else 1f },
    ) {
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
    // NOT the media bitrate: this is Media3's onBandwidthEstimate value, i.e.
    // measured network throughput. Labelling it "Bitrate" read as a ~19 Mbps
    // stream reporting 151.3 Mbps on a fast LAN, which is actively misleading
    // in a panel whose whole job is diagnosing playback.
    bitrateBps?.let { add("Estimated bandwidth" to formatBitrate(it)) }
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
            .fillMaxWidth()
            .heightIn(min = HudCardMinHeight - HudPanelPadding * 2)
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
    entryFocusRequester: FocusRequester,
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
                focusRequester = entryFocusRequester.takeIf { idx == 0 },
            )
        }
    }
}

@Composable
private fun HudChapterRow(
    chapter: VersionChapter,
    onSelect: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val bg = if (isFocused) Color.White.copy(alpha = 0.12f) else Color.Transparent
    val fg = if (isFocused) Color.White else Color.White.copy(alpha = 0.86f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
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
    /**
     * A second requester for the same row — the pane's entry point, which the
     * HUD card's custom `enter` routes a Down from the rail to. Separate from
     * [focusRequester] so a pane can keep its own handle on the row too.
     */
    entryFocusRequester: FocusRequester? = null,
    leftFocusRequester: FocusRequester? = null,
    rightFocusRequester: FocusRequester? = null,
    /**
     * False for a toggle row: Select flips the value in place, so there is no
     * drill-in to advertise. Mirrors tvOS HUDToggleRow (showsChevron: false).
     */
    showsChevron: Boolean = true,
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
            .then(if (entryFocusRequester != null) Modifier.focusRequester(entryFocusRequester) else Modifier)
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
        // A single weighted spacer used to be the only thing between label and
        // value. Compose measures unweighted children first, so once the two
        // texts filled the row that spacer resolved to 0dp and they abutted
        // ("BackgroundNo background", "SubtitlesDanish — SRT · E…").
        //
        // Now the gap is fixed and unconditional, and the value region carries
        // the weight: it still right-aligns, but it is the side that gives way
        // and ellipsizes when the row is cramped.
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
        Spacer(modifier = Modifier.width(12.dp))
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.End),
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
            // Weighted so the swatch and chevron are measured first and the
            // value is what gives way. Unweighted, a long value consumes the
            // width and squeezes the trailing chevron toward zero.
            // fill = false keeps short values grouped against the right edge.
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
                modifier = Modifier.weight(1f, fill = false),
            )
            if (showsChevron) {
                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = chevronColor,
                    modifier = Modifier.size(11.dp),
                )
            }
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
    val optionFocusModifier = rememberTvContentInitialFocus(
        target = focusRequester,
        contentKey = presentation.title,
    )

    Box(
        modifier = modifier
            .then(optionFocusModifier)
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
            // present in the focus graph. A lazy list made below-fold rows look
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
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
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
            .bringIntoViewRequester(bringIntoViewRequester)
            .onFocusChanged { state ->
                if (state.isFocused) {
                    onFocused()
                    scope.launch { bringIntoViewRequester.bringIntoView() }
                }
            }
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

/** The label each intro-skip mode is offered under; the copy is contract-fixed. */
@StringRes
private fun introSkipModeLabel(mode: IntroSkipMode): Int = when (mode) {
    IntroSkipMode.NEVER -> R.string.settings_intro_skip_never
    IntroSkipMode.ASK -> R.string.settings_intro_skip_ask
    IntroSkipMode.ALWAYS -> R.string.settings_intro_skip_always
}

/** Declaration order, wrapping: never -> ask -> always -> never. */
private fun IntroSkipMode.next(): IntroSkipMode =
    IntroSkipMode.entries[(ordinal + 1) % IntroSkipMode.entries.size]
