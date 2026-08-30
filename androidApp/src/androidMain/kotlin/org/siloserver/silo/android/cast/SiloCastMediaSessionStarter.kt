package org.siloserver.silo.android.cast

import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Starts the phone-only Remote Control media service while the application is
 * foregrounded, then leaves Media3 to maintain its foreground lifetime after
 * the Activity moves to the background.
 *
 * Android 12+ rejects a new foreground-service start from the background. A
 * TV may begin a new title while the phone is backgrounded, so those starts
 * are deliberately deferred until [onStart]. An already-running service keeps
 * receiving controller state directly and does not need to be started again.
 */
class SiloCastMediaSessionStarter(
    context: Context,
    private val controller: SiloCastController,
) : DefaultLifecycleObserver {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var appForeground = false
    private var latestState = controller.state.value.toRemoteServiceState()

    fun start() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        scope.launch {
            controller.state
                .map { it.toRemoteServiceState() }
                .distinctUntilChanged()
                .collect { state ->
                    latestState = state
                    applyServiceAction(resolveRemoteMediaServiceAction(state, appForeground))
                }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        appForeground = true
        applyServiceAction(resolveRemoteMediaServiceAction(latestState, appForeground = true))
    }

    override fun onStop(owner: LifecycleOwner) {
        appForeground = false
    }

    @OptIn(UnstableApi::class)
    private fun applyServiceAction(action: RemoteMediaServiceAction) {
        val intent = Intent(appContext, SiloCastMediaSessionService::class.java)
        runCatching {
            when (action) {
                RemoteMediaServiceAction.None -> Unit
                RemoteMediaServiceAction.Stop -> appContext.stopService(intent)
                // Media3 promotes an ongoing session itself. Launching with
                // startForegroundService here arms the platform watchdog
                // before Media3 has decided that a notification is needed.
                RemoteMediaServiceAction.Start -> appContext.startService(intent)
            }
        }.onFailure { error ->
            android.util.Log.w(TAG, "Could not apply Remote Control media-service action $action", error)
        }
    }

    private companion object {
        const val TAG = "SiloCastMediaStarter"
    }
}

internal data class RemoteServiceState(
    val hasMedia: Boolean,
)

internal enum class RemoteMediaServiceAction {
    None,
    Stop,
    Start,
}

internal fun resolveRemoteMediaServiceAction(
    state: RemoteServiceState,
    appForeground: Boolean,
): RemoteMediaServiceAction = when {
    !state.hasMedia -> RemoteMediaServiceAction.Stop
    !appForeground -> RemoteMediaServiceAction.None
    else -> RemoteMediaServiceAction.Start
}

private fun SiloCastControllerState.toRemoteServiceState(): RemoteServiceState = RemoteServiceState(
    hasMedia = !playbackState?.contentId.isNullOrBlank(),
)
