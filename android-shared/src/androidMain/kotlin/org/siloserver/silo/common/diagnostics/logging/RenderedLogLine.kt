package org.siloserver.silo.common.diagnostics.logging

import kotlinx.serialization.json.JsonObject
import org.siloserver.silo.model.diagnostics.DiagnosticsLogCategory
import org.siloserver.silo.model.diagnostics.DiagnosticsLogLevel
import org.siloserver.silo.model.diagnostics.DiagnosticsLogLine
import org.siloserver.silo.network.SiloJson
import java.time.Instant

/**
 * A log line rendered once, at write time, into UTF-8 JSON.
 *
 * [canonicalUtf8] is the full-fidelity line (segment files, breadcrumbs).
 * [ringUtf8] is the same line unless it exceeds the ring's per-entry byte
 * budget, in which case it is *re-rendered* with a shortened `msg` and no
 * `attrs` — never post-truncated, so ring entries are always valid JSON.
 */
internal class RenderedLogLine(
    val canonicalUtf8: ByteArray,
    val ringUtf8: ByteArray,
    val epochMs: Long,
    val category: DiagnosticsLogCategory,
) {
    companion object {
        /** Ring-local budget; the ring targets ~1.5 MB at 4000 entries. */
        const val RING_ENTRY_MAX_BYTES = 384
        private const val RING_MSG_MAX_BYTES = 224

        fun render(
            run: String,
            lvl: DiagnosticsLogLevel,
            cat: DiagnosticsLogCategory,
            tag: String,
            msg: String,
            attrs: JsonObject?,
            now: Instant = Instant.now(),
        ): RenderedLogLine {
            val line = DiagnosticsLogLine(
                ts = now.toString(),
                run = run,
                lvl = lvl,
                cat = cat,
                tag = tag,
                msg = msg,
                attrs = attrs,
            )
            val canonical = SiloJson.encodeToString(DiagnosticsLogLine.serializer(), line)
                .encodeToByteArray()
            val ring = if (canonical.size <= RING_ENTRY_MAX_BYTES) {
                canonical
            } else {
                SiloJson.encodeToString(
                    DiagnosticsLogLine.serializer(),
                    line.copy(
                        msg = DiagRedactor.truncateUtf8(msg, RING_MSG_MAX_BYTES),
                        attrs = null,
                    ),
                ).encodeToByteArray()
            }
            return RenderedLogLine(
                canonicalUtf8 = canonical,
                ringUtf8 = ring,
                epochMs = now.toEpochMilli(),
                category = cat,
            )
        }
    }
}
