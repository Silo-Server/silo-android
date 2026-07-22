package org.siloserver.silo.model.diagnostics

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * One `logs.jsonl` / `breadcrumbs.jsonl` line, schema v1.
 *
 * `run` is the capture session id of the app run that produced the line, so
 * lines from prior runs (breadcrumbs, debug segment files) stay attributable.
 * `attrs` keys must come from [DiagnosticsAttrRegistry]; the server silently
 * drops unregistered keys but rejects registered keys carrying a wrong type.
 */
@Serializable
data class DiagnosticsLogLine(
    val ts: String,
    val run: String,
    val lvl: DiagnosticsLogLevel,
    val cat: DiagnosticsLogCategory,
    val tag: String,
    val msg: String,
    val attrs: JsonObject? = null,
)
