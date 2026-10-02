package org.siloserver.silo.android.ui.screens.settings

import org.siloserver.silo.android.ui.screens.auth.passwordLoginMessage
import org.siloserver.silo.model.auth.AccountIdentity
import org.siloserver.silo.model.auth.OAuthHandshakeCapabilities
import org.siloserver.silo.model.auth.SignInProvider
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.api.DefaultExternalSignInApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignInSettingsTest {
    private val local = SignInProvider("local", "Silo account", SignInProvider.Mode.Credentials, true, null, null, null)
    private val ldap = SignInProvider("plugin:6:ldap", "Directory", SignInProvider.Mode.Credentials, false, null, "6", null)
    private val oidc = SignInProvider(
        "plugin:5:oidc", "Keycloak", SignInProvider.Mode.OAuth, false, null, "5",
        "/api/v2/auth/oauth/5/native/start",
    )
    private val identity = AccountIdentity("4", "5", "plugin:5:oidc", "Keycloak", "alice", "", "", "2026-01-02T03:04:05Z", null)
    private val all = OAuthHandshakeCapabilities(available = true, native = true, linking = true)

    @Test
    fun aServerWithoutIdentitiesShowsNoSection() {
        val section = SignInSettingsViewModel.sectionOf(false, listOf(identity), listOf(local, oidc, ldap), all, credentialsLinking = true)
        assertTrue(section.identities.isEmpty() && section.connectable.isEmpty() && section.directory == null)
    }

    @Test
    fun oidcIsConnectableThroughTheBrowserUntilLinked() {
        val unlinked = SignInSettingsViewModel.sectionOf(true, emptyList(), listOf(local, oidc), all, credentialsLinking = true)
        assertEquals(listOf(oidc), unlinked.connectable)
        val linked = SignInSettingsViewModel.sectionOf(true, listOf(identity), listOf(local, oidc), all, credentialsLinking = true)
        assertTrue(linked.connectable.isEmpty())
        assertEquals(listOf(identity), linked.identities)
        val noAppLinking = SignInSettingsViewModel.sectionOf(true, emptyList(), listOf(oidc), all.copy(linking = false), credentialsLinking = true)
        assertTrue(noAppLinking.connectable.isEmpty())
    }

    @Test
    fun ldapIsConnectableOnlyWithCredentialsLinking() {
        assertEquals(ldap, SignInSettingsViewModel.sectionOf(true, emptyList(), listOf(local, ldap), all, credentialsLinking = true).directory)
        assertNull(SignInSettingsViewModel.sectionOf(true, emptyList(), listOf(local, ldap), all, credentialsLinking = false).directory)
        assertNull(SignInSettingsViewModel.sectionOf(true, emptyList(), listOf(local), all, credentialsLinking = true).directory, "the local provider is not a directory")
        val ldapIdentity = identity.copy(installationId = "6")
        assertNull(SignInSettingsViewModel.sectionOf(true, listOf(ldapIdentity), listOf(ldap), all, credentialsLinking = true).directory)
    }

    /** `credentials_linking` comes from the external sign-in document, not the OAuth handshake. */
    @Test
    fun directoryLinkingNeedsNoOAuthHandshake() {
        assertEquals(
            ldap,
            SignInSettingsViewModel.sectionOf(true, emptyList(), listOf(local, ldap), OAuthHandshakeCapabilities.None, credentialsLinking = true)
                .directory,
        )
    }

    @Test
    fun canUnlinkDecidesDisconnect() {
        val only = SignInSettingsViewModel.sectionOf(true, listOf(identity), listOf(oidc), all, credentialsLinking = true, canUnlink = false)
        assertEquals(false, only.canUnlink)
        val free = SignInSettingsViewModel.sectionOf(true, listOf(identity), listOf(oidc), all, credentialsLinking = true, canUnlink = true)
        assertEquals(true, free.canUnlink)
        assertNull(SignInSettingsViewModel.sectionOf(true, emptyList(), listOf(oidc), all, credentialsLinking = true, canUnlink = false).canUnlink)
        // With can_unlink the confirmation no longer hedges.
        assertFalse(SignInSettingsViewModel.disconnectConfirmBody(identity, canUnlink = true).contains("another way to sign in"))
        assertTrue(SignInSettingsViewModel.ONLY_SIGN_IN_METHOD.contains("only way to sign in"))
    }

    @Test
    fun directoryRefusalsHaveTheirOwnText() {
        val codes = listOf(
            422 to DefaultExternalSignInApi.WRONG_PASSWORD,
            422 to DefaultExternalSignInApi.DIRECTORY_REFUSED,
            409 to "local_password_required",
            403 to "not_permitted",
            403 to "account_disabled",
            403 to "password_expired",
            403 to "permission_denied",
            409 to "identity_linked_elsewhere",
            409 to "already_linked",
            404 to "not_found",
            503 to "provider_unavailable",
            429 to "rate_limited",
        )
        val texts = codes.map { (status, code) ->
            SignInSettingsViewModel.directoryLinkMessage(ApiResult.Error(status, code, "server detail"), "Directory")
        }
        assertEquals(texts.size, texts.toSet().size, texts.joinToString("\n"))
        texts.forEach { assertFalse(it.contains("server detail"), "the server's detail is never shown") }
        assertTrue(texts[1].contains("Directory"))
        // Typing mistakes and throttling keep the dialog open; the rest close it.
        assertTrue(SignInSettingsViewModel.keepsPromptOpen(ApiResult.Error(422, DefaultExternalSignInApi.WRONG_PASSWORD, "")))
        assertTrue(SignInSettingsViewModel.keepsPromptOpen(ApiResult.Error(429, "rate_limited", "")))
        assertFalse(SignInSettingsViewModel.keepsPromptOpen(ApiResult.Error(409, "identity_linked_elsewhere", "")))
    }

    @Test
    fun linkTicketAndDisconnectRefusals() {
        assertEquals(
            "That password is incorrect.",
            SignInSettingsViewModel.linkTicketMessage(ApiResult.Error(422, DefaultExternalSignInApi.WRONG_PASSWORD, ""), "Keycloak"),
        )
        assertTrue(SignInSettingsViewModel.linkTicketMessage(ApiResult.Error(409, "conflict", ""), "Keycloak").contains("no password"))
        assertTrue(SignInSettingsViewModel.linkTicketMessage(ApiResult.Error(403, "permission_denied", ""), "Keycloak").contains("session"))
        assertTrue(
            SignInSettingsViewModel.disconnectMessage(ApiResult.Error(409, "last_sign_in_method", ""), identity)
                .contains("only way to sign in"),
        )
        assertTrue(SignInSettingsViewModel.disconnectMessage(ApiResult.Error(404, "not_found", ""), identity).contains("already"))
    }

    @Test
    fun connectSaysThePasswordStopsWorkingAndDisconnectDoesNotPromiseIt() {
        assertTrue(SignInSettingsViewModel.connectDescription("Keycloak").contains("instead of your password"))
        assertTrue(SignInSettingsViewModel.connectPrompt("Keycloak").contains("instead of your password"))
        assertTrue(SignInSettingsViewModel.connectDirectoryDescription("Directory").contains("Directory username and password instead"))
        assertTrue(SignInSettingsViewModel.connectDirectoryPrompt("Directory").contains("username and password instead"))
        val body = SignInSettingsViewModel.disconnectConfirmBody(identity)
        assertFalse(body.contains("such as your password"), body)
        assertTrue(body.contains("another way to sign in"), body)
    }

    @Test
    fun sharedRefusalsReadTheSameInEveryOperation() {
        val tooMany = ApiResult.Error(429, "rate_limited", "")
        assertEquals(
            SignInSettingsViewModel.linkTicketMessage(tooMany, "Keycloak"),
            SignInSettingsViewModel.disconnectMessage(tooMany, identity),
        )
        val denied = ApiResult.Error(403, "permission_denied", "")
        assertEquals(
            SignInSettingsViewModel.linkTicketMessage(denied, "Keycloak"),
            SignInSettingsViewModel.directoryLinkMessage(denied, "Keycloak"),
        )
    }

    @Test
    fun passwordLoginNamesLocalLoginDisabled() {
        assertEquals(
            "Password sign-in is turned off on this server. Sign in with Keycloak instead.",
            passwordLoginMessage(ApiResult.Error(403, "local_login_disabled", ""), "Keycloak"),
        )
        assertEquals(
            "Password sign-in is turned off on this server.",
            passwordLoginMessage(ApiResult.Error(403, "local_login_disabled", "")),
        )
        assertEquals("Invalid username or password", passwordLoginMessage(ApiResult.Error(401, "invalid_credentials", "")))
        assertTrue(passwordLoginMessage(ApiResult.Error(403, "password_expired", "")).contains("expired"))
        assertTrue(passwordLoginMessage(ApiResult.Error(503, "provider_unavailable", "")).contains("can't be reached"))
    }
}
