package org.siloserver.silo.network.api

import org.siloserver.silo.model.download.DownloadCapability
import org.siloserver.silo.model.download.DownloadRecord
import org.siloserver.silo.model.download.DownloadRequest
import org.siloserver.silo.model.download.DownloadsListResponse
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.apiv2.DownloadCreationV2Api
import org.siloserver.silo.network.apiv2.DownloadRegistryV2Api

/**
 * Scope-guarded facade over the v2 download registry and creation APIs. The
 * /file streaming endpoint is intentionally NOT wrapped here — that goes
 * through DownloadWorker's raw HttpClient so per-byte progress can be
 * reported to WorkManager.
 */
// `open` so DownloadsRepositoryTest can extend with a fake. Other API
// classes (SectionApi, CatalogApi) are final because their repos aren't
// unit-tested at the API boundary; downloads gets real fake-based tests
// because the upsert / refresh / delete state transitions are non-trivial.
open class DownloadsApi(
    private val registry: DownloadRegistryV2Api,
    private val tokens: TokenManager,
    private val creation: DownloadCreationV2Api,
) {

    private fun changed() = ApiResult.Error(0, "identity_changed", "Downloads need the original saved account and profile.")

    open suspend fun list(scope: AuthScopeSnapshot?): ApiResult<DownloadsListResponse> =
        if (scope == null) changed() else registry.list(scope)

    open suspend fun delete(id: String, scope: AuthScopeSnapshot?): ApiResult<Unit> =
        if (scope == null) changed() else registry.delete(id, scope)

    /**
     * Feature detection (issue #20 §3). Call at detail load / profile switch;
     * the picker offers only `quality_presets` and hides bitrate presets when
     * transcode is disabled.
     */
    open suspend fun capability(): ApiResult<DownloadCapability> {
        val scope = tokens.snapshotCurrentScope() ?: return changed()
        return registry.capability(scope)
    }

    open suspend fun create(request: DownloadRequest, scope: AuthScopeSnapshot?): ApiResult<DownloadRecord> =
        if (scope == null) changed() else creation.create(request, scope)

    /**
     * Series-batch creation. When the request has `series=true`, the server
     * returns a [DownloadsListResponse] (one entry per episode, all sharing
     * a `batch_id`) instead of a single record. Wired separately from
     * [create] so the return shape is type-safe at the call site.
     */
    open suspend fun createBatch(request: DownloadRequest, scope: AuthScopeSnapshot?): ApiResult<DownloadsListResponse> =
        if (scope == null) changed() else creation.createBatch(request, scope)
}
