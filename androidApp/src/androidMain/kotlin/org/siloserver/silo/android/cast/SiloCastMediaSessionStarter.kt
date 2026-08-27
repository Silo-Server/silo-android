package org.siloserver.silo.android.cast

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Starts the phone-only Remote Control media service whenever the TV reports
 * an active title. This is application-scoped so the service is established
 * before the Activity can move to the background.
 */
class SiloCastMediaSessionStarter(
    context: Context,
    private val controller: SiloCastController,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun start() {
        scope.launch {
            controller.state
                .map { state ->
                    val playback = state.playbackState
                    RemoteServiceState(
                        hasMedia = !playback?.contentId.isNullOrBlank(),
                        needsForegroundStart = playback?.let {
                            it.isPlaying || it.isLoading || it.isBuffering
                        } == true,
                    )
                }
                .distinctUntilChanged()
                .collect { state ->
                    val intent = Intent(appContext, SiloCastMediaSessionService::class.java)
                    when {
                        !state.hasMedia -> appContext.stopService(intent)
                        state.needsForegroundStart -> ContextCompat.startForegroundService(appContext, intent)
                        else -> appContext.startService(intent)
                    }
                }
        }
    }

    private data class RemoteServiceState(
        val hasMedia: Boolean,
        val needsForegroundStart: Boolean,
    )
}
