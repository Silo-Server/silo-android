package org.siloserver.silo.common.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReplayableSubtitleDataSourceTest {
    @Test
    fun `whole sidecar is fetched once and replayed across data sources`() {
        val upstream = RecordingDataSourceFactory("WEBVTT\n\n00:01.000 --> 00:02.000\nHello\n".encodeToByteArray())
        val factory = ReplayableSubtitleDataSourceFactory(upstream)
        val spec = DataSpec(Uri.parse("https://example.test/subtitles/7.vtt"))

        val first = factory.createDataSource()
        assertEquals(upstream.payload.size.toLong(), first.open(spec))
        assertContentEquals(upstream.payload, first.readAll())
        first.close()

        val reopened = factory.createDataSource()
        assertEquals(upstream.payload.size.toLong(), reopened.open(spec))
        assertContentEquals(upstream.payload, reopened.readAll())
        reopened.close()

        assertEquals(1, upstream.openCount)
    }

    @Test
    fun `cached sidecar honors a ranged extractor reopen`() {
        val upstream = RecordingDataSourceFactory("0123456789".encodeToByteArray())
        val factory = ReplayableSubtitleDataSourceFactory(upstream)
        val uri = Uri.parse("https://example.test/subtitles/2.vtt")
        factory.createDataSource().run {
            open(DataSpec(uri))
            readAll()
            close()
        }

        val ranged = factory.createDataSource()
        val rangeSpec = DataSpec.Builder()
            .setUri(uri)
            .setPosition(4)
            .setLength(3)
            .build()
        assertEquals(3L, ranged.open(rangeSpec))
        assertContentEquals("456".encodeToByteArray(), ranged.readAll())
        ranged.close()

        assertEquals(1, upstream.openCount)
    }

    @Test
    fun `oversized sidecar fails before it can be cached`() {
        val upstream = RecordingDataSourceFactory("too large".encodeToByteArray())
        val factory = ReplayableSubtitleDataSourceFactory(upstream, maxBytes = 3)

        assertFailsWith<IOException> {
            factory.createDataSource().open(
                DataSpec(Uri.parse("https://example.test/subtitles/7.vtt")),
            )
        }
        assertEquals(1, upstream.openCount)
        assertEquals(1, upstream.closeCount)
    }
}

private class RecordingDataSourceFactory(
    val payload: ByteArray,
) : DataSource.Factory {
    var openCount = 0
    var closeCount = 0

    override fun createDataSource(): DataSource = object : DataSource {
        private var uri: Uri? = null
        private var position = 0
        private var limit = 0

        override fun addTransferListener(transferListener: TransferListener) = Unit

        override fun open(dataSpec: DataSpec): Long {
            openCount++
            uri = dataSpec.uri
            position = dataSpec.position.toInt()
            limit = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
                payload.size
            } else {
                minOf(payload.size, position + dataSpec.length.toInt())
            }
            return (limit - position).toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= limit) return C.RESULT_END_OF_INPUT
            val count = minOf(length, limit - position)
            payload.copyInto(buffer, offset, position, position + count)
            position += count
            return count
        }

        override fun getUri(): Uri? = uri

        override fun getResponseHeaders(): Map<String, List<String>> =
            mapOf("ETag" to listOf("\"subtitle\""))

        override fun close() {
            closeCount++
            uri = null
        }
    }
}

private fun DataSource.readAll(): ByteArray {
    val chunks = ArrayList<Byte>()
    val buffer = ByteArray(7)
    while (true) {
        val read = read(buffer, 0, buffer.size)
        if (read == C.RESULT_END_OF_INPUT) break
        repeat(read) { chunks += buffer[it] }
    }
    return chunks.toByteArray()
}
