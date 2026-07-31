package org.siloserver.silo.common.player.cast

import android.util.Log
import java.net.URLEncoder
import org.siloserver.silo.common.player.PlaybackNetworkEvidenceProvider
import org.siloserver.silo.common.player.PlaybackSessionManager
import org.siloserver.silo.common.player.VideoSessionStartV3
import org.siloserver.silo.common.player.audio.PassthroughSuppressionScope
import org.siloserver.silo.common.player.isBitmapSubtitleCodecOrMime
import org.siloserver.silo.common.player.resolvePlaybackStreamUrl
import org.siloserver.silo.model.playback.CAPABILITY_EVIDENCE_DECLARED
import org.siloserver.silo.model.playback.ClientCodecCapabilities
import org.siloserver.silo.model.playback.ClientPlaybackContext
import org.siloserver.silo.model.playback.DELIVERY_CLASS_HLS
import org.siloserver.silo.model.playback.DeliveryCapability
import org.siloserver.silo.model.playback.DeliverySubtitleCapabilities
import org.siloserver.silo.model.playback.PlaybackDeviceContext
import org.siloserver.silo.model.playback.PlaybackOutputContext
import org.siloserver.silo.model.playback.PlaybackPlanV3
import org.siloserver.silo.model.playback.PlaybackStreamProtocol
import org.siloserver.silo.model.playback.PlaybackSubtitleInventoryItemV3
import org.siloserver.silo.model.playback.SubtitleFidelityPreference
import org.siloserver.silo.model.playback.VideoDecodeCapability
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.repository.PlaybackRepository

/**
 * Tier-2 Google Cast session preparation.
 *
 * Casting to a Chromecast dongle can NOT reuse the phone's current stream: the
 * phone may be direct-playing an MKV/HEVC file the dongle cannot decode, and the
 * phone's stream URL is authenticated with an `Authorization` header that a
 * Cast receiver cannot send. So this helper opens a *separate* playback session
 * that advertises a conservative Chromecast profile (H.264 / AAC / HLS-only),
 * making the server return a cast-playable HLS manifest, then rewrites the
 * resulting URL into a self-contained `?st=`-signed absolute URL the dongle can
 * fetch on its own.
 *
 * It deliberately uses its OWN [PlaybackSessionManager] instance (never the DI
 * singleton) because that class holds a single `activeVideoAttempt` reference —
 * driving a cast session through the shared instance would clobber the phone's
 * local playback session. For the same reason it passes
 * [PassthroughSuppressionScope.None]: the suppression registry is
 * process-global, and a cast plan must not reset the suppression state of the
 * phone's own audio sink.
 */
