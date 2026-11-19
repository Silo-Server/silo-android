package org.siloserver.silo.network.api

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.siloserver.silo.model.section.sectionItemLimitParam
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.apiv2.HomeSectionsV2Api
import org.siloserver.silo.network.apiv2.ITEM_LIMIT_UNSUPPORTED
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The `item_limit` the Home and library section lists carry, read from the
 * server's `section_item_limit_max` once per server for the life of the
 * process. A failed or slow read counts as "not supported", and so does a
 * server without the field: those requests carry no `item_limit`, which an
 * older server rejects with 422 `unknown_parameter`. Rows still cap their
 * cards on the client.
 */
class SectionRowItemLimit internal constructor(
    private val readMax: (suspend (AuthScopeSnapshot) -> ApiResult<Int?>)?,
    private val timeout: Duration = 3.seconds,
) {
    constructor(home: HomeSectionsV2Api) : this({ owner -> home.sectionItemLimitMax(owner) })

    private val lock = Mutex()
    private val byServer = mutableMapOf<String, Int?>()

    /** The value to send for [owner]'s server, or null to send nothing. */
    suspend fun forOwner(owner: AuthScopeSnapshot): Int? {
        val read = readMax ?: return null
        // Held across the read so concurrent first loads share one request.
        return lock.withLock {
            if (owner.serverId in byServer) return@withLock byServer[owner.serverId]
            val result = withTimeoutOrNull(timeout) { read(owner) }
            val limit = sectionItemLimitParam((result as? ApiResult.Success)?.data)
            // A profile switch mid-read is not the server's answer; ask again next time.
            if ((result as? ApiResult.Error)?.error != "identity_changed") byServer[owner.serverId] = limit
            limit
        }
    }

    /**
     * Runs a section list read with this server's limit. A server that
     * rejects `item_limit` as unknown is marked unsupported for the session
     * and read once more without it.
     */
    suspend fun <T> read(owner: AuthScopeSnapshot, block: suspend (itemLimit: Int?) -> ApiResult<T>): ApiResult<T> {
        val limit = forOwner(owner) ?: return block(null)
        val result = block(limit)
        if ((result as? ApiResult.Error)?.error != ITEM_LIMIT_UNSUPPORTED) return result
        lock.withLock { byServer[owner.serverId] = null }
        return block(null)
    }

    companion object {
        /** Never reads the capability and never sends `item_limit`. */
        val Unsupported = SectionRowItemLimit(readMax = null)
    }
}
