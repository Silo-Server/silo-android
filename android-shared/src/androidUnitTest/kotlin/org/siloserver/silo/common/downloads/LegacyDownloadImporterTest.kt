package org.siloserver.silo.common.downloads

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.siloserver.silo.common.data.db.SiloDatabase
import org.siloserver.silo.model.download.DownloadRecord
import org.siloserver.silo.model.download.DownloadSidecar
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class LegacyDownloadImporterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        SiloDatabase::class.java,
    ).allowMainThreadQueries().build()

    private val store = DownloadMetadataStore(db)
    private val json = Json { ignoreUnknownKeys = true }

    @AfterTest
    fun tearDown() = db.close()

    private fun importer(baseDir: File) = LegacyDownloadImporter(baseDir, db)

    private fun sidecar(fileId: Int, contentId: String = "tt$fileId"): DownloadSidecar =
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

    private fun writeLegacySidecar(baseDir: File, serverId: String, profileId: String, sidecar: DownloadSidecar) {
        val dir = File(baseDir, "downloads/$serverId/$profileId").apply { mkdirs() }
        File(dir, "${sidecar.record.mediaFileId}.record.json").writeText(json.encodeToString(sidecar))
    }

    @Test
    fun `imports legacy sidecars into Room and drains the tree`() = runTest {
        val baseDir = tmp.newFolder("filesDir")
        writeLegacySidecar(baseDir, "srv1", "profA", sidecar(1))
        writeLegacySidecar(baseDir, "srv1", "profB", sidecar(2))
        writeLegacySidecar(baseDir, "srv2", "profA", sidecar(3, contentId = "movie-x"))

        importer(baseDir).importIfNeeded(nowMs = 1_000L)

        // Metadata is now readable from Room.
        assertEquals("tt1", store.readSidecar("srv1", "", "profA", 1)?.record?.contentId)
        assertEquals("tt2", store.readSidecar("srv1", "", "profB", 2)?.record?.contentId)
        assertEquals("movie-x", store.readSidecar("srv2", "", "profA", 3)?.record?.contentId)

        // The legacy tree drained.
        assertFalse(File(baseDir, "downloads").exists())

        // Each import is audited.
        assertEquals(3, db.legacyImportDao().getByKind("download_sidecar").count { it.status == "imported" })
    }

    @Test
    fun `room rows win over frozen sidecars but the stale file is still removed`() = runTest {
        val baseDir = tmp.newFolder("filesDir")
        // Room already has a (newer) row for this scope/fileId.
        store.writeSidecar("srv1", "", "profA", sidecar(7, contentId = "room-newer"))
        // A frozen legacy sidecar for the same key exists on disk.
        writeLegacySidecar(baseDir, "srv1", "profA", sidecar(7, contentId = "disk-stale"))

        importer(baseDir).importIfNeeded(nowMs = 1_000L)

        // Room's value is preserved (not overwritten by the stale sidecar)...
        assertEquals("room-newer", store.readSidecar("srv1", "", "profA", 7)?.record?.contentId)
        // ...and the stale sidecar is drained.
        assertFalse(File(baseDir, "downloads/srv1/profA/7.record.json").exists())
        assertEquals(1, db.legacyImportDao().getByKind("download_sidecar").count { it.status == "skipped" })
    }

    @Test
    fun `malformed sidecar is recorded as failed and left in place`() = runTest {
        val baseDir = tmp.newFolder("filesDir")
        val dir = File(baseDir, "downloads/srv1/profA").apply { mkdirs() }
        val bad = File(dir, "9.record.json").apply { writeText("{ not valid json") }

        importer(baseDir).importIfNeeded(nowMs = 1_000L)

        assertNull(store.readSidecar("srv1", "", "profA", 9))
        assertEquals(true, bad.exists()) // not deleted — preserved for recovery
        val failed = db.legacyImportDao().getByKind("download_sidecar").single { it.status == "failed" }
        assertNotNull(failed.error)
    }

    @Test
    fun `no-op when there is no downloads tree`() = runTest {
        val baseDir = tmp.newFolder("filesDir")
        importer(baseDir).importIfNeeded(nowMs = 1_000L)
        assertEquals(0, db.legacyImportDao().count())
    }

    @Test
    fun `sidecar whose filename id mismatches its record is failed and left in place`() = runTest {
        val baseDir = tmp.newFolder("filesDir")
        // Filename says 5 but the record's mediaFileId is 7 — misnamed/corrupt.
        val dir = File(baseDir, "downloads/srv1/profA").apply { mkdirs() }
        val mismatched = File(dir, "5.record.json").apply { writeText(json.encodeToString(sidecar(7))) }

        importer(baseDir).importIfNeeded(nowMs = 1_000L)

        assertNull(store.readSidecar("srv1", "", "profA", 7))
        assertNull(store.readSidecar("srv1", "", "profA", 5))
        assertEquals(true, mismatched.exists())
        assertEquals(
            "file_id_mismatch",
            db.legacyImportDao().getByKind("download_sidecar").single { it.status == "failed" }.error,
        )
    }

    @Test
    fun `sidecar at the wrong path depth is failed and left in place`() = runTest {
        val baseDir = tmp.newFolder("filesDir")
        // Too deep: downloads/srv1/profA/extra/7.record.json
        val dir = File(baseDir, "downloads/srv1/profA/extra").apply { mkdirs() }
        val tooDeep = File(dir, "7.record.json").apply { writeText(json.encodeToString(sidecar(7))) }

        importer(baseDir).importIfNeeded(nowMs = 1_000L)

        assertNull(store.readSidecar("srv1", "", "profA", 7))
        assertEquals(true, tooDeep.exists())
        assertEquals(
            "unexpected_path_depth",
            db.legacyImportDao().getByKind("download_sidecar").single { it.status == "failed" }.error,
        )
    }

    @Test
    fun `awaitImport runs at most once`() = runTest {
        val baseDir = tmp.newFolder("filesDir")
        writeLegacySidecar(baseDir, "srv1", "profA", sidecar(1))
        val importer = importer(baseDir)

        importer.awaitImport(nowMs = 1_000L)
        // Recreate the sidecar; a second await must NOT re-import (memoized).
        writeLegacySidecar(baseDir, "srv1", "profA", sidecar(2))
        importer.awaitImport(nowMs = 2_000L)

        assertNotNull(store.readSidecar("srv1", "", "profA", 1))
        assertNull(store.readSidecar("srv1", "", "profA", 2))
    }
}
