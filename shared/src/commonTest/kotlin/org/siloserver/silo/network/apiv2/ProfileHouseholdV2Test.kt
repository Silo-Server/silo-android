package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.siloserver.silo.model.profile.*
import org.siloserver.silo.network.*
import org.siloserver.silo.network.api.HouseholdManager
import org.siloserver.silo.network.api.ProfileApi
import kotlin.test.*

class ProfileHouseholdV2Test {
    private var scope = AuthScopeSnapshot("server", null, "https://example.invalid", null, identityGeneration = 1)
    private val tokens = object : TokenManager by TokenManagerImpl() {
        override suspend fun snapshotCurrentScope() = scope
    }
    private val profile = """{"id":"42","name":"Reader","created_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-01T00:00:00Z"}"""
    private fun client(handler: suspend MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData) =
        HttpClient(MockEngine(handler)) { install(ContentNegotiation) { json(SiloJson) } }

    @Test fun listBeforeSelectionAndStalePinProof() = runTest {
        val c = client {
            assertEquals(scope, it.attributes[AuthScopeAttributeKey])
            assertTrue(it.attributes[RequireSiloAuthAttributeKey])
            assertFalse(it.attributes.contains(SingleAttemptAttributeKey))
            if (it.method == HttpMethod.Get) {
                assertEquals("/api/v2/profiles", it.url.encodedPath)
                respond("""{"items":[$profile],"avatar_upload_enabled":true}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType,"application/json"))
            } else {
                assertEquals("/api/v2/profiles/42/verify-pin", it.url.encodedPath)
                scope = scope.copy(identityGeneration = 2)
                respond("""{"valid":true,"profile_token":"proof","expires_at":null}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType,"application/json"))
            }
        }
        try {
            val api = ProfileApi(c, ApiV2Gate.Unrestricted, tokens)
            assertEquals("42", assertIs<ApiResult.Success<ProfilesResponse>>(api.listProfiles()).data.profiles.single().id)
            assertEquals("identity_changed", assertIs<ApiResult.Error>(api.verifyPin("42", "1234")).error)
            assertNull(tokens.getProfileToken())
        } finally { c.close() }
    }

    @Test fun createOmitsNullsAndDeleteIsSingleAttempt() = runTest {
        scope = scope.copy(profileId = "1", profileToken = "manager-proof")
        var sends = 0
        val c = client {
            sends++
            assertEquals(scope, it.attributes[AuthScopeAttributeKey])
            assertTrue(it.attributes[SingleAttemptAttributeKey])
            if (it.method == HttpMethod.Post) {
                val body = SiloJson.parseToJsonElement(it.body.toByteArray().decodeToString()).jsonObject
                assertFalse(body.containsKey("pin"))
                assertEquals(JsonArray(listOf(JsonPrimitive("7"))), body["allowed_library_ids"])
                respond(profile, HttpStatusCode.Created, headersOf(HttpHeaders.ContentType,"application/json"))
            } else {
                assertEquals("/api/v2/profiles/42", it.url.encodedPath)
                respond("", HttpStatusCode.NoContent)
            }
        }
        try {
            val api = ProfileApi(c, ApiV2Gate.Unrestricted, tokens)
            assertIs<ApiResult.Success<Profile>>(api.createProfile(CreateProfileRequest("Reader", allowedLibraryIds = listOf(7))))
            assertIs<ApiResult.Success<Unit>>(api.deleteProfile("42"))
            assertEquals(2, sends)
        } finally { c.close() }
    }

    @Test fun wrongPinRemainsSuccessfulDataAndDeleteRequires204() = runTest {
        val c = client {
            respond(if (it.method == HttpMethod.Post) """{"valid":false,"expires_at":null}""" else "{}",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType,"application/json"))
        }
        try {
            val api = ProfileApi(c, ApiV2Gate.Unrestricted, tokens)
            assertFalse(assertIs<ApiResult.Success<VerifyPinResponse>>(api.verifyPin("42", "wrong")).data.valid)
            assertFalse(api.deleteProfile("42") is ApiResult.Success)
        } finally { c.close() }
    }

    @Test fun managementActsAsPrimaryWithoutChangingSelection() = runTest {
        scope = scope.copy(profileId = "kid", profileToken = "kid-proof")
        val manager = HouseholdManager("primary", "primary-proof", scope)
        var sends = 0
        val c = client {
            sends++
            val pinned = it.attributes[AuthScopeAttributeKey]
            assertEquals("primary", pinned.profileId)
            assertEquals("primary-proof", pinned.profileToken)
            if (it.method == HttpMethod.Delete) respond("", HttpStatusCode.NoContent)
            else respond(profile, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        try {
            val api = ProfileApi(c, ApiV2Gate.Unrestricted, tokens)
            assertIs<ApiResult.Success<Profile>>(api.updateProfile("42", UpdateProfileRequest(pin = "1234"), manager))
            assertIs<ApiResult.Success<Unit>>(api.deleteProfile("42", manager))
            assertEquals("kid", tokens.snapshotCurrentScope()?.profileId)
            // A proof verified under another identity is never sent.
            scope = scope.copy(identityGeneration = 2)
            assertEquals("identity_changed", assertIs<ApiResult.Error>(api.deleteProfile("42", manager)).error)
            assertEquals(2, sends)
        } finally { c.close() }
    }

    @Test fun managementWithoutScopeSetsExplicitHeaders() = runTest {
        val c = client {
            assertFalse(it.attributes.contains(AuthScopeAttributeKey))
            assertEquals("primary", it.headers["X-Profile-Id"])
            assertNull(it.headers["X-Profile-Token"])
            respond(profile, HttpStatusCode.Created, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        try {
            val api = ProfileApi(c, ApiV2Gate.Unrestricted)
            assertIs<ApiResult.Success<Profile>>(api.createProfile(CreateProfileRequest("Reader"), HouseholdManager("primary", null, null)))
        } finally { c.close() }
    }

    @Test fun managerProofSurvivesTheDevicesProfileSwitchButNotASignIn() = runTest {
        scope = scope.copy(profileId = "kid", profileToken = "kid-proof", credentialEpoch = 3)
        val manager = HouseholdManager("primary", "primary-proof", scope)
        var sends = 0
        val c = client { sends++; respond("", HttpStatusCode.NoContent) }
        try {
            val api = ProfileApi(c, ApiV2Gate.Unrestricted, tokens)
            // The picker cleared the deleted, selected profile: same account.
            scope = scope.copy(profileId = null, profileToken = null, identityGeneration = scope.identityGeneration + 1)
            assertIs<ApiResult.Success<Unit>>(api.deleteProfile("42", manager))
            scope = scope.copy(credentialEpoch = 4)
            assertEquals("identity_changed", assertIs<ApiResult.Error>(api.deleteProfile("42", manager)).error)
            scope = scope.copy(credentialEpoch = 3, serverId = "other")
            assertEquals("identity_changed", assertIs<ApiResult.Error>(api.deleteProfile("42", manager)).error)
            assertEquals(1, sends)
        } finally { c.close() }
    }

    @Test fun verifyPinNeverDeclaresTheSelectedProfile() = runTest {
        scope = scope.copy(profileId = "1", profileToken = "stale-proof")
        val c = client {
            val pinned = it.attributes[AuthScopeAttributeKey]
            assertNull(pinned.profileId)
            assertNull(pinned.profileToken)
            assertEquals(scope.serverId, pinned.serverId)
            assertEquals(scope.identityGeneration, pinned.identityGeneration)
            respond("""{"valid":true,"profile_token":"fresh","expires_at":null}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        try {
            val api = ProfileApi(c, ApiV2Gate.Unrestricted, tokens)
            assertEquals("fresh", assertIs<ApiResult.Success<VerifyPinResponse>>(api.verifyPin("1", "1234")).data.profileToken)
            assertEquals("1", tokens.snapshotCurrentScope()?.profileId)
        } finally { c.close() }
    }
}
