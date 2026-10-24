package org.siloserver.silo.common.data.repository

import org.siloserver.silo.common.data.db.SiloDatabase
import org.siloserver.silo.common.data.db.entity.TrackPreferenceEntity
import org.siloserver.silo.playback.TrackPreferenceKind
import org.siloserver.silo.playback.TrackPreferenceRecord
import org.siloserver.silo.playback.TrackPreferenceStore

/** The durable [TrackPreferenceStore]: picks made offline survive the app being closed. */
class RoomTrackPreferenceStore(db: SiloDatabase) : TrackPreferenceStore {
    private val dao = db.trackPreferenceDao()

    override suspend fun get(serverId: String, profileId: String, kind: TrackPreferenceKind, key: String) =
        dao.get(serverId, profileId, kind.name, key)?.toRecord()

    override suspend fun put(record: TrackPreferenceRecord) = dao.upsert(record.toEntity())

    override suspend fun pending(serverId: String, profileId: String) =
        dao.pending(serverId, profileId).mapNotNull { it.toRecord() }
}

private fun TrackPreferenceEntity.toRecord(): TrackPreferenceRecord? {
    val kind = TrackPreferenceKind.entries.firstOrNull { it.name == kind } ?: return null
    return TrackPreferenceRecord(
        serverId = serverId,
        profileId = profileId,
        kind = kind,
        key = prefKey,
        serverRow = serverRow,
        serverUpdatedAt = serverUpdatedAt,
        serverObserved = serverObserved,
        pending = pending,
        pendingPickedAtMs = pendingPickedAtMs,
        pendingBaselineUpdatedAt = pendingBaselineUpdatedAt,
        pendingBaselineKnown = pendingBaselineKnown,
        pendingGeneration = pendingGeneration,
    )
}

private fun TrackPreferenceRecord.toEntity() = TrackPreferenceEntity(
    serverId = serverId,
    profileId = profileId,
    kind = kind.name,
    prefKey = key,
    serverRow = serverRow,
    serverUpdatedAt = serverUpdatedAt,
    serverObserved = serverObserved,
    pending = pending,
    pendingPickedAtMs = pendingPickedAtMs,
    pendingBaselineUpdatedAt = pendingBaselineUpdatedAt,
    pendingBaselineKnown = pendingBaselineKnown,
    pendingGeneration = pendingGeneration,
)
