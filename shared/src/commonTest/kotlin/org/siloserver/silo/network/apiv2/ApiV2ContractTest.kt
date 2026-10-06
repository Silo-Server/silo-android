package org.siloserver.silo.network.apiv2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.siloserver.silo.network.apiv2.ApiV2Fixtures.plusUnknown
import org.siloserver.silo.network.apiv2.ApiV2Fixtures.with
import org.siloserver.silo.model.auth.DeviceLoginCancelResponse
import org.siloserver.silo.model.auth.DeviceLoginLookupResponse
import org.siloserver.silo.model.auth.DeviceLoginStartResponse
import org.siloserver.silo.network.api.DeviceCapabilityV2
import org.siloserver.silo.network.api.DevicePollV2
import org.siloserver.silo.network.api.ServerConnectionsV2
import org.siloserver.silo.network.api.ServerIdentityV2
import org.siloserver.silo.network.api.AccountIdentityCollectionV2
import org.siloserver.silo.network.api.AccountIdentityV2
import org.siloserver.silo.network.api.AuthProviderCollectionV2
import org.siloserver.silo.network.api.DefaultExternalSignInApi
import org.siloserver.silo.network.api.ExternalSignInCapabilitiesV2
import org.siloserver.silo.network.api.LinkTicketV2
import org.siloserver.silo.network.api.OAuthHandshakeCapabilitiesV2
import org.siloserver.silo.network.api.TokenPairV2
import org.siloserver.silo.model.auth.OAuthHandshakeCapabilities
import org.siloserver.silo.model.auth.SignInOptions
import org.siloserver.silo.model.auth.SignInProvider
import org.siloserver.silo.model.auth.SignInProviders
import org.siloserver.silo.pairing.PairingEndpoint

/**
 * Decodes every vendored API v2 fixture with the production `SiloJson` and
 * pins the null/absence semantics each UI flow relies on. Fixture bodies are
 * byte-identical copies of the server's generated contract fixtures (see
 * `shared/src/commonTest/resources/api/v2/fixtures/SOURCE`).
 */
class ApiV2ContractTest {

    @Test
    fun sourceRecordsTheServerCommit() {
        val commit = ApiV2Fixtures.source["commit"]
        assertNotNull(commit)
        assertTrue(Regex("^[0-9a-f]{40}$").matches(commit), "commit=$commit is not a full SHA")
        assertEquals("contracts/api/v2/fixtures", ApiV2Fixtures.source["path"])
    }

    @Test
    fun everyIndexedFixtureDecodesToItsModel() {
        val index = ApiV2Fixtures.index.fixtures
        assertTrue(index.size >= 14, "expected the vendored subset, got ${index.size}")
        index.forEach { entry ->
            val body = ApiV2Fixtures.bodyObject(entry.name)
            if (entry.responseMediaType == "application/problem+json") {
                val problem = ApiV2Fixtures.decode<Problem>(body)
                assertEquals(entry.expectedStatus, problem.status, entry.name)
                assertTrue(problem.code.isNotBlank(), entry.name)
            } else {
                assertTrue(entry.expectedStatus in 200..299, entry.name)
                when (entry.operationId) {
                    "getSetupStatus" -> ApiV2Fixtures.decode<SetupStatus>(body)
                    "getCurrentUser" -> ApiV2Fixtures.decode<Account>(body)
                    "listProgress" -> Unit // Android does not read /api/v2/progress.
                    "updateProfile" -> ApiV2Fixtures.decode<ProfileV2>(body)
                    "getSystemInfo" -> ApiV2Fixtures.decode<SystemInfo>(body)
                    "startDeviceLogin" -> ApiV2Fixtures.decode<DeviceLoginStartResponse>(body)
                    "pollDeviceLogin" -> ApiV2Fixtures.decode<DevicePollV2>(body)
                    "cancelDeviceLogin" -> ApiV2Fixtures.decode<DeviceLoginCancelResponse>(body)
                    "getDeviceLogin" -> ApiV2Fixtures.decode<DeviceLoginLookupResponse>(body)
                    "getDeviceLoginCapability" -> ApiV2Fixtures.decode<DeviceCapabilityV2>(body)
                    "getServerIdentity" -> ApiV2Fixtures.decode<ServerIdentityV2>(body)
                    "getServerConnections" -> ApiV2Fixtures.decode<ServerConnectionsV2>(body)
                    "listAuthProviders" -> ApiV2Fixtures.decode<AuthProviderCollectionV2>(body)
                    "getOAuthHandshakeCapabilities" -> ApiV2Fixtures.decode<OAuthHandshakeCapabilitiesV2>(body)
                    "getExternalSignInCapabilities" -> ApiV2Fixtures.decode<ExternalSignInCapabilitiesV2>(body)
                    "completeOAuthLogin" -> ApiV2Fixtures.decode<TokenPairV2>(body)
                    "listAccountIdentities" -> ApiV2Fixtures.decode<AccountIdentityCollectionV2>(body)
                    "createAccountIdentityLinkTicket" -> ApiV2Fixtures.decode<LinkTicketV2>(body)
                    "signInWithNetworkIdentity" -> ApiV2Fixtures.decode<TokenPairV2>(body)
                    "linkAccountIdentityWithNetwork" -> ApiV2Fixtures.decode<AccountIdentityV2>(body)
                    else -> error("unhandled success fixture ${entry.name} (${entry.operationId})")
                }
            }
        }
    }

