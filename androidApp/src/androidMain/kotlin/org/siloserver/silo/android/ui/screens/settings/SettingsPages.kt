package org.siloserver.silo.android.ui.screens.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import org.koin.compose.koinInject
import org.siloserver.silo.android.ui.components.SiloConfirmDialog
import org.siloserver.silo.android.ui.components.SiloTopBar
import org.siloserver.silo.android.ui.screens.downloads.DownloadsViewModel
import org.siloserver.silo.android.ui.screens.home.HomeSectionsEditor
import org.siloserver.silo.android.ui.theme.SettingsDimens
import org.siloserver.silo.android.ui.theme.SiloSettingsBackground
import org.siloserver.silo.android.ui.util.formatBytes
import org.siloserver.silo.common.profiles.ProfileLaunchBehavior
import org.siloserver.silo.common.profiles.ProfileLaunchPreferences
import org.siloserver.silo.common.settings.PlayerSettingsStore
import org.siloserver.silo.common.settings.TitleArtStore
import org.siloserver.silo.common.settings.UseProfileSettingsCopy
import org.siloserver.silo.common.settings.shownIn
import org.siloserver.silo.model.feature.MetadataAiFeatureStore
import org.siloserver.silo.model.metadata.MetadataAiOnView
import org.siloserver.silo.model.settings.SeekMedia

// The sub-pages the Settings overview pushes, one per Apple Settings page.
// Each shares the overview's SettingsViewModel (AppNavigation hands it the
// overview's back-stack entry), so a change made here is the value the
// overview row shows on the way back.

/** A Settings sub-page: a centred inline title and a grouped list. */
@Composable
internal fun SettingsPageScaffold(
    title: String,
    onBackClick: () -> Unit,
    content: LazyListScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            SiloTopBar(
                title = title,
                onBackClick = onBackClick,
                containerColor = SiloSettingsBackground,
                centerTitle = true,
            )
        },
        containerColor = SiloSettingsBackground,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                start = SettingsDimens.pageGutter,
                end = SettingsDimens.pageGutter,
                top = SettingsDimens.pageTopPadding,
                bottom = SettingsDimens.pageBottomSpacer,
            ),
            verticalArrangement = Arrangement.spacedBy(SettingsDimens.sectionGap),
            content = content,
        )
    }
}

/**
 * Interface (Apple `InterfaceCustomizationView`): Cards & Posters, Home,
 * Title Pages, then Theme Music.
 */
@Composable
fun SettingsInterfaceScreen(viewModel: SettingsViewModel, onBackClick: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    var showHomeSectionsEditor by rememberSaveable { mutableStateOf(false) }
    val titleArtStore: TitleArtStore = koinInject()
    val playerSettingsStore: PlayerSettingsStore = koinInject()
    // Opening Interface is a refresh edge, so a title art choice made on
    // another device shows here without waiting for the next foreground. Kept
    // out of the LazyColumn item, which re-enters composition on scroll.
    LaunchedEffect(titleArtStore) { titleArtStore.refresh() }

    SettingsPageScaffold(title = "Interface", onBackClick = onBackClick) {
        item(key = "cards") {
            MediaCardsSettings(
                state = state.cardPresentation,
                onPosterSizeSelected = viewModel::setCardPosterSize,
                onDeviceOnlyChanged = viewModel::setCardDeviceOnly,
                onUseProfileDefault = viewModel::useCardProfileDefault,
            )
        }
        item(key = "home") {
            SettingsSection(
                title = "Home",
                footer = "Choose which Home rows are visible and the order they appear in.",
            ) {
                SettingsNavigationRow(
                    label = "Home Sections",
                    onClick = { showHomeSectionsEditor = true },
                )
            }
        }
        item(key = "title-art") {
            TitleArtSettingsSection(store = titleArtStore)
        }
        item(key = "theme-music") {
            ThemeMusicSettingsSection(store = playerSettingsStore)
        }
    }

    if (showHomeSectionsEditor) {
        HomeSectionsEditor(onDismiss = { showHomeSectionsEditor = false })
    }
}

/**
 * Playback (Apple `PlaybackSettingsView`): Streaming, Episodes, the Video and
 * Audiobooks skip intervals, and the reset.
 */
