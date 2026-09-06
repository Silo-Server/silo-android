package org.siloserver.silo.repository.port

import org.siloserver.silo.network.AuthScopeSnapshot

data class PlaybackWriteScope(
    val serverId: String,
    val profileId: String,
    val credentialGenerationId: String?,
    val identityGeneration: Long,
)

sealed interface TrackSelectionFingerprintUpdate {
    data object Preserve : TrackSelectionFingerprintUpdate
    data object Clear : TrackSelectionFingerprintUpdate

    data class Set(val fingerprint: String) : TrackSelectionFingerprintUpdate {
        init {
            require(fingerprint.isNotBlank()) { "Track-selection fingerprint must not be blank" }
        }
    }
}

/**
 * Local-first side-channel for **content-level** user-state mutations
 * (watched / favorite / rating). The strangler entry point for Track B:
 * [org.siloserver.silo.repository.PersonalDataRepository] records each mutation
 * through this port (optimistic local projection + a durable outbox op) before
 * calling the server, then [resolve]s the op with the network outcome.
 *
 * The port lives in `commonMain` so the shared repository can depend on it; the
 * real Room-backed implementation is Android-only and bound in the platform DI
 * module. On any target without that binding (commonMain tests), the
 * [NoOpUserItemStatePort] default keeps behaviour exactly network-only.
 *
 * Contract: `record*` persists the projection + a pending outbox op atomically
 * and returns a [OutboxHandle]; the caller then calls [resolve] with the
 * server result so the op can be acked, kept for retry, or dropped.
 */
interface UserItemStatePort {

    /** rating `null` clears the rating. */
    suspend fun recordWatched(contentId: String, watched: Boolean): OutboxHandle
    suspend fun recordFavorite(contentId: String, favorite: Boolean): OutboxHandle
    suspend fun recordRating(contentId: String, rating: Int?): OutboxHandle

    /** Reconcile the enqueued op with the inline network result. */
    suspend fun resolve(handle: OutboxHandle, outcome: WriteOutcome)

    /**
     * Drop locally stored resume positions for the given items after the
     * server confirmed a container-level watched mutation (season or series).
     * [recordWatched] only clears progress for the mutated content id; the
     * children it fans out to must be cleared here, or the local overlay would
     * resurrect Resume on episodes the server now reports as played. No-op on
     * the default port.
     */
    suspend fun clearPlaybackProgress(contentIds: List<String>) {
    }

    /**
     * Durably record a playback position for offline-safe resume + sync. Unlike
     * the mutations above there is no inline network call to [resolve]: the write
     * persists a file-level local projection (for resume) and enqueues a single
     * content-level position op that the sync engine drains (furthest-position-
     * wins on the server). Safe to call on every progress tick — repeated writes
     * for the same item coalesce to the latest. No-op on the default port.
     */
    suspend fun recordPosition(
        contentId: String,
        fileId: Int,
        positionSeconds: Double,
        durationSeconds: Double?,
    ) {
    }

    /**
     * Records a final playback position only while the captured playback
     * identity is still current. Returns true only when the write was accepted.
     */
    suspend fun recordPosition(
        scope: PlaybackWriteScope,
        contentId: String,
        fileId: Int,
        positionSeconds: Double,
        durationSeconds: Double?,
    ): Boolean = false

    /**
     * The locally-recorded resume position for an item, or null if none. Lets the
     * player resume from the last on-device position when offline (no server
     * round-trip). No-op (null) on the default port.
     */
    suspend fun localPosition(contentId: String, fileId: Int): Double? = null

    /**
     * The furthest locally-recorded position across all files of a content item.
     * For content-level resume (e.g. audiobooks) where the playing file id isn't
     * known yet at resume time. No-op (null) on the default port.
     */
    suspend fun localPositionForContent(contentId: String): Double? = null

    /** Full local resume snapshot for a content item, including the file that produced it. */
    suspend fun localPlaybackProgress(contentId: String): LocalPlaybackProgress? = null

    /** Batched local resume snapshots for card/detail overlays. */
    suspend fun localPlaybackProgressForContent(contentIds: List<String>): Map<String, LocalPlaybackProgress> = emptyMap()

    /**
     * Durably record per-file track selections. These are local-only hints for
     * future playback starts, stored as stable fingerprints rather than raw UI
     * ordinals so they survive track-list reordering. They do not enqueue a
     * server outbox op because the server currently models track choices as
     * playback-session inputs, not user state.
     */
    suspend fun recordAudioTrackSelection(contentId: String, fileId: Int, audioFingerprint: String?) {
    }

    suspend fun recordSubtitleTrackSelection(contentId: String, fileId: Int, subtitleFingerprint: String?) {
    }

    suspend fun recordTrackSelection(
        contentId: String,
        fileId: Int,
        audioFingerprint: String?,
        subtitleFingerprint: String?,
    ) {
        recordTrackSelection(
            contentId = contentId,
            fileId = fileId,
            audioUpdate = audioFingerprint.toTrackSelectionFingerprintUpdate(),
            subtitleUpdate = subtitleFingerprint.toTrackSelectionFingerprintUpdate(),
        )
    }

