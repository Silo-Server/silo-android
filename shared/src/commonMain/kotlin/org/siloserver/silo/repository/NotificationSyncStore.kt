package org.siloserver.silo.repository

/** Checkpoints are keyed by server origin, saved login incarnation and profile. No credentials. */
interface NotificationSyncStore {
    suspend fun read(key: String): String?
    suspend fun write(key: String, cursor: String)
}
