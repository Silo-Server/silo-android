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
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import org.siloserver.silo.common.player.PlayerStatsSnapshot
import org.siloserver.silo.common.player.SleepTimerState
import org.siloserver.silo.domain.player.IntroSkipMode
import org.siloserver.silo.model.catalog.VersionChapter
import org.siloserver.silo.model.playback.PlaybackExecutionPlan
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.model.playback.SubtitleIdentity
import org.siloserver.silo.model.settings.SubtitleAppearance
import org.siloserver.silo.model.settings.SubtitleBackgroundStylePreset
import org.siloserver.silo.playback.SubtitleTimingActions
import org.siloserver.silo.tv.R
import org.siloserver.silo.tv.ui.components.tvDialogSurface
import org.siloserver.silo.tv.ui.focus.TvContentInitialFocusMaxAttempts
import org.siloserver.silo.tv.ui.focus.TvFocusLog
import org.siloserver.silo.tv.ui.focus.TvFrameRelocationMaxAttempts
import org.siloserver.silo.tv.ui.focus.rememberTvContentInitialFocus
import org.siloserver.silo.tv.ui.focus.requestFocusUntilObserved

// Geometry follows the options mockup on a 960×540dp screen: a capsule tab
// rail 24dp from the top, and a smoked panel 10dp under it, 780dp (81%) wide
// with a 22dp radius. The panel stays wide and short, as tvOS TVPlayerInfoHUD
// does, so it sits above faces rather than on them.
private val HudWidthFraction = 0.8125f
private val HudMaxWidth = 780.dp
/**
 * Card height wraps the pane between these bounds. A fixed height left Audio
 * (two rows) and Stats (nine) as the same mostly-empty slab; wrapping lets a
 * two-row pane be a two-row card. The max keeps long panes scrolling inside
 * the card rather than growing it down over the picture.
 */
private val HudCardMinHeight = 156.dp
private val HudCardMaxHeight = 336.dp
private val HudPanelCorner = 22.dp
private val HudPanelPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 20.dp, bottom = 18.dp)
private val HudRailTop = 24.dp
private val HudTabCardGap = 10.dp
private val HudTabHeight = 32.dp
private val HudRowHeight = 34.dp
private val HudRowCorner = 10.dp
private val HudRowPadding = 10.dp
private val HudCheckColumn = 20.dp
private val HudPaneColumnGap = 26.dp
private val HudSectionGap = 12.dp

/** The left column of a two-column pane takes 330 of 736dp, as in the mockup. */
private const val HudLeftColumnWeight = 33f
private const val HudRightColumnWeight = 38f

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
    /** False when the server is known to discard subtitle text opacity. */
    subtitleTextOpacitySupported: Boolean = true,
    onSubtitlesPaneShown: () -> Unit,
    onSearchSubtitles: (() -> Unit)?,
    onTranslateWithAi: (() -> Unit)?,
    subtitleSync: TvHudSubtitleSync = TvHudSubtitleSync(),
    onSyncSubtitle: (String) -> Unit = {},
    onResetSubtitleTiming: (String) -> Unit = {},
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
    /**
     * False in a Watch Party: the room plays one exact file at 1x, so the
     * version picker and the speed row are hidden. Quality stays (it is the
     * same file's ladder).
     */
    versionAndSpeedControlsVisible: Boolean = true,
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

    // The Subtitles pane's right column drills into Appearance and Timing in
    // place. Owned here so Back can step out of a page before it closes the
    // HUD, and reset when the viewer moves to another tab.
    var subtitlePage by remember { mutableStateOf(HudSubtitlePage.Root) }
    LaunchedEffect(selectedTab) {
        if (selectedTab != HudTab.Subtitles) subtitlePage = HudSubtitlePage.Root
    }

    // Every picker names the tab it came from above its title.
    val presentPicker: (HudPickerPresentation) -> Unit = { picker ->
        activePicker = if (picker.eyebrow == null) picker.copy(eyebrow = selectedTab.label) else picker
    }
    val closePicker: () -> Unit = { activePicker = null }

    // Android 16 no longer dispatches KEYCODE_BACK to target-36 apps. Register
    // the picker and the subtitle page as the most specific callbacks; when
    // both are closed, the player screen's callback dismisses the HUD itself.
    BackHandler(enabled = activePicker == null && subtitlePage != HudSubtitlePage.Root) {
        subtitlePage = HudSubtitlePage.Root
    }
    BackHandler(enabled = activePicker != null) { closePicker() }

    val pickerOpen = activePicker != null
    val chromeAlpha by animateFloatAsState(
        targetValue = if (pickerOpen) 0.3f else 1f,
        animationSpec = tween(160),
        label = "hudChromeAlpha",
    )

    // Full screen, so the scrim and a picker can use the whole picture. The
    // scrim is light while the panel is up (the picture stays watchable and
    // the rail reads over bright frames) and deepens behind a picker.
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                if (pickerOpen) {
                    drawRect(Color.Black.copy(alpha = 0.55f))
                } else {
                    drawRect(Color.Black.copy(alpha = 0.10f))
                    drawRect(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.60f),
                            1f to Color.Black.copy(alpha = 0.30f),
                            endY = 300.dp.toPx(),
                            tileMode = TileMode.Clamp,
                        ),
                    )
                }
            }
            .onFocusChanged { hudHasFocus = it.hasFocus }
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
                        when {
                            activePicker != null -> activePicker = null
                            subtitlePage != HudSubtitlePage.Root -> subtitlePage = HudSubtitlePage.Root
                            else -> {
                                TvFocusLog.d { "hud key BACK -> onDismiss" }
                                onDismiss()
                            }
                        }
                        true
                    }
                    else -> false
                }
            },
    ) {
        CompositionLocalProvider(LocalHudPickerReturnFocus provides registerPickerReturnFocus) {
        // fillMaxWidth BEFORE widthIn. Chained the other way round, fillMaxWidth
        // sees the already-capped max and takes its fraction of THAT, which
        // clipped the tab rail and cramped every two-column pane.
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = HudRailTop)
                .fillMaxWidth(HudWidthFraction)
                .widthIn(max = HudMaxWidth)
                .graphicsLayer { alpha = chromeAlpha },
            verticalArrangement = Arrangement.spacedBy(HudTabCardGap),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Floating capsule rail. The scroll is a safety net for very long
            // localised labels; the six English tabs fit with room.
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(HudRailFill)
                    .border(1.dp, TvPlayerChrome.PanelStroke, CircleShape)
                    .padding(4.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
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
            val panelWindow = remember { HudPanelWindow() }
            val panelShape = RoundedCornerShape(HudPanelCorner)
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
                        // Down from a pill lands on the pane's entry row, not
                        // whichever swatch or row happens to sit under that pill.
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
                        elevation = 24.dp,
                        shape = panelShape,
                        ambientColor = Color.Black.copy(alpha = 0.6f),
                        spotColor = Color.Black.copy(alpha = 0.6f),
                    )
                    .clip(panelShape)
                    .onPlaced { panelWindow.panel = it }
                    // Smoked at 95.5%: the picture shows AROUND the card, not
                    // through it, because this is a settings surface people
                    // read from the sofa. The one exception is the subtitle
                    // sample, which cuts a window so it sits on the real frame.
                    .drawBehind {
                        val corner = CornerRadius(HudPanelCorner.toPx())
                        val outline = Path().apply { addRoundRect(RoundRect(size.toRect(), corner)) }
                        val window = panelWindow.bounds
                        if (window == null) {
                            drawPath(outline, TvPlayerChrome.Smoke)
                        } else {
                            val hole = Path().apply {
                                addRoundRect(RoundRect(window, CornerRadius(HudPreviewCorner.toPx())))
                            }
                            drawPath(Path.combine(PathOperation.Difference, outline, hole), TvPlayerChrome.Smoke)
                        }
                    }
                    .border(width = 1.dp, color = TvPlayerChrome.PanelStroke, shape = panelShape)
                    .padding(HudPanelPadding),
            ) {
                CompositionLocalProvider(LocalHudPanelWindow provides panelWindow) {
                when (selectedTab) {
                    // Info scrolls per column, so it must not sit inside the
                    // single-column viewport: nested vertical scrolls throw.
                    HudTab.Info -> HudInfoPane(
                        title = title,
                        positionSec = positionSec,
                        durationSec = durationSec,
                        seasonNumber = seasonNumber,
                        episodeNumber = episodeNumber,
                        stats = stats,
                        playbackPlan = playbackPlan,
                        subtitleLabel = subtitlePresentation.rows
                            .firstOrNull { row -> row.checked }
                            ?.let { row -> tvSubtitleRowParts(row.label).title }
                            ?: "Off",
                        chapters = chapters,
                    )
                    HudTab.Stats -> HudPaneViewport { HudStatsPane(stats) }
                    HudTab.Video -> HudVideoPane(
                        videoQualities = videoQualities,
                        onSelectVideoQuality = onSelectVideoQuality,
                        fileVersions = if (versionAndSpeedControlsVisible) fileVersions else emptyList(),
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
                        speedRowVisible = versionAndSpeedControlsVisible,
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
                        page = subtitlePage,
                        onPageChange = { subtitlePage = it },
                        subtitleDelayMs = subtitleDelayMs,
                        subtitleDelayEnabled = subtitleDelayEnabled,
                        onSubtitleDelayChanged = onSubtitleDelayChanged,
                        appearance = subtitleAppearance,
                        onAppearanceChanged = onSubtitleAppearanceChanged,
                        showTextOpacity = subtitleTextOpacitySupported,
                        onPaneShown = onSubtitlesPaneShown,
                        onSearchSubtitles = onSearchSubtitles,
                        onTranslateWithAi = onTranslateWithAi,
                        timing = subtitleSync.timing,
                        syncStatus = subtitleSync::statusFor,
                        onSyncSubtitle = onSyncSubtitle,
                        onResetTiming = onResetSubtitleTiming,
                        entryFocusRequester = paneEntryFocus,
                        enabled = activePicker == null,
                        onPresentPicker = presentPicker,
                    )
                    HudTab.Chapters -> HudChaptersPane(
                        chapters = chapters,
                        positionSec = positionSec,
                        durationSec = durationSec,
                        onSelectChapter = onSelectChapter,
                        entryFocusRequester = paneEntryFocus,
                    )
                }
                }
            }
        }
        }

        // Centered modal picker, drawn over the dimmed rail and card.
        val picker = activePicker
        if (picker != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 24.dp),
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

