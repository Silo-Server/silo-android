package org.siloserver.silo.playback

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Which of a title's two remembered tracks a [TrackPreferenceRecord] holds. */
enum class TrackPreferenceKind(val path: String) { AUDIO("audio-prefs"), SUBTITLE("subtitle-prefs") }

/**
 * What this device knows about one remembered track, kept per server and
 * profile so an offline download can be seeded from it and a pick made
 * offline reaches the server later.
 *
 * The server's row and the viewer's pending pick are kept apart: a refresh
 * from the server never overwrites a pick that has not been sent, and a pick
 * never pretends to be the server's row.
 */
data class TrackPreferenceRecord(
    val serverId: String,
    val profileId: String,
    val kind: TrackPreferenceKind,
    val key: String,
    /** The server's row as last read (its JSON body), or null when it had none. */
    val serverRow: String? = null,
    /** The row's `updated_at`; null when the server had no row. */
    val serverUpdatedAt: String? = null,
    /** Whether [serverRow] and [serverUpdatedAt] were read from the server rather than assumed. */
    val serverObserved: Boolean = false,
    /** The viewer's pick still to be sent (the update body), or null when nothing is pending. */
    val pending: String? = null,
    /** Device time of the pending pick. */
    val pendingPickedAtMs: Long = 0L,
    /** The server row's `updated_at` this device had seen when the pick was made. */
    val pendingBaselineUpdatedAt: String? = null,
    /** False when the pick was made without having read the server's row. */
    val pendingBaselineKnown: Boolean = false,
    /** Bumped by every pick, so an answer for an older pick never clears a newer one. */
    val pendingGeneration: Long = 0L,
)

/** Durable storage for [TrackPreferenceRecord]s; the apps bind a Room table. */
interface TrackPreferenceStore {
    suspend fun get(serverId: String, profileId: String, kind: TrackPreferenceKind, key: String): TrackPreferenceRecord?
    suspend fun put(record: TrackPreferenceRecord)
    suspend fun pending(serverId: String, profileId: String): List<TrackPreferenceRecord>
}

/** For targets without a database: remembered for the process only. */
class InMemoryTrackPreferenceStore : TrackPreferenceStore {
    private val lock = Mutex()
    private val records = mutableMapOf<List<String>, TrackPreferenceRecord>()

    override suspend fun get(serverId: String, profileId: String, kind: TrackPreferenceKind, key: String) =
        lock.withLock { records[listOf(serverId, profileId, kind.name, key)] }

    override suspend fun put(record: TrackPreferenceRecord) = lock.withLock {
        records[listOf(record.serverId, record.profileId, record.kind.name, record.key)] = record
    }

    override suspend fun pending(serverId: String, profileId: String) = lock.withLock {
        records.values.filter { it.serverId == serverId && it.profileId == profileId && it.pending != null }
    }
}
