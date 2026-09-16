package org.siloserver.silo.android.ui.screens.reader

import kotlinx.coroutines.CancellationException
import org.siloserver.silo.common.io.ContentLimitExceeded
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReaderFileCacheTest {
    @Test
    fun `remote cache separates profiles and login incarnations`() {
        val scope = org.siloserver.silo.network.AuthScopeSnapshot("server", "profile", "https://reader.example", null)
        val authority = org.siloserver.silo.network.DurableLoginAuthority("login-one", scope)
        val url = "https://reader.example/api/v2/ebooks/book/files/7/read"
        val first = readerRemoteCacheFileName(url, authority, "pdf")
        assertFalse(first == readerRemoteCacheFileName(url, authority.copy(loginId = "login-two"), "pdf"))
        assertFalse(first == readerRemoteCacheFileName(url, authority.copy(scope = scope.copy(profileId = "other")), "pdf"))
        assertEquals(first, readerRemoteCacheFileName(url, authority.copy(scope = scope.copy(identityGeneration = 8)), "pdf"))
    }

    private val source = java.io.File(
        "src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/reader/ReaderFileCache.kt",
    ).readText()

    @Test
    fun `successful fetch lands at the final path with no tmp residue`() {
        val cacheDir = newCacheDir()

        val result = cacheReaderFile(cacheDir, "abc.pdf") { out ->
            out.write("pdf-bytes".toByteArray())
        }

        assertEquals(File(cacheDir, "abc.pdf"), result)
        assertEquals("pdf-bytes", result.readText())
        assertTrue(cacheDir.listFiles()?.none { it.name.endsWith(".tmp") } ?: true, "no .tmp residue expected")
    }

    @Test
    fun `failed fetch leaves no cached file and no tmp residue`() {
        val cacheDir = newCacheDir()

        assertFailsWith<IOException> {
            cacheReaderFile(cacheDir, "abc.pdf") { out ->
                out.write("trunc".toByteArray())
                throw IOException("connection reset")
            }
        }

        assertFalse(
            File(cacheDir, "abc.pdf").exists(),
            "truncated download must not poison the cache",
        )
        assertTrue(cacheDir.listFiles()?.none { it.name.endsWith(".tmp") } ?: true, "no .tmp residue expected")
    }

    @Test
    fun `reader input limit is 2GiB`() {
        assertEquals(2L * 1024 * 1024 * 1024, MAX_READER_INPUT_BYTES)
    }

    @Test
    fun `reader copy accepts exactly the byte limit`() {
        val cacheDir = newCacheDir()

        val result = cacheReaderFile(cacheDir, "exact.pdf") { out ->
            ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))
                .copyReaderInputTo(out, maxBytes = 4)
        }

        assertEquals(4, result.length())
    }

    @Test
    fun `reader declared length accepts exact limit and rejects limit plus one`() {
        validateReaderDeclaredLength(length = 4, maxBytes = 4)

        assertFailsWith<ContentLimitExceeded> {
            validateReaderDeclaredLength(length = 5, maxBytes = 4)
        }
    }

    @Test
    fun `reader unknown declared length defers to streamed byte accounting`() {
        validateReaderDeclaredLength(length = -1, maxBytes = 0)
    }

    @Test
    fun `reader copy rejects limit plus one and removes partial files`() {
        val cacheDir = newCacheDir()

        assertFailsWith<ContentLimitExceeded> {
            cacheReaderFile(cacheDir, "oversize.pdf") { out ->
                ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5))
                    .copyReaderInputTo(out, maxBytes = 4)
            }
        }

        assertFalse(File(cacheDir, "oversize.pdf").exists())
        assertTrue(cacheDir.listFiles().isNullOrEmpty(), "no partial reader files expected")
    }

    @Test
    fun `cancelled fetch removes partial files`() {
        val cacheDir = newCacheDir()

        assertFailsWith<CancellationException> {
            cacheReaderFile(cacheDir, "cancelled.pdf") { out ->
                out.write(byteArrayOf(1, 2, 3))
                throw CancellationException("cancelled")
            }
        }

        assertFalse(File(cacheDir, "cancelled.pdf").exists())
        assertTrue(cacheDir.listFiles().isNullOrEmpty(), "no partial reader files expected")
    }

    @Test
    fun `cancelled validation removes the newly cached file`() {
        val cacheDir = newCacheDir()

        assertFailsWith<CancellationException> {
            cacheReaderFile(
                cacheDir = cacheDir,
                fileName = "cancelled-validation.epub",
                validate = { throw CancellationException("cancelled") },
            ) { out ->
                out.write(byteArrayOf(1, 2, 3))
            }
        }

        assertTrue(cacheDir.listFiles().isNullOrEmpty(), "no partial reader files expected")
    }

    @Test
    fun `existing non-empty cache entry short-circuits without fetching`() {
        val cacheDir = newCacheDir()
        File(cacheDir, "abc.pdf").writeText("cached")

        val result = cacheReaderFile(cacheDir, "abc.pdf") {
            throw AssertionError("fetch must not run on cache hit")
        }

        assertEquals("cached", result.readText())
    }

    @Test
    fun `invalid existing cache entry is deleted and refetched when validator rejects it`() {
        val cacheDir = newCacheDir()
        File(cacheDir, "abc.epub").writeText("not an epub")

        val result = cacheReaderFile(
            cacheDir = cacheDir,
            fileName = "abc.epub",
            validate = { it.readText() == "valid epub" },
        ) { out ->
            out.write("valid epub".toByteArray())
        }

        assertEquals("valid epub", result.readText())
    }

    @Test
    fun `invalid fetched cache entry is deleted and fails validation`() {
        val cacheDir = newCacheDir()

        assertFailsWith<IOException> {
            cacheReaderFile(
                cacheDir = cacheDir,
                fileName = "abc.epub",
                validate = { it.readText() == "valid epub" },
            ) { out ->
                out.write("not an epub".toByteArray())
            }
        }

        assertFalse(File(cacheDir, "abc.epub").exists())
    }

    @Test
    fun `empty cache entry is refetched`() {
        val cacheDir = newCacheDir()
        File(cacheDir, "abc.pdf").writeText("")

        val result = cacheReaderFile(cacheDir, "abc.pdf") { out ->
            out.write("refetched".toByteArray())
        }

        assertEquals("refetched", result.readText())
    }

    @Test
    fun `cache key is the sha1 hex of the url`() {
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", readerCacheKey("abc"))
    }

    @Test
    fun `reader fetch errors do not expose full urls`() {
        assertFalse(source.contains("Could not open \$url"))
        assertFalse(source.contains("fetching \$requestUrl"))
        assertFalse(source.contains("body for \$requestUrl"))
    }

    private fun newCacheDir(): File =
        Files.createTempDirectory("reader-cache").toFile().apply { deleteOnExit() }
}
