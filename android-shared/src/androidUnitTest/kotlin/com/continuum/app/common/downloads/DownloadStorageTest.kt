package com.continuum.app.common.downloads

import com.continuum.app.model.download.DownloadMediaType
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadStorageTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newStorage() = DownloadStorage(tmp.newFolder("filesDir"))

    @Test
    fun `prepareWrite uses original filename as display name`() {
        val storage = newStorage()
        val target = storage.prepareWrite("srv1", "profA", 42, fileName = "The Book.epub")

        assertEquals("The Book.epub", target.displayName)
    }

    @Test
    fun `file backed download uris encode paths with spaces and uri reserved characters`() {
        val storage = newStorage()
        val target = storage.prepareWrite("srv1", "profA", 42, fileName = "The #1 Book.epub")
        target.writeTargetBytes(ByteArray(10))

        assertEquals("The #1 Book.epub", storage.locateLocalMedia("srv1", "profA", 42)?.displayName)
        assertTrue(target.uriString.startsWith("file://"))
        assertTrue(target.uriString.contains("The%20%231%20Book.epub"))
        assertFalse(target.uriString.contains("The #1 Book.epub"))
    }

    @Test
    fun `prepareWrite falls back to container extension when filename is unavailable`() {
        val storage = newStorage()
        val target = storage.prepareWrite("srv1", "profA", 42, container = "m4b")

        assertEquals("42.m4b", target.displayName)
    }

    @Test
    fun `prepareWrite sanitizes unsupported filename characters but keeps extension`() {
        val storage = newStorage()
        val target = storage.prepareWrite("srv1", "profA", 42, fileName = "../bad:name?.pdf")

        assertEquals("bad_name_.pdf", target.displayName)
    }

    @Test
    fun `prepareWrite routes video downloads into video collection`() {
        val storage = newStorage()
        val target = storage.prepareWrite(
            serverId = "srv1",
            profileId = "profA",
            fileId = 42,
            fileName = "Movie.mkv",
            mediaType = DownloadMediaType.Movie.wire,
        )

        assertTrue(target.uriString.contains("/Movies/"))
    }

    @Test
    fun `prepareWrite routes audiobook downloads into audio collection`() {
        val storage = newStorage()
        val target = storage.prepareWrite(
            serverId = "srv1",
            profileId = "profA",
            fileId = 42,
            fileName = "Book.m4b",
            mediaType = DownloadMediaType.Audiobook.wire,
        )

        assertTrue(target.uriString.contains("/Music/"))
    }

    @Test
    fun `prepareWrite routes ebook downloads into downloads collection`() {
        val storage = newStorage()
        val target = storage.prepareWrite(
            serverId = "srv1",
            profileId = "profA",
            fileId = 42,
            fileName = "Book.epub",
            mediaType = DownloadMediaType.Ebook.wire,
        )

        assertTrue(target.uriString.contains("/Downloads/"))
    }

    @Test
    fun `file backed store can route collections into distinct public roots`() {
        val downloadsRoot = tmp.newFolder("public-downloads")
        val moviesRoot = tmp.newFolder("public-movies")
        val musicRoot = tmp.newFolder("public-music")
        val storage = DownloadStorage(
            baseDir = tmp.newFolder("filesDir"),
            publicStore = FileBackedPublicDownloadStore(
                collectionRoots = mapOf(
                    PublicDownloadCollection.Downloads to downloadsRoot,
                    PublicDownloadCollection.Video to moviesRoot,
                    PublicDownloadCollection.Audio to musicRoot,
                ),
            ),
        )

        val movie = storage.prepareWrite("srv1", "profA", 10, fileName = "Movie.mkv", mediaType = DownloadMediaType.Movie.wire)
        val audiobook = storage.prepareWrite("srv1", "profA", 11, fileName = "Book.m4b", mediaType = DownloadMediaType.Audiobook.wire)
        val ebook = storage.prepareWrite("srv1", "profA", 12, fileName = "Book.epub", mediaType = DownloadMediaType.Ebook.wire)

        assertTrue(movie.uriString.contains(moviesRoot.name))
        assertFalse(movie.uriString.contains(downloadsRoot.name))
        assertTrue(audiobook.uriString.contains(musicRoot.name))
        assertFalse(audiobook.uriString.contains(downloadsRoot.name))
        assertTrue(ebook.uriString.contains(downloadsRoot.name))
    }

    @Test
    fun `locateLocalFile finds original-format download only`() {
        val storage = newStorage()
        storage.prepareWrite("srv1", "profA", 42, fileName = "The Book.epub").writeTargetBytes(ByteArray(10))

        assertEquals("The Book.epub", storage.locateLocalFile("srv1", "profA", 42)?.name)
    }

    @Test
    fun `completeWrite notifies file backed public store with completed file`() {
        var completedFile: java.io.File? = null
        val storage = DownloadStorage(
            baseDir = tmp.newFolder("filesDir"),
            publicStore = FileBackedPublicDownloadStore(
                root = tmp.newFolder("public"),
                onFileCompleted = { completedFile = it },
            ),
        )
        val target = storage.prepareWrite("srv1", "profA", 42, fileName = "The Book.epub")
        target.writeTargetBytes(ByteArray(10))

        storage.completeWrite(target.uriString)

        assertEquals("The Book.epub", completedFile?.name)
    }

    @Test
    fun `prepareWrite refuses to invent a non-original download extension`() {
        val storage = newStorage()

        assertFailsWith<IllegalArgumentException> {
            storage.prepareWrite("srv1", "profA", 7)
        }
    }

    @Test
    fun `prepareWrite creates parent directories and returns target when original container is known`() {
        val storage = newStorage()
        val target = storage.prepareWrite("srv1", "profA", 7, container = "epub")
        assertTrue(target.uriString.startsWith("file://"))
        assertEquals("7.epub", target.displayName)
    }

    @Test
    fun `exists round-trips with a real write`() {
        val storage = newStorage()
        assertFalse(storage.exists("srv1", "profA", 1))
        val f = storage.prepareWrite("srv1", "profA", 1, fileName = "Movie.mkv")
        f.writeTargetBytes(ByteArray(256))
        assertTrue(storage.exists("srv1", "profA", 1))
    }

    @Test
    fun `locateLocalMedia resolves bytes by file id`() {
        val storage = newStorage()
        storage.prepareWrite("srv1", "profA", 42, fileName = "Recovered.epub").writeTargetBytes(ByteArray(10))

        assertEquals("Recovered.epub", storage.locateLocalMedia("srv1", "profA", 42)?.displayName)
    }

    @Test
    fun `delete removes only the targeted file`() {
        val storage = newStorage()
        storage.prepareWrite("srv1", "profA", 1, fileName = "One.mkv").writeTargetBytes(ByteArray(10))
        storage.prepareWrite("srv1", "profA", 2, fileName = "Two.mkv").writeTargetBytes(ByteArray(10))

        assertTrue(storage.delete("srv1", "profA", 1))
        assertFalse(storage.exists("srv1", "profA", 1))
        assertTrue(storage.exists("srv1", "profA", 2))
        // Re-delete returns false (nothing to remove).
        assertFalse(storage.delete("srv1", "profA", 1))
    }

    @Test
    fun `deleteAllForProfile isolates other profiles on the same server`() {
        val storage = newStorage()
        storage.prepareWrite("srv1", "profA", 1, fileName = "One.mkv", mediaType = DownloadMediaType.Movie.wire)
            .writeTargetBytes(ByteArray(10))
        storage.prepareWrite("srv1", "profA", 2, fileName = "Two.m4b", mediaType = DownloadMediaType.Audiobook.wire)
            .writeTargetBytes(ByteArray(10))
        storage.prepareWrite("srv1", "profB", 3, fileName = "Three.epub", mediaType = DownloadMediaType.Ebook.wire)
            .writeTargetBytes(ByteArray(10))

        assertTrue(storage.deleteAllForProfile("srv1", "profA"))
        assertFalse(storage.exists("srv1", "profA", 1))
        assertFalse(storage.exists("srv1", "profA", 2))
        assertTrue(storage.exists("srv1", "profB", 3))  // untouched
    }

    @Test
    fun `deleteAllForServer wipes everything under a server`() {
        val storage = newStorage()
        storage.prepareWrite("srv1", "profA", 1, fileName = "One.mkv", mediaType = DownloadMediaType.Movie.wire)
            .writeTargetBytes(ByteArray(10))
        storage.prepareWrite("srv1", "profB", 2, fileName = "Two.m4b", mediaType = DownloadMediaType.Audiobook.wire)
            .writeTargetBytes(ByteArray(10))
        storage.prepareWrite("srv2", "profA", 3, fileName = "Three.epub", mediaType = DownloadMediaType.Ebook.wire)
            .writeTargetBytes(ByteArray(10))

        assertTrue(storage.deleteAllForServer("srv1"))
        assertFalse(storage.exists("srv1", "profA", 1))
        assertFalse(storage.exists("srv1", "profB", 2))
        assertTrue(storage.exists("srv2", "profA", 3))
    }

    @Test
    fun `totalBytesUsed sums every downloaded file under the root`() {
        val storage = newStorage()
        storage.prepareWrite("srv1", "profA", 1, fileName = "One.mkv").writeTargetBytes(ByteArray(100))
        storage.prepareWrite("srv1", "profB", 2, fileName = "Two.mkv").writeTargetBytes(ByteArray(250))
        storage.prepareWrite("srv2", "profA", 3, fileName = "Three.mkv").writeTargetBytes(ByteArray(50))
        assertEquals(400L, storage.totalBytesUsed())
    }

    @Test
    fun `scoped totalBytesUsed sums only one server profile`() {
        val storage = newStorage()
        storage.prepareWrite("srv1", "profA", 1, fileName = "One.mkv").writeTargetBytes(ByteArray(100))
        storage.prepareWrite("srv1", "profB", 2, fileName = "Two.mkv").writeTargetBytes(ByteArray(250))
        storage.prepareWrite("srv2", "profA", 3, fileName = "Three.mkv").writeTargetBytes(ByteArray(50))

        assertEquals(100L, storage.totalBytesUsed("srv1", "profA"))
    }

    @Test
    fun `totalBytesUsed is zero when no downloads exist`() {
        val storage = newStorage()
        assertEquals(0L, storage.totalBytesUsed())
    }

    @Test
    fun `media store relative path includes the file id directory`() {
        assertEquals(
            "Downloads/Silo/srv1/profA/42/",
            mediaStoreRelativePath(PublicDownloadCollection.Downloads, "srv1", "profA", 42),
        )
    }

    private fun DownloadTarget.writeTargetBytes(bytes: ByteArray) {
        openOutputStream().use { it.write(bytes) }
    }
}
