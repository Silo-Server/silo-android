package org.siloserver.silo.android.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.feature.MetadataAiFeatureStore
import org.siloserver.silo.model.feature.RequestsFeatureStore
import org.siloserver.silo.model.profile.ActiveProfileStore
import org.siloserver.silo.network.ServerRegistry
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An explicit sign-out asks the server's next provider sign-in to offer
 * another account (`prompt=select_account`): the provider's browser session
 * outlives the Silo one, and would otherwise sign the same person straight
 * back in.
 */
class SignOutTeardownTest {
    private val http = HttpClient(MockEngine { respond("", HttpStatusCode.NoContent) })

    @AfterTest fun tearDown() = http.close()

    @Test
    fun signingOutAsksTheSignedOutServerForAnAccountChoice() = runTest {
        val tokens = TokenManagerImpl()
        val coordinator = NativeSignInCoordinator(
            InMemoryPendingNativeSignInStore(),
            idle(),
            backgroundScope,
            InMemoryAccountChoiceStore(),
        )
        val registry = idle<ServerRegistry>(mapOf("getActiveServerId" to MutableStateFlow<String?>("home")))
        val teardown = SignOutTeardown(
            authRepository = AuthRepository(AuthApi(http, ApiV2Gate.Unrestricted), tokens),
            playerSettingsStore = idle(),
            libraryPlaybackPrefsStore = idle(),
            overlayPrefsStore = idle(),
            activeProfileStore = ActiveProfileStore(ProfileRepository(ProfileApi(http, ApiV2Gate.Unrestricted), tokens)),
            cardPresentationStore = idle(),
            seekIntervalStore = idle(),
            titleArtStore = idle(),
            requestsFeatureStore = RequestsFeatureStore(RequestsRepository(idle())),
            metadataAiFeatureStore = MetadataAiFeatureStore(MetadataAiRepository(idle())),
            serverRegistry = registry,
            nativeSignIn = coordinator,
        )
        assertFalse(coordinator.accountChoiceRequested("home"))

        teardown.signOut()

        assertTrue(coordinator.accountChoiceRequested("home"))
        assertFalse(coordinator.accountChoiceRequested("cabin"), "only the server signed out of")
    }

    /** Collaborators the sign-out doesn't depend on: they answer nothing and emit nothing. */
    private inline fun <reified T> idle(values: Map<String, Any> = emptyMap()): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            values[method.name] ?: if (Flow::class.java.isAssignableFrom(method.returnType)) emptyFlow<Any>() else Unit
        } as T
}
