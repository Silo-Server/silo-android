package org.siloserver.silo.repository

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.siloserver.silo.model.auth.AccountIdentities
import org.siloserver.silo.model.auth.AccountIdentity
import org.siloserver.silo.model.auth.AccountIdentityLinkTicket
import org.siloserver.silo.model.auth.ExternalSignInCapabilities
import org.siloserver.silo.model.auth.LoginResponse
import org.siloserver.silo.model.auth.OAuthHandshakeCapabilities
import org.siloserver.silo.model.auth.SignInOptions
import org.siloserver.silo.model.auth.SignInProviders
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.api.ExternalSignInApi

/**
 * External sign-in (OIDC, LDAP, network identity) for the apps: what a
 * sign-in screen offers, and the account's Sign-in section. The native OAuth
 * handoff itself (browser, PKCE, the app redirect) belongs to the phone app;
 * TVs never run it. The network identity sign-in needs neither and runs on both.
 */
class ExternalSignInRepository(
    private val api: ExternalSignInApi,
) {
    /**
     * What to offer on [serverUrl]'s sign-in screen, or null when it couldn't
     * be asked just now (a network failure, a 5xx or a 429 on either read):
     * the screen then offers the password form and a retry rather than
     * settling on no providers. A server that answers without provider
     * discovery (an older one) gets the password form
     * alone, as before external sign-in; one that answers without the OAuth
     * handshake capability shows no provider buttons.
     */
    suspend fun signInOptions(serverUrl: String): SignInOptions? = coroutineScope {
        val providers = async { api.listProviders(serverUrl) }
        val handshake = async { api.oauthCapabilities(serverUrl) }
        val listed = when (val result = providers.await()) {
            is ApiResult.Success -> result.data
            is ApiResult.Error -> return@coroutineScope if (result.isTemporary()) null else SignInOptions.PasswordOnly
            is ApiResult.NetworkError -> return@coroutineScope null
        }
        val capabilities = when (val result = handshake.await()) {
            is ApiResult.Success -> result.data
            is ApiResult.Error -> if (result.isTemporary()) return@coroutineScope null else OAuthHandshakeCapabilities.None
            is ApiResult.NetworkError -> return@coroutineScope null
        }
        SignInOptions.of(listed, capabilities)
    }

    /** A refusal that says nothing about what the server offers: try again later. */
    private fun ApiResult.Error.isTemporary(): Boolean = code >= 500 || code == 429

    suspend fun providers(serverUrl: String): ApiResult<SignInProviders> = api.listProviders(serverUrl)

    suspend fun oauthCapabilities(serverUrl: String): ApiResult<OAuthHandshakeCapabilities> =
        api.oauthCapabilities(serverUrl)

    suspend fun externalSignInCapabilities(scope: AuthScopeSnapshot): ApiResult<ExternalSignInCapabilities> =
        api.externalSignInCapabilities(scope)

    suspend fun identities(scope: AuthScopeSnapshot): ApiResult<AccountIdentities> = api.listIdentities(scope)

    suspend fun disconnect(scope: AuthScopeSnapshot, identityId: String): ApiResult<Unit> =
        api.deleteIdentity(scope, identityId)

    suspend fun linkTicket(
        scope: AuthScopeSnapshot,
        installationId: String,
        password: String,
    ): ApiResult<AccountIdentityLinkTicket> = api.createLinkTicket(scope, installationId, password)

    suspend fun linkWithCredentials(
        scope: AuthScopeSnapshot,
        installationId: String,
        password: String,
        username: String,
        directoryPassword: String,
    ): ApiResult<AccountIdentity> = api.linkWithCredentials(scope, installationId, password, username, directoryPassword)

    /** The token pair for this device's owner at a network provider; see [ExternalSignInApi.signInWithNetworkIdentity]. */
    suspend fun signInWithNetworkIdentity(serverUrl: String, signInPath: String): ApiResult<LoginResponse> =
        api.signInWithNetworkIdentity(serverUrl, signInPath)

    suspend fun linkWithNetwork(
        scope: AuthScopeSnapshot,
        installationId: String,
        password: String,
    ): ApiResult<AccountIdentity> = api.linkWithNetwork(scope, installationId, password)
}
