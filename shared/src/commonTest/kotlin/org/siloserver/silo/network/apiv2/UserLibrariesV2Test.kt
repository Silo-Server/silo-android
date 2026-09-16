package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.personal.UserLibrary
import org.siloserver.silo.network.*
import org.siloserver.silo.network.api.PersonalDataApi
import kotlin.test.*

class UserLibrariesV2Test {
    private var scope = AuthScopeSnapshot("server", null, "https://example.invalid", null, identityGeneration = 1)
    private val tokens = object : TokenManager by TokenManagerImpl() { override suspend fun snapshotCurrentScope() = scope }
    private fun client(handler: suspend MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData) =
        HttpClient(MockEngine(handler)) { install(ContentNegotiation) { json(SiloJson) } }
    private fun MockRequestHandleScope.reply(body: String) = respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    private val row = """{"id":"12","name":"Movies","type":"movies","sort_order":2,"poster_url":"https://images.example/poster"}"""

    @Test fun optionalProfileAndCompleteOrderedCollection() = runTest {
        val captured = mutableListOf<AuthScopeSnapshot>()
        val c = client {
            assertEquals("/api/v2/user/libraries", it.url.encodedPath)
            assertNull(it.url.parameters["cursor"])
            captured += it.attributes[AuthScopeAttributeKey]
            reply("""{"items":[$row,{"id":"7","name":"Reading","type":"ebooks","sort_order":3}]}""")
        }
        try {
            val api = PersonalDataApi(c, tokenManager = tokens)
            val rows = assertIs<ApiResult.Success<List<UserLibrary>>>(api.listUserLibraries()).data
            assertEquals(listOf(12, 7), rows.map { it.id }); assertEquals(2, rows[0].sortOrder)
            assertEquals("https://images.example/poster", rows[0].posterUrl); assertNull(rows[1].posterUrl)
            scope = scope.copy(profileId = "profile", identityGeneration = 2)
            assertIs<ApiResult.Success<List<UserLibrary>>>(api.listUserLibraries())
            assertEquals(listOf(null, "profile"), captured.map { it.profileId })
        } finally { c.close() }
    }

    @Test fun rejectsPartialDuplicateNumericAndUnsupportedIDs() = runTest {
        var body = ""
        val c = client { reply(body) }
        try {
            val api = PersonalDataApi(c, tokenManager = tokens)
            for (wire in listOf("""{"items":[$row],"page":{"has_more":true,"next_cursor":"more"}}""",
                """{"items":[$row,$row]}""", """{"items":[${row.replace("\"12\"", "12")}]}""",
                """{"items":[${row.replace("\"12\"", "\"2147483648\"")}]}""")) {
                body = wire; assertFalse(api.listUserLibraries() is ApiResult.Success)
            }
            body = """{"items":[]}"""
            assertTrue(assertIs<ApiResult.Success<List<UserLibrary>>>(api.listUserLibraries()).data.isEmpty())
        } finally { c.close() }
    }

    @Test fun staleViewerResponseIsDiscarded() = runTest {
        val c = client {
            scope = scope.copy(profileId = "other", identityGeneration = 2)
            reply("""{"items":[$row]}""")
        }
        try { assertIs<ApiResult.Error>(PersonalDataApi(c, tokenManager = tokens).listUserLibraries()) }
        finally { c.close() }
    }
}
