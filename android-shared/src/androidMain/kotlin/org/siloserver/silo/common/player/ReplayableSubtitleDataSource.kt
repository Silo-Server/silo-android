package org.siloserver.silo.common.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.siloserver.silo.common.io.checkedLimitedByteCount

/**
 * Buffers one parsed-text subtitle response and replays later opens locally.
 *
 * Media3's [androidx.media3.extractor.text.SubtitleExtractor] reads the whole
 * sidecar to publish cues. A start-position seek can then reopen the same URI
 * from byte zero so the extractor can rebuild its seeked output. When that
 * second network read shares a [androidx.media3.exoplayer.source.MergingMediaSource]
 * with HLS, Media3 may close its load gate after the first video segment. The
 * subtitle response then remains backpressured and the primary source cannot
 * continue loading. Reading the bounded text artifact during [DataSource.open]
 * makes the first request atomic from Media3's perspective and lets every
 * subsequent extractor open read the same immutable bytes without HTTP.
 *
 * A factory instance belongs to one subtitle child source, so its cache cannot
 * leak between tracks, sessions, or media mounts.
 */
@OptIn(UnstableApi::class)
internal class ReplayableSubtitleDataSourceFactory(
    private val upstreamFactory: DataSource.Factory,
    private val maxBytes: Long = MAX_SUBTITLE_BYTES,
) : DataSource.Factory {
    private val cache = ReplayableSubtitleCache()

    override fun createDataSource(): DataSource = ReplayableSubtitleDataSource(
        upstream = upstreamFactory.createDataSource(),
        cache = cache,
        maxBytes = maxBytes,
    )
}

private data class ReplayableSubtitleEntry(
    val requestedUri: Uri,
    val resolvedUri: Uri,
    val responseHeaders: Map<String, List<String>>,
    val data: ByteArray,
)

private class ReplayableSubtitleCache {
    var entry: ReplayableSubtitleEntry? = null
}

@OptIn(UnstableApi::class)
private class ReplayableSubtitleDataSource(
    private val upstream: DataSource,
    private val cache: ReplayableSubtitleCache,
    private val maxBytes: Long,
) : DataSource {
    private var replay: ReplayableSubtitleEntry? = null
    private var replayPosition = 0
    private var replayLimit = 0
    private var upstreamOpen = false

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        close()

        synchronized(cache) { cache.entry }
            ?.takeIf { it.requestedUri == dataSpec.uri }
            ?.let { return openReplay(it, dataSpec) }

        if (!dataSpec.isWholeResourceFromStart()) {
            val length = upstream.open(dataSpec)
            upstreamOpen = true
            return length
        }

        val declaredLength = upstream.open(dataSpec)
        upstreamOpen = true
        val resolvedUri = upstream.uri ?: dataSpec.uri
        val responseHeaders = upstream.responseHeaders
        val bytes = try {
            if (declaredLength >= 0L) {
                checkedLimitedByteCount(
                    currentBytes = 0L,
                    additionalBytes = declaredLength,
                    maxBytes = maxBytes,
                    limitName = "subtitle",
                )
            }
            readAllFromUpstream()
        } finally {
            upstream.close()
            upstreamOpen = false
        }
        val entry = ReplayableSubtitleEntry(
            requestedUri = dataSpec.uri,
            resolvedUri = resolvedUri,
            responseHeaders = responseHeaders,
            data = bytes,
        )
        synchronized(cache) {
            val published = cache.entry
                ?.takeIf { it.requestedUri == dataSpec.uri }
                ?: entry.also { cache.entry = it }
            return openReplay(published, dataSpec)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val entry = replay ?: return upstream.read(buffer, offset, length)
        if (replayPosition >= replayLimit) return C.RESULT_END_OF_INPUT
        val count = minOf(length, replayLimit - replayPosition)
        entry.data.copyInto(
            destination = buffer,
            destinationOffset = offset,
            startIndex = replayPosition,
            endIndex = replayPosition + count,
        )
        replayPosition += count
        return count
    }

    override fun getUri(): Uri? = replay?.resolvedUri ?: upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        replay?.responseHeaders ?: upstream.responseHeaders

    override fun close() {
        if (upstreamOpen) upstream.close()
        upstreamOpen = false
        replay = null
        replayPosition = 0
        replayLimit = 0
    }

    private fun openReplay(entry: ReplayableSubtitleEntry, dataSpec: DataSpec): Long {
        val position = dataSpec.position
        if (position < 0L || position > entry.data.size.toLong()) {
            throw IOException(
                "Subtitle replay position $position is outside ${entry.data.size} bytes",
            )
        }
        val requestedLength = dataSpec.length
        val available = entry.data.size.toLong() - position
        val exposedLength = if (requestedLength == C.LENGTH_UNSET.toLong()) {
            available
        } else {
            minOf(available, requestedLength)
        }
        replay = entry
        replayPosition = position.toInt()
        replayLimit = (position + exposedLength).toInt()
        return exposedLength
    }

    private fun readAllFromUpstream(): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_SUBTITLE_READ_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = upstream.read(buffer, 0, buffer.size)
            if (read == C.RESULT_END_OF_INPUT) break
            if (read > 0) {
                total = checkedLimitedByteCount(
                    currentBytes = total,
                    additionalBytes = read.toLong(),
                    maxBytes = maxBytes,
                    limitName = "subtitle",
                )
                out.write(buffer, 0, read)
            }
        }
        return out.toByteArray()
    }
}

@OptIn(UnstableApi::class)
private fun DataSpec.isWholeResourceFromStart(): Boolean =
    position == 0L && length == C.LENGTH_UNSET.toLong()

private const val DEFAULT_SUBTITLE_READ_BUFFER_SIZE = 16 * 1024
