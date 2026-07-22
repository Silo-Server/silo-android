package org.siloserver.silo.common.diagnostics

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.siloserver.silo.common.player.PlayerStatsSnapshot
import org.siloserver.silo.common.player.video.PlaybackDiagnosticsCode
import org.siloserver.silo.model.diagnostics.DiagnosticsLogCategory
import org.siloserver.silo.network.NetworkDiagnosticsObserver

object DiagnosticsCaptureDetailState {
    private val enabled = AtomicBoolean(false)

    fun isEnabled(): Boolean = enabled.get()

    internal fun setEnabled(value: Boolean) {
        enabled.set(value)
    }
}

class DiagnosticsStatsCadence(
    private val intervalMs: Long = 5_000,
) {
    private var lastEmissionMs: Long? = null

    init {
        require(intervalMs > 0)
    }

    @Synchronized
    fun shouldEmit(detailedCapture: Boolean, nowMs: Long): Boolean {
        if (!detailedCapture) return false
        val previous = lastEmissionMs
        if (previous != null && nowMs - previous < intervalMs) return false
        lastEmissionMs = nowMs
        return true
    }
}

object DiagnosticsNetworkLogger : NetworkDiagnosticsObserver {
    override fun completed(method: String, rawPath: String, status: Int, durationMs: Long) {
        SiloLog.i(
            category = DiagnosticsLogCategory.NETWORK,
            tag = "HttpClient",
            message = "request completed",
            attributes = mapOf(
                "method" to SiloLogAttribute.Text(method.uppercase().take(12)),
                "path" to SiloLogAttribute.Text(safeDiagnosticsNetworkPath(rawPath)),
                "status" to SiloLogAttribute.Integer(status.toLong().coerceIn(0, 999)),
                "duration_ms" to SiloLogAttribute.Integer(durationMs.coerceAtLeast(0)),
            ),
        )
    }

    override fun authRefresh(state: String) {
        val message = when (state) {
            "required" -> "token refresh required"
            "started" -> "token refresh started"
            "succeeded" -> "token refresh succeeded"
            "failed" -> "token refresh failed"
            else -> return
        }
        SiloLog.i(DiagnosticsLogCategory.NETWORK, "Auth", message)
    }
}

internal fun safeDiagnosticsNetworkPath(rawPath: String): String {
    val path = rawPath.substringBefore('?').substringBefore('#')
    val segments = path.split('/').filter(String::isNotBlank)
    if (segments.size < 3 || segments[0] != "api" || !segments[1].matches(API_VERSION)) {
        return "/other"
    }
    if ('?' in rawPath || '#' in rawPath) return "/api/${segments[1]}/other"
    val resource = segments[2].lowercase()
    val tail = segments.drop(3)
    val template = API_ROUTE_TEMPLATES[resource]
        ?.firstOrNull { candidate ->
            candidate.size == tail.size && candidate.indices.all { index ->
                candidate[index] == DYNAMIC_ROUTE_SEGMENT || candidate[index] == tail[index].lowercase()
            }
        }
        ?: return "/api/${segments[1]}/other"
    return (listOf("", "api", segments[1], resource) + template).joinToString("/")
}

object DiagnosticsPlaybackLogger {
    fun sessionEvent(message: String) = playbackInfo(message)

    fun startFailure(code: PlaybackDiagnosticsCode?) = playbackInfo(
        "video start failed",
        code?.let { mapOf("failure_code" to SiloLogAttribute.Text(it.wireValue)) }.orEmpty(),
    )

    fun startReady(durationMs: Long) {
        if (DiagnosticsCaptureDetailState.isEnabled()) {
            playbackInfo(
                "video start ready",
                mapOf("startup_ready_ms" to SiloLogAttribute.Integer(durationMs.coerceAtLeast(0))),
            )
        }
    }

    fun firstFrame(durationMs: Long) {
        if (DiagnosticsCaptureDetailState.isEnabled()) {
            playbackInfo(
                "first video frame rendered",
                mapOf("first_frame_ms" to SiloLogAttribute.Integer(durationMs.coerceAtLeast(0))),
            )
        }
    }

    fun videoDecoderInitialized(decoderName: String) = playbackInfo(
        "video decoder initialized",
        mapOf("decoder" to SiloLogAttribute.Text(decoderName)),
    )

    fun audioDecoderInitialized(decoderName: String) = playbackInfo(
        "audio decoder initialized",
        mapOf("decoder" to SiloLogAttribute.Text(decoderName)),
    )

