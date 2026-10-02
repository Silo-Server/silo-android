package org.siloserver.silo.model.auth

/**
 * One way to sign in that the server lists before login
 * (`GET /api/v2/auth/providers`). See silo-server `docs/auth-api.md`
 * ("External sign-in") and `docs/architecture/external-sign-in.md`.
 */
data class SignInProvider(
    /** The value `login` takes as `provider`; apps never send it (the server routes by account). */
    val id: String,
    val displayName: String,
    val mode: Mode,
    val isDefault: Boolean,
    /** Absolute icon URL, or null when the provider ships none. */
    val iconUrl: String?,
    val installationId: String?,
    /**
     * `startNativeOAuthLogin` relative to the server base
     * (`/api/v2/auth/oauth/{id}/native/start`); oauth providers only. Apps
     * open it on their own saved base URL, never on the origin the server
     * listed it with.
     */
    val nativeStartPath: String?,
    /**
     * `signInWithNetworkIdentity` relative to the server base
     * (`/api/v2/auth/network/{id}/sign-in`); network providers only. Apps
     * POST `{}` to it on their own saved base URL, like [nativeStartPath].
     */
    val networkSignInPath: String? = null,
    /** Who the network provider says owns this device; network providers only. It authorizes nothing. */
    val networkIdentity: NetworkIdentity? = null,
) {
    /**
     * How the provider signs people in: [Network] providers (such as the
     * Tailscale plugin) are listed only to a request that came through their
     * own network, and sign in the owner of the device with no password and
     * no browser.
     */
    enum class Mode { Credentials, OAuth, Network, Unknown }
}

/** A network provider's `network_identity`: the person who owns the device that asked. */
data class NetworkIdentity(
    val displayName: String,
    val username: String,
) {
    /** The name for "Continue as": the display name, else the username; null when the provider gave neither. */
    val label: String?
        get() = displayName.trim().ifEmpty { username.trim() }.ifEmpty { null }
}

/** `listAuthProviders`: the providers plus whether any of them takes a password. */
data class SignInProviders(
    val providers: List<SignInProvider>,
    val passwordLogin: Boolean,
)

/** `getOAuthHandshakeCapabilities`, reduced to what the apps use. */
data class OAuthHandshakeCapabilities(
    val available: Boolean,
    /** `startNativeOAuthLogin` serves apps (the `org.siloserver.silo:/auth/callback` handoff). */
    val native: Boolean,
    /** Link tickets and `completeAccountIdentityLink` are served. */
    val linking: Boolean,
    /** Sign-in starts take `prompt=select_account` ("Use a different account", "Not you? Switch account"). */
    val selectAccount: Boolean = false,
    /** A network provider (such as Tailscale) that signs in this device's owner, with its sign-in path. */
    val networkProvider: SignInProvider? = null,
) {
    companion object {
        val None = OAuthHandshakeCapabilities(available = false, native = false, linking = false)
    }
}

/** `getExternalSignInCapabilities`, reduced to what the apps use. */
data class ExternalSignInCapabilities(
    /** `listAccountIdentities` and `deleteAccountIdentity` are served: Settings shows "Sign-in". */
    val identities: Boolean,
    /**
     * `linkAccountIdentityWithCredentials` is served: an account links a
     * directory (LDAP) identity with its directory username and password.
     * Directory linking needs no OAuth handshake, so this document reports it.
     */
    val credentialsLinking: Boolean = false,
    /**
     * `signInWithNetworkIdentity` and `linkAccountIdentityWithNetwork` are
     * served. Whether this device may use them is answered by provider
     * discovery, which lists a network provider only through its own network.
     */
    val networkSignIn: Boolean = false,
)

/**
 * What a sign-in screen offers for one server.
 *
 * - [oauthProviders]: a "Sign in with <name>" button each. Only providers the
 *   app can run natively: an oauth provider with a native start path, on a
 *   server whose handshake capability says `native`.
 * - [showPasswordForm]: the username and password form. Shown when any listed
 *   provider takes a password (the local one, or a directory such as LDAP,
 *   which the server reaches without a provider field) and hidden only when
 *   the server says no provider does.
 * - [networkProvider]: "Continue as <owner>" above everything else. The
 *   server lists a network provider only when this request came through that
 *   provider's network; it never changes what else is offered.
 */
data class SignInOptions(
    val oauthProviders: List<SignInProvider>,
    val showPasswordForm: Boolean,
    /** A non-local credentials provider (LDAP) is enabled: its people use the password form too. */
    val directoryProvider: SignInProvider?,
    /** A provider sign-in may ask the provider to offer another account (`prompt=select_account`). */
    val selectAccount: Boolean = false,
    /** A network provider (such as Tailscale) that signs in this device's owner, with its sign-in path. */
    val networkProvider: SignInProvider? = null,
) {
    companion object {
        /** A server that predates provider discovery: the password form alone. */
        val PasswordOnly = SignInOptions(oauthProviders = emptyList(), showPasswordForm = true, directoryProvider = null)

        /**
         * What [providers] offer through the app, given the server's OAuth
         * [handshake]. The account's Sign-in settings build on the same
         * filters (linking adds its own capability gates).
         */
        fun of(providers: SignInProviders, handshake: OAuthHandshakeCapabilities): SignInOptions {
            val oauth = if (handshake.available && handshake.native) {
                providers.providers.filter {
                    it.mode == SignInProvider.Mode.OAuth && !it.nativeStartPath.isNullOrBlank()
                }
            } else {
                emptyList()
            }
            val credentials = providers.providers.filter { it.mode == SignInProvider.Mode.Credentials }
            return SignInOptions(
                oauthProviders = oauth,
                showPasswordForm = providers.passwordLogin || credentials.isNotEmpty(),
                directoryProvider = credentials.firstOrNull { it.id != LOCAL_PROVIDER_ID },
                selectAccount = oauth.isNotEmpty() && handshake.selectAccount,
                networkProvider = providers.providers.firstOrNull {
                    it.mode == SignInProvider.Mode.Network && !it.networkSignInPath.isNullOrBlank()
                },
            )
        }

        const val LOCAL_PROVIDER_ID = "local"
    }
}

