package org.siloserver.silo.common.di

import org.siloserver.silo.common.network.ServerReachabilityMonitor
import org.siloserver.silo.common.pip.SiloPictureInPictureCoordinator
import org.siloserver.silo.common.player.ActivePlayerHolder
import org.siloserver.silo.common.player.AudiobookSettingsStore
import org.siloserver.silo.common.player.FinalPlaybackPositionWriter
import org.siloserver.silo.common.player.PlaybackSessionLifecycle
import org.siloserver.silo.common.diagnostics.DiagnosticsPlaybackSessionTracker
import org.siloserver.silo.common.player.SleepTimerController
import org.siloserver.silo.common.settings.AndroidPlayerSettingsStore
import org.siloserver.silo.common.settings.CardPresentationStore
import org.siloserver.silo.common.settings.DefaultCardPresentationStore
import org.siloserver.silo.common.settings.DefaultLibraryPlaybackPrefsStore
import org.siloserver.silo.common.settings.DefaultOverlayPrefsStore
import org.siloserver.silo.common.settings.DefaultServerSettingsFlusher
import org.siloserver.silo.common.settings.LibraryPlaybackPrefsStore
import org.siloserver.silo.common.settings.OverlayPrefsStore
import org.siloserver.silo.common.settings.PlayerSettingsStore
import org.siloserver.silo.common.settings.ServerDrivenConfigRefresher
import org.siloserver.silo.common.settings.ServerSettingsFlusher
import org.siloserver.silo.domain.player.IntroAutoSkipController
import org.siloserver.silo.network.DeviceMetadataProvider
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.repository.LibraryPlaybackPrefsRepository
import org.siloserver.silo.repository.ProfileRepository
import org.siloserver.silo.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

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
    // Shares the active session Player with the in-process UI so the video
    single { ActivePlayerHolder() }

    single { SiloPictureInPictureCoordinator() }

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
            serverSettingsFlusher = get(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            profileChangeSignal = profileChangeSignal,
            serverChangeSignal = serverChangeSignal,
            settingsRepository = get<SettingsRepository>(),
            // Reuse the same device id the rest of the app sends to the
            // server so settings scope stays in lockstep with what the
            // server records as `device_id` for each override.
            getDeviceId = { get<DeviceMetadataProvider>().current()?.id },
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

    single {
        ServerDrivenConfigRefresher(
            overlayPrefsStore = get(),
            cardPresentationStore = get(),
            libraryPlaybackPrefsStore = get(),
            playerSettingsStore = get(),
            hasAuthenticatedProfile = {
                !get<ProfileRepository>().getActiveProfileId().isNullOrBlank()
            },
        )
    }

    single {
        ServerReachabilityMonitor(
            healthApi = get(),
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
