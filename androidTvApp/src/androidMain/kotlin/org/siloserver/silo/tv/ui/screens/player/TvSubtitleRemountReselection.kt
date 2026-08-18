package org.siloserver.silo.tv.ui.screens.player

import org.siloserver.silo.common.player.SubDiag
import org.siloserver.silo.common.player.MountedSubtitleTrack
import org.siloserver.silo.common.player.trackIdDenotes
import org.siloserver.silo.common.player.subtitleArtifactTrackId
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.model.playback.SubtitleIdentity
import org.siloserver.silo.playback.downloadedSubtitleArtifactTrackId

/**
 * Why a subtitle mount was asked for, ordered by authority.
 *
 * Every mount now originates in the subtitle transaction adapter — the legacy
 * ordinal pipeline that raced it is gone — but the adapter still serves three
 * kinds of intent, and a weaker one arriving late must not evict a stronger one
 * that is still being applied. That collision is what used to turn an applied
 * subtitle back off: a rollback armed the pre-transaction identity (typically
 * Off) over the selection the viewer had just made.
 *
 * Higher ordinal wins.
 */
internal enum class TvSubtitleMountPriority {
    /** Automatic language/forced heuristics; may never override a real choice. */
    Auto,

    /** Persisted preference, detail-page pick, transport remount restore. */
    Restore,

    /** An explicit in-flight user selection. Outranks everything. */
    UserTransaction,
}

internal data class TvSubtitleRemountOwner(
    val identity: SubtitleIdentity,
    val generation: Long,
    val priority: TvSubtitleMountPriority = TvSubtitleMountPriority.UserTransaction,
)

/**
 * A mount the screen must apply to the player backend, carrying its owner.
 *
 * The owner travels WITH the request rather than sitting in a ViewModel field
 * the acknowledgement reads back: that field was the reason an app-originated
 * selection could be applied to the player and then dropped on the floor,
 * because whoever emitted the request had armed no owner and the
 * acknowledgement silently returned. An ownerless mount is now unrepresentable.
 */
internal data class TvSubtitleMountRequest(
    val owner: TvSubtitleRemountOwner,
    /** Media3 flat text-track ordinal, or -1 to disable text entirely. */
    val trackIndex: Int,
)

internal class TvSubtitleSnapshotSettlementTracker {
    private var previousKey: String? = null

    fun observe(tracks: List<PlayerTrackEntry>): Boolean {
        val key = tracks
            .takeIf(List<PlayerTrackEntry>::isNotEmpty)
            ?.joinToString("|") {
                "${it.index}:${it.trackId}:${it.label}:${it.language}:${it.codecOrMime}:${it.isSelected}"
            }
        val settled = key != null && key == previousKey
        previousKey = key
        return settled
    }

    fun reset() {
        previousKey = null
    }
}

internal enum class TvSubtitleRemountFailure {
    Missing,
    Ambiguous,
}

internal sealed interface TvSubtitleRemountEvent {
    val owner: TvSubtitleRemountOwner

    data class Select(
        override val owner: TvSubtitleRemountOwner,
        val trackIndex: Int,
    ) : TvSubtitleRemountEvent

    data class Failed(
        override val owner: TvSubtitleRemountOwner,
        val reason: TvSubtitleRemountFailure,
    ) : TvSubtitleRemountEvent
}

