package org.siloserver.silo.network.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.siloserver.silo.model.auth.PASSWORD_CHANGE_REQUIRED
import org.siloserver.silo.model.auth.PASSWORD_CHANGE_REQUIRED_MESSAGE
import org.siloserver.silo.model.auth.PasswordCapability
import org.siloserver.silo.model.auth.isPasswordChangeRequired
import org.siloserver.silo.model.auth.User
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeAttributeKey
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.ForceSiloRefreshAttributeKey
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.SingleAttemptAttributeKey
import org.siloserver.silo.network.apiv2.ApiV2Gate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The account password on the wire (silo-server `changePassword` and
 * `getAccountPasswordCapability`): account-scoped, never a declared profile,
 * and the change is sent once.
 */
class AccountPasswordApiTest {
    private val scope = AuthScopeSnapshot("server-a", "stale-profile", "https://silo.example.test", "proof", identityGeneration = 1)

    private fun api(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): Pair<HttpClient, AuthApi> {
        val client = HttpClient(MockEngine(handler)) { install(ContentNegotiation) { json(SiloJson) } }
        return client to AuthApi(client, ApiV2Gate.Unrestricted)
    }

    private fun MockRequestHandleScope.problem(code: String, status: Int, location: String? = null, detail: String = "x") = respond(
        buildString {
            append("""{"type":"https://siloserver.org/docs/api/v2/problems/$code","title":"t","status":$status,"detail":"d"""")
            if (location != null) append(""","errors":[{"location":"$location","code":"invalid","detail":"$detail"}]""")
            append("}")
        },
        HttpStatusCode.fromValue(status),
        headersOf(HttpHeaders.ContentType, "application/problem+json"),
    )

    private fun HttpRequestData.pinnedScope() = attributes.getOrNull(AuthScopeAttributeKey)

    @Test
    fun theChangeIsSentOnceWithNoProfileDeclared() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val (client, api) = api { request ->
            requests += request
            respond("", HttpStatusCode.NoContent)
        }
        try {
            assertIs<ApiResult.Success<Unit>>(api.changePassword(scope, "temporary", "a-new-password"))
            val request = requests.single()
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/api/v2/account/password", request.url.encodedPath)
            assertEquals(true, request.attributes.getOrNull(SingleAttemptAttributeKey))
            val pinned = request.pinnedScope()
            assertEquals("server-a", pinned?.serverId)
            assertNull(pinned?.profileId, "a declared profile would be refused")
            assertNull(pinned?.profileToken)
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals("temporary", body["current_password"]?.jsonPrimitive?.content)
            assertEquals("a-new-password", body["new_password"]?.jsonPrimitive?.content)
        } finally { client.close() }
    }

    @Test
    fun aWrongTemporaryPasswordIsToldApartFromARejectedNewOne() = runTest {
        val cases = listOf(
            "body.current_password" to WRONG_CURRENT_PASSWORD,
            "body.new_password" to "validation_failed",
        )
        for ((location, expected) in cases) {
            val (client, api) = api { problem("validation_failed", 422, location, detail = "Use at least 8 characters.") }
            try {
                val error = assertIs<ApiResult.Error>(api.changePassword(scope, "temporary", "short"))
                assertEquals(422, error.code)
                assertEquals(expected, error.error, location)
                assertEquals("Use at least 8 characters.", error.message)
            } finally { client.close() }
        }
    }

    @Test
    fun aSuccessOtherThanNoContentIsNotAChange() = runTest {
        val (client, api) = api { respond("{}", HttpStatusCode.OK) }
        try {
            assertIs<ApiResult.Error>(api.changePassword(scope, "temporary", "a-new-password"))
        } finally { client.close() }
    }

    @Test
    fun theCapabilityAndAccountAreReadWithNoProfileDeclared() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val (client, api) = api { request ->
            requests += request
            val body = when (request.url.encodedPath) {
                "/api/v2/account/password/capability" ->
                    """{"state":"available","allowed":true,"minimum_password_length":10,"maximum_password_bytes":64}"""
                else ->
                    """{"id":"1","username":"member","email":"m@example.test","role":"user","password_change_required":true}"""
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        try {
            val capability = assertIs<ApiResult.Success<PasswordCapability>>(api.passwordCapability(scope)).data
            assertEquals(10, capability.minimumPasswordLength)
            assertEquals(64, capability.maximumPasswordBytes)
            val account = assertIs<ApiResult.Success<User>>(api.getAccount(scope)).data
            assertEquals(true, account.passwordChangeRequired)
            assertEquals(listOf(null, null), requests.map { it.pinnedScope()?.profileId })
        } finally { client.close() }
    }

    @Test
    fun theSessionRefreshForcesARotation() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val (client, api) = api { request ->
            requests += request
            respond(
                """{"id":"1","username":"member","email":"m@example.test","role":"user","password_change_required":false}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        try {
            api.refreshAccountSession(scope)
            val request = requests.single()
            assertEquals(true, request.attributes.getOrNull(ForceSiloRefreshAttributeKey))
            assertNull(request.pinnedScope()?.profileId)
        } finally { client.close() }
    }

    /** Any other screen that hits the restriction says what to do, not "permission denied". */
    @Test
    fun theRestrictionReadsAsATemporaryPasswordAnywhere() = runTest {
        val (client, api) = api {
            respond(
                """{"type":"https://siloserver.org/docs/api/v2/problems/$PASSWORD_CHANGE_REQUIRED","title":"t","status":403,""" +
                    """"detail":"The account holds a temporary password; change it, then refresh the session."}""",
                HttpStatusCode.Forbidden,
                headersOf(HttpHeaders.ContentType, "application/problem+json"),
            )
        }
        try {
            val result = api.getMe()
            val error = assertIs<ApiResult.Error>(result)
            assertEquals(PASSWORD_CHANGE_REQUIRED_MESSAGE, error.message)
            assertTrue(result.isPasswordChangeRequired())
        } finally { client.close() }
    }

    @Test
    fun anAccountFromAnOlderServerLeavesTheFlagUnknown() = runTest {
        val (client, api) = api {
            respond(
                """{"id":"1","username":"member","email":"m@example.test","role":"user"}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        try {
            assertNull(assertIs<ApiResult.Success<User>>(api.getAccount(scope)).data.passwordChangeRequired)
        } finally { client.close() }
    }
}
