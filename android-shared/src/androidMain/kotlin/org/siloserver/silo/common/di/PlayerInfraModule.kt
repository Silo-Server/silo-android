@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package org.siloserver.silo.common.di

import org.siloserver.silo.common.network.ServerReachabilityMonitor
import org.siloserver.silo.common.pip.SiloPictureInPictureCoordinator
import org.siloserver.silo.common.player.ActivePlayerHolder
import org.siloserver.silo.common.player.LEGACY_PLAYER_SEEK_INTERVALS
import org.siloserver.silo.common.player.AudiobookSettingsStore
import org.siloserver.silo.common.player.FinalPlaybackPositionWriter
import org.siloserver.silo.common.player.PlaybackSessionLifecycle
import org.siloserver.silo.common.diagnostics.DiagnosticsPlaybackSessionTracker
import org.siloserver.silo.common.player.SleepTimerController
import org.siloserver.silo.common.settings.AndroidPlayerSettingsStore
import org.siloserver.silo.common.settings.CardPresentationStore
import org.siloserver.silo.common.settings.DefaultCardPresentationStore
import org.siloserver.silo.common.settings.DefaultEpisodeSpoilerStore
import org.siloserver.silo.common.settings.DefaultLibraryPlaybackPrefsStore
import org.siloserver.silo.common.settings.DefaultOverlayPrefsStore
import org.siloserver.silo.common.settings.DefaultSeekIntervalStore
import org.siloserver.silo.common.settings.DefaultServerSettingsFlusher
import org.siloserver.silo.common.settings.EpisodeSpoilerStore
import org.siloserver.silo.common.settings.LibraryPlaybackPrefsStore
import org.siloserver.silo.common.settings.OverlayPrefsStore
import org.siloserver.silo.common.settings.PlayerSettingsStore
import org.siloserver.silo.common.settings.ServerDrivenConfigRefresher
import org.siloserver.silo.common.settings.SeekIntervalStore
import org.siloserver.silo.common.settings.SettingsContractRevision
import org.siloserver.silo.common.settings.DefaultTitleArtStore
import org.siloserver.silo.common.settings.TitleArtStore
import org.siloserver.silo.common.settings.ServerSettingsFlusher
import org.siloserver.silo.domain.player.IntroAutoSkipController
import org.siloserver.silo.domain.settings.SeekIntervalController
import org.siloserver.silo.domain.settings.TitleArtController
import org.siloserver.silo.network.DeviceMetadataProvider
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.api.SettingsApi
import org.siloserver.silo.repository.LibraryPlaybackPrefsRepository
import org.siloserver.silo.repository.ProfileRepository
import org.siloserver.silo.repository.SettingsRepository
import org.siloserver.silo.common.player.probe.BandwidthProbeScheduler
import org.siloserver.silo.common.player.probe.PlaybackBandwidthEstimates
import org.siloserver.silo.common.player.probe.PlaybackV2BandwidthProbeServer
import org.siloserver.silo.common.player.probe.PlayerMediaActivity
import org.siloserver.silo.common.player.probe.profileSwitches
import org.siloserver.silo.model.server.ServerEntry
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/** The entry's id while it is signed in with a profile: what the bandwidth probe measures. */
private fun ServerEntry?.signedInServerId(): String? = this?.takeIf { !it.profileId.isNullOrBlank() }?.id

/**
 * Phase 0 player infrastructure DI module.
 *
 * Sibling of [playerModule] (which owns OkHttp + interceptors). This module
 * registers the new per-profile settings store, server-settings flusher, and
 * playback session lifecycle. ViewModels in Phase 0 still consume the legacy
 * [org.siloserver.silo.common.player.PlaybackSessionManager] directly; the
 * lifecycle here is wired but unused until Phase 1+ migrations.
 */
