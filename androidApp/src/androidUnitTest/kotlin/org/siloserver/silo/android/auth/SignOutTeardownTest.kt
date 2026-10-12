package org.siloserver.silo.android.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.feature.MetadataAiFeatureStore
import org.siloserver.silo.model.feature.RequestsFeatureStore
import org.siloserver.silo.model.profile.ActiveProfileStore
import org.siloserver.silo.network.TokenManagerImpl
import org.siloserver.silo.network.api.AuthApi
import org.siloserver.silo.network.api.ProfileApi
import org.siloserver.silo.network.apiv2.ApiV2Gate
import org.siloserver.silo.repository.AuthRepository
import org.siloserver.silo.repository.MetadataAiRepository
import org.siloserver.silo.repository.ProfileRepository
import org.siloserver.silo.repository.RequestsRepository
import java.lang.reflect.Proxy
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignOutTeardownTest {
    private val http = HttpClient(MockEngine { respond("", HttpStatusCode.NoContent) })

    @AfterTest fun tearDown() = http.close()

    /**
     * An explicit sign-out asks the server's next provider sign-in to offer
     * another account (`prompt=select_account`): the provider's browser session
     * outlives the Silo one, and would otherwise sign the same person straight
     * back in.
     */
    @Test
    fun signingOutAsksTheSignedOutServerForAnAccountChoice() = runTest {
        val coordinator = nativeSignIn()
        assertFalse(coordinator.accountChoiceRequested("home"))

        teardown(TokenManagerImpl(), coordinator).signOut()

        assertTrue(coordinator.accountChoiceRequested("home"))
        assertFalse(coordinator.accountChoiceRequested("cabin"), "only the server signed out of")
    }

    @Test
    fun theServersPushRegistrationIsRemovedWhileItsCredentialsStillExist() = runTest {
        val tokens = TokenManagerImpl().apply { saveTokens("access", "refresh", 3600) }
        val removedWith = mutableMapOf<String, String?>()

        teardown(tokens, nativeSignIn(), unregisterPush = { removedWith[it] = tokens.getAccessToken() }).signOut()

        assertEquals<Map<String, String?>>(mapOf("home" to "access"), removedWith)
        assertNull(tokens.getAccessToken())
    }

    @Test
    fun switchingServersWhileThePushRemovalWaitsSignsNobodyOut() = runTest {
        val tokens = TokenManagerImpl().apply { saveTokens("access", "refresh", 3600) }
        val active = MutableStateFlow<String?>("home")

        val signedOut = teardown(tokens, nativeSignIn(), active, unregisterPush = { active.value = "cabin" }).signOut()

        assertFalse(signedOut)
        assertEquals("access", tokens.getAccessToken(), "cabin, active by then, keeps its account")
    }

    @Test
    fun aPushRemovalThatFailsStillSignsOutButCancellationSignsNobodyOut() = runTest {
        val tokens = TokenManagerImpl().apply { saveTokens("access", "refresh", 3600) }

        assertFailsWith<CancellationException> {
            teardown(tokens, nativeSignIn(), unregisterPush = { throw CancellationException("left") }).signOut()
        }
        assertEquals("access", tokens.getAccessToken())

        assertTrue(teardown(tokens, nativeSignIn(), unregisterPush = { error("unreadable push records") }).signOut())
        assertNull(tokens.getAccessToken())
    }

    private fun TestScope.nativeSignIn() = NativeSignInCoordinator(
        InMemoryPendingNativeSignInStore(),
        idle(),
        backgroundScope,
        InMemoryAccountChoiceStore(),
    )

    private fun teardown(
        tokens: TokenManagerImpl,
        coordinator: NativeSignInCoordinator,
        activeServerId: MutableStateFlow<String?> = MutableStateFlow("home"),
        unregisterPush: suspend (String) -> Unit = {},
    ) = SignOutTeardown(
        authRepository = AuthRepository(AuthApi(http, ApiV2Gate.Unrestricted), tokens),
        playerSettingsStore = idle(),
        libraryPlaybackPrefsStore = idle(),
        overlayPrefsStore = idle(),
        activeProfileStore = ActiveProfileStore(ProfileRepository(ProfileApi(http, ApiV2Gate.Unrestricted), tokens)),
        cardPresentationStore = idle(),
        seekIntervalStore = idle(),
        titleArtStore = idle(),
        episodeSpoilerStore = idle(),
        requestsFeatureStore = RequestsFeatureStore(RequestsRepository(idle())),
        metadataAiFeatureStore = MetadataAiFeatureStore(MetadataAiRepository(idle())),
        serverRegistry = idle(mapOf("getActiveServerId" to activeServerId)),
        nativeSignIn = coordinator,
        unregisterPush = unregisterPush,
    )

    /** Collaborators the sign-out doesn't depend on: they answer nothing and emit nothing. */
    private inline fun <reified T> idle(values: Map<String, Any> = emptyMap()): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            values[method.name] ?: if (Flow::class.java.isAssignableFrom(method.returnType)) emptyFlow<Any>() else Unit
        } as T
}
