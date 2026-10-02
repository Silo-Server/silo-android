package org.siloserver.silo.android.auth

import java.net.IDN
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.Serializable
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.SiloAuthUnavailableException
import org.siloserver.silo.network.canonicalHttpOrigin

/**
 * The native OAuth handoff (silo-server `docs/architecture/external-sign-in.md`,
 * "Native apps"): the app opens the provider's native start in the system
 * browser with a PKCE S256 challenge and a random `app_state`; the server
 * returns the browser to the fixed app redirect
 * `org.siloserver.silo:/auth/callback?code=&state=&server=&iss=` (or
 * `error=`), and the app redeems the code with its verifier. Codes, verifiers
 * and tokens are never logged.
 *
 * The native start always opens on the saved server's own base URL: the path
 * the server lists is resolved against it, whatever origin it was listed
 * with. The server answers with `iss`, the origin where the native start first
 * arrived (RFC 9207), which must be the saved base's origin, and the code is
 * redeemed at the saved base only. The saved server never changes address or
 * id because of a sign-in. The `server` id is self-asserted, so it never
 * decides where the verifier goes. A saved server whose native start relays
 * the browser to another server gets nothing: the other server's redirect
 * names its own origin.
 */
object NativeSignInProtocol {
    const val CALLBACK_SCHEME = "org.siloserver.silo"
    const val CALLBACK_PATH = "/auth/callback"

    /** `prompt` value asking the provider to let the person pick another account. */
    const val PROMPT_SELECT_ACCOUNT = "select_account"

    /** Server flows expire after 10 minutes; a callback for an older flow is ignored. */
    const val FLOW_LIFETIME_MS = 10 * 60 * 1000L

    private val random = SecureRandom()

    /** 32 random bytes as unpadded base64url: 43 unreserved characters (RFC 7636 §4.1). */
    fun newCodeVerifier(): String = randomToken()

    /** A random `app_state` of unreserved characters. */
    fun newAppState(): String = randomToken()

