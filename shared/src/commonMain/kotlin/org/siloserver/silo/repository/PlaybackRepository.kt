package org.siloserver.silo.repository

import org.siloserver.silo.model.playback.PlaybackDecisionResponseV3
import org.siloserver.silo.model.playback.PlaybackReplanRequestV3
import org.siloserver.silo.model.playback.PlaybackRouteEventV3
import org.siloserver.silo.model.playback.PlaybackStartRequestV3
import org.siloserver.silo.model.playback.ProgressRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.acceptsMetadataOwner

class PlaybackRepository(
    private val sequenced: SequencedPlayback? = null,
    private val tokens: TokenManager? = null,
) {
    suspend fun controlOwner(sessionId: String): Pair<AuthScopeSnapshot, String?>? = sequenced?.controlOwner(sessionId)
    val pendingPlayback = sequenced?.pending ?: MutableStateFlow(emptyList<String>())
    fun isSequenced(sessionId: String): Boolean = sequenced?.owns(sessionId) == true
    suspend fun pendingPlaybackCount(): Int = sequenced?.pendingForCurrentViewer() ?: 0
    suspend fun recoverPlayback(): ApiResult<Unit> = guarded { sequenced?.recover() ?: ApiResult.Success(Unit) }
    private suspend fun <T> guarded(block: suspend () -> ApiResult<T>): ApiResult<T> = try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { ApiResult.Error(0, "playback_storage", "Playback recovery storage is unavailable.") }

    /** Starts a protocol-v3 playback attempt using the supplied client and route evidence. */
    suspend fun startPlaybackV3(request: PlaybackStartRequestV3, expectedMetadataOwner: AuthScopeSnapshot? = null): ApiResult<PlaybackDecisionResponseV3> = guarded {
        if (!tokens.acceptsMetadataOwner(expectedMetadataOwner, request.profileId)) return@guarded changedOwner()
        val negotiated = sequenced?.start(request, expectedMetadataOwner)
        if (negotiated != null) return@guarded negotiated
        if (!tokens.acceptsMetadataOwner(expectedMetadataOwner, request.profileId)) return@guarded changedOwner()
        unavailable()
    }
    private fun unavailable() = ApiResult.Error(0, "playback_unavailable", "V2 playback is unavailable. Start playback again when the server supports it.")
    private fun changedOwner() = ApiResult.Error(0, "identity_changed", "The metadata viewer changed before playback admission.")

    /** Requests a replacement protocol-v3 plan for an active [sessionId]. */
    suspend fun replanPlaybackV3(
        sessionId: String,
        request: PlaybackReplanRequestV3,
    ): ApiResult<PlaybackDecisionResponseV3> = guarded { sequenced?.replan(sessionId, request) ?: unavailable() }

    /** Reports attempt-scoped telemetry under the original v2 admission authority. */
    suspend fun reportRouteEventV3(request: PlaybackRouteEventV3): ApiResult<Unit> =
        guarded { sequenced?.routeEvent(request) ?: unavailable() }

    /** Reports current playback position and paused state to the server. */
    suspend fun updateProgress(
        sessionId: String,
        position: Double,
        isPaused: Boolean,
    ): ApiResult<Unit> =
        guarded { sequenced?.progress(sessionId, position, isPaused) ?: unavailable() }

    /** Stops an active playback session. */
    suspend fun stopPlayback(sessionId: String): ApiResult<Unit> =
        guarded { sequenced?.stop(sessionId) ?: unavailable() }
}