class CastPlaybackPreparer(
    private val playbackRepository: PlaybackRepository,
    private val tokenManager: TokenManager,
    private val networkEvidenceProvider: PlaybackNetworkEvidenceProvider =
        PlaybackNetworkEvidenceProvider.None,
) {
    /**
     * Opens a cast-capability playback session and resolves a self-contained
     * cast media spec. Returns `null` when the server refuses or the network
     * fails — the caller should surface a "couldn't cast" notice.
     */
    suspend fun prepareCastMedia(request: CastPrepareRequest): CastMediaSpec? {
        // Separate manager instance so the phone's activeVideoAttempt and its
        // passthrough suppression state are both untouched.
        val castSession = PlaybackSessionManager(
            playbackRepository = playbackRepository,
            tokenManager = tokenManager,
            networkEvidenceProvider = networkEvidenceProvider,
            passthroughSuppression = PassthroughSuppressionScope.None,
        )

        val result = castSession.startVideoSessionV3(
            fileId = request.fileId,
            profileId = request.profileId,
            capabilities = chromecastCodecCapabilities(),
            clientPlaybackContext = chromecastPlaybackContext(request.appVersion),
            audioTrackIndex = request.audioTrackIndex,
            subtitleTrackIndex = request.subtitleTrackIndex,
            qualityPreference = "auto",
            startPosition = request.startPositionSeconds,
            // Cast subtitles ride as WebVTT text tracks, never burn-in and never
            // libass. COMPATIBLE tells the server to convert an ASS track into
            // WebVTT rather than treating a styling loss as a reason to burn it
            // into the video (or to fail the plan outright).
            subtitleFidelityPreference = SubtitleFidelityPreference.COMPATIBLE,
        )

        val started = when (result) {
            is ApiResult.Success -> result.data
            is ApiResult.Error -> {
                Log.w(TAG, "Cast session start failed: ${result.code} ${result.message}")
                return null
            }
            is ApiResult.NetworkError -> {
                Log.w(TAG, "Cast session start network error: ${result.exception}")
                return null
            }
        }

        val ready = when (started) {
            is VideoSessionStartV3.Ready -> started
            is VideoSessionStartV3.Terminal -> {
                Log.w(TAG, "Cast session terminal: ${started.reason} ${started.message}")
                return null
            }
            VideoSessionStartV3.ServerUpgradeRequired -> {
                Log.w(TAG, "Cast session refused: server does not speak playback protocol v3")
                return null
            }
        }

        // From here on a server session exists. If the preparing coroutine is
        // cancelled before the spec is handed to the Cast SDK, stop the
        // session — otherwise it lingers, counts against the account's
        // concurrent-stream cap, and every later cast start 429s.
        val sessionId = ready.session.sessionId
        try {
            return buildCastMediaSpec(request, ready)
        } catch (e: kotlinx.coroutines.CancellationException) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                castSession.stopSession(sessionId)
            }
            throw e
        }
    }

    private suspend fun buildCastMediaSpec(
        request: CastPrepareRequest,
        ready: VideoSessionStartV3.Ready,
    ): CastMediaSpec {
        val plan = ready.plan
        val serverUrl = tokenManager.getServerUrl()
        val token = tokenManager.getAccessToken()

        // Unlike the legacy two-step start, a v3 plan's stream URL is already
        // live and fully signed when the plan is returned — the server prepares
        // the transport inside the start handler — so there is nothing to
        // "start" before handing the URL to the receiver.
        val castUrl = signStreamUrl(resolvePlaybackStreamUrl(serverUrl, plan.stream.url), token)

        // The receiver must know whether the URL is an HLS manifest or a
        // progressive stream. The plan states it outright, so guessing from the
        // URL shape is only a last resort for a plan that omitted the mime.
        val mimeType = plan.stream.mimeType?.takeIf { it.isNotBlank() }
            ?: castFallbackMimeType(plan)

        return CastMediaSpec(
            fileId = plan.effectiveMediaFileId ?: request.fileId,
            streamUrl = castUrl,
            mimeType = mimeType,
            title = request.title,
            posterUrl = request.posterUrl,
            positionSeconds = plan.timeline.sourceStartSeconds
                .takeIf { it.isFinite() && it >= 0.0 }
                ?: request.startPositionSeconds,
            durationSeconds = plan.source.durationSeconds ?: 0.0,
            subtitles = castSubtitleTracks(request, plan, serverUrl, token, castUrl),
        )
    }

    /**
     * The receiver's full subtitle menu, built from `plan.subtitle.inventory`.
     *
     * The inventory is the contract's authoritative track list, and it is the
     * only source that carries every track: the session response projects just
     * the one artifact the plan selected, so a cast started with subtitles off —
     * or with one track chosen — would otherwise reach the receiver with an
     * empty or single-entry CC menu.
     */
    private fun castSubtitleTracks(
        request: CastPrepareRequest,
        plan: PlaybackPlanV3,
        serverUrl: String,
        token: String?,
        castUrl: String,
    ): List<CastSubtitleTrack> {
        // Subtitle URLs are signed with the STREAM token from the cast URL, not
        // the account access token: the server stamps a session-scoped ?st= on
        // the stream URL only, and the subtitle route rejects anything else
        // (observed: all subtitle fetches 401'd with the access token).
        val streamToken = STREAM_TOKEN_VALUE_REGEX.find(castUrl)?.groupValues?.get(1)
        val usable = plan.subtitle.inventory.filter { it.isCastableAsVtt() }
        val labels = castSubtitleLabels(usable)
        return usable.mapIndexed { index, item ->
            val base = forceVttExtension(resolvePlaybackStreamUrl(serverUrl, item.url.orEmpty()))
            CastSubtitleTrack(
                url = if (streamToken != null && !STREAM_TOKEN_REGEX.containsMatchIn(base)) {
                    val sep = if (base.contains('?')) '&' else '?'
                    "$base${sep}st=$streamToken"
                } else {
                    signStreamUrl(base, token)
                },
                language = item.language,
                label = labels[index],
                // Activate the track the user selected on the phone. Selection
                // is by combined ordinal, the contract's own track identity —
                // never by position in this filtered list.
                selected = request.subtitleTrackIndex != null &&
                    request.subtitleTrackIndex >= 0 &&
                    item.combinedIndex == request.subtitleTrackIndex,
            )
        }
    }

    /**
     * Whether an inventory entry can reach the Cast Default Media Receiver,
     * which renders WebVTT text tracks and nothing else.
     *
     * `burn_in_only` entries carry no URL at all. Bitmap tracks that DO get a
     * sidecar URL — embedded PGS is published as `.sup` — are excluded too: the
     * subtitle route answers 415 for a bitmap track requested as `.vtt`, so
     * offering one would put a permanently-failing row in the CC menu.
     */
    private fun PlaybackSubtitleInventoryItemV3.isCastableAsVtt(): Boolean =
        delivery == SUBTITLE_DELIVERY_SIDECAR &&
            !url.isNullOrBlank() &&
            !isBitmapSubtitleCodecOrMime(codec)

    /**
     * Makes a stream URL self-contained for a Cast receiver by carrying the
     * session/access token in a `?st=` query parameter (the receiver can't send
     * the `Authorization` header the phone normally relies on).
     *
     * If the server already stamped an `st=` token onto the URL (transcode
     * plans do this today), it is used as-is.
     */
    private fun signStreamUrl(url: String, token: String?): String {
        if (token.isNullOrBlank()) return url
        if (STREAM_TOKEN_REGEX.containsMatchIn(url)) return url
        val separator = if (url.contains('?')) '&' else '?'
        val encoded = URLEncoder.encode(token, "UTF-8")
        return "$url${separator}st=$encoded"
    }

    /**
     * Human labels for the CC picker and the receiver's track metadata. The
     * server's label field is a fallback chain that bottoms out at the sidecar
     * FILENAME (release-group junk) or the codec name ("SUBRIP"), so it is
     * ignored: the language code is resolved to its display name ("da" →
     * "Danish"), forced tracks are marked, and same-language duplicates get a
     * counter ("English 2") so every menu row is distinct.
     */
    private fun castSubtitleLabels(items: List<PlaybackSubtitleInventoryItemV3>): List<String> {
        val bases = items.map { item ->
            val code = item.language?.trim()?.takeIf { it.isNotBlank() }
            val display = code?.let { c ->
                val locale = java.util.Locale.forLanguageTag(c.replace('_', '-').lowercase())
                locale.displayLanguage
                    .takeIf { it.isNotBlank() && !it.equals(locale.language, ignoreCase = true) }
            }
            val base = display
                ?: code?.replaceFirstChar { it.uppercase() }
                ?: "Subtitle"
            if (item.forced) "$base (Forced)" else base
        }
        val totals = bases.groupingBy { it }.eachCount()
        val seen = mutableMapOf<String, Int>()
        return bases.map { base ->
            val n = (seen[base] ?: 0) + 1
            seen[base] = n
            if ((totals[base] ?: 0) > 1) "$base $n" else base
        }
    }

    /**
     * Rewrites a subtitle URL's PATH EXTENSION to `.vtt`, mirroring the server's
     * own `forceSubtitleExtensionV3`.
     *
     * The extension is the whole request: the subtitle route parses the output
     * format from the last `.` of the path segment and has no `format=` query
     * parameter. An ASS track therefore has to be asked for as `.vtt` or it
     * arrives as raw SSA that the receiver cannot render.
     */
    private fun forceVttExtension(url: String): String {
        if (url.isBlank()) return url
        val queryStart = url.indexOf('?')
        val path = if (queryStart >= 0) url.substring(0, queryStart) else url
        val query = if (queryStart >= 0) url.substring(queryStart) else ""
        val lastSlash = path.lastIndexOf('/')
        val lastDot = path.lastIndexOf('.')
        val stem = if (lastDot > lastSlash) path.substring(0, lastDot) else path
        return "$stem$VTT_EXTENSION$query"
    }

    private companion object {
        private const val TAG = "CastPlaybackPreparer"
        private const val VTT_EXTENSION = ".vtt"

        /** Inventory `delivery` value for a track the stream handler can serve. */
        private const val SUBTITLE_DELIVERY_SIDECAR = "sidecar"

        private val STREAM_TOKEN_REGEX = Regex("[?&]st=")
        private val STREAM_TOKEN_VALUE_REGEX = Regex("[?&]st=([^&]+)")

        /**
         * Mime for a cast URL when the plan did not state one. HLS is the only
         * delivery this profile advertises, so anything else is a progressive
         * stream — and under the cast capability profile (mp4-only containers)
         * that is always `video/mp4`.
         */
        fun castFallbackMimeType(plan: PlaybackPlanV3): String =
            when (plan.stream.protocol) {
                PlaybackStreamProtocol.HLS -> "application/x-mpegURL"
                PlaybackStreamProtocol.HTTP_PROGRESSIVE -> "video/mp4"
            }
    }
}

