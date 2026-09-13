package org.siloserver.silo.network.api

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.*
import org.siloserver.silo.model.personal.*
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.authScope
import org.siloserver.silo.network.singleAttempt
import org.siloserver.silo.network.requireSiloAuth
import org.siloserver.silo.network.apiv2.HistoryV2Api
import org.siloserver.silo.network.apiv2.HistoryContinuationV2
import org.siloserver.silo.network.apiv2.HistoryPageV2
import org.siloserver.silo.network.apiv2.ApiV2Gate
import org.siloserver.silo.network.apiv2.ProgressCollection
import org.siloserver.silo.network.apiv2.safeApiV2Call

/** The v2 contract caps `limit` at 200; ask for the maximum so the walk is as short as possible. */
private const val PROGRESS_PAGE_SIZE = 200

/**
 * Runaway guard only (100 pages × 200 = 20,000 entries). Hitting it with
 * `has_more` still true is reported as [PROGRESS_INCOMPLETE_ERROR], never
 * as a silent prefix.
 */
private const val PROGRESS_MAX_PAGES = 100

/** [ApiResult.Error.error] when the progress walk stopped before the last page. */
const val PROGRESS_INCOMPLETE_ERROR = "progress_incomplete"

/** [ApiResult.Error.error] when the active identity moved while the progress walk was in flight. */
const val PROGRESS_IDENTITY_CHANGED_ERROR = "identity_changed"

class PersonalDataApi(
    private val client: HttpClient,
    private val apiV2Gate: ApiV2Gate = ApiV2Gate.Unrestricted,
    /**
     * Source of the identity a multi-request operation is pinned to. Null
     * (single-scope tests) leaves each request on the globally-active scope.
     */
    private val tokenManager: TokenManager? = null,
) {

    suspend fun writePersonal(handle: org.siloserver.silo.repository.port.PersonalWriteHandle): ApiResult<Unit> {
        val scope = handle.scope
        if (tokenManager == null || scope != tokenManager.snapshotCurrentScope())
            return ApiResult.Error(0, "identity_changed", "The initiating viewer changed.")
        val command = handle.command
        if (!command.valid()) return ApiResult.Error(422, "validation_failed", "Invalid personal-data command.")
        val result = safeApiV2Call<Unit>(apiV2Gate) {
            client.request(command.path) {
                method = HttpMethod.parse(command.method)
                authScope(scope)
                requireSiloAuth()
                singleAttempt()
                command.body?.let { contentType(ContentType.Application.Json); setBody(it) }
            }.also { check(!it.status.isSuccess() || it.status == HttpStatusCode.NoContent) }
        }
        if (scope != tokenManager.snapshotCurrentScope())
            return ApiResult.Error(0, "identity_changed", "The initiating viewer changed.")
        return result
    }

    // --- User Libraries ---

    suspend fun listUserLibraries(): ApiResult<List<UserLibrary>> {
        val scope = tokenManager?.snapshotCurrentScope()
        if (tokenManager != null && scope == null)
            return ApiResult.Error(0, "identity_changed", "Library discovery needs an active account.")
        val result = safeApiV2Call<org.siloserver.silo.network.apiv2.UserLibrariesV2>(apiV2Gate) {
            client.get("/api/v2/user/libraries") {
                // A captured account with no profile is valid for preselection discovery.
                scope?.let { authScope(it) }
                requireSiloAuth()
            }
        }
        if (scope != null && !scope.isSameIdentityAs(tokenManager?.snapshotCurrentScope()))
            return ApiResult.Error(0, "identity_changed", "The library viewer changed.")
        return when (result) {
            is ApiResult.Success -> try { ApiResult.Success(result.data.project()) }
                catch (_: IllegalArgumentException) { ApiResult.Error(0, "invalid_libraries", "The server returned an incomplete or unsupported library collection.") }
            is ApiResult.Error -> result
            is ApiResult.NetworkError -> result
        }
    }

    // --- History ---

    private val historyV2 = HistoryV2Api(client, apiV2Gate, tokenManager)

    suspend fun listHistory(continuation: HistoryContinuationV2? = null, limit: Int = 40): ApiResult<HistoryPageV2> =
        historyV2.page(limit = limit, continuation = continuation)

    // --- Progress ---

    /**
     * Pilot v2 operation (listProgress): v2 only, no v1 fallback. v1 returned
     * the whole list; v2 pages by opaque cursor, so every page is walked here.
     *
     * The whole walk runs under ONE identity. [scope] pins it explicitly;
     * otherwise the active scope is captured once up front and applied to
     * every page, so a server or profile switch mid-walk cannot send a later
     * page under a different identity than the entries already collected.
     * When the scope was captured here and the active identity moves between
     * pages, the walk aborts with [PROGRESS_IDENTITY_CHANGED_ERROR] instead of
     * relying on the server to reject the cursor.
     *
     * The result is either the complete list or an error — never a silent
     * prefix. A page failure returns that page's error; exceeding
     * [PROGRESS_MAX_PAGES] with more pages left returns an
     * [ApiResult.Error] whose `error` is [PROGRESS_INCOMPLETE_ERROR], so
     * continue-watching consumers never treat older entries as absent.
     */
    suspend fun listProgress(scope: AuthScopeSnapshot? = null): ApiResult<ProgressListResponse> {
        val pinned = scope ?: tokenManager?.snapshotCurrentScope()
        // Only a scope captured here is checked against the live identity; an
        // explicitly pinned scope is the caller's to send whatever is active.
        val liveIdentityGuard = if (scope == null) pinned else null
        val entries = mutableListOf<ProgressEntry>()
        var cursor: String? = null
        var pages = 0
        while (true) {
            if (
                pages > 0 &&
                liveIdentityGuard != null &&
                !liveIdentityGuard.isSameIdentityAs(tokenManager?.snapshotCurrentScope())
            ) {
                return ApiResult.Error(
                    code = 0,
                    error = PROGRESS_IDENTITY_CHANGED_ERROR,
                    message = "Active identity changed after $pages progress page(s); " +
                        "refusing to continue the walk under a different identity.",
                )
            }
            val page = safeApiV2Call<ProgressCollection>(apiV2Gate) {
                client.get("/api/v2/progress") {
                    pinned?.let { authScope(it) }
                    parameter("limit", PROGRESS_PAGE_SIZE)
                    cursor?.let { parameter("cursor", it) }
                }
            }
            val collection = when (page) {
                is ApiResult.Success -> page.data
                is ApiResult.Error -> return page
                is ApiResult.NetworkError -> return page
            }
            collection.items.mapTo(entries) {
                ProgressEntry(
                    mediaItemId = it.mediaItemId,
                    positionSeconds = it.positionSeconds,
                    durationSeconds = it.durationSeconds,
                    completed = it.completed,
                    updatedAt = it.updatedAt,
                )
            }
            val next = collection.page.nextCursor
            pages++
            if (!collection.page.hasMore || next == null) break
            if (pages >= PROGRESS_MAX_PAGES) {
                return ApiResult.Error(
                    code = 0,
                    error = PROGRESS_INCOMPLETE_ERROR,
                    message = "Progress list still has more pages after $PROGRESS_MAX_PAGES pages of " +
                        "$PROGRESS_PAGE_SIZE; refusing to return a partial list.",
                )
            }
            cursor = next
        }
        return ApiResult.Success(ProgressListResponse(progress = entries))
    }
}
