package org.siloserver.silo.common.data.repository

import androidx.room.withTransaction
import org.siloserver.silo.common.data.db.SiloDatabase
import org.siloserver.silo.common.data.db.entity.ContentItemStateEntity
import org.siloserver.silo.common.data.db.entity.DirtyOperationEntity
import org.siloserver.silo.common.data.db.entity.UserItemStateEntity
import org.siloserver.silo.common.data.sync.OutboxOperation
import org.siloserver.silo.common.data.sync.OutboxSyncScheduler
import org.siloserver.silo.common.data.sync.revertForTerminalOp
import org.siloserver.silo.common.data.sync.restorePlaybackProgressForTerminalOp
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.repository.port.EbookLocalProgress
import org.siloserver.silo.repository.port.LocalContentState
import org.siloserver.silo.repository.port.LocalPlaybackProgress
import org.siloserver.silo.repository.port.LocalTrackSelection
import org.siloserver.silo.repository.port.OutboxHandle
import org.siloserver.silo.repository.port.PlaybackWriteScope
import org.siloserver.silo.repository.port.TrackSelectionFingerprintUpdate
import org.siloserver.silo.repository.port.UserItemStatePort
import org.siloserver.silo.repository.port.WriteOutcome
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.booleanOrNull
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.repository.port.PersonalWrite
import org.siloserver.silo.repository.port.PersonalWriteHandle
import java.util.UUID

/**
 * Room-backed [UserItemStatePort] (Track B). Each content-level mutation
 * writes an optimistic [ContentItemStateEntity] projection **and** a pending
 * [DirtyOperationEntity] outbox op in one transaction, then returns a handle so
 * [org.siloserver.silo.repository.PersonalDataRepository] can [resolve] the op
 * with the inline network outcome.
 *
 * Scope is captured as ONE atomic [AuthScopeSnapshot] per call (not separate
 * server/profile reads, which could straddle a switch). The snapshot's
 * `(serverId, profileId)` scopes the projection + outbox rows, and the handle
 * carries the snapshot so the repository can PIN the inline network call to the
 * exact scope the op was recorded for — a switch between record and send then
 * can't route the write to the wrong account. No active scope (or no profile) →
 * records nothing and returns [OutboxHandle.NONE]; the inline call still runs
 * (unpinned, current scope) so foreground behaviour is unchanged.
 *
 * Coalesce keys are server-scoped (`serverId|profileId|contentId|kind`) so a
 * newer pending op of the same kind+target replaces an older un-synced one
 * without crossing server/profile boundaries.
 */
