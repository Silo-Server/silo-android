package org.siloserver.silo.repository

import org.siloserver.silo.model.download.DownloadRecord
import org.siloserver.silo.model.download.DownloadsListResponse
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.port.DownloadDeletionPort
import org.siloserver.silo.repository.port.PendingDownloadDeletion
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun rec(id: String, fileId: Int): DownloadRecord = DownloadRecord(
    id = id,
    contentId = "tt$fileId",
    mediaFileId = fileId,
    fileSize = 1000,
    bytesSent = 1000,
    kind = "queued",
    status = "completed",
    createdAt = "2026-06-16T00:00:00Z",
)

/** In-memory durable tombstone store standing in for the Room-backed port. */
private class FakeDeletionPort : DownloadDeletionPort {
    data class Key(val serverId: String, val accountId: String, val profileId: String, val recordId: String)
    val rows = mutableMapOf<Key, Int?>()
    override suspend fun enqueue(serverId: String, accountId: String, profileId: String, recordId: String, mediaFileId: Int?) {
        rows[Key(serverId, accountId, profileId, recordId)] = mediaFileId
    }
    override suspend fun allPendingRecordIds(): Set<String> = rows.keys.map { it.recordId }.toSet()
    override suspend fun pendingForScope(serverId: String, accountId: String, profileId: String): List<PendingDownloadDeletion> =
        rows.filterKeys { it.serverId == serverId && it.accountId == accountId && it.profileId == profileId }
            .map { (k, v) -> PendingDownloadDeletion(k.serverId, k.accountId, k.profileId, k.recordId, v) }
    override suspend fun remove(serverId: String, accountId: String, profileId: String, recordId: String) {
        rows.remove(Key(serverId, accountId, profileId, recordId))
    }
}

private object OfflineDeleteNoDevices : org.siloserver.silo.network.DeviceMetadataProvider {
    override suspend fun current(): org.siloserver.silo.network.SiloDeviceMetadata? = null
}

private class FakeApi(
    var serverList: List<DownloadRecord> = emptyList(),
    var deleteResult: (String) -> ApiResult<Unit> = { ApiResult.Success(Unit) },
    var capability: org.siloserver.silo.model.download.DownloadCapability? = null,
    private val log: MutableList<String> = mutableListOf(),
) : org.siloserver.silo.network.api.DownloadsApi(
    registry = org.siloserver.silo.network.apiv2.DownloadRegistryV2Api(HttpClient(), org.siloserver.silo.network.TokenManagerImpl(), OfflineDeleteNoDevices, org.siloserver.silo.network.apiv2.ApiV2Gate.Unrestricted),
    tokens = org.siloserver.silo.network.TokenManagerImpl(),
    creation = org.siloserver.silo.network.apiv2.DownloadCreationV2Api(HttpClient(), org.siloserver.silo.network.TokenManagerImpl(), OfflineDeleteNoDevices,
        org.siloserver.silo.network.apiv2.DownloadRegistryV2Api(HttpClient(), org.siloserver.silo.network.TokenManagerImpl(), OfflineDeleteNoDevices, org.siloserver.silo.network.apiv2.ApiV2Gate.Unrestricted), org.siloserver.silo.network.apiv2.ApiV2Gate.Unrestricted),
) {
    val deleteCalls = mutableListOf<String>()
    override suspend fun list(scope: org.siloserver.silo.network.AuthScopeSnapshot?): ApiResult<DownloadsListResponse> = ApiResult.Success(DownloadsListResponse(serverList))
    override suspend fun capability(): ApiResult<org.siloserver.silo.model.download.DownloadCapability> =
        capability?.let { ApiResult.Success(it) } ?: ApiResult.Error(404, "not_found", "no capability")
    override suspend fun delete(id: String, scope: org.siloserver.silo.network.AuthScopeSnapshot?): ApiResult<Unit> {
        deleteCalls += id
        log += "delete:$id"
        return deleteResult(id)
    }
}

