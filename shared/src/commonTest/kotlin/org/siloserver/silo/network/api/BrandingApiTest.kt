package org.siloserver.silo.network.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.SiloJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BrandingApiTest {
    @Test
    fun `getBranding decodes native server name`() = runTest {
        val api = BrandingApi(
            HttpClient(
                MockEngine {
                    respond(
                        content = """{"server_name":"Home Silo","login_subtitle":"Welcome"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                },
            ) {
                install(ContentNegotiation) { json(SiloJson) }
            },
        )

        val result = assertIs<ApiResult.Success<BrandingStatus>>(api.getBranding())

        assertEquals("Home Silo", result.data.serverName)
    }
}
