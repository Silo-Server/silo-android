package org.siloserver.silo.tv.ui.screens.player

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvPlayerControlsUsabilityTest {
    private val screenSource = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvPlayerScreen.kt",
    ).readText()
    private val clusterSource = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvPlayerTransportCluster.kt",
    ).readText()
    private val hudSource = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvPlayerHud.kt",
    ).readText()
    private val viewModelSource = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvPlayerViewModel.kt",
    ).readText()
    private val activitySource = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/MainTvActivity.kt",
    ).readText()
    private val routeSource = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/navigation/TvRoute.kt",
    ).readText()
    private val navigationSource = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/navigation/TvAppNavigation.kt",
    ).readText()
    private val remoteKeyBridgeSource = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvPlayerRemoteKeyBridge.kt",
    ).takeIf { it.exists() }?.readText().orEmpty()

    @Test
    fun idleOverlayDefaultsToPlayPauseInTransportDock() {
        assertTrue(screenSource.contains("LaunchedEffect(focusRequest.nonce)"))
        assertTrue(
            screenSource.contains(
                "TvIdleOverlayFocusTarget.Transport -> playPauseFocus.requestFocus()",
            ),
        )
        // Primary group pinned left + secondary group pushed right.
        assertTrue(clusterSource.contains("Arrangement.SpaceBetween"))
        assertTrue(clusterSource.contains("Icons.Filled.Replay10"))
        assertTrue(clusterSource.contains("Icons.Filled.Forward30"))
        assertTrue(clusterSource.contains("Icons.Filled.ClosedCaption"))
        assertTrue(clusterSource.contains("Icons.Filled.Tune"))
        assertTrue(clusterSource.contains("Icons.Filled.Close"))
    }

    @Test
    fun transportDropsBackButKeepsFastSubtitlePicker() {
        // No Back button and no generic More button. Subtitles are a primary
        // playback action, so the transport keeps a dedicated CC quick picker;
        // options (Tune) still opens the full HUD for style/search/AI.
        assertFalse(clusterSource.contains("Icons.AutoMirrored.Filled.ArrowBack"))
        assertFalse(clusterSource.contains("Icons.Filled.MoreHoriz"))
        assertFalse(clusterSource.contains("Icons.Filled.Forward10"))
        assertTrue(clusterSource.contains("Icons.Filled.ClosedCaption"))
        assertTrue(screenSource.contains("fun TvQuickSubtitlePicker("))
        assertTrue(screenSource.contains("onOpenQuickSubtitles"))
        assertTrue(screenSource.contains("showQuickSubtitlePicker"))
    }

    @Test
    fun hudIsAdaptiveTopCenterCardInsteadOfRightDrawer() {
        assertTrue(screenSource.contains("Alignment.TopCenter"))
        assertTrue(hudSource.contains("private val HudMaxWidth = 680.dp"))
        assertTrue(hudSource.contains("private val HudMinHeight = 290.dp"))
        assertTrue(hudSource.contains("private val HudMaxHeight = 360.dp"))
        assertTrue(hudSource.contains(".fillMaxWidth(0.72f)"))
        assertTrue(hudSource.contains(".heightIn(min = HudMinHeight, max = HudMaxHeight)"))
        assertFalse(hudSource.contains(".height(190.dp)"))
        assertFalse(hudSource.contains("private val HudMaxWidth = 600.dp"))
        assertFalse(hudSource.contains(".fillMaxWidth(0.8f)"))
        assertFalse(hudSource.contains(".fillMaxWidth(0.66f)"))
        assertFalse(hudSource.contains("PlayerSidePanel"))
        assertFalse(hudSource.contains(".width(560.dp)"))
    }

    @Test
    fun subtitleStyleSwatchesFitInsideReadableHud() {
        val styleSectionBlock = hudSource
            .substringAfter("private fun StyleSection")
            .substringBefore("private fun StyleColorSwatch")

        assertTrue(hudSource.contains(".size(24.dp)"))
        assertTrue(styleSectionBlock.contains("FlowRow("))
        assertTrue(styleSectionBlock.contains("horizontalArrangement = Arrangement.spacedBy(5.dp)"))
        assertTrue(styleSectionBlock.contains("verticalArrangement = Arrangement.spacedBy(5.dp)"))
        assertFalse(hudSource.contains(".size(36.dp)"))
        assertFalse(hudSource.contains(".size(28.dp)"))
        assertFalse(styleSectionBlock.contains(".horizontalScroll(rememberScrollState())"))
        assertTrue(hudSource.contains("modifier = Modifier.padding(top = 5.dp)"))
    }

    @Test
    fun hudTabsUseScrollSafePaneViewports() {
        assertTrue(hudSource.contains("private fun HudPaneViewport("))
        assertTrue(hudSource.contains("private fun HudTwoColumnPane("))
        assertTrue(hudSource.contains(".padding(bottom = HudPaneBottomPadding)"))
        assertTrue(hudSource.contains("HudTab.Info -> HudPaneViewport"))
        assertTrue(hudSource.contains("HudTab.Stats -> HudPaneViewport"))
        assertTrue(hudSource.contains("HudTab.Chapters -> HudPaneViewport"))
    }

    @Test
    fun videoTabUsesSettingRowsForSleepTimer() {
        assertTrue(hudSource.contains("label = \"Sleep timer\""))
        assertTrue(hudSource.contains("title = \"Sleep Timer\""))
        assertTrue(hudSource.contains("HudPickerOption(\"cancel\", \"Cancel timer\")"))
        assertFalse(hudSource.contains("SLEEP_TIMER_PRESETS.forEach { minutes ->"))
    }

    @Test
    fun chapterRowsCommitOnCenterSelect() {
        assertTrue(hudSource.contains("onSelect = { onSelectChapter(idx) }"))
        assertTrue(hudSource.contains("private fun HudChapterRow("))
        assertTrue(hudSource.contains(".clickable(enabled = true, interactionSource = interactionSource, indication = null) { onSelect() }"))
        assertFalse(hudSource.contains("onFocused = { onSelectChapter(idx) }"))
    }

    @Test
    fun chapterRowsUseReadableHudTypography() {
        assertTrue(hudSource.contains(".padding(horizontal = 14.dp, vertical = 9.dp)"))
        assertTrue(hudSource.contains("fontSize = HudBodyTextSize"))
        assertTrue(hudSource.contains("lineHeight = HudBodyLineHeight"))
        assertFalse(hudSource.contains("fontSize = 13.sp"))
        assertFalse(hudSource.contains("fontSize = 10.sp"))
        assertFalse(hudSource.contains("lineHeight = 12.sp"))
    }

    @Test
    fun statsPaneUsesHudTypographyInsteadOfDefaultBodyMedium() {
        val statsBlock = hudSource
            .substringAfter("private fun HudStatsPane")
            .substringBefore("private val PLAYBACK_SPEED_OPTIONS")

        assertTrue(statsBlock.contains("fontSize = HudBodyTextSize"))
        assertTrue(statsBlock.contains("lineHeight = HudBodyLineHeight"))
        assertTrue(statsBlock.contains("verticalArrangement = Arrangement.spacedBy(4.dp)"))
        assertFalse(statsBlock.contains("style = MaterialTheme.typography.bodyMedium,"))
    }

    @Test
    fun timelineShowsBufferedProgress() {
        assertTrue(screenSource.contains("bufferedAheadSec = bufferedAheadSec"))
        assertTrue(screenSource.contains("val bufferedAheadSec ="))
    }

    @Test
    fun timelineLabelsTimeAndChapterMarkers() {
        val scrubberSource = File(
            "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvPlayerScrubber.kt",
        ).readText()

        assertTrue(scrubberSource.contains("formatScrubberTime(positionSec)"))
        assertTrue(scrubberSource.contains("formatRemainingTime(durationSec - positionSec)"))
        assertTrue(scrubberSource.contains("Chapter marker"))
        assertTrue(scrubberSource.contains("alpha = 0.45f"))
    }

    @Test
    fun subtitleTrackRowsCommitOnSelectInsteadOfFocusTraversal() {
        // Picker-dialog options commit on explicit Select (click), not focus
        // traversal — so D-pad-scrolling the option list doesn't change the
        // value until the user presses Select.
        assertTrue(hudSource.contains("presentation.onSelect(option.id)"))
        assertTrue(hudSource.contains(".clickable(interactionSource = interactionSource, indication = null)"))
        assertFalse(hudSource.contains("option = opt,\n                onFocused"))
    }

    @Test
    fun quickSubtitlePickerUsesHudPickerModalForDpadNavigation() {
        val quickPickerBlock = screenSource
            .substringAfter("private fun TvQuickSubtitlePicker(")
            .substringBefore("/**\n * Top-end Watch Together status pill")

        assertTrue(quickPickerBlock.contains("HudPickerDialog("))
        assertTrue(quickPickerBlock.contains("HudPickerPresentation("))
        assertTrue(quickPickerBlock.contains("selectedId ="))
        assertTrue(quickPickerBlock.contains("Dialog("))
        assertTrue(quickPickerBlock.contains("DialogProperties(usePlatformDefaultWidth = false)"))
        assertFalse(quickPickerBlock.contains(".verticalScroll(rememberScrollState())"))
        assertFalse(quickPickerBlock.contains("TvDialogActionRow("))
    }

    @Test
    fun optionsButtonOpensFloatingHudWithSubtitleTab() {
        // The retired separate subtitles button + drawer are gone; subtitles are
        // now a HUD tab reached through the options (Tune) button.
        assertTrue(hudSource.contains("add(HudTab.Subtitles)"))
        assertTrue(hudSource.contains("HudTab.Subtitles -> HudSubtitlesPane"))
        assertTrue(screenSource.contains("onOpenHUD = onOpenHUD"))
        assertFalse(screenSource.contains("viewModel.openSubtitleMenu()"))
    }

    @Test
    fun subtitleTabAlwaysAvailableButAudioStillGated() {
        // Subtitles tab is unconditional — it hosts the Android-only Search /
        // AI-Translate / style controls that must stay reachable even when a
        // title carries no subtitle tracks (there is no separate subtitles
        // button anymore). Audio still hides when empty.
        assertTrue(hudSource.contains("add(HudTab.Subtitles)"))
        assertFalse(hudSource.contains("if (subtitleTracks.isNotEmpty()) add(HudTab.Subtitles)"))
        assertTrue(hudSource.contains("if (audioTracks.isNotEmpty()) add(HudTab.Audio)"))
    }

    @Test
    fun subtitleTabKeepsTrackSelectionDelayAndAppearance() {
        // Track selection, delay, size, and position are now drill-in setting
        // rows (HudFocusedSettingRow) that open a centered HudPickerDialog —
        // matching the tvOS row→picker-dialog model.
        assertTrue(hudSource.contains("fun HudSubtitlesPane("))
        assertTrue(hudSource.contains("onSelectSubtitle(id.toIntOrNull() ?: -1)"))
        assertTrue(hudSource.contains("delayPicker("))
        assertTrue(hudSource.contains("title = \"Subtitle Delay\""))
        assertTrue(hudSource.contains("title = \"Subtitle Size\""))
        assertTrue(hudSource.contains("title = \"Subtitle Position\""))
        // The shared row + dialog primitives exist.
        assertTrue(hudSource.contains("fun HudFocusedSettingRow("))
        assertTrue(hudSource.contains("fun HudPickerDialog("))
    }

    @Test
    fun subtitlePaneShowsAppearanceExampleAndColumnFocusAnchors() {
        assertTrue(hudSource.contains("private fun HudSubtitlePreview("))
        assertTrue(hudSource.contains("HudSubtitlePreview(appearance = appearance)"))
        assertTrue(hudSource.contains("Subtitle example"))
        assertTrue(hudSource.contains("val subtitleTrackFocus = remember { FocusRequester() }"))
        assertTrue(hudSource.contains("val subtitleTextColorFocus = remember { FocusRequester() }"))
        assertTrue(hudSource.contains("val subtitleBackgroundColorFocus = remember { FocusRequester() }"))
        assertTrue(hudSource.contains("val subtitleOutlineColorFocus = remember { FocusRequester() }"))
        assertTrue(hudSource.contains("rightFocusRequester = subtitleTextColorFocus"))
        assertTrue(hudSource.contains("rightFocusRequester = subtitleBackgroundColorFocus"))
        assertTrue(hudSource.contains("rightFocusRequester = subtitleOutlineColorFocus"))
        assertTrue(hudSource.contains("leftFocusRequester = subtitleTrackFocus"))
        assertTrue(hudSource.contains("focusRequester = if (index == 0) subtitleTextColorFocus else null"))
        assertTrue(hudSource.contains("focusRequester = if (index == 0) subtitleBackgroundColorFocus else null"))
        assertTrue(hudSource.contains("focusRequester = if (index == 0) subtitleOutlineColorFocus else null"))
    }

    @Test
    fun subtitlePaneShowsDirectNoBackgroundControlAndReadableWhiteSelection() {
        assertTrue(hudSource.contains("label = \"No background\""))
        // Must be a real toggle: On sets None, Off restores the default Box
        // background (a one-way None assignment left users stuck on On).
        assertTrue(hudSource.contains("SubtitleBackgroundStylePreset.Box"))
        assertTrue(hudSource.contains("appearance.copy(backgroundStyle = target)"))
        assertTrue(hudSource.contains("val isLightSwatch ="))
        assertTrue(hudSource.contains("Icons.Filled.Check"))
        assertTrue(hudSource.contains("checkTint"))
    }

    @Test
    fun videoPaneUsesRowDialogModelWithQualityPicker() {
        // The Video pane drives Quality / Speed / Aspect through the row→dialog
        // model; Quality is new and derived from the available video variants.
        assertTrue(hudSource.contains("title = \"Quality\""))
        assertTrue(hudSource.contains("title = \"Playback Speed\""))
        assertTrue(hudSource.contains("title = \"Aspect\""))
        // Speed presets aligned to tvOS (0.75 / 1.0 / 1.25 / 1.5 / 2.0).
        assertTrue(hudSource.contains("listOf(0.75, 1.0, 1.25, 1.5, 2.0)"))
        // The old inline "Fill mode" label is renamed to "Aspect".
        assertFalse(hudSource.contains("text = \"Fill mode\""))
    }

    @Test
    fun aspectSelectionAppliesToMedia3PlayerSurface() {
        assertTrue(screenSource.contains("applyPlayerViewVideoFillMode(view, state.videoFillMode)"))
        assertTrue(screenSource.contains("view.getVideoSurfaceView()"))
        assertTrue(screenSource.contains("fun resizeModeForVideoFillMode(mode: VideoFillMode): Int"))
        assertTrue(screenSource.contains("fun applyPlayerViewVideoFillMode(view: PlayerView, mode: VideoFillMode)"))
        assertFalse(screenSource.contains("applyMpvVideoScaleMode"))
        assertFalse(screenSource.contains("CropLetterboxSurfaceScale"))
        assertFalse(screenSource.contains("videoSurface.scaleX = surfaceScale"))
    }

    @Test
    fun qualityRowOffersServerTranscodeLadder() {
        // Quality is a server-transcode ladder (tvOS ApplePlaybackQuality parity):
        // selecting a rung re-requests the session at that quality rather than
        // pinning a Media3 adaptive-variant override.
        assertTrue(screenSource.contains("viewModel.switchQuality(id)"))
        // The ladder is built by the VM from the source resolution + selected
        // quality, offering at least Auto + Original (plus downscale rungs).
        assertTrue(viewModelSource.contains("fun transcodeQualityLadder("))
        assertTrue(viewModelSource.contains("PlaybackQuality.Auto, PlaybackQuality.Original"))
        // Row is enabled whenever more than one option exists (always ≥ 2), so a
        // single-file movie is no longer stuck with a disabled Quality row.
        assertTrue(hudSource.contains("val hasQualityChoice = videoQualities.size > 1"))
        assertTrue(hudSource.contains("enabled = enabled && hasQualityChoice"))
        // Quality is no longer keyed off the group-level videoTracks.size count.
        assertFalse(hudSource.contains("videoTracks.size > 1"))
    }

    @Test
    fun speedOptionIdsAreLocaleIndependent() {
        // The speed option id must round-trip on comma-decimal locales — build
        // and parse it with Locale.ROOT (not the default-locale "%.2f".format),
        // otherwise the selection silently no-ops on e.g. nl-NL.
        assertTrue(hudSource.contains("fun speedOptionId("))
        assertTrue(hudSource.contains("java.util.Locale.ROOT, \"%.2f\""))
        // The old default-locale id + toDoubleOrNull parse is gone.
        assertFalse(hudSource.contains("selectedId = \"%.2f\".format(playbackSpeed)"))
        assertFalse(hudSource.contains("id.toDoubleOrNull()?.let(onPlaybackSpeedChanged)"))
        // Commit matches by id, not by re-parsing a localized string.
        assertTrue(hudSource.contains("PLAYBACK_SPEED_OPTIONS.firstOrNull { speedOptionId(it) == id }"))
    }

    @Test
    fun backClosesOnlyThePickerWhilePickerOpen() {
        // Back must close the active picker first (consumed by the HUD), not the
        // whole HUD. The HUD's own key handler closes the picker; the screen-
        // level BackHandler defers (disabled) while a picker is open.
        assertTrue(hudSource.contains("if (activePicker != null) {"))
        assertTrue(hudSource.contains("activePicker = null"))
        assertTrue(hudSource.contains("onPickerOpenChanged(pickerOpen)"))
        assertTrue(screenSource.contains("BackHandler(enabled = !(state.hudOpen && hudPickerOpen))"))
        assertTrue(screenSource.contains("onPickerOpenChanged = { hudPickerOpen = it }"))
    }

    @Test
    fun pickerDialogIsAFocusTrap() {
        // The picker is a modal: D-pad must stay inside it. Focus is contained
        // with a focusGroup + cancelled exit, and the panes/tabs are blocked
        // from focus while a picker is open.
        assertTrue(hudSource.contains(".focusGroup()"))
        assertTrue(hudSource.contains("exit = { FocusRequester.Cancel }"))
        assertTrue(hudSource.contains("enabled = activePicker == null"))
    }

    @Test
    fun subtitleTabKeepsAndroidOnlySearchAndAiRows() {
        assertTrue(hudSource.contains("Search subtitles"))
        assertTrue(hudSource.contains("Translate with AI"))
        assertTrue(screenSource.contains("viewModel.openSubtitleSearchDialog()"))
        assertTrue(screenSource.contains("viewModel.openAiTranslateDialog()"))
    }

    @Test
    fun subtitleSelectionUpdatesUiAfterBackendTrackApply() {
        val selectionBlock = screenSource
            .substringAfter("val applyTvSubtitleSelection")
            .substringBefore("DisposableEffect(context)")
        val uiUpdateIndex = selectionBlock.indexOf("viewModel.onSubtitleSelectionApplied(idx)")
        val backendIndex = selectionBlock.indexOf("backend.selectSubtitle(selectedTrack)")
        val missingTrackGuardIndex = selectionBlock.indexOf("if (idx >= 0 && selectedTrack == null)")

        assertTrue(missingTrackGuardIndex >= 0, "unknown positive subtitle indexes must not be treated as Off")
        assertTrue(uiUpdateIndex >= 0, "selection should update the checkmark after the backend accepts it")
        assertTrue(backendIndex >= 0, "selection should still apply the Media3 subtitle track")
        assertTrue(
            backendIndex < uiUpdateIndex,
            "UI state must not advance until Media3 accepts the same subtitle track",
        )
    }

    @Test
    fun hiddenLeftRightDpadSkipsInsteadOfRevealingOverlay() {
        assertTrue(screenSource.contains("TvPlayerRemoteKeyAction.SkipBack"))
        assertTrue(screenSource.contains("TvPlayerRemoteKeyAction.SkipForward"))
        assertTrue(screenSource.contains("performRelativeSeek(-SKIP_BACK_MS"))
        assertTrue(screenSource.contains("performRelativeSeek(SKIP_FORWARD_MS"))
        assertTrue(
            screenSource.contains("tvPlayerIdleOverlayRemoteKeyAction("),
            "Visible idle overlay must use the tested remote-key mapping too.",
        )
        assertFalse(
            screenSource.contains("event.nativeKeyEvent.keyCode != KeyEvent.KEYCODE_BACK &&\n                    !state.showControls"),
            "Hidden controls must not treat every non-Back key as a chrome reveal; left/right are transport keys.",
        )
    }

    @Test
    fun manualSubtitleSelectionSuppressesAutoLanguageReselection() {
        val selectionBlock = screenSource
            .substringAfter("val applyTvSubtitleSelection")
            .substringBefore("fun handleSkipIntroNow")

        assertTrue(selectionBlock.contains("viewModel.onManualSubtitleSelectionIntent(idx)"))
        assertTrue(viewModelSource.contains("manualSubtitleSelectionApplied"))
        assertTrue(viewModelSource.contains("manualSubtitleSelectionApplied = true"))
        assertTrue(
            viewModelSource.contains("if (manualSubtitleSelectionApplied) return"),
            "Once the viewer explicitly selects a subtitle track, the auto preferred-language resolver " +
                "must not switch it back during the same playback session.",
        )
    }

    @Test
    fun skipIntroPromptIsVisibleOutsideHiddenTransportControlsAndCenterSelectSkips() {
        assertTrue(
            screenSource.contains("if (!state.hudOpen && !state.showNextUp)"),
            "Skip Intro must not be gated by showControls; otherwise the user has to press OK just to reveal it.",
        )
        assertFalse(
            screenSource.contains("if (state.showControls && !state.hudOpen)"),
            "The old showControls gate hid Skip Intro whenever transport chrome was hidden.",
        )
        assertTrue(screenSource.contains("latestIntroSkipState"))
        assertTrue(screenSource.contains("handleSkipIntroNow()"))
        assertTrue(
            screenSource.contains("KeyEvent.KEYCODE_DPAD_CENTER") &&
                screenSource.contains("IntroAutoSkipState.ShowingButton"),
            "D-pad center should activate a visible Skip Intro prompt directly instead of only revealing controls.",
        )
    }

    @Test
    fun skipIntroRoutesSeekThroughWatchTogetherTransportGate() {
        val skipBlock = screenSource
            .substringAfter("fun handleSkipIntroNow(): Boolean")
            .substringBefore("DisposableEffect(context)")

        assertTrue(skipBlock.contains("tvRoomTransportGate(latestRoomSnapshot, TvTransportIntent.Seek)"))
        assertTrue(skipBlock.contains("roomController.onUserSeek(target)"))
        assertTrue(skipBlock.contains("viewModel.seekImmediate(soloTarget)"))
    }

    @Test
    fun playerKeepsTvScreenAwakeDuringPlayback() {
        assertTrue(screenSource.contains("WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON"))
        assertTrue(screenSource.contains("window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)"))
        assertTrue(screenSource.contains("window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)"))
    }

    @Test
    fun activityDispatchesRemoteKeysToMountedPlayerWhenComposeFocusIsLost() {
        assertTrue(activitySource.contains("@SuppressLint(\"RestrictedApi\")"))
        assertTrue(activitySource.contains("override fun dispatchKeyEvent(event: KeyEvent): Boolean"))
        assertTrue(activitySource.contains("TvPlayerRemoteKeyBridge.dispatch(event)"))
        assertTrue(remoteKeyBridgeSource.contains("fun install(handler: (KeyEvent) -> Boolean)"))
        assertTrue(remoteKeyBridgeSource.contains("fun dispatch(event: KeyEvent): Boolean"))
        assertTrue(screenSource.contains("latestShowQuickSubtitlePicker"))
        assertTrue(screenSource.contains("latestShowQuickSubtitlePicker ||"))
    }

    @Test
    fun mountedPlayerRegistersRemoteKeyBridgeAndRefocusesTransport() {
        assertTrue(screenSource.contains("TvPlayerRemoteKeyBridge.install(handler)"))
        assertTrue(screenSource.contains("TvPlayerRemoteKeyBridge.clear(handler)"))
        assertTrue(screenSource.contains("requestIdleOverlayFocus(TvIdleOverlayFocusTarget.Transport)"))
        assertTrue(screenSource.contains("LaunchedEffect(focusRequest.nonce)"))
    }

    @Test
    fun showingAlreadyVisibleControlsRefreshesAutoHideTimer() {
        assertTrue(viewModelSource.contains("controlsVisibilityNonce"))
        assertTrue(viewModelSource.contains("controlsVisibilityNonce = if (visible)"))
        assertTrue(screenSource.contains("state.controlsVisibilityNonce"))
    }

    @Test
    fun endOfPlaybackSurfaceIsUpNextOverlayNotStillWatchingDialog() {
        // The pass-out "Still watching?" dialog is retired; the end-of-playback
        // surface is now the Up-Next overlay (16:9 mini-player + next-episode
        // panel with Play Now / Keep Watching / Back + a countdown ring).
        assertTrue(screenSource.contains("fun TvPlayerNextUpOverlay("))
        assertTrue(screenSource.contains("if (state.showNextUp) {"))
        assertTrue(screenSource.contains("title = \"Play Now\""))
        assertTrue(screenSource.contains("title = \"Keep Watching\""))
        assertTrue(screenSource.contains("fun TvCountdownRing("))
        assertFalse(screenSource.contains("fun TvStillWatchingDialog("))
        // The overlay is wired to the VM's next-episode + auto-advance state.
        assertTrue(viewModelSource.contains("val showNextUp: Boolean"))
        assertTrue(viewModelSource.contains("fun playNextEpisodeNow()"))
        assertTrue(viewModelSource.contains("fun dismissNextUp()"))
        assertTrue(viewModelSource.contains("nextUpCountdownSeconds"))
    }

    @Test
    fun upNextOverlayAutoPlaysNextEpisodeWhenCountdownReachesZero() {
        // The countdown ticker plays the next episode at zero (auto-advance),
        // and the pass-out gate suppresses the countdown (overlay shows with no
        // ring → explicit choice required) rather than blocking the surface.
        assertTrue(viewModelSource.contains("fun startNextUpCountdown()"))
        assertTrue(viewModelSource.contains("playNextEpisodeNow()"))
        assertTrue(viewModelSource.contains("val passOutGated ="))
        assertTrue(viewModelSource.contains("if (autoCountdown) startNextUpCountdown()"))
    }

    @Test
    fun explicitPlayNowResetsPassOutStreakWhileCountdownIncrementsIt() {
        // Bug #2: an explicit "Play Now" is active watching, so it RESETS the
        // pass-out / still-watching streak to 0. Only the automatic
        // countdown-expiry advance keeps incrementing it.
        val playNowBody = viewModelSource
            .substringAfter("fun playNextEpisodeNow() {")
            .substringBefore("}")
        assertTrue(
            playNowBody.contains("advanceToNextEpisode(nextAutoAdvanceCount = 0)"),
            "explicit Play Now must reset the pass-out streak to 0",
        )
        // The countdown-expiry path increments the streak.
        assertTrue(
            viewModelSource.contains("advanceToNextEpisode(nextAutoAdvanceCount = autoAdvanceCount + 1)"),
            "automatic countdown-expiry advance must keep incrementing the streak",
        )
    }

    @Test
    fun upNextDoesNotLatchUntilNextEpisodeResolvesSoCountdownReArms() {
        // Bug #3: don't latch autoAdvanceHandled before nextEpisode is resolved,
        // and re-arm the overlay countdown when nextEpisode arrives afterward.
        assertTrue(viewModelSource.contains("private fun commitApproachingEnd("))
        assertTrue(viewModelSource.contains("pendingApproachingEndVideoEnded"))
        // resolveNextEpisode completes a deferred end-of-playback arming.
        val resolveBody = viewModelSource
            .substringAfter("private fun resolveNextEpisode() {")
        assertTrue(
            resolveBody.contains("commitApproachingEnd(nextState"),
            "nextEpisode resolution must re-arm a pending end-of-playback overlay",
        )
    }

    @Test
    fun playNextCarriesCurrentQualityToNextEpisodeRoute() {
        val starterSource = File(
            "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvVideoPlaybackStarter.kt",
        ).readText()
        assertTrue(viewModelSource.contains("val preferredQuality: String? = null"))
        assertTrue(viewModelSource.contains("PlayNextRequest(next.contentId, nextAutoAdvanceCount, selectedQuality)"))
        assertTrue(screenSource.contains("onPlayNext(req.contentId, req.autoAdvanceCount, req.preferredQuality)"))
        assertTrue(routeSource.contains("val quality: String? = null"))
        assertTrue(routeSource.contains("VideoPlayerRouteArgs.normalizeQuality(quality)?.let { value ->"))
        assertTrue(routeSource.contains("add(\"quality=\${value.routeEncode()}\")"))
        assertTrue(navigationSource.contains("TvRoute.Player(contentId = nextContentId, quality = nextQuality"))
        assertTrue(navigationSource.contains("preferredQuality = preferredQuality"))
        // The saved preference supplies the default ceiling; an explicit
        // in-player selection remains a separate override.
        assertTrue(viewModelSource.contains("preferredQualityOverride = preferredQuality"))
        assertTrue(viewModelSource.contains("playbackQualityIntent = qualityOverride"))
        assertTrue(starterSource.contains("request.playbackQualityIntent ?: preferredQuality"))
        assertTrue(viewModelSource.contains("fun switchQuality(wireValue: String)"))
    }

    @Test
    fun scrubberDrawsIntroRegionAsCyanBandWhenKnown() {
        val scrubberSource = File(
            "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvPlayerScrubber.kt",
        ).readText()
        assertTrue(scrubberSource.contains("introRangeSec"))
        assertTrue(scrubberSource.contains("Color.Cyan"))
        // Screen passes the session intro range through to the scrubber.
        assertTrue(screenSource.contains("introRangeSec = introRange"))
    }

    @Test
    fun idleOverlayShowsStatusChipsInsteadOfFullScreenBufferingSpinner() {
        // Buffering surfaces as a top-right capsule (spinner + "Buffering") in
        // the idle overlay's statusColumn, plus a sleep-timer countdown chip —
        // mirroring tvOS. The centered full-screen spinner is now reserved for
        // the lifecycle Reconnecting state only.
        assertTrue(screenSource.contains("text = \"Buffering\""))
        assertTrue(screenSource.contains("Icons.Filled.Bedtime"))
        assertTrue(screenSource.contains("sessionState is SessionState.Reconnecting && !state.showNextUp"))
        assertFalse(screenSource.contains("val showSpinner = state.isBuffering"))
    }

    @Test
    fun titleMovesToQuietBottomLeftFooterWithoutPlayingChip() {
        // The boxed top-left "Playing" chip is replaced by a quiet bottom-left
        // caption above the scrubber: title + episode tag, shadowed, no box,
        // no "Playing" literal.
        assertTrue(screenSource.contains(".align(Alignment.BottomStart)"))
        assertFalse(screenSource.contains("text = \"Playing\""))
    }

    @Test
    fun holdSeekIndicatorUsesVerticalChipOverBarOverHintLayout() {
        val holdSeekSource = File(
            "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvHoldSeekIndicator.kt",
        ).readText()
        // Vertical Column layout, compact tvOS-mapped rate label + progress bar.
        assertTrue(holdSeekSource.contains("fontSize = 22.sp"))
        assertTrue(holdSeekSource.contains(".width(260.dp)"))
        assertTrue(holdSeekSource.contains("chip-over-bar-over-hint"))
    }

    @Test
    fun tvPlayerDetectsDirectStartupStallsAndUsesExistingFallbackPath() {
        assertTrue(screenSource.contains("PlaybackStartupStallDetector"))
        assertTrue(screenSource.contains("startupStallDetector.onMounted("))
        assertTrue(screenSource.contains("startupStallDetector.sample("))
        assertTrue(screenSource.contains("viewModel.onUnsupportedPlayback(reason)"))
        assertTrue(viewModelSource.contains("Playability.StartupStalled"))
    }
}
