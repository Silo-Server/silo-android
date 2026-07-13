package org.siloserver.silo.common.player.seek

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource

/**
 * True only for failures that can plausibly be caused by the byte/timeline
 * boundary selected by a seek. Decoder, audio-sink, generic network, timeout,
 * and startup failures deliberately remain on the normal recovery ladder.
 */
fun PlaybackException.isSameRouteSeekReanchorCandidate(): Boolean =
    isSameRouteSeekReanchorError(
        errorCode = errorCode,
        httpResponseCode = findHttpResponseCode(),
    )

internal fun isSameRouteSeekReanchorError(
    errorCode: Int,
    httpResponseCode: Int? = null,
): Boolean = when (errorCode) {
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
    PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
    PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
    -> true
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
        httpResponseCode in SEEK_REANCHOR_HTTP_STATUS_CODES
    else -> false
}

private fun PlaybackException.findHttpResponseCode(): Int? {
    var current: Throwable? = this
    while (current != null) {
        if (current is HttpDataSource.InvalidResponseCodeException) {
            return current.responseCode
        }
        current = current.cause
    }
    return null
}

private val SEEK_REANCHOR_HTTP_STATUS_CODES = setOf(404, 410, 416)
