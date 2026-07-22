package org.siloserver.silo.common.diagnostics.bundle

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import org.siloserver.silo.common.diagnostics.TestDrafts
import org.siloserver.silo.common.diagnostics.TestResources
import org.siloserver.silo.model.diagnostics.DiagnosticsLogCategory
import org.siloserver.silo.model.diagnostics.validate
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wire-compat test for the transmitted bundle: the tar.gz is verified with an
 * independent, test-local strict USTAR reader (never the production writer),
 * so any format drift the server would reject fails here first.
 */
class DiagnosticsBundleBuilderTest {

    private val token = "SECRETSTACKTOKEN123"
    private val stackText =
        "java.lang.NullPointerException: player was null\n" +
            "\tat org.siloserver.silo.PlaybackSessionManager.start(PlaybackSessionManager.kt:42)\n" +
            "context: $token must never ship\n"

    private val deviceJson = TestResources.bytes("diagnostics/v1/fixtures/valid/device.json")
    private val logsJsonl = TestResources.bytes("diagnostics/v1/fixtures/valid/loglines.jsonl")

    /** Deliberately invalid UTF-8 (lone continuation + truncated lead bytes). */
    private val tombstone = byteArrayOf(
        0x00, 0x01, 0xFF.toByte(), 0x88.toByte(), 0xC0.toByte(), 0x00, 0x7F, 0xFE.toByte(),
    )

    private fun buildCrashResult(): DiagnosticsBundleBuilder.Result = DiagnosticsBundleBuilder().build(
        DiagnosticsBundleBuilder.Input(
            draft = TestDrafts.crashDraft(droppedLines = 12),
            deviceJson = deviceJson,
            logsJsonl = logsJsonl,
            stackTxt = stackText.encodeToByteArray(),
            tombstonePb = tombstone,
            breadcrumbsJsonl = logsJsonl,
            debugLogging = true,
        ),
        // "player" is below the 8-char token floor and must be ignored.
        redactionTokens = listOf(token, "player"),
    )

    // ---- (a) gzip layer --------------------------------------------------

    @Test
    fun `bundle decompresses fully and uncompressed byte count matches manifest`() {
        val result = buildCrashResult()
        val decompressed = gunzip(result.bundleBytes)
        assertEquals(result.manifest.archive.uncompressedBytes, decompressed.size.toLong())
        assertEquals(0, decompressed.size % 512, "tar stream must be block-aligned")
    }

    // ---- (b) strict ustar layer -----------------------------------------

    @Test
    fun `bundle is a strict plain ustar archive in exactly manifest entry order`() {
        val result = buildCrashResult()
        val entries = readStrictUstar(gunzip(result.bundleBytes))

        assertEquals(
            listOf(
                "manifest.json",
                "device.json",
                "logs.jsonl",
                "crash/summary.json",
                "crash/stack.txt",
                "crash/tombstone.pb",
                "breadcrumbs.jsonl",
            ),
            entries.map { it.name },
        )
        assertEquals(result.manifest.archive.entries, entries.map { it.name })
        assertEquals("manifest.json", entries.first().name)
    }

    // ---- (c) hash + size -------------------------------------------------

    @Test
    fun `bundle sha256 and byte count match the manifest archive object`() {
        val result = buildCrashResult()
        assertEquals(result.manifest.archive.bytes, result.bundleBytes.size.toLong())
        assertEquals(result.manifest.archive.sha256, sha256Hex(result.bundleBytes))
        assertTrue(result.manifest.validate().isEmpty(), "built manifest must pass contract validation")
    }

    // ---- (d) embedded manifest == part 1 minus archive -------------------

    @Test
    fun `embedded manifest entry equals outer manifest minus archive`() {
        val result = buildCrashResult()
        val embedded = readStrictUstar(gunzip(result.bundleBytes)).first { it.name == "manifest.json" }

        val outer = Json.parseToJsonElement(result.manifestJson.decodeToString()).jsonObject
        val embeddedJson = Json.parseToJsonElement(embedded.data.decodeToString()).jsonObject

        assertTrue("archive" in outer, "part-1 manifest must carry the archive object")
        assertFalse("archive" in embeddedJson, "embedded manifest must not carry the archive object")
        assertEquals(JsonObject(outer.filterKeys { it != "archive" }), embeddedJson)
    }

    // ---- (e) redaction before sizing -------------------------------------

    @Test
    fun `token is scrubbed before tar sizing and never appears in the bundle`() {
        val result = buildCrashResult()
        val decompressed = gunzip(result.bundleBytes)
        val entries = readStrictUstar(decompressed)

        // The strict reader slices each entry by its DECLARED header size, so
        // content equality here proves the header was sized post-redaction.
        val stack = entries.first { it.name == "crash/stack.txt" }
        assertContentEquals(
            stackText.replace(token, "[redacted_token]").encodeToByteArray(),
            stack.data,
        )

        val haystack = String(decompressed, Charsets.ISO_8859_1)
        assertFalse(haystack.contains(token), "token leaked into the decompressed archive")
        assertFalse(result.manifestJson.decodeToString().contains(token), "token leaked into part-1 manifest")
        // Sub-floor tokens are ignored, not scrubbed.
        assertTrue(stack.data.decodeToString().contains("player was null"))
    }

    // ---- (f) manual reports ----------------------------------------------