val playerInfraModule = module {
    single<org.siloserver.silo.repository.NotificationSyncStore> {
        org.siloserver.silo.common.data.sync.AndroidNotificationSyncStore(androidContext())
    }
    single<org.siloserver.silo.repository.PlaybackJournalStore> {
        org.siloserver.silo.common.player.AndroidPlaybackJournalStore(androidContext())
    }
    single { org.siloserver.silo.network.apiv2.PlaybackV2Api(get(), get()) }
    // The buffering-quality connection state and learned-limit reset Settings shows.
    single { org.siloserver.silo.repository.PlaybackConnectionRepository(get(), get()) }
    // The bandwidth probe: one schedule per process, which each app's
    // Application starts on the process lifecycle. Playback starts read its
    // estimate through PlaybackBandwidthEstimates.
    single {
        val registry = get<ServerRegistry>()
        // One coroutine at a time: the scheduler's state is confined to it.
        val probeScope = CoroutineScope(
            SupervisorJob() + Dispatchers.Default.limitedParallelism(1) +
                CoroutineExceptionHandler { _, failure ->
                    // Class name only: the message can carry the server's address.
                    android.util.Log.w("BandwidthProbe", "bandwidth probe failed: ${failure::class.simpleName}")
                },
        )
        BandwidthProbeScheduler(
            server = PlaybackV2BandwidthProbeServer(get(), get()),
            network = get(),
            mediaActive = PlayerMediaActivity(
                holder = get(),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            ).active,
            activeServer = registry.activeEntry
                .map { it.signedInServerId() }
                .stateIn(probeScope, SharingStarted.Eagerly, registry.activeEntry.value.signedInServerId()),
            scope = probeScope,
            profileSwitches = registry.activeEntry
                .map { it?.id to it?.profileId?.takeIf(String::isNotBlank) }
                .distinctUntilChanged()
                .profileSwitches(),
            localRoute = org.siloserver.silo.network.localroute.LocalRoutes.state.active,
        )
    }
    single<PlaybackBandwidthEstimates> { get<BandwidthProbeScheduler>() }
    // The video player and its session manager, the same on phone and TV: the
    // subtitle manager each app registers carries the form factor's policy.
    single {
        org.siloserver.silo.common.player.SiloPlayerFactory(
            context = androidContext(),
            tokenManager = get(),
            subtitleManager = get(),
            httpDataSourceFactory = get(PLAYER_HTTP_DATA_SOURCE_FACTORY_QUALIFIER),
            mediaAuthSession = get(),
            bandwidthMeter = get(),
            delayProcessor = get(),
            subtitleOffsetHolder = get(),
            libassBridge = get(),
            playbackAnalytics = get(),
            throughputMeter = get(),
        )
    }
    // Video starts send the bandwidth probe's estimate, and plan summaries the
    // video's throughput; the audiobook managers the apps register do neither.
    single {
        val playerSettings = get<PlayerSettingsStore>()
        org.siloserver.silo.common.player.PlaybackSessionManager(
            get(), get(), get(),
            bandwidthEstimates = get(),
            throughputKbps = get<org.siloserver.silo.common.player.PlaybackThroughputMeter>()::takeKbps,
            playingPlanThroughputKbps = get<org.siloserver.silo.common.player.PlaybackThroughputMeter>()::peekKbps,
            // The profile's mode as last stored on this device, which the
            // store answers locally, so no start goes out before it is known.
            bufferingQualityMode = { playerSettings.bufferingQualityModeFlow.first() },
        )
    }
    single {
        org.siloserver.silo.repository.SequencedPlayback(
            get(), get(), get(), get(),
            log = { android.util.Log.i("SequencedPlayback", it) },
        ) {
            java.util.UUID.randomUUID().toString()
        }
    }
    // Shares the active session Player with the in-process UI so the video
    single { ActivePlayerHolder() }
    single { org.siloserver.silo.common.player.AudiobookSeekRouter() }

    single {
        val seekIntervalStore = get<SeekIntervalStore>()
        SiloPictureInPictureCoordinator(seekIntervals = {
            seekIntervalStore.state.value.video(LEGACY_PLAYER_SEEK_INTERVALS)
        })
    }

    single {
        val userItemState = get<org.siloserver.silo.repository.port.UserItemStatePort>()
        val syncScheduler = get<org.siloserver.silo.common.data.sync.OutboxSyncScheduler>()
        FinalPlaybackPositionWriter(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            scopeProvider = { get<TokenManager>().snapshotCurrentScope() },
        ) { snapshot ->
            val written = userItemState.recordPosition(
                snapshot.scope,
                snapshot.contentId,
                snapshot.fileId,
                snapshot.positionSeconds,
                snapshot.durationSeconds,
            )
            if (written) syncScheduler.requestSync()
        }
    }

    // The connected server's settings manifest revision. One instance, so the
    // flusher's send-time gates and the settings UI agree on what it supports.
    single<SettingsContractRevision> {
        val settingsApi = get<SettingsApi>()
        SettingsContractRevision(
            fetchCapabilities = { settingsApi.getContractCapabilities() },
            getServerUrl = { get<TokenManager>().getServerUrl() },
            fetchManifestKeys = { settingsApi.getContractKeys() },
        )
    }

    // Long-lived application-scope flusher: debounced server writes survive
    // ViewModel teardown. Uses Dispatchers.IO since flushOne does network work.
    single<ServerSettingsFlusher> {
        DefaultServerSettingsFlusher(
            settingsApi = get(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            // Same source the settings store stamps its ops with, so a
            // retained retry can tell whether the server it was authored
            // against is still the one requests would reach.
            getServerUrl = { get<TokenManager>().getServerUrl() },
            getAuthScope = { get<TokenManager>().snapshotCurrentScope() },
            contractRevision = get(),
        )
    }

    // Manual audio and subtitle choices remembered per series on the server.
    // App-scoped so a retry outlives the player that made the choice.
    single<org.siloserver.silo.common.settings.SeriesTrackChoiceWriter> {
        val v2 = get<org.siloserver.silo.network.apiv2.SettingsV2Api>()
        org.siloserver.silo.common.settings.DefaultSeriesTrackChoiceWriter(
            putSubtitle = { key, choice, owner -> v2.putSubtitlePreference(key, choice, owner) },
            deleteSubtitle = { key, owner -> v2.deleteSubtitlePreference(key, owner) },
            putAudio = { key, choice, owner -> v2.putAudioPreference(key, choice, owner) },
            deleteAudio = { key, owner -> v2.deleteAudioPreference(key, owner) },
            captureOwner = {
                get<TokenManager>().snapshotCurrentScope()?.takeIf { !it.profileId.isNullOrBlank() }
            },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            identityChanges = get<org.siloserver.silo.network.IdentityTransitionBarrier>().transitions,
            // Each successful probe: the server is back, or the app returned
            // to the foreground and checked it again.
            resumeSignals = get<ServerReachabilityMonitor>().state
                .filter { it.status == org.siloserver.silo.common.network.ServerReachabilityStatus.Reachable }
                .map { it.lastCheckedAtMs }
                .distinctUntilChanged()
                .map { },
            autoResets = org.siloserver.silo.common.settings.SharedPreferencesSeriesAutoResetStore(androidContext()),
        )
    }

    // Per-profile DataStore-backed settings store with legacy SharedPreferences
    // migration. We resolve the active profile on demand via ProfileRepository,
    // and the current server URL via TokenManager — both of which the auth
    // flow keeps up to date (TokenManager.setServerUrl on connect, profile id
    // saved on profile selection).
    single<PlayerSettingsStore> {
        val registry = get<ServerRegistry>()
        val profileChangeSignal = registry.activeEntry
            .map { it?.profileId }
            .distinctUntilChanged()
            .map { Unit }
        val serverChangeSignal = registry.activeEntry
            .map { it?.url }
            .distinctUntilChanged()
            .map { Unit }
        AndroidPlayerSettingsStore(
            context = androidContext(),
            legacyCache = get(),
            getActiveProfileId = { get<ProfileRepository>().getActiveProfileId() },
            getServerUrl = { get<TokenManager>().getServerUrl() },
            getAuthScope = { get<TokenManager>().snapshotCurrentScope() },
            serverSettingsFlusher = get(),
            profileChangeSignal = profileChangeSignal,
            serverChangeSignal = serverChangeSignal,
            settingsRepository = get<SettingsRepository>(),
            // Reuse the same device id the rest of the app sends to the
            // server so settings scope stays in lockstep with what the
            // server records as `device_id` for each override.
            getDeviceId = { get<DeviceMetadataProvider>().current()?.id },
            contractRevision = get(),
        )
    }

    single<LibraryPlaybackPrefsStore> {
        DefaultLibraryPlaybackPrefsStore(
            repository = get<LibraryPlaybackPrefsRepository>(),
        )
    }

    // Cached card-overlay configuration (admin enable flag + resolved prefs).
    // Long-lived application scope so its coalesced writes survive view
    // teardown, matching the other settings stores in this module.
    single<OverlayPrefsStore> {
        DefaultOverlayPrefsStore(
            repository = get<SettingsRepository>(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )
    }

    // Card-presentation preference (poster size + captions). Same lifetime
    // rationale as the overlay store; the extra lambdas feed the last-known
    // SharedPreferences cache identity (server|profile|family|device).
    single<CardPresentationStore> {
        DefaultCardPresentationStore(
            context = androidContext(),
            repository = get<SettingsRepository>(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            getActiveProfileId = { get<ProfileRepository>().getActiveProfileId() },
            getServerUrl = { get<TokenManager>().getServerUrl() },
            getDeviceMetadata = { get<DeviceMetadataProvider>().current() },
        )
    }

    // Profile-wide forward/rewind intervals (settings revision 9). Resets and
    // re-resolves whenever the active server or profile changes; players read
    // its state on every relative seek so edits apply mid-playback.
    single<SeekIntervalStore> {
        val registry = get<ServerRegistry>()
        val identityChanges = registry.activeEntry
            .map { it?.url to it?.profileId }
            .distinctUntilChanged()
            .map { Unit }
        DefaultSeekIntervalStore(
            context = androidContext(),
            controller = SeekIntervalController(get<SettingsRepository>()),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            getActiveProfileId = { get<ProfileRepository>().getActiveProfileId() },
            getServerUrl = { get<TokenManager>().getServerUrl() },
            identityChanges = identityChanges,
        )
    }

    // Spoiler protection for unwatched episodes (see EpisodeSpoilers.MIN_CONTRACT_REVISION). Both
    // keys are profile-scope only, so the store writes the profile row itself
    // rather than going through the profile_device flusher. Resets and
    // re-resolves on a server or profile change, like SeekIntervalStore:
    // Add Server → sign in → pick profile clears nothing on its own.
    single<EpisodeSpoilerStore> {
        val identityChanges = get<ServerRegistry>().activeEntry
            .map { it?.url to it?.profileId }
            .distinctUntilChanged()
            .map { Unit }
        DefaultEpisodeSpoilerStore(
            context = androidContext(),
            repository = get<SettingsRepository>(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            getActiveProfileId = { get<ProfileRepository>().getActiveProfileId() },
            getServerUrl = { get<TokenManager>().getServerUrl() },
            captureAuthority = { get<TokenManager>().snapshotCurrentScope() },
            identityChanges = identityChanges,
        )
    }

    // "Show title art" (settings revision 16) for this device. Every title
    // surface and both settings screens read this one instance, so a change
    // reaches an open detail page without a restart.
    single<TitleArtStore> {
        val registry = get<ServerRegistry>()
        val identityChanges = registry.activeEntry
            .map { it?.url to it?.profileId }
            .distinctUntilChanged()
            .map { Unit }
        DefaultTitleArtStore(
            context = androidContext(),
            controller = TitleArtController(get<SettingsRepository>()),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            getAuthScope = { get<TokenManager>().snapshotCurrentScope() },
            identityChanges = identityChanges,
        )
    }

    // Theme music on the phone and TV detail pages. One instance, so a theme
    // continues from one detail page to the next one with the same theme.
    single {
        val identityChanges = get<ServerRegistry>().activeEntry
            .map { it?.url to it?.profileId }
            .distinctUntilChanged()
            .drop(1)
            .map { Unit }
        org.siloserver.silo.common.thememusic.ThemeMusicService(
            context = androidContext(),
            api = get(),
            tokens = get(),
            settings = get(),
            identityChanges = identityChanges,
            playbackStarts = org.siloserver.silo.common.thememusic.playbackStarts(get<ActivePlayerHolder>().player),
            // The grant URL carries its own token and may be on a proxy's
            // origin, so it goes out on the raw transport without account auth.
            dataSourceFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(
                get<okhttp3.OkHttpClient>(PLAYER_TRANSPORT_OKHTTP_QUALIFIER),
            ),
        )
    }

    single {
        ServerDrivenConfigRefresher(
            overlayPrefsStore = get(),
            cardPresentationStore = get(),
            libraryPlaybackPrefsStore = get(),
            playerSettingsStore = get(),
            seekIntervalStore = get(),
            episodeSpoilerStore = get(),
            titleArtStore = get(),
            hiddenLibrariesStore = get(),
            hasAuthenticatedProfile = {
                !get<ProfileRepository>().getActiveProfileId().isNullOrBlank()
            },
        )
    }

    single {
        val registry = get<ServerRegistry>()
        val transitions = get<org.siloserver.silo.network.IdentityTransitionBarrier>()
        val discovery = get<org.siloserver.silo.network.apiv2.ApiV2Probe>()
        ServerReachabilityMonitor(
            probe = discovery::probeFresh,
            captureTarget = {
                registry.activeEntry.value?.let { entry ->
                    org.siloserver.silo.common.network.ReachabilityTarget(entry.id, entry.url, transitions.generation.value)
                }
            },
            targetChanges = kotlinx.coroutines.flow.combine(registry.activeEntry, transitions.generation) { entry, generation -> entry?.id to generation },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            onServerReconnected = {
                get<ServerDrivenConfigRefresher>().forceRefresh()
            },
        )
    }

    // Local, per-profile audiobook playback preferences (skip interval,
    // default speed, audio-processing toggles). Device-local — no server
    // flush — so it only needs the active profile and a profile-change signal.
    single {
        val registry = get<ServerRegistry>()
        val profileChangeSignal = registry.activeEntry
            .map { it?.profileId }
            .distinctUntilChanged()
            .map { Unit }
        AudiobookSettingsStore(
            context = androidContext(),
            getActiveProfileId = { get<ProfileRepository>().getActiveProfileId() },
            profileChangeSignal = profileChangeSignal,
        )
    }

    // Singleton lifecycle. v1 trade-off: there is only ever one active player
    // session at a time, and start()/stop() handle cleanup between sessions.
    // ViewModels can `get<PlaybackSessionLifecycle>()` once Phase 1+ migrates
    // them; for now this binding is registered but unobserved.
    single<PlaybackSessionLifecycle> {
        PlaybackSessionLifecycle(
            sessionManager = get(),
            healthApi = get(),
            personalDataRepository = get(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            playbackSessions = get<DiagnosticsPlaybackSessionTracker>(),
        )
    }
    single(AUDIOBOOK_PLAYBACK_SESSION_LIFECYCLE_QUALIFIER) {
        PlaybackSessionLifecycle(
            sessionManager = get(AUDIOBOOK_PLAYBACK_SESSION_MANAGER_QUALIFIER),
            healthApi = get(),
            personalDataRepository = get(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            playbackSessions = get<DiagnosticsPlaybackSessionTracker>(),
        )
    }

    // Phase 2 sleep timer. Pinned to Main.immediate so the on-fire callback
    // (typically `_uiState.update { isPaused = true }`) lands on the same
    // thread the player VM and MediaController already use, avoiding an extra
    // dispatch hop.
    single<SleepTimerController> {
        SleepTimerController(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )
    }

    factory<IntroAutoSkipController> {
        IntroAutoSkipController(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )
    }
}
