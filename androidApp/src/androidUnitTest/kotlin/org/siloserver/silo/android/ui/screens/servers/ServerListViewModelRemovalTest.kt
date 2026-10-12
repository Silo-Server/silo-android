package org.siloserver.silo.android.ui.screens.servers

import android.app.Application
import android.content.Context
import androidx.lifecycle.viewModelScope
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.siloserver.silo.android.auth.ActiveProfileReset
import org.siloserver.silo.common.profiles.ProfileLaunchPreferences
import org.siloserver.silo.model.profile.ActiveProfileStore
import org.siloserver.silo.model.server.ServerEntry
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.network.TokenManagerImpl
import org.siloserver.silo.network.api.AuthApi
import org.siloserver.silo.network.api.ProfileApi
import org.siloserver.silo.network.apiv2.ApiV2Gate
import org.siloserver.silo.repository.AuthRepository
import org.siloserver.silo.repository.ProfileRepository
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Removing a saved server removes it even when removing its push registration fails. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ServerListViewModelRemovalTest {
    private val http = HttpClient(MockEngine { respond("", HttpStatusCode.NoContent) })
    private val removed = mutableListOf<String>()

    @AfterTest fun tearDown() = http.close()

    @Test
    fun `a push removal that fails still removes the server`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = viewModel(unregisterPush = { error("unreadable push records") })
        try {
            viewModel.onRemove("cabin")

            assertEquals(listOf("cabin"), removed)
        } finally {
            viewModel.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }

    private fun viewModel(unregisterPush: suspend (String) -> Unit): ServerListViewModel {
        val tokens = TokenManagerImpl()
        val profiles = ProfileRepository(ProfileApi(http, ApiV2Gate.Unrestricted), tokens)
        val launchPreferences = RuntimeEnvironment.getApplication().getSharedPreferences("launch", Context.MODE_PRIVATE)
        return ServerListViewModel(
            serverRegistry = registry(),
            tokenManager = tokens,
            authRepository = AuthRepository(AuthApi(http, ApiV2Gate.Unrestricted), tokens),
            activeProfileReset = ActiveProfileReset(
                profiles, idle(), ProfileLaunchPreferences(launchPreferences), idle(),
                ActiveProfileStore(profiles), idle(), idle(), idle(), idle(),
            ),
            unregisterPush = unregisterPush,
        )
    }

    /** "home" is active and "cabin" saved; records what is removed. */
    private fun registry(): ServerRegistry {
        val home = ServerEntry(id = "home", url = "https://home.example")
        val entries = MutableStateFlow(listOf(home, ServerEntry(id = "cabin", url = "https://cabin.example")))
        val active = MutableStateFlow<String?>(home.id)
        return Proxy.newProxyInstance(ServerRegistry::class.java.classLoader, arrayOf(ServerRegistry::class.java)) { _, method, args ->
            when (method.name) {
                "getEntries" -> entries
                "getActiveServerId" -> active
                "getActiveEntry" -> MutableStateFlow(home)
                "remove" -> {
                    removed += args[0] as String
                    Unit
                }
                else -> error("Unexpected ${method.name}")
            }
        } as ServerRegistry
    }

    /** Collaborators the removal of an inactive server never touches. */
    private inline fun <reified T> idle(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            if (Flow::class.java.isAssignableFrom(method.returnType)) emptyFlow<Any>() else Unit
        } as T
}