    fun videoFormatChanged(format: String?, width: Int, height: Int, hdrMode: String?) = playbackInfo(
        "video format changed",
        buildMap {
            format?.let { put("fmt", SiloLogAttribute.Text(it)) }
            width.takeIf { it > 0 }?.let { put("width", SiloLogAttribute.Integer(it.toLong())) }
            height.takeIf { it > 0 }?.let { put("height", SiloLogAttribute.Integer(it.toLong())) }
            hdrMode?.let { put("hdr_mode", SiloLogAttribute.Text(it)) }
        },
    )

    fun audioFormatChanged(format: String?) = playbackInfo(
        "audio format changed",
        format?.let { mapOf("fmt" to SiloLogAttribute.Text(it)) }.orEmpty(),
    )

    fun tracksChanged() = playbackInfo("tracks changed")

    fun droppedFrames(count: Int) = SiloLog.w(
        DiagnosticsLogCategory.PLAYBACK,
        "Media3Analytics",
        "video frames dropped",
        mapOf("dropped_frames" to SiloLogAttribute.Integer(count.coerceAtLeast(0).toLong())),
    )

    fun audioUnderrun() = SiloLog.w(DiagnosticsLogCategory.PLAYBACK, "Media3Analytics", "audio underrun")

    fun playerError() = SiloLog.e(DiagnosticsLogCategory.PLAYBACK, "Media3Analytics", "player error")

    fun loadError() = SiloLog.w(DiagnosticsLogCategory.PLAYBACK, "Media3Analytics", "media load error")

    fun statsSnapshot(snapshot: PlayerStatsSnapshot) = playbackInfo(
        "player stats snapshot",
        buildMap {
            snapshot.videoDecoderName?.let { put("decoder", SiloLogAttribute.Text(it)) }
            snapshot.videoMimeType?.let { put("fmt", SiloLogAttribute.Text(it)) }
            snapshot.videoWidth?.let { put("width", SiloLogAttribute.Integer(it.toLong())) }
            snapshot.videoHeight?.let { put("height", SiloLogAttribute.Integer(it.toLong())) }
            snapshot.hdrMode?.let { put("hdr_mode", SiloLogAttribute.Text(it)) }
            snapshot.bitrateBps?.let { put("bitrate_kbps", SiloLogAttribute.Integer((it / 1_000).coerceAtLeast(0))) }
            put("dropped_frames", SiloLogAttribute.Integer(snapshot.droppedFrames.coerceAtLeast(0).toLong()))
            put("audio_underruns", SiloLogAttribute.Integer(snapshot.audioUnderruns.coerceAtLeast(0).toLong()))
            snapshot.startupReadyMs?.let { put("startup_ready_ms", SiloLogAttribute.Integer(it.coerceAtLeast(0))) }
            snapshot.firstFrameMs?.let { put("first_frame_ms", SiloLogAttribute.Integer(it.coerceAtLeast(0))) }
            snapshot.bufferedDurationMs?.let { put("buffered_ms", SiloLogAttribute.Integer(it.coerceAtLeast(0))) }
            put("rebuffer_count", SiloLogAttribute.Integer(snapshot.rebufferCount.coerceAtLeast(0).toLong()))
            put("rebuffer_total_ms", SiloLogAttribute.Integer(snapshot.rebufferTotalMs.coerceAtLeast(0)))
            put("rebuffer_max_ms", SiloLogAttribute.Integer(snapshot.rebufferMaxMs.coerceAtLeast(0)))
            put("seek_count", SiloLogAttribute.Integer(snapshot.seekCount.coerceAtLeast(0).toLong()))
            snapshot.lastSeekDurationMs?.let { put("seek_last_ms", SiloLogAttribute.Integer(it.coerceAtLeast(0))) }
            put("seek_total_ms", SiloLogAttribute.Integer(snapshot.seekTotalMs.coerceAtLeast(0)))
            put("seek_max_ms", SiloLogAttribute.Integer(snapshot.seekMaxMs.coerceAtLeast(0)))
        },
    )