/** Where the Subtitles pane's right column is: its root, or a drill-in page. */
internal enum class HudSubtitlePage { Root, Appearance, Timing }

/** The rail floats on the picture, so it carries its own smoked ground. */
private val HudRailFill = TvPlayerChrome.Smoke.copy(alpha = 0.78f)
private val HudPreviewCorner = 12.dp

/**
 * The window the subtitle sample cuts in the panel, in panel coordinates.
 * The panel draws its fill around it, so the sample sits on the live picture.
 */
private class HudPanelWindow {
    var panel: LayoutCoordinates? = null
    var bounds by mutableStateOf<Rect?>(null)

    fun report(source: LayoutCoordinates?) {
        val panelCoordinates = panel
        bounds = if (source == null || panelCoordinates == null || !source.isAttached || !panelCoordinates.isAttached) {
            null
        } else {
            // Clipped to every scroll viewport in between, so a sample that
            // scrolls partly away never opens a hole outside its column.
            panelCoordinates.localBoundingBoxOf(source, clipBounds = true).takeIf { !it.isEmpty }
        }
    }
}

private val LocalHudPanelWindow = staticCompositionLocalOf<HudPanelWindow?> { null }

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

    // The rail carries the ground, so an idle tab is just its label. Selection
    // follows focus, so a focused tab is always the selected one; a selected
    // tab that is NOT focused means focus is down in the pane, and it keeps a
    // white-16% marker so the one Paper element on screen is the control
    // you're on. Focus is an inversion, never a scale.
    val bg = when {
        isFocused -> TvPlayerChrome.Paper
        isSelected -> Color.White.copy(alpha = 0.16f)
        else -> Color.Transparent
    }
    val fg = when {
        isFocused -> TvPlayerChrome.Ink
        isSelected -> TvPlayerChrome.Paper
        else -> TvPlayerChrome.Graphite
    }

    Box(
        modifier = Modifier
            .height(HudTabHeight)
            .clip(CircleShape)
            .background(bg)
            .focusRequester(focusRequester)
            .focusable(enabled = enabled, interactionSource = interactionSource)
            .padding(horizontal = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = TvPlayerType.Tab, color = fg, maxLines = 1)
    }
}

@Composable
private fun HudPaneViewport(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .hudFadingEdges(scroll)
            .verticalScroll(scroll),
        content = content,
    )
}

/**
 * The pane grid every two-column tab shares: 330 of 736dp on the left, a
 * 26dp gutter, the rest on the right. Each column scrolls on its own and
 * fades at the edge it scrolls past rather than cutting a row in half.
 */
