package org.siloserver.silo.android.ui.screens.player

import org.siloserver.silo.model.playback.SubtitleIdentity
import org.siloserver.silo.playback.encodeSubtitleIdentityPreference
import org.siloserver.silo.playback.resolveCatalogSubtitlePreferenceOrdinal

/**
 * The route-level choices that produced the current mobile player target.
 *
 * Explicitness is kept separately from the value because a null route argument
 * means "keep using automatic selection", not "the resolved player has no
 * file/track". This lets a bare repeated play link remain idempotent after its
 * first automatic resolution while still detecting a later explicit in-player
 * version or track choice.
 */
internal data class MobilePlayerRouteIntent(
    val contentId: String,
    val fileId: Int? = null,
    val quality: String? = null,
    val audioTrackIndex: Int? = null,
    val subtitleTrackIndex: Int? = null,
    val resumePositionSeconds: Double? = null,
    val fileIsExplicit: Boolean = fileId != null,
    val qualityIsExplicit: Boolean = quality != null,
    val audioTrackIsExplicit: Boolean = audioTrackIndex != null,
    val subtitleTrackIsExplicit: Boolean = subtitleTrackIndex != null,
)

/** Route intent plus the values the player is currently loading or playing. */
internal data class MobilePlayerRouteTarget(
    val intent: MobilePlayerRouteIntent,
    val contentId: String,
    val fileId: Int?,
    val quality: String?,
    val audioTrackIndex: Int?,
    val subtitleTrackIndex: Int?,
    val resumePositionSeconds: Double?,
)

/**
 * Owns route provenance independently from the resolved player state.
 *
 * Track choices are staged until the subtitle/audio transaction reports the
 * requested value as committed. Internal persisted/automatic restores never
 * call the staging methods, so they cannot become explicit route choices.
 */
internal class MobilePlayerRouteIntentState {
    private data class PendingAudioSelection(
        val contentId: String,
        val routeOrdinal: Int,
        val serverIndex: Int,
    )

    private data class PendingSubtitleSelection(
        val contentId: String,
        val routeOrdinal: Int?,
        val identity: SubtitleIdentity,
    )

    private data class VersionSelection(
        val contentId: String,
        val previous: MobilePlayerRouteIntent,
    )

    var current: MobilePlayerRouteIntent? = null
        private set

    private var pendingAudioSelection: PendingAudioSelection? = null
    private var pendingSubtitleSelection: PendingSubtitleSelection? = null
    private var versionSelection: VersionSelection? = null

    fun beginLoad(
        contentId: String,
        fileId: Int?,
        quality: String?,
        audioTrackIndex: Int?,
        subtitleTrackIndex: Int?,
        resumePositionSeconds: Double?,
        preserveCurrent: Boolean,
    ) {
        clearPendingTrackSelections()
        if (preserveCurrent && current != null) return
        current = MobilePlayerRouteIntent(
            contentId = contentId,
            fileId = fileId,
            quality = quality,
            audioTrackIndex = audioTrackIndex,
            subtitleTrackIndex = subtitleTrackIndex,
            resumePositionSeconds = resumePositionSeconds,
        )
        versionSelection = null
    }

    fun beginVersionSelection(contentId: String, fileId: Int) {
        val previous = current
            ?.takeIf { it.contentId == contentId }
            ?: MobilePlayerRouteIntent(contentId = contentId)
        versionSelection = VersionSelection(contentId = contentId, previous = previous)
        current = previous.copy(fileId = fileId, fileIsExplicit = true)
        clearPendingTrackSelections()
    }

    fun qualityForLoad(
        contentId: String,
        normalizedRequestedQuality: String?,
        preserveCurrent: Boolean,
    ): String? {
        if (!preserveCurrent) return normalizedRequestedQuality
        return current
            ?.takeIf { it.contentId == contentId && it.qualityIsExplicit }
            ?.quality
    }

    fun recoverVersionSelection(contentId: String) {
        val selection = versionSelection?.takeIf { it.contentId == contentId } ?: return
        current = selection.previous
        clearPendingTrackSelections()
    }

    fun beginAudioSelection(contentId: String, routeOrdinal: Int, serverIndex: Int) {
        pendingAudioSelection = PendingAudioSelection(contentId, routeOrdinal, serverIndex)
    }

