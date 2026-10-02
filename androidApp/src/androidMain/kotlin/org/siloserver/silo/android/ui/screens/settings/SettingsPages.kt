package org.siloserver.silo.android.ui.screens.settings

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
import org.koin.compose.koinInject
import org.siloserver.silo.android.ui.components.SiloConfirmDialog
import org.siloserver.silo.android.ui.components.SiloTopBar
import org.siloserver.silo.android.ui.screens.downloads.DownloadsViewModel
import org.siloserver.silo.android.ui.screens.home.HomeSectionsEditor
import org.siloserver.silo.android.ui.theme.SettingsDimens
import org.siloserver.silo.android.ui.theme.SiloSettingsBackground
import org.siloserver.silo.android.ui.util.formatBytes
import org.siloserver.silo.common.settings.TitleArtStore
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
 * Interface (Apple `InterfaceCustomizationView`): Cards & Posters, Home, then
 * Title Pages.
 */
@Composable
fun SettingsInterfaceScreen(viewModel: SettingsViewModel, onBackClick: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    var showHomeSectionsEditor by rememberSaveable { mutableStateOf(false) }
    val titleArtStore: TitleArtStore = koinInject()
    // Opening Interface is a refresh edge, so a title art choice made on
    // another device shows here without waiting for the next foreground. Kept
    // out of the LazyColumn item, which re-enters composition on scroll.
    LaunchedEffect(titleArtStore) { titleArtStore.refresh() }

    SettingsPageScaffold(title = "Interface", onBackClick = onBackClick) {
        item(key = "cards") {
            MediaCardsSettings(
                state = state.cardPresentation,
                onPresetSelected = viewModel::setCardPreset,
                onPosterSizeSelected = viewModel::setCardPosterSize,
                onCaptionSelected = viewModel::setCardCaption,
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
                onQualityPresetSelected = viewModel::setQualityPreset,
                onAudioLanguageChanged = viewModel::setAudioLanguage,
                onDolbyVisionEnabledChanged = viewModel::setDolbyVisionEnabled,
                onDvProfile7HDR10FallbackChanged = viewModel::setDvProfile7HDR10Fallback,
                onPictureInPictureEnabledChanged = viewModel::setPictureInPictureEnabled,
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
                onAutoPlayNextChanged = viewModel::setAutoPlayNext,
                onNextUpPromptSecondsChanged = viewModel::setNextUpPromptSeconds,
                onIntroSkipModeChanged = viewModel::setIntroSkipMode,
                onAutoSkipCreditsChanged = viewModel::setAutoSkipCredits,
                onResumeRewindSecondsChanged = viewModel::setResumeRewindSeconds,
                onPassOutThresholdChanged = viewModel::setPassOutThreshold,
            )
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
                appearance = state.subtitleAppearance,
                subtitleMatchesDevice = state.subtitleMatchesDevice,
                onSubtitleMatchesDeviceChanged = viewModel::setSubtitleMatchesDevice,
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
                    footer = "Favorites and Next Up cover new episodes of series you follow. Watchlist covers " +
                        "titles that become available. Continue Watching covers titles you started but " +
                        "haven't finished.",
                ) {
                    SettingsSwitchRow(
                        label = "Favorites",
                        checked = state.notifyFavorites,
                        onCheckedChange = viewModel::setNotifyFavorites,
                    )
                    SettingsSwitchRow(
                        label = "Watchlist",
                        checked = state.notifyWatchlist,
                        onCheckedChange = viewModel::setNotifyWatchlist,
                    )
                    SettingsSwitchRow(
                        label = "Continue Watching",
                        checked = state.notifyContinueWatching,
                        onCheckedChange = viewModel::setNotifyContinueWatching,
                    )
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