class RoomUserItemStateRepository(
    private val db: SiloDatabase,
    private val snapshotProvider: suspend () -> AuthScopeSnapshot?,
    private val syncScheduler: OutboxSyncScheduler = OutboxSyncScheduler.NONE,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val ebookAuthorities: org.siloserver.silo.network.DurableLoginAuthorityProvider? = null,
    private val identityTransitions: org.siloserver.silo.network.IdentityTransitionBarrier? = null,
) : UserItemStatePort {

    private val contentDao = db.contentItemStateDao()
    private val userStateDao = db.userItemStateDao()
    private val outboxDao = db.dirtyOperationDao()

    override suspend fun beginPersonalWrite(command: PersonalWrite): PersonalWriteHandle? {
        if (!command.valid()) return null
        val authority = ebookAuthorities?.snapshotDurableLoginAuthority() ?: return null
        val snapshot = authority.scope
        val profile = snapshot.profileId ?: return null
        if (snapshot.credentialGenerationId != null || !snapshot.isSameIdentityAs(snapshotProvider())) return null
        return db.withTransaction {
            if (authority != ebookAuthorities.snapshotDurableLoginAuthority()) return@withTransaction null
            // A v2 row left by a crashed or failed attempt is superseded by this newer desired state.
            outboxDao.deletePersonalV2ForItem(snapshot.serverId, profile, command.itemId)
            // Legacy bytes have no safe v2 replay authority; never coalesce, convert or supersede them.
            if (outboxDao.unresolvedPersonalCount(snapshot.serverId, profile, command.itemId) != 0)
                return@withTransaction null
            val nowMs = now()
            val existing = contentDao.get(snapshot.serverId, profile, command.itemId)
                ?: ContentItemStateEntity(snapshot.serverId, profile, command.itemId, null, null, null, nowMs, null)
            val payload = buildJsonObject {
                put("login_id", authority.loginId)
                put("origin", snapshot.serverUrl)
                put("method", command.method)
                put("path", command.path)
                command.body?.let { put("body", it) }
                put("command", SiloJson.encodeToJsonElement(PersonalWrite.serializer(), command))
                put("projection_updated_at_ms", existing.clientUpdatedAtMs)
                put("projection_existed", contentDao.get(snapshot.serverId, profile, command.itemId) != null)
                put("previous_watched", existing.watched?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
                put("previous_rating", existing.ratingValue?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
            }.toString()
            val id = outboxDao.insert(DirtyOperationEntity(
                opKind = "PERSONAL_V2", serverId = snapshot.serverId, profileId = profile,
                targetContentId = command.itemId, targetFileId = null,
                coalesceKey = "personal-v2|${snapshot.serverId}|$profile|${command.itemId}",
                idempotencyKey = idGenerator(), opVersion = 2, payloadJson = payload,
                state = "personal_uncertain", createdAtMs = nowMs,
            ))
            PersonalWriteHandle(id, snapshot, command)
        }
    }

    override suspend fun completePersonalWrite(handle: PersonalWriteHandle) {
        db.withTransaction {
            val row = outboxDao.getById(handle.opId) ?: return@withTransaction
            if (row.opKind != "PERSONAL_V2" || row.state != "personal_uncertain" ||
                row.serverId != handle.scope.serverId || row.profileId != handle.scope.profileId ||
                row.targetContentId != handle.command.itemId) return@withTransaction
            val saved = SiloJson.parseToJsonElement(row.payloadJson) as kotlinx.serialization.json.JsonObject
            if (saved["command"] != SiloJson.encodeToJsonElement(PersonalWrite.serializer(), handle.command)) return@withTransaction
            // Publish only an acknowledged write, and only over the local state observed at admission.
            // Unknown/rejected writes never persist an optimistic success after the UI rolls back.
            val current = contentDao.get(row.serverId, row.profileId, row.targetContentId)
            val existed = saved["projection_existed"]?.jsonPrimitive?.booleanOrNull == true
            val unchanged = if (!existed) current == null else current != null &&
                current.clientUpdatedAtMs == saved.getValue("projection_updated_at_ms").jsonPrimitive.long &&
                current.watched == saved["previous_watched"]?.jsonPrimitive?.booleanOrNull &&
                current.ratingValue == saved["previous_rating"]?.jsonPrimitive?.intOrNull
            if (unchanged) {
                val previous = current ?: ContentItemStateEntity(row.serverId, row.profileId, row.targetContentId, null, null, null, now(), null)
                val projected = when (val command = handle.command) {
                    is PersonalWrite.Watched -> previous.copy(watched = command.watched)
                    is PersonalWrite.Rating -> previous.copy(ratingValue = command.rating)
                }
                contentDao.upsert(projected.copy(clientUpdatedAtMs = now()))
                if (handle.command is PersonalWrite.Watched)
                    userStateDao.clearPlaybackProgressBefore(row.serverId, row.profileId, row.targetContentId, row.createdAtMs, now())
            }
            outboxDao.deleteById(row.id)
        }
    }

    override suspend fun abandonPersonalWrite(handle: PersonalWriteHandle) {
        val row = outboxDao.getById(handle.opId) ?: return
        if (row.opKind == "PERSONAL_V2" && row.state == "personal_uncertain") outboxDao.deleteById(row.id)
    }

    override suspend fun recordWatched(contentId: String, watched: Boolean): OutboxHandle =
        record(
            contentId,
            OutboxOperation.SET_WATCHED,
            JsonPrimitive(watched).toString(),
            clearPlaybackProgress = true,
        ) {
            it.copy(watched = watched)
        }

    override suspend fun recordFavorite(contentId: String, favorite: Boolean): OutboxHandle =
        error("Favorite writes require the durable MembershipPort")

    override suspend fun recordRating(contentId: String, rating: Int?): OutboxHandle =
        record(
            contentId,
            OutboxOperation.SET_RATING,
            if (rating == null) "null" else JsonPrimitive(rating).toString(),
        ) {
            it.copy(ratingValue = rating)
        }

    override suspend fun recordPosition(
        contentId: String,
        fileId: Int,
        positionSeconds: Double,
        durationSeconds: Double?,
    ) {
        val snapshot = snapshotProvider() ?: return
        val serverId = snapshot.serverId
        val profileId = snapshot.profileId ?: return
        recordPositionOwned(
            serverId = serverId,
            profileId = profileId,
            contentId = contentId,
            fileId = fileId,
            positionSeconds = positionSeconds,
            durationSeconds = durationSeconds,
        )
    }

    override suspend fun recordPosition(
        scope: PlaybackWriteScope,
        contentId: String,
        fileId: Int,
        positionSeconds: Double,
        durationSeconds: Double?,
    ): Boolean {
        val current = snapshotProvider() ?: return false
        if (current.serverId != scope.serverId ||
            current.profileId != scope.profileId ||
            current.credentialGenerationId != scope.credentialGenerationId ||
            current.identityGeneration != scope.identityGeneration
        ) return false

        return recordPositionOwned(
            serverId = scope.serverId,
            profileId = scope.profileId,
            contentId = contentId,
            fileId = fileId,
            positionSeconds = positionSeconds,
            durationSeconds = durationSeconds,
        )
    }

    private suspend fun recordPositionOwned(
        serverId: String,
        profileId: String,
        contentId: String,
        fileId: Int,
        positionSeconds: Double,
        durationSeconds: Double?,
    ): Boolean {
        // Reject values that would enqueue invalid JSON and poison the drain
        // (a NaN/Infinity/negative would parse-fail after claim and retry forever).
        // Reject 0 too: a resume row is only readable when positionSeconds > 0, so
        // writing 0 can't help resume — it can only HARM it. On player teardown the
        // final recordPosition snapshots _uiState.position, which is 0 in the race
        // where the player was already reset/released; without this guard that stale
        // 0 overwrites a good resume locally AND syncs SET_POSITION=0 to the server,
        // so re-entering the detail shows "Play" instead of "Resume" (Jim TV/phone
        // QA 2026-07-10). Legit resets go through markUnwatched, not this path.
        if (contentId.isBlank() || !positionSeconds.isFinite() || positionSeconds <= 0.0) return false
        val safeDuration = durationSeconds?.takeIf { it.isFinite() && it > 0.0 }
        val nowMs = now()

        db.withTransaction {
            // File-level local projection for resume (preserve track/cfi fields).
            val existing = userStateDao.get(serverId, profileId, contentId, fileId)
            val row = existing?.copy(
                positionSeconds = positionSeconds,
                durationSeconds = safeDuration,
                clientUpdatedAtMs = nowMs,
            ) ?: UserItemStateEntity(
                serverId = serverId,
                profileId = profileId,
                contentId = contentId,
                fileId = fileId,
                positionSeconds = positionSeconds,
                durationSeconds = safeDuration,
                audioFingerprint = null,
                subtitleFingerprint = null,
                cfi = null,
                readProgress = null,
                clientUpdatedAtMs = nowMs,
                serverUpdatedAtMs = null,
            )
            userStateDao.upsert(row)

            // V2 playback sends sequenced progress under its admitted session. Keep local resume,
            // but never coalesce a new position into a legacy queue with unknown authority.
            if (ebookAuthorities != null) return@withTransaction

            // Content-level outbox op: syncProgress is keyed by content id, so the
            // coalesce key omits fileId — all pending positions for the item
            // collapse to the latest.
            outboxDao.enqueueCoalescingRestoringItemOrder(
                DirtyOperationEntity(
                    opKind = OutboxOperation.SET_POSITION,
                    serverId = serverId,
                    profileId = profileId,
                    targetContentId = contentId,
                    targetFileId = fileId,
                    coalesceKey = "$serverId|$profileId|$contentId|${OutboxOperation.SET_POSITION}",
                    idempotencyKey = idGenerator(),
                    payloadJson = OutboxOperation.encodePositionPayload(positionSeconds, safeDuration),
                    createdAtMs = nowMs,
                    nextAttemptAtMs = nowMs,
                ),
                nowMs = nowMs,
            )
        }
        return true
    }

    override suspend fun localPosition(contentId: String, fileId: Int): Double? {
        val snapshot = snapshotProvider() ?: return null
        val profileId = snapshot.profileId ?: return null
        return userStateDao.get(snapshot.serverId, profileId, contentId, fileId)
            ?.positionSeconds
            ?.takeIf { it > 0.0 }
    }

    override suspend fun localPositionForContent(contentId: String): Double? {
        return localPlaybackProgress(contentId)?.positionSeconds
    }

    override suspend fun localPlaybackProgress(contentId: String): LocalPlaybackProgress? {
        val snapshot = snapshotProvider() ?: return null
        val profileId = snapshot.profileId ?: return null
        return userStateDao.getByContent(snapshot.serverId, profileId, contentId)
            .latestProgress()
    }

    override suspend fun localPlaybackProgressForContent(contentIds: List<String>): Map<String, LocalPlaybackProgress> {
        if (contentIds.isEmpty()) return emptyMap()
        val snapshot = snapshotProvider() ?: return emptyMap()
        val profileId = snapshot.profileId ?: return emptyMap()
        return userStateDao.progressForContentIds(snapshot.serverId, profileId, contentIds.distinct())
            .groupBy { it.contentId }
            .mapValues { (_, rows) -> rows.latestProgress() }
            .filterValues { it != null }
            .mapValues { (_, value) -> value!! }
    }

    override suspend fun recordAudioTrackSelection(
        contentId: String,
        fileId: Int,
        audioFingerprint: String?,
    ) {
        recordSingleTrackSelection(
            contentId = contentId,
            fileId = fileId,
            update = { it.copy(audioFingerprint = audioFingerprint?.trim()?.takeIf { value -> value.isNotBlank() }) },
        )
    }

    override suspend fun recordSubtitleTrackSelection(
        contentId: String,
        fileId: Int,
        subtitleFingerprint: String?,
    ) {
        recordSingleTrackSelection(
            contentId = contentId,
            fileId = fileId,
            update = { it.copy(subtitleFingerprint = subtitleFingerprint?.trim()?.takeIf { value -> value.isNotBlank() }) },
        )
    }

    override suspend fun recordTrackSelection(
        contentId: String,
        fileId: Int,
        audioUpdate: TrackSelectionFingerprintUpdate,
        subtitleUpdate: TrackSelectionFingerprintUpdate,
    ) {
        if (contentId.isBlank()) return
        val snapshot = snapshotProvider() ?: return
        val serverId = snapshot.serverId
        val profileId = snapshot.profileId ?: return
        recordTrackSelectionOwned(
            serverId = serverId,
            profileId = profileId,
            contentId = contentId,
            fileId = fileId,
            audioUpdate = audioUpdate,
            subtitleUpdate = subtitleUpdate,
        )
    }

    override suspend fun recordTrackSelection(
        scope: PlaybackWriteScope,
        contentId: String,
        fileId: Int,
        audioUpdate: TrackSelectionFingerprintUpdate,
        subtitleUpdate: TrackSelectionFingerprintUpdate,
    ): Boolean {
        val current = snapshotProvider() ?: return false
        if (current.serverId != scope.serverId ||
            current.profileId != scope.profileId ||
            current.credentialGenerationId != scope.credentialGenerationId ||
            current.identityGeneration != scope.identityGeneration
        ) return false

        return recordTrackSelectionOwned(
            serverId = scope.serverId,
            profileId = scope.profileId,
            contentId = contentId,
            fileId = fileId,
            audioUpdate = audioUpdate,
            subtitleUpdate = subtitleUpdate,
        )
    }

    private suspend fun recordTrackSelectionOwned(
        serverId: String,
        profileId: String,
        contentId: String,
        fileId: Int,
        audioUpdate: TrackSelectionFingerprintUpdate,
        subtitleUpdate: TrackSelectionFingerprintUpdate,
    ): Boolean {
        if (contentId.isBlank()) return false
        val nowMs = now()

        db.withTransaction {
            val existing = userStateDao.get(serverId, profileId, contentId, fileId)
            val row = existing ?: UserItemStateEntity(
                serverId = serverId,
                profileId = profileId,
                contentId = contentId,
                fileId = fileId,
                positionSeconds = 0.0,
                durationSeconds = null,
                audioFingerprint = null,
                subtitleFingerprint = null,
                cfi = null,
                readProgress = null,
                clientUpdatedAtMs = nowMs,
                serverUpdatedAtMs = null,
            )
            userStateDao.upsert(
                row.copy(
                    audioFingerprint = audioUpdate.applyTo(row.audioFingerprint),
                    subtitleFingerprint = subtitleUpdate.applyTo(row.subtitleFingerprint),
                    clientUpdatedAtMs = nowMs,
                ),
            )
        }
        return true
    }

    override suspend fun localTrackSelection(contentId: String, fileId: Int): LocalTrackSelection? {
        val snapshot = snapshotProvider() ?: return null
        val profileId = snapshot.profileId ?: return null
        val row = userStateDao.get(snapshot.serverId, profileId, contentId, fileId) ?: return null
        if (row.audioFingerprint == null && row.subtitleFingerprint == null) return null
        return LocalTrackSelection(
            audioFingerprint = row.audioFingerprint,
            subtitleFingerprint = row.subtitleFingerprint,
        )
    }

    override suspend fun recordEbookProgress(contentId: String, fileId: Int, location: String, progress: Double) {
        val snapshot = snapshotProvider() ?: return
        recordEbookEvent(snapshot, contentId, fileId, location, progress, now(), null)
    }

    override suspend fun recordEbookProgress(authority: org.siloserver.silo.network.DurableLoginAuthority,
        contentId: String, fileId: Int, location: String, progress: Double, eventTimeMs: Long) {
        if (authority != ebookAuthorities?.snapshotDurableLoginAuthority()) return
        identityTransitions?.withCurrentGeneration(authority.scope.identityGeneration) {
            recordEbookEvent(authority.scope, contentId, fileId, location, progress, eventTimeMs, authority.loginId)
        }
    }

    private suspend fun recordEbookEvent(snapshot: AuthScopeSnapshot, contentId: String, fileId: Int,
        location: String, progress: Double, nowMs: Long, loginId: String?) {
        if (contentId.isBlank() || location.isBlank() || !progress.isFinite()) return
        val clamped = progress.coerceIn(0.0, 1.0)
        val serverId = snapshot.serverId
        val profileId = snapshot.profileId ?: return
        val key = "$serverId|$profileId|$contentId|${OutboxOperation.SET_EBOOK_PROGRESS}" +
            (loginId?.let { "|$it" } ?: "")
        db.withTransaction {
            val pending = outboxDao.getLatestByCoalesceKey(key)
            if (loginId != null && pending != null && pending.createdAtMs >= nowMs) return@withTransaction
            val existing = userStateDao.get(serverId, profileId, contentId, fileId)
            if (loginId != null && existing != null && existing.clientUpdatedAtMs >= nowMs) return@withTransaction
            val row = existing?.copy(cfi = location, readProgress = clamped, clientUpdatedAtMs = nowMs)
                ?: UserItemStateEntity(
                    serverId = serverId,
                    profileId = profileId,
                    contentId = contentId,
                    fileId = fileId,
                    positionSeconds = 0.0,
                    durationSeconds = null,
                    audioFingerprint = null,
                    subtitleFingerprint = null,
                    cfi = location,
                    readProgress = clamped,
                    clientUpdatedAtMs = nowMs,
                    serverUpdatedAtMs = null,
                )
            userStateDao.upsert(row)

            outboxDao.enqueueCoalescing(
                DirtyOperationEntity(
                    opKind = OutboxOperation.SET_EBOOK_PROGRESS,
                    serverId = serverId,
                    profileId = profileId,
                    targetContentId = contentId,
                    targetFileId = fileId,
                    coalesceKey = key,
                    idempotencyKey = idGenerator(),
                    opVersion = if (loginId == null) 1 else 2,
                    payloadJson = OutboxOperation.encodeEbookProgressPayload(fileId, location, clamped,
                        if (loginId == null) null else java.time.Instant.ofEpochMilli(nowMs).toString(), loginId, snapshot.serverUrl),
                    createdAtMs = nowMs,
                    nextAttemptAtMs = nowMs,
                ),
            )
        }
    }

    override suspend fun localEbookProgress(contentId: String, fileId: Int): EbookLocalProgress? {
        val snapshot = snapshotProvider() ?: return null
        val profileId = snapshot.profileId ?: return null
        val row = userStateDao.get(snapshot.serverId, profileId, contentId, fileId) ?: return null
        val location = row.cfi ?: return null
        return EbookLocalProgress(location = location, progress = row.readProgress ?: 0.0)
    }

    override suspend fun resolve(handle: OutboxHandle, outcome: WriteOutcome) {
        if (handle.opId < 0) return
        when (outcome) {
            // Acked: normally drop the op. A watched mutation recorded while an
            // older position was already in flight stays queued for one final,
            // idempotent replay so that position cannot land last and resurrect
            // Continue Watching.
            WriteOutcome.SYNCED -> {
                val op = outboxDao.getById(handle.opId)
                val requiresReplay = op?.takeIf { it.opKind == OutboxOperation.SET_WATCHED }
                    ?.let { runCatching { OutboxOperation.decodeWatchedPayload(it.payloadJson).requiresReplay }.getOrDefault(false) }
                    ?: false
                if (requiresReplay) syncScheduler.requestSync()
                else outboxDao.deleteById(handle.opId)
            }
            // Rejected for good: drop the op AND revert the optimistic projection
            // field so the card overlay defers to server state (no fake local state
            // across cold starts).
            WriteOutcome.TERMINAL -> db.withTransaction {
                outboxDao.getById(handle.opId)?.let {
                    if (outboxDao.countNewerPending(it.coalesceKey, it.id) == 0) {
                        contentDao.revertForTerminalOp(it)
                        userStateDao.restorePlaybackProgressForTerminalOp(it)
                    }
                }
                outboxDao.deleteById(handle.opId)
            }
            // Transient: leave the pending op and ask the sync engine to drain it.
            // Triggered here (not in record()) so it can't race the inline call.
            WriteOutcome.RETRIABLE -> syncScheduler.requestSync()
        }
    }

    override suspend fun localContentStates(contentIds: List<String>): Map<String, LocalContentState> {
        if (contentIds.isEmpty()) return emptyMap()
        val snapshot = snapshotProvider() ?: return emptyMap()
        val profileId = snapshot.profileId ?: return emptyMap()
        return contentDao.getForContentIds(snapshot.serverId, profileId, contentIds.distinct())
            .associate { it.contentId to LocalContentState(watched = it.watched, favorite = null) }
    }

    private suspend fun record(
        contentId: String,
        opKind: String,
        payloadJson: String,
        clearPlaybackProgress: Boolean = false,
        applyField: (ContentItemStateEntity) -> ContentItemStateEntity,
    ): OutboxHandle {
        val snapshot = snapshotProvider() ?: return OutboxHandle.NONE
        val serverId = snapshot.serverId
        val profileId = snapshot.profileId ?: return OutboxHandle.NONE
        val nowMs = now()

        var opId = OutboxHandle.NONE.opId
        db.withTransaction {
            // Read-modify-write so a single-field mutation (e.g. favorite) does
            // not clobber another field (e.g. an existing rating) on the row.
            val existing = contentDao.get(serverId, profileId, contentId)
                ?: ContentItemStateEntity(
                    serverId = serverId,
                    profileId = profileId,
                    contentId = contentId,
                    watched = null,
                    ratingValue = null,
                    favorite = null,
                    clientUpdatedAtMs = nowMs,
                    serverUpdatedAtMs = null,
                )
            contentDao.upsert(applyField(existing).copy(clientUpdatedAtMs = nowMs))

            var operationPayload = payloadJson
            if (clearPlaybackProgress) {
                val watchedCoalesceKey = "$serverId|$profileId|$contentId|${OutboxOperation.SET_WATCHED}"
                // An older watched mutation may already be in flight. Preserve
                // its rollback data too: if it fails after this newer intent is
                // queued, the older op deliberately defers rollback to us.
                val inheritedPayload = outboxDao.getLatestByCoalesceKey(watchedCoalesceKey)
                    ?.let { runCatching { OutboxOperation.decodeWatchedPayload(it.payloadJson) }.getOrNull() }
                val clearedByFile = inheritedPayload?.clearedProgress
                    .orEmpty()
                    .associateByTo(linkedMapOf()) { it.fileId }
                userStateDao.getByContent(serverId, profileId, contentId)
                    .filter { it.positionSeconds > 0.0 }
                    .forEach { row ->
                        clearedByFile[row.fileId] = OutboxOperation.ClearedPlaybackProgress(
                            fileId = row.fileId,
                            positionSeconds = row.positionSeconds,
                            previousClientUpdatedAtMs = row.clientUpdatedAtMs,
                            clearedAtMs = nowMs,
                        )
                    }
                val hasInFlightPosition = outboxDao.countInFlightForTargetKind(
                    serverId = serverId,
                    profileId = profileId,
                    contentId = contentId,
                    opKind = OutboxOperation.SET_POSITION,
                ) > 0
                outboxDao.deletePendingForTargetKind(
                    serverId = serverId,
                    profileId = profileId,
                    contentId = contentId,
                    opKind = OutboxOperation.SET_POSITION,
                )
                userStateDao.clearPlaybackProgress(serverId, profileId, contentId, nowMs)
                operationPayload = OutboxOperation.encodeWatchedPayload(
                    OutboxOperation.WatchedPayload(
                        watched = OutboxOperation.decodeBooleanPayload(payloadJson),
                        clearedProgress = clearedByFile.values.toList(),
                        requiresReplay = inheritedPayload?.requiresReplay == true || hasInFlightPosition,
                    ),
                )
            }

            opId = outboxDao.enqueueCoalescing(
                DirtyOperationEntity(
                    opKind = opKind,
                    serverId = serverId,
                    profileId = profileId,
                    targetContentId = contentId,
                    targetFileId = null,
                    coalesceKey = "$serverId|$profileId|$contentId|$opKind",
                    idempotencyKey = idGenerator(),
                    payloadJson = operationPayload,
                    createdAtMs = nowMs,
                    nextAttemptAtMs = nowMs,
                ),
            )
        }
        return OutboxHandle(opId, snapshot)
    }

    private suspend fun recordSingleTrackSelection(
        contentId: String,
        fileId: Int,
        update: (UserItemStateEntity) -> UserItemStateEntity,
    ) {
        if (contentId.isBlank()) return
        val snapshot = snapshotProvider() ?: return
        val serverId = snapshot.serverId
        val profileId = snapshot.profileId ?: return
        val nowMs = now()

        db.withTransaction {
            val existing = userStateDao.get(serverId, profileId, contentId, fileId)
            val row = existing ?: UserItemStateEntity(
                serverId = serverId,
                profileId = profileId,
                contentId = contentId,
                fileId = fileId,
                positionSeconds = 0.0,
                durationSeconds = null,
                audioFingerprint = null,
                subtitleFingerprint = null,
                cfi = null,
                readProgress = null,
                clientUpdatedAtMs = nowMs,
                serverUpdatedAtMs = null,
            )
            userStateDao.upsert(update(row).copy(clientUpdatedAtMs = nowMs))
        }
    }
}

private fun TrackSelectionFingerprintUpdate.applyTo(current: String?): String? =
    when (this) {
        TrackSelectionFingerprintUpdate.Preserve -> current
        TrackSelectionFingerprintUpdate.Clear -> null
        is TrackSelectionFingerprintUpdate.Set -> fingerprint.trim()
    }

// Newest local write wins — NOT the furthest position. Picking the max
// position made a deliberate backward seek (or an old row for a different
// file version) permanently shadow the real resume point.
internal fun List<UserItemStateEntity>.latestProgress(): LocalPlaybackProgress? =
    filter { it.positionSeconds.isFinite() && it.positionSeconds > 0.0 }
        .maxWithOrNull(
            compareBy<UserItemStateEntity> { it.clientUpdatedAtMs }
                .thenBy { it.positionSeconds }
                // Deterministic final tiebreak so equal-stamped rows across
                // file versions can't flip-flop between reads.
                .thenBy { it.fileId },
        )
        ?.let {
            LocalPlaybackProgress(
                fileId = it.fileId,
                positionSeconds = it.positionSeconds,
                durationSeconds = it.durationSeconds,
            )
        }
