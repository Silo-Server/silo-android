package org.siloserver.silo.android.home

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.siloserver.silo.repository.ForegroundAccessCheck
import org.siloserver.silo.repository.HomeRealtimeCoordinator
import org.siloserver.silo.repository.ProfileRepository

/**
 * Drives the [HomeRealtimeCoordinator] socket off the app's foreground
 * lifecycle, mirroring NotificationsForegroundStarter: ON_START opens the
 * socket, ON_STOP tears it down, and each profile-switch reset signal
 * (ProfileRepository.profileSwitches)
 * reconnects so the coordinator's profile filter tracks the new session.
 * REST stays the source of truth; a dead socket just means pull behavior.
 * Returning from the background runs [accessCheck], because a socket that was
 * closed while stopped cannot report an access change made in that time.
 */
class HomeRealtimeForegroundStarter(
    private val coordinator: HomeRealtimeCoordinator,
    private val profileRepository: ProfileRepository,
    private val accessCheck: ForegroundAccessCheck? = null,
) : DefaultLifecycleObserver {

    private var realtimeScope: CoroutineScope? = null
    private var foregroundScope: CoroutineScope? = null
    private var stoppedSinceStart = false

    fun register() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        startRealtime()
        observeProfileSwitches()
        if (stoppedSinceStart) {
            stoppedSinceStart = false
            foregroundScope?.launch { accessCheck?.afterReturnToForeground() }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        stoppedSinceStart = true
        stopRealtime()
        foregroundScope?.cancel()
        foregroundScope = null
    }

    private fun startRealtime() {
        if (realtimeScope != null) return
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        realtimeScope = scope
        coordinator.connect(scope)
    }

    private fun stopRealtime() {
        realtimeScope?.cancel()
        realtimeScope = null
    }

    private fun observeProfileSwitches() {
        if (foregroundScope != null) return
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        foregroundScope = scope
        scope.launch {
            profileRepository.profileSwitches.collect {
                stopRealtime()
                startRealtime()
            }
        }
    }
}
