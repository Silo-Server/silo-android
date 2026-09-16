package org.siloserver.silo.repository

import org.siloserver.silo.model.download.DownloadRecord
import org.siloserver.silo.model.download.DownloadRequest
import org.siloserver.silo.model.download.DownloadsListResponse
import org.siloserver.silo.network.ApiResult
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun stubRecord(
    id: String,
    fileId: Int,
    status: String = "queued",
    bytes: Long = 0,
): DownloadRecord = DownloadRecord(
    id = id,
    contentId = "tt$fileId",
    mediaFileId = fileId,
    fileSize = 1000,
    bytesSent = bytes,
    kind = "queued",
    status = status,
    createdAt = "2026-05-24T00:00:00Z",
)

/**
 * Stand-in `DownloadsApi` for repo tests. Inherits the real class so the
 * type matches what `DownloadsRepository` expects, but overrides the three
 * scope-taking suspending methods with controllable test fakes.
 */
private object RepoTestNoDevices : org.siloserver.silo.network.DeviceMetadataProvider {
    override suspend fun current(): org.siloserver.silo.network.SiloDeviceMetadata? = null
}

private open class FakeDownloadsApi(
    private val initialList: List<DownloadRecord> = emptyList(),
    private val createResult: ((DownloadRequest) -> ApiResult<DownloadRecord>)? = null,
) : org.siloserver.silo.network.api.DownloadsApi(
    registry = org.siloserver.silo.network.apiv2.DownloadRegistryV2Api(HttpClient(), org.siloserver.silo.network.TokenManagerImpl(), RepoTestNoDevices, org.siloserver.silo.network.apiv2.ApiV2Gate.Unrestricted),
    tokens = org.siloserver.silo.network.TokenManagerImpl(),
    creation = org.siloserver.silo.network.apiv2.DownloadCreationV2Api(HttpClient(), org.siloserver.silo.network.TokenManagerImpl(), RepoTestNoDevices,
        org.siloserver.silo.network.apiv2.DownloadRegistryV2Api(HttpClient(), org.siloserver.silo.network.TokenManagerImpl(), RepoTestNoDevices, org.siloserver.silo.network.apiv2.ApiV2Gate.Unrestricted), org.siloserver.silo.network.apiv2.ApiV2Gate.Unrestricted),
) {

    var listCalls = 0
    var deleteCalls = mutableListOf<String>()

    override suspend fun list(scope: org.siloserver.silo.network.AuthScopeSnapshot?): ApiResult<DownloadsListResponse> {
        listCalls++
        return ApiResult.Success(DownloadsListResponse(initialList))
    }

    override suspend fun create(request: DownloadRequest, scope: org.siloserver.silo.network.AuthScopeSnapshot?): ApiResult<DownloadRecord> {
        val factory = createResult ?: return ApiResult.NetworkError(IllegalStateException("no fake"))
        return factory(request)
    }

    override suspend fun delete(id: String, scope: org.siloserver.silo.network.AuthScopeSnapshot?): ApiResult<Unit> {
        deleteCalls += id
        return ApiResult.Success(Unit)
    }
}

class DownloadsRepositoryTest {

    @Test
    fun `refresh populates StateFlow from server list`() = runTest {
        val seeded = listOf(stubRecord("a", 1), stubRecord("b", 2))
        val api = FakeDownloadsApi(initialList = seeded)
        val repo = DownloadsRepository(api)

        assertTrue(repo.records.first().isEmpty())
        val result = repo.refresh()
        assertTrue(result is ApiResult.Success)
        assertEquals(seeded, repo.records.first())
        assertEquals(1, api.listCalls)
    }

    @Test
    fun `create upserts the returned record without an extra refresh`() = runTest {
        val created = stubRecord("new", 7, status = "queued")
        val api = FakeDownloadsApi(createResult = { ApiResult.Success(created) })
        val repo = DownloadsRepository(api)

        val result = repo.create(DownloadRequest(contentId = "tt7", fileId = 7))
        assertTrue(result is ApiResult.Success)
        assertEquals(listOf(created), repo.records.first())
        // No extra GET — server's POST response is enough.
        assertEquals(0, api.listCalls)
    }

    @Test
    fun `delete removes from cache (two-phase calls server twice)`() = runTest {
        // v1.1: the server's DELETE only cancels an active record on first
        // call; second call actually drops the row. Repo always issues both.
        val a = stubRecord("a", 1)
        val b = stubRecord("b", 2)
        val api = FakeDownloadsApi(initialList = listOf(a, b))
        val repo = DownloadsRepository(api)
        repo.refresh()

        val result = repo.delete("a")
        assertTrue(result is ApiResult.Success)
        assertEquals(listOf(b), repo.records.first())
        assertEquals(listOf("a", "a"), api.deleteCalls)
    }

    @Test
    fun `delete tolerates 404 on second call`() = runTest {
        val a = stubRecord("a", 1)
        val api = object : FakeDownloadsApi(initialList = listOf(a)) {
            override suspend fun delete(id: String, scope: org.siloserver.silo.network.AuthScopeSnapshot?): ApiResult<Unit> {
                deleteCalls += id
                return if (deleteCalls.size == 1) ApiResult.Success(Unit)
                       else ApiResult.Error(code = 404, error = "not_found", message = "gone")
            }
        }
        val repo = DownloadsRepository(api)
        repo.refresh()

        val result = repo.delete("a")
        assertTrue(result is ApiResult.Success)
        assertEquals(2, api.deleteCalls.size)
    }

