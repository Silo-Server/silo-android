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
import androidx.compose.ui.res.stringResource
import org.koin.compose.koinInject
import org.siloserver.silo.android.ui.components.SiloConfirmDialog
import org.siloserver.silo.android.ui.components.SiloTopBar
import org.siloserver.silo.android.ui.screens.downloads.DownloadsViewModel
import org.siloserver.silo.android.ui.screens.home.HomeSectionsEditor
import org.siloserver.silo.android.ui.theme.SettingsDimens
import org.siloserver.silo.android.ui.theme.SiloSettingsBackground
import org.siloserver.silo.android.ui.util.formatBytes
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.common.i18n.AppLanguageStore
import org.siloserver.silo.common.i18n.currentFormatLocale
import org.siloserver.silo.common.profiles.ProfileLaunchBehavior
import org.siloserver.silo.common.profiles.ProfileLaunchPreferences
import org.siloserver.silo.common.settings.profileLaunchDescription
import org.siloserver.silo.common.settings.profileLaunchTitle
import org.siloserver.silo.common.settings.PlayerSettingsStore
import org.siloserver.silo.common.settings.TitleArtStore
import org.siloserver.silo.common.settings.UseProfileSettingsCopy
import org.siloserver.silo.common.settings.shownIn
import org.siloserver.silo.model.feature.MetadataAiFeatureStore
import org.siloserver.silo.model.metadata.MetadataAiOnView
import org.siloserver.silo.model.settings.SeekMedia
import org.siloserver.silo.common.settings.text

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
    val appLanguageStore: AppLanguageStore = koinInject()
    LaunchedEffect(appLanguageStore) { appLanguageStore.refresh() }

    SettingsPageScaffold(title = stringResource(CommonR.string.settings_interface_title), onBackClick = onBackClick) {
        item(key = "app-language") {
            AppLanguageSettingsSection(store = appLanguageStore)
        }
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
                title = stringResource(CommonR.string.settings_interface_home),
                footer = stringResource(CommonR.string.settings_interface_homeFooter),
            ) {
                SettingsNavigationRow(
                    label = stringResource(CommonR.string.settings_homeSections_title),
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

    SettingsPageScaffold(title = stringResource(CommonR.string.settings_playback), onBackClick = onBackClick) {
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

    SettingsPageScaffold(title = stringResource(CommonR.string.settings_subtitles), onBackClick = onBackClick) {
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

    SettingsPageScaffold(title = stringResource(CommonR.string.settings_downloads_title), onBackClick = onBackClick) {
        item(key = "downloads") {
            SettingsSection(
                title = stringResource(CommonR.string.settings_downloads_title),
                footer = stringResource(CommonR.string.settings_downloads_qualityFooter),
            ) {
                SettingsSwitchRow(
                    label = stringResource(CommonR.string.settings_downloads_wifiOnly),
                    checked = state.downloadsWifiOnly,
                    onCheckedChange = viewModel::setDownloadsWifiOnly,
                )
                SettingsDropdownRow(
                    label = stringResource(CommonR.string.settings_downloads_quality),
                    value = state.defaultDownloadQuality,
                    options = state.downloadQualityOptions,
                    onOptionSelected = viewModel::setDefaultDownloadQuality,
                )
            }
        }
        item(key = "cleanup") {
            SettingsSection(
                title = stringResource(CommonR.string.settings_downloads_cleanup),
                footer = stringResource(CommonR.string.settings_downloads_cleanupFooter),
            ) {
                SettingsSwitchRow(
                    label = stringResource(CommonR.string.settings_downloads_keepWatched),
                    checked = state.keepWatchedDownloads,
                    onCheckedChange = viewModel::setKeepWatchedDownloads,
                )
            }
        }
        item(key = "storage") {
            SettingsSection(title = stringResource(CommonR.string.settings_downloads_storage)) {
                SettingsNavigationRow(
                    label = stringResource(CommonR.string.settings_downloads_used),
                    value = formatBytes(downloadsState.totalBytesUsed, currentFormatLocale()),
                )
                if (!downloadsState.isEmpty || downloadsState.totalBytesUsed > 0L) {
                    SettingsDestructiveRow(
                        label = stringResource(
                            if (downloadsState.isRemovingAllDownloads) {
                                CommonR.string.settings_downloads_removingAll
                            } else {
                                CommonR.string.settings_downloads_removeAll
                            },
                        ),
                        enabled = !downloadsState.isRemovingAllDownloads,
                        onClick = { showRemoveAllDownloadsConfirm = true },
                    )
                }
            }
        }
    }

    if (showRemoveAllDownloadsConfirm) {
        SiloConfirmDialog(
            title = stringResource(CommonR.string.settings_downloads_removeAllConfirm_title),
            body = stringResource(
                CommonR.string.settings_downloads_removeAllConfirm_body,
                formatBytes(downloadsState.totalBytesUsed),
            ),
            confirmLabel = stringResource(
                if (downloadsState.isRemovingAllDownloads) {
                    CommonR.string.settings_downloads_removeAllConfirm_removing
                } else {
                    CommonR.string.settings_downloads_removeAllConfirm_confirm
                },
            ),
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

    SettingsPageScaffold(title = stringResource(CommonR.string.settings_notifications_title), onBackClick = onBackClick) {
        item(key = "in-app") {
            SettingsSection(
                title = null,
                footer = stringResource(CommonR.string.settings_notifications_inAppFooter),
            ) {
                SettingsSwitchRow(
                    label = stringResource(CommonR.string.settings_notifications_inApp),
                    checked = state.notificationsEnabled,
                    onCheckedChange = viewModel::setNotificationsEnabled,
                )
            }
        }
        if (state.notificationsEnabled) {
            item(key = "topics") {
                SettingsSection(
                    title = stringResource(CommonR.string.settings_notifications_topics),
                    footer = stringResource(CommonR.string.settings_notifications_topicsFooter),
                ) {
                    SettingsSwitchRow(
                        label = stringResource(CommonR.string.menu_favorites),
                        checked = state.notifyFavorites,
                        onCheckedChange = viewModel::setNotifyFavorites,
                    )
                    SettingsSwitchRow(
                        label = stringResource(CommonR.string.menu_watchlist),
                        checked = state.notifyWatchlist,
                        onCheckedChange = viewModel::setNotifyWatchlist,
                    )
                    SettingsSwitchRow(
                        label = stringResource(CommonR.string.home_section_continueWatching),
                        checked = state.notifyContinueWatching,
                        onCheckedChange = viewModel::setNotifyContinueWatching,
                    )
                    SettingsSwitchRow(
                        label = stringResource(CommonR.string.home_section_nextUp),
                        checked = state.notifyNextUp,
                        onCheckedChange = viewModel::setNotifyNextUp,
                    )
                }
            }
        }
    }
}

/**
 * Profile Selection (Apple Settings → General → Profile Selection): whether
 * Silo reopens the last profile or asks who's watching. The choice is kept on
 * this phone or tablet only and never sent to the server.
 */
@Composable
fun SettingsProfileSelectionScreen(onBackClick: () -> Unit) {
    val preferences: ProfileLaunchPreferences = koinInject()
    val launchState by preferences.state.collectAsState()

    SettingsPageScaffold(title = stringResource(CommonR.string.settings_control_profileSelection), onBackClick = onBackClick) {
        item(key = "profile-selection") {
            SettingsSection(
                title = null,
                footer = stringResource(CommonR.string.settings_profileSelection_footer),
            ) {
                ProfileLaunchBehavior.entries.forEach { behavior ->
                    SettingsChoiceRow(
                        label = stringResource(profileLaunchTitle(behavior)),
                        description = stringResource(
                            profileLaunchDescription(behavior, CommonR.string.settings_profileLaunch_automatic_detailPhone),
                        ),
                        selected = behavior == launchState.behavior,
                        onSelect = { preferences.setBehavior(behavior) },
                    )
                }
            }
        }
    }
}
