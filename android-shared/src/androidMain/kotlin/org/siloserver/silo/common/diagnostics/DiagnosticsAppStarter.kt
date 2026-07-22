package org.siloserver.silo.common.diagnostics

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import org.siloserver.silo.common.diagnostics.logging.SiloLog
import org.siloserver.silo.common.player.PlaybackAnalyticsListener
import org.siloserver.silo.model.diagnostics.DiagnosticsLogCategory
import org.siloserver.silo.repository.ProfileRepository

/**
 * Application-level diagnostics bring-up, shared by phone and TV. Registered
 * from Application.onCreate (after Koin), guarded like the other foreground
 * starters — never load-bearing for cold start.
 *
 * Maintains the crash-context foreground flag, emits lifecycle breadcrumbs,
 * drives the coordinator's throttled foreground refresh, and attaches the
 * playback diagnostics logger to the analytics event stream.
 */
class DiagnosticsAppStarter(
    private val coordinator: DiagnosticsCoordinator,
    private val playbackDiagnosticsLogger: PlaybackDiagnosticsLogger,
    private val analyticsEvents: SharedFlow<PlaybackAnalyticsListener.Event>,
    private val profileRepository: ProfileRepository,
) : DefaultLifecycleObserver {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        playbackDiagnosticsLogger.attach(analyticsEvents)
        // Child-profile gating must disarm the moment a profile switches, not
        // on the next throttled foreground refresh — every switch path emits
        // through this flow (the same signal HomeRealtime uses).
        scope.launch {
            profileRepository.profileSwitches.collect { coordinator.onProfileChanged() }
        }
        coordinator.onAppLaunched()
    }

    override fun onStart(owner: LifecycleOwner) {
        CrashContextCache.foreground.set(true)
        SiloLog.breadcrumb(DiagnosticsLogCategory.LIFECYCLE, "App", "app foregrounded")
        coordinator.onForeground()
    }

    override fun onStop(owner: LifecycleOwner) {
        CrashContextCache.foreground.set(false)
        SiloLog.breadcrumb(DiagnosticsLogCategory.LIFECYCLE, "App", "app backgrounded")
    }
}
