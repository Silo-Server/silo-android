package org.siloserver.silo.model.subtitles

import kotlin.test.Test
import kotlin.test.assertEquals
import org.siloserver.silo.network.ApiResult

class SubtitleProviderStatusTest {
    private val available = SubtitleProviderStatus(state = "available", allowed = true, enabled = true, providers = listOf("opensubtitles"))
    private val noProviders = SubtitleProviderStatus(state = "disabled", allowed = false, enabled = false)
    private val failed: ApiResult<SubtitleProviderStatus> = ApiResult.Error(code = 404, error = "not_found", message = "Not found")

    @Test
    fun serverAnswerDecides() {
        assertEquals(true, subtitleSearchAvailabilityAfter(ApiResult.Success(available), previous = false))
        assertEquals(false, subtitleSearchAvailabilityAfter(ApiResult.Success(noProviders), previous = true))
        assertEquals(false, subtitleSearchAvailabilityAfter(ApiResult.Success(available.copy(allowed = false)), previous = null))
    }

    @Test
    fun failedProbeKeepsKnownAnswerAndOtherwiseOffersSearch() {
        assertEquals(false, subtitleSearchAvailabilityAfter(failed, previous = false))
        assertEquals(true, subtitleSearchAvailabilityAfter(failed, previous = true))
        assertEquals(true, subtitleSearchAvailabilityAfter(failed, previous = null))
    }
}