/** One external identity linked to the signed-in account (`listAccountIdentities`). */
data class AccountIdentity(
    val id: String,
    val installationId: String,
    /** Empty while that provider is not enabled. */
    val providerId: String,
    /** Empty while that provider is not enabled. */
    val providerName: String,
    val username: String,
    val email: String,
    val displayName: String,
    /** RFC 3339 instant. */
    val linkedAt: String,
    /** RFC 3339 instant, or null before the first sign-in through it. */
    val lastSignInAt: String?,
) {
    /** The name to show for the person at the provider: username, then email, then display name. */
    val accountLabel: String
        get() = username.ifBlank { email.ifBlank { displayName } }
}

/** `listAccountIdentities`: the account's identities and whether one may be disconnected now. */
data class AccountIdentities(
    val items: List<AccountIdentity>,
    /**
     * Whether `deleteAccountIdentity` would disconnect an identity now (the
     * account has another identity, or its local password still signs in).
     * Null from a server that doesn't say; Disconnect is then offered and the
     * server's refusal explains.
     */
    val canUnlink: Boolean?,
)

/** A single-use ticket that starts one linking flow (`createAccountIdentityLinkTicket`). */
data class AccountIdentityLinkTicket(
    val ticket: String,
    val expiresAt: String,
) {
    override fun toString(): String = "AccountIdentityLinkTicket(ticket=<redacted>, expiresAt=$expiresAt)"
}

/**
 * Why a password sign-in (`login`) was refused, from the problem code first
 * and the status second (silo-server `docs/auth-api.md`, "External sign-in":
 * `local_login_disabled`, `not_permitted` and `password_expired` are 403,
 * `provider_unavailable` 503). Phone and TV map each to their own copy.
 */
enum class PasswordLoginFailure {
    InvalidCredentials,

    /** Password sign-in is off on this server and the account is not break-glass. */
    LocalLoginDisabled,

    /** The directory accepted the password but its group rules don't admit the account. */
    NotPermitted,

    /** The directory says the password has expired. */
    PasswordExpired,
    AccountDisabled,

    /** The directory (LDAP) could not be reached. */
    ProviderUnavailable,

    /** A first directory sign-in found another account with the same email (409). */
    EmailInUse,

    /** The directory identity is linked to another account (409). */
    IdentityLinkedElsewhere,

    /** Too many attempts (429). */
    RateLimited,

    /** A first directory sign-in found no account and the server doesn't create one (`account_required`). */
    AccountRequired,
    Other;

    companion object {
        fun of(code: Int, problem: String): PasswordLoginFailure = when {
            problem == "local_login_disabled" -> LocalLoginDisabled
            problem == "not_permitted" -> NotPermitted
            problem == "password_expired" -> PasswordExpired
            problem == "provider_unavailable" -> ProviderUnavailable
            problem == "email_in_use" -> EmailInUse
            problem == "identity_linked_elsewhere" -> IdentityLinkedElsewhere
            problem == "account_required" -> AccountRequired
            problem == "rate_limited" || code == 429 -> RateLimited
            code == 401 -> InvalidCredentials
            code == 403 -> AccountDisabled
            code == 503 -> ProviderUnavailable
            else -> Other
        }
    }
}

/**
 * Why a network identity sign-in (`signInWithNetworkIdentity`) was refused,
 * from the problem code first and the status second (silo-server
 * `docs/auth-api.md`, "External sign-in"). Phone and TV map each to their own copy.
 */
enum class NetworkSignInFailure {
    /**
     * The request didn't come through the provider's network
     * (`network_identity_required`): the app uses another address for the
     * server, or the device left that network.
     */
    NetworkIdentityRequired,

    /** The provider refuses this device (`not_permitted`): a tagged device, or one its policy leaves out. */
    NotPermitted,

    /** No account matches and the server doesn't create one (`account_required`). */
    AccountRequired,

    /** The account is disabled (`permission_denied`). */
    AccountDisabled,

    /** A first sign-in found another account with the same email (409): link from that account instead. */
    EmailInUse,

    /** The network identity is linked to another account (409). */
    IdentityLinkedElsewhere,

    /** Not an enabled network provider any more (404). */
    NotFound,
    ProviderUnavailable,

    /** Too many attempts (429). */
    RateLimited,
    Other;

    companion object {
        fun of(code: Int, problem: String): NetworkSignInFailure = when {
            problem == "network_identity_required" -> NetworkIdentityRequired
            problem == "not_permitted" -> NotPermitted
            problem == "account_required" -> AccountRequired
            problem == "permission_denied" -> AccountDisabled
            problem == "email_in_use" -> EmailInUse
            problem == "identity_linked_elsewhere" -> IdentityLinkedElsewhere
            problem == "provider_unavailable" -> ProviderUnavailable
            problem == "rate_limited" || code == 429 -> RateLimited
            problem == "not_found" || code == 404 -> NotFound
            code == 503 -> ProviderUnavailable
            else -> Other
        }
    }
}