class DownloadsRepositoryOfflineDeleteTest {
    @Test fun onlyCurrentLoginTombstonesReplayOrClear() = runTest {
        val scope = org.siloserver.silo.network.AuthScopeSnapshot("s", "p", "https://example.invalid", null, identityGeneration = 0)
        val authority = org.siloserver.silo.network.DurableLoginAuthority("login", scope)
        val owners = object : org.siloserver.silo.network.DurableLoginAuthorityProvider {
            override suspend fun snapshotDurableLoginAuthority() = authority
        }
        val devices = object : org.siloserver.silo.network.DeviceMetadataProvider {
            override suspend fun current() = org.siloserver.silo.network.SiloDeviceMetadata("device", "test", "android")
        }
        val rows = mutableListOf(
            PendingDownloadDeletion("s", "", "p", "owned", 1, "login", scope.serverUrl, "device"),
            PendingDownloadDeletion("s", "", "p", "foreign", 2, "prior-login", scope.serverUrl, "device"),
            PendingDownloadDeletion("s", "", "p", "legacy", 3),
        )
        val port = object : DownloadDeletionPort {
            override suspend fun enqueue(serverId: String, accountId: String, profileId: String, recordId: String, mediaFileId: Int?) = Unit
            override suspend fun allPendingRecordIds() = rows.map { it.recordId }.toSet()
            override suspend fun pendingForScope(serverId: String, accountId: String, profileId: String) = rows.toList()
            override suspend fun remove(serverId: String, accountId: String, profileId: String, recordId: String) { rows.removeAll { it.recordId == recordId } }
        }
        val api = FakeApi(serverList = rows.mapIndexed { i, row -> rec(row.recordId, i + 1) })
        val repo = DownloadsRepository(api, port, owners, devices, org.siloserver.silo.network.DefaultIdentityTransitionBarrier())
        repo.refresh(serverId = "s", profileId = "p")
        assertEquals(listOf("owned", "owned"), api.deleteCalls)
        assertEquals(listOf("owned"), repo.pendingDeletionsForScope("s", "", "p").map { it.recordId })
        api.serverList = emptyList()
        repo.refresh(serverId = "s", profileId = "p")
        assertEquals(listOf("foreign", "legacy"), rows.map { it.recordId })
    }


    @Test
    fun `enqueueDurableDelete drops the record and persists a tombstone`() = runTest {
        val port = FakeDeletionPort()
        val api = FakeApi(serverList = listOf(rec("a", 1), rec("b", 2)))
        val repo = DownloadsRepository(api, port)
        repo.refresh()
        assertEquals(setOf("a", "b"), repo.records.first().map { it.id }.toSet())

        repo.enqueueDurableDelete("srv1", "", "prof1", "a", mediaFileId = 1)

        assertEquals(listOf("b"), repo.records.first().map { it.id })
        assertEquals(setOf("a"), port.allPendingRecordIds())
    }

    @Test
    fun `durable tombstone filters a still-present server record across a restart`() = runTest {
        val port = FakeDeletionPort()
        // Offline delete happened in a previous "session": tombstone persisted,
        // but the server still lists the record (delete never reached it).
        port.enqueue("srv1", "", "prof1", "a", 1)

        // Fresh repository instance = app restart (in-memory pendingDelete empty).
        val api = FakeApi(serverList = listOf(rec("a", 1), rec("b", 2)), deleteResult = { ApiResult.NetworkError(RuntimeException("offline")) })
        val repo = DownloadsRepository(api, port)

        repo.refresh()  // no scope → pure filter, no reconcile

        assertEquals(listOf("b"), repo.records.first().map { it.id }) // 'a' stays filtered
    }

    @Test
    fun `scoped refresh replays the server delete for a still-present tombstone`() = runTest {
        val port = FakeDeletionPort()
        port.enqueue("srv1", "", "prof1", "a", 1)
        val api = FakeApi(serverList = listOf(rec("a", 1)))
        val repo = DownloadsRepository(api, port)

        repo.refresh(serverId = "srv1", profileId = "prof1")

        // Two-phase DELETE replayed for the still-present record.
        assertTrue(api.deleteCalls.contains("a"))
        // Still present in this list → tombstone kept for the next refresh.
        assertTrue(port.allPendingRecordIds().contains("a"))
    }

    @Test
    fun `tombstone clears only once the server list confirms absence`() = runTest {
        val port = FakeDeletionPort()
        port.enqueue("srv1", "", "prof1", "a", 1)
        val api = FakeApi(serverList = listOf(rec("a", 1)))
        val repo = DownloadsRepository(api, port)

        repo.refresh(serverId = "srv1", profileId = "prof1")
        assertTrue(port.allPendingRecordIds().contains("a")) // still listed → kept

        api.serverList = emptyList() // server now confirms it's gone
        repo.refresh(serverId = "srv1", profileId = "prof1")
        assertFalse(port.allPendingRecordIds().contains("a")) // cleared
    }

    @Test
    fun `refreshing one scope never clears another scope's tombstone`() = runTest {
        val port = FakeDeletionPort()
        port.enqueue("srvA", "", "prof1", "a", 1) // offline delete on server A, still pending
        port.enqueue("srvB", "", "prof1", "b", 2)
        // Refresh scope B; its server lists nothing (b already gone there).
        val api = FakeApi(serverList = emptyList())
        val repo = DownloadsRepository(api, port)

        repo.refresh(serverId = "srvB", profileId = "prof1")

        // Scope B's tombstone cleared (confirmed gone); scope A's is untouched
        // even though it's absent from scope B's list.
        assertFalse(port.allPendingRecordIds().contains("b"))
        assertTrue(port.allPendingRecordIds().contains("a"))
    }