    /** `BASE64URL(SHA256(ASCII(verifier)))`, unpadded (RFC 7636 §4.2). */
    @OptIn(ExperimentalEncodingApi::class)
    fun challenge(verifier: String): String =
        base64Url.encode(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    @OptIn(ExperimentalEncodingApi::class)
    private fun randomToken(): String = ByteArray(32).also(random::nextBytes).let(base64Url::encode)

    @OptIn(ExperimentalEncodingApi::class)
    private val base64Url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    /**
     * The URL to open: [nativeStartPath] (base-relative, from the provider
     * listing) on the saved server's base [serverUrl], with the PKCE
     * challenge, `app_state`, a `prompt` when given and, when linking, the
     * link ticket. Null when [serverUrl] isn't an http(s) base or the path
     * isn't base-relative, so nothing ever opens on another origin.
     */
    fun startUrl(
        serverUrl: String,
        nativeStartPath: String,
        appState: String,
        codeVerifier: String,
        linkTicket: String? = null,
        prompt: String? = null,
    ): String? {
        if (origin(serverUrl) == null) return null
        if (!nativeStartPath.startsWith("/") || nativeStartPath.startsWith("//")) return null
        val params = buildList {
            add("code_challenge" to challenge(codeVerifier))
            add("code_challenge_method" to "S256")
            add("app_state" to appState)
            if (prompt != null) add("prompt" to prompt)
            if (linkTicket != null) add("link_ticket" to linkTicket)
        }.joinToString("&") { (name, value) -> "$name=${URLEncoder.encode(value, "UTF-8")}" }
        val base = serverUrl.trim().substringBefore('#').substringBefore('?').trimEnd('/') + nativeStartPath.substringBefore('#')
        val separator = when {
            !base.contains('?') -> "?"
            base.endsWith("?") || base.endsWith("&") -> ""
            else -> "&"
        }
        return base + separator + params
    }

    /**
     * The web origin of an http(s) [url] (`scheme://host[:port]`, lowercase,
     * default port omitted), or null when it has none or carries user info.
     * The app's canonical form ([canonicalHttpOrigin]) with the host written
     * the way a browser sends it as `Host`, which is what the server names in
     * `iss`: an IDN name in punycode and an IPv6 literal compressed (RFC 5952).
     * Used for every same-origin decision in a native flow, so the saved base
     * and `iss` compare in one form.
     */
    fun origin(url: String?): String? {
        val trimmed = url?.trim().orEmpty()
        if (!trimmed.startsWith("https://", ignoreCase = true) && !trimmed.startsWith("http://", ignoreCase = true)) return null
        val canonical = canonicalHttpOrigin(trimmed) ?: return null
        val scheme = canonical.substringBefore("://")
        val authority = canonical.substringAfter("://")
        val host = if (authority.startsWith('[')) authority.substringBefore(']') + "]" else authority.substringBefore(':')
        val port = authority.removePrefix(host)
        return "$scheme://${browserHost(host) ?: return null}$port"
    }

    /** [host] as a browser's URL serializer writes it; null when an IDN name has no ASCII form. */
    private fun browserHost(host: String): String? = when {
        host.startsWith('[') -> "[${compressIpv6(host.trim('[', ']')) ?: host.trim('[', ']')}]"
        host.all { it.code < 0x80 } -> host
        else -> runCatching { IDN.toASCII(host).lowercase() }.getOrNull()
    }

    /**
     * An IPv6 literal of hex groups in RFC 5952 form: lowercase, no leading
     * zeros, the first longest run of two or more zero groups as `::`. Null
     * when [literal] isn't eight groups once expanded.
     */
    internal fun compressIpv6(literal: String): String? {
        val halves = literal.split("::")
        if (halves.size > 2) return null
        fun groups(part: String) = if (part.isEmpty()) emptyList() else part.split(':')
        val left = groups(halves[0])
        val right = if (halves.size == 2) groups(halves[1]) else emptyList()
        val missing = 8 - left.size - right.size
        if (if (halves.size == 2) missing < 1 else missing != 0) return null
        val values = (left + List(missing) { "0" } + right).map { it.toIntOrNull(16) ?: return null }
        var bestStart = -1
        var bestLength = 1
        var index = 0
        while (index < values.size) {
            if (values[index] != 0) {
                index++
                continue
            }
            val start = index
            while (index < values.size && values[index] == 0) index++
            if (index - start > bestLength) {
                bestStart = start
                bestLength = index - start
            }
        }
        val hex = values.map { it.toString(16) }
        if (bestStart < 0) return hex.joinToString(":")
        return hex.subList(0, bestStart).joinToString(":") + "::" + hex.subList(bestStart + bestLength, 8).joinToString(":")
    }
}

/** What the app redirect carried. Parsed only for the exact scheme and path. */
data class NativeSignInCallback(
    val code: String?,
    val state: String?,
    val server: String?,
    val error: String?,
    val link: Boolean,
    /** The origin where the flow's native start first arrived (RFC 9207 `iss`); must equal the saved base's origin; required. */
    val iss: String? = null,
) {
    override fun toString(): String =
        "NativeSignInCallback(code=${if (code == null) "none" else "<redacted>"}, " +
            "state=${if (state == null) "none" else "<redacted>"}, server=$server, iss=$iss, error=$error, link=$link)"

    companion object {
        /**
         * Null unless [uri] is exactly `org.siloserver.silo:/auth/callback`
         * with a query: another path, a host, or user info is not ours.
         */
        fun parse(uri: String?): NativeSignInCallback? {
            if (uri.isNullOrBlank()) return null
            val parsed = runCatching { URI(uri) }.getOrNull() ?: return null
            if (!parsed.scheme.equals(NativeSignInProtocol.CALLBACK_SCHEME, ignoreCase = true)) return null
            if (parsed.isOpaque || parsed.rawAuthority != null) return null
            if (parsed.rawPath != NativeSignInProtocol.CALLBACK_PATH) return null
            val query = parsed.rawQuery ?: return null
            val values = mutableMapOf<String, String>()
            for (pair in query.split('&')) {
                if (pair.isEmpty()) continue
                val name = decode(pair.substringBefore('='))
                val value = decode(pair.substringAfter('=', ""))
                // A repeated parameter is ambiguous: the first one wins, as on the server.
                if (name != null && value != null && name !in values) values[name] = value
            }
            return NativeSignInCallback(
                code = values["code"]?.takeIf { it.isNotEmpty() },
                state = values["state"]?.takeIf { it.isNotEmpty() },
                server = values["server"]?.takeIf { it.isNotEmpty() },
                error = values["error"]?.takeIf { it.isNotEmpty() },
                link = values["link"] == "1",
                iss = values["iss"]?.takeIf { it.isNotEmpty() },
            )
        }

        private fun decode(value: String): String? = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrNull()
    }
}

/** Why a flow was started: to sign in, or to link the signed-in account (Account settings). */
enum class NativeSignInPurpose { SignIn, Link }

/**
 * A flow the app started and has not finished. Kept on disk (encrypted) for
 * the flow's lifetime, so a process the system killed while the browser was
 * open can still finish it.
 */
@Serializable
data class PendingNativeSignIn(
    val purpose: NativeSignInPurpose,
    /** The saved server ([org.siloserver.silo.model.server.ServerEntry.id]) the flow belongs to. */
    val serverEntryId: String,
    /** That server's verified `server_id`; the redirect's `server` must equal it. */
    val verifiedServerId: String,
    /**
     * The origin of the saved server's base URL, where the app opened the
     * native start. The redirect's `iss` must equal it, and the code is
     * redeemed only while the saved server is still at that origin.
     */
    val startOrigin: String,
    val appState: String,
    val codeVerifier: String,
    val providerName: String,
    val startedAtEpochMs: Long,
    /** Linking only: the account session that asked, which must still be the signed-in one. */
    val identityGeneration: Long? = null,
    val credentialEpoch: Long? = null,
) {
    override fun toString(): String =
        "PendingNativeSignIn(purpose=$purpose, serverEntryId=$serverEntryId, providerName=$providerName, <secrets redacted>)"
}

interface PendingNativeSignInStore {
    fun load(): PendingNativeSignIn?
    fun save(pending: PendingNativeSignIn)
    fun clear()
}

/**
 * Saved servers whose next provider sign-in asks the provider to offer
 * another account (`prompt=select_account`): set by an explicit Silo
 * sign-out, cleared by the next sign-in there. Kept on disk, so a sign-out
 * today still asks tomorrow after the app was closed.
 */
interface AccountChoiceStore {
    fun request(serverEntryId: String)
    fun isRequested(serverEntryId: String): Boolean
    fun clear(serverEntryId: String)
}

/**
 * Text for every reason a native flow can end without signing in: the
 * server's `error=` reasons (docs/auth-api.md, "OAuth sign-in flows") and the
 * app's own checks. The same wording as the web login page where both exist.
 * The account's Sign-in settings and the password form reuse the reasons that
 * other operations share ([forProblem]).
 */
object NativeSignInMessages {
    const val WRONG_SERVER = "wrong_server"

