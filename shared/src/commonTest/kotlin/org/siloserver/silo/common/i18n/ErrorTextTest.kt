package org.siloserver.silo.common.i18n

import io.ktor.client.plugins.HttpRequestTimeoutException
import org.siloserver.silo.model.server.ServerContract
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.apiv2.ApiV2Gate
import kotlin.test.Test
import kotlin.test.assertEquals

class ErrorTextTest {
    private val fallback = UiText.Key(SharedString.HomeLoadFailed)

    @Test
    fun anErrorTheAppBuildsShowsItsOwnTextNotItsEnglishMessage() {
        val error = ApiResult.Error(0, ApiV2Gate.UPDATE_REQUIRED_ERROR, ServerContract.UPDATE_REQUIRED_MESSAGE, text = ServerContract.UPDATE_REQUIRED_TEXT)
        assertEquals(UiText.Key(SharedString.ServerUpdateRequired), error.errorText(fallback))
    }

    @Test
    fun theServersMessageShowsAsWrittenAndABlankOneShowsTheFallback() {
        assertEquals(UiText.Raw("Library not found."), ApiResult.Error(404, "not_found", "Library not found.").errorText(fallback))
        assertEquals(fallback, ApiResult.Error(500, "internal", "").errorText(fallback))
        assertEquals(fallback, ApiResult.Success(Unit).errorText(fallback))
    }

    @Test
    fun anUnansweredRequestShowsTheNetworkOrTimeoutText() {
        assertEquals(UiText.Key(SharedString.NetworkError), ApiResult.NetworkError(IllegalStateException("offline")).errorText(fallback))
        assertEquals(
            UiText.Key(SharedString.RequestTimeout),
            ApiResult.NetworkError(HttpRequestTimeoutException("https://silo.test", 1000)).errorText(fallback),
        )
    }
}
