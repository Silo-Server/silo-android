package org.siloserver.silo.common.diagnostics.crash

import android.app.Application
import android.content.Context
import android.os.Process
import android.util.Log
import org.siloserver.silo.common.diagnostics.CrashContextCache
import org.siloserver.silo.common.diagnostics.appendJsonEscaped
import org.siloserver.silo.common.diagnostics.logging.SiloLog
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * JVM crash capture: an UncaughtExceptionHandler that writes ONE bounded
 * marker file on the dying thread and then chains to the prior handler.
 *
 * Rules for everything on the dying-thread path:
 *  - no coroutines, no DataStore, no kotlinx-serialization, no locks shared
 *    with live threads — the ring snapshot is lock-free, the session/device
 *    context is pre-rendered `@Volatile` state spliced verbatim;
 *  - every fragment has a fixed byte budget; object-shaped fragments are
 *    all-or-nothing (a truncated JSON object would corrupt the marker),
 *    ring lines are dropped whole, string fragments truncate by length;
 *  - one atomic write: temp file → fsync → rename;
 *  - the prior handler always runs, in a finally.
 *
 * Markers are raw evidence, app-private, never uploaded directly: redaction
 * runs at next-launch assembly ([CrashMarkerAssembler]) — the mandatory choke
 * point before anything reaches a pending report.
 */
class CrashCapture private constructor(
    private val markerDir: File,
    private val processName: String,
    private val priorHandler: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            writeMarkerBestEffort(thread, throwable)
        } catch (_: Throwable) {
            // Marker writing must never mask the crash handling below.
        } finally {
            val prior = priorHandler
            if (prior != null) {
                prior.uncaughtException(thread, throwable)
            } else {
                // The runtime installs a default handler, so this is unreachable
                // in practice — but never leave a crashed process alive.
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }

    private fun writeMarkerBestEffort(thread: Thread, throwable: Throwable) {
        enforceMarkerCap()

        // Bounded stack text. Log.getStackTraceString handles cause chains and
        // circular references; the take() bounds a pathological output.
        val stack = Log.getStackTraceString(throwable).take(STACK_BUDGET_CHARS)
        val message = throwable.message?.take(MESSAGE_BUDGET_CHARS) ?: ""

        val ringSnapshot = SiloLog.ringSnapshotForCrash(RING_LINE_BUDGET_COUNT)
        val session = CrashContextCache.session
        val device = CrashContextCache.deviceSnapshotJson
            ?.takeIf { it.size <= DEVICE_BUDGET_BYTES }

        val sb = StringBuilder(128 * 1024)
        sb.append("{\"marker_version\":1,")
        sb.append("\"written_at_ms\":").append(System.currentTimeMillis()).append(',')
        sb.append("\"process_name\":"); sb.appendJsonEscaped(processName); sb.append(',')
        sb.append("\"pid\":").append(Process.myPid()).append(',')
        sb.append("\"capture_session_id\":"); sb.appendJsonEscaped(SiloLog.captureSessionId); sb.append(',')
        sb.append("\"thread_name\":"); sb.appendJsonEscaped(thread.name); sb.append(',')
        sb.append("\"exception_class\":"); sb.appendJsonEscaped(throwable.javaClass.name); sb.append(',')
        sb.append("\"exception_message\":"); sb.appendJsonEscaped(message); sb.append(',')
        sb.append("\"stack_text\":"); sb.appendJsonEscaped(stack); sb.append(',')
        sb.append("\"foreground\":").append(CrashContextCache.foreground.get()).append(',')
        sb.append("\"session\":").append(session?.prerenderedJson ?: "null").append(',')
        // Spliced verbatim (already-rendered JSON object), all-or-nothing.
        sb.append("\"device_snapshot\":")
        if (device != null) sb.append(String(device, Charsets.UTF_8)) else sb.append("null")
        sb.append(',')
        sb.append("\"playback_session_ids\":[")
        CrashContextCache.recentPlaybackSessionIds.forEachIndexed { i, id ->
            if (i > 0) sb.append(',')
            sb.appendJsonEscaped(id)
        }
        sb.append("],")
        sb.append("\"dropped_lines\":").append(ringSnapshot.droppedCount).append(',')
        sb.append("\"log_lines\":[")
        // Pre-rendered JSON lines spliced verbatim; drop OLDEST whole lines to
        // fit the byte budget, never cut inside one.
        var budget = RING_BYTE_BUDGET
        val kept = ArrayList<ByteArray>(ringSnapshot.lines.size)
        for (line in ringSnapshot.lines.asReversed()) {
            if (budget - line.size - 1 < 0) break
            budget -= line.size + 1
            kept.add(line)
        }
        kept.asReversed().forEachIndexed { i, line ->
            if (i > 0) sb.append(',')
            sb.append(String(line, Charsets.UTF_8))
        }
        sb.append("]}")

        writeAtomic(sb.toString().toByteArray(Charsets.UTF_8))
    }

    private fun enforceMarkerCap() {
        val existing = markerDir.listFiles { f -> f.name.endsWith(".json") } ?: return
        if (existing.size >= MAX_PENDING_MARKERS) {
            existing.sortedBy { it.lastModified() }
                .take(existing.size - MAX_PENDING_MARKERS + 1)
                .forEach { it.delete() }
        }
    }

    private fun writeAtomic(bytes: ByteArray) {
        // Temp name from pid+nanoTime: no SecureRandom/UUID entropy dependency.
        val base = "marker-${Process.myPid()}-${System.nanoTime()}"
        val tmp = File(markerDir, "$base.tmp")
        val target = File(markerDir, "$base.json")
        FileOutputStream(tmp).use { stream ->
            stream.write(bytes)
            // Deliberate bounded cost (single-digit ms) for durability of the
            // rarest, highest-value artifact.
            stream.fd.sync()
        }
        tmp.renameTo(target)
    }

    companion object {
        private val installed = AtomicBoolean(false)

        /**
         * Install as the very first thing in Application.onCreate, before
         * Koin. Idempotent; captures the prior default handler exactly once.
         */
        fun install(context: Context) {
            if (!installed.compareAndSet(false, true)) return
            val markerDir = markerDir(context)
            runCatching { markerDir.mkdirs() } // off the crash path
            val prior = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler(
                CrashCapture(markerDir, processNameSafe(context), prior),
            )
        }

        fun markerDir(context: Context): File =
            File(File(context.noBackupFilesDir, "diagnostics"), "crash-markers")

        private fun processNameSafe(context: Context): String =
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                runCatching { Application.getProcessName() }.getOrNull() ?: context.packageName
            } else {
                context.packageName
            }

        const val MAX_PENDING_MARKERS = 3
        private const val STACK_BUDGET_CHARS = 64 * 1024
        private const val MESSAGE_BUDGET_CHARS = 4 * 1024
        private const val DEVICE_BUDGET_BYTES = 96 * 1024
        private const val RING_LINE_BUDGET_COUNT = 400
        private const val RING_BYTE_BUDGET = 256 * 1024
    }
}
