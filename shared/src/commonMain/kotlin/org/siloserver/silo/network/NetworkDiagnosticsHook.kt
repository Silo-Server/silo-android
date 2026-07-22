package org.siloserver.silo.network

/**
 * Optional sink for `cat=network` diagnostics log lines.
 *
 * The safe-logging contract for the network category is strict: method,
 * templated path, status, and duration only — never headers, bodies, query
 * strings, or full URLs. Callers receive an already-templated path (see
 * [templateApiPath]); the host is never included.
 *
 * Wired the same way [DeviceMetadataProvider] is: an optional collaborator on
 * [createSiloClient], resolved via Koin `getOrNull()`, so platforms without a
 * diagnostics sink pay nothing.
 */
fun interface NetworkDiagnosticsHook {
    /** [statusCode] is 0 when the request failed before any response arrived. */
    fun onRequestCompleted(method: String, templatedPath: String, statusCode: Int, durationMs: Long)
}

private val UUID_SEGMENT = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
private val NUMERIC_SEGMENT = Regex("^\\d+$")
private val OPAQUE_ID_SEGMENT = Regex("^[A-Za-z0-9_-]{16,}$")

/**
 * Replaces identifier-shaped path segments with placeholders so log lines
 * aggregate by endpoint and never carry content identifiers:
 * `/api/v1/items/42` → `/api/v1/items/{id}`.
 */
fun templateApiPath(path: String): String {
    if (path.isEmpty()) return path
    return path.split('/').joinToString("/") { segment ->
        when {
            segment.isEmpty() -> segment
            NUMERIC_SEGMENT.matches(segment) -> "{id}"
            UUID_SEGMENT.matches(segment) -> "{uuid}"
            OPAQUE_ID_SEGMENT.matches(segment) -> "{id}"
            else -> segment
        }
    }
}