    // --- getSetupStatus ---

    @Test
    fun setupStatusFixture() {
        val status = ApiV2Fixtures.decode<SetupStatus>(ApiV2Fixtures.bodyObject("get_setup_status_ok").plusUnknown())
        assertFalse(status.needsSetup)
    }

    // --- getCurrentUser ---

    @Test
    fun accountFixtureConsumedFields() {
        val account = ApiV2Fixtures.decode<Account>(ApiV2Fixtures.bodyObject("get_current_user_ok"))
        assertEquals("1", account.id)
        assertEquals("laura", account.username)
        assertEquals("laura@example.test", account.email)
        assertEquals("user", account.role.wire)
        assertTrue(account.downloadAllowed)
        assertNull(account.impersonation, "impersonation is absent outside an impersonation session")
    }

    @Test
    fun accountImpersonationExplicitNullAndPresent() {
        val base = ApiV2Fixtures.bodyObject("get_current_user_ok")
        assertNull(ApiV2Fixtures.decode<Account>(with(base, "impersonation" to JsonNull)).impersonation)
        val active = ApiV2Fixtures.json.parseToJsonElement(
            """{"active":true,"impersonator_user_id":"42","impersonator_username":"root"}""",
        )
        val account = ApiV2Fixtures.decode<Account>(with(base, "impersonation" to active))
        assertEquals("42", account.impersonation?.impersonatorUserId)
        assertEquals("root", account.impersonation?.impersonatorUsername)
        assertTrue(account.impersonation?.active == true)
    }

    @Test
    fun accountUnknownFieldAndUnknownRoleAreObservable() {
        val body = with(ApiV2Fixtures.bodyObject("get_current_user_ok"), "role" to JsonPrimitive("auditor")).plusUnknown()
        val account = ApiV2Fixtures.decode<Account>(body)
        assertEquals("auditor", account.role.wire, "an unknown role must not collapse to a known default")
    }

    @Test
    fun accountDefaults() {
        val account = ApiV2Fixtures.json.decodeFromString(
            Account.serializer(),
            """{"id":"7","username":"u","email":"e","role":"admin"}""",
        )
        assertFalse(account.downloadAllowed)
        assertNull(account.impersonation)
        assertEquals("admin", account.role.wire)
    }

    // --- updateProfile ---

    @Test
    fun profileFixtureConsumedFields() {
        val profile = ApiV2Fixtures.decode<ProfileV2>(ApiV2Fixtures.bodyObject("update_profile_ok"))
        assertEquals("p-owner", profile.id)
        assertEquals("Laura", profile.name)
        assertEquals("preset:fox", profile.avatar)
        assertEquals("/avatars/presets/fox.png", profile.avatarUrl)
        assertEquals("preset", profile.avatarSource.wire)
        assertFalse(profile.hasPin)
        assertTrue(profile.isPrimary)
        assertEquals("", profile.maxContentRating, "cleared string members are emitted as empty, never absent")
        assertEquals("auto", profile.qualityPreference.wire)
        assertEquals("en", profile.language)
        assertEquals("auto", profile.subtitleMode.wire)
        assertTrue(profile.autoSkipIntro)
        assertEquals(listOf("3"), profile.allowedLibraryIds)
        assertEquals("1080p", profile.maxPlaybackQuality.wire)
        assertEquals("2026-01-02T03:04:05.000Z", profile.createdAt)
    }

