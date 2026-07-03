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
open class DownloadsApi(protected val client: HttpClient) {

    open suspend fun list(): ApiResult<DownloadsListResponse> = safeApiCall {
        // Trailing slash required — chi router registers the handlers under
        // `r.Route("/downloads", { r.Get("/", ...); r.Post("/", ...) })`
        // (silo-server/internal/api/router.go:1543-1548), and chi 302-redirects
        // the bare `/api/v1/downloads` to `/api/v1/downloads/`. Ktor's POST
        // doesn't follow redirects, which surfaced as ApiResult.Error(302).
        client.get("/api/v1/downloads/")
    }

    open suspend fun create(request: DownloadRequest): ApiResult<DownloadRecord> = safeApiCall {
        client.post("/api/v1/downloads/") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    /**
     * Series-batch creation. When the request has `series=true`, the server
     * returns a [DownloadsListResponse] (one entry per episode, all sharing
     * a `batch_id`) instead of a single record. Wired separately from
     * [create] so the return shape is type-safe at the call site.
     */
    open suspend fun createBatch(request: DownloadRequest): ApiResult<DownloadsListResponse> = safeApiCall {
        client.post("/api/v1/downloads/") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    /** GET /api/v1/downloads/capability — no trailing slash (single route,
     *  unlike the list/create pair). 404 on older servers → callers treat a
     *  missing capability as original-only. */
    open suspend fun capability(): ApiResult<DownloadCapability> = safeApiCall {
        client.get("/api/v1/downloads/capability")
    }

    open suspend fun delete(id: String): ApiResult<Unit> = safeApiCall {
        client.delete("/api/v1/downloads/$id")
    }
}
