package org.siloserver.silo.common.downloads

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.siloserver.silo.common.data.db.SiloDatabase
import org.siloserver.silo.model.download.DownloadRecord
import org.siloserver.silo.model.download.DownloadSidecar
import org.siloserver.silo.model.catalog.LeafItemUserData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.siloserver.silo.common.data.db.dao.DownloadArtworkRow
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
        store.writeSidecar("srv1", "acct", "profA", sidecar)

        val read = store.readSidecar("srv1", "acct", "profA", 7)
        assertNotNull(read)
        assertEquals(sidecar, read)
    }

    @Test
    fun `episode artwork provenance and watch state survive Room storage`() = runTest {
        for (isStill in listOf(true, false, null)) {
            val sidecar = stubSidecar(7).copy(
                posterIsEpisodeStill = isStill,
                episodeUserData = LeafItemUserData(played = false, isInProgress = true, positionSeconds = 42.0),
            )
            store.writeSidecar("srv1", "acct", "profA", sidecar)
            assertEquals(sidecar, store.readSidecar("srv1", "acct", "profA", 7))
        }
    }

    @Test
    fun `writeSidecar upserts on re-write`() = runTest {
        store.writeSidecar("srv1", "acct", "profA", stubSidecar(7, contentId = "old"))
        store.writeSidecar("srv1", "acct", "profA", stubSidecar(7, contentId = "new"))

        assertEquals("new", store.readSidecar("srv1", "acct", "profA", 7)?.record?.contentId)
        assertEquals(1, store.listSidecars("srv1", "acct", "profA").size)
    }

    @Test
    fun `readSidecar returns null when missing`() = runTest {
        assertNull(store.readSidecar("srv1", "acct", "profA", 99))
    }

    @Test
    fun `listAllSidecars walks every server-profile combination`() = runTest {
        store.writeSidecar("srv1", "acct", "profA", stubSidecar(1))
        store.writeSidecar("srv1", "acct", "profB", stubSidecar(2))
        store.writeSidecar("srv2", "acct", "profA", stubSidecar(3))

        val all = store.listAllSidecars().sortedBy { it.record.mediaFileId }
        assertEquals(listOf(1, 2, 3), all.map { it.record.mediaFileId })
    }

    @Test
    fun `listAllSidecarsWithScope reports the serverId and profileId each sidecar lives under`() = runTest {
        store.writeSidecar("srv1", "acct", "profA", stubSidecar(1))
        store.writeSidecar("srv2", "acct", "profB", stubSidecar(2))

        val scoped = store.listAllSidecarsWithScope()
            .associateBy { (_, _, _, sidecar) -> sidecar.record.mediaFileId }

        assertEquals(2, scoped.size)
        assertEquals("srv1" to "profA", scoped[1]?.let { it.serverId to it.profileId })
        assertEquals("srv2" to "profB", scoped[2]?.let { it.serverId to it.profileId })
    }

    @Test
    fun `listAllSidecars matches the scoped walk`() = runTest {
        store.writeSidecar("srv1", "acct", "profA", stubSidecar(1))
        store.writeSidecar("srv1", "acct", "profB", stubSidecar(2))

        assertEquals(
            store.listAllSidecarsWithScope().map { it.sidecar }.toSet(),
            store.listAllSidecars().toSet(),
        )
    }

    @Test
    fun `listSidecars returns only the requested server profile scope`() = runTest {
        store.writeSidecar("srv1", "acct", "profA", stubSidecar(1))
        store.writeSidecar("srv1", "acct", "profB", stubSidecar(2))
        store.writeSidecar("srv2", "acct", "profA", stubSidecar(3))

        assertEquals(
            listOf(1),
            store.listSidecars("srv1", "acct", "profA").map { it.record.mediaFileId },
        )
    }

    @Test
    fun `scoped lookup does not return same file id from another profile`() = runTest {
        store.writeSidecar("srv1", "acct", "profA", stubSidecar(7, contentId = "wrong"))
        store.writeSidecar("srv1", "acct", "profB", stubSidecar(7, contentId = "right"))

        assertEquals(
            "right",
            store.locateSidecarByFileId("srv1", "acct", "profB", 7)?.sidecar?.record?.contentId,
        )
        assertNull(store.locateSidecarByFileId("srv2", "acct", "profB", 7))
    }

    @Test
    fun `locateSidecarByFileId finds across scopes`() = runTest {
        store.writeSidecar("srv2", "acct", "profB", stubSidecar(7, contentId = "found"))

        val located = store.locateSidecarByFileId(7)
        assertNotNull(located)
        assertEquals("srv2", located.serverId)
        assertEquals("profB", located.profileId)
        assertEquals("found", located.sidecar.record.contentId)
        assertNull(store.locateSidecarByFileId(99))
    }

    @Test
    fun `deleteSidecar removes only the targeted sidecar`() = runTest {
        store.writeSidecar("srv1", "acct", "profA", stubSidecar(1))
        store.writeSidecar("srv1", "acct", "profA", stubSidecar(2))

        store.deleteSidecar("srv1", "acct", "profA", 1)
        assertNull(store.readSidecar("srv1", "acct", "profA", 1))
        assertNotNull(store.readSidecar("srv1", "acct", "profA", 2))
    }

    @Test
    fun `deleteAllForProfile isolates other profiles on the same server`() = runTest {
        store.writeSidecar("srv1", "acct", "profA", stubSidecar(1))
        store.writeSidecar("srv1", "acct", "profA", stubSidecar(2))
        store.writeSidecar("srv1", "acct", "profB", stubSidecar(3))

        store.deleteAllForProfile("srv1", "acct", "profA")
        assertEquals(emptyList(), store.listSidecars("srv1", "acct", "profA"))
        assertEquals(listOf(3), store.listSidecars("srv1", "acct", "profB").map { it.record.mediaFileId })
    }

    @Test
    fun `deleteAllForServer wipes everything under a server`() = runTest {
        store.writeSidecar("srv1", "acct", "profA", stubSidecar(1))
        store.writeSidecar("srv1", "acct", "profB", stubSidecar(2))
        store.writeSidecar("srv2", "acct", "profA", stubSidecar(3))

        store.deleteAllForServer("srv1")
        assertEquals(emptyList(), store.listSidecars("srv1", "acct", "profA"))
        assertEquals(emptyList(), store.listSidecars("srv1", "acct", "profB"))
        assertEquals(listOf(3), store.listSidecars("srv2", "acct", "profA").map { it.record.mediaFileId })
    }
    @Test fun `old deletion cannot erase a replacement in the same file slot`() = runTest {
        val old = stubSidecar(7)
        val replacement = old.copy(record = old.record.copy(id = "replacement"))
        store.writeSidecar("server", "acct", "profile", replacement)
        var deletedBytes = false
        assertFalse(store.completePendingDeletion("server", "acct", "profile", 7, old.record.id) {
            deletedBytes = true; true
        })
        assertFalse(deletedBytes)
        assertEquals(replacement, store.readSidecar("server", "acct", "profile", 7))
    }

    @Test fun `a conditional update leaves a replaced or deleted row as it is`() = runTest {
        val old = stubSidecar(7)
        val replacement = old.copy(record = old.record.copy(id = "replacement"))
        store.writeSidecar("server", "acct", "profile", replacement)
        assertFalse(store.updateSidecarIf("server", "acct", "profile", 7, matches = { it.record.id == old.record.id }) {
            error("A replaced row must not be updated")
        })
        assertEquals(replacement, store.readSidecar("server", "acct", "profile", 7))

        assertTrue(store.updateSidecarIf("server", "acct", "profile", 7, matches = { it.record.id == "replacement" }) {
            it.copy(updatedAtMs = 42)
        })
        assertEquals(42, store.readSidecar("server", "acct", "profile", 7)?.updatedAtMs)

        store.deleteSidecar("server", "acct", "profile", 7)
        assertFalse(store.updateSidecarIf("server", "acct", "profile", 7, matches = { true }) { it })
        assertNull(store.readSidecar("server", "acct", "profile", 7))
    }

    @Test fun `matching cleanup removes metadata only after byte deletion succeeds`() = runTest {
        val row = stubSidecar(7)
        store.writeSidecar("server", "acct", "profile", row)
        assertFalse(store.completePendingDeletion("server", "acct", "profile", 7, row.record.id) { false })
        assertNotNull(store.readSidecar("server", "acct", "profile", 7))
        assertTrue(store.completePendingDeletion("server", "acct", "profile", 7, row.record.id) { true })
        assertNull(store.readSidecar("server", "acct", "profile", 7))
    }

    @Test fun `missing or other profile metadata cannot authorize byte cleanup`() = runTest {
        val row = stubSidecar(7)
        store.writeSidecar("server", "acct", "profile", row)
        assertFalse(store.completePendingDeletion("server", "acct", "other-profile", 7, row.record.id) {
            error("Another profile's bytes must not be touched")
        })
        assertFalse(store.completePendingDeletion("server", "acct", "profile", 8, row.record.id) {
            error("Missing metadata cannot prove byte ownership")
        })
        assertNotNull(store.readSidecar("server", "acct", "profile", 7))
    }


    @Test
    fun `savedArtworkChanges emits on artwork writes and not on other column writes`() = runBlocking {
        val emissions = Channel<List<DownloadArtworkRow>>(Channel.UNLIMITED)
        val collector = launch(Dispatchers.Default) { store.savedArtworkChanges().collect { emissions.send(it) } }
        suspend fun next() = withTimeout(10_000) { emissions.receive() }
        try {
            assertEquals(emptyList(), next())

            val downloading = stubSidecar(7).let {
                it.copy(record = it.record.copy(status = "downloading", bytesSent = 10))
            }
            // A new row and progress ticks carry no artwork.
            store.writeSidecar("srv1", "acct", "profA", downloading)
            store.writeSidecar("srv1", "acct", "profA", downloading.copy(record = downloading.record.copy(bytesSent = 20)))
            val completed = stubSidecar(7).copy(offlinePosterPath = "/art/poster")
            store.writeSidecar("srv1", "acct", "profA", completed)
            assertEquals(listOf(DownloadArtworkRow("dl-7", null, "/art/poster", null, null)), next())

            // Status and title rewrites keep the artwork as it was.
            store.writeSidecar("srv1", "acct", "profA", completed.copy(title = "Renamed", updatedAtMs = 2L))
            store.writeSidecar("srv1", "acct", "profA", completed.copy(offlineSeriesPosterPath = "/art/series_poster"))
            // Ordered invalidation: had the rename emitted, it would be received here.
            assertEquals(
                listOf(DownloadArtworkRow("dl-7", null, "/art/poster", "/art/series_poster", null)),
                next(),
            )
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun `savedArtworkChanges emits when only the poster thumbhash is saved`() = runBlocking {
        val emissions = Channel<List<DownloadArtworkRow>>(Channel.UNLIMITED)
        val collector = launch(Dispatchers.Default) { store.savedArtworkChanges().collect { emissions.send(it) } }
        suspend fun next() = withTimeout(10_000) { emissions.receive() }
        try {
            assertEquals(emptyList(), next())

            // A completed movie queued without a hash, whose poster fetch failed:
            // the capture saves only the manifest's ThumbHash.
            val completed = stubSidecar(7)
            store.writeSidecar("srv1", "acct", "profA", completed)
            store.writeSidecar("srv1", "acct", "profA", completed.copy(posterThumbhash = "hash"))
            assertEquals(listOf(DownloadArtworkRow("dl-7", "hash", null, null, null)), next())
        } finally {
            collector.cancel()
        }
    }
}