    @Test
    fun profileUnknownEnumsAreObservable() {
        val body = with(
            ApiV2Fixtures.bodyObject("update_profile_ok"),
            "avatar_source" to JsonPrimitive("hologram"),
            "quality_preference" to JsonPrimitive("balanced"),
            "subtitle_mode" to JsonPrimitive("forced_only"),
            "max_playback_quality" to JsonPrimitive("4320p"),
        ).plusUnknown()
        val profile = ApiV2Fixtures.decode<ProfileV2>(body)
        assertEquals("hologram", profile.avatarSource.wire)
        assertEquals("balanced", profile.qualityPreference.wire)
        assertEquals("forced_only", profile.subtitleMode.wire)
        assertEquals("4320p", profile.maxPlaybackQuality.wire)
    }

    @Test
    fun profileDefaultsAndExplicitNulls() {
        val minimal = ApiV2Fixtures.json.decodeFromString(
            ProfileV2.serializer(),
            """{"id":"p","name":"n","created_at":"2026-01-02T03:04:05Z","updated_at":"2026-01-02T03:04:05Z"}""",
        )
        assertEquals("", minimal.avatar)
        assertNull(minimal.avatarUrl)
        assertEquals("none", minimal.avatarSource.wire)
        assertFalse(minimal.hasPin)
        assertFalse(minimal.isChild)
        assertFalse(minimal.isPrimary)
        assertEquals("", minimal.maxContentRating)
        assertEquals("auto", minimal.qualityPreference.wire)
        assertEquals("", minimal.language)
        assertEquals("", minimal.preferredMetadataLanguage)
        assertEquals("", minimal.subtitleLanguage)
        assertEquals("auto", minimal.subtitleMode.wire)
        assertFalse(minimal.autoSkipIntro)
        assertFalse(minimal.autoSkipCredits)
        assertFalse(minimal.autoSkipRecap)
        assertFalse(minimal.autoPlayNextPreview)
        assertFalse(minimal.showForcedSubtitles)
        assertFalse(minimal.libraryRestrictionsEnabled)
        assertEquals(emptyList(), minimal.allowedLibraryIds)
        assertEquals("1080p", minimal.maxPlaybackQuality.wire)

        // Explicit null: nullable member → null; non-nullable member with a
        // default → coerced to the default (coerceInputValues), documented here
        // so a contract change to nullable strings is caught.
        val nulled = ApiV2Fixtures.decode<ProfileV2>(
            with(ApiV2Fixtures.bodyObject("update_profile_ok"), "avatar_url" to JsonNull, "language" to JsonNull),
        )
        assertNull(nulled.avatarUrl)
        assertEquals("", nulled.language)
    }

