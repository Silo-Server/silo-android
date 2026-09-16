package org.siloserver.silo.android.ui.screens.player

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.siloserver.silo.model.catalog.WatchDetail
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.repository.CatalogRepository

/** Retains the metadata owner captured before coordinator startup. */
internal class ReadyWatchMetadata(
    private val repository: CatalogRepository,
    private val owner: AuthScopeSnapshot?,
    private val contentId: String,
    private val serverUrl: String,
    private val libraryId: Int? = null,
    private val ownsLoad: () -> Boolean,
) {
    suspend fun current(): Boolean {
        // Missing optional metadata authority cannot dispatch a read. The
        // coordinator's existing ready fallback still owns its own session.
        val valid = owner == null || (owner.serverUrl == serverUrl && repository.isWatchAuthorityCurrent(owner))
        currentCoroutineContext().ensureActive()
        return valid && ownsLoad()
    }

    suspend fun read(): WatchDetail? {
        if (owner == null || !current()) return null
        val result = repository.getWatchDetail(contentId, owner, libraryId)
        if (!current()) return null
        return (result as? ApiResult.Success)?.data
    }
}