    fun finalStats(snapshot: PlayerStatsSnapshot) = playbackInfo(
        "player final stats",
        buildMap {
            snapshot.startupReadyMs?.let { put("startup_ready_ms", SiloLogAttribute.Integer(it.coerceAtLeast(0))) }
            snapshot.firstFrameMs?.let { put("first_frame_ms", SiloLogAttribute.Integer(it.coerceAtLeast(0))) }
            snapshot.bufferedDurationMs?.let { put("buffered_ms", SiloLogAttribute.Integer(it.coerceAtLeast(0))) }
            snapshot.bitrateBps?.let { put("bitrate_kbps", SiloLogAttribute.Integer((it / 1_000).coerceAtLeast(0))) }
            put("rebuffer_count", SiloLogAttribute.Integer(snapshot.rebufferCount.coerceAtLeast(0).toLong()))
            put("rebuffer_total_ms", SiloLogAttribute.Integer(snapshot.rebufferTotalMs.coerceAtLeast(0)))
            put("rebuffer_max_ms", SiloLogAttribute.Integer(snapshot.rebufferMaxMs.coerceAtLeast(0)))
            put("seek_count", SiloLogAttribute.Integer(snapshot.seekCount.coerceAtLeast(0).toLong()))
            snapshot.lastSeekDurationMs?.let { put("seek_last_ms", SiloLogAttribute.Integer(it.coerceAtLeast(0))) }
            put("seek_total_ms", SiloLogAttribute.Integer(snapshot.seekTotalMs.coerceAtLeast(0)))
            put("seek_max_ms", SiloLogAttribute.Integer(snapshot.seekMaxMs.coerceAtLeast(0)))
            put("dropped_frames", SiloLogAttribute.Integer(snapshot.droppedFrames.coerceAtLeast(0).toLong()))
            put("audio_underruns", SiloLogAttribute.Integer(snapshot.audioUnderruns.coerceAtLeast(0).toLong()))
        },
    )

    private fun playbackInfo(
        message: String,
        attributes: Map<String, SiloLogAttribute> = emptyMap(),
    ) = SiloLog.i(DiagnosticsLogCategory.PLAYBACK, "Media3Analytics", message, attributes)
}

object DiagnosticsLifecycleLogger {
    fun state(state: String) = SiloLog.breadcrumb(
        DiagnosticsLogCategory.LIFECYCLE,
        "AppLifecycle",
        "app lifecycle changed",
        mapOf("state" to SiloLogAttribute.Text(state)),
    )

    fun route(rawRoute: String?) {
        val identifier = rawRoute
            ?.substringBefore('/')
            ?.substringBefore('?')
            ?.lowercase()
            ?.takeIf(KNOWN_ROUTE_ROOTS::contains)
            ?: "unknown"
        DiagnosticsPerformanceRoute.update(identifier)
        state("route:$identifier")
    }
}

private object DiagnosticsPerformanceRoute {
    private val route = AtomicReference("unknown")

    fun update(value: String) = route.set(value.take(64))
    fun current(): String = route.get()
}

object DiagnosticsPerformanceLogger {
    fun snapshot(
        frames: PerformanceWindowSnapshot,
        resources: DiagnosticsResourceSnapshot,
        startupFirstFrameMs: Long?,
    ) = SiloLog.i(
        DiagnosticsLogCategory.LIFECYCLE,
        "AppPerformance",
        "app performance snapshot",
        buildMap {
            put("route", SiloLogAttribute.Text(DiagnosticsPerformanceRoute.current()))
            put("frame_count", SiloLogAttribute.Integer(frames.frameCount.coerceAtLeast(0)))
            put("slow_frame_count", SiloLogAttribute.Integer(frames.slowFrameCount.coerceAtLeast(0)))
            put("p95_frame_ms", SiloLogAttribute.Integer(frames.p95FrameMs.coerceAtLeast(0)))
            put("worst_frame_ms", SiloLogAttribute.Integer(frames.worstFrameMs.coerceAtLeast(0)))
            put("main_stall_count", SiloLogAttribute.Integer(frames.mainThreadStallCount.coerceAtLeast(0)))
            put("main_stall_max_ms", SiloLogAttribute.Integer(frames.longestMainThreadStallMs.coerceAtLeast(0)))
            put("java_heap_mb", SiloLogAttribute.Integer(resources.javaHeapMb.coerceAtLeast(0)))
            put("process_pss_mb", SiloLogAttribute.Integer(resources.processPssMb.coerceAtLeast(0)))
            put("low_memory", SiloLogAttribute.Flag(resources.lowMemory))
            resources.thermalStatus?.let { put("thermal_status", SiloLogAttribute.Integer(it.toLong().coerceAtLeast(0))) }
            startupFirstFrameMs?.let { put("startup_first_frame_ms", SiloLogAttribute.Integer(it.coerceAtLeast(0))) }
        },
    )
}