    fun beginSubtitleSelection(
        contentId: String,
        routeOrdinal: Int?,
        identity: SubtitleIdentity,
    ) {
        pendingSubtitleSelection = PendingSubtitleSelection(contentId, routeOrdinal, identity)
    }

    fun applyCommittedTracks(
        contentId: String,
        committedAudioServerIndex: Int?,
        committedSubtitleIdentity: SubtitleIdentity,
        transactionFailed: Boolean,
        transactionActive: Boolean,
    ) {
        if (transactionFailed) {
            clearPendingTrackSelections()
            return
        }

        pendingAudioSelection
            ?.takeIf { it.contentId == contentId }
            ?.let { pending ->
                if (pending.serverIndex == committedAudioServerIndex) {
                    current
                        ?.takeIf { it.contentId == contentId }
                        ?.let { intent ->
                            current = intent.copy(
                                audioTrackIndex = pending.routeOrdinal,
                                audioTrackIsExplicit = true,
                            )
                        }
                    pendingAudioSelection = null
                } else if (!transactionActive) {
                    pendingAudioSelection = null
                }
            }

        pendingSubtitleSelection
            ?.takeIf { it.contentId == contentId }
            ?.let { pending ->
                if (pending.identity == committedSubtitleIdentity) {
                    current
                        ?.takeIf { it.contentId == contentId }
                        ?.let { intent ->
                            current = intent.copy(
                                subtitleTrackIndex = pending.routeOrdinal,
                                subtitleTrackIsExplicit = true,
                            )
                        }
                    pendingSubtitleSelection = null
                } else if (!transactionActive) {
                    pendingSubtitleSelection = null
                }
            }
    }

    fun clear() {
        current = null
        versionSelection = null
        clearPendingTrackSelections()
    }

    private fun clearPendingTrackSelections() {
        pendingAudioSelection = null
        pendingSubtitleSelection = null
    }
}

/**
 * Produces one atomic target snapshot for external-route redelivery.
 *
 * While a load is pending, its explicit route intent is the only authoritative
 * target. Once mounted, content/file/track values come from live player state;
 * the quality intent remains route-owned because it is a playback ceiling, not
 * necessarily the selected source file's resolution.
 */
internal fun mobilePlayerRouteTarget(
    intent: MobilePlayerRouteIntent?,
    state: PlayerViewModel.PlayerUiState,
): MobilePlayerRouteTarget? {
    intent ?: return null
    if (state.contentId.isBlank() || state.contentId != intent.contentId || state.error != null) {
        return null
    }

    if (state.isLoading) {
        return MobilePlayerRouteTarget(
            intent = intent,
            contentId = state.contentId,
            fileId = intent.fileId,
            quality = intent.quality,
            audioTrackIndex = intent.audioTrackIndex,
            subtitleTrackIndex = intent.subtitleTrackIndex,
            resumePositionSeconds = intent.resumePositionSeconds,
        )
    }

    if (state.streamUrl.isNullOrBlank()) return null
    val version = state.versions.getOrNull(state.selectedVersionIndex)
    val catalogSubtitleOrdinal = resolveCatalogSubtitlePreferenceOrdinal(
        tracks = version?.subtitleTracks.orEmpty(),
        preference = encodeSubtitleIdentityPreference(state.committedSubtitleIdentity),
    )
    return MobilePlayerRouteTarget(
        intent = intent,
        contentId = state.contentId,
        fileId = state.mediaFileId,
        quality = intent.quality,
        audioTrackIndex = state.selectedAudioIndex.takeIf { it in state.audioTracks.indices },
        subtitleTrackIndex = catalogSubtitleOrdinal,
        resumePositionSeconds = intent.resumePositionSeconds,
    )
}

internal fun catalogSubtitleRouteOrdinal(
    state: PlayerViewModel.PlayerUiState,
    identity: org.siloserver.silo.model.playback.SubtitleIdentity,
): Int? = resolveCatalogSubtitlePreferenceOrdinal(
    tracks = state.versions
        .getOrNull(state.selectedVersionIndex)
        ?.subtitleTracks
        .orEmpty(),
    preference = encodeSubtitleIdentityPreference(identity),
)