internal class SubtitleRemountReselection(
    private val maxMeaningfulSnapshots: Int = 3,
) {
    private var pendingOwner: TvSubtitleRemountOwner? = null
    private val meaningfulSnapshotKeys = linkedSetOf<String>()

    /**
     * The owner whose match has been emitted but not yet acknowledged.
     *
     * consume() used to clear() the instant it matched, leaving the latch
     * unowned while the request travelled to the screen — and a lower-authority
     * arm landing in that window silently took over the mount. Authority has to
     * survive until the mount is acknowledged, so a resolved owner keeps
     * defending its claim.
     *
     * Released by [acknowledgeResolved] on mount success/failure, and by
     * [releaseResolved] when the acknowledgement can no longer arrive (backend
     * swap, mount deadline) — without which a dropped acknowledgement would
     * wedge subtitle mounting for the rest of the session.
     */
    private var resolvedOwner: TvSubtitleRemountOwner? = null

    val hasPendingOwner: Boolean
        get() = pendingOwner != null

    fun requiresRemount(identity: SubtitleIdentity): Boolean = when (identity) {
        SubtitleIdentity.Off,
        is SubtitleIdentity.ServerSidecar,
        is SubtitleIdentity.Embedded,
        is SubtitleIdentity.Downloaded,
        is SubtitleIdentity.LocalMedia3,
        -> true
        is SubtitleIdentity.ServerBurnIn -> false
    }

    /** Priority defending the mount: a pending owner, or one awaiting its ack. */
    val pendingPriority: TvSubtitleMountPriority?
        get() = pendingOwner?.priority ?: resolvedOwner?.priority

    /** Clears the resolved claim once its mount has been acknowledged. */
    fun acknowledgeResolved(generation: Long) {
        if (resolvedOwner?.generation == generation) resolvedOwner = null
    }

    /**
     * Drops the resolved claim when its acknowledgement can no longer arrive.
     *
     * The acknowledgement travels over a replay-0 shared flow collected inside
     * a backend-scoped effect, so a backend swap — an auto-advance or version
     * switch — tears the collector down and the emission is lost. Without this
     * release the claim would defend a mount that can never be confirmed.
     */
    fun releaseResolved() {
        resolvedOwner = null
    }

    fun arm(
        identity: SubtitleIdentity,
        generation: Long,
        priority: TvSubtitleMountPriority = TvSubtitleMountPriority.UserTransaction,
    ) {
        // A lower-authority source must not evict a mount the user asked for.
        // That collision is what turned an applied subtitle back off: a rollback
        // armed the pre-transaction identity (typically Off) over the selection
        // that had just been applied.
        // Equal authority always replaces — a user's newest pick must win over
        // their previous one, and a replan legitimately re-arms the identity it
        // is rebasing. Only STRICTLY lower authority is refused, which is what
        // stops a rollback-armed Off (Restore) from evicting a selection the
        // user is applying (UserTransaction).
        val holder = pendingOwner ?: resolvedOwner
        val blocked = holder != null && holder.priority > priority
        if (blocked) {
            SubDiag.log("REMOUNT arm IGNORED $identity prio=$priority held=${holder?.priority}")
            return
        }
        SubDiag.log("REMOUNT arm $identity gen=$generation prio=$priority")
        pendingOwner = if (requiresRemount(identity)) {
            TvSubtitleRemountOwner(identity, generation, priority)
        } else {
            null
        }
        meaningfulSnapshotKeys.clear()
    }

    /**
     * @param subtitleRows the authoritative inventory rows, needed to resolve a
     * server-row identity whose track is muxed into the stream rather than
     * mounted as an authored artifact (see [tvResolveMountedSubtitleTrack]).
     * Without them such an identity resolves to no ordinal at all, and a mount
     * the adapter committed locally would fail and roll back.
     */
    fun consume(
        subtitleTracks: List<PlayerTrackEntry>,
        subtitleRows: List<PlayerSubtitleInfo> = emptyList(),
        snapshotKey: String?,
        settled: Boolean,
    ): TvSubtitleRemountEvent? {
        val owner = pendingOwner ?: return null
        if (owner.identity == SubtitleIdentity.Off) {
            // Disabling the text renderer needs no track to exist, so this
            // deliberately resolves without inspecting the snapshot. A stale
            // Off owner reaching here at all is an OWNERSHIP failure, fixed by
            // arming rollback-derived identities below user authority — not by
            // adding evidence checks here, which would break a legitimate Off
            // applied while the stream is still publishing.
            clear()
            resolvedOwner = owner
            return TvSubtitleRemountEvent.Select(owner, trackIndex = -1)
        }

        val mounted = subtitleTracks.map(PlayerTrackEntry::toMountedTvSubtitleTrack)
        val exactTrackId = owner.identity.exactTvMountTrackId()
        val matchIndex = exactTrackId?.let { expected ->
            // Every candidate here denotes the SAME authored artifact id, so
            // multiple hits are the one sidecar merged more than once (Media3
            // prefixes each with its MergingMediaSource child index, e.g.
            // "1:silo-subtitle:3" and "2:silo-subtitle:3"). That is not the
            // ambiguity this guard exists for — it cannot select the wrong
            // language — so refusing on it left the mount unresolved until the
            // deadline blew and the transaction rolled back to Off. Ambiguity
            // between genuinely different tracks is still caught by the
            // metadata path below and by hasAmbiguousTvLabel.
            mounted.filter { trackIdDenotes(it.trackId, expected) }
                .minByOrNull { it.index }
                ?.index
        } ?: when {
            // An identity carrying a REAL Media3 id stays exact-only: falling
            // back to metadata could mount a different track that merely looks
            // alike. A sidecar's id is authored by us, not by the stream, and a
            // v3 row describing a track muxed into a direct-play stream is
            // typed as a sidecar all the same — so that id matches nothing and
            // the mount hung until the deadline. Only that case may resolve
            // through the row (see tvResolveMountedSubtitleTrack).
            exactTrackId != null && owner.identity !is SubtitleIdentity.ServerSidecar -> null
            else -> tvResolveMountedSubtitleTrack(
                identity = owner.identity,
                subtitleRows = subtitleRows,
                mounted = mounted,
            )?.index
        }
        SubDiag.log("REMOUNT consume id=${owner.identity} exact=$exactTrackId mounted=${mounted.map { it.trackId }} settled=$settled match=$matchIndex")
        if (matchIndex != null) {
            clear()
            resolvedOwner = owner
            return TvSubtitleRemountEvent.Select(owner, matchIndex)
        }

        val meaningfulKey = snapshotKey?.takeIf { subtitleTracks.isNotEmpty() }
        if (meaningfulKey != null) meaningfulSnapshotKeys += meaningfulKey
        // An empty track list is not evidence that the wanted track is missing:
        // a replanned stream publishes its tracks a moment after the player
        // reports READY. Concluding Missing here clears the pending owner, so
        // the real tracks arrive with nobody left to match them.
        if (subtitleTracks.isEmpty()) return null
        if (!settled && meaningfulSnapshotKeys.size < maxMeaningfulSnapshots) return null

        clear()
        return TvSubtitleRemountEvent.Failed(
            owner = owner,
            reason = if (owner.identity.hasAmbiguousTvLabel(subtitleTracks)) {
                TvSubtitleRemountFailure.Ambiguous
            } else {
                TvSubtitleRemountFailure.Missing
            },
        )
    }

    fun clear() {
        pendingOwner = null
        meaningfulSnapshotKeys.clear()
    }
}

