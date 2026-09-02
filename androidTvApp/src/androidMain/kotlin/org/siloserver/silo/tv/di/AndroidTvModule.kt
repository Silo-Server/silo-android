@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package org.siloserver.silo.tv.di

import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.SettingsRepository
import org.siloserver.silo.tv.BuildConfig
import org.siloserver.silo.tv.data.preferences.LegacyTvPrefsMigration
import org.siloserver.silo.tv.data.preferences.TvHomeSectionPreferences
import org.siloserver.silo.tv.data.preferences.TvLibrarySelectionStore
import org.siloserver.silo.common.network.AndroidDeviceMetadataProvider
import org.siloserver.silo.common.network.SiloClientBuildIdentity
import org.siloserver.silo.common.network.CleartextConsentStore
import org.siloserver.silo.common.network.DataStoreCleartextConsentStore
import org.siloserver.silo.common.settings.AndroidServerSettingsCache
import android.content.SharedPreferences
import org.siloserver.silo.network.AndroidServerRegistry
import org.siloserver.silo.network.EncryptedTokenManagerImpl
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.createSecureSharedPrefs
import org.siloserver.silo.tv.ui.screens.servers.TvServerListViewModel
import org.siloserver.silo.common.player.AudioCapabilityManager
import org.siloserver.silo.common.player.AudioTrackManager
import org.siloserver.silo.common.player.AndroidSubtitlePresentation
import org.siloserver.silo.common.player.PlaybackCapabilityDetector
import org.siloserver.silo.common.player.backend.VideoPlaybackBackendFactory
import org.siloserver.silo.tv.ui.screens.settings.TvSettingsViewModel
import org.siloserver.silo.tv.ui.screens.settings.diagnostics.TvDiagnosticsViewModel
import org.siloserver.silo.common.player.SiloPlayerFactory
import org.siloserver.silo.common.player.PlaybackSessionManager
import org.siloserver.silo.common.player.audio.PassthroughSuppressionScope
import org.siloserver.silo.common.di.AUDIOBOOK_PLAYBACK_SESSION_MANAGER_QUALIFIER
import org.siloserver.silo.common.di.AUDIOBOOK_PLAYBACK_SESSION_LIFECYCLE_QUALIFIER
import org.siloserver.silo.common.player.SubtitleManager
import org.siloserver.silo.common.cast.SiloCastNsdAdvertiser
import org.siloserver.silo.common.player.video.VideoPlaybackSessionCoordinator
import org.siloserver.silo.common.player.video.VideoPlaybackStarter
import org.siloserver.silo.tv.cast.TvSiloCastReceiver
import org.siloserver.silo.tv.cast.RemotePlaybackIdentityManager
import org.siloserver.silo.tv.ui.screens.player.TvPlayerLaunchArgs
import org.siloserver.silo.tv.ui.screens.auth.TvLoginViewModel
import org.siloserver.silo.tv.ui.screens.auth.TvServerSetupViewModel
import org.siloserver.silo.tv.ui.screens.collections.TvCollectionDetailViewModel
import org.siloserver.silo.viewmodel.CalendarViewModel
import org.siloserver.silo.viewmodel.CollectionsViewModel
import org.siloserver.silo.tv.ui.screens.detail.TvItemDetailViewModel
import org.siloserver.silo.viewmodel.HomeViewModel
import org.siloserver.silo.viewmodel.RecommendationsViewModel
import org.siloserver.silo.viewmodel.MyRequestsViewModel
import org.siloserver.silo.viewmodel.RequestSearchViewModel
import org.siloserver.silo.viewmodel.RequestsViewModel
import org.siloserver.silo.tv.ui.screens.libraries.TvLibrariesViewModel
import org.siloserver.silo.tv.ui.screens.library.TvLibraryCollectionDetailViewModel
import org.siloserver.silo.tv.ui.screens.library.TvLibraryDetailViewModel
import org.siloserver.silo.tv.ui.screens.personal.TvPersonalListControlsViewModel
import org.siloserver.silo.viewmodel.FavoritesViewModel
import org.siloserver.silo.viewmodel.HistoryViewModel
import org.siloserver.silo.viewmodel.WatchlistViewModel
import org.siloserver.silo.tv.ui.screens.player.TvPlayerViewModel
import org.siloserver.silo.tv.ui.screens.player.TvVideoPlaybackStarter
import org.siloserver.silo.tv.ui.screens.profiles.TvProfileSelectionViewModel
import org.siloserver.silo.tv.ui.screens.search.TvSearchViewModel
import org.siloserver.silo.tv.data.preferences.TvLibraryScopeStore
import org.siloserver.silo.tv.watchnext.WatchNextRepository
import org.siloserver.silo.tv.watchnext.WatchNextSeeder
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
 * phone module — see the TODO on [SiloPlayerFactory]) and the TV ViewModels.
 * The shared [org.siloserver.silo.di.sharedModules] is added alongside this one in
 * [org.siloserver.silo.tv.SiloTvApplication].
 */
