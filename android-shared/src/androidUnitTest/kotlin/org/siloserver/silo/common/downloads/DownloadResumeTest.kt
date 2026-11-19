package org.siloserver.silo.common.downloads

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DownloadResumeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── Content-Range parsing ────────────────────────────────────────────

    @Test
    fun `parses a well-formed content range with total`() {
        val r = parseContentRange("bytes 1000-9999/10000")
        assertEquals(ContentRangeInfo(1000, 9999, 10000), r)
    }

    @Test
    fun `parses unknown total as null`() {
        assertEquals(ContentRangeInfo(0, 99, null), parseContentRange("bytes 0-99/*"))
    }

    @Test
    fun `is case-insensitive and tolerates surrounding space`() {
        assertEquals(ContentRangeInfo(5, 10, 11), parseContentRange("  BYTES 5-10/11  "))
    }

    @Test
    fun `rejects malformed or inconsistent ranges`() {
        assertNull(parseContentRange(null))
        assertNull(parseContentRange(""))
        assertNull(parseContentRange("items 0-9/10"))      // wrong unit
        assertNull(parseContentRange("bytes 100-50/200"))  // end < start
        assertNull(parseContentRange("bytes 0-9999/100"))  // total <= end
        assertNull(parseContentRange("bytes 0-/100"))      // missing end
    }

    // ── Storage append + partial size (resume primitives) ────────────────

    @Test
    fun `openAppend continues an existing partial and partialSize reports real bytes`() {
        val storage = DownloadStorage(tmp.newFolder("filesDir"))
        val target = storage.prepareWrite("srv1", "acct", "profA", 42, fileName = "Movie.mkv")
        target.openOutputStream().use { it.write(ByteArray(100)) }

        assertEquals(100L, storage.partialSize(target.uriString))

        storage.openAppend(target.uriString)!!.use { it.write(ByteArray(50) { 1 }) }

        assertEquals(150L, storage.partialSize(target.uriString))
    }

    @Test
    fun `partialSize is zero for a missing partial`() {
        val storage = DownloadStorage(tmp.newFolder("filesDir"))
        assertEquals(0L, storage.partialSize("file:///nope/missing.mkv"))
    }
}
