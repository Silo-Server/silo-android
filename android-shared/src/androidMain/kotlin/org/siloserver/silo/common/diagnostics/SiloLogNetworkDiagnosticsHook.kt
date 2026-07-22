package org.siloserver.silo.common.diagnostics

import org.siloserver.silo.common.diagnostics.logging.SiloLog
import org.siloserver.silo.model.diagnostics.DiagnosticsAttrRegistry.Attr
import org.siloserver.silo.model.diagnostics.DiagnosticsLogCategory
import org.siloserver.silo.network.NetworkDiagnosticsHook

/**
 * Binds the Ktor client's network plugin to the diagnostics facade:
 * `method, templated path, status, duration_ms` only — the safe-logging
 * network contract. Non-2xx/0 statuses log at W so failures stand out in the
 * ring without debug logging on.
 */
class SiloLogNetworkDiagnosticsHook : NetworkDiagnosticsHook {

    override fun onRequestCompleted(method: String, templatedPath: String, statusCode: Int, durationMs: Long) {
        val attrs = mapOf(
            "method" to Attr.Str(method),
            "path" to Attr.Str(templatedPath),
            "status" to Attr.Int64(statusCode.toLong()),
            "duration_ms" to Attr.Int64(durationMs),
        )
        if (statusCode in 200..399) {
            SiloLog.v(DiagnosticsLogCategory.NETWORK, TAG, "request", attrs)
        } else {
            SiloLog.w(DiagnosticsLogCategory.NETWORK, TAG, "request failed", attrs = attrs)
        }
    }

    private companion object {
        const val TAG = "Http"
    }
}
