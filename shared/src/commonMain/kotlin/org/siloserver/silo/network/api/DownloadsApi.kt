package org.siloserver.silo.network.api

import org.siloserver.silo.model.download.DownloadCapability
import org.siloserver.silo.model.download.DownloadRecord
import org.siloserver.silo.model.download.DownloadRequest
import org.siloserver.silo.model.download.DownloadsListResponse
import org.siloserver.silo.network.ApiResult
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

/**
 * Ktor wrappers for the server's /api/v1/downloads endpoints
 * (`silo-server/internal/api/handlers/downloads.go`). The /file streaming
 * endpoint is intentionally NOT wrapped here — that goes through
 * DownloadWorker's raw HttpClient so per-byte progress can be reported to
 * WorkManager.
 */
// `open` so DownloadsRepositoryTest can extend with a fake. Other API
// classes (SectionApi, CatalogApi) are final because their repos aren't
// unit-tested at the API boundary; downloads gets real fake-based tests
// because the upsert / refresh / delete state transitions are non-trivial.
open class DownloadsApi(protected val client: HttpClient, private val registry: org.siloserver.silo.network.apiv2.DownloadRegistryV2Api? = null, private val tokens: org.siloserver.silo.network.TokenManager? = null, private val creation: org.siloserver.silo.network.apiv2.DownloadCreationV2Api? = null) {

    private fun changed() = ApiResult.Error(0, "identity_changed", "Downloads need the original saved account and profile.")
    open suspend fun list(scope: org.siloserver.silo.network.AuthScopeSnapshot?): ApiResult<DownloadsListResponse> =
        if (registry == null) list() else if (scope == null) changed() else registry.list(scope)
    open suspend fun delete(id: String, scope: org.siloserver.silo.network.AuthScopeSnapshot?): ApiResult<Unit> =
        if (registry == null) delete(id) else if (scope == null) changed() else registry.delete(id, scope)

    open suspend fun list(): ApiResult<DownloadsListResponse> {
        if (registry != null) return list(tokens?.snapshotCurrentScope())
        return safeApiCall {
        // Trailing slash required — chi router registers the handlers under
        // `r.Route("/downloads", { r.Get("/", ...); r.Post("/", ...) })`
        // (silo-server/internal/api/router.go:1543-1548), and chi 302-redirects
        // the bare `/api/v1/downloads` to `/api/v1/downloads/`. Ktor's POST
        // doesn't follow redirects, which surfaced as ApiResult.Error(302).
        client.get("/api/v1/downloads/")
        }
    }

    /**
     * Feature detection (issue #20 §3). Call at detail load / profile switch;
     * the picker offers only `quality_presets` and hides bitrate presets when
     * transcode is disabled. No trailing slash — this is a named subpath, not
     * the collection root that chi 302-redirects.
     */
    open suspend fun capability(): ApiResult<DownloadCapability> {
        if (registry != null) return registry.capability(tokens?.snapshotCurrentScope() ?: return changed())
        return safeApiCall {
        client.get("/api/v1/downloads/capability")
        }
    }

    open suspend fun create(request: DownloadRequest, scope: org.siloserver.silo.network.AuthScopeSnapshot?): ApiResult<DownloadRecord> =
        if (creation == null) create(request) else if (scope == null) changed() else creation.create(request, scope)

    open suspend fun createBatch(request: DownloadRequest, scope: org.siloserver.silo.network.AuthScopeSnapshot?): ApiResult<DownloadsListResponse> =
        if (creation == null) createBatch(request) else if (scope == null) changed() else creation.createBatch(request, scope)

    open suspend fun create(request: DownloadRequest): ApiResult<DownloadRecord> {
        if (creation != null) return create(request, tokens?.snapshotCurrentScope())
        return safeApiCall {
        client.post("/api/v1/downloads/") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        }
    }

    /**
     * Series-batch creation. When the request has `series=true`, the server
     * returns a [DownloadsListResponse] (one entry per episode, all sharing
     * a `batch_id`) instead of a single record. Wired separately from
     * [create] so the return shape is type-safe at the call site.
     */
    open suspend fun createBatch(request: DownloadRequest): ApiResult<DownloadsListResponse> {
        if (creation != null) return createBatch(request, tokens?.snapshotCurrentScope())
        return safeApiCall {
        client.post("/api/v1/downloads/") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        }
    }

    open suspend fun delete(id: String): ApiResult<Unit> {
        if (registry != null) return delete(id, tokens?.snapshotCurrentScope())
        return safeApiCall {
        client.delete("/api/v1/downloads/$id")
        }
    }
}