    @Test
    fun `refresh filters out pending-delete ids (ghost suppression)`() = runTest {
        // The server's first DELETE on an active record only marks cancelled,
        // so the next list() may still return it. Repo's pendingDelete set
        // must suppress it so the row doesn't ghost back into the UI.
        val ghost = stubRecord("ghost", 1, status = "downloading")
        val api = object : FakeDownloadsApi(initialList = listOf(ghost)) {
            override suspend fun delete(id: String, scope: org.siloserver.silo.network.AuthScopeSnapshot?): ApiResult<Unit> {
                deleteCalls += id
                return ApiResult.Success(Unit)
            }
        }
        val repo = DownloadsRepository(api)
        repo.refresh()
        assertEquals(1, repo.records.first().size)

        repo.delete("ghost")
        // Even though the server still returns it, refresh filters it out.
        repo.refresh()
        assertTrue(repo.records.first().isEmpty())
    }

    @Test
    fun `seedFromSidecars populates cache without network`() = runTest {
        val api = FakeDownloadsApi(initialList = emptyList())
        val repo = DownloadsRepository(api)

        repo.seedFromSidecars(listOf(stubRecord("disk-a", 1), stubRecord("disk-b", 2)))
        assertEquals(2, repo.records.first().size)
        assertEquals(0, api.listCalls)
    }

    @Test
    fun `refresh merge keeps disk records absent from server when caller flags them`() = runTest {
        val server = stubRecord("from-server", 1)
        val onlyOnDisk = stubRecord("disk-only", 2)
        val api = FakeDownloadsApi(initialList = listOf(server))
        val repo = DownloadsRepository(api)

        repo.seedFromSidecars(listOf(server, onlyOnDisk))
        repo.refresh(keepIdsAbsentFromServer = setOf("disk-only"))
        val ids = repo.records.first().map { it.id }.toSet()
        assertEquals(setOf("from-server", "disk-only"), ids)
    }

    @Test
    fun `upsertLocal replaces existing record by id`() = runTest {
        val initial = stubRecord("a", 1, status = "queued", bytes = 0)
        val api = FakeDownloadsApi(initialList = listOf(initial))
        val repo = DownloadsRepository(api)
        repo.refresh()

        val updated = initial.copy(status = "downloading", bytesSent = 500)
        repo.upsertLocal(updated)

        val rec = repo.records.first().first()
        assertEquals("downloading", rec.status)
        assertEquals(500L, rec.bytesSent)
    }

    @Test
    fun `delete keeps cache row when server delete fails`() = runTest {
        // pendingDelete is in-memory only: if we dropped the row (and bytes)
        // on a failed server delete, an app restart would merge the server
        // record back pointing at deleted files. Failure must be a no-op.
        val a = stubRecord("a", 1)
        val api = object : FakeDownloadsApi(initialList = listOf(a)) {
            override suspend fun delete(id: String, scope: org.siloserver.silo.network.AuthScopeSnapshot?): ApiResult<Unit> {
                deleteCalls += id
                return ApiResult.NetworkError(IllegalStateException("offline"))
            }
        }
        val repo = DownloadsRepository(api)
        repo.refresh()

        val result = repo.delete("a")
        assertTrue(result is ApiResult.NetworkError)
        assertEquals(listOf(a), repo.records.first())
        // Must NOT be marked pending-delete — the next refresh has to keep it.
        repo.refresh()
        assertEquals(listOf(a), repo.records.first())
    }

    @Test
    fun `delete keeps cache row when second delete call fails`() = runTest {
        // The first DELETE only cancels an active record; the row is still on
        // the server until the second DELETE confirms. If that second call
        // fails, the cache must keep the row (and not mark pending-delete) so
        // the caller keeps the bytes and surfaces the error.
        val a = stubRecord("a", 1)
        val api = object : FakeDownloadsApi(initialList = listOf(a)) {
            override suspend fun delete(id: String, scope: org.siloserver.silo.network.AuthScopeSnapshot?): ApiResult<Unit> {
                deleteCalls += id
                return if (deleteCalls.size == 1) ApiResult.Success(Unit)
                       else ApiResult.Error(code = 500, error = "server_error", message = "boom")
            }
        }
        val repo = DownloadsRepository(api)
        repo.refresh()

        val result = repo.delete("a")
        assertTrue(result is ApiResult.Error)
        assertEquals(500, result.code)
        assertEquals(listOf(a), repo.records.first())
        // Must NOT be marked pending-delete — the next refresh has to keep it.
        repo.refresh()
        assertEquals(listOf(a), repo.records.first())
    }

    @Test
    fun `delete treats 404 on first call as already removed`() = runTest {
        // Local-only records (server cleaned them up, or they never had a
        // server row) come back 404 — that's a confirmed removal, so the
        // caller may proceed with local file cleanup.
        val a = stubRecord("a", 1)
        val api = object : FakeDownloadsApi(initialList = listOf(a)) {
            override suspend fun delete(id: String, scope: org.siloserver.silo.network.AuthScopeSnapshot?): ApiResult<Unit> {
                deleteCalls += id
                return ApiResult.Error(code = 404, error = "not_found", message = "gone")
            }
        }
        val repo = DownloadsRepository(api)
        repo.refresh()

        val result = repo.delete("a")
        assertTrue(result is ApiResult.Success)
        assertTrue(repo.records.first().isEmpty())
        assertEquals(1, api.deleteCalls.size)
    }

    @Test
    fun `recordForFile looks up by mediaFileId`() = runTest {
        val target = stubRecord("a", 42)
        val api = FakeDownloadsApi(initialList = listOf(stubRecord("x", 1), target))
        val repo = DownloadsRepository(api)
        repo.refresh()

        assertNotNull(repo.recordForFile(42))
        assertEquals("a", repo.recordForFile(42)?.id)
        assertNull(repo.recordForFile(999))
    }
}