    private val revokeAware = org.siloserver.silo.model.download.DownloadCapability(enabled = true, downloadAllowed = true, revokedRemovesLocal = true)

    private class FakeCleanup(private val log: MutableList<String>, var succeeds: Boolean = true) :
        org.siloserver.silo.repository.port.RevokedDownloadCleanupPort {
        override suspend fun removeLocalCopy(serverId: String, accountId: String, profileId: String, recordId: String, mediaFileId: Int): Boolean {
            log += "local:$serverId/$profileId/$recordId/$mediaFileId"
            return succeeds
        }
    }

    @Test
    fun `background refresh removes a revoked download locally before deleting its entry`() = runTest {
        val scope = org.siloserver.silo.network.AuthScopeSnapshot("s", "p", "https://example.invalid", null, identityGeneration = 0)
        val owners = object : org.siloserver.silo.network.DurableLoginAuthorityProvider {
            override suspend fun snapshotDurableLoginAuthority() = org.siloserver.silo.network.DurableLoginAuthority("login", scope)
        }
        val log = mutableListOf<String>()
        val port = FakeDeletionPort()
        val api = FakeApi(serverList = listOf(rec("kept", 1), rec("gone", 2).copy(status = "revoked")), capability = revokeAware, log = log)
        val repo = DownloadsRepository(api, port, owners, identityTransitions = org.siloserver.silo.network.DefaultIdentityTransitionBarrier(),
            revokedCleanup = FakeCleanup(log))

        repo.refresh() // unscoped, as the status worker reads the registry

        assertEquals(listOf("local:s/p/gone/2", "delete:gone", "delete:gone"), log)
        assertEquals(listOf("kept"), repo.records.first().map { it.id })
        assertEquals(setOf("gone"), port.allPendingRecordIds())
    }

    @Test
    fun `a revoked entry keeps its server entry until the local copy is gone`() = runTest {
        val log = mutableListOf<String>()
        val port = FakeDeletionPort()
        val cleanup = FakeCleanup(log, succeeds = false)
        val api = FakeApi(serverList = listOf(rec("gone", 2).copy(status = "revoked")), capability = revokeAware, log = log)
        val repo = DownloadsRepository(api, port, revokedCleanup = cleanup)

        repo.refresh(serverId = "s", profileId = "p")
        assertEquals(listOf("local:s/p/gone/2"), log)
        assertTrue(repo.records.first().isEmpty())

        cleanup.succeeds = true
        log.clear()
        repo.refresh(serverId = "s", profileId = "p")
        assertEquals(listOf("local:s/p/gone/2", "delete:gone", "delete:gone"), log)

        api.serverList = emptyList()
        repo.refresh(serverId = "s", profileId = "p")
        assertTrue(port.allPendingRecordIds().isEmpty())
    }

    @Test
    fun `a revoked entry is not deleted on the server while the capability is unknown`() = runTest {
        val log = mutableListOf<String>()
        val port = FakeDeletionPort()
        val api = FakeApi(serverList = listOf(rec("gone", 2).copy(status = "revoked")), capability = revokeAware, log = log)
        DownloadsRepository(api, port, revokedCleanup = FakeCleanup(log, succeeds = false)).refresh(serverId = "s", profileId = "p")
        assertEquals(setOf("gone"), port.allPendingRecordIds())

        // After a restart the capability cannot be read; the tombstone must not replay.
        api.capability = null
        log.clear()
        val restarted = DownloadsRepository(api, port, revokedCleanup = FakeCleanup(log))
        restarted.refresh(serverId = "s", profileId = "p")

        assertTrue(api.deleteCalls.isEmpty())
        assertTrue(restarted.records.first().isEmpty())
        assertEquals(setOf("gone"), port.allPendingRecordIds())
    }

    @Test
    fun `without revoked_removes_local a revoked entry keeps its local copy`() = runTest {
        val log = mutableListOf<String>()
        val api = FakeApi(serverList = listOf(rec("gone", 2).copy(status = "revoked")),
            capability = revokeAware.copy(revokedRemovesLocal = false), log = log)
        val repo = DownloadsRepository(api, FakeDeletionPort(), revokedCleanup = FakeCleanup(log))

        repo.refresh(serverId = "s", profileId = "p")

        assertTrue(log.isEmpty())
        assertEquals(listOf("gone"), repo.records.first().map { it.id })
    }
}