private fun SubtitleIdentity.exactTvMountTrackId(): String? = when (this) {
    is SubtitleIdentity.ServerSidecar -> subtitleArtifactTrackId(serverIndex)
    is SubtitleIdentity.Downloaded -> downloadedSubtitleArtifactTrackId(downloadId)
    is SubtitleIdentity.Embedded -> media.trackId?.trim()?.takeIf(String::isNotEmpty)
    is SubtitleIdentity.LocalMedia3 -> media.trackId?.trim()?.takeIf(String::isNotEmpty)
    SubtitleIdentity.Off,
    is SubtitleIdentity.ServerBurnIn,
    -> null
}

internal fun PlayerTrackEntry.toMountedTvSubtitleTrack(): MountedSubtitleTrack =
    MountedSubtitleTrack(
        index = index,
        trackId = trackId,
        label = label,
        language = language,
        codec = codecOrMime,
        forced = isForced,
        hearingImpaired = isHearingImpaired,
    )

private fun SubtitleIdentity.hasAmbiguousTvLabel(tracks: List<PlayerTrackEntry>): Boolean {
    val label = when (this) {
        is SubtitleIdentity.ServerSidecar -> media?.label
        is SubtitleIdentity.ServerBurnIn -> media?.label
        is SubtitleIdentity.Embedded -> media.label
        is SubtitleIdentity.Downloaded -> media.label
        is SubtitleIdentity.LocalMedia3 -> media.label
        SubtitleIdentity.Off -> null
    }?.trim()?.lowercase() ?: return false
    return tracks.count {
        it.label.trim().lowercase() == label ||
            it.displayLabel.trim().lowercase() == label
    } > 1
}
