package org.siloserver.silo.android.auth

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.auth.User
import org.siloserver.silo.network.ApiResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeSignInProtocolTest {
    @Test
    fun challengeIsRfc7636S256() {
        // RFC 7636 Appendix B.
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            NativeSignInProtocol.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun verifierAndStateAreFreshUnreservedTokens() {
        val unreserved = Regex("^[A-Za-z0-9._~-]+$")
        val verifiers = List(20) { NativeSignInProtocol.newCodeVerifier() }
        verifiers.forEach {
            assertEquals(43, it.length)
            assertTrue(unreserved.matches(it), it)
        }
        assertEquals(verifiers.size, verifiers.toSet().size)
        val state = NativeSignInProtocol.newAppState()
        assertTrue(unreserved.matches(state) && state.length in 1..512)
        assertEquals(43, NativeSignInProtocol.challenge(verifiers.first()).length)
    }

    @Test
    fun startUrlAddsThePkceAndStateParameters() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertEquals(
            "https://silo.example.test/api/v2/auth/oauth/5/native/start" +
                "?code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM&code_challenge_method=S256&app_state=abc",
            NativeSignInProtocol.startUrl("https://silo.example.test/", "/api/v2/auth/oauth/5/native/start", "abc", verifier),
        )
        val linking = NativeSignInProtocol.startUrl("https://h/silo", "/api/v2/auth/oauth/5/native/start?x=1#frag", "s", verifier, linkTicket = "t k")
        assertEquals(
            "https://h/silo/api/v2/auth/oauth/5/native/start?x=1&code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM" +
                "&code_challenge_method=S256&app_state=s&link_ticket=t+k",
            linking,
        )
        assertFalse(assertNotNull(linking).contains(verifier), "the verifier itself never leaves the app")
    }

    /** The start opens on the saved base and nowhere else: a path that names another host opens nothing. */
    @Test
    fun startUrlNeverLeavesTheSavedBase() {
        assertNull(NativeSignInProtocol.startUrl("https://silo.example.test", "//evil.example.test/x", "s", "v"))
        assertNull(NativeSignInProtocol.startUrl("https://silo.example.test", "https://evil.example.test/x", "s", "v"))
        assertNull(NativeSignInProtocol.startUrl("not a url", "/api/v2/auth/oauth/5/native/start", "s", "v"))
        assertEquals(
            "http://192.168.1.10:8096",
            NativeSignInProtocol.origin(NativeSignInProtocol.startUrl("http://192.168.1.10:8096", "/api/v2/auth/oauth/5/native/start", "s", "v")),
        )
    }

    @Test
    fun startUrlCarriesAPrompt() {
        val url = assertNotNull(
            NativeSignInProtocol.startUrl("https://h", "/start", "s", "v", prompt = NativeSignInProtocol.PROMPT_SELECT_ACCOUNT),
        )
        assertTrue(url.contains("&prompt=select_account"), url)
        assertFalse(assertNotNull(NativeSignInProtocol.startUrl("https://h", "/start", "s", "v")).contains("prompt="))
    }

    @Test
    fun originsCompareSchemeHostAndEffectivePort() {
        assertEquals("https://silo.example.test", NativeSignInProtocol.origin("https://Silo.Example.test:443/api/v2/x?y=1"))
        assertEquals("http://10.0.0.2:8096", NativeSignInProtocol.origin("http://10.0.0.2:8096/"))
        assertEquals("http://silo.lan", NativeSignInProtocol.origin("http://silo.lan:80"))
        assertNotEquals(NativeSignInProtocol.origin("https://silo.example.test"), NativeSignInProtocol.origin("http://silo.example.test"))
        assertNotEquals(NativeSignInProtocol.origin("https://silo.example.test"), NativeSignInProtocol.origin("https://silo.example.test:8443"))
        listOf(null, "", "silo.example.test", "ftp://silo.example.test", "https://user@silo.example.test", "https:///x", "ws://silo.lan")
            .forEach { assertNull(NativeSignInProtocol.origin(it), "$it") }
    }

    /**
     * The same canonical origin as the rest of the app, which accepts an
     * underscore host that `java.net.URI` reads as having none. (Hosts a
     * browser rewrites differ; see the next test.)
     */
    @Test
    fun originsAgreeWithTheAppsCanonicalOrigin() {
        assertEquals("http://silo_server:8096", NativeSignInProtocol.origin("http://silo_server:8096/api/v2/auth/oauth/5/native/start"))
        listOf("http://silo_server:8096", "https://[fd00::1]:8920/x", "https://Silo.Example.test:443").forEach {
            assertEquals(org.siloserver.silo.network.canonicalHttpOrigin(it), NativeSignInProtocol.origin(it), it)
        }
    }

    /**
     * The server names in `iss` the `Host` the browser sent, which a browser
     * writes in punycode and with a compressed IPv6 literal. A base saved in
     * Unicode or with an uncompressed literal compares in that same form.
     */
    @Test
    fun originsWriteTheHostTheWayABrowserSendsIt() {
        assertEquals("https://xn--bcher-kva.example", NativeSignInProtocol.origin("https://bücher.example/silo"))
        assertEquals(NativeSignInProtocol.origin("https://xn--bcher-kva.example"), NativeSignInProtocol.origin("https://BÜCHER.example"))
        assertEquals("http://[fd00::1]:8096", NativeSignInProtocol.origin("http://[fd00:0:0:0:0:0:0:1]:8096"))
        assertEquals("http://[fd00::1]:8096", NativeSignInProtocol.origin("http://[FD00:0000::0001]:8096/"))
        assertEquals("https://[2001:db8::1:0:0:1]", NativeSignInProtocol.origin("https://[2001:db8:0:0:1:0:0:1]:443"))
        assertEquals("http://[2001:db8:0:1:1:1:1:1]", NativeSignInProtocol.origin("http://[2001:db8:0:1:1:1:1:1]"))
        assertEquals("http://[::]", NativeSignInProtocol.origin("http://[0:0:0:0:0:0:0:0]"))
        assertTrue(NativeSignInCoordinator.issuedBy("https://xn--bcher-kva.example", NativeSignInProtocol.origin("https://bücher.example")!!))
        assertTrue(NativeSignInCoordinator.issuedBy("http://[fd00::1]:8096", NativeSignInProtocol.origin("http://[fd00:0:0:0:0:0:0:1]:8096")!!))
    }
}

