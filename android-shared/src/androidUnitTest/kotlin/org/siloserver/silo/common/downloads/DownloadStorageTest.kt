package org.siloserver.silo.common.downloads

import org.siloserver.silo.model.download.DownloadMediaType
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URI
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DownloadStorageTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newStorage() = DownloadStorage(tmp.newFolder("filesDir"))

    @Test
    fun `prepareWrite uses original filename as display name`() {
        val storage = newStorage()
        val target = storage.prepareWrite("srv1", PRE_ACCOUNT, "profA", 42, fileName = "The Book.epub")

        assertEquals("The Book.epub", target.displayName)
    }

    @Test
    fun `file backed download uris encode paths with spaces and uri reserved characters`() {
        val storage = newStorage()
        val target = storage.prepareWrite("srv1", PRE_ACCOUNT, "profA", 42, fileName = "The #1 Book.epub")
        target.writeTargetBytes(ByteArray(10))

        assertEquals("The #1 Book.epub", storage.locateLocalMedia("srv1", PRE_ACCOUNT, "profA", 42)?.displayName)
        assertTrue(target.uriString.startsWith("file://"))
        assertTrue(target.uriString.contains("The%20%231%20Book.epub"))
        assertFalse(target.uriString.contains("The #1 Book.epub"))
    }

    @Test
    fun `prepareWrite falls back to container extension when filename is unavailable`() {
        val storage = newStorage()
        val target = storage.prepareWrite("srv1", PRE_ACCOUNT, "profA", 42, container = "m4b")

        assertEquals("42.m4b", target.displayName)
    }

    @Test
    fun `prepareWrite sanitizes unsupported filename characters but keeps extension`() {
        val storage = newStorage()
        val target = storage.prepareWrite("srv1", PRE_ACCOUNT, "profA", 42, fileName = "../bad:name?.pdf")

        assertEquals("bad_name_.pdf", target.displayName)
    }

    @Test
    fun `prepareWrite routes video downloads into video collection`() {
        val storage = newStorage()
        val target = storage.prepareWrite(
            serverId = "srv1",
            accountId = PRE_ACCOUNT,
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
            accountId = PRE_ACCOUNT,
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
            accountId = PRE_ACCOUNT,
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

        val movie = storage.prepareWrite("srv1", PRE_ACCOUNT, "profA", 10, fileName = "Movie.mkv", mediaType = DownloadMediaType.Movie.wire)
        val audiobook = storage.prepareWrite("srv1", PRE_ACCOUNT, "profA", 11, fileName = "Book.m4b", mediaType = DownloadMediaType.Audiobook.wire)
        val ebook = storage.prepareWrite("srv1", PRE_ACCOUNT, "profA", 12, fileName = "Book.epub", mediaType = DownloadMediaType.Ebook.wire)

        assertTrue(movie.uriString.contains(moviesRoot.name))
        assertFalse(movie.uriString.contains(downloadsRoot.name))
        assertTrue(audiobook.uriString.contains(musicRoot.name))
        assertFalse(audiobook.uriString.contains(downloadsRoot.name))
        assertTrue(ebook.uriString.contains(downloadsRoot.name))
    }

    @Test
    fun `locateLocalFile finds original-format download only`() {
        val storage = newStorage()
        val target = storage.prepareWrite("srv1", PRE_ACCOUNT, "profA", 42, fileName = "The Book.epub")
        target.writeTargetBytes(ByteArray(10))
        storage.completeWrite(target.uriString)

        assertEquals("The Book.epub", storage.locateLocalFile("srv1", PRE_ACCOUNT, "profA", 42)?.name)
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
        val target = storage.prepareWrite("srv1", PRE_ACCOUNT, "profA", 42, fileName = "The Book.epub")
        target.writeTargetBytes(ByteArray(10))

        storage.completeWrite(target.uriString)

        assertEquals("The Book.epub", completedFile?.name)
    }

    @Test
    fun `staged download completion survives file backed store recreation`() {
        val publicRoot = tmp.newFolder("public")
        val firstStorage = DownloadStorage(
            baseDir = tmp.newFolder("first-files"),
            publicStore = FileBackedPublicDownloadStore(publicRoot),
        )
        val target = firstStorage.prepareWrite("srv1", PRE_ACCOUNT, "profA", 42, fileName = "The Book.epub")
        target.writeTargetBytes(ByteArray(10))

        val recreatedStorage = DownloadStorage(
            baseDir = tmp.newFolder("second-files"),
            publicStore = FileBackedPublicDownloadStore(publicRoot),
        )
        val completedUri = recreatedStorage.completeWrite(target.uriString)

        assertEquals(null, URI(completedUri).fragment)
        assertEquals("The Book.epub", File(URI(completedUri)).name)
        assertEquals(10L, File(URI(completedUri)).length())
    }

    @Test
    fun `prepareWrite refuses to invent a non-original download extension`() {
        val storage = newStorage()

        assertFailsWith<IllegalArgumentException> {
            storage.prepareWrite("srv1", PRE_ACCOUNT, "profA", 7)
        }
    }

    @Test
    fun `prepareWrite validation failure keeps existing downloaded bytes`() {
        val storage = newStorage()
        storage.prepareWrite("srv1", PRE_ACCOUNT, "profA", 7, fileName = "Existing.mkv")
            .writeTargetBytes(ByteArray(10))

        assertFailsWith<IllegalArgumentException> {
            storage.prepareWrite("srv1", PRE_ACCOUNT, "profA", 7)
        }

        assertTrue(storage.exists("srv1", PRE_ACCOUNT, "profA", 7))
        assertEquals("Existing.mkv", storage.locateLocalMedia("srv1", PRE_ACCOUNT, "profA", 7)?.displayName)
    }

    @Test
    fun `prepareWrite creates parent directories and returns target when original container is known`() {
        val storage = newStorage()
        val target = storage.prepareWrite("srv1", PRE_ACCOUNT, "profA", 7, container = "epub")
        assertTrue(target.uriString.startsWith("file://"))
        assertEquals("7.epub", target.displayName)
    }

    @Test
    fun `exists round-trips with a real write`() {
        val storage = newStorage()
        assertFalse(storage.exists("srv1", PRE_ACCOUNT, "profA", 1))
        val f = storage.prepareWrite("srv1", PRE_ACCOUNT, "profA", 1, fileName = "Movie.mkv")
        f.writeTargetBytes(ByteArray(256))
        assertTrue(storage.exists("srv1", PRE_ACCOUNT, "profA", 1))
    }

    @Test
    fun `locateLocalMedia resolves bytes by file id`() {
        val storage = newStorage()
        storage.prepareWrite("srv1", PRE_ACCOUNT, "profA", 42, fileName = "Recovered.epub").writeTargetBytes(ByteArray(10))

        assertEquals("Recovered.epub", storage.locateLocalMedia("srv1", PRE_ACCOUNT, "profA", 42)?.displayName)
    }

    @Test
    fun `delete removes only the targeted file`() {
        val storage = newStorage()
        storage.prepareWrite("srv1", PRE_ACCOUNT, "profA", 1, fileName = "One.mkv").writeTargetBytes(ByteArray(10))
        storage.prepareWrite("srv1", PRE_ACCOUNT, "profA", 2, fileName = "Two.mkv").writeTargetBytes(ByteArray(10))

        assertTrue(storage.delete("srv1", PRE_ACCOUNT, "profA", 1))
        assertFalse(storage.exists("srv1", PRE_ACCOUNT, "profA", 1))
        assertTrue(storage.exists("srv1", PRE_ACCOUNT, "profA", 2))
        // Re-delete returns false (nothing to remove).
        assertFalse(storage.delete("srv1", PRE_ACCOUNT, "profA", 1))
    }

    @Test
    fun `deleteAllForProfile isolates other profiles on the same server`() {
        val storage = newStorage()
        storage.prepareWrite("srv1", PRE_ACCOUNT, "profA", 1, fileName = "One.mkv", mediaType = DownloadMediaType.Movie.wire)
            .writeTargetBytes(ByteArray(10))
        storage.prepareWrite("srv1", PRE_ACCOUNT, "profA", 2, fileName = "Two.m4b", mediaType = DownloadMediaType.Audiobook.wire)
            .writeTargetBytes(ByteArray(10))
        storage.prepareWrite("srv1", PRE_ACCOUNT, "profB", 3, fileName = "Three.epub", mediaType = DownloadMediaType.Ebook.wire)
            .writeTargetBytes(ByteArray(10))

        assertTrue(storage.deleteAllForProfile("srv1", PRE_ACCOUNT, "profA"))
        assertFalse(storage.exists("srv1", PRE_ACCOUNT, "profA", 1))
        assertFalse(storage.exists("srv1", PRE_ACCOUNT, "profA", 2))
        assertTrue(storage.exists("srv1", PRE_ACCOUNT, "profB", 3))  // untouched
    }

    @Test
    fun `deleteAllForServer wipes everything under a server`() {
        val storage = newStorage()
        storage.prepareWrite("srv1", PRE_ACCOUNT, "profA", 1, fileName = "One.mkv", mediaType = DownloadMediaType.Movie.wire)
            .writeTargetBytes(ByteArray(10))
        storage.prepareWrite("srv1", PRE_ACCOUNT, "profB", 2, fileName = "Two.m4b", mediaType = DownloadMediaType.Audiobook.wire)
            .writeTargetBytes(ByteArray(10))
        storage.prepareWrite("srv2", PRE_ACCOUNT, "profA", 3, fileName = "Three.epub", mediaType = DownloadMediaType.Ebook.wire)
            .writeTargetBytes(ByteArray(10))

        assertTrue(storage.deleteAllForServer("srv1"))
        assertFalse(storage.exists("srv1", PRE_ACCOUNT, "profA", 1))
        assertFalse(storage.exists("srv1", PRE_ACCOUNT, "profB", 2))
        assertTrue(storage.exists("srv2", PRE_ACCOUNT, "profA", 3))
    }

    @Test
    fun `identity segments are encoded and remain under the collection root`() {
        val downloadsRoot = tmp.newFolder("public-downloads")
        val storage = DownloadStorage(
            baseDir = tmp.newFolder("filesDir"),
            publicStore = FileBackedPublicDownloadStore(downloadsRoot),
        )

        val target = storage.prepareWrite("../server", PRE_ACCOUNT, "profile/name", 9, fileName = "Book.epub")
        target.writeTargetBytes(ByteArray(10))
        val file = storage.locateLocalFile("../server", PRE_ACCOUNT, "profile/name", 9)

        assertTrue(file != null)
        assertTrue(file.canonicalPath.startsWith(downloadsRoot.canonicalPath + File.separator))
        assertFalse(file.path.contains("../server"))
        assertFalse(file.path.contains("profile/name"))
    }

    @Test
    fun `recursive scoped deletion cannot touch a sentinel outside the collection root`() {
        val parent = tmp.newFolder("parent")
        val downloadsRoot = File(parent, "public").apply { mkdirs() }
        val sentinel = File(parent, "sentinel.txt").apply { writeText("keep") }
        val storage = DownloadStorage(
            baseDir = tmp.newFolder("filesDir"),
            publicStore = FileBackedPublicDownloadStore(downloadsRoot),
        )

        assertFalse(storage.deleteAllForServer(".."))
        assertFalse(storage.deleteAllForProfile("..", PRE_ACCOUNT, "."))
        assertTrue(sentinel.isFile)
        assertEquals("keep", sentinel.readText())
    }

    @Test
    fun `recursive deletion does not follow a symlink outside the collection root`() {
        val parent = tmp.newFolder("parent")
        val downloadsRoot = File(parent, "public")
        val serverDir = File(downloadsRoot, "Downloads/srv1/profile/1").apply { mkdirs() }
        val outside = File(parent, "outside").apply { mkdirs() }
        val sentinel = File(outside, "sentinel.txt").apply { writeText("keep") }
        Files.createSymbolicLink(File(serverDir, "linked-outside").toPath(), outside.toPath())
        val storage = DownloadStorage(
            baseDir = tmp.newFolder("filesDir"),
            publicStore = FileBackedPublicDownloadStore(downloadsRoot),
        )

        assertTrue(storage.deleteAllForServer("srv1"))
        assertTrue(sentinel.isFile)
        assertEquals("keep", sentinel.readText())
    }

    @Test
    fun `file uri deletion cannot remove a file outside managed roots`() {
        val parent = tmp.newFolder("parent")
        val downloadsRoot = File(parent, "public").apply { mkdirs() }
        val sentinel = File(parent, "sentinel.txt").apply { writeText("keep") }
        val storage = DownloadStorage(
            baseDir = tmp.newFolder("filesDir"),
            publicStore = FileBackedPublicDownloadStore(downloadsRoot),
        )

        assertFalse(storage.deleteUri(sentinel.toURI().toASCIIString()))
        assertTrue(sentinel.isFile)
    }

    @Test
    fun `contained legacy download remains readable and migrates after completed update`() {
        val downloadsRoot = tmp.newFolder("public-downloads")
        val legacyDir = File(downloadsRoot, "Downloads/server id/profile%id/42").apply { mkdirs() }
        val legacyFile = File(legacyDir, "Legacy.epub").apply { writeBytes(ByteArray(7)) }
        val storage = DownloadStorage(
            baseDir = tmp.newFolder("filesDir"),
            publicStore = FileBackedPublicDownloadStore(downloadsRoot),
        )

        assertEquals(legacyFile.canonicalFile, storage.locateLocalFile("server id", PRE_ACCOUNT, "profile%id", 42)?.canonicalFile)

        val target = storage.prepareWrite("server id", PRE_ACCOUNT, "profile%id", 42, fileName = "Updated.epub")
        target.writeTargetBytes(ByteArray(11))
        assertTrue(legacyFile.isFile)

        storage.completeWrite(target.uriString)

        assertFalse(legacyDir.exists())
        assertEquals("Updated.epub", storage.locateLocalFile("server id", PRE_ACCOUNT, "profile%id", 42)?.name)
        assertEquals(11L, storage.locateLocalFile("server id", PRE_ACCOUNT, "profile%id", 42)?.length())
    }

    @Test
    fun `replacement keeps old collection bytes until the new write completes`() {
        val storage = newStorage()
        val old = storage.prepareWrite(
            "srv1",
            PRE_ACCOUNT,
            "profA",
            42,
            fileName = "Old.mkv",
            mediaType = DownloadMediaType.Movie.wire,
        )
        old.writeTargetBytes(ByteArray(7))
        storage.completeWrite(old.uriString)
        val oldFile = checkNotNull(storage.locateLocalFile("srv1", PRE_ACCOUNT, "profA", 42))

        val replacement = storage.prepareWrite(
            "srv1",
            PRE_ACCOUNT,
            "profA",
            42,
            fileName = "New.epub",
            mediaType = DownloadMediaType.Ebook.wire,
        )
        replacement.writeTargetBytes(ByteArray(11))
        assertTrue(oldFile.isFile)

        storage.completeWrite(replacement.uriString)

        assertFalse(oldFile.exists())
        assertEquals("New.epub", storage.locateLocalFile("srv1", PRE_ACCOUNT, "profA", 42)?.name)
    }

    @Test
    fun `delete removes both encoded and contained legacy candidates`() {
        val downloadsRoot = tmp.newFolder("public-downloads")
        val legacyDir = File(downloadsRoot, "Downloads/server id/profile%id/42").apply { mkdirs() }
        File(legacyDir, "Legacy.epub").writeBytes(ByteArray(7))
        val storage = DownloadStorage(
            baseDir = tmp.newFolder("filesDir"),
            publicStore = FileBackedPublicDownloadStore(downloadsRoot),
        )
        storage.prepareWrite("server id", PRE_ACCOUNT, "profile%id", 42, fileName = "Updated.epub")
            .writeTargetBytes(ByteArray(11))

        assertTrue(storage.delete("server id", PRE_ACCOUNT, "profile%id", 42))
        assertFalse(legacyDir.exists())
        assertFalse(storage.exists("server id", PRE_ACCOUNT, "profile%id", 42))
    }

    @Test
    fun `raw reserved namespace identity cannot read size or delete encoded sibling`() {
        val storage = newStorage()
        storage.prepareWrite("?", PRE_ACCOUNT, "prof", 42, fileName = "Question.epub")
            .writeTargetBytes(ByteArray(13))

        assertFalse(storage.exists("~Pw", PRE_ACCOUNT, "prof", 42))
        assertEquals(0L, storage.totalBytesUsed("~Pw", PRE_ACCOUNT, "prof"))
        assertFalse(storage.delete("~Pw", PRE_ACCOUNT, "prof", 42))
        assertTrue(storage.exists("?", PRE_ACCOUNT, "prof", 42))
        assertEquals(13L, storage.totalBytesUsed("?", PRE_ACCOUNT, "prof"))
    }

    @Test
    fun `prepared output stream cannot be swapped to an outside symlink before open`() {
        val parent = tmp.newFolder("parent")
        val publicRoot = File(parent, "public")
        val storage = DownloadStorage(
            baseDir = tmp.newFolder("filesDir"),
            publicStore = FileBackedPublicDownloadStore(publicRoot),
        )
        val target = storage.prepareWrite("server", PRE_ACCOUNT, "profile", 42, fileName = "Book.epub")
        val intendedFinal = File(publicRoot, "Downloads/server/profile/42/Book.epub")
        val sentinel = File(parent, "sentinel.txt").apply { writeText("keep") }
        Files.createSymbolicLink(intendedFinal.toPath(), sentinel.toPath())

        target.writeTargetBytes(ByteArray(9))
        storage.completeWrite(target.uriString)

        assertEquals("keep", sentinel.readText())
        assertTrue(intendedFinal.isFile)
        assertEquals(9L, intendedFinal.length())
    }

    @Test
    fun `totalBytesUsed sums every downloaded file under the root`() {
        val storage = newStorage()
        storage.prepareWrite("srv1", PRE_ACCOUNT, "profA", 1, fileName = "One.mkv").writeTargetBytes(ByteArray(100))
        storage.prepareWrite("srv1", PRE_ACCOUNT, "profB", 2, fileName = "Two.mkv").writeTargetBytes(ByteArray(250))
        storage.prepareWrite("srv2", PRE_ACCOUNT, "profA", 3, fileName = "Three.mkv").writeTargetBytes(ByteArray(50))
        assertEquals(400L, storage.totalBytesUsed())
    }

    @Test
    fun `scoped totalBytesUsed sums only one server profile`() {
        val storage = newStorage()
        storage.prepareWrite("srv1", PRE_ACCOUNT, "profA", 1, fileName = "One.mkv").writeTargetBytes(ByteArray(100))
        storage.prepareWrite("srv1", PRE_ACCOUNT, "profB", 2, fileName = "Two.mkv").writeTargetBytes(ByteArray(250))
        storage.prepareWrite("srv2", PRE_ACCOUNT, "profA", 3, fileName = "Three.mkv").writeTargetBytes(ByteArray(50))

        assertEquals(100L, storage.totalBytesUsed("srv1", PRE_ACCOUNT, "profA"))
    }

    @Test
    fun `totalBytesUsed is zero when no downloads exist`() {
        val storage = newStorage()
        assertEquals(0L, storage.totalBytesUsed())
    }

    @Test
    fun `media store relative path includes the file id directory`() {
        // Singular "Download" — MediaStore's collection root, per
        // Environment.DIRECTORY_DOWNLOADS. This assertion previously encoded the
        // plural spelling, which MediaProvider rejects outright, so ebook
        // downloads failed permanently on API 29+ while the test stayed green.
        assertEquals(
            "Download/Silo/srv1/profA/42/",
            mediaStoreRelativePath(PublicDownloadCollection.Downloads, listOf("srv1", "profA"), 42),
        )
    }

    @Test
    fun `media store relative path encodes unsafe identity segments`() {
        val path = mediaStoreRelativePath(
            PublicDownloadCollection.Downloads,
            listOf("../server", "profile/name"),
            42,
        )

        assertFalse(path.contains("../server"))
        assertFalse(path.contains("profile/name"))
        assertTrue(path.startsWith("Download/Silo/"))
        assertTrue(path.endsWith("/42/"))
    }

    @Test
    fun `media store prefix pattern escapes percent underscore and escape character`() {
        assertEquals("a\\%b\\_c\\\\d", escapeMediaStoreLike("a%b_c\\d"))

        val pattern = mediaStoreRelativePathPrefix(
            PublicDownloadCollection.Downloads,
            listOf("_", "profile_name"),
        )
        assertEquals("Download/Silo/\\_/profile\\_name/%", pattern)
    }

    @Test
    fun `unsafe media store prefix contains encoded not raw identity`() {
        val pattern = mediaStoreRelativePathPrefix(
            PublicDownloadCollection.Downloads,
            listOf("server%", "profile\\name"),
        )

        assertFalse(pattern.contains("server%"))
        assertFalse(pattern.contains("profile\\name"))
        assertNotEquals("Download/Silo/server\\%/profile\\\\name/%", pattern)
    }

    private companion object {
        // The empty account id is the pre-account layout `<server>/<profile>/<fileId>`,
        // which the path assertions here describe.
        const val PRE_ACCOUNT = ""
    }

    private fun DownloadTarget.writeTargetBytes(bytes: ByteArray) {
        openOutputStream().use { it.write(bytes) }
    }
}