@Composable
fun SettingsPlaybackScreen(viewModel: SettingsViewModel, onBackClick: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    val seekIntervals by viewModel.seekIntervals.state.collectAsState()
    val connectionSpeed = viewModel.connectionSpeed?.state?.collectAsState()?.value
    val context = LocalContext.current
    // Re-reads the learned limit and connection speed while the page shows, and
    // after a network change: a playback since the last visit can change them,
    // and the page can stay in the back stack under the player.
    LaunchedEffect(viewModel) { viewModel.connectionSpeed?.follow() }
    LaunchedEffect(state.playbackOverridesReset) {
        val landed = state.playbackOverridesReset ?: return@LaunchedEffect
        Toast.makeText(context, UseProfileSettingsCopy.notice(landed), Toast.LENGTH_SHORT).show()
        viewModel.onPlaybackOverridesResetShown()
    }

    SettingsPageScaffold(title = "Playback", onBackClick = onBackClick) {
        item(key = "streaming") {
            PlaybackStreamingSection(
                qualityResolution = state.qualityResolution,
                maxBitrateKbps = state.maxBitrateKbps,
                audioLanguage = state.audioLanguage,
                audioLanguageSuggestions = state.audioLanguageSuggestions,
                dolbyVisionEnabled = state.dolbyVisionEnabled,
                dvProfile7HDR10Fallback = state.dvProfile7HDR10Fallback,
                pictureInPictureEnabled = state.pictureInPictureEnabled,
                bufferingQualityMode = state.bufferingQualityMode,
                bufferingQualityModeSupported = state.bufferingQualityModeSupported,
                learnedLimit = state.learnedLimit.shownIn(state.bufferingQualityMode),
                learnedLimitResetFailed = state.learnedLimitResetFailed,
                deviceOverrides = state.deviceOverrides,
                onResolutionSelected = viewModel::setPreferredQuality,
                onMaxBitrateSelected = viewModel::setMaxBitrateKbps,
                onAudioLanguageChanged = viewModel::setAudioLanguage,
                onDolbyVisionEnabledChanged = viewModel::setDolbyVisionEnabled,
                onDvProfile7HDR10FallbackChanged = viewModel::setDvProfile7HDR10Fallback,
                onPictureInPictureEnabledChanged = viewModel::setPictureInPictureEnabled,
                onBufferingQualityModeChanged = viewModel::setBufferingQualityMode,
                onResetLearnedLimit = viewModel::resetLearnedLimit,
                onUseProfileSetting = viewModel::useProfileSetting,
            )
        }
        // Under Streaming, whose buffering and learned-limit rows it explains.
        if (connectionSpeed?.visible == true) {
            item(key = "connection") {
                PlaybackConnectionSection(
                    state = connectionSpeed.shownIn(state.bufferingQualityMode, state.learnedLimit),
                    onTestSpeed = { viewModel.connectionSpeed?.testSpeed() },
                )
            }
        }
        item(key = "cellular") {
            PlaybackCellularSection(
                resolution = state.cellularQualityResolution,
                maxBitrateKbps = state.cellularMaxBitrateKbps,
                deviceOverrides = state.deviceOverrides,
                onPresetSelected = viewModel::setCellularQualityPreset,
                onUseProfileSetting = viewModel::useProfileSetting,
            )
        }
        item(key = "episodes") {
            PlaybackEpisodesSection(
                autoPlayNext = state.autoPlayNext,
                nextUpPromptSeconds = state.nextUpPromptSeconds,
                introSkipMode = state.introSkipMode,
                autoSkipCredits = state.autoSkipCredits,
                resumeRewindSeconds = state.resumeRewindSeconds,
                passOutThreshold = state.passOutThreshold,
                deviceOverrides = state.deviceOverrides,
                onAutoPlayNextChanged = viewModel::setAutoPlayNext,
                onNextUpPromptSecondsChanged = viewModel::setNextUpPromptSeconds,
                onIntroSkipModeChanged = viewModel::setIntroSkipMode,
                onAutoSkipCreditsChanged = viewModel::setAutoSkipCredits,
                onResumeRewindSecondsChanged = viewModel::setResumeRewindSeconds,
                onPassOutThresholdChanged = viewModel::setPassOutThreshold,
                onUseProfileSetting = viewModel::useProfileSetting,
            )
        }
        if (state.episodeSpoilers.isSupported) {
            item(key = "spoilers") {
                EpisodeSpoilerSettings(
                    hideImages = state.episodeSpoilers.hideImages,
                    hideOverviews = state.episodeSpoilers.hideOverviews,
                    onHideImagesChanged = viewModel::setHideUnwatchedEpisodeImages,
                    onHideOverviewsChanged = viewModel::setHideUnwatchedEpisodeOverviews,
                    saveError = state.episodeSpoilerSaveError,
                )
            }
        }
        SeekMedia.entries.forEach { media ->
            item(key = "seek-$media") {
                SeekIntervalSettings(
                    media = media,
                    state = seekIntervals,
                    onIntervalSelected = viewModel.seekIntervals::select,
                    onImportLegacyAudiobook = viewModel.seekIntervals::importLegacyAudiobook,
                    onRetry = viewModel.seekIntervals::refresh,
                )
            }
        }
        item(key = "reset") {
            PlaybackResetSection(onResetPlaybackOverrides = viewModel::resetPlaybackOverrides)
        }
    }
}

