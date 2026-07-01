@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.continuum.app.tv.di

import com.continuum.app.network.ApiResult
import com.continuum.app.repository.SettingsRepository
import com.continuum.app.tv.data.preferences.LegacyTvPrefsMigration
import com.continuum.app.tv.data.preferences.TvLibrarySelectionStore
import com.continuum.app.common.network.AndroidDeviceMetadataProvider
import com.continuum.app.common.settings.AndroidServerSettingsCache
import android.content.SharedPreferences
import com.continuum.app.network.AndroidServerRegistry
import com.continuum.app.network.EncryptedTokenManagerImpl
import com.continuum.app.network.ServerRegistry
import com.continuum.app.network.TokenManager
import com.continuum.app.network.createSecureSharedPrefs
import com.continuum.app.tv.ui.screens.servers.TvServerListViewModel
import com.continuum.app.common.player.AudioCapabilityManager
import com.continuum.app.common.player.AudioTrackManager
import com.continuum.app.common.player.PlaybackCapabilityDetector
import com.continuum.app.common.player.backend.VideoPlaybackBackendFactory
import com.continuum.app.tv.ui.screens.settings.TvSettingsViewModel
import com.continuum.app.common.player.ContinuumPlayerFactory
import com.continuum.app.common.player.PlaybackSessionManager
import com.continuum.app.common.player.SubtitleManager
import com.continuum.app.common.player.video.VideoPlaybackSessionCoordinator
import com.continuum.app.common.player.video.VideoPlaybackStarter
import com.continuum.app.tv.ui.screens.player.TvPlayerLaunchArgs
import com.continuum.app.tv.ui.screens.auth.TvLoginViewModel
import com.continuum.app.tv.ui.screens.auth.TvServerSetupViewModel
import com.continuum.app.tv.ui.screens.collections.TvCollectionDetailViewModel
import com.continuum.app.viewmodel.AdminStatsViewModel
import com.continuum.app.viewmodel.CalendarViewModel
import com.continuum.app.viewmodel.CollectionsViewModel
import com.continuum.app.tv.ui.screens.detail.TvItemDetailViewModel
import com.continuum.app.viewmodel.HomeViewModel
import com.continuum.app.viewmodel.RecommendationsViewModel
import com.continuum.app.viewmodel.MyRequestsViewModel
import com.continuum.app.viewmodel.RequestSearchViewModel
import com.continuum.app.viewmodel.RequestsViewModel
import com.continuum.app.tv.ui.screens.libraries.TvLibrariesViewModel
import com.continuum.app.tv.ui.screens.library.TvLibraryCollectionDetailViewModel
import com.continuum.app.tv.ui.screens.library.TvLibraryDetailViewModel
import com.continuum.app.viewmodel.FavoritesViewModel
import com.continuum.app.viewmodel.HistoryViewModel
import com.continuum.app.viewmodel.WatchlistViewModel
import com.continuum.app.tv.ui.screens.player.TvPlayerViewModel
import com.continuum.app.tv.ui.screens.player.TvVideoPlaybackStarter
import com.continuum.app.tv.ui.screens.profiles.TvProfileSelectionViewModel
import com.continuum.app.tv.ui.screens.search.TvSearchViewModel
import com.continuum.app.tv.watchnext.WatchNextRepository
import com.continuum.app.tv.watchnext.WatchNextSeeder
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * TV-specific Koin module.
 *
 * Provides the TV flavor of player infrastructure (currently a duplicate of the
 * phone module — see the TODO on [ContinuumPlayerFactory]) and the TV ViewModels.
 * The shared [com.continuum.app.di.sharedModules] is added alongside this one in
 * [com.continuum.app.tv.ContinuumTvApplication].
 */