/** Inputs captured from the live player state when the user starts casting. */
data class CastPrepareRequest(
    val fileId: Int,
    val profileId: String,
    val startPositionSeconds: Double,
    val audioTrackIndex: Int?,
    val subtitleTrackIndex: Int?,
    val title: String,
    val posterUrl: String?,
    val appVersion: String,
)

/** Self-contained media descriptor handed to the Cast receiver. */
data class CastMediaSpec(
    val fileId: Int,
    val streamUrl: String,
    val mimeType: String,
    val title: String,
    val posterUrl: String?,
    val positionSeconds: Double,
    val durationSeconds: Double,
    val subtitles: List<CastSubtitleTrack>,
)

data class CastSubtitleTrack(
    val url: String,
    val language: String?,
    val label: String,
    val selected: Boolean,
)

/**
 * A static, conservative codec profile every Chromecast can decode. Unlike
 * [org.siloserver.silo.common.player.PlaybackCapabilityDetector.detect], this
 * NEVER probes the phone — the phone's decoders are irrelevant to what the
 * dongle can play, which is also why the evidence tier is `declared`: nothing
 * here was measured, and a `declared` profile is deliberately not eligible for
 * audio passthrough.
 */
fun chromecastCodecCapabilities(): ClientCodecCapabilities = ClientCodecCapabilities(
    videoEvidence = CAPABILITY_EVIDENCE_DECLARED,
    audioEvidence = CAPABILITY_EVIDENCE_DECLARED,
    codecsVideo = listOf("h264"),
    codecsVideoHardware = listOf("h264"),
    codecsAudio = listOf("aac", "mp3"),
    containers = listOf("mp4"),
    maxResolution = "1080p",
    hdr = false,
    hdrDetails = null,
    audioPassthrough = null,
    videoDecode = listOf(
        VideoDecodeCapability(
            codec = "h264",
            profiles = listOf("baseline", "main", "high"),
            // Decimal-encoded H.264 levels (10 = 1.0 … 42 = 4.2), matching
            // MediaCodecCapabilitiesProbe.normalizedLevel so the server reads
            // them identically. Capped at 4.2 (1080p / ~62 Mbps).
            levels = listOf(10, 11, 12, 13, 20, 21, 22, 30, 31, 32, 40, 41, 42),
            bitDepths = listOf(8),
            hardware = true,
        ),
    ),
)