    /** The redirect's `iss` is missing or isn't the saved server's origin, where the app opened the native start. */
    const val ISSUER_MISMATCH = "issuer_mismatch"

    const val ACCOUNT_REQUIRED = "account_required"
    const val ACCOUNT_CHANGED = "account_changed"
    const val PERMISSION_DENIED = "permission_denied"
    const val NETWORK = "network"
    const val NO_BROWSER = "no_browser"
    const val RATE_LIMITED = "rate_limited"
    const val UPDATE_REQUIRED = org.siloserver.silo.network.apiv2.ApiV2Gate.UPDATE_REQUIRED_ERROR

    /** Problem codes whose text is the same whichever operation refused. */
    private val SHARED_PROBLEMS = setOf(
        "not_permitted",
        "email_in_use",
        "identity_linked_elsewhere",
        "account_disabled",
        "provider_unavailable",
        "already_linked",
        ACCOUNT_REQUIRED,
        PERMISSION_DENIED,
        RATE_LIMITED,
    )

    fun forReason(reason: String?, providerName: String? = null): String {
        val provider = providerLabel(providerName)
        return when (reason) {
            "not_permitted" -> "Your account at $provider isn't allowed to use this server."
            "email_in_use" -> "An account with this email already exists. Ask an admin to connect it to $provider."
            "identity_linked_elsewhere" -> "That $provider account is already connected to another account."
            "account_disabled" -> "This account is disabled."
            "provider_unavailable" -> "$provider can't be reached right now. Try again later."
            "state_invalid" -> "The sign-in didn't finish in the browser. Try again."
            "session_expired" -> "The sign-in took too long. Try again."
            "already_linked" -> "This account is already connected to $provider."
            "password_expired" -> "Your $provider password has expired. Change it, then try again."
            "login_failed" -> "Sign-in with $provider failed. Try again."
            ACCOUNT_REQUIRED -> "You don't have an account on this server yet. Ask an admin to add you."
            WRONG_SERVER, ISSUER_MISMATCH -> DIFFERENT_SERVER
            ACCOUNT_CHANGED -> "The account or server changed while you were signing in. Try again."
            PERMISSION_DENIED ->
                "This session can't change how the account signs in. Sign in with your own account and try again."
            RATE_LIMITED -> "Too many attempts. Wait a moment, then try again."
            UPDATE_REQUIRED -> "This server needs an update before this app can sign in."
            NETWORK -> "Network error. Check your connection and try again."
            NO_BROWSER -> "No browser is available to sign in with $provider."
            else -> "Sign-in with $provider failed. Try again."
        }
    }

    /** The same words as silo-apple for a redirect from another server or origin. */
    const val DIFFERENT_SERVER = "This sign-in came back from a different server. Nothing was signed in."

    /**
     * The shared text for a refusal any account operation can meet, by
     * problem code first and status second; null when the operation must
     * word it itself.
     */
    fun forProblem(error: ApiResult.Error, providerName: String? = null): String? =
        problemReason(error)?.let { forReason(it, providerName) }

    /** Whether [code] is a problem code whose text every operation shares. */
    fun isSharedProblem(code: String): Boolean = code in SHARED_PROBLEMS

    /** [forProblem]'s reason rather than its text. */
    fun problemReason(error: ApiResult.Error): String? = when {
        isSharedProblem(error.error) -> error.error
        error.code == 0 && error.error == UPDATE_REQUIRED -> UPDATE_REQUIRED
        error.code == 403 -> PERMISSION_DENIED
        error.code == 429 -> RATE_LIMITED
        error.code == 503 -> "provider_unavailable"
        else -> null
    }

    /**
     * The reason an exchange that never got an answer shows: the provider
     * couldn't re-check the session before a request that needs a fresh
     * bearer (linking, a link ticket), or the network.
     */
    fun networkReason(error: ApiResult.NetworkError): String =
        if (SiloAuthUnavailableException.isProviderUnavailable(error.exception)) "provider_unavailable" else NETWORK

    /** [networkReason]'s text. */
    fun forNetworkError(error: ApiResult.NetworkError, providerName: String? = null): String =
        forReason(networkReason(error), providerName)

    private fun providerLabel(providerName: String?): String =
        providerName?.trim()?.takeIf { it.isNotEmpty() } ?: "the sign-in provider"
}
