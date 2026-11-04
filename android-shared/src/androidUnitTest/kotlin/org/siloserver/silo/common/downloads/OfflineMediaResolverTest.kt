package org.siloserver.silo.common.downloads

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.siloserver.silo.common.data.db.SiloDatabase
import org.siloserver.silo.model.download.DownloadRecord
import org.siloserver.silo.model.download.DownloadSidecar
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The resolver now reads metadata from Room ([DownloadMetadataStore]) and bytes
 * from disk ([DownloadStorage]). Each scenario writes BOTH a metadata row and the
 * actual file, because [OfflineMediaResolver] requires both to surface a download.
 */
@RunWith(RobolectricTestRunner::class)
class OfflineMediaResolverTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        SiloDatabase::class.java,
    ).allowMainThreadQueries().build()

    private val metadata = DownloadMetadataStore(db)

    @AfterTest
    fun tearDown() = db.close()

    private fun newStorage() = DownloadStorage(tmp.newFolder("filesDir"))

    // Importer points at an empty dir → awaitImport is a no-op; these tests
    // populate Room directly via `metadata`.
    private fun resolver(storage: DownloadStorage) =
        OfflineMediaResolver(metadata, storage, LegacyDownloadImporter(tmp.newFolder(), db))

    @Test
    fun `findLocalMedia returns completed original-named download`() = runTest {
        val storage = newStorage()
        storage.writeCompleted("srv1", "profA", 42, "Novel.epub")
        metadata.writeSidecar("srv1", "acct", "profA", sidecar(42, contentId = "book-1", fileName = "Novel.epub"))

        val media = resolver(storage).findLocalMedia("srv1", "acct", "profA", "book-1", requestedFileId = 42)

        assertEquals("srv1", media?.serverId)
        assertEquals("profA", media?.profileId)
        assertEquals(42, media?.fileId)
        assertEquals("Novel.epub", media?.file?.name)
        assertEquals("file://${media?.file?.absolutePath}", media?.fileUrl)
    }

    @Test
    fun `findLocalMedia ignores incomplete downloads`() = runTest {
        val storage = newStorage()
        storage.prepareWrite("srv1", "acct", "profA", 42, fileName = "Novel.epub").writeTargetBytes(ByteArray(10))
        metadata.writeSidecar(
            "srv1",
            "acct",
            "profA",
            sidecar(42, contentId = "book-1", status = "downloading", fileName = "Novel.epub"),
        )

        assertNull(resolver(storage).findLocalMedia("srv1", "acct", "profA", "book-1", requestedFileId = 42))
    }

    @Test
    fun `findLocalMedia honors requested file id when fallback is disabled`() = runTest {
        val storage = newStorage()
        storage.writeCompleted("srv1", "profA", 1, "A.epub")
        metadata.writeSidecar("srv1", "acct", "profA", sidecar(1, contentId = "book-1", fileName = "A.epub"))

        assertNull(
            resolver(storage).findLocalMedia(
                serverId = "srv1",
                accountId = "acct",
                profileId = "profA",
                contentId = "book-1",
                requestedFileId = 2,
                allowFallback = false,
            ),
        )
    }

    @Test
    fun `findLocalMedia stays inside the requested server profile scope`() = runTest {
        val storage = newStorage()
        storage.writeCompleted("srv1", "profA", 42, "Wrong.epub")
        metadata.writeSidecar("srv1", "acct", "profA", sidecar(42, contentId = "book-1", fileName = "Wrong.epub"))
        storage.writeCompleted("srv1", "profB", 42, "Right.epub")
        metadata.writeSidecar("srv1", "acct", "profB", sidecar(42, contentId = "book-1", fileName = "Right.epub"))

        val media = resolver(storage).findLocalMedia(
            serverId = "srv1",
            accountId = "acct",
            profileId = "profB",
            contentId = "book-1",
            requestedFileId = 42,
        )

        assertEquals("profB", media?.profileId)
        assertEquals("Right.epub", media?.displayName)
    }

    @Test
    fun `listLocalMedia returns every completed local candidate for content`() = runTest {
        val storage = newStorage()
        storage.writeCompleted("srv1", "profA", 7, "Book.mobi")
        metadata.writeSidecar("srv1", "acct", "profA", sidecar(7, contentId = "book-1", fileName = "Book.mobi"))
        storage.writeCompleted("srv1", "profA", 8, "Book.epub")
        metadata.writeSidecar("srv1", "acct", "profA", sidecar(8, contentId = "book-1", fileName = "Book.epub"))
        storage.writeCompleted("srv1", "profA", 9, "Other.epub")
        metadata.writeSidecar("srv1", "acct", "profA", sidecar(9, contentId = "book-2", fileName = "Other.epub"))

        val media = resolver(storage).listLocalMedia("srv1", "acct", "profA", "book-1")

        // Only the two book-1 candidates surface; book-2 is filtered out.
        // (Order follows the DAO's updatedAtMs sort, so assert on the set.)
        assertEquals(setOf(7, 8), media.map { it.fileId }.toSet())
        assertEquals(setOf("Book.mobi", "Book.epub"), media.map { it.displayName }.toSet())
    }

    private fun sidecar(
        fileId: Int,
        contentId: String,
        status: String = "completed",
        fileName: String,
    ): DownloadSidecar =
        DownloadSidecar(
            record = DownloadRecord(
                id = "dl-$fileId",
                contentId = contentId,
                mediaFileId = fileId,
                fileSize = 10,
                bytesSent = 10,
                kind = "queued",
                status = status,
                createdAt = "2026-06-09T00:00:00Z",
            ),
            title = "Title",
            fileName = fileName,
            container = fileName.substringAfterLast('.', missingDelimiterValue = ""),
            updatedAtMs = 1L,
        )

    private fun DownloadTarget.writeTargetBytes(bytes: ByteArray) {
        openOutputStream().use { it.write(bytes) }
    }

    private fun DownloadStorage.writeCompleted(
        serverId: String,
        profileId: String,
        fileId: Int,
        fileName: String,
    ) {
        val target = prepareWrite(serverId, "acct", profileId, fileId, fileName = fileName)
        target.writeTargetBytes(ByteArray(10))
        completeWrite(target.uriString)
    }
}
