package org.siloserver.silo.model.playback

sealed interface SubtitleIdentity {
    data object Off : SubtitleIdentity

    data class ServerSidecar(
        val serverIndex: Int,
        val media: SubtitleMediaIdentity? = null,
    ) : SubtitleIdentity

    data class ServerBurnIn(
        val serverIndex: Int,
        val media: SubtitleMediaIdentity? = null,
    ) : SubtitleIdentity

    data class Embedded(
        val serverIndex: Int,
        val media: SubtitleMediaIdentity,
        /** Exact server-probed container identity; never permit metadata fallback. */
        val containerTrackId: String? = null,
    ) : SubtitleIdentity

    data class Downloaded(
        val downloadId: Int,
        val media: SubtitleMediaIdentity,
    ) : SubtitleIdentity

    data class LocalMedia3(val media: SubtitleMediaIdentity) : SubtitleIdentity
}

data class SubtitleMediaIdentity(
    val trackId: String? = null,
    val label: String? = null,
    val language: String? = null,
    val codecFamily: String? = null,
    val forced: Boolean? = null,
    val hearingImpaired: Boolean? = null,
)

data class CommittedSubtitle(
    val identity: SubtitleIdentity,
    val audioTrackIndex: Int? = null,
    val qualityPreference: String? = null,
    /**
     * Whether the AUDIO was what this commit changed.
     *
     * Every commit carries the current audio index, including one that only
     * changed subtitles — so the index alone cannot say whether the viewer
     * chose that audio or merely happens to be listening to it. Callers that
     * persist an audio preference need the difference.
     */
    val audioPreferenceSpecified: Boolean = false,
)

data class PendingSubtitle(
    val identity: SubtitleIdentity,
    val generation: Long,
    val audioTrackIndex: Int?,
    val qualityPreference: String?,
    val audioPreferenceSpecified: Boolean = false,
    val qualityPreferenceSpecified: Boolean = false,
)

data class SubtitleTransitionState(
    val committed: CommittedSubtitle,
    val pending: PendingSubtitle?,
    val nextGeneration: Long,
) {
    companion object {
        fun committed(
            identity: SubtitleIdentity,
            audioTrackIndex: Int? = null,
            qualityPreference: String? = null,
        ): SubtitleTransitionState = SubtitleTransitionState(
            committed = CommittedSubtitle(
                identity = identity,
                audioTrackIndex = audioTrackIndex,
                qualityPreference = qualityPreference,
            ),
            pending = null,
            nextGeneration = 1L,
        )
    }
}

data class StagedSubtitleCandidate(val id: String)

sealed interface SubtitleTransitionEvent

data class SelectSubtitle(val identity: SubtitleIdentity) : SubtitleTransitionEvent

data class UpdateAudioPreference(val audioTrackIndex: Int?) : SubtitleTransitionEvent

data class UpdateQualityPreference(val qualityPreference: String?) : SubtitleTransitionEvent

data class StagedSubtitleValidated(
    val generation: Long,
    val candidate: StagedSubtitleCandidate,
) : SubtitleTransitionEvent

data class StagedSubtitleFailed(
    val generation: Long,
    val message: String,
) : SubtitleTransitionEvent

data class SubtitleContentReset(
    val committedIdentity: SubtitleIdentity = SubtitleIdentity.Off,
) : SubtitleTransitionEvent

sealed interface SubtitleTransitionEffect

data class StageSubtitleReplan(
    val pending: PendingSubtitle,
) : SubtitleTransitionEffect

data class CommitStagedSubtitleReplan(
    val generation: Long,
    val candidate: StagedSubtitleCandidate,
    val committed: CommittedSubtitle,
) : SubtitleTransitionEffect

data class DiscardStagedSubtitleReplan(
    val candidate: StagedSubtitleCandidate,
) : SubtitleTransitionEffect

data class ApplyLocalSubtitleSelection(
    val committed: CommittedSubtitle,
) : SubtitleTransitionEffect

data class PersistSubtitleSelection(
    val committed: CommittedSubtitle,
) : SubtitleTransitionEffect

data class ReportSubtitleTransitionFailure(
    val message: String,
) : SubtitleTransitionEffect

data class SubtitleTransitionResult(
    val state: SubtitleTransitionState,
    val effects: List<SubtitleTransitionEffect>,
)

fun reduceSubtitleTransition(
    state: SubtitleTransitionState,
    event: SubtitleTransitionEvent,
): SubtitleTransitionResult = when (event) {
    is SelectSubtitle -> state.select(event.identity)
    is UpdateAudioPreference -> state.stageLatest(
        identity = state.pending?.identity ?: state.committed.identity,
        audioTrackIndex = event.audioTrackIndex,
        qualityPreference = state.effectiveQualityPreference(),
        audioPreferenceSpecified = true,
        qualityPreferenceSpecified = state.pending?.qualityPreferenceSpecified ?: false,
    )
    is UpdateQualityPreference -> state.stageLatest(
        identity = state.pending?.identity ?: state.committed.identity,
        audioTrackIndex = state.effectiveAudioTrackIndex(),
        qualityPreference = event.qualityPreference,
        audioPreferenceSpecified = state.pending?.audioPreferenceSpecified ?: false,
        qualityPreferenceSpecified = true,
    )
    is StagedSubtitleValidated -> state.validate(event)
    is StagedSubtitleFailed -> state.fail(event)
    is SubtitleContentReset -> SubtitleTransitionResult(
        state = SubtitleTransitionState.committed(event.committedIdentity).copy(
            nextGeneration = state.nextGeneration + 1,
        ),
        effects = emptyList(),
    )
}

