package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.request.RequestsFeatureStatus
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.api.DefaultRequestsApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** `DELETE /api/v2/requests/{id}` (`removeFailedRequest`). */
class RequestRemoveFailedV2Test {

    @Test fun removeIsOneDeleteAnsweredWithNoContent() = runTest {
        var calls = 0
        val client = HttpClient(MockEngine { request ->
            calls++
            assertEquals(HttpMethod.Delete, request.method)
            assertEquals("/api/v2/requests/r-1", request.url.encodedPath)
            respond("", HttpStatusCode.NoContent)
        })
        assertIs<ApiResult.Success<Unit>>(DefaultRequestsApi(client, ApiV2Gate.Unrestricted).removeFailed("r-1"))
        assertEquals(1, calls)
        client.close()
    }

    @Test fun aRequestThatIsNoLongerFailedAnswersConflict() = runTest {
        val client = HttpClient(MockEngine {
            respond(
                """{"type":"https://siloserver.org/docs/api/v2/problems/conflict","title":"Conflict","status":409,"detail":"The request is not in a state that allows this action."}""",
                HttpStatusCode.Conflict,
                headersOf(HttpHeaders.ContentType, "application/problem+json"),
            )
        })
        val error = assertIs<ApiResult.Error>(DefaultRequestsApi(client, ApiV2Gate.Unrestricted).removeFailed("r-1"))
        assertEquals(409, error.code)
        assertEquals("conflict", error.error)
        client.close()
    }

    @Test fun statusDecodesRemoveFailedSupportedAndDefaultsItOffOnOlderServers() {
        val current = SiloJson.decodeFromString<RequestsFeatureStatus>(
            """{"requests_enabled":true,"allowed":true,"state":"available","remove_failed_supported":true}""",
        )
        assertTrue(current.removeFailedSupported)
        val older = SiloJson.decodeFromString<RequestsFeatureStatus>("""{"requests_enabled":true,"allowed":true,"state":"available"}""")
        assertFalse(older.removeFailedSupported)
    }
}
