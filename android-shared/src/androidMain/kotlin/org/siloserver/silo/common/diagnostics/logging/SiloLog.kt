package org.siloserver.silo.common.diagnostics.logging

import android.util.Log
import org.siloserver.silo.common.BuildConfig
import org.siloserver.silo.model.diagnostics.DiagnosticsAttrRegistry
import org.siloserver.silo.model.diagnostics.DiagnosticsAttrRegistry.Attr
import org.siloserver.silo.model.diagnostics.DiagnosticsLogCategory
import org.siloserver.silo.model.diagnostics.DiagnosticsLogLevel
import java.util.UUID

/**
 * Safe-logging facade for client diagnostics.
 *
 * Every call always forwards to [android.util.Log] unchanged (today's logcat
 * behavior), then offers a sanitized, pre-rendered JSON line to the always-on
 * in-memory ring, to the debug-mode segment file logger when installed, and —
 * for [breadcrumb] calls — to the persistent breadcrumb journal when installed.
 *
 * Redaction happens here, at collection time: free-text `msg`/`tag` pass
 * through [DiagRedactor], attributes are typed and checked against
 * [DiagnosticsAttrRegistry] (unregistered keys throw in debug builds and are
 * dropped in release), and throwables are folded into `msg` via a sanitizing
 * single-line formatter. Unmigrated `Log.*` call sites elsewhere in the app
 * simply never enter diagnostics bundles.
 */
object SiloLog {

    /** Stable per-app-run id, stamped on every line and on report manifests. */
    val captureSessionId: String = "run_" + UUID.randomUUID().toString().replace("-", "")

    @Volatile
    private var ring = LogRing()

    @Volatile
    private var fileLogger: DiagnosticsFileLogger? = null

    @Volatile
    private var breadcrumbJournal: BreadcrumbJournal? = null

    /** Debug builds throw on unregistered attrs; release drops them silently. */
    @Volatile
    var strictAttrs: Boolean = BuildConfig.DEBUG

    fun v(cat: DiagnosticsLogCategory, tag: String, msg: String, attrs: Map<String, Attr> = emptyMap()) =
        log(DiagnosticsLogLevel.V, cat, tag, msg, null, attrs, breadcrumb = false)

    fun d(cat: DiagnosticsLogCategory, tag: String, msg: String, attrs: Map<String, Attr> = emptyMap()) =
        log(DiagnosticsLogLevel.D, cat, tag, msg, null, attrs, breadcrumb = false)

    fun i(cat: DiagnosticsLogCategory, tag: String, msg: String, attrs: Map<String, Attr> = emptyMap()) =
        log(DiagnosticsLogLevel.I, cat, tag, msg, null, attrs, breadcrumb = false)

    fun w(
        cat: DiagnosticsLogCategory,
        tag: String,
        msg: String,
        throwable: Throwable? = null,
        attrs: Map<String, Attr> = emptyMap(),
    ) = log(DiagnosticsLogLevel.W, cat, tag, msg, throwable, attrs, breadcrumb = false)

    fun e(
        cat: DiagnosticsLogCategory,
        tag: String,
        msg: String,
        throwable: Throwable? = null,
        attrs: Map<String, Attr> = emptyMap(),
    ) = log(DiagnosticsLogLevel.E, cat, tag, msg, throwable, attrs, breadcrumb = false)

    /**
     * A curated pre-crash context event (lifecycle transition, playback
     * start/stop, screen change): logged normally *and* appended to the
     * persistent breadcrumb journal so next-launch reports (ANR, native crash)
     * still carry context after the in-memory ring is gone.
     */
    fun breadcrumb(cat: DiagnosticsLogCategory, tag: String, msg: String, attrs: Map<String, Attr> = emptyMap()) =
        log(DiagnosticsLogLevel.I, cat, tag, msg, null, attrs, breadcrumb = true)

    private fun log(
        level: DiagnosticsLogLevel,
        cat: DiagnosticsLogCategory,
        tag: String,
        msg: String,
        throwable: Throwable?,
        attrs: Map<String, Attr>,
        breadcrumb: Boolean,
    ) {
        // Logcat keeps today's full fidelity; only diagnostics sinks are redacted.
        when {
            throwable != null && level == DiagnosticsLogLevel.E -> Log.e(tag, msg, throwable)
            throwable != null -> Log.w(tag, msg, throwable)
            else -> Log.println(priorityFor(level), tag, msg)
        }

        val safeMsg = buildString {
            append(msg)
            if (throwable != null) {
                append(": ")
                append(sanitizedThrowable(throwable))
            }
        }
        val line = RenderedLogLine.render(
            run = captureSessionId,
            lvl = level,
            cat = cat,
            tag = DiagRedactor.sanitize(tag, maxBytes = 128),
            msg = DiagRedactor.sanitize(safeMsg, maxBytes = 2048),
            attrs = DiagnosticsAttrRegistry.filter(cat, attrs, strict = strictAttrs),
        )
        ring.append(line.ringUtf8)
        fileLogger?.offer(line.canonicalUtf8)
        if (breadcrumb) breadcrumbJournal?.offer(line.canonicalUtf8)
    }

    fun ringSnapshot(): LogRing.Snapshot = ring.snapshot()

    /** Crash-path snapshot: bounded line count, lock-free, no allocation surprises. */
    fun ringSnapshotForCrash(maxLines: Int = 400): LogRing.Snapshot = ring.snapshotNewest(maxLines)

    /**
     * Clears the ring by instance swap — an in-place reset would race
     * lock-free concurrent appends; a reference swap can't. Used on binding
     * change and Never-mode purge.
     */
    fun resetRing() {
        ring = LogRing()
    }

    fun installFileLogger(logger: DiagnosticsFileLogger?) {
        fileLogger = logger
    }

    fun installBreadcrumbJournal(journal: BreadcrumbJournal?) {
        breadcrumbJournal = journal
    }

    private fun priorityFor(level: DiagnosticsLogLevel): Int = when (level) {
        DiagnosticsLogLevel.V -> Log.VERBOSE
        DiagnosticsLogLevel.D -> Log.DEBUG
        DiagnosticsLogLevel.I -> Log.INFO
        DiagnosticsLogLevel.W -> Log.WARN
        DiagnosticsLogLevel.E -> Log.ERROR
    }
}

/**
 * One-line, redacted rendering of a throwable for log `msg` text. The class
 * name is kept verbatim; the message passes through the redaction pipeline
 * (exception messages are a known leak path for URLs and tokens). Cause chain
 * is capped and cycle-guarded.
 */
internal fun sanitizedThrowable(t: Throwable, maxCauses: Int = 4): String {
    val sb = StringBuilder()
    var current: Throwable? = t
    val visited = HashSet<Throwable>()
    var depth = 0
    while (current != null && depth <= maxCauses && visited.add(current)) {
        if (depth > 0) sb.append(" ← ")
        sb.append(current.javaClass.name)
        val message = current.message?.takeIf { it.isNotBlank() }
        if (message != null) {
            sb.append(": ")
            sb.append(DiagRedactor.sanitize(message, maxBytes = 256))
        }
        current = current.cause
        depth++
    }
    return sb.toString()
}
