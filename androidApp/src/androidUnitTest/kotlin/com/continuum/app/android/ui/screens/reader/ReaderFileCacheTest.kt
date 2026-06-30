package com.continuum.app.android.ui.screens.reader

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReaderFileCacheTest {
    private val source = java.io.File(
        "src/androidMain/kotlin/com/continuum/app/android/ui/screens/reader/ReaderFileCache.kt",
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