    suspend fun recordTrackSelection(
        contentId: String,
        fileId: Int,
        audioUpdate: TrackSelectionFingerprintUpdate,
        subtitleUpdate: TrackSelectionFingerprintUpdate,
    ) {
        when (audioUpdate) {
            TrackSelectionFingerprintUpdate.Preserve -> Unit
            TrackSelectionFingerprintUpdate.Clear ->
                recordAudioTrackSelection(contentId, fileId, null)
            is TrackSelectionFingerprintUpdate.Set ->
                recordAudioTrackSelection(contentId, fileId, audioUpdate.fingerprint.trim())
        }
        when (subtitleUpdate) {
            TrackSelectionFingerprintUpdate.Preserve -> Unit
            TrackSelectionFingerprintUpdate.Clear ->
                recordSubtitleTrackSelection(contentId, fileId, null)
            is TrackSelectionFingerprintUpdate.Set ->
                recordSubtitleTrackSelection(contentId, fileId, subtitleUpdate.fingerprint.trim())
        }
    }

    /**
     * Records a final track selection only while the auth identity captured for
     * this playback remains current. Returns true when the write was accepted.
     */
    suspend fun recordTrackSelection(
        scope: PlaybackWriteScope,
        contentId: String,
        fileId: Int,
        audioUpdate: TrackSelectionFingerprintUpdate,
        subtitleUpdate: TrackSelectionFingerprintUpdate,
    ): Boolean = false

    suspend fun localTrackSelection(contentId: String, fileId: Int): LocalTrackSelection? = null

    /**
     * Durably record ebook reading position (CFI [location] + [progress] 0..1) for
     * offline-safe resume + sync. Like [recordPosition] there is no inline call:
     * the local file-level projection (cfi/readProgress) is the resume source and a
     * single content-level op drains via the monotonic-guarded ebook sync (the
     * drain only pushes when local progress exceeds the server's, so a stale replay
     * never rewinds reading position). No-op on the default port.
     */
    suspend fun recordEbookProgress(
        contentId: String,
        fileId: Int,
        location: String,
        progress: Double,
    ) {
    }

    /** Locally-recorded ebook resume point for a file, or null. No-op (null) by default. */
    suspend fun localEbookProgress(contentId: String, fileId: Int): EbookLocalProgress? = null

    /**
     * Local optimistic content-level state (watched/favorite) for the given content
     * ids, for overlaying onto displayed cards so a mutation — especially one made
     * offline — reflects immediately instead of the stale server snapshot baked into
     * a cached card. Batched (one query). Empty map by default / unknown ids absent.
     */
    suspend fun localContentStates(contentIds: List<String>): Map<String, LocalContentState> = emptyMap()
}

/** Local optimistic content-level state; null fields = "no local opinion" (defer to server). */
data class LocalContentState(val watched: Boolean?, val favorite: Boolean?)

/** Local per-file track choices; null fields mean "no local override". */
data class LocalTrackSelection(val audioFingerprint: String?, val subtitleFingerprint: String?)

/** Local ebook resume point: CFI [location] + [progress] fraction (0..1). */
data class EbookLocalProgress(val location: String, val progress: Double)

/** Local video/audio resume point that has not necessarily synced to the server yet. */
data class LocalPlaybackProgress(
    val fileId: Int,
    val positionSeconds: Double,
    val durationSeconds: Double?,
)

/**
 * Opaque reference to an enqueued outbox op. [NONE] means nothing was recorded
 * (e.g. no active server/profile scope, or the no-op port) — [resolve] ignores it.
 *
 * [scope] is the auth snapshot captured atomically when the op was recorded.
 * The repository pins the inline network call to it so a server/profile switch
 * between recording and sending can't route the write to the wrong account (and
 * then false-ack/delete the op). Null for the no-op port (single-scope) →
 * unpinned, preserving the original behaviour.
 */
data class OutboxHandle(val opId: Long, val scope: AuthScopeSnapshot? = null) {
    companion object {
        val NONE = OutboxHandle(-1L)
    }
}

/**
 * What the inline network call told us about a recorded op:
 * - [SYNCED] — server accepted it; delete the op (Task 5 must not replay it).
 * - [RETRIABLE] — transient (no network / 5xx / 408 / 429); keep it pending.
 * - [TERMINAL] — server rejected it (4xx); drop the op so a doomed write is not replayed.
 */
enum class WriteOutcome { SYNCED, RETRIABLE, TERMINAL }

private fun String?.toTrackSelectionFingerprintUpdate(): TrackSelectionFingerprintUpdate =
    this
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let(TrackSelectionFingerprintUpdate::Set)
        ?: TrackSelectionFingerprintUpdate.Clear

/** Network-only behaviour: records nothing, resolves to nothing. */
object NoOpUserItemStatePort : UserItemStatePort {
    override suspend fun recordWatched(contentId: String, watched: Boolean) = OutboxHandle.NONE
    override suspend fun recordFavorite(contentId: String, favorite: Boolean) = OutboxHandle.NONE
    override suspend fun recordRating(contentId: String, rating: Int?) = OutboxHandle.NONE
    override suspend fun resolve(handle: OutboxHandle, outcome: WriteOutcome) {}
}
