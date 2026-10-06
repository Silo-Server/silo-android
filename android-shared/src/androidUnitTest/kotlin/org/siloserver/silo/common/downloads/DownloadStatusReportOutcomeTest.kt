package org.siloserver.silo.common.downloads

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import org.siloserver.silo.model.download.DownloadRecord
import org.siloserver.silo.network.ApiResult

class DownloadStatusReportOutcomeTest {
    private val row = DownloadRecord(id = "dl", contentId = "movie", mediaFileId = 42, kind = "queued",
        status = "completed", createdAt = "2026-09-29T00:00:00Z", revision = 1)

    private fun error(code: Int) = ApiResult.Error(code, "", "")

    @Test
    fun `an accepted event is settled`() {
        assertEquals(DownloadStatusReportOutcome.Settled, downloadStatusReportOutcome(ApiResult.Success(row)))
    }

    @Test
    fun `a moved revision reconciles instead of resending`() {
        assertEquals(DownloadStatusReportOutcome.Reconcile, downloadStatusReportOutcome(error(409)))
    }

    @Test
    fun `offline and transient answers keep the event for later`() {
        assertEquals(DownloadStatusReportOutcome.RetryLater, downloadStatusReportOutcome(ApiResult.NetworkError(IOException("offline"))))
        // A device clock ahead of the server's makes the event "future" until time catches up.
        for (code in listOf(0, 400, 401, 408, 422, 429, 500, 502, 503)) {
            assertEquals(DownloadStatusReportOutcome.RetryLater, downloadStatusReportOutcome(error(code)), "HTTP $code")
        }
    }

    @Test
    fun `answers a resend cannot change are settled`() {
        for (code in listOf(403, 404, 410)) {
            assertEquals(DownloadStatusReportOutcome.Settled, downloadStatusReportOutcome(error(code)), "HTTP $code")
        }
    }

    @Test
    fun `a locked profile keeps its report until PIN verification succeeds`() {
        val locked = ApiResult.Error(403, "profile_verification_required", "Verify the active profile.")
        assertEquals(DownloadStatusReportOutcome.RetryLater, downloadStatusReportOutcome(locked))
        assertEquals(DownloadStatusReportOutcome.Settled, downloadStatusReportOutcome(
            ApiResult.Error(403, "permission_denied", "Downloads are disabled."),
        ))
    }
}
