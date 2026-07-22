package org.siloserver.silo.common.diagnostics

import org.junit.Test
import org.siloserver.silo.model.diagnostics.DiagnosticsErrorCode
import kotlin.test.assertEquals

/**
 * Wire mapping for every error code the server's diagnostics handler emits.
 * A missing mapping would demote a structured, actionable failure to UNKNOWN.
 */
class DiagnosticsErrorCodeTest {

    @Test
    fun `every server wire code maps to its enum`() {
        val expected = mapOf(
            "disabled" to DiagnosticsErrorCode.DISABLED,
            "storage_unavailable" to DiagnosticsErrorCode.STORAGE_UNAVAILABLE,
            "quota_exceeded" to DiagnosticsErrorCode.QUOTA_EXCEEDED,
            "too_large" to DiagnosticsErrorCode.TOO_LARGE,
            "busy" to DiagnosticsErrorCode.BUSY,
            "unsupported_schema" to DiagnosticsErrorCode.UNSUPPORTED_SCHEMA,
            "destination_mismatch" to DiagnosticsErrorCode.DESTINATION_MISMATCH,
            "stale_consent" to DiagnosticsErrorCode.STALE_CONSENT,
            "archive_mismatch" to DiagnosticsErrorCode.ARCHIVE_MISMATCH,
            "invalid_bundle" to DiagnosticsErrorCode.INVALID_BUNDLE,
            "profile_mismatch" to DiagnosticsErrorCode.PROFILE_MISMATCH,
            "child_profile_forbidden" to DiagnosticsErrorCode.CHILD_PROFILE_FORBIDDEN,
            "unauthorized" to DiagnosticsErrorCode.UNAUTHORIZED,
            "internal_error" to DiagnosticsErrorCode.INTERNAL_ERROR,
        )
        for ((wire, code) in expected) {
            assertEquals(code, DiagnosticsErrorCode.fromWire(wire), "wire code '$wire'")
        }
    }

    @Test
    fun `unknown and absent codes map to UNKNOWN`() {
        assertEquals(DiagnosticsErrorCode.UNKNOWN, DiagnosticsErrorCode.fromWire("some_future_code"))
        assertEquals(DiagnosticsErrorCode.UNKNOWN, DiagnosticsErrorCode.fromWire(""))
        assertEquals(DiagnosticsErrorCode.UNKNOWN, DiagnosticsErrorCode.fromWire(null))
        // Case-sensitive by design: the server emits lowercase only.
        assertEquals(DiagnosticsErrorCode.UNKNOWN, DiagnosticsErrorCode.fromWire("DISABLED"))
    }
}