val androidTvModule = module {
    single<CleartextConsentStore> { DataStoreCleartextConsentStore(androidContext()) }
    single<org.siloserver.silo.network.CleartextOriginConsent> { get<CleartextConsentStore>() }
    // Single encrypted prefs handle shared between the server registry and
    // the token manager — see the phone module for rationale.
    single<SharedPreferences> { createSecureSharedPrefs(androidContext()) }

    // Multi-server registry. Loaded synchronously in init so MainTvActivity's
    // runBlocking-resolved start destination sees consistent state.
    single<ServerRegistry> { AndroidServerRegistry(get(), get()) }

    // Persistent (EncryptedSharedPreferences-backed) replacement for the
    // commonMain in-memory TokenManager. Koin 3.1+ replaces same-key bindings
    // when the redefining module is loaded after the original — sharedModules()
    // is registered first in SiloTvApplication, so this wins.
    single<TokenManager> { EncryptedTokenManagerImpl(get(), get(), get()) }

    // Offline-first Room store (Track B). Bound after sharedModules() so the
    // commonMain PersonalDataRepository's `getOrNull<UserItemStatePort>()` picks
    // up the Room-backed port and writes optimistic projection + outbox rows.
    single { org.siloserver.silo.common.data.db.SiloDatabase.build(androidContext()) }
    single<org.siloserver.silo.common.data.sync.OutboxSyncScheduler> {
        val appContext = androidContext().applicationContext
        org.siloserver.silo.common.data.sync.OutboxSyncScheduler {
            org.siloserver.silo.common.data.sync.SyncWorker.enqueue(appContext)
        }
    }
    single<org.siloserver.silo.repository.port.UserItemStatePort> {
        val tokenManager: TokenManager = get()
        org.siloserver.silo.common.data.repository.RoomUserItemStateRepository(
            db = get(),
            snapshotProvider = { tokenManager.snapshotCurrentScope() },
            syncScheduler = get(),
        )
    }
    single<org.siloserver.silo.repository.port.HomeCachePort> {
        val tokenManager: TokenManager = get()
        org.siloserver.silo.common.data.repository.RoomHomeCacheRepository(
            db = get(),
            snapshotProvider = { tokenManager.snapshotCurrentScope() },
            identityTransitions = get(),
        )
    }
    single<org.siloserver.silo.repository.port.CatalogCachePort> {
        val tokenManager: TokenManager = get()
        org.siloserver.silo.common.data.repository.RoomCatalogCacheRepository(
            db = get(),
            snapshotProvider = { tokenManager.snapshotCurrentScope() },
            identityTransitions = get(),
        )
    }
    single<org.siloserver.silo.repository.port.DownloadDeletionPort> {
        org.siloserver.silo.common.data.repository.RoomDownloadDeletionStore(db = get())
    }
    single {
        val tokenManager: TokenManager = get()
        org.siloserver.silo.common.data.sync.SyncEngine(
            db = get(),
            personalDataApi = get(),
            ebookReaderApi = get(),
            snapshotProvider = { tokenManager.snapshotCurrentScope() },
        )
    }

    single<AndroidServerSettingsCache> { AndroidServerSettingsCache(androidContext()) }
    // The one place the TV app's BuildConfig crosses into android-shared; see
    // androidModule for why every identity reporter resolves this instead of
    // deriving its own answer.
    single { SiloClientBuildIdentity(BuildConfig.BUILD_NUMBER, BuildConfig.RELEASE_CHANNEL) }
    single<org.siloserver.silo.network.DeviceMetadataProvider> {
        AndroidDeviceMetadataProvider(
            androidContext(),
            platform = "android-tv",
            buildIdentity = get(),
        )
    }
    // Player infrastructure (duplicate-for-now; extract to :android-player later).
    single {
        SubtitleManager(
            libassBridge = get(),
            presentation = AndroidSubtitlePresentation.Television,
        )
    }
    single { AudioTrackManager() }
    single {
        VideoPlaybackBackendFactory(
            playerFactory = get(),
            audioTrackManager = get(),
            subtitleManager = get(),
        )
    }
    single { AudioCapabilityManager(androidContext()) }
    single { PlaybackCapabilityDetector(androidContext(), get(), get(), get()) }
    single {
        SiloPlayerFactory(
            context = androidContext(),
            tokenManager = get(),
            subtitleManager = get(),
            httpDataSourceFactory = get(org.siloserver.silo.common.di.PLAYER_HTTP_DATA_SOURCE_FACTORY_QUALIFIER),
            mediaAuthSession = get(),
            bandwidthMeter = get(),
            delayProcessor = get(),
            subtitleOffsetHolder = get(),
            libassBridge = get(),
        )
    }
    single { PlaybackSessionManager(get(), get(), get()) }
    single(AUDIOBOOK_PLAYBACK_SESSION_MANAGER_QUALIFIER) {
        PlaybackSessionManager(
            playbackRepository = get(),
            tokenManager = get(),
            networkEvidenceProvider = get(),
            passthroughSuppression = PassthroughSuppressionScope.None,
        )
    }
    factory<VideoPlaybackStarter>(named("tvVideoPlaybackStarter")) {
        TvVideoPlaybackStarter(
            catalogRepository = get(),
            playbackSessionManager = get(),
            profileRepository = get(),
            capabilityDetector = get(),
            playerSettingsStore = get(),
            sessionLifecycle = get(),
            reachabilityMonitor = get(),
            userItemStatePort = get(),
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
    // Registered rather than constructed inline because the orphaned-server
    // purge resolves it from the graph at startup. It is streaming-only here,
    // so there are no download bytes to delete — but the purge also clears the
    // Room rows of removed servers (resume positions, cached home and catalog
    // rows, pending outbox ops), and without this definition the whole purge
    // fails at startup and none of that is ever reclaimed.
    single { org.siloserver.silo.common.downloads.DownloadStorage(androidContext()) }
    single {
        org.siloserver.silo.common.downloads.OfflineMediaResolver(
            org.siloserver.silo.common.downloads.DownloadMetadataStore(get()),
            get(),
            get(),
        )
    }
    // One-time import of the legacy .record.json sidecar tree into Room.
    single {
        org.siloserver.silo.common.downloads.LegacyDownloadImporter(androidContext().filesDir, get())
    }
    single { org.siloserver.silo.common.audiobook.AudiobookBookmarksStore(androidContext().filesDir) }

    // Shared audiobook player VM (android-shared). SavedStateHandle is
    // auto-injected by Koin's viewModel scope so the contentId/fileId nav args
    // (TvRoute.AudiobookPlayer) flow through unchanged — same as the phone.
    viewModel {
        org.siloserver.silo.common.player.AudiobookPlayerViewModel(
            catalogRepository = get(),
            playbackSessionManager = get(AUDIOBOOK_PLAYBACK_SESSION_MANAGER_QUALIFIER),
            playbackSessionLifecycle = get(AUDIOBOOK_PLAYBACK_SESSION_LIFECYCLE_QUALIFIER),
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
        org.siloserver.silo.tv.data.preferences.TvLibraryScopeStore(androidContext(), get())
    }

    // tvOS-parity Home row visibility/order, local to this TV and partitioned
    // by active server + profile.
    single { TvHomeSectionPreferences(androidContext(), get()) }

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
    // SiloTvApplication (Koin's worker DSL is not used — see
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
        org.siloserver.silo.common.pairing.PairingReceiver(
            authPort = org.siloserver.silo.common.pairing.RegistryPairingAuthPort(get(), get(), get()),
            deviceLogin = org.siloserver.silo.common.pairing.DeviceLoginRepositoryPort(get()),
            identityProvider = {
                org.siloserver.silo.common.pairing.PairingDeviceIdentity(
                    name = tvDeviceName(),
                    deviceId = org.siloserver.silo.common.pairing.PairingDeviceId
                        .stable(androidContext()),
                )
            },
            // Always `setup`: advertising only runs while the server-setup
            // screen is showing, and Apple is authoritative for the wire —
            // silo-apple's TVPairingAdvertiser hardcodes st=setup and its
            // companion card FILTERS to state == .setup, so a registry-based
            // `login` (always true after a sign-out, since the registry keeps
            // entries) made the TV invisible to phones exactly when the user
            // needed set-up-with-phone again.
            receiverStateProvider = { org.siloserver.silo.pairing.PairingReceiverState.Setup },
        )
    }
    single {
        org.siloserver.silo.common.pairing.TvPairingAdvertiser(
            context = androidContext(),
            receiver = get(),
            // Always `setup`: advertising only runs while the server-setup
            // screen is showing, and Apple is authoritative for the wire —
            // silo-apple's TVPairingAdvertiser hardcodes st=setup and its
            // companion card FILTERS to state == .setup, so a registry-based
            // `login` (always true after a sign-out, since the registry keeps
            // entries) made the TV invisible to phones exactly when the user
            // needed set-up-with-phone again.
            receiverStateProvider = { org.siloserver.silo.pairing.PairingReceiverState.Setup },
        )
    }
    single { SiloCastNsdAdvertiser(androidContext()) }
    single {
        RemotePlaybackIdentityManager(
            deviceLoginApi = get(),
            tokenManager = get(),
            deviceNameProvider = ::tvDeviceName,
        )
    }
    single {
        TvSiloCastReceiver(
            advertiser = get(),
            serverRegistry = get(),
            identityManager = get(),
            deviceNameProvider = ::tvDeviceName,
            deviceIdProvider = {
                org.siloserver.silo.common.pairing.PairingDeviceId.stable(androidContext())
            },
        )
    }

    // Auth ViewModels
    viewModel { TvServerSetupViewModel(get(), get()) }
    viewModel { org.siloserver.silo.tv.ui.screens.auth.TvSetupViewModel(get()) }
    viewModel { org.siloserver.silo.tv.ui.screens.auth.TvSignupViewModel(get()) }
    viewModel { TvLoginViewModel(get(), get(), get()) }
    viewModel { TvProfileSelectionViewModel(get(), get()) }
    viewModel { org.siloserver.silo.tv.ui.screens.profiles.TvCreateProfileViewModel(get()) }
    viewModel { params ->
        org.siloserver.silo.tv.ui.screens.profiles.TvEditProfileViewModel(
            profileRepository = get(),
            profileId = params.get(),
        )
    }
    viewModel { TvServerListViewModel(get(), get(), get()) }

    viewModel { params ->
        org.siloserver.silo.viewmodel.RequestDetailViewModel(get(), params.get(), params.get())
    }
    viewModel { params ->
        val args = params.get<Pair<String?, String?>>()
        org.siloserver.silo.viewmodel.DevicePairingViewModel(
            repository = get(),
            initialToken = args.first,
            initialCode = args.second,
        )
    }

    // Content ViewModels
    viewModel { HomeViewModel(get(), get(), get(), get(), getOrNull(), get(), get()) }
    viewModel { org.siloserver.silo.tv.ui.screens.home.TvUpcomingViewModel(get()) }
    viewModel { RecommendationsViewModel(get()) }
    viewModel { RequestsViewModel(get()) }
    viewModel { RequestSearchViewModel(get()) }
    viewModel { MyRequestsViewModel(get()) }
    viewModel { org.siloserver.silo.tv.ui.screens.requests.TvRequestsViewModel(get()) }
    // Platform supplies "today" and the IANA timezone; the shared ViewModel's
    // week math stays deterministic in commonTest (no Clock.System default).
    viewModel {
        CalendarViewModel(
            repository = get(),
            timezoneId = java.util.TimeZone.getDefault().id,
            todayProvider = { java.time.LocalDate.now().toString() },
        )
    }
    viewModel { org.siloserver.silo.tv.ui.screens.browse.TvBrowseViewModel(get()) }
    viewModel { params ->
        org.siloserver.silo.tv.ui.screens.people.TvPersonDetailViewModel(
            catalogRepository = get(),
            personId = params.get(),
            personalDataRepository = getOrNull(),
            showAudiobooksProvider = {
                get<TvLibraryScopeStore>().getShowAudiobooksTab()
            },
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
            catalogRepository = get(),
            libraryId = params.get(),
            collectionId = params.get(),
            title = params.get(),
        )
    }
    viewModel { TvSearchViewModel(get(), get(), get()) }
    viewModel { params ->
        TvItemDetailViewModel(
            catalogRepository = get(),
            personalDataRepository = get(),
            playerSettingsStore = get(),
            profileRepository = get(),
            profileSettings = get(),
            metadataAiRepository = get(),
            contentId = params.get(),
            userItemState = getOrNull<org.siloserver.silo.repository.port.UserItemStatePort>()
                ?: org.siloserver.silo.repository.port.NoOpUserItemStatePort,
            recommendationRepository = getOrNull(),
            tokenManager = get(),
            identityTransitions = get(),
            capabilityDetector = get(),
        )
    }
    // Watch Together entry (create/join orchestration) — backs the entry +
    // join-code dialogs on the detail screen.
    viewModel {
        org.siloserver.silo.tv.ui.screens.watchtogether.TvWatchTogetherViewModel(get())
    }
    viewModel {
        org.siloserver.silo.tv.ui.screens.watchtogether.TvSuggestToRoomViewModel(get())
    }
    // Watch Together lobby — keyed per roomId (koinViewModel key="wt-lobby-$roomId");
    // roomId is read from the positional parameter.
    viewModel { params ->
        org.siloserver.silo.tv.ui.screens.watchtogether.TvWatchTogetherLobbyViewModel(
            roomId = params.get(),
            repository = get(),
            roomSession = get(),
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
            finalPlaybackPositionWriter = get(),
            catalogRepository = get(),
            serverReachabilityMonitor = get(),
            launchArgs = params.get<TvPlayerLaunchArgs>(),
        )
    }

    // Personal data grids.
    viewModel { FavoritesViewModel(get(), get()) }
    viewModel { WatchlistViewModel(get(), get()) }
    viewModel { HistoryViewModel(get()) }
    // Sort/filter state for the favorites and watchlist grids, keyed by source.
    viewModel { params ->
        TvPersonalListControlsViewModel(
            catalogRepository = get(),
            source = params.get(),
        )
    }

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
            serverRegistry = get(),
            playerSettingsStore = get(),
            libraryPlaybackPrefsStore = get(),
            overlayPrefsStore = get(),
            cardPresentationStore = get(),
            legacyTvPrefsMigration = get(),
            profileSettings = get(),
            tvLibraryScopeStore = getOrNull(),
        )
    }
    viewModel { TvDiagnosticsViewModel(get()) }
}

private fun tvDeviceName(): String =
    android.os.Build.MODEL?.trim()?.ifBlank { null } ?: "Android TV"
