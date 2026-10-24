package org.siloserver.silo.common.data.db.entity

import androidx.room.Entity

/**
 * One remembered track of a series (or movie) for a server and profile: the
 * server's row as last read and the viewer's pick still to be sent. See
 * [org.siloserver.silo.playback.TrackPreferenceRecord].
 */
@Entity(tableName = "track_preferences", primaryKeys = ["serverId", "profileId", "kind", "prefKey"])
data class TrackPreferenceEntity(
    val serverId: String,
    val profileId: String,
    val kind: String,
    val prefKey: String,
    val serverRow: String?,
    val serverUpdatedAt: String?,
    val serverObserved: Boolean,
    val pending: String?,
    val pendingPickedAtMs: Long,
    val pendingBaselineUpdatedAt: String?,
    val pendingBaselineKnown: Boolean,
    val pendingGeneration: Long,
)