class NativeSignInCallbackTest {
    @Test
    fun parsesOnlyTheExactAppRedirect() {
        val parsed = assertNotNull(
            NativeSignInCallback.parse("org.siloserver.silo:/auth/callback?code=c%2B1&state=s&server=srv-1"),
        )
        assertEquals("c+1", parsed.code)
        assertEquals("s", parsed.state)
        assertEquals("srv-1", parsed.server)
        assertNull(parsed.error)
        assertFalse(parsed.link)
        assertNull(parsed.iss)
        assertEquals(
            "https://silo.example.test",
            assertNotNull(NativeSignInCallback.parse("org.siloserver.silo:/auth/callback?code=c&iss=https%3A%2F%2Fsilo.example.test")).iss,
        )
        // A raw iss (some servers don't encode the colon and slashes) parses the same.
        assertEquals(
            "https://silo.example.test:8443",
            assertNotNull(NativeSignInCallback.parse("org.siloserver.silo:/auth/callback?code=c&iss=https://silo.example.test:8443")).iss,
        )

        val error = assertNotNull(NativeSignInCallback.parse("org.siloserver.silo:/auth/callback?error=not_permitted&state=s&server=x"))
        assertEquals("not_permitted", error.error)
        assertNull(error.code)
        assertTrue(assertNotNull(NativeSignInCallback.parse("org.siloserver.silo:/auth/callback?code=c&link=1")).link)

        listOf(
            null,
            "",
            "org.siloserver.silo:/auth/other?code=c",
            "org.siloserver.silo:/auth/callback/extra?code=c",
            "org.siloserver.silo://auth/callback?code=c",
            "org.siloserver.silo://evil.example/auth/callback?code=c",
            "org.siloserver.silo:auth/callback?code=c",
            "org.siloserver.silo:/auth/callback",
            "silo:/auth/callback?code=c",
            "https://silo.example.test/auth/callback?code=c",
        ).forEach { assertNull(NativeSignInCallback.parse(it), "$it") }
    }

    @Test
    fun aRepeatedParameterKeepsTheFirstValue() {
        val parsed = assertNotNull(NativeSignInCallback.parse("org.siloserver.silo:/auth/callback?state=a&state=b&code=c"))
        assertEquals("a", parsed.state)
    }

