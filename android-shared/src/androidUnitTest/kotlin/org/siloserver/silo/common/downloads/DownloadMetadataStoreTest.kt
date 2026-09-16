package org.siloserver.silo.common.downloads

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.siloserver.silo.common.data.db.SiloDatabase
import org.siloserver.silo.model.download.DownloadRecord
import org.siloserver.silo.model.download.DownloadSidecar
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Room-backed metadata store — replaces the old on-disk `.record.json` sidecar
 * tree. These tests exercise the same scenarios the sidecar round-trip suite in
 * `DownloadStorageTest` used to cover, now against the [DownloadEntity] table.
 */
@RunWith(RobolectricTestRunner::class)
class DownloadMetadataStoreTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        SiloDatabase::class.java,
    ).allowMainThreadQueries().build()

    private val store = DownloadMetadataStore(db)

    @AfterTest
    fun tearDown() = db.close()

    private fun stubSidecar(fileId: Int, contentId: String = "tt$fileId"): DownloadSidecar =
        DownloadSidecar(
            record = DownloadRecord(
                id = "dl-$fileId",
                contentId = contentId,
                mediaFileId = fileId,
                fileSize = 1024,
                bytesSent = 1024,
                kind = "queued",
                status = "completed",
                createdAt = "2026-05-24T00:00:00Z",
            ),
            title = "Title $fileId",
            posterUrl = "https://example/p$fileId.jpg",
            updatedAtMs = 1716_500_000_000L,
        )

    @Test
    fun `writeSidecar then readSidecar round-trips`() = runTest {
        val sidecar = stubSidecar(7)
        store.writeSidecar("srv1", "profA", sidecar)

        val read = store.readSidecar("srv1", "profA", 7)
        assertNotNull(read)
        assertEquals(sidecar, read)
    }

    @Test
    fun `writeSidecar upserts on re-write`() = runTest {
        store.writeSidecar("srv1", "profA", stubSidecar(7, contentId = "old"))
        store.writeSidecar("srv1", "profA", stubSidecar(7, contentId = "new"))

        assertEquals("new", store.readSidecar("srv1", "profA", 7)?.record?.contentId)
        assertEquals(1, store.listSidecars("srv1", "profA").size)
    }

    @Test
    fun `readSidecar returns null when missing`() = runTest {
        assertNull(store.readSidecar("srv1", "profA", 99))
    }

    @Test
    fun `listAllSidecars walks every server-profile combination`() = runTest {
        store.writeSidecar("srv1", "profA", stubSidecar(1))
        store.writeSidecar("srv1", "profB", stubSidecar(2))
        store.writeSidecar("srv2", "profA", stubSidecar(3))

        val all = store.listAllSidecars().sortedBy { it.record.mediaFileId }
        assertEquals(listOf(1, 2, 3), all.map { it.record.mediaFileId })
    }

    @Test
    fun `listAllSidecarsWithScope reports the serverId and profileId each sidecar lives under`() = runTest {
        store.writeSidecar("srv1", "profA", stubSidecar(1))
        store.writeSidecar("srv2", "profB", stubSidecar(2))

        val scoped = store.listAllSidecarsWithScope()
            .associateBy { (_, _, sidecar) -> sidecar.record.mediaFileId }

        assertEquals(2, scoped.size)
        assertEquals("srv1" to "profA", scoped[1]?.let { it.first to it.second })
        assertEquals("srv2" to "profB", scoped[2]?.let { it.first to it.second })
    }

    @Test
    fun `listAllSidecars matches the scoped walk`() = runTest {
        store.writeSidecar("srv1", "profA", stubSidecar(1))
        store.writeSidecar("srv1", "profB", stubSidecar(2))

        assertEquals(
            store.listAllSidecarsWithScope().map { it.third }.toSet(),
            store.listAllSidecars().toSet(),
        )
    }

    @Test
    fun `listSidecars returns only the requested server profile scope`() = runTest {
        store.writeSidecar("srv1", "profA", stubSidecar(1))
        store.writeSidecar("srv1", "profB", stubSidecar(2))
        store.writeSidecar("srv2", "profA", stubSidecar(3))

        assertEquals(
            listOf(1),
            store.listSidecars("srv1", "profA").map { it.record.mediaFileId },
        )
    }

    @Test
    fun `scoped lookup does not return same file id from another profile`() = runTest {
        store.writeSidecar("srv1", "profA", stubSidecar(7, contentId = "wrong"))
        store.writeSidecar("srv1", "profB", stubSidecar(7, contentId = "right"))

        assertEquals(
            "right",
            store.locateSidecarByFileId("srv1", "profB", 7)?.third?.record?.contentId,
        )
        assertNull(store.locateSidecarByFileId("srv2", "profB", 7))
    }

    @Test
    fun `locateSidecarByFileId finds across scopes`() = runTest {
        store.writeSidecar("srv2", "profB", stubSidecar(7, contentId = "found"))

        val located = store.locateSidecarByFileId(7)
        assertNotNull(located)
        assertEquals("srv2", located.first)
        assertEquals("profB", located.second)
        assertEquals("found", located.third.record.contentId)
        assertNull(store.locateSidecarByFileId(99))
    }

    @Test
    fun `findSidecarByContentId locates across server-profile combinations`() = runTest {
        store.writeSidecar("srv1", "profA", stubSidecar(1, contentId = "tt-A"))
        store.writeSidecar("srv2", "profB", stubSidecar(2, contentId = "tt-B"))

        val found = store.findSidecarByContentId("tt-B")
        assertNotNull(found)
        assertEquals(2, found.record.mediaFileId)
        assertNull(store.findSidecarByContentId("tt-missing"))
    }

    @Test
    fun `deleteSidecar removes only the targeted sidecar`() = runTest {
        store.writeSidecar("srv1", "profA", stubSidecar(1))
        store.writeSidecar("srv1", "profA", stubSidecar(2))

        store.deleteSidecar("srv1", "profA", 1)
        assertNull(store.readSidecar("srv1", "profA", 1))
        assertNotNull(store.readSidecar("srv1", "profA", 2))
    }

    @Test
    fun `deleteAllForProfile isolates other profiles on the same server`() = runTest {
        store.writeSidecar("srv1", "profA", stubSidecar(1))
        store.writeSidecar("srv1", "profA", stubSidecar(2))
        store.writeSidecar("srv1", "profB", stubSidecar(3))

        store.deleteAllForProfile("srv1", "profA")
        assertEquals(emptyList(), store.listSidecars("srv1", "profA"))
        assertEquals(listOf(3), store.listSidecars("srv1", "profB").map { it.record.mediaFileId })
    }

    @Test
    fun `deleteAllForServer wipes everything under a server`() = runTest {
        store.writeSidecar("srv1", "profA", stubSidecar(1))
        store.writeSidecar("srv1", "profB", stubSidecar(2))
        store.writeSidecar("srv2", "profA", stubSidecar(3))

        store.deleteAllForServer("srv1")
        assertEquals(emptyList(), store.listSidecars("srv1", "profA"))
        assertEquals(emptyList(), store.listSidecars("srv1", "profB"))
        assertEquals(listOf(3), store.listSidecars("srv2", "profA").map { it.record.mediaFileId })
    }
    @Test fun `old deletion cannot erase a replacement in the same file slot`() = runTest {
        val old = stubSidecar(7)
        val replacement = old.copy(record = old.record.copy(id = "replacement"))
        store.writeSidecar("server", "profile", replacement)
        var deletedBytes = false
        assertFalse(store.completePendingDeletion("server", "profile", 7, old.record.id) {
            deletedBytes = true; true
        })
        assertFalse(deletedBytes)
        assertEquals(replacement, store.readSidecar("server", "profile", 7))
    }

    @Test fun `matching cleanup removes metadata only after byte deletion succeeds`() = runTest {
        val row = stubSidecar(7)
        store.writeSidecar("server", "profile", row)
        assertFalse(store.completePendingDeletion("server", "profile", 7, row.record.id) { false })
        assertNotNull(store.readSidecar("server", "profile", 7))
        assertTrue(store.completePendingDeletion("server", "profile", 7, row.record.id) { true })
        assertNull(store.readSidecar("server", "profile", 7))
    }

    @Test fun `missing or other profile metadata cannot authorize byte cleanup`() = runTest {
        val row = stubSidecar(7)
        store.writeSidecar("server", "profile", row)
        assertFalse(store.completePendingDeletion("server", "other-profile", 7, row.record.id) {
            error("Another profile's bytes must not be touched")
        })
        assertFalse(store.completePendingDeletion("server", "profile", 8, row.record.id) {
            error("Missing metadata cannot prove byte ownership")
        })
        assertNotNull(store.readSidecar("server", "profile", 7))
    }

}