    @Test
    fun profileUpdateEncodesOmittedAbsentAndClearedAsNull() {
        val update = ProfileUpdate(
            name = Patch.Set("Laura"),
            subtitleMode = Patch.Set(SubtitleMode("always")),
            maxContentRating = Patch.Clear,
            allowedLibraryIds = Patch.Set(listOf("3")),
        )
        val encoded = ApiV2Fixtures.json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), update.toJsonObject())
        // Matches the vendored update_profile_ok request body member for member.
        val expected = ApiV2Fixtures.index.fixtures.single { it.name == "update_profile_ok" }.request.body
        assertEquals(ApiV2Fixtures.json.parseToJsonElement(checkNotNull(expected)), ApiV2Fixtures.json.parseToJsonElement(encoded))
        assertTrue("\"max_content_rating\":null" in encoded, encoded)
        assertFalse("\"avatar\"" in encoded, "omitted member must be absent: $encoded")
        assertFalse("\"pin\"" in encoded, encoded)
        assertEquals("{}", ApiV2Fixtures.json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), ProfileUpdate().toJsonObject()))
    }

    @Test
    fun profileUpdateNullNotClearableProblem() {
        val problem = ApiV2Fixtures.decode<Problem>(ApiV2Fixtures.bodyObject("update_profile_null_not_clearable"))
        assertEquals(422, problem.status)
        assertEquals("validation_failed", problem.code)
        val error = problem.errors.single()
        assertEquals("body.is_child", error.location)
        assertEquals("invalid_type", error.code)
    }

    // --- getSystemInfo ---

    @Test
    fun systemInfoFixture() {
        val info = ApiV2Fixtures.decode<SystemInfo>(ApiV2Fixtures.bodyObject("get_system_info_ok").plusUnknown())
        assertEquals(2, info.apiMajor)
        assertTrue(info.contractDigest.isNotBlank())
        assertEquals("/api/v2/openapi.json", info.links.openapi)
        assertEquals("/api/v2/capabilities", info.links.capabilities)
    }

    // --- Problems ---

    @Test
    fun problemFixturesDecodeToTheirType() {
        val expected = mapOf(
            "authentication_required" to (401 to "authentication_required"),
            "validation_failed_body" to (422 to "validation_failed"),
            "not_found" to (404 to "not_found"),
            "rate_limited" to (429 to "rate_limited"),
            "profile_verification_required" to (403 to "profile_verification_required"),
            "not_acceptable" to (406 to "not_acceptable"),
            "list_progress_profile_header_required" to (422 to "validation_failed"),
            "list_progress_offset_rejected" to (422 to "validation_failed"),
        )
        expected.forEach { (name, statusAndCode) ->
            val problem = ApiV2Fixtures.decode<Problem>(ApiV2Fixtures.bodyObject(name).plusUnknown())
            assertEquals(statusAndCode.first, problem.status, name)
            assertEquals(statusAndCode.second, problem.code, name)
            assertTrue(problem.title.isNotBlank(), name)
            assertTrue(ApiV2Fixtures.bodyObject(name)["type"]!!.jsonPrimitive.content.startsWith("https://"), name)
        }
        assertEquals(2, ApiV2Fixtures.decode<Problem>(ApiV2Fixtures.bodyObject("validation_failed_body")).errors.size)
    }

    @Test
    fun problemDefaults() {
        val problem = ApiV2Fixtures.json.decodeFromString(
            Problem.serializer(),
            """{"type":"https://siloserver.org/docs/api/v2/problems/x","title":"X","status":500}""",
        )
        assertEquals("", problem.detail)
        assertNull(problem.instance)
        assertEquals(emptyList(), problem.errors)
        assertEquals("x", problem.code)
    }

    // --- Device sign-in (TV) ---

    @Test
    fun deviceLoginStartFixtureCarriesTheDigitCodeAndActivateLink() {
        val start = ApiV2Fixtures.decode<DeviceLoginStartResponse>(ApiV2Fixtures.bodyObject("start_device_login_ok").plusUnknown())
        assertEquals("4821-7730", start.userCode)
        assertEquals("https://silo.example.test/activate", start.verificationUri)
        assertEquals("https://silo.example.test/activate?code=48217730", start.verificationUriComplete)
        assertEquals(900, start.expiresIn)
    }

    @Test
    fun deviceLoginPollFixturesCarryOpenedAndTokens() {
        val opened = ApiV2Fixtures.decode<DevicePollV2>(ApiV2Fixtures.bodyObject("poll_device_login_opened")).domain()
        assertEquals("pending", opened.status)
        assertTrue(opened.opened)
        assertNull(opened.accessToken)
        assertEquals("2026-01-02T03:14:05.678Z", opened.expiresAt, "a pending poll carries the current expiry")

        val approved = ApiV2Fixtures.decode<DevicePollV2>(ApiV2Fixtures.bodyObject("poll_device_login_ok")).domain()
        assertEquals("approved", approved.status)
        assertFalse(approved.opened)
        assertEquals("acc", approved.accessToken)
        assertEquals("ref", approved.refreshToken)

        // Servers that predate the opened signal omit it.
        val legacy = ApiV2Fixtures.decode<DevicePollV2>(
            ApiV2Fixtures.bodyObject("poll_device_login_opened").let { body ->
                kotlinx.serialization.json.JsonObject(body.filterKeys { it != "opened" })
            },
        ).domain()
        assertFalse(legacy.opened)
    }

    @Test
    fun deviceLoginCancelLookupAndCapabilityFixtures() {
        assertEquals("canceled", ApiV2Fixtures.decode<DeviceLoginCancelResponse>(ApiV2Fixtures.bodyObject("cancel_device_login_ok")).status)

        val lookup = ApiV2Fixtures.decode<DeviceLoginLookupResponse>(ApiV2Fixtures.bodyObject("get_device_login_ok"))
        assertEquals("3f2a9d5e-6b1c-4c7e-9a0d-2f4b8c1e7a35", lookup.serverId)
        assertEquals("2026-01-02T03:04:05.678Z", lookup.requestedAt)
        assertEquals("Silo", lookup.serverName)
        assertEquals("4821-7730", lookup.userCode)

        val capability = ApiV2Fixtures.decode<DeviceCapabilityV2>(ApiV2Fixtures.bodyObject("get_device_login_capability_ok")).domain()
        assertTrue(capability.deviceLoginAvailable)
        assertTrue(capability.cancel)
        assertTrue(capability.openedSignal)

        val unconfigured = ApiV2Fixtures.decode<DeviceCapabilityV2>(
            with(ApiV2Fixtures.bodyObject("get_device_login_capability_ok"), "state" to JsonPrimitive("not_configured")),
        ).domain()
        assertFalse(unconfigured.deviceLoginAvailable)
        assertFalse(unconfigured.cancel)
    }

    @Test
    fun serverIdentityAndConnectionsFixtures() {
        assertEquals(
            "3f2a9d5e-6b1c-4c7e-9a0d-2f4b8c1e7a35",
            ApiV2Fixtures.decode<ServerIdentityV2>(ApiV2Fixtures.bodyObject("server_identity_ok")).serverId,
        )
        val connections = ApiV2Fixtures.decode<ServerConnectionsV2>(ApiV2Fixtures.bodyObject("server_connections_ok"))
        assertTrue(connections.isAvailable)
        // The provider without a URL (plugin not running) is not offered.
        assertEquals(
            listOf(
                PairingEndpoint.of("https://silo.example.test", PairingEndpoint.Kind.Public),
                PairingEndpoint.of(
                    "https://silo.overlay.example.test",
                    PairingEndpoint.Kind.Provider,
                    provider = "stub",
                    displayName = "Stub Overlay",
                ),
            ),
            connections.domain().endpoints,
        )
    }

    // --- External sign-in (listAuthProviders, OAuth handshake, identities) ---

    @Test
    fun authProvidersFixtureResolvesTheNativeStartAndIcon() {
        val providers = ApiV2Fixtures.decode<AuthProviderCollectionV2>(ApiV2Fixtures.bodyObject("list_auth_providers_ok").plusUnknown())
            .domain("https://silo.lan:8080")
        assertTrue(providers.passwordLogin)
        val (local, sso) = providers.providers
        assertEquals("local", local.id)
        assertEquals(SignInProvider.Mode.Credentials, local.mode)
        assertNull(local.installationId)
        assertNull(local.nativeStartPath)
        assertEquals(SignInProvider.Mode.OAuth, sso.mode)
        assertEquals("Example SSO", sso.displayName)
        assertEquals("3", sso.installationId)
        // Only the path is kept: apps open it on their own saved base, never on the listed origin.
        assertEquals("/api/v2/auth/oauth/3/native/start", sso.nativeStartPath)
        assertEquals("https://plugins.example.test/icon.svg", sso.iconUrl)
    }

    @Test
    fun authProviderRelativeIconResolvesAgainstTheListingServer() {
        val body = ApiV2Fixtures.json.parseToJsonElement(
            """{"items":[{"id":"plugin:3:oidc","display_name":"SSO","mode":"oauth","default":false,
               "icon_url":"/api/v2/plugin-content/3/assets/sso.svg","installation_id":"3"}],
               "password_login":false}""",
        ).jsonObject
        val provider = ApiV2Fixtures.decode<AuthProviderCollectionV2>(body).domain("https://silo.lan:8080/").providers.single()
        assertEquals("https://silo.lan:8080/api/v2/plugin-content/3/assets/sso.svg", provider.iconUrl)
        assertNull(provider.nativeStartPath, "no native_start_path offers no native sign-in")
    }

    /**
     * Whatever path prefix the listing names, apps get the start relative to
     * the server base, to resolve against their saved base URL. Only
     * `native_start_path` enables native sign-in.
     */
    @Test
    fun authProviderNativeStartIsReducedToTheBaseRelativePath() {
        fun path(fields: String) = ApiV2Fixtures.decode<AuthProviderCollectionV2>(
            ApiV2Fixtures.json.parseToJsonElement(
                """{"items":[{"id":"plugin:3:oidc","display_name":"SSO","mode":"oauth","installation_id":"3"$fields}]}""",
            ).jsonObject,
        ).domain("http://192.168.1.10:8096").providers.single().nativeStartPath
        val route = "/api/v2/auth/oauth/3/native/start"
        assertEquals(route, path(""","native_start_path":"/api/v2/auth/oauth/3/native/start""""))
        assertEquals(route, path(""","native_start_path":"/silo/api/v2/auth/oauth/3/native/start#x""""))
        assertEquals("$route?x=1", path(""","native_start_path":"/api/v2/auth/oauth/3/native/start?x=1""""))
        listOf(
            ""","native_start_path":"/api/v2/auth/oauth/3/other"""",
            ""","native_start_path":"//evil.example.test/api/v2/auth/oauth/3/native/start"""",
            ""","native_start_path":"https://public.example.test/api/v2/auth/oauth/3/native/start"""",
            ""","native_start_path":"/?next=/api/v2/auth/oauth/3/native/start"""",
            "",
        ).forEach { assertNull(path(it), it) }
    }

    @Test
    fun oauthHandshakeFixtureServesAppsAndLinking() {
        val handshake = ApiV2Fixtures.decode<OAuthHandshakeCapabilitiesV2>(
            ApiV2Fixtures.bodyObject("get_oauth_handshake_capabilities_ok").plusUnknown(),
        ).domain()
        assertTrue(handshake.available)
        assertTrue(handshake.native)
        assertTrue(handshake.linking)
        assertTrue(handshake.selectAccount)

        // An older server's body without the newer members: absent means not served.
        val older = ApiV2Fixtures.decode<OAuthHandshakeCapabilitiesV2>(
            with(ApiV2Fixtures.bodyObject("get_oauth_handshake_capabilities_ok"), "select_account" to null),
        ).domain()
        assertTrue(older.native)
        assertFalse(older.selectAccount, "absent select_account means sign-in starts don't take it")

        val disabled = ApiV2Fixtures.decode<OAuthHandshakeCapabilitiesV2>(
            with(ApiV2Fixtures.bodyObject("get_oauth_handshake_capabilities_ok"), "state" to JsonPrimitive("disabled")),
        ).domain()
        assertFalse(disabled.native)
        assertFalse(disabled.linking)
    }

    @Test
    fun externalSignInCapabilitiesFixtureServesIdentities() {
        val capabilities = ApiV2Fixtures.decode<ExternalSignInCapabilitiesV2>(
            ApiV2Fixtures.bodyObject("get_external_sign_in_capabilities_ok").plusUnknown(),
        ).domain()
        assertTrue(capabilities.identities)
        // Directory linking needs no OAuth handshake, so this document reports it.
        assertTrue(capabilities.credentialsLinking)
        val older = ApiV2Fixtures.decode<ExternalSignInCapabilitiesV2>(
            with(ApiV2Fixtures.bodyObject("get_external_sign_in_capabilities_ok"), "credentials_linking" to null),
        ).domain()
        assertFalse(older.credentialsLinking, "absent credentials_linking means the operation isn't served")
        assertTrue(capabilities.networkSignIn)
        val beforeNetwork = ApiV2Fixtures.decode<ExternalSignInCapabilitiesV2>(
            with(ApiV2Fixtures.bodyObject("get_external_sign_in_capabilities_ok"), "network_sign_in" to null),
        ).domain()
        assertFalse(beforeNetwork.networkSignIn, "absent network_sign_in means the operations aren't served")
        assertTrue(beforeNetwork.credentialsLinking)
        val unsupported = ApiV2Fixtures.decode<ExternalSignInCapabilitiesV2>(
            with(ApiV2Fixtures.bodyObject("get_external_sign_in_capabilities_ok"), "state" to JsonPrimitive("unsupported")),
        ).domain()
        assertFalse(unsupported.identities)
        assertFalse(unsupported.credentialsLinking)
        assertFalse(unsupported.networkSignIn)
    }

    @Test
    fun networkLinkKeepsPasswordDefaultsToFalseOnOlderServers() {
        val fixture = ApiV2Fixtures.bodyObject("get_external_sign_in_capabilities_ok")
        val keeps = ApiV2Fixtures.decode<ExternalSignInCapabilitiesV2>(
            with(fixture, "network_link_keeps_password" to JsonPrimitive(true)),
        ).domain()
        assertTrue(keeps.networkLinkKeepsPassword)
        val older = ApiV2Fixtures.decode<ExternalSignInCapabilitiesV2>(with(fixture, "network_link_keeps_password" to null)).domain()
        assertFalse(older.networkLinkKeepsPassword, "absent network_link_keeps_password means a network link turns the password off")
        val notServed = ApiV2Fixtures.decode<ExternalSignInCapabilitiesV2>(
            with(fixture, "network_link_keeps_password" to JsonPrimitive(true), "network_sign_in" to JsonPrimitive(false)),
        ).domain()
        assertFalse(notServed.networkLinkKeepsPassword, "false while network sign-in isn't served")
    }

    // --- Network identity (signInWithNetworkIdentity, linkAccountIdentityWithNetwork) ---

    @Test
    fun networkSignInFixtureCarriesTheTokenPairLoginAnswers() {
        val login = ApiV2Fixtures.decode<TokenPairV2>(ApiV2Fixtures.bodyObject("sign_in_with_network_identity_ok").plusUnknown()).domain()
        assertEquals("acc", login.accessToken)
        assertEquals("ref", login.refreshToken)
        assertEquals(3600L, login.expiresIn)
        assertEquals("laura", login.user.username)
        // The contract's request is the one the app sends: a POST of an empty JSON object.
        val request = ApiV2Fixtures.index.fixtures.single { it.name == "sign_in_with_network_identity_ok" }.request
        assertEquals("POST", request.method)
        assertEquals("{}", request.body)
        assertEquals("/api/v2/auth/network/5/sign-in", org.siloserver.silo.network.api.networkSignInPath(request.path))
    }

    @Test
    fun networkSignInOffTheOverlayIsNetworkIdentityRequired() {
        val problem = ApiV2Fixtures.decode<Problem>(ApiV2Fixtures.bodyObject("sign_in_with_network_identity_off_overlay"))
        assertEquals(403, problem.status)
        assertEquals("network_identity_required", problem.code)
        assertEquals(
            org.siloserver.silo.model.auth.NetworkSignInFailure.NetworkIdentityRequired,
            org.siloserver.silo.model.auth.NetworkSignInFailure.of(problem.status, problem.code),
        )
    }

    @Test
    fun networkLinkFixtureIsTheLinkedIdentity() {
        val identity = ApiV2Fixtures.decode<AccountIdentityV2>(
            ApiV2Fixtures.bodyObject("link_account_identity_with_network_ok").plusUnknown(),
        ).domain()
        assertEquals("8", identity.id)
        assertEquals("5", identity.installationId)
        assertEquals("Tailscale", identity.providerName)
        assertEquals("alice@example.test", identity.accountLabel)
        assertNull(identity.lastSignInAt)
    }

    /**
     * A network provider as `listAuthProviders` lists it over its own network
     * (the provider list fixture is read off it, so it shows none): apps get
     * the sign-in route relative to the server base, like the native start.
     */
    @Test
    fun networkProviderSignInPathIsReducedToTheBaseRelativeRoute() {
        fun provider(fields: String) = ApiV2Fixtures.decode<AuthProviderCollectionV2>(
            ApiV2Fixtures.json.parseToJsonElement(
                """{"items":[{"id":"plugin:5:tailscale","display_name":"Tailscale","mode":"network","default":false,
                   "installation_id":"5"$fields}],"password_login":true}""",
            ).jsonObject,
        ).domain("https://silo.tailnet.ts.net").providers.single()
        val route = "/api/v2/auth/network/5/sign-in"
        val listed = provider(
            ""","network_sign_in_path":"$route","network_identity":{"display_name":"","username":"alice@example.test"}""",
        )
        assertEquals(SignInProvider.Mode.Network, listed.mode)
        assertEquals(route, listed.networkSignInPath)
        assertEquals("alice@example.test", listed.networkIdentity?.label)
        assertEquals(route, provider(""","network_sign_in_path":"/silo/api/v2/auth/network/5/sign-in"""").networkSignInPath)
        assertNull(provider("").networkIdentity)
        listOf(
            "",
            ""","network_sign_in_path":"/api/v2/auth/network/5/other"""",
            ""","network_sign_in_path":"//evil.example.test/api/v2/auth/network/5/sign-in"""",
            ""","network_sign_in_path":"https://public.example.test/api/v2/auth/network/5/sign-in"""",
            ""","network_sign_in_path":"/api/v2/auth/oauth/5/native/start"""",
        ).forEach { assertNull(provider(it).networkSignInPath, it) }
        // Without its path the provider offers nothing.
        assertNull(SignInOptions.of(SignInProviders(listOf(provider("")), true), OAuthHandshakeCapabilities.None).networkProvider)
    }

    @Test
    fun nativeCompletionFixtureCarriesTokensAndUser() {
        val login = ApiV2Fixtures.decode<TokenPairV2>(ApiV2Fixtures.bodyObject("complete_oauth_login_native_ok").plusUnknown()).domain()
        assertEquals("acc", login.accessToken)
        assertEquals("ref", login.refreshToken)
        assertEquals(3600L, login.expiresIn)
        assertEquals("laura", login.user.username)
    }

    @Test
    fun accountIdentitiesAndLinkTicketFixtures() {
        val collection = ApiV2Fixtures.decode<AccountIdentityCollectionV2>(
            ApiV2Fixtures.bodyObject("list_account_identities_ok").plusUnknown(),
        )
        // The only identity and no local password: Disconnect is not offered.
        assertEquals(false, collection.canUnlink)
        val identity = collection.items.single().domain()
        assertEquals("4", identity.id)
        assertEquals("3", identity.installationId)
        assertEquals("Company SSO", identity.providerName)
        assertEquals("alice", identity.accountLabel)
        assertEquals("2026-01-03T04:05:06.000Z", identity.lastSignInAt)

        val ticket = ApiV2Fixtures.decode<LinkTicketV2>(ApiV2Fixtures.bodyObject("create_account_identity_link_ticket_ok"))
        assertTrue(ticket.ticket.isNotBlank())
        assertFalse(ticket.toString().contains(ticket.ticket), "the ticket never reaches a log line")
    }

    @Test
    fun externalSignInProblemFixturesMapToTheirCodes() {
        fun raw(name: String) = ApiV2Fixtures.bodyObject(name).toString()
        val wrongPassword = DefaultExternalSignInApi.locatedError(422, raw("create_account_identity_link_ticket_wrong_password"))
        assertEquals(DefaultExternalSignInApi.WRONG_PASSWORD, wrongPassword.error)
        assertEquals(
            "last_sign_in_method",
            DefaultExternalSignInApi.locatedError(409, raw("delete_account_identity_last_sign_in_method")).error,
        )
        assertEquals("invalid_grant", ApiV2Fixtures.decode<Problem>(ApiV2Fixtures.bodyObject("complete_oauth_login_invalid_grant")).code)
        assertEquals(
            "invalid_grant",
            ApiV2Fixtures.decode<Problem>(ApiV2Fixtures.bodyObject("complete_account_identity_link_invalid_grant")).code,
        )
        assertEquals(
            "provider_unavailable",
            ApiV2Fixtures.decode<Problem>(ApiV2Fixtures.bodyObject("refresh_session_provider_unavailable")).code,
        )
    }

    @Test
    fun signInOptionsFromTheProvidersFixture() {
        val providers = ApiV2Fixtures.decode<AuthProviderCollectionV2>(ApiV2Fixtures.bodyObject("list_auth_providers_ok"))
            .domain("https://silo.example.test")
        val handshake = ApiV2Fixtures.decode<OAuthHandshakeCapabilitiesV2>(
            ApiV2Fixtures.bodyObject("get_oauth_handshake_capabilities_ok"),
        ).domain()
        val options = SignInOptions.of(providers, handshake)
        assertTrue(options.showPasswordForm)
        assertEquals(listOf("Example SSO"), options.oauthProviders.map { it.displayName })
        assertNull(options.directoryProvider)
        assertNull(options.networkProvider, "a listing that didn't come through a network provider offers none")
    }
}
