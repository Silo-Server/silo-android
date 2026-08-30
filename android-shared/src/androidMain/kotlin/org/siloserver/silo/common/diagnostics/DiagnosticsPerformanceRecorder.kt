package org.siloserver.silo.common.diagnostics

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class PerformanceWindowSnapshot(
    val frameCount: Long,
    val slowFrameCount: Long,
    val p95FrameMs: Long,
    val worstFrameMs: Long,
    val mainThreadStallCount: Long,
    val longestMainThreadStallMs: Long,
)

internal class PerformanceWindowAggregator(
    refreshPeriodMs: Double,
) {
    private val frameHistogram = LongArray(MAX_HISTOGRAM_MS + 1)
    private var refreshPeriodMs = refreshPeriodMs.coerceIn(MIN_REFRESH_PERIOD_MS, MAX_REFRESH_PERIOD_MS)
    private var frameCount = 0L
    private var slowFrameCount = 0L
    private var worstFrameMs = 0L
    private var mainThreadStallCount = 0L
    private var longestMainThreadStallMs = 0L

    @Synchronized
    fun setRefreshPeriodMs(value: Double) {
        refreshPeriodMs = value.coerceIn(MIN_REFRESH_PERIOD_MS, MAX_REFRESH_PERIOD_MS)
    }

    @Synchronized
    fun recordFrame(durationMs: Long) {
        val bounded = durationMs.coerceIn(0, MAX_DURATION_MS)
        frameCount += 1
        if (bounded.toDouble() > refreshPeriodMs * SLOW_FRAME_MULTIPLIER) slowFrameCount += 1
        worstFrameMs = maxOf(worstFrameMs, bounded)
        frameHistogram[minOf(bounded, MAX_HISTOGRAM_MS.toLong()).toInt()] += 1
    }

    @Synchronized
    fun recordMainThreadDelay(delayMs: Long) {
        val bounded = delayMs.coerceIn(0, MAX_DURATION_MS)
        if (bounded < MAIN_THREAD_STALL_MS) return
        mainThreadStallCount += 1
        longestMainThreadStallMs = maxOf(longestMainThreadStallMs, bounded)
    }

    @Synchronized
    fun snapshotAndReset(): PerformanceWindowSnapshot {
        val snapshot = PerformanceWindowSnapshot(
            frameCount = frameCount,
            slowFrameCount = slowFrameCount,
            p95FrameMs = percentile95(),
            worstFrameMs = worstFrameMs,
            mainThreadStallCount = mainThreadStallCount,
            longestMainThreadStallMs = longestMainThreadStallMs,
        )
        frameHistogram.fill(0)
        frameCount = 0
        slowFrameCount = 0
        worstFrameMs = 0
        mainThreadStallCount = 0
        longestMainThreadStallMs = 0
        return snapshot
    }

    private fun percentile95(): Long {
        if (frameCount == 0L) return 0
        val target = ((frameCount * 95) + 99) / 100
        var seen = 0L
        frameHistogram.forEachIndexed { durationMs, count ->
            seen += count
            if (seen >= target) return durationMs.toLong()
        }
        return MAX_HISTOGRAM_MS.toLong()
    }

    private companion object {
        const val MAX_HISTOGRAM_MS = 1_000
        const val MAX_DURATION_MS = 60_000L
        const val MAIN_THREAD_STALL_MS = 250L
        const val SLOW_FRAME_MULTIPLIER = 2.0
        const val MIN_REFRESH_PERIOD_MS = 4.0
        const val MAX_REFRESH_PERIOD_MS = 100.0
    }
}

interface DiagnosticsPerformanceCapture {
    fun setDetailedCaptureEnabled(enabled: Boolean)

    data object None : DiagnosticsPerformanceCapture {
        override fun setDetailedCaptureEnabled(enabled: Boolean) = Unit
    }
}

enum class DiagnosticsResourceCheckpointCause(internal val wireValue: String) {
    LIST_READY("list_ready"),
    SCROLL_START("scroll_start"),
}

object DiagnosticsResourceCheckpoints {
    private val handler = AtomicReference<(DiagnosticsListSurface, DiagnosticsResourceCheckpointCause) -> Unit>(
        { _, _ -> },
    )

    internal fun install(value: (DiagnosticsListSurface, DiagnosticsResourceCheckpointCause) -> Unit) {
        handler.set(value)
    }

    fun request(
        surface: DiagnosticsListSurface,
        cause: DiagnosticsResourceCheckpointCause = DiagnosticsResourceCheckpointCause.LIST_READY,
    ) {
        handler.get().invoke(surface, cause)
    }
}

