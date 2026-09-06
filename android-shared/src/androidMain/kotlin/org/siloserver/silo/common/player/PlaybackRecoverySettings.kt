package org.siloserver.silo.common.player

import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.PlaybackRepository

class PlaybackRecoverySettings {
    var visible by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var message by mutableStateOf("Retry unfinished playback requests and confirm they have stopped.")
    var retry: () -> Unit = {}
}

/** An explicit user action; entering Settings only reads the local journal. */
@Composable
fun rememberPlaybackRecoverySettings(repository: PlaybackRepository): PlaybackRecoverySettings {
    val pending by repository.pendingPlayback.collectAsState()
    val scope = rememberCoroutineScope()
    val state = remember { PlaybackRecoverySettings() }
    LaunchedEffect(pending) {
        try { state.visible = repository.pendingPlaybackCount() > 0 }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { state.visible = true; state.message = "Playback recovery storage is unavailable." }
    }
    state.retry = {
        if (!state.busy) scope.launch {
            state.busy = true
            try {
                state.message = when (val result = repository.recoverPlayback()) {
                    is ApiResult.Success -> if (repository.pendingPlaybackCount() == 0) "Playback stops confirmed."
                        else "Some playback requests remain unavailable for recovery."
                    is ApiResult.Error -> result.message
                    is ApiResult.NetworkError -> "Playback recovery could not reach the server. Retry when connected."
                }
            } finally { state.busy = false }
        }
    }
    return state
}