object DiagnosticsCastLogger {
    fun event(message: String) = SiloLog.i(DiagnosticsLogCategory.CAST, "Cast", message)

    fun warning(message: String) = SiloLog.w(DiagnosticsLogCategory.CAST, "Cast", message)
}

object DiagnosticsDownloadLogger {
    fun event(message: String) = SiloLog.i(DiagnosticsLogCategory.DOWNLOAD, "DownloadWorker", message)

    fun warning(message: String) = SiloLog.w(DiagnosticsLogCategory.DOWNLOAD, "DownloadWorker", message)

    fun error(message: String) = SiloLog.e(DiagnosticsLogCategory.DOWNLOAD, "DownloadWorker", message)
}

object DiagnosticsFocusLogger {
    fun transition(target: String, action: String) = SiloLog.d(
        DiagnosticsLogCategory.FOCUS,
        "TvShellFocus",
        "focus transition",
        mapOf(
            "target" to SiloLogAttribute.Text(target),
            "action" to SiloLogAttribute.Text(action),
        ),
    )
}

private val API_VERSION = Regex("v[0-9]+")
private val KNOWN_ROUTE_ROOTS = setOf(
    "admin",
    "audiobook",
    "browse",
    "calendar",
    "collection",
    "collections",
    "diagnostics",
    "downloads",
    "favorites",
    "history",
    "home",
    "inbox",
    "item",
    "libraries",
    "library",
    "login",
    "main",
    "pair_device",
    "person",
    "personal_lists",
    "player",
    "profiles",
    "reader",
    "recommendations",
    "requests",
    "search",
    "server_first_setup",
    "server_list",
    "server_setup",
    "settings",
    "setup",
    "signup",
    "silocast",
    "watch_together",
    "watchlist",
)
private const val DYNAMIC_ROUTE_SEGMENT = "{id}"
private val API_ROUTE_TEMPLATES: Map<String, List<List<String>>> = mapOf(
    "admin" to listOf(emptyList(), listOf("stats"), listOf("users"), listOf("users", DYNAMIC_ROUTE_SEGMENT),
        listOf("sessions"), listOf("sessions", DYNAMIC_ROUTE_SEGMENT, DYNAMIC_ROUTE_SEGMENT),
        listOf("logs", "app"), listOf("logs", "audit")),
    "auth" to listOf(emptyList(), listOf("login"), listOf("refresh"), listOf("setup"), listOf("signup"),
        listOf("me"), listOf("logout"), listOf("device"), listOf("device", "capability"),
        listOf("device", "start"), listOf("device", "poll"), listOf("device", "approve"),
        listOf("device", "deny"), listOf("device", "approve-handoff")),
    "calendar" to listOf(emptyList()),
    "catalog" to listOf(emptyList(), listOf("home"), listOf("filters"), listOf("audiobook-groups"),
        listOf("items", DYNAMIC_ROUTE_SEGMENT)),
    "collections" to listOf(emptyList(), listOf(DYNAMIC_ROUTE_SEGMENT),
        listOf(DYNAMIC_ROUTE_SEGMENT, "items", DYNAMIC_ROUTE_SEGMENT), listOf("groups"),
        listOf("groups", DYNAMIC_ROUTE_SEGMENT), listOf("groups", "order"), listOf("order")),
    "diagnostics" to listOf(listOf("status"), listOf("reports")),
    "downloads" to listOf(emptyList(), listOf("capability"), listOf(DYNAMIC_ROUTE_SEGMENT),
        listOf(DYNAMIC_ROUTE_SEGMENT, "file")),
    "ebooks" to listOf(listOf("capability"), listOf(DYNAMIC_ROUTE_SEGMENT),
        listOf(DYNAMIC_ROUTE_SEGMENT, "files", DYNAMIC_ROUTE_SEGMENT, "read"),
        listOf(DYNAMIC_ROUTE_SEGMENT, "progress")),
    "events" to listOf(listOf("ws"), listOf("ws-ticket")),
    "health" to listOf(emptyList()),
    "home" to listOf(listOf("layout"), listOf("sections"),
        listOf("sections", DYNAMIC_ROUTE_SEGMENT, "items"),
        listOf("dismissals", "continue_watching", DYNAMIC_ROUTE_SEGMENT),
        listOf("dismissals", "next_up", DYNAMIC_ROUTE_SEGMENT)),
    "items" to listOf(listOf(DYNAMIC_ROUTE_SEGMENT), listOf(DYNAMIC_ROUTE_SEGMENT, "translate-description")),
    "libraries" to listOf(emptyList(), listOf("scan"), listOf("scan", "cancel")),
    "library" to listOf(listOf(DYNAMIC_ROUTE_SEGMENT, "sections"),
        listOf(DYNAMIC_ROUTE_SEGMENT, "sections", DYNAMIC_ROUTE_SEGMENT, "items"),
        listOf(DYNAMIC_ROUTE_SEGMENT, "collections")),
    "notifications" to listOf(emptyList(), listOf("sync"), listOf("unread-count"), listOf("read-all"),
        listOf("preferences"), listOf("capability"), listOf(DYNAMIC_ROUTE_SEGMENT),
        listOf(DYNAMIC_ROUTE_SEGMENT, "read"), listOf("push", "devices")),
    "people" to listOf(listOf(DYNAMIC_ROUTE_SEGMENT)),
    "playback" to listOf(listOf("start"), listOf("route-events"), listOf("transcode", "start"),
        listOf(DYNAMIC_ROUTE_SEGMENT), listOf(DYNAMIC_ROUTE_SEGMENT, "progress"),
        listOf(DYNAMIC_ROUTE_SEGMENT, "replan"),
        listOf("sessions", DYNAMIC_ROUTE_SEGMENT, "control", "ws")),
    "profiles" to listOf(emptyList(), listOf(DYNAMIC_ROUTE_SEGMENT),
        listOf(DYNAMIC_ROUTE_SEGMENT, "verify-pin")),
    "progress" to listOf(emptyList()),
    "ratings" to listOf(emptyList(), listOf(DYNAMIC_ROUTE_SEGMENT)),
    "recommendations" to listOf(listOf("discover"), listOf("taste-profile"),
        listOf("similar", DYNAMIC_ROUTE_SEGMENT)),
    "requests" to listOf(emptyList(), listOf("status"), listOf("discover"),
        listOf("discover", DYNAMIC_ROUTE_SEGMENT), listOf("search"), listOf("mine"),
        listOf("detail", DYNAMIC_ROUTE_SEGMENT, DYNAMIC_ROUTE_SEGMENT), listOf(DYNAMIC_ROUTE_SEGMENT),
        listOf(DYNAMIC_ROUTE_SEGMENT, "cancel")),
    "settings" to listOf(emptyList(), listOf("overlay-config"), listOf("effective"),
        listOf("subtitle_appearance", "effective"), listOf("device", DYNAMIC_ROUTE_SEGMENT),
        listOf(DYNAMIC_ROUTE_SEGMENT)),
    "stream" to listOf(listOf(DYNAMIC_ROUTE_SEGMENT),
        listOf(DYNAMIC_ROUTE_SEGMENT, "subtitles", DYNAMIC_ROUTE_SEGMENT)),
    "subtitles" to listOf(listOf("search"), listOf("download"), listOf(DYNAMIC_ROUTE_SEGMENT),
        listOf("ai", "status"), listOf("ai", "quota"), listOf("ai", "translate"),
        listOf("ai", "jobs"), listOf("ai", "jobs", DYNAMIC_ROUTE_SEGMENT),
        listOf("ai", "jobs", DYNAMIC_ROUTE_SEGMENT, "cancel")),
    "sync" to listOf(listOf("progress")),
    "user" to listOf(listOf("libraries")),
    "users" to listOf(listOf(DYNAMIC_ROUTE_SEGMENT), listOf(DYNAMIC_ROUTE_SEGMENT, "avatar.png")),
    "watched" to listOf(listOf(DYNAMIC_ROUTE_SEGMENT)),
    "watch-together" to listOf(listOf("rooms"), listOf("join"), listOf("rooms", DYNAMIC_ROUTE_SEGMENT),
        listOf("rooms", DYNAMIC_ROUTE_SEGMENT, "selection"), listOf("rooms", DYNAMIC_ROUTE_SEGMENT, "policy"),
        listOf("rooms", DYNAMIC_ROUTE_SEGMENT, "suggestions"),
        listOf("rooms", DYNAMIC_ROUTE_SEGMENT, "suggestions", DYNAMIC_ROUTE_SEGMENT),
        listOf("rooms", DYNAMIC_ROUTE_SEGMENT, "suggestions", DYNAMIC_ROUTE_SEGMENT, "vote"),
        listOf("rooms", DYNAMIC_ROUTE_SEGMENT, "suggestions", "promote"),
        listOf("rooms", DYNAMIC_ROUTE_SEGMENT, "ws")),
)