/**
 * Subtitles (Apple `SubtitleSettingsView`): Profile, Metadata, Appearance, then
 * the Text, Background, and Layout groups, which dim while the device caption
 * style is in use.
 */
@Composable
fun SettingsSubtitlesScreen(viewModel: SettingsViewModel, onBackClick: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    val metadataAiStore: MetadataAiFeatureStore = koinInject()
    val metadataAiStatus by metadataAiStore.status.collectAsState()
    val metadataLanguageEnabled = metadataAiStatus.enabled && metadataAiStatus.onView != MetadataAiOnView.Off
    val customAppearance = !state.subtitleMatchesDevice

    SettingsPageScaffold(title = "Subtitles", onBackClick = onBackClick) {
        item(key = "profile") {
            SubtitleProfileSection(
                subtitleLanguage = state.subtitleLanguage,
                subtitleLanguageSuggestions = state.subtitleLanguageSuggestions,
                subtitleMode = state.subtitleMode,
                showForcedSubtitles = state.showForcedSubtitles,
                onLanguageChanged = viewModel::setSubtitleLanguage,
                onModeChanged = viewModel::setSubtitleMode,
                onForcedSubtitlesChanged = viewModel::setShowForcedSubtitles,
            )
        }
        if (metadataLanguageEnabled) {
            item(key = "metadata") {
                SubtitleMetadataSection(
                    metadataLanguage = state.metadataLanguage,
                    metadataLanguageSuggestions = state.metadataLanguageSuggestions,
                    onMetadataLanguageChanged = viewModel::setMetadataLanguage,
                )
            }
        }
        item(key = "appearance") {
            SubtitleAppearanceSection(
                previewAppearance = state.effectiveSubtitleAppearance,
                subtitleMatchesDevice = state.subtitleMatchesDevice,
                onSubtitleMatchesDeviceChanged = viewModel::setSubtitleMatchesDevice,
                subtitleUsesDeviceOverride = state.subtitleUsesDeviceOverride,
                onSubtitleDeviceOverrideChanged = viewModel::setSubtitleDeviceOverrideEnabled,
            )
        }
        item(key = "text") {
            SubtitleTextSection(
                appearance = state.subtitleAppearance,
                enabled = customAppearance,
                showTextOpacity = state.subtitleTextOpacitySupported,
                onEdit = viewModel::editSubtitleAppearance,
            )
        }
        item(key = "background") {
            SubtitleBackgroundSection(
                appearance = state.subtitleAppearance,
                enabled = customAppearance,
                onEdit = viewModel::editSubtitleAppearance,
            )
        }
        item(key = "layout") {
            SubtitleLayoutSection(
                appearance = state.subtitleAppearance,
                enabled = customAppearance,
                onEdit = viewModel::editSubtitleAppearance,
            )
        }
    }
}

