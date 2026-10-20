package org.siloserver.silo.common.player

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.repository.PlaybackRepository

/**
 * Sends the playback telemetry earlier launches left unsent: the plan summary
 * the phone saved as it left the foreground, and route events whose send
 * failed on the network. See
 * [org.siloserver.silo.repository.SequencedPlayback.replayRouteEvents].
 *
 * Runs off the main thread once a profile is signed in, and again whenever the
 * signed-in server or profile changes, because each attempt's events go only
 * to the login and profile that recorded them. Used by both the phone and TV
 * `Application`s.
 */
class RouteEventReplayStarter(
    private val registry: ServerRegistry,
    private val playback: PlaybackRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    fun start() {
        scope.launch {
            registry.activeEntry
                .map { entry -> entry?.profileId?.takeIf(String::isNotBlank)?.let { entry.id to it } }
                .distinctUntilChanged()
                .filterNotNull()
                .collect {
                    val result = playback.replayRouteEventsV3()
                    if (result is ApiResult.Error) Log.w(TAG, "route event replay failed: ${result.error}")
                }
        }
    }

    private companion object {
        const val TAG = "RouteEventReplay"
    }
}
