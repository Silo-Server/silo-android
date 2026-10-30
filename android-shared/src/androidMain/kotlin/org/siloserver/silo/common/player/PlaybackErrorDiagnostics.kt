package org.siloserver.silo.common.player

import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer
import androidx.media3.exoplayer.source.MediaLoadData
import java.io.IOException
import org.siloserver.silo.common.diagnostics.DiagnosticsLoadErrorDetail
import org.siloserver.silo.common.diagnostics.DiagnosticsPlayerErrorDetail

/**
 * Maps a player error to diagnostics detail without touching its message,
 * data spec, or URI: exception messages from the HTTP stack routinely embed
 * the stream URL and its token.
 */
@UnstableApi
internal fun PlaybackException.toDiagnosticsDetail(): DiagnosticsPlayerErrorDetail {
    val chain = causeChain(cause)
    val rendererError = (this as? ExoPlaybackException)?.takeIf { it.type == ExoPlaybackException.TYPE_RENDERER }
    val decoderInit = chain.firstNotNullOfOrNull { it as? MediaCodecRenderer.DecoderInitializationException }
    val decoderFailure = chain.firstNotNullOfOrNull { it as? MediaCodecDecoderException }
    return DiagnosticsPlayerErrorDetail(
        errorCode = errorCode,
        errorCodeName = errorCodeName,
        httpStatus = chain.httpStatus(),
        causeClass = chain.firstOrNull()?.diagnosticsClassName(),
        rootCauseClass = chain.rootCauseClassName(),
        renderer = rendererError?.rendererName,
        decoder = decoderInit?.codecInfo?.name ?: decoderFailure?.codecInfo?.name,
        sampleMimeType = rendererError?.rendererFormat?.sampleMimeType ?: decoderInit?.mimeType,
        formatSupport = rendererError?.let { C.getFormatSupportString(it.rendererFormatSupport) },
    )
}

@UnstableApi
internal fun mediaLoadErrorDiagnosticsDetail(
    mediaLoadData: MediaLoadData,
    error: IOException,
    wasCanceled: Boolean,
): DiagnosticsLoadErrorDetail {
    val chain = causeChain(error)
    return DiagnosticsLoadErrorDetail(
        dataType = mediaLoadData.dataType.dataTypeName(),
        trackType = mediaLoadData.trackType.diagnosticsTrackTypeName(),
        httpStatus = chain.httpStatus(),
        errorClass = error.diagnosticsClassName(),
        rootCauseClass = chain.rootCauseClassName(),
        wasCanceled = wasCanceled,
    )
}

private fun causeChain(first: Throwable?): List<Throwable> {
    val seen = HashSet<Throwable>()
    return generateSequence(first) { it.cause }
        .takeWhile { seen.add(it) }
        .take(MAX_CAUSE_DEPTH)
        .toList()
}

@UnstableApi
private fun List<Throwable>.httpStatus(): Int? =
    firstNotNullOfOrNull { (it as? HttpDataSource.InvalidResponseCodeException)?.responseCode }

private fun List<Throwable>.rootCauseClassName(): String? =
    if (size > 1) last().diagnosticsClassName() else null

private fun Throwable.diagnosticsClassName(): String =
    javaClass.simpleName.ifEmpty { javaClass.name.substringAfterLast('.') }

@UnstableApi
private fun Int.dataTypeName(): String = when (this) {
    C.DATA_TYPE_MEDIA -> "media"
    C.DATA_TYPE_MEDIA_INITIALIZATION -> "media_init"
    C.DATA_TYPE_MANIFEST -> "manifest"
    C.DATA_TYPE_DRM -> "drm"
    C.DATA_TYPE_TIME_SYNCHRONIZATION -> "time_sync"
    C.DATA_TYPE_AD -> "ad"
    C.DATA_TYPE_MEDIA_PROGRESSIVE_LIVE -> "media_progressive_live"
    C.DATA_TYPE_STEERING_MANIFEST -> "steering_manifest"
    C.DATA_TYPE_UNKNOWN -> "unknown"
    else -> "custom"
}

private fun Int.diagnosticsTrackTypeName(): String = when (this) {
    C.TRACK_TYPE_DEFAULT -> "default"
    C.TRACK_TYPE_VIDEO -> "video"
    C.TRACK_TYPE_AUDIO -> "audio"
    C.TRACK_TYPE_TEXT -> "text"
    C.TRACK_TYPE_METADATA -> "metadata"
    C.TRACK_TYPE_IMAGE -> "image"
    C.TRACK_TYPE_CAMERA_MOTION -> "camera_motion"
    C.TRACK_TYPE_NONE -> "none"
    C.TRACK_TYPE_UNKNOWN -> "unknown"
    else -> "custom"
}

private const val MAX_CAUSE_DEPTH = 8
