package org.siloserver.silo.common.diagnostics

import android.os.SystemClock
import java.io.IOException
import java.net.SocketTimeoutException
import org.siloserver.silo.model.diagnostics.DiagnosticsLogCategory

enum class DiagnosticsImageSource(internal val wireValue: String) {
    MEMORY("memory"),
    DISK("disk"),
    NETWORK("network"),
}

enum class DiagnosticsImageStage(internal val wireValue: String) {
    FETCH("fetch"),
    DECODE("decode"),
    UNKNOWN("unknown"),
}

enum class DiagnosticsImageFailureKind(internal val wireValue: String) {
    HTTP("http"),
    TIMEOUT("timeout"),
    OUT_OF_MEMORY("out_of_memory"),
    DECODE("decode"),
    IO("io"),
    OTHER("other"),
}

internal data class DiagnosticsImageWindow(
    val completed: Int,
    val failures: Int,
    val memorySuccesses: Int,
    val diskSuccesses: Int,
    val networkSuccesses: Int,
)

internal data class DiagnosticsImageFailure(
    val stage: DiagnosticsImageStage,
    val kind: DiagnosticsImageFailureKind,
)

internal data class DiagnosticsImageEvents(
    val failure: DiagnosticsImageFailure?,
    val window: DiagnosticsImageWindow?,
)

internal class DiagnosticsImageHealthAccumulator(
    private val windowSize: Int = 50,
    private val failureIntervalMs: Long = 60_000,
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
) {
    private var completed = 0
    private var failures = 0
    private var memorySuccesses = 0
    private var diskSuccesses = 0
    private var networkSuccesses = 0
    private var lastFailureEmissionMs: Long? = null

    init {
        require(windowSize > 0)
        require(failureIntervalMs > 0)
    }

    @Synchronized
    fun success(source: DiagnosticsImageSource): DiagnosticsImageEvents {
        completed += 1
        when (source) {
            DiagnosticsImageSource.MEMORY -> memorySuccesses += 1
            DiagnosticsImageSource.DISK -> diskSuccesses += 1
            DiagnosticsImageSource.NETWORK -> networkSuccesses += 1
        }
        return DiagnosticsImageEvents(failure = null, window = completeWindowIfReady())
    }

    @Synchronized
    fun failure(
        stage: DiagnosticsImageStage,
        kind: DiagnosticsImageFailureKind,
    ): DiagnosticsImageEvents {
        completed += 1
        failures += 1
        val now = nowMs()
        val previous = lastFailureEmissionMs
        val shouldEmit = previous == null || now < previous || now - previous >= failureIntervalMs
        val emission = if (shouldEmit) {
            lastFailureEmissionMs = now
            DiagnosticsImageFailure(stage, kind)
        } else {
            null
        }
        return DiagnosticsImageEvents(emission, completeWindowIfReady())
    }

    private fun completeWindowIfReady(): DiagnosticsImageWindow? {
        if (completed < windowSize) return null
        val snapshot = DiagnosticsImageWindow(
            completed = completed,
            failures = failures,
            memorySuccesses = memorySuccesses,
            diskSuccesses = diskSuccesses,
            networkSuccesses = networkSuccesses,
        )
        completed = 0
        failures = 0
        memorySuccesses = 0
        diskSuccesses = 0
        networkSuccesses = 0
        return snapshot
    }
}

object DiagnosticsImageHealth {
    private val accumulator = DiagnosticsImageHealthAccumulator()

    fun success(source: DiagnosticsImageSource) = emit(accumulator.success(source))

    fun failure(stage: DiagnosticsImageStage, throwable: Throwable) = emit(
        accumulator.failure(stage, classifyImageFailure(stage, throwable)),
    )

    private fun emit(events: DiagnosticsImageEvents) {
        events.failure?.let(DiagnosticsImageLogger::failure)
        events.window?.let(DiagnosticsImageLogger::window)
    }
}

internal object DiagnosticsImageLogger {
    fun failure(failure: DiagnosticsImageFailure) = SiloLog.breadcrumb(
        DiagnosticsLogCategory.LIFECYCLE,
        "ImagePipeline",
        "image pipeline failure",
        mapOf(
            "phase" to SiloLogAttribute.Text("image_failure"),
            "outcome" to SiloLogAttribute.Text(failure.stage.wireValue),
            "reason" to SiloLogAttribute.Text(failure.kind.wireValue),
        ),
    )

    fun window(window: DiagnosticsImageWindow) = SiloLog.breadcrumb(
        DiagnosticsLogCategory.LIFECYCLE,
        "ImagePipeline",
        "image pipeline health snapshot",
        mapOf(
            "phase" to SiloLogAttribute.Text("image_pipeline"),
            "outcome" to SiloLogAttribute.Text(failureRateBucket(window.failures, window.completed)),
            "reason" to SiloLogAttribute.Text(sourceMix(window)),
        ),
    )
}

internal fun classifyImageFailure(
    stage: DiagnosticsImageStage,
    throwable: Throwable,
): DiagnosticsImageFailureKind {
    val causes = generateSequence(throwable) { it.cause }.take(8).toList()
    return when {
        causes.any { it is OutOfMemoryError } -> DiagnosticsImageFailureKind.OUT_OF_MEMORY
        causes.any { it is SocketTimeoutException } -> DiagnosticsImageFailureKind.TIMEOUT
        causes.any { cause ->
            val name = cause.javaClass.name
            name.contains("HttpException") || name.contains("HttpResponse")
        } -> DiagnosticsImageFailureKind.HTTP
        stage == DiagnosticsImageStage.DECODE -> DiagnosticsImageFailureKind.DECODE
        causes.any { it is IOException } -> DiagnosticsImageFailureKind.IO
        else -> DiagnosticsImageFailureKind.OTHER
    }
}

private fun failureRateBucket(failures: Int, completed: Int): String {
    if (completed <= 0 || failures <= 0) return "no_failures"
    val percent = failures * 100 / completed
    return when (percent) {
        in 0..9 -> "failure_rate_under_10"
        in 10..24 -> "failure_rate_10_24"
        else -> "failure_rate_25_plus"
    }
}

private fun sourceMix(window: DiagnosticsImageWindow): String {
    val successes = (window.completed - window.failures).coerceAtLeast(0)
    return "memory_${shareBucket(window.memorySuccesses, successes)}_" +
        "disk_${shareBucket(window.diskSuccesses, successes)}_" +
        "network_${shareBucket(window.networkSuccesses, successes)}"
}

private fun shareBucket(count: Int, total: Int): String {
    if (total <= 0 || count <= 0) return "0"
    val percent = (count.toLong() * 100 / total).coerceAtLeast(1)
    return when (percent) {
        in 1L..24L -> "1_24"
        in 25L..49L -> "25_49"
        in 50L..74L -> "50_74"
        else -> "75_plus"
    }
}