val androidTvModule = module {
    // Single encrypted prefs handle shared between the server registry and
    // the token manager — see the phone module for rationale.
    single<SharedPreferences> { createSecureSharedPrefs(androidContext()) }

    // Multi-server registry. Loaded synchronously in init so MainTvActivity's
    // runBlocking-resolved start destination sees consistent state.
    single<ServerRegistry> { AndroidServerRegistry(androidContext(), get()) }

    // Persistent (EncryptedSharedPreferences-backed) replacement for the
    // commonMain in-memory TokenManager. Koin 3.1+ replaces same-key bindings
    // when the redefining module is loaded after the original — sharedModules()
    // is registered first in ContinuumTvApplication, so this wins.
    single<TokenManager> { EncryptedTokenManagerImpl(get(), get()) }

    // Offline-first Room store (Track B). Bound after sharedModules() so the
    // commonMain PersonalDataRepository's `getOrNull<UserItemStatePort>()` picks
    // up the Room-backed port and writes optimistic projection + outbox rows.
    single { com.continuum.app.common.data.db.SiloDatabase.build(androidContext()) }
    single<com.continuum.app.common.data.sync.OutboxSyncScheduler> {
        val appContext = androidContext().applicationContext
        com.continuum.app.common.data.sync.OutboxSyncScheduler {
            com.continuum.app.common.data.sync.SyncWorker.enqueue(appContext)
        }
    }
    single<com.continuum.app.repository.port.UserItemStatePort> {
        val tokenManager: TokenManager = get()
        com.continuum.app.common.data.repository.RoomUserItemStateRepository(
            db = get(),
            snapshotProvider = { tokenManager.snapshotCurrentScope() },
            syncScheduler = get(),
        )
    }
    single<com.continuum.app.repository.port.HomeCachePort> {
        val tokenManager: TokenManager = get()
        com.continuum.app.common.data.repository.RoomHomeCacheRepository(
            db = get(),
            snapshotProvider = { tokenManager.snapshotCurrentScope() },
        )
    }
    single<com.continuum.app.repository.port.CatalogCachePort> {
        val tokenManager: TokenManager = get()
        com.continuum.app.common.data.repository.RoomCatalogCacheRepository(
            db = get(),
            snapshotProvider = { tokenManager.snapshotCurrentScope() },
        )
    }
    single<com.continuum.app.repository.port.DownloadDeletionPort> {
        com.continuum.app.common.data.repository.RoomDownloadDeletionStore(db = get())
    }
    single {
        val tokenManager: TokenManager = get()
        com.continuum.app.common.data.sync.SyncEngine(
            db = get(),
            personalDataApi = get(),
            ebookReaderApi = get(),
            snapshotProvider = { tokenManager.snapshotCurrentScope() },
        )
    }

    single<AndroidServerSettingsCache> { AndroidServerSettingsCache(androidContext()) }
    single<com.continuum.app.network.DeviceMetadataProvider> {
        AndroidDeviceMetadataProvider(androidContext(), platform = "android-tv")
    }
    // Player infrastructure (duplicate-for-now; extract to :android-player later).
    single { SubtitleManager() }
    single { AudioTrackManager() }
    single {
        VideoPlaybackBackendFactory(
            playerFactory = get(),
            audioTrackManager = get(),
            subtitleManager = get(),
        )
    }
    single { AudioCapabilityManager(androidContext()) }
    single { PlaybackCapabilityDetector(androidContext(), get()) }
    single {
        ContinuumPlayerFactory(
            context = androidContext(),
            tokenManager = get(),
            subtitleManager = get(),
            okHttpClient = get(com.continuum.app.common.di.PLAYER_OKHTTP_QUALIFIER),
            delayProcessor = get(),
            subtitleOffsetHolder = get(),
        )
    }
    single { PlaybackSessionManager(get(), get()) }
    factory<VideoPlaybackStarter>(named("tvVideoPlaybackStarter")) {
        TvVideoPlaybackStarter(
            catalogRepository = get(),
            playbackSessionManager = get(),
            profileRepository = get(),
            capabilityDetector = get(),
            playerSettingsStore = get(),
            sessionLifecycle = get(),
        )
    }
    factory {
        VideoPlaybackSessionCoordinator(
            starter = get(named("tvVideoPlaybackStarter")),
        )
    }

    // Audiobook player deps. TV reuses the shared android-shared VM. The TV
    // graph has no downloads (streaming-only), so register the resolver inline:
    // it just always finds no local media and the VM falls back to the server
    // stream. The stores are local-only JSON under filesDir.
    single {
        com.continuum.app.common.downloads.OfflineMediaResolver(
            com.continuum.app.common.downloads.DownloadMetadataStore(get()),
            com.continuum.app.common.downloads.DownloadStorage(androidContext()),
            get(),
        )
    }
    // One-time import of the legacy .record.json sidecar tree into Room.
    single {
        com.continuum.app.common.downloads.LegacyDownloadImporter(androidContext().filesDir, get())
    }
    single { com.continuum.app.common.audiobook.AudiobookBookmarksStore(androidContext().filesDir) }

    // Shared audiobook player VM (android-shared). SavedStateHandle is
    // auto-injected by Koin's viewModel scope so the contentId/fileId nav args
    // (TvRoute.AudiobookPlayer) flow through unchanged — same as the phone.
    viewModel {
        com.continuum.app.common.player.AudiobookPlayerViewModel(
            catalogRepository = get(),
            playbackSessionManager = get(),
            capabilityDetector = get(),
            bookmarksStore = get(),
            userItemStatePort = get(),
            outboxSyncScheduler = get(),
            serverRegistry = get(),
            profileRepository = get(),
            offlineMediaResolver = get(),
            audiobookSettings = get(),
            savedStateHandle = get(),
        )
    }

    // Preferences (per-profile DataStore for the Libraries tab's selected library).
    single { TvLibrarySelectionStore(androidContext(), get()) }

    // Skyline per-profile·server·type library scope (persisted across launches).
    single {
        com.continuum.app.tv.data.preferences.TvLibraryScopeStore(androidContext(), get())
    }

    // One-shot legacy `tv_prefs` import (playback settings → server device
    // overrides; selected-library id → active profile's selection store).
    // Sentinel-gated; invoked from TvSettingsViewModel.loadSettings and
    // TvLibrariesViewModel.load. Lambda-injected lookups follow the
    // AndroidPlayerSettingsStore wiring pattern in PlayerInfraModule.
    single {
        LegacyTvPrefsMigration(
            context = androidContext(),
            settingsCache = get(),
            playerSettingsStore = get(),
            librarySelectionStore = get(),
            getServerUrl = { get<TokenManager>().getServerUrl() },
            getProfileId = { get<TokenManager>().getProfileId() },
            getEffectiveSettings = { keys ->
                when (val result = get<SettingsRepository>().getEffectiveSettings(keys)) {
                    is ApiResult.Success -> result.data
                    is ApiResult.Error, is ApiResult.NetworkError -> emptyMap()
                }
            },
        )
    }

    // Watch Next launcher integration (TV-only). Repository wraps the
    // TvProvider ContentResolver; the worker is constructed by
    // TvWorkerFactory, installed via WorkManager.initialize in
    // ContinuumTvApplication (Koin's worker DSL is not used — see
    // TvWorkerFactory for why).
    single { WatchNextRepository(androidContext()) }
    single { WatchNextSeeder(androidContext(), get()) }

    // Deep-link bridge between MainTvActivity (producer) and TvAppNavigation
    // (consumer). The Activity writes incoming Silo app-scheme URIs here on
    // cold-launch (read from launching intent in onCreate) and warm-launch
    // (onNewIntent); the navigation Composable observes the flow and routes
    // to ItemDetail / Player once the user is past the auth chain. Using a
    // shared singleton avoids prop-drilling the URI through every screen
    // that might be the active destination at the moment the link arrives.
    single<MutableStateFlow<Uri?>>(named("pendingDeepLink")) { MutableStateFlow(null) }

    // LAN companion-pairing receiver engine (TV). The advertiser owns the NSD +
    // TLS-PSK socket lifecycle; the receiver is the transport-agnostic state
    // machine. A later step wires the UI to PairingReceiver.status.
    single {
        com.continuum.app.common.pairing.PairingReceiver(
            authPort = com.continuum.app.common.pairing.AuthRepositoryPairingPort(get(), get()),
            deviceLogin = com.continuum.app.common.pairing.DeviceLoginRepositoryPort(get()),
            identityProvider = {
                com.continuum.app.common.pairing.PairingDeviceIdentity(
                    name = android.os.Build.MODEL?.trim()?.ifBlank { null } ?: "Android TV",
                    deviceId = com.continuum.app.common.pairing.PairingDeviceId
                        .stable(androidContext()),
                )
            },
            receiverStateProvider = {
                val registry = get<ServerRegistry>()
                if (registry.activeServerId.value == null) {
                    com.continuum.app.pairing.PairingReceiverState.Setup
                } else {
                    com.continuum.app.pairing.PairingReceiverState.Login
                }
            },
        )
    }
    single {
        com.continuum.app.common.pairing.TvPairingAdvertiser(
            context = androidContext(),
            receiver = get(),
            receiverStateProvider = {
                val registry = get<ServerRegistry>()
                if (registry.activeServerId.value == null) {
                    com.continuum.app.pairing.PairingReceiverState.Setup
                } else {
                    com.continuum.app.pairing.PairingReceiverState.Login
                }
            },
        )
    }

    // Auth ViewModels
    viewModel { TvServerSetupViewModel(get()) }
    viewModel { com.continuum.app.tv.ui.screens.auth.TvSetupViewModel(get()) }
    viewModel { com.continuum.app.tv.ui.screens.auth.TvSignupViewModel(get()) }
    viewModel { TvLoginViewModel(get(), get(), get()) }
    viewModel { TvProfileSelectionViewModel(get()) }
    viewModel { com.continuum.app.tv.ui.screens.profiles.TvCreateProfileViewModel(get()) }
    viewModel { params ->
        com.continuum.app.tv.ui.screens.profiles.TvEditProfileViewModel(
            profileRepository = get(),
            profileId = params.get(),
        )
    }
    viewModel { TvServerListViewModel(get(), get()) }

    // Admin ViewModels
    viewModel { AdminStatsViewModel(get()) }
    viewModel { com.continuum.app.viewmodel.AdminUsersViewModel(get()) }
    viewModel { com.continuum.app.viewmodel.AdminUserEditViewModel(get()) }
    viewModel { com.continuum.app.tv.ui.screens.admin.TvAdminSessionsViewModel(get()) }
    viewModel { com.continuum.app.tv.ui.screens.admin.TvAdminScansViewModel(get(), get()) }
    viewModel { com.continuum.app.tv.ui.screens.admin.TvAdminLogsViewModel(get()) }
    viewModel { com.continuum.app.tv.ui.screens.settings.TvManageSessionsViewModel(get()) }
    viewModel { params ->
        com.continuum.app.viewmodel.RequestDetailViewModel(get(), params.get(), params.get())
    }
    viewModel { params ->
        val args = params.get<Pair<String?, String?>>()
        com.continuum.app.viewmodel.DevicePairingViewModel(
            repository = get(),
            initialToken = args.first,
            initialCode = args.second,
        )
    }

    // Content ViewModels
    viewModel { HomeViewModel(get(), get(), get(), get()) }
    viewModel { com.continuum.app.tv.ui.screens.home.TvUpcomingViewModel(get()) }
    viewModel { RecommendationsViewModel(get()) }
    viewModel { RequestsViewModel(get()) }
    viewModel { RequestSearchViewModel(get()) }
    viewModel { MyRequestsViewModel(get()) }
    // Platform supplies "today" and the IANA timezone; the shared ViewModel's
    // week math stays deterministic in commonTest (no Clock.System default).
    viewModel {
        CalendarViewModel(
            repository = get(),
            timezoneId = java.util.TimeZone.getDefault().id,
            todayProvider = { java.time.LocalDate.now().toString() },
        )
    }
    viewModel { com.continuum.app.tv.ui.screens.browse.TvBrowseViewModel(get()) }
    viewModel { params ->
        com.continuum.app.tv.ui.screens.people.TvPersonDetailViewModel(
            catalogRepository = get(),
            personId = params.get(),
        )
    }
    viewModel { TvLibrariesViewModel(get(), get(), get()) }
    viewModel { params ->
        TvLibraryDetailViewModel(
            sectionRepository = get(),
            catalogRepository = get(),
            libraryId = params.get(),
            libraryTitle = params.get(),
            libraryType = params.get(),
        )
    }
    viewModel { params ->
        TvLibraryCollectionDetailViewModel(
            sectionRepository = get(),
            libraryId = params.get(),
            collectionId = params.get(),
            title = params.get(),
        )
    }
    viewModel { TvSearchViewModel(get(), get()) }
    viewModel { params ->
        TvItemDetailViewModel(
            catalogRepository = get(),
            personalDataRepository = get(),
            playerSettingsStore = get(),
            contentId = params.get(),
        )
    }
    // Watch Together entry (create/join orchestration) — backs the entry +
    // join-code dialogs on the detail screen.
    viewModel {
        com.continuum.app.tv.ui.screens.watchtogether.TvWatchTogetherViewModel(get())
    }
    // Watch Together lobby — keyed per roomId (koinViewModel key="wt-lobby-$roomId");
    // roomId is read from the positional parameter.
    viewModel { params ->
        com.continuum.app.tv.ui.screens.watchtogether.TvWatchTogetherLobbyViewModel(
            roomId = params.get(),
            repository = get(),
        )
    }
    viewModel { params ->
        TvPlayerViewModel(
            videoPlaybackCoordinator = get(),
            playbackSessionManager = get(),
            playbackAnalytics = get(),
            capabilityDetector = get(),
            // Phase 3 TV uplift dependencies (per-profile settings, intro
            // auto-skip controller, lifecycle, sleep timer).
            playerSettingsStore = get(),
            introAutoSkipController = get(),
            sessionLifecycle = get(),
            sleepTimer = get(),
            subtitlesRepository = get(),
            userItemStatePort = get(),
            outboxSyncScheduler = get(),
            catalogRepository = get(),
            launchArgs = params.get<TvPlayerLaunchArgs>(),
        )
    }

    // Personal data grids.
    viewModel { FavoritesViewModel(get()) }
    viewModel { WatchlistViewModel(get()) }
    viewModel { HistoryViewModel(get()) }

    // Collections.
    viewModel { CollectionsViewModel(get()) }
    viewModel { params ->
        TvCollectionDetailViewModel(
            collectionRepository = get(),
            collectionId = params.get(),
            initialTitle = params.get(),
        )
    }

    // Settings.
    viewModel {
        TvSettingsViewModel(
            authRepository = get(),
            profileRepository = get(),
            tokenManager = get(),
            playerSettingsStore = get(),
            libraryPlaybackPrefsStore = get(),
            overlayPrefsStore = get(),
            legacyTvPrefsMigration = get(),
            notificationsRepository = get(),
        )
    }
}