internal class DiagnosticsCheckpointCadence(
    private val intervalMs: Long = 30_000,
) {
    private val lastEmissionByKey = mutableMapOf<String, Long>()

    init {
        require(intervalMs > 0)
    }

    @Synchronized
    fun shouldEmit(
        surface: DiagnosticsListSurface,
        cause: DiagnosticsResourceCheckpointCause,
        nowMs: Long,
    ): Boolean {
        val key = "${surface.wireValue}:${cause.wireValue}"
        val previous = lastEmissionByKey[key]
        if (previous != null && nowMs >= previous && nowMs - previous < intervalMs) return false
        lastEmissionByKey[key] = nowMs
        return true
    }
}

class AndroidDiagnosticsPerformanceRecorder(
    private val application: Application,
    private val nowElapsedMs: () -> Long = SystemClock::elapsedRealtime,
    private val processStartedAtElapsedMs: Long = android.os.Process.getStartElapsedRealtime(),
    private val sampleIntervalMs: Long = 10_000,
) : DiagnosticsPerformanceCapture, Application.ActivityLifecycleCallbacks {
    private val installed = AtomicBoolean(false)
    private val detailed = AtomicBoolean(false)
    private val firstFrameMs = AtomicLong(UNSET)
    private val window = PerformanceWindowAggregator(DEFAULT_REFRESH_PERIOD_MS)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val workerThread = HandlerThread("silo-diagnostics-performance").apply { start() }
    private val worker = Handler(workerThread.looper)
    private var resumedActivity = WeakReference<Activity>(null)
    private var attachedWindow: Window? = null
    private val startupReported = AtomicBoolean(false)
    private val checkpointCadence = DiagnosticsCheckpointCadence()
    private var heartbeatExpectedMs = 0L

    private val frameListener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
        val nanos = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
        if (nanos >= 0) window.recordFrame(nanos / NANOS_PER_MS)
    }
    private val heartbeat = object : Runnable {
        override fun run() {
            if (!detailed.get() || resumedActivity.get() == null) return
            val now = nowElapsedMs()
            window.recordMainThreadDelay((now - heartbeatExpectedMs).coerceAtLeast(0))
            heartbeatExpectedMs = now + HEARTBEAT_INTERVAL_MS
            mainHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }
    private val sampler = object : Runnable {
        override fun run() {
            if (!detailed.get() || resumedActivity.get() == null) return
            emitSnapshot()
            worker.postDelayed(this, sampleIntervalMs)
        }
    }

    fun install() {
        if (installed.compareAndSet(false, true)) {
            application.registerActivityLifecycleCallbacks(this)
            DiagnosticsResourceCheckpoints.install(::requestResourceCheckpoint)
        }
    }

    override fun setDetailedCaptureEnabled(enabled: Boolean) {
        if (detailed.getAndSet(enabled) == enabled) return
        if (enabled) startupReported.set(false)
        mainHandler.post {
            val activity = resumedActivity.get()
            if (enabled && activity != null) startDetailed(activity) else stopDetailed(flush = false)
        }
    }

    override fun onActivityCreated(activity: Activity, state: Bundle?) {
        if (firstFrameMs.get() != UNSET) return
        lateinit var listener: Window.OnFrameMetricsAvailableListener
        listener = Window.OnFrameMetricsAvailableListener { window, metrics, _ ->
            if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) {
                firstFrameMs.compareAndSet(UNSET, (nowElapsedMs() - processStartedAtElapsedMs).coerceAtLeast(0))
                mainHandler.post { runCatching { window.removeOnFrameMetricsAvailableListener(listener) } }
            }
        }
        activity.window.addOnFrameMetricsAvailableListener(listener, worker)
    }

    override fun onActivityResumed(activity: Activity) {
        resumedActivity = WeakReference(activity)
        if (detailed.get()) startDetailed(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumedActivity.get() === activity) {
            stopDetailed(flush = true)
            resumedActivity.clear()
        }
    }

    private fun startDetailed(activity: Activity) {
        val refreshRate = activity.window.decorView.display?.refreshRate?.takeIf { it > 0f } ?: 60f
        window.setRefreshPeriodMs(1_000.0 / refreshRate)
        if (attachedWindow !== activity.window) {
            attachedWindow?.let { runCatching { it.removeOnFrameMetricsAvailableListener(frameListener) } }
            activity.window.addOnFrameMetricsAvailableListener(frameListener, worker)
            attachedWindow = activity.window
        }
        mainHandler.removeCallbacks(heartbeat)
        heartbeatExpectedMs = nowElapsedMs() + HEARTBEAT_INTERVAL_MS
        mainHandler.postDelayed(heartbeat, HEARTBEAT_INTERVAL_MS)
        worker.removeCallbacks(sampler)
        worker.postDelayed(sampler, sampleIntervalMs)
    }

    private fun stopDetailed(flush: Boolean) {
        mainHandler.removeCallbacks(heartbeat)
        worker.removeCallbacks(sampler)
        attachedWindow?.let { runCatching { it.removeOnFrameMetricsAvailableListener(frameListener) } }
        attachedWindow = null
        if (flush && detailed.get()) {
            worker.post(::emitSnapshot)
        } else {
            worker.post { window.snapshotAndReset() }
        }
    }

    private fun emitSnapshot() {
        val frameSnapshot = window.snapshotAndReset()
        val memory = memorySnapshot()
        val startup = firstFrameMs.get().takeIf { it != UNSET && startupReported.compareAndSet(false, true) }
        DiagnosticsPerformanceLogger.snapshot(frameSnapshot, memory, startup)
    }

    private fun requestResourceCheckpoint(
        surface: DiagnosticsListSurface,
        cause: DiagnosticsResourceCheckpointCause,
    ) {
        val now = nowElapsedMs()
        if (!checkpointCadence.shouldEmit(surface, cause, now)) return
        worker.post {
            DiagnosticsResourceLogger.checkpoint(surface, cause, memorySnapshot())
        }
    }

    private fun memorySnapshot(): DiagnosticsResourceSnapshot {
        val runtime = Runtime.getRuntime()
        val javaHeapMb = ((runtime.totalMemory() - runtime.freeMemory()) / BYTES_PER_MIB).coerceAtLeast(0)
        val debugMemory = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        val activityManager = application.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val systemMemory = ActivityManager.MemoryInfo().also { activityManager?.getMemoryInfo(it) }
        val power = application.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return DiagnosticsResourceSnapshot(
            javaHeapMb = javaHeapMb,
            processPssMb = (debugMemory.totalPss.toLong() / KIB_PER_MIB).coerceAtLeast(0),
            lowMemory = systemMemory.lowMemory,
            thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) power?.currentThermalStatus else null,
        )
    }

    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    private companion object {
        const val UNSET = -1L
        const val NANOS_PER_MS = 1_000_000L
        const val HEARTBEAT_INTERVAL_MS = 1_000L
        const val DEFAULT_REFRESH_PERIOD_MS = 16.67
        const val BYTES_PER_MIB = 1_024L * 1_024L
        const val KIB_PER_MIB = 1_024L
    }
}

