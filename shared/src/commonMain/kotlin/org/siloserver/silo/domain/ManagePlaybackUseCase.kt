package org.siloserver.silo.domain

import org.siloserver.silo.model.catalog.WatchDetail
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.CatalogRepository
import org.siloserver.silo.repository.PlaybackRepository

/**
 * Orchestrates the playback lifecycle around an already-started session:
 * progress reporting, teardown, and watch detail.
 *
 * Session creation is not here. Starting playback needs the full v3 evidence
 * bundle — codec probe, output context, delivery capabilities — which only the
 * platform layer can assemble, so it runs through
 * `PlaybackSessionManager.startVideoSessionV3` instead.
 *
 * Combines [PlaybackRepository] for session management with [CatalogRepository]
 * for fetching watch detail (versions, intro/credits markers, user progress).
 */
class ManagePlaybackUseCase(
    private val playbackRepo: PlaybackRepository,
    private val catalogRepo: CatalogRepository,
) {
    /**
     * Reports the current playback position and paused state.
     * Should be called periodically during playback (e.g. every 10 seconds).
     */
    suspend fun reportProgress(
        sessionId: String,
        position: Double,
        isPaused: Boolean,
    ): ApiResult<Unit> =
        playbackRepo.updateProgress(sessionId, position, isPaused)

    /**
     * Stops the playback session.
     * Should be called when the user exits the player or playback completes.
     */
    suspend fun stopPlayback(sessionId: String): ApiResult<Unit> =
        playbackRepo.stopPlayback(sessionId)

    /**
     * Fetches playback-oriented detail for a content item.
     * Includes available versions, subtitle info, intro/credits markers,
     * and the user's last playback position.
     */
    suspend fun getWatchDetail(contentId: String): ApiResult<WatchDetail> =
        catalogRepo.getWatchDetail(contentId)
}