@Composable
private fun HudColumns(
    modifier: Modifier = Modifier,
    /** Pinned above the left column's scroll, so a long list keeps its label. */
    leftHeader: (@Composable () -> Unit)? = null,
    left: @Composable ColumnScope.() -> Unit,
    right: @Composable ColumnScope.() -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(HudPaneColumnGap),
    ) {
        val leftScroll = rememberScrollState()
        val rightScroll = rememberScrollState()
        Column(modifier = Modifier.weight(HudLeftColumnWeight)) {
            leftHeader?.invoke()
            Column(
                modifier = Modifier
                    .hudFadingEdges(leftScroll)
                    .verticalScroll(leftScroll),
                content = left,
            )
        }
        Column(
            modifier = Modifier
                .weight(HudRightColumnWeight)
                .hudFadingEdges(rightScroll)
                .verticalScroll(rightScroll),
            content = right,
        )
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
) {
    val episodeTag = if (seasonNumber != null && episodeNumber != null) {
        "S$seasonNumber · E$episodeNumber"
    } else {
        null
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

    HudColumns(
        left = {
            HudEyebrow("Title")
            Column(
                modifier = Modifier.padding(horizontal = HudRowPadding),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (episodeTag != null) {
                    Text(text = episodeTag.uppercase(), style = TvPlayerType.Eyebrow)
                }
                Text(
                    text = title.ifBlank { "Now Playing" },
                    style = TvPlayerType.DialogTitle,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (durationSec > 0) {
                    Text(text = formatTime(durationSec), style = TvPlayerType.Figures)
                }
                if (badges.isNotEmpty()) {
                    Row(
                        modifier = Modifier.padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        badges.forEach { badge -> HudChip(badge) }
                    }
                }
            }
        },
        right = {
            HudEyebrow("Stream")
            streamRows.forEach { (label, value) ->
                HudReadOnlyRow(label = label, value = value)
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

/** A section label inside a pane, inset to line up with row text. */
@Composable
private fun HudEyebrow(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = HudRowPadding, end = HudRowPadding, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text.uppercase(),
            style = TvPlayerType.Eyebrow,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(text = trailing, style = TvPlayerType.Figures, maxLines = 1)
        }
    }
}

/** Space between two sections in one column. */
@Composable
private fun HudSectionSpacer() {
    Spacer(modifier = Modifier.height(HudSectionGap))
}

/** A small uppercase tag: SDH, FORCED, a resolution, an HDR format. */
@Composable
private fun HudChip(text: String, onPaper: Boolean = false) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (onPaper) TvPlayerChrome.Ink.copy(alpha = 0.10f) else TvPlayerChrome.Chip)
            .padding(horizontal = 5.dp, vertical = 2.dp),
    ) {
        Text(
            text = text.uppercase(),
            style = TvPlayerType.Chip,
            color = if (onPaper) TvPlayerChrome.InkMuted else TvPlayerType.Chip.color,
            maxLines = 1,
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
            Column(modifier = Modifier.weight(1f)) {
                column.forEach { (label, value) -> HudReadOnlyRow(label = label, value = value) }
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

/** 1.0 -> "1×", 1.25 -> "1.25×", 2.0 -> "2×": no trailing zero, as on the phone. */
private fun formatTvPlaybackSpeed(speed: Double): String {
    // Locale.ROOT so comma-decimal devices render "1.25×", not "1,25×",
    // matching the dot-formatted speedOptionId used to commit the choice.
    val text = String.format(java.util.Locale.ROOT, "%.2f", speed).trimEnd('0').trimEnd('.')
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
    speedRowVisible: Boolean,
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
    // The quality menu exists whenever a plan does: even a single entry
    // lists Auto + Original.
    val hasQualityRow = videoQualities.isNotEmpty()
    val entryRow = when {
        hasVersionRow -> "version"
        hasQualityRow -> "quality"
        speedRowVisible -> "speed"
        else -> "aspect"
    }
    HudColumns(
        modifier = modifier,
        left = {
            // Playback column — Version / Quality / Speed / Aspect.
            HudEyebrow("Playback")
            Column {
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

                // Quality — the plan's quality menu: Auto, then the server's
                // entries in its order, each re-planned on the server when
                // picked. Hidden only when there is no plan.
                if (hasQualityRow) {
                    val selectedQuality = videoQualities.firstOrNull { it.isSelected }
                    HudFocusedSettingRow(
                        label = "Quality",
                        value = selectedQuality?.label ?: "Auto",
                        enabled = enabled,
                        entryFocusRequester = entryFocusRequester.takeIf { entryRow == "quality" },
                        onActivate = {
                            onPresentPicker(
                                HudPickerPresentation(
                                    title = "Quality",
                                    options = videoQualities.map {
                                        HudPickerOption(id = it.id, label = it.label, trailing = it.bitrateLabel)
                                    },
                                    selectedId = selectedQuality?.id.orEmpty(),
                                    onSelect = { id -> onSelectVideoQuality(id) },
                                ),
                            )
                        },
                    )
                }

                if (speedRowVisible) {
                    HudFocusedSettingRow(
                        label = "Speed",
                        value = formatTvPlaybackSpeed(playbackSpeed),
                        enabled = enabled,
                        entryFocusRequester = entryFocusRequester.takeIf { entryRow == "speed" },
                        onActivate = {
                            onPresentPicker(
                                HudPickerPresentation(
                                    title = "Playback speed",
                                    options = PLAYBACK_SPEED_OPTIONS.map {
                                        if (it == 1.0) {
                                            HudPickerOption(speedOptionId(it), "Normal", trailing = formatTvPlaybackSpeed(it))
                                        } else {
                                            HudPickerOption(speedOptionId(it), formatTvPlaybackSpeed(it))
                                        }
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
                }

                HudFocusedSettingRow(
                    label = "Aspect",
                    value = fillModeLabel(fillMode),
                    enabled = enabled,
                    entryFocusRequester = entryFocusRequester.takeIf { entryRow == "aspect" },
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
        },
        // Right column: what the device does with the picture, then what the
        // player does on its own. Previously the left column carried eight
        // rows against a lone Sleep timer here — the pane scrolled while
        // half the card sat empty.
        right = {
            HudEyebrow("Output")
            Column {
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
            HudSectionSpacer()
            HudEyebrow("Automation")
            Column {
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
                                title = "Sleep timer",
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
        },
    )
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
    HudColumns(
        modifier = modifier,
        left = {
            HudEyebrow("Track")
            Column {
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
                                title = "Audio track",
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
                                title = "Audio delay",
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
        },
        right = {
            HudEyebrow("Output")
            outputRows.forEach { (label, value) ->
                // Same row metrics as the setting rows on the left, so the
                // two columns rule up; not focusable, nothing to open.
                HudReadOnlyRow(label = label, value = value)
            }
        },
    )
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
            .heightIn(min = HudRowHeight)
            .padding(horizontal = HudRowPadding, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Fixed gap, weighted value: a lone weighted spacer collapses to 0dp
        // once the two texts fill the row ("SubtitlesArabic — SRT · Exter…").
        Text(text = label, style = TvPlayerType.Row, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            style = TvPlayerType.Value,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Subtitles pane. The left column lists every track inline, so switching
 * subtitles is one press rather than row → picker → row. The right column
 * shows a sample on the live picture, then Style (Size, Appearance, Timing)
 * and the Android-only Find more rows. Appearance and Timing drill in place:
 * [page] swaps the right column, and Back steps out (handled by the HUD).
 */
@Composable
private fun HudSubtitlesPane(
    presentation: TvSubtitleHudPresentation,
    page: HudSubtitlePage,
    onPageChange: (HudSubtitlePage) -> Unit,
    subtitleDelayMs: Int,
    subtitleDelayEnabled: Boolean,
    onSubtitleDelayChanged: (Int) -> Unit,
    appearance: SubtitleAppearance,
    onAppearanceChanged: (SubtitleAppearance) -> Unit,
    showTextOpacity: Boolean,
    onPaneShown: () -> Unit,
    onSearchSubtitles: (() -> Unit)?,
    onTranslateWithAi: (() -> Unit)?,
    timing: SubtitleTimingActions?,
    syncStatus: (SubtitleIdentity) -> String?,
    onSyncSubtitle: (String) -> Unit,
    onResetTiming: (String) -> Unit,
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

    // Moving between the root and a page removes the focused row, so focus is
    // handed on explicitly: into a page's first row, or back to the row that
    // opened it. Observed through the rows' own focus, not the pane's.
    val appearanceRowFocus = remember { FocusRequester() }
    val timingRowFocus = remember { FocusRequester() }
    val pageEntryFocus = remember { FocusRequester() }
    var focusedPageRow by remember { mutableStateOf<String?>(null) }
    var lastPage by remember { mutableStateOf(page) }
    LaunchedEffect(page) {
        val previous = lastPage
        lastPage = page
        if (previous == page) return@LaunchedEffect
        val (target, key) = when (page) {
            HudSubtitlePage.Root -> when (previous) {
                HudSubtitlePage.Timing -> timingRowFocus to "root:timing"
                else -> appearanceRowFocus to "root:appearance"
            }
            else -> pageEntryFocus to "page:entry"
        }
        requestFocusUntilObserved(
            maxAttempts = TvFrameRelocationMaxAttempts,
            awaitAttempt = { withFrameNanos { } },
            requestFocus = target::requestFocus,
            isFocused = { focusedPageRow == key },
        )
    }
    fun Modifier.trackPageFocus(key: String) = onFocusChanged { state ->
        if (state.isFocused) {
            focusedPageRow = key
        } else if (focusedPageRow == key) {
            focusedPageRow = null
        }
    }

    HudColumns(
        modifier = modifier,
        leftHeader = {
            val trackCount = presentation.rows.count { row -> row.identity != SubtitleIdentity.Off }
            HudEyebrow(if (trackCount > 0) "Tracks · $trackCount" else "Tracks")
        },
        left = {
            // The entry row is the checked track, so Down from the rail lands
            // on the current choice; the first row when nothing is checked.
            val entryRow = presentation.rows.firstOrNull { row -> row.checked } ?: presentation.rows.firstOrNull()
            presentation.rows.forEach { row ->
                key(row.stableId) {
                    HudSubtitleTrackRow(
                        row = row,
                        syncStatus = syncStatus(row.identity),
                        enabled = enabled,
                        focusRequester = entryFocusRequester.takeIf { row == entryRow },
                        onFocused = { presentation.onFocused(row.stableId) },
                        onSelect = { presentation.onSelect(row.identity) },
                    )
                }
            }
        },
        right = {
            HudSubtitlePreview(appearance = appearance)
            when (page) {
                HudSubtitlePage.Root -> {
                    HudEyebrow("Style")
                    HudFocusedSettingRow(
                        label = "Size",
                        value = TvSubtitleAppearanceOptions.fontSizeLabel(appearance.fontSize),
                        enabled = geometryEnabled,
                        onActivate = {
                            onPresentPicker(
                                HudPickerPresentation(
                                    title = "Subtitle size",
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
                        label = "Appearance",
                        value = subtitleAppearanceSummary(appearance),
                        // Position is geometry, so the page is useful even
                        // when only geometry applies (image subtitles).
                        enabled = geometryEnabled,
                        focusRequester = appearanceRowFocus,
                        modifier = Modifier.trackPageFocus("root:appearance"),
                        onActivate = { onPageChange(HudSubtitlePage.Appearance) },
                    )
                    HudFocusedSettingRow(
                        label = "Timing",
                        value = subtitleTimingSummary(subtitleDelayMs, subtitleDelayEnabled, timing),
                        enabled = enabled && (subtitleDelayEnabled || timing != null),
                        focusRequester = timingRowFocus,
                        modifier = Modifier.trackPageFocus("root:timing"),
                        onActivate = { onPageChange(HudSubtitlePage.Timing) },
                    )
                    applicability.note?.let { note -> HudNote(note) }
                    if (onSearchSubtitles != null || onTranslateWithAi != null) {
                        HudSectionSpacer()
                        HudEyebrow("Find more")
                        if (onSearchSubtitles != null) {
                            HudActionRow(
                                label = "Search online",
                                icon = Icons.Rounded.Search,
                                enabled = enabled,
                                onClick = onSearchSubtitles,
                            )
                        }
                        if (onTranslateWithAi != null) {
                            HudActionRow(
                                label = "Translate with AI",
                                icon = Icons.Rounded.AutoAwesome,
                                enabled = enabled,
                                onClick = onTranslateWithAi,
                            )
                        }
                    }
                }

                HudSubtitlePage.Appearance -> {
                    HudEyebrow("Appearance")
                    HudFocusedSettingRow(
                        label = "Font",
                        value = TvSubtitleAppearanceOptions.fontFamilyLabel(appearance.fontFamily),
                        enabled = stylingEnabled,
                        // Entry is Position when styling is out (image tracks):
                        // a disabled row cannot take focus.
                        focusRequester = pageEntryFocus.takeIf { stylingEnabled },
                        modifier = Modifier.trackPageFocus(if (stylingEnabled) "page:entry" else "page:font"),
                        onActivate = {
                            onPresentPicker(
                                HudPickerPresentation(
                                    title = "Subtitle font",
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
                    HudSwatchRow(
                        label = "Text color",
                        swatches = TEXT_COLOR_SWATCHES,
                        selectedHex = appearance.fontColor,
                        swatchLabel = TvSubtitleAppearanceOptions::fontColorLabel,
                        enabled = stylingEnabled,
                        onSelect = { hex -> onAppearanceChanged(appearance.copy(fontColor = hex)) },
                    )
                    if (showTextOpacity) {
                        HudFocusedSettingRow(
                            label = "Text opacity",
                            value = "${appearance.textOpacity}%",
                            enabled = stylingEnabled,
                            onActivate = {
                                onPresentPicker(
                                    HudPickerPresentation(
                                        title = "Text opacity",
                                        options = TvSubtitleAppearanceOptions.percentOptions(
                                            TEXT_OPACITY_STEPS,
                                            appearance.textOpacity,
                                        ).map { HudPickerOption(it.toString(), "$it%") },
                                        selectedId = appearance.textOpacity.toString(),
                                        onSelect = { id ->
                                            id.toIntOrNull()?.let {
                                                onAppearanceChanged(appearance.copy(textOpacity = it))
                                            }
                                        },
                                    ),
                                )
                            },
                        )
                    }
                    HudFocusedSettingRow(
                        label = "Background",
                        value = BACKGROUND_STYLES.firstOrNull { it.first == appearance.backgroundStyle }?.second
                            ?: appearance.backgroundStyle.name,
                        enabled = stylingEnabled,
                        onActivate = {
                            onPresentPicker(
                                HudPickerPresentation(
                                    title = "Subtitle background",
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
                    HudSwatchRow(
                        label = "Background color",
                        swatches = BACKGROUND_COLOR_SWATCHES,
                        selectedHex = appearance.backgroundColor,
                        swatchLabel = TvSubtitleAppearanceOptions::backgroundColorLabel,
                        enabled = stylingEnabled,
                        onSelect = { hex -> onAppearanceChanged(appearance.copy(backgroundColor = hex)) },
                    )
                    HudFocusedSettingRow(
                        label = "Background opacity",
                        value = "${appearance.backgroundOpacity}%",
                        enabled = stylingEnabled,
                        onActivate = {
                            onPresentPicker(
                                HudPickerPresentation(
                                    title = "Background opacity",
                                    options = TvSubtitleAppearanceOptions.percentOptions(
                                        OPACITY_STEPS,
                                        appearance.backgroundOpacity,
                                    ).map { HudPickerOption(it.toString(), "$it%") },
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
                        showsChevron = false,
                        onActivate = { onAppearanceChanged(appearance.copy(textOutline = !appearance.textOutline)) },
                    )
                    if (appearance.textOutline) {
                        HudSwatchRow(
                            label = "Outline color",
                            swatches = OUTLINE_COLOR_SWATCHES,
                            selectedHex = appearance.textOutlineColor,
                            swatchLabel = TvSubtitleAppearanceOptions::outlineColorLabel,
                            enabled = stylingEnabled,
                            onSelect = { hex -> onAppearanceChanged(appearance.copy(textOutlineColor = hex)) },
                        )
                    }
                    HudFocusedSettingRow(
                        label = "Position",
                        value = POSITIONS.firstOrNull { it.first == appearance.position }?.second
                            ?: appearance.position.name,
                        enabled = geometryEnabled,
                        focusRequester = pageEntryFocus.takeIf { !stylingEnabled },
                        modifier = Modifier.trackPageFocus(if (!stylingEnabled) "page:entry" else "page:position"),
                        onActivate = {
                            onPresentPicker(
                                HudPickerPresentation(
                                    title = "Subtitle position",
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
                    applicability.note?.let { note -> HudNote(note) }
                }

                HudSubtitlePage.Timing -> {
                    HudEyebrow("Timing")
                    HudFocusedSettingRow(
                        label = "Delay",
                        value = if (subtitleDelayEnabled) {
                            delayLabel(subtitleDelayMs)
                        } else {
                            "Unavailable for burned-in subtitles"
                        },
                        enabled = enabled && subtitleDelayEnabled,
                        focusRequester = pageEntryFocus.takeIf { subtitleDelayEnabled },
                        modifier = Modifier.trackPageFocus(if (subtitleDelayEnabled) "page:entry" else "page:delay"),
                        onActivate = {
                            onPresentPicker(
                                delayPicker(
                                    title = "Subtitle delay",
                                    current = subtitleDelayMs,
                                    from = -2_000,
                                    to = 2_000,
                                    step = 100,
                                    onSet = onSubtitleDelayChanged,
                                ),
                            )
                        },
                    )
                    if (timing != null) {
                        // Swallow, not disable: a running sync or a refusal
                        // would otherwise strand focus on a row that just
                        // stopped being focusable.
                        HudFocusedSettingRow(
                            label = "Sync to audio",
                            value = subtitleTimingValue(timing),
                            enabled = enabled,
                            showsChevron = false,
                            focusRequester = pageEntryFocus.takeIf { !subtitleDelayEnabled },
                            modifier = Modifier.trackPageFocus(if (!subtitleDelayEnabled) "page:entry" else "page:sync"),
                            onActivate = {
                                if (!timing.forbidden && timing.actionsEnabled && timing.canSync) {
                                    onSyncSubtitle(timing.key)
                                }
                            },
                        )
                        if (timing.canReset) {
                            HudFocusedSettingRow(
                                label = "Reset timing",
                                value = "",
                                enabled = enabled,
                                showsChevron = false,
                                onActivate = {
                                    if (!timing.forbidden && timing.actionsEnabled) onResetTiming(timing.key)
                                },
                            )
                        }
                        HudSubtitleTimingDetail(timing)
                    }
                }
            }
        },
    )
}

/** "White · Box": the two appearance facts a viewer recognizes at a glance. */
private fun subtitleAppearanceSummary(appearance: SubtitleAppearance): String {
    val color = TvSubtitleAppearanceOptions.fontColorLabel(appearance.fontColor)
    val background = when (appearance.backgroundStyle) {
        SubtitleBackgroundStylePreset.None -> "No background"
        SubtitleBackgroundStylePreset.Box -> "Box"
        SubtitleBackgroundStylePreset.Shadow -> "Shadow"
        SubtitleBackgroundStylePreset.Outline -> "Outline"
    }
    return "$color · $background"
}

/** The Timing row's value: the delay in seconds, or what a sync is doing. */
private fun subtitleTimingSummary(
    delayMs: Int,
    delayEnabled: Boolean,
    timing: SubtitleTimingActions?,
): String = when {
    timing != null && (timing.busy || timing.inProgress) -> "Working…"
    delayEnabled -> delaySecondsLabel(delayMs)
    timing != null -> subtitleTimingValue(timing)
    else -> "Unavailable"
}

/** 0 -> "0.0 s", 300 -> "+0.3 s", -1200 -> "−1.2 s" (true minus sign). */
private fun delaySecondsLabel(valueMs: Int): String {
    val seconds = String.format(java.util.Locale.ROOT, "%.1f", kotlin.math.abs(valueMs) / 1000.0)
    return when {
        valueMs > 0 -> "+$seconds s"
        valueMs < 0 -> "−$seconds s"
        else -> "$seconds s"
    }
}

/**
 * A subtitle track's label split for a row: the language as the title, the
 * tags worth scanning for (Forced, SDH, AI) as chips, the rest as detail.
 * Labels arrive as "English — SRT · Forced · External" from
 * [subtitleChoiceLabel], or "English • SRT" from a player-only track.
 */
internal data class TvSubtitleRowParts(val title: String, val chips: List<String>, val detail: String?)

internal fun tvSubtitleRowParts(label: String): TvSubtitleRowParts {
    val separator = listOf(" — ", " • ").firstOrNull { it in label }
    val title = separator?.let { label.substringBefore(it) } ?: label
    val facts = separator
        ?.let { label.substringAfter(it) }
        ?.split(" · ", " • ")
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        .orEmpty()
    val chips = facts.mapNotNull { fact -> TvSubtitleChipNames[fact.lowercase(java.util.Locale.US)] }
    val detail = facts
        .filter { fact -> fact.lowercase(java.util.Locale.US) !in TvSubtitleChipNames }
        .joinToString(" · ")
        .ifBlank { null }
    return TvSubtitleRowParts(title = title.trim(), chips = chips.distinct(), detail = detail)
}

private val TvSubtitleChipNames = mapOf(
    "forced" to "Forced",
    "sdh" to "SDH",
    "cc" to "CC",
    "ai translation" to "AI",
)

/**
 * One inline subtitle track: a check column, the language with its chips,
 * and the format and source on the right. Select switches to it at once.
 */
@Composable
private fun HudSubtitleTrackRow(
    row: TvSubtitleHudRow,
    syncStatus: String?,
    enabled: Boolean,
    focusRequester: FocusRequester?,
    onFocused: () -> Unit,
    onSelect: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val isFocused by interactionSource.collectIsFocusedAsState()
    val colors = hudRowColors(focused = isFocused, current = row.checked)
    val parts = remember(row.label) { tvSubtitleRowParts(row.label) }
    val detail = listOfNotNull(
        row.status ?: syncStatus,
        parts.detail,
    ).joinToString(" · ").ifBlank { null }

    // The checked row is revealed when the pane opens; focus reveals the rest.
    LaunchedEffect(Unit) { if (row.checked) bringIntoViewRequester.bringIntoView() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoViewRequester)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { state ->
                if (state.isFocused) {
                    onFocused()
                    scope.launch { bringIntoViewRequester.bringIntoView() }
                }
            }
            .hudRow(colors.background)
            .clickable(enabled = enabled, interactionSource = interactionSource, indication = null) { onSelect() }
            .semantics { selected = row.checked }
            .padding(horizontal = HudRowPadding, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HudCheckSlot(checked = row.checked, tint = colors.content)
        // The title side takes what the detail leaves; the detail is short
        // ("SRT · External") and capped, so a long name ellipsizes first.
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = parts.title,
                style = TvPlayerType.Row,
                color = colors.content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            parts.chips.forEach { chip ->
                Spacer(modifier = Modifier.width(6.dp))
                HudChip(chip, onPaper = isFocused)
            }
        }
        if (detail != null) {
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = detail,
                style = TvPlayerType.RowDetail,
                color = colors.detail,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.widthIn(max = 168.dp),
            )
        }
    }
}

/**
 * The subtitle sample: a 58dp strip that cuts a window in the panel, so the
 * line is drawn over the picture it will appear on. Updates live as the
 * appearance changes.
 */
@Composable
private fun HudSubtitlePreview(
    appearance: SubtitleAppearance,
    modifier: Modifier = Modifier,
) {
    val safe = appearance.sanitized()
    val decoration = TvSubtitleAppearanceOptions.previewDecoration(safe)
    val fontSize = TvSubtitleAppearanceOptions.previewFontSizeSp(safe.fontSize).coerceAtMost(22f).sp
    val fontFamily = TvSubtitleAppearanceOptions.previewFontFamily(safe.fontFamily)
    val foreground = hexToColor(safe.fontColor).copy(
        alpha = TvSubtitleAppearanceOptions.previewOpacityAlpha(safe.textOpacity, floor = 1),
    )
    val outline = hexToColor(safe.textOutlineColor)
    val backgroundColor = hexToColor(safe.backgroundColor).copy(
        alpha = if (safe.backgroundStyle == SubtitleBackgroundStylePreset.Box) {
            TvSubtitleAppearanceOptions.previewOpacityAlpha(safe.backgroundOpacity, floor = 0)
        } else {
            0f
        },
    )
    val textStyle = TvPlayerType.Row.copy(
        fontSize = fontSize,
        lineHeight = fontSize * 1.18f,
        fontFamily = fontFamily,
        fontWeight = FontWeight.SemiBold,
        shadow = if (decoration.shadow) {
            Shadow(color = outline.copy(alpha = 0.9f), offset = Offset(1f, 2f), blurRadius = 5f)
        } else {
            null
        },
    )
    val window = LocalHudPanelWindow.current
    DisposableEffect(window) { onDispose { window?.report(null) } }

    Box(
        modifier = modifier
            .padding(bottom = HudSectionGap)
            .fillMaxWidth()
            .height(58.dp)
            .onGloballyPositioned { window?.report(it) }
            .clip(RoundedCornerShape(HudPreviewCorner))
            // A light veil keeps the sample legible over a bright frame.
            .background(Color.Black.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        contentAlignment = TvSubtitleAppearanceOptions.previewAlignment(safe.position),
    ) {
        val boxed = safe.backgroundStyle == SubtitleBackgroundStylePreset.Box
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(3.dp))
                .background(backgroundColor)
                .padding(horizontal = if (boxed) 8.dp else 0.dp, vertical = if (boxed) 2.dp else 0.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (decoration.outline) {
                listOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1).forEach { (x, y) ->
                    Text(
                        text = HudPreviewLine,
                        color = outline,
                        style = textStyle.copy(shadow = null),
                        maxLines = 1,
                        modifier = Modifier.offset(x.dp, y.dp),
                    )
                }
            }
            Text(
                text = HudPreviewLine,
                color = foreground,
                style = textStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private const val HudPreviewLine = "Subtitles will look like this."

/**
 * A row of colour swatches under its label. Swatches stay inline, as on
 * tvOS: a picker of colours would lose the at-a-glance palette.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun HudSwatchRow(
    label: String,
    swatches: List<String>,
    selectedHex: String,
    swatchLabel: (String) -> String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HudRowPadding, vertical = 6.dp)
            // Same 0.35 alpha HudFocusedSettingRow uses for a disabled row.
            .graphicsLayer { alpha = if (enabled) 1f else 0.35f },
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(text = label, style = TvPlayerType.Row, maxLines = 1)
        // Moving into the row lands on the current colour, not on whichever
        // swatch happens to sit under the row the viewer came from.
        val selectedFocus = remember { FocusRequester() }
        val hasSelected = swatches.any { it.equals(selectedHex, ignoreCase = true) }
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .focusProperties {
                    enter = { if (hasSelected) selectedFocus else FocusRequester.Default }
                }
                .focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            swatches.forEach { hex ->
                val selected = selectedHex.equals(hex, ignoreCase = true)
                StyleColorSwatch(
                    hex = hex,
                    label = swatchLabel(hex),
                    selected = selected,
                    enabled = enabled,
                    focusRequester = selectedFocus.takeIf { selected },
                ) { onSelect(hex) }
            }
        }
    }
}

@Composable
private fun StyleColorSwatch(
    hex: String,
    label: String,
    selected: Boolean,
    enabled: Boolean,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val swatchColor = hexToColor(hex)
    val isLightSwatch = isLightHexColor(hex)
    val checkTint = if (isLightSwatch) Color.Black.copy(alpha = 0.78f) else Color.White
    // Focus is a Paper ring outside the swatch, so it reads on any colour.
    Box(
        modifier = Modifier
            .size(32.dp)
            .border(
                width = 2.dp,
                color = if (isFocused) TvPlayerChrome.Paper else Color.Transparent,
                shape = CircleShape,
            )
            .padding(4.dp)
            .clip(CircleShape)
            .background(swatchColor)
            .border(
                width = 1.dp,
                color = if (selected && !isLightSwatch) Color.White.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.22f),
                shape = CircleShape,
            )
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clickable(enabled = enabled, interactionSource = interactionSource, indication = null) { onClick() }
            .semantics {
                contentDescription = if (selected) "$label, selected" else label
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = checkTint,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/** A line of explanation under the rows it qualifies. Never takes focus. */
@Composable
private fun HudNote(text: String) {
    Text(
        text = text,
        style = TvPlayerType.RowDetail,
        modifier = Modifier.padding(horizontal = HudRowPadding, vertical = 6.dp),
    )
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
private val TEXT_OPACITY_STEPS = TvSubtitleAppearanceOptions.TEXT_OPACITY_STEPS
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
 * Full-width action row for HUD panes: an icon, a label, a chevron. A true
 * click target: an explicit Select press is required (focus-driven commit
 * would fire dialogs during plain traversal).
 */
@Composable
private fun HudActionRow(
    label: String,
    icon: ImageVector,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val colors = hudRowColors(focused = isFocused)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hudRow(colors.background)
            .clickable(enabled = enabled, interactionSource = interactionSource, indication = null) { onClick() }
            .padding(horizontal = HudRowPadding, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = colors.content, modifier = Modifier.size(19.dp))
        Spacer(modifier = Modifier.width(9.dp))
        Text(
            text = label,
            style = TvPlayerType.Row,
            color = colors.content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        HudChevron(colors.chevron)
    }
}

/** What a row looks like at rest, when it is the current choice, and focused. */
private data class HudRowColors(
    val background: Color,
    val content: Color,
    val detail: Color,
    val chevron: Color,
)

private fun hudRowColors(focused: Boolean, current: Boolean = false): HudRowColors = when {
    focused -> HudRowColors(
        background = TvPlayerChrome.Paper,
        content = TvPlayerChrome.Ink,
        detail = TvPlayerChrome.InkMuted,
        chevron = TvPlayerChrome.Ink.copy(alpha = 0.45f),
    )
    current -> HudRowColors(
        background = TvPlayerChrome.Selected,
        content = TvPlayerChrome.Paper,
        detail = TvPlayerChrome.Graphite,
        chevron = TvPlayerChrome.Faint,
    )
    else -> HudRowColors(
        background = Color.Transparent,
        content = TvPlayerChrome.Paper,
        detail = TvPlayerChrome.Graphite,
        chevron = TvPlayerChrome.Faint,
    )
}

/** The shared row ground: 34dp tall, a 10dp radius, filled by state. */
private fun Modifier.hudRow(background: Color): Modifier =
    heightIn(min = HudRowHeight)
        .clip(RoundedCornerShape(HudRowCorner))
        .background(background)

/** The leading check column, kept even when empty so labels line up. */
@Composable
private fun HudCheckSlot(checked: Boolean, tint: Color) {
    Box(modifier = Modifier.width(HudCheckColumn + 8.dp), contentAlignment = Alignment.CenterStart) {
        if (checked) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

@Composable
private fun HudChevron(tint: Color) {
    Icon(
        imageVector = Icons.Rounded.ChevronRight,
        contentDescription = null,
        tint = tint,
        modifier = Modifier
            .padding(start = 4.dp)
            .offset(x = 4.dp)
            .size(18.dp),
    )
}

/**
 * Fades content at whichever edge it can scroll past, instead of cutting a
 * row in half at the panel's edge. Nothing fades when nothing scrolls.
 */
private fun Modifier.hudFadingEdges(
    state: ScrollableState,
    top: Dp = 16.dp,
    bottom: Dp = 36.dp,
): Modifier = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        if (state.canScrollBackward) {
            drawRect(
                brush = Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black, endY = top.toPx()),
                size = size.copy(height = top.toPx()),
                blendMode = BlendMode.DstIn,
            )
        }
        if (state.canScrollForward) {
            val height = bottom.toPx()
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Black,
                    1f to Color.Transparent,
                    startY = size.height - height,
                    endY = size.height,
                ),
                topLeft = Offset(0f, size.height - height),
                size = size.copy(height = height),
                blendMode = BlendMode.DstIn,
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
            .heightIn(min = 110.dp)
            .padding(18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = message, style = TvPlayerType.Body)
    }
}

/**
 * Chapters pane — the active FileVersion's chapters as a table: number,
 * title, start, length. The current chapter carries a play glyph, a tint and
 * a progress line, and the list opens on it with focus there, so the chapters
 * around "now" are one press away. Selecting a chapter seeks to its start.
 */
@Composable
private fun HudChaptersPane(
    chapters: List<VersionChapter>,
    positionSec: Double,
    durationSec: Double,
    onSelectChapter: (Int) -> Unit,
    entryFocusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    if (chapters.isEmpty()) {
        HudEmptyStatePane("No chapters in this title", modifier)
        return
    }
    val currentIndex = chapters.indexOfLast { it.startSeconds <= positionSec }.coerceAtLeast(0)
    val current = chapters[currentIndex]
    val currentEnd = chapterEndSeconds(chapters, currentIndex, durationSec)
    // Three chapters of context above the current one, as in the mockup.
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 3).coerceAtLeast(0))

    Column(modifier = modifier.fillMaxWidth()) {
        HudEyebrow(
            text = if (chapters.size == 1) "1 chapter" else "${chapters.size} chapters",
            trailing = if (currentEnd > positionSec) {
                "${chapterTitle(current)} · ${formatTime(currentEnd - positionSec)} left in this chapter"
            } else {
                null
            },
        )
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .hudFadingEdges(listState, top = 30.dp, bottom = 40.dp),
        ) {
            itemsIndexed(
                chapters,
                key = { _, chapter -> "${chapter.index}:${chapter.startSeconds}:${chapter.title}" },
                contentType = { _, _ -> "hud-chapter" },
            ) { idx, ch ->
                val end = chapterEndSeconds(chapters, idx, durationSec)
                HudChapterRow(
                    number = idx + 1,
                    chapter = ch,
                    lengthSec = (end - ch.startSeconds).coerceAtLeast(0.0),
                    progress = if (idx == currentIndex && end > ch.startSeconds) {
                        ((positionSec - ch.startSeconds) / (end - ch.startSeconds)).toFloat().coerceIn(0f, 1f)
                    } else {
                        null
                    },
                    onSelect = { onSelectChapter(idx) },
                    focusRequester = entryFocusRequester.takeIf { idx == currentIndex },
                )
            }
        }
    }
}

/** A chapter's end: its own end time, else the next start, else the runtime. */
private fun chapterEndSeconds(chapters: List<VersionChapter>, index: Int, durationSec: Double): Double {
    val chapter = chapters[index]
    return when {
        chapter.endSeconds > chapter.startSeconds -> chapter.endSeconds
        index + 1 < chapters.size -> chapters[index + 1].startSeconds
        else -> durationSec
    }
}

private fun chapterTitle(chapter: VersionChapter): String =
    chapter.title.ifBlank { "Chapter ${chapter.index + 1}" }

@Composable
private fun HudChapterRow(
    number: Int,
    chapter: VersionChapter,
    lengthSec: Double,
    /** Set on the current chapter only: how far into it playback is. */
    progress: Float?,
    onSelect: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val isCurrent = progress != null
    val colors = hudRowColors(focused = isFocused, current = isCurrent)
    val numberColor = if (isFocused) TvPlayerChrome.InkMuted else TvPlayerChrome.Graphite

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .heightIn(min = 35.dp)
            .clip(RoundedCornerShape(HudRowCorner))
            .background(colors.background)
            .clickable(enabled = true, interactionSource = interactionSource, indication = null) { onSelect() }
            .semantics { selected = isCurrent },
    ) {
        Row(
            modifier = Modifier
                .matchParentSize()
                .padding(horizontal = HudRowPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.width(42.dp)) {
                if (isCurrent && !isFocused) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = "Now playing",
                        tint = TvPlayerChrome.Paper,
                        modifier = Modifier.size(18.dp),
                    )
                } else {
                    Text(
                        text = number.toString().padStart(2, '0'),
                        style = TvPlayerType.Figures.copy(fontWeight = FontWeight.SemiBold),
                        color = numberColor,
                    )
                }
            }
            Text(
                text = chapterTitle(chapter),
                style = TvPlayerType.Row.copy(fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Medium),
                color = colors.content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatTime(chapter.startSeconds),
                style = TvPlayerType.Figures,
                color = colors.detail,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier.width(70.dp),
            )
            Text(
                text = formatTime(lengthSec),
                style = TvPlayerType.Figures,
                color = colors.detail,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier.width(62.dp),
            )
        }
        if (progress != null) {
            // Under the title column only, so it reads as the chapter's own bar.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = HudRowPadding + 42.dp, end = HudRowPadding + 140.dp, bottom = 3.dp)
                    .fillMaxWidth()
                    .height(2.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(if (isFocused) TvPlayerChrome.Ink.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.14f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .height(2.dp)
                        .background(if (isFocused) TvPlayerChrome.Ink else TvPlayerChrome.Paper),
                )
            }
        }
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
    /** A short second line under the label, such as a subtitle's sync status. */
    val detail: String? = null,
    /** A short value at the row's end, such as "1×" beside "Normal" or a bitrate. */
    val trailing: String? = null,
)

/**
 * A request to present a centered picker dialog. Built by a pane row and handed
 * to the HUD, which owns the single active-dialog slot.
 */
internal data class HudPickerPresentation(
    val title: String,
    /** Names where the picker came from ("Video"); the HUD fills in its tab. */
    val eyebrow: String? = null,
    val options: List<HudPickerOption>,
    val selectedId: String,
    val focusedId: String = selectedId,
    val closeOnSelect: Boolean = true,
    val onFocused: (String) -> Unit = {},
    val onSelect: (String) -> Unit,
)

/**
 * The Timing row's value: the refusal or failure when there is one, else the
 * sync state. The refusal is shortened to fit the row; the line under it says
 * it in full.
 */
private fun subtitleTimingValue(timing: SubtitleTimingActions): String = when {
    timing.forbidden -> "Not allowed"
    timing.busy -> "Working…"
    else -> timing.error ?: timing.statusLabel ?: "Not synced"
}

/**
 * What the Timing row cannot fit: a running sync's progress and phase, the
 * last result, what a sync does, or why the actions are missing. It never
 * takes focus.
 */
@Composable
private fun HudSubtitleTimingDetail(timing: SubtitleTimingActions) {
    val modifier = Modifier.padding(horizontal = HudRowPadding, vertical = 4.dp)
    val percent = timing.percent
    if (timing.inProgress && percent != null) {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            TvSyncProgressBar(percent = percent)
            timing.phaseLabel?.let { HudTimingText(it) }
        }
    }
    val message = when {
        timing.forbidden -> SubtitleTimingActions.FORBIDDEN_MESSAGE to null
        timing.error != null -> timing.error.orEmpty() to Color(0xFFFCA5A5)
        timing.result != null -> timing.result?.let { it.text to if (it.warning) Color(0xFFFDE68A) else null }
        else -> timing.note?.let { it to null }
    }
    message?.let { (text, color) -> HudTimingText(text, color, modifier) }
}

@Composable
private fun HudTimingText(text: String, color: Color? = null, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = color ?: TvPlayerChrome.Graphite,
        style = TvPlayerType.RowDetail,
        modifier = modifier,
    )
}

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
 * Drill-in setting row: label, current value, chevron, inverting to Paper on
 * focus. Activating (Select) runs [onActivate], which the caller wires to
 * present a [HudPickerDialog] or a page. Mirrors tvOS `HUDFocusedSettingRow`.
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
    /**
     * False for a toggle row: Select flips the value in place, so there is no
     * drill-in to advertise. Mirrors tvOS HUDToggleRow (showsChevron: false).
     */
    showsChevron: Boolean = true,
    modifier: Modifier = Modifier,
    onActivate: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    // Own focus requester so the HUD can return focus to this row after the
    // picker it opens is dismissed (registered via LocalHudPickerReturnFocus).
    val selfFocusRequester = remember { FocusRequester() }
    val registerPickerReturnFocus = LocalHudPickerReturnFocus.current

    val colors = hudRowColors(focused = isFocused)
    val rowAlpha = if (enabled) 1f else 0.35f

    Row(
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(selfFocusRequester)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .then(if (entryFocusRequester != null) Modifier.focusRequester(entryFocusRequester) else Modifier)
            .graphicsLayer { alpha = rowAlpha }
            .hudRow(colors.background)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
            ) {
                registerPickerReturnFocus?.invoke(selfFocusRequester)
                onActivate()
            }
            .padding(horizontal = HudRowPadding, vertical = 4.dp),
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
            style = TvPlayerType.Row,
            color = colors.content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
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
                style = TvPlayerType.Value,
                color = colors.detail,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (showsChevron) HudChevron(colors.chevron)
        }
    }
}

/**
 * Centered modal option list mirroring tvOS `HUDPickerDialog`: an opaque card
 * with an eyebrow naming where it came from, a title, and the options, each
 * with a leading check column so the check stays visible on a focused row.
 * The card sizes to its options up to the screen, and scrolls past that.
 * Focus auto-lands on the selected option and scrolls it into view, Select
 * commits the option and closes, and Back closes (handled by the HUD's key
 * handler). Options commit on explicit Select, not focus, so D-pad traversal
 * doesn't change the value.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun HudPickerDialog(
    presentation: HudPickerPresentation,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = presentation.options
    // An action menu has no current choice, so nothing is marked selected.
    val selectedIndex = options.indexOfFirst { it.id == presentation.selectedId }
    val focusedIndex = options.indexOfFirst { it.id == presentation.focusedId }
        .takeIf { it >= 0 }
        ?: selectedIndex.coerceAtLeast(0)
    val focusRequester = remember { FocusRequester() }
    val listScroll = rememberScrollState()

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
            .heightIn(max = 470.dp)
            .tvDialogSurface(RoundedCornerShape(HudPanelCorner))
            .focusGroup()
            .focusProperties { exit = { FocusRequester.Cancel } }
            .padding(start = 14.dp, end = 14.dp, top = 20.dp, bottom = 14.dp),
    ) {
        Column {
            Column(modifier = Modifier.padding(start = HudRowPadding, end = HudRowPadding, bottom = 12.dp)) {
                presentation.eyebrow?.let { eyebrow ->
                    Text(
                        text = eyebrow.uppercase(),
                        style = TvPlayerType.Eyebrow,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                Text(text = presentation.title, style = TvPlayerType.DialogTitle)
            }
            // Fully compose this small modal list so every D-pad destination is
            // present in the focus graph. A lazy list made below-fold rows look
            // like the end of the modal and either trapped or leaked focus.
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .hudFadingEdges(listScroll)
                    .verticalScroll(listScroll),
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
    val colors = hudRowColors(focused = isFocused, current = isSelected)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hudRow(colors.background)
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
            .padding(horizontal = HudRowPadding, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HudCheckSlot(checked = isSelected, tint = colors.content)
        if (option.colorHex != null) {
            Box(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(hexToColor(option.colorHex))
                    .border(0.5.dp, Color.White.copy(alpha = 0.45f), CircleShape),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = option.label,
                style = TvPlayerType.Row,
                color = colors.content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            option.detail?.let { detail ->
                Text(
                    text = detail,
                    style = TvPlayerType.RowDetail,
                    color = colors.detail,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        option.trailing?.let { trailing ->
            Text(
                text = trailing,
                style = TvPlayerType.Value,
                color = colors.detail,
                maxLines = 1,
                modifier = Modifier.padding(start = 12.dp),
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