    @Test
    fun `manual draft produces no crash entries`() {
        val result = DiagnosticsBundleBuilder().build(
            DiagnosticsBundleBuilder.Input(
                draft = TestDrafts.manualDraft(),
                deviceJson = deviceJson,
                logsJsonl = logsJsonl,
                stackTxt = null,
                tombstonePb = null,
                breadcrumbsJsonl = null,
                debugLogging = false,
            ),
            redactionTokens = emptyList(),
        )

        assertNull(result.manifest.crash)
        assertEquals(listOf("manifest.json", "device.json", "logs.jsonl"), result.manifest.archive.entries)
        val entries = readStrictUstar(gunzip(result.bundleBytes))
        assertEquals(listOf("manifest.json", "device.json", "logs.jsonl"), entries.map { it.name })
        assertTrue(result.manifest.validate().isEmpty())
    }

    // ---- (g) opaque binary passthrough -----------------------------------

    @Test
    fun `tombstone bytes pass through byte-identical`() {
        val result = buildCrashResult()
        val entry = readStrictUstar(gunzip(result.bundleBytes)).first { it.name == "crash/tombstone.pb" }
        assertContentEquals(tombstone, entry.data, "binary tombstone must never be string-scrubbed")
    }

    // ---- log summary recompute -------------------------------------------

    @Test
    fun `log summary is recomputed from the shipped logs`() {
        val summary = buildCrashResult().manifest.logSummary
        assertEquals(3L, summary.lines, "loglines fixture ships three lines")
        assertEquals(
            listOf(DiagnosticsLogCategory.PLAYBACK, DiagnosticsLogCategory.NETWORK, DiagnosticsLogCategory.FOCUS),
            summary.categories,
        )
        assertEquals(12L, summary.droppedLines, "dropped count carries over from the draft")
        assertTrue(summary.debugLogging)
        assertTrue(summary.bytesGz > 0)
    }

    // ---- test-local strict ustar reader ----------------------------------

    private class TarEntry(val name: String, val data: ByteArray)

    /**
     * Independent strict USTAR reader: plain POSIX ustar only (magic
     * `ustar\0`, version `00` — PAX/GNU markers fail), typeflag '0', declared
     * sizes honored exactly, checksum verified, zero padding verified, and the
     * archive must end with exactly two 512-byte zero blocks.
     */
    private fun readStrictUstar(tar: ByteArray): List<TarEntry> {
        val entries = ArrayList<TarEntry>()
        var pos = 0
        while (true) {
            assertTrue(pos + 512 <= tar.size, "truncated archive: no room for header at $pos")
            val header = tar.copyOfRange(pos, pos + 512)
            if (header.all { it == 0.toByte() }) {
                assertEquals(tar.size, pos + 1024, "archive must end with exactly two zero blocks")
                assertTrue(
                    tar.copyOfRange(pos + 512, pos + 1024).all { it == 0.toByte() },
                    "second end-of-archive block must be all zero",
                )
                assertTrue(entries.isNotEmpty(), "archive contained no entries")
                return entries
            }

            // POSIX ustar magic/version — GNU ("ustar  ") and PAX extension
            // headers both fail these asserts.
            assertEquals("ustar", String(header, 257, 5, Charsets.US_ASCII), "header magic at offset 257")
            assertEquals(0, header[262].toInt(), "magic NUL terminator")
            assertEquals('0'.code.toByte(), header[263], "version byte 263 must be '0'")
            assertEquals('0'.code.toByte(), header[264], "version byte 264 must be '0'")
            assertEquals('0', (header[156].toInt() and 0xFF).toChar(), "typeflag must be regular file")

            val nameEnd = (0 until 100).firstOrNull { header[it] == 0.toByte() } ?: 100
            val name = String(header, 0, nameEnd, Charsets.US_ASCII)
            assertTrue(name.isNotEmpty(), "entry with empty name")

            val size = parseOctal(header, 124, 12)

            val declaredChecksum = parseOctal(header, 148, 8)
            val checksumCopy = header.copyOf()
            for (i in 148 until 156) checksumCopy[i] = ' '.code.toByte()
            assertEquals(
                checksumCopy.sumOf { (it.toInt() and 0xFF).toLong() },
                declaredChecksum,
                "$name: header checksum",
            )

            pos += 512
            assertTrue(pos + size <= tar.size, "$name: declared size $size overruns the archive")
            val data = tar.copyOfRange(pos, pos + size.toInt())
            pos += size.toInt()
            val padding = ((512 - (size % 512)) % 512).toInt()
            for (i in 0 until padding) {
                assertEquals(0, tar[pos + i].toInt(), "$name: nonzero content padding")
            }
            pos += padding
            entries.add(TarEntry(name, data))
        }
    }

    private fun parseOctal(header: ByteArray, offset: Int, length: Int): Long {
        var value = 0L
        var sawDigit = false
        for (i in offset until offset + length) {
            val b = header[i].toInt() and 0xFF
            if (b == 0 || b == ' '.code) break
            assertTrue(b in '0'.code..'7'.code, "non-octal byte $b in field at offset $offset")
            value = value * 8 + (b - '0'.code)
            sawDigit = true
        }
        assertTrue(sawDigit, "empty octal field at offset $offset")
        return value
    }

    private fun gunzip(bytes: ByteArray): ByteArray =
        GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
