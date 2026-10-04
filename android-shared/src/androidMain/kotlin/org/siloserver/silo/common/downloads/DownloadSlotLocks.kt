package org.siloserver.silo.common.downloads

import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

/**
 * One lock per download slot (server, profile, file), shared by the whole
 * process. The track capture, the subtitle refresh, and deleting a download
 * each check the slot's metadata row and then change its subtitle files and
 * row in separate steps. Holding the slot's lock across those steps keeps a
 * refresh from publishing over a capture in progress or writing a deleted
 * download's row back.
 *
 * Take it before [org.siloserver.silo.network.IdentityTransitionBarrier.withCurrentGeneration]
 * or a database transaction, never inside either.
 */
object DownloadSlotLocks {
    private data class Slot(val serverId: String, val profileId: String, val fileId: Int)

    private val locks = ConcurrentHashMap<Slot, Mutex>()

    fun of(serverId: String, profileId: String, fileId: Int): Mutex =
        locks.computeIfAbsent(Slot(serverId, profileId, fileId)) { Mutex() }
}