    @Test
    fun secretsStayOutOfLogs() {
        val parsed = assertNotNull(NativeSignInCallback.parse("org.siloserver.silo:/auth/callback?code=SECRET&state=STATE&server=x"))
        assertFalse(parsed.toString().contains("SECRET"))
        assertFalse(parsed.toString().contains("STATE"))
        val pending = pending(NativeSignInPurpose.SignIn)
        assertFalse(pending.toString().contains(pending.codeVerifier))
        assertFalse(pending.toString().contains(pending.appState))
    }
}

class NativeSignInManifestTest {
    @Test
    fun phoneReceivesTheAppRedirectWithoutAutoVerify() {
        val manifest = File("src/androidMain/AndroidManifest.xml").readText()
        val activity = manifest.substringAfter("""android:name=".auth.NativeSignInCallbackActivity"""").substringBefore("</activity>")
        assertTrue(activity.contains("""android:exported="true""""))
        assertTrue(activity.contains("""<data android:scheme="org.siloserver.silo" />"""))
        assertTrue(activity.contains("android.intent.category.BROWSABLE"))
        assertFalse(activity.contains("autoVerify"))
    }

    @Test
    fun tvNeverClaimsTheAppRedirect() {
        val tv = File("../androidTvApp/src/androidMain/AndroidManifest.xml").readText()
        assertFalse(tv.contains("org.siloserver.silo\""), "TVs never run a provider sign-in")
    }
}

class NativeSignInCoordinatorTest {
    private var now = 1_000_000L

    private class FakeCompleter : NativeSignInCompleter {
        val signIns = mutableListOf<Pair<String, String>>()
        val links = mutableListOf<Pair<String, String>>()
        var signInResult: ApiResult<User> = ApiResult.Success(User("1", "alice", "a@example.test", "user"))
        var linkResult: ApiResult<Unit> = ApiResult.Success(Unit)
        val startOrigins = mutableListOf<String>()
        override suspend fun signIn(pending: PendingNativeSignIn, code: String): ApiResult<User> {
            signIns += code to pending.codeVerifier
            startOrigins += pending.startOrigin
            return signInResult
        }
        override suspend fun link(pending: PendingNativeSignIn, code: String): ApiResult<Unit> {
            links += code to pending.codeVerifier
            return linkResult
        }
    }

    private fun coordinator(
        store: PendingNativeSignInStore,
        completer: NativeSignInCompleter,
        scope: CoroutineScope,
    ) = NativeSignInCoordinator(store, completer, scope, InMemoryAccountChoiceStore(), clock = { now })

    private fun callback(
        state: String?,
        code: String? = "code-1",
        server: String? = "srv-1",
        error: String? = null,
        link: Boolean = false,
        iss: String? = "https://silo.example.test",
    ) = NativeSignInCallback(code = code, state = state, server = server, error = error, link = link, iss = iss)

    private suspend fun NativeSignInCoordinator.start(
        purpose: NativeSignInPurpose = NativeSignInPurpose.SignIn,
        prompt: String? = null,
    ): String {
        val url = assertIs<NativeSignInStart.Open>(
            begin(
                purpose = purpose,
                serverEntryId = "entry-1",
                serverUrl = "https://silo.example.test/",
                verifiedServerId = "srv-1",
                providerName = "Keycloak",
                nativeStartPath = START_PATH,
                linkTicket = if (purpose == NativeSignInPurpose.Link) "ticket" else null,
                prompt = prompt,
            ),
        ).url
        assertTrue(url.contains("code_challenge_method=S256"))
        lastStartUrl = url
        return url.substringAfter("app_state=").substringBefore('&')
    }

    private var lastStartUrl = ""

    /**
     * The relay: a hostile saved server's native start (on its own origin)
     * redirects the browser to the real server's native start with this app's
     * challenge and `app_state`. The real server's redirect carries a valid
     * code and `state`, and `server` matches because the hostile server
     * echoed the real id, but `iss` names where the real server's native
     * start arrived, its own origin, not the saved one. Nothing is redeemed
     * anywhere.
     */
    @Test
    fun aRelayedSignInComesBackWithAnotherIssuerAndIsDiscarded() = runTest {
        val store = InMemoryPendingNativeSignInStore()
        val completer = FakeCompleter()
        val coordinator = coordinator(store, completer, this)
        val start = assertIs<NativeSignInStart.Open>(
            coordinator.begin(
                purpose = NativeSignInPurpose.SignIn,
                serverEntryId = "hostile-entry",
                serverUrl = "https://hostile.example.test",
                verifiedServerId = "srv-real",
                providerName = "Keycloak",
                nativeStartPath = START_PATH,
            ),
        )
        val state = start.url.substringAfter("app_state=").substringBefore('&')
        assertTrue(coordinator.finish(callback(state = state, server = "srv-real", iss = "https://real.example.test")))
        val failed = assertIs<NativeSignInResult.Failed>(coordinator.result.value)
        assertEquals(NativeSignInMessages.ISSUER_MISMATCH, failed.reason)
        assertEquals("This sign-in came back from a different server. Nothing was signed in.", failed.message)
        assertTrue(completer.signIns.isEmpty() && completer.links.isEmpty())
        assertNull(store.load(), "the flow is spent")
    }

    /** A sign-out while the code is being redeemed: the superseded flow reports nothing, not even Finishing. */
    @Test
    fun aFlowDiscardedDuringRedemptionPublishesNothing() = runTest {
        lateinit var coordinator: NativeSignInCoordinator
        val completer = object : NativeSignInCompleter {
            override suspend fun signIn(pending: PendingNativeSignIn, code: String): ApiResult<User> {
                coordinator.discardPending()
                return ApiResult.Error(0, "identity_changed", "The account or server changed.")
            }
            override suspend fun link(pending: PendingNativeSignIn, code: String): ApiResult<Unit> = ApiResult.Success(Unit)
        }
        coordinator = coordinator(InMemoryPendingNativeSignInStore(), completer, this)

        assertTrue(coordinator.finish(callback(state = coordinator.start())))

        assertNull(coordinator.result.value)
    }

    @Test
    fun aRedirectWithoutAnIssuerIsDiscarded() = runTest {
        val completer = FakeCompleter()
        val coordinator = coordinator(InMemoryPendingNativeSignInStore(), completer, this)
        assertTrue(coordinator.finish(callback(state = coordinator.start(), iss = null)))
        assertEquals(NativeSignInMessages.ISSUER_MISMATCH, assertIs<NativeSignInResult.Failed>(coordinator.result.value).reason)
        // An error redirect needs it too.
        assertTrue(coordinator.finish(callback(state = coordinator.start(), code = null, error = "not_permitted", iss = null)))
        assertEquals(NativeSignInMessages.ISSUER_MISMATCH, assertIs<NativeSignInResult.Failed>(coordinator.result.value).reason)
        assertTrue(completer.signIns.isEmpty())
    }

    /**
     * A server saved by its LAN address signs in there: the native start
     * opens on the LAN base, the server answers with the LAN origin as `iss`, and the
     * code is redeemed for the LAN entry. The public origin as `iss` is
     * another origin than the one opened, so it is refused.
     */
    @Test
    fun aLanSavedServerSignsInOnItsLanAddress() = runTest {
        val store = InMemoryPendingNativeSignInStore()
        val completer = FakeCompleter()
        val coordinator = coordinator(store, completer, this)
        suspend fun begin() = assertIs<NativeSignInStart.Open>(
            coordinator.begin(NativeSignInPurpose.SignIn, "lan", "http://192.168.1.10:8096", "srv-1", "Keycloak", START_PATH),
        ).url
        val url = begin()
        assertTrue(url.startsWith("http://192.168.1.10:8096/api/v2/auth/oauth/5/native/start?"), url)
        assertEquals("http://192.168.1.10:8096", store.load()?.startOrigin)
        assertTrue(coordinator.finish(callback(state = url.substringAfter("app_state=").substringBefore('&'), iss = "https://silo.example.test")))
        assertEquals(NativeSignInMessages.ISSUER_MISMATCH, assertIs<NativeSignInResult.Failed>(coordinator.result.value).reason)
        assertTrue(completer.startOrigins.isEmpty())

        val again = begin().substringAfter("app_state=").substringBefore('&')
        assertTrue(coordinator.finish(callback(state = again, iss = "http://192.168.1.10:8096")))
        assertEquals("lan", assertIs<NativeSignInResult.SignedIn>(coordinator.result.value).serverEntryId)
        assertEquals(listOf("http://192.168.1.10:8096"), completer.startOrigins)
    }

    /** A path the app can't place on the saved base starts nothing. */
    @Test
    fun aStartThatCannotOpenOnTheSavedBaseIsRefused() = runTest {
        val store = InMemoryPendingNativeSignInStore()
        val coordinator = coordinator(store, FakeCompleter(), this)
        assertIs<NativeSignInStart.Refused>(
            coordinator.begin(NativeSignInPurpose.SignIn, "pub", "https://silo.example.test", "srv-1", "Keycloak", "//evil.example.test/x"),
        )
        assertIs<NativeSignInStart.Refused>(
            coordinator.begin(NativeSignInPurpose.SignIn, "pub", "not a url", "srv-1", "Keycloak", START_PATH),
        )
        assertNull(store.load())
    }

    @Test
    fun anIssuerOtherThanTheOpenedOriginIsRefusedWithoutRedeeming() = runTest {
        val completer = FakeCompleter()
        val coordinator = coordinator(InMemoryPendingNativeSignInStore(), completer, this)
        assertTrue(coordinator.finish(callback(state = coordinator.start(), iss = "https://real.example.test")))
        assertEquals(NativeSignInMessages.ISSUER_MISMATCH, assertIs<NativeSignInResult.Failed>(coordinator.result.value).reason)
        assertTrue(completer.signIns.isEmpty())

        assertTrue(coordinator.finish(callback(state = coordinator.start(), iss = "https://SILO.example.test:443")))
        assertIs<NativeSignInResult.SignedIn>(coordinator.result.value)
        assertEquals(1, completer.signIns.size)
    }

    @Test
    fun switchAccountAsksForAnotherAccountOnSignInOnly() = runTest {
        val coordinator = coordinator(InMemoryPendingNativeSignInStore(), FakeCompleter(), this)
        assertFalse(coordinator.accountChoiceRequested("entry-1"))
        coordinator.requestAccountChoice("entry-1")
        assertTrue(coordinator.accountChoiceRequested("entry-1"))
        assertFalse(coordinator.accountChoiceRequested("entry-2"))

        coordinator.start(NativeSignInPurpose.SignIn, prompt = NativeSignInProtocol.PROMPT_SELECT_ACCOUNT)
        assertTrue(lastStartUrl.contains("prompt=select_account"), lastStartUrl)
        coordinator.start(NativeSignInPurpose.Link, prompt = NativeSignInProtocol.PROMPT_SELECT_ACCOUNT)
        assertFalse(lastStartUrl.contains("prompt="), "a linking flow never asks to pick an account")

        // Signing in ends it.
        assertTrue(coordinator.finish(callback(state = coordinator.start())))
        assertIs<NativeSignInResult.SignedIn>(coordinator.result.value)
        assertFalse(coordinator.accountChoiceRequested("entry-1"))
    }

    @Test
    fun aCallbackWithNoPendingFlowIsIgnored() = runTest {
        val completer = FakeCompleter()
        val coordinator = coordinator(InMemoryPendingNativeSignInStore(), completer, this)
        assertFalse(coordinator.finish(callback(state = "anything")))
        assertNull(coordinator.result.value)
        assertTrue(completer.signIns.isEmpty())
    }

    @Test
    fun aForeignStateIsIgnoredAndTheRealRedirectStillFinishes() = runTest {
        val store = InMemoryPendingNativeSignInStore()
        val completer = FakeCompleter()
        val coordinator = coordinator(store, completer, this)
        val state = coordinator.start()
        assertFalse(coordinator.finish(callback(state = "hijacker")))
        assertFalse(coordinator.finish(callback(state = null)))
        assertNotNull(store.load(), "the pending flow waits for the real redirect")
        assertNull(coordinator.result.value)

        assertTrue(coordinator.finish(callback(state = state)))
        assertIs<NativeSignInResult.SignedIn>(coordinator.result.value)
        assertEquals("code-1", completer.signIns.single().first)
        assertEquals(43, completer.signIns.single().second.length)
        assertNull(store.load(), "the flow is spent")
        assertFalse(coordinator.finish(callback(state = state)), "a replayed redirect finds no flow")
        assertEquals(1, completer.signIns.size)
    }

    @Test
    fun anExpiredFlowIsDropped() = runTest {
        val store = InMemoryPendingNativeSignInStore()
        val coordinator = coordinator(store, FakeCompleter(), this)
        val state = coordinator.start()
        now += NativeSignInProtocol.FLOW_LIFETIME_MS + 1
        assertFalse(coordinator.finish(callback(state = state)))
        assertNull(store.load())
    }

    @Test
    fun anotherServerIsRefusedWithoutRedeemingTheCode() = runTest {
        val completer = FakeCompleter()
        val coordinator = coordinator(InMemoryPendingNativeSignInStore(), completer, this)
        val state = coordinator.start()
        assertTrue(coordinator.finish(callback(state = state, server = "srv-other")))
        val failed = assertIs<NativeSignInResult.Failed>(coordinator.result.value)
        assertEquals(NativeSignInMessages.WRONG_SERVER, failed.reason)
        assertTrue(completer.signIns.isEmpty())

        val again = coordinator.start()
        assertTrue(coordinator.finish(callback(state = again, server = null)))
        assertEquals(NativeSignInMessages.WRONG_SERVER, assertIs<NativeSignInResult.Failed>(coordinator.result.value).reason)
    }

    @Test
    fun serverReasonsBecomeReadableFailures() = runTest {
        val coordinator = coordinator(InMemoryPendingNativeSignInStore(), FakeCompleter(), this)
        for (reason in listOf("not_permitted", "email_in_use", "identity_linked_elsewhere", "account_disabled",
            "provider_unavailable", "state_invalid", "session_expired", "already_linked", "login_failed", "account_required",
            "password_expired")) {
            val state = coordinator.start()
            assertTrue(coordinator.finish(callback(state = state, code = null, error = reason)))
            val failed = assertIs<NativeSignInResult.Failed>(coordinator.result.value)
            assertEquals(reason, failed.reason)
            assertEquals(NativeSignInMessages.forReason(reason, "Keycloak"), failed.message)
            coordinator.consume(failed)
            assertNull(coordinator.result.value)
        }
    }

    @Test
    fun aRedirectForTheOtherPurposeIsRefused() = runTest {
        val completer = FakeCompleter()
        val coordinator = coordinator(InMemoryPendingNativeSignInStore(), completer, this)
        val state = coordinator.start(NativeSignInPurpose.SignIn)
        assertTrue(coordinator.finish(callback(state = state, link = true)))
        assertEquals("login_failed", assertIs<NativeSignInResult.Failed>(coordinator.result.value).reason)
        assertTrue(completer.signIns.isEmpty() && completer.links.isEmpty())
    }

    @Test
    fun linkingConfirmsTheCodeWithTheVerifier() = runTest {
        val completer = FakeCompleter()
        val coordinator = coordinator(InMemoryPendingNativeSignInStore(), completer, this)
        val state = coordinator.start(NativeSignInPurpose.Link)
        assertTrue(coordinator.finish(callback(state = state, link = true)))
        assertEquals("Keycloak", assertIs<NativeSignInResult.Linked>(coordinator.result.value).providerName)
        assertEquals("code-1", completer.links.single().first)

        completer.linkResult = ApiResult.Error(409, "identity_linked_elsewhere", "")
        val second = coordinator.start(NativeSignInPurpose.Link)
        coordinator.finish(callback(state = second, link = true))
        assertEquals("identity_linked_elsewhere", assertIs<NativeSignInResult.Failed>(coordinator.result.value).reason)
    }

    @Test
    fun redemptionFailuresMapToReasons() = runTest {
        val completer = FakeCompleter()
        val coordinator = coordinator(InMemoryPendingNativeSignInStore(), completer, this)
        completer.signInResult = ApiResult.NetworkError(RuntimeException("offline"))
        coordinator.finish(callback(state = coordinator.start()))
        assertEquals(NativeSignInMessages.NETWORK, assertIs<NativeSignInResult.Failed>(coordinator.result.value).reason)

        // The provider couldn't re-check the session before a fresh-bearer request.
        completer.signInResult = ApiResult.NetworkError(
            org.siloserver.silo.network.SiloAuthUnavailableException(
                org.siloserver.silo.network.SiloAuthUnavailableException.PROVIDER_UNAVAILABLE,
            ),
        )
        coordinator.finish(callback(state = coordinator.start()))
        assertEquals("provider_unavailable", assertIs<NativeSignInResult.Failed>(coordinator.result.value).reason)

        val table = mapOf(
            ApiResult.Error(400, "invalid_grant", "") to "state_invalid",
            ApiResult.Error(401, "invalid_token", "") to "session_expired",
            ApiResult.Error(403, "not_permitted", "") to "not_permitted",
            ApiResult.Error(403, "permission_denied", "") to NativeSignInMessages.PERMISSION_DENIED,
            ApiResult.Error(403, "account_disabled", "") to "account_disabled",
            ApiResult.Error(409, "conflict", "") to "already_linked",
            ApiResult.Error(409, "identity_linked_elsewhere", "") to "identity_linked_elsewhere",
            ApiResult.Error(409, "email_in_use", "") to "email_in_use",
            ApiResult.Error(409, "already_linked", "") to "already_linked",
            ApiResult.Error(403, "account_required", "") to NativeSignInMessages.ACCOUNT_REQUIRED,
            ApiResult.Error(503, "", "") to "provider_unavailable",
            ApiResult.Error(0, "identity_changed", "") to NativeSignInMessages.ACCOUNT_CHANGED,
            ApiResult.Error(500, "internal", "") to "login_failed",
            ApiResult.Error(400, "password_expired", "") to "password_expired",
            ApiResult.Error(429, "rate_limited", "") to NativeSignInMessages.RATE_LIMITED,
            ApiResult.Error(429, "", "") to NativeSignInMessages.RATE_LIMITED,
            ApiResult.Error(403, "", "") to "not_permitted",
            ApiResult.Error(0, NativeSignInMessages.UPDATE_REQUIRED, "") to NativeSignInMessages.UPDATE_REQUIRED,
        )
        table.forEach { (error, reason) -> assertEquals(reason, NativeSignInCoordinator.completionReason(error), "$error") }
    }

    @Test
    fun everyReasonHasItsOwnText() {
        val generic = NativeSignInMessages.forReason("something_new", "Keycloak")
        listOf("not_permitted", "email_in_use", "identity_linked_elsewhere", "account_disabled", "provider_unavailable",
            "state_invalid", "session_expired", "already_linked", "password_expired", NativeSignInMessages.WRONG_SERVER,
            NativeSignInMessages.ISSUER_MISMATCH,
            NativeSignInMessages.ACCOUNT_REQUIRED, NativeSignInMessages.ACCOUNT_CHANGED, NativeSignInMessages.PERMISSION_DENIED,
            NativeSignInMessages.NETWORK, NativeSignInMessages.NO_BROWSER, NativeSignInMessages.RATE_LIMITED,
            NativeSignInMessages.UPDATE_REQUIRED).forEach {
            assertNotEquals(generic, NativeSignInMessages.forReason(it, "Keycloak"), it)
            assertFalse(NativeSignInMessages.forReason(it, "Keycloak").contains(it) && it.contains('_'), "no raw code in $it")
        }
    }
}

internal const val START_PATH = "/api/v2/auth/oauth/5/native/start"

internal fun pending(purpose: NativeSignInPurpose) = PendingNativeSignIn(
    purpose = purpose,
    serverEntryId = "entry-1",
    verifiedServerId = "srv-1",
    startOrigin = "https://silo.example.test",
    appState = NativeSignInProtocol.newAppState(),
    codeVerifier = NativeSignInProtocol.newCodeVerifier(),
    providerName = "Keycloak",
    startedAtEpochMs = 0L,
)

/** The nearby-TV confirmation uses the same content model as silo-apple's CompanionPairingCard. */
class CompanionConfirmCopyTest {
    @Test
    fun confirmationCopyMatchesTheIphoneApp() {
        val strings = File("src/androidMain/res/values/strings.xml").readText()
        listOf(
            "companion_confirm_yes\">Yes, this matches<",
            "sign_in_tv_profiles_note\">Anyone using this TV can pick from your profiles. Profiles with a PIN stay locked.<",
            "sign_in_tv_warning\">Only approve a TV that\\'s in front of you right now.<",
            "sign_in_tv_server_account\">You\\'ll sign it in to %1\$s as %2\$s.<",
            "companion_offer_signin_body\">A TV nearby named “%1\$s” says it\\'s on its sign-in screen. You\\'ll check its code before approving.<",
            "companion_confirm_older_tv\">Older TV apps show %1\$s instead.<",
        ).forEach { assertTrue(strings.contains(it), it) }
    }
}