private fun SubtitleTransitionState.select(identity: SubtitleIdentity): SubtitleTransitionResult {
    val audioTrackIndex = effectiveAudioTrackIndex()
    val qualityPreference = effectiveQualityPreference()
    if (identity.requiresClientMount() && pending.hasServerPreferenceMutation()) {
        return stageLatest(
            identity = identity,
            audioTrackIndex = audioTrackIndex,
            qualityPreference = qualityPreference,
            audioPreferenceSpecified = pending?.audioPreferenceSpecified == true,
            qualityPreferenceSpecified = pending?.qualityPreferenceSpecified == true,
        )
    }
    if (identity.requiresClientMount()) {
        val updated = CommittedSubtitle(
            identity = identity,
            audioTrackIndex = audioTrackIndex,
            qualityPreference = qualityPreference,
            audioPreferenceSpecified = pending?.audioPreferenceSpecified == true,
        )
        return SubtitleTransitionResult(
            state = copy(
                committed = updated,
                pending = null,
                nextGeneration = nextGeneration + 1,
            ),
            effects = listOf(
                ApplyLocalSubtitleSelection(updated),
                PersistSubtitleSelection(updated),
            ),
        )
    }
    return stageLatest(
        identity = identity,
        audioTrackIndex = audioTrackIndex,
        qualityPreference = qualityPreference,
        audioPreferenceSpecified = pending?.audioPreferenceSpecified ?: false,
        qualityPreferenceSpecified = pending?.qualityPreferenceSpecified ?: false,
    )
}

private fun SubtitleIdentity.requiresClientMount(): Boolean =
    this is SubtitleIdentity.LocalMedia3 || this is SubtitleIdentity.Downloaded

private fun PendingSubtitle?.hasServerPreferenceMutation(): Boolean =
    this?.audioPreferenceSpecified == true || this?.qualityPreferenceSpecified == true

private fun SubtitleTransitionState.stageLatest(
    identity: SubtitleIdentity,
    audioTrackIndex: Int?,
    qualityPreference: String?,
    audioPreferenceSpecified: Boolean,
    qualityPreferenceSpecified: Boolean,
): SubtitleTransitionResult {
    val latest = PendingSubtitle(
        identity = identity,
        generation = nextGeneration,
        audioTrackIndex = audioTrackIndex,
        qualityPreference = qualityPreference,
        audioPreferenceSpecified = audioPreferenceSpecified,
        qualityPreferenceSpecified = qualityPreferenceSpecified,
    )
    return SubtitleTransitionResult(
        state = copy(
            pending = latest,
            nextGeneration = nextGeneration + 1,
        ),
        effects = listOf(StageSubtitleReplan(latest)),
    )
}

private fun SubtitleTransitionState.effectiveAudioTrackIndex(): Int? =
    if (pending != null) pending.audioTrackIndex else committed.audioTrackIndex

private fun SubtitleTransitionState.effectiveQualityPreference(): String? =
    if (pending != null) pending.qualityPreference else committed.qualityPreference

private fun SubtitleTransitionState.validate(
    event: StagedSubtitleValidated,
): SubtitleTransitionResult {
    val latest = pending
    if (latest == null || latest.generation != event.generation) {
        return SubtitleTransitionResult(
            state = this,
            effects = listOf(DiscardStagedSubtitleReplan(event.candidate)),
        )
    }

    val updated = CommittedSubtitle(
        identity = latest.identity,
        audioTrackIndex = latest.audioTrackIndex,
        qualityPreference = latest.qualityPreference,
        audioPreferenceSpecified = latest.audioPreferenceSpecified,
    )
    return SubtitleTransitionResult(
        state = copy(committed = updated, pending = null),
        effects = listOf(
            CommitStagedSubtitleReplan(
                generation = latest.generation,
                candidate = event.candidate,
                committed = updated,
            ),
            PersistSubtitleSelection(updated),
        ),
    )
}

private fun SubtitleTransitionState.fail(
    event: StagedSubtitleFailed,
): SubtitleTransitionResult {
    val latest = pending
    if (latest == null || latest.generation != event.generation) {
        return SubtitleTransitionResult(this, emptyList())
    }
    return SubtitleTransitionResult(
        state = copy(pending = null),
        effects = listOf(ReportSubtitleTransitionFailure(event.message)),
    )
}
