package org.siloserver.silo.repository

import org.siloserver.silo.model.playback.PlaybackDecisionResponseV3
import org.siloserver.silo.model.playback.PlaybackReplanRequestV3
import org.siloserver.silo.model.playback.PlaybackRouteEventV3
import org.siloserver.silo.model.playback.PlaybackStartRequestV3
import kotlinx.coroutines.CancellationException
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot

class PlaybackRepository(private val sequenced: SequencedPlayback) {
    suspend fun controlOwner(sessionId: String): Pair<AuthScopeSnapshot, String?>? = sequenced.controlOwner(sessionId)
    val pendingPlayback = sequenced.pending
    fun isSequenced(sessionId: String): Boolean = sequenced.owns(sessionId)
    suspend fun pendingPlaybackCount(): Int = sequenced.pendingForCurrentViewer()
    suspend fun recoverPlayback(): ApiResult<Unit> = guarded { sequenced.recover() }
    /** [SequencedPlayback] answers null for a session it never journaled. */
    private fun unknownSession() = ApiResult.Error(0, "playback_unavailable", "This playback session is not owned by the app.")
    private suspend fun <T> guarded(block: suspend () -> ApiResult<T>): ApiResult<T> = try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { ApiResult.Error(0, "playback_storage", "Playback recovery storage is unavailable.") }

    /** Starts a protocol-v3 playback attempt using the supplied client and route evidence. */
    suspend fun startPlaybackV3(request: PlaybackStartRequestV3, expectedMetadataOwner: AuthScopeSnapshot? = null): ApiResult<PlaybackDecisionResponseV3> =
        guarded { sequenced.start(request, expectedMetadataOwner) }

    /** Requests a replacement protocol-v3 plan for an active [sessionId]. */
    suspend fun replanPlaybackV3(
        sessionId: String,
        request: PlaybackReplanRequestV3,
    ): ApiResult<PlaybackDecisionResponseV3> = guarded { sequenced.replan(sessionId, request) }

    /** Reports attempt-scoped telemetry under the original v2 admission authority. */
    suspend fun reportRouteEventV3(request: PlaybackRouteEventV3): ApiResult<Unit> =
        guarded { sequenced.routeEvent(request) }

    /** Reports current playback position and paused state to the server. */
    suspend fun updateProgress(
        sessionId: String,
        position: Double,
        isPaused: Boolean,
    ): ApiResult<Unit> =
        guarded { sequenced.progress(sessionId, position, isPaused) ?: unknownSession() }

    /** Stops an active playback session. */
    suspend fun stopPlayback(sessionId: String): ApiResult<Unit> =
        guarded { sequenced.stop(sessionId) ?: unknownSession() }
}