/** Downloads (Apple `DownloadsSettingsView`): Downloads, Cleanup, Storage. */
@Composable
fun SettingsDownloadsScreen(
    viewModel: SettingsViewModel,
    downloadsViewModel: DownloadsViewModel,
    onBackClick: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val downloadsState by downloadsViewModel.uiState.collectAsState()
    var showRemoveAllDownloadsConfirm by rememberSaveable { mutableStateOf(false) }

    SettingsPageScaffold(title = "Downloads", onBackClick = onBackClick) {
        item(key = "downloads") {
            SettingsSection(title = "Downloads", footer = "Quality used for new downloads.") {
                SettingsSwitchRow(
                    label = "Download over Wi-Fi Only",
                    checked = state.downloadsWifiOnly,
                    onCheckedChange = viewModel::setDownloadsWifiOnly,
                )
                SettingsDropdownRow(
                    label = "Quality",
                    value = state.defaultDownloadQuality,
                    options = state.downloadQualityOptions,
                    onOptionSelected = viewModel::setDefaultDownloadQuality,
                )
            }
        }
        item(key = "cleanup") {
            SettingsSection(
                title = "Cleanup",
                footer = "When off, the Downloads tab suggests freeing up space by removing items " +
                    "you've finished watching.",
            ) {
                SettingsSwitchRow(
                    label = "Keep Watched Downloads",
                    checked = state.keepWatchedDownloads,
                    onCheckedChange = viewModel::setKeepWatchedDownloads,
                )
            }
        }
        item(key = "storage") {
            SettingsSection(title = "Storage") {
                SettingsNavigationRow(label = "Used", value = formatBytes(downloadsState.totalBytesUsed))
                if (!downloadsState.isEmpty || downloadsState.totalBytesUsed > 0L) {
                    SettingsDestructiveRow(
                        label = if (downloadsState.isRemovingAllDownloads) {
                            "Removing Downloads…"
                        } else {
                            "Remove All Downloads"
                        },
                        enabled = !downloadsState.isRemovingAllDownloads,
                        onClick = { showRemoveAllDownloadsConfirm = true },
                    )
                }
            }
        }
    }

    if (showRemoveAllDownloadsConfirm) {
        SiloConfirmDialog(
            title = "Remove all downloads?",
            body = "This removes ${formatBytes(downloadsState.totalBytesUsed)} of downloaded files " +
                "from this device. Your library and server media stay intact.",
            confirmLabel = if (downloadsState.isRemovingAllDownloads) "Removing…" else "Remove all",
            confirmEnabled = !downloadsState.isRemovingAllDownloads,
            onConfirm = {
                showRemoveAllDownloadsConfirm = false
                downloadsViewModel.removeAllDownloads()
            },
            onDismiss = { showRemoveAllDownloadsConfirm = false },
        )
    }
}

/**
 * Notifications. The Apple apps have no in-app notification preferences;
 * Android keeps them on their own page under Preferences.
 */
@Composable
fun SettingsNotificationsScreen(viewModel: SettingsViewModel, onBackClick: () -> Unit) {
    val state by viewModel.uiState.collectAsState()

    SettingsPageScaffold(title = "Notifications", onBackClick = onBackClick) {
        item(key = "in-app") {
            SettingsSection(
                title = null,
                footer = "Show alerts inside Silo as new releases arrive.",
            ) {
                SettingsSwitchRow(
                    label = "In-App Notifications",
                    checked = state.notificationsEnabled,
                    onCheckedChange = viewModel::setNotificationsEnabled,
                )
            }
        }
        if (state.notificationsEnabled) {
            item(key = "topics") {
                SettingsSection(
                    title = "Notify Me About",
                    footer = "Next Up tells you when a new episode becomes the next one to watch in a series " +
                        "you're following. Titles you requested or added to your watchlist notify you when " +
                        "they arrive.",
                ) {
                    SettingsSwitchRow(
                        label = "Next Up",
                        checked = state.notifyNextUp,
                        onCheckedChange = viewModel::setNotifyNextUp,
                    )
                }
            }
        }
    }
}

/** How Profile Selection's descriptions name this device. */
internal const val PROFILE_LAUNCH_DEVICE = "this device"

/**
 * Profile Selection (Apple Settings → General → Profile Selection): whether
 * Silo reopens the last profile or asks who's watching. The choice is kept on
 * this phone or tablet only and never sent to the server.
 */
@Composable
fun SettingsProfileSelectionScreen(onBackClick: () -> Unit) {
    val preferences: ProfileLaunchPreferences = koinInject()
    val launchState by preferences.state.collectAsState()

    SettingsPageScaffold(title = "Profile Selection", onBackClick = onBackClick) {
        item(key = "profile-selection") {
            SettingsSection(
                title = null,
                footer = "Choose when Silo asks who's watching. This applies only to this device.",
            ) {
                ProfileLaunchBehavior.entries.forEach { behavior ->
                    SettingsChoiceRow(
                        label = behavior.title,
                        description = behavior.description(PROFILE_LAUNCH_DEVICE),
                        selected = behavior == launchState.behavior,
                        onSelect = { preferences.setBehavior(behavior) },
                    )
                }
            }
        }
    }
}