data class DiagnosticsResourceSnapshot(
    val javaHeapMb: Long,
    val processPssMb: Long,
    val lowMemory: Boolean,
    val thermalStatus: Int?,
)

object DiagnosticsResourceLogger {
    fun checkpoint(
        surface: DiagnosticsListSurface,
        cause: DiagnosticsResourceCheckpointCause,
        resources: DiagnosticsResourceSnapshot,
    ) = SiloLog.breadcrumb(
        org.siloserver.silo.model.diagnostics.DiagnosticsLogCategory.LIFECYCLE,
        "AppResources",
        "resource pressure checkpoint",
        mapOf(
            "phase" to SiloLogAttribute.Text("resource_pressure"),
            "outcome" to SiloLogAttribute.Text(resourceOutcome(resources)),
            "reason" to SiloLogAttribute.Text(
                "${surface.wireValue}_${cause.wireValue}_" +
                    "heap_${memoryBucket(resources.javaHeapMb)}_pss_${memoryBucket(resources.processPssMb)}",
            ),
        ),
    )
}

private fun resourceOutcome(resources: DiagnosticsResourceSnapshot): String = when {
    resources.lowMemory -> "low_memory"
    (resources.thermalStatus ?: 0) >= 4 -> "thermal_severe"
    (resources.thermalStatus ?: 0) >= 2 -> "thermal_elevated"
    else -> "normal"
}

internal fun memoryBucket(valueMb: Long): String = when (valueMb.coerceAtLeast(0)) {
    in 0..63 -> "0_63"
    in 64..127 -> "64_127"
    in 128..255 -> "128_255"
    in 256..511 -> "256_511"
    in 512..1_023 -> "512_1023"
    else -> "1024_plus"
}