/**
 * Mirrors the shape [org.siloserver.silo.common.player.PlaybackCapabilityDetector.detectPlaybackContext]
 * produces, but declares the Chromecast-oriented delivery set instead of the
 * phone's probed one. Progressive delivery is deliberately absent — see the
 * inline note.
 */
fun chromecastPlaybackContext(appVersion: String): ClientPlaybackContext =
    ClientPlaybackContext(
        formFactor = "mobile",
        appVersion = appVersion,
        device = PlaybackDeviceContext(),
        output = PlaybackOutputContext(
            hdrDetails = null,
            audioPassthrough = null,
            currentSink = "cast_receiver",
            sinkType = "cast",
        ),
        // HLS is deliberately the ONLY delivery class. Any progressive delivery
        // (original or remux) reaches the receiver as a chunked/range-less
        // stream it cannot seek — the slider and ±30s skips restarted playback
        // from zero, and the receiver reported a live, growing duration.
        // Advertising original_http was enough for the planner to keep choosing
        // progressive remux, so it goes too: with HLS alone the server must
        // serve a VOD manifest, which the receiver seeks natively via segments.
        deliveries = mapOf(
            DELIVERY_CLASS_HLS to DeliveryCapability(
                enabled = true,
                supportedOnDevice = true,
                containers = listOf("m3u8", "hls"),
                videoCodecs = listOf("h264"),
                audioDecodeCodecs = listOf("aac", "mp3"),
                subtitles = DeliverySubtitleCapabilities(
                    embeddedText = true,
                    sidecarText = true,
                ),
                features = listOf("hls", "buffer_reporting"),
            ),
        ),
    )
