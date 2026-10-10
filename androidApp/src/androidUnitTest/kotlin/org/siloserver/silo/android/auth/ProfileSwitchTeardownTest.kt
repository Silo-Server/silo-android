package org.siloserver.silo.android.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.common.settings.EpisodeSpoilerStore
import org.siloserver.silo.common.settings.PlayerSettingsStore
import org.siloserver.silo.common.settings.SeekIntervalStore
import org.siloserver.silo.common.settings.TitleArtStore
import org.siloserver.silo.model.profile.ActiveProfileStore
import org.siloserver.silo.network.TokenManagerImpl
import org.siloserver.silo.network.api.ProfileApi
import org.siloserver.silo.network.apiv2.ApiV2Gate
import org.siloserver.silo.repository.ProfileRepository
import java.lang.reflect.Proxy
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A settings write waits out the flusher's debounce, and the flusher drops it
 * once its profile is no longer active. The phone switch has to push pending
 * writes before it leaves the shell, as the TV switch does.
 */
class ProfileSwitchTeardownTest {
    private val http = HttpClient(MockEngine { respond("", HttpStatusCode.NoContent) })

    @AfterTest fun tearDown() = http.close()

    @Test
    fun pendingSettingsAreFlushedBeforeLeavingTheShell() = runTest {
        val events = mutableListOf<String>()
        val settings = object : PlayerSettingsStore by idle<PlayerSettingsStore>() {
            override suspend fun flushPendingDeviceSettings() {
                events += "flush"
            }
        }
        val teardown = teardown(settings, onClear = { events += "clear:$it" })

        teardown.switchProfile { events += "leave" }

        assertEquals("flush", events.first())
        assertEquals("leave", events[1], "the shell is left only after the flush")
        assertEquals(
            setOf("clear:overlays", "clear:cards", "clear:seek", "clear:titleArt", "clear:spoilers"),
            events.drop(2).toSet(),
        )
    }

    @Test
    fun aFlushThatCannotFinishDoesNotBlockTheSwitch() = runTest {
        var left = false
        val settings = object : PlayerSettingsStore by idle<PlayerSettingsStore>() {
            override suspend fun flushPendingDeviceSettings() = awaitCancellation()
        }

        teardown(settings, flushTimeoutMs = 5_000).switchProfile { left = true }

        assertEquals(true, left)
        assertEquals(5_000, currentTime)
    }

    @Test
    fun aSecondRequestWhileSwitchingIsIgnored() = runTest {
        val flushGate = CompletableDeferred<Unit>()
        val settings = object : PlayerSettingsStore by idle<PlayerSettingsStore>() {
            override suspend fun flushPendingDeviceSettings() = flushGate.await()
        }
        val teardown = teardown(settings)
        var left = 0

        val first = async { teardown.switchProfile { left++ } }
        runCurrent()
        assertTrue(teardown.switching.value, "the first switch is still pushing settings")
        // A second tap during the push must not open a second profile picker.
        assertFalse(teardown.switchProfile { left++ })

        flushGate.complete(Unit)
        assertTrue(first.await())
        assertEquals(1, left)
        assertFalse(teardown.switching.value)

        // Once finished, the next switch runs normally.
        assertTrue(teardown.switchProfile { left++ })
        assertEquals(2, left)
    }

    private fun teardown(
        settings: PlayerSettingsStore,
        onClear: (String) -> Unit = {},
        flushTimeoutMs: Long = 5_000,
    ) = ProfileSwitchTeardown(
        playerSettingsStore = settings,
        overlayPrefsStore = recordingClear("overlays", onClear),
        activeProfileStore = ActiveProfileStore(ProfileRepository(ProfileApi(http, ApiV2Gate.Unrestricted), TokenManagerImpl())),
        cardPresentationStore = recordingClear("cards", onClear),
        seekIntervalStore = recordingClear<SeekIntervalStore>("seek", onClear),
        titleArtStore = recordingClear<TitleArtStore>("titleArt", onClear),
        episodeSpoilerStore = recordingClear<EpisodeSpoilerStore>("spoilers", onClear),
        flushTimeoutMs = flushTimeoutMs,
    )

    /** A store that reports its `clear()` and otherwise answers nothing. */
    private inline fun <reified T> recordingClear(name: String, crossinline onClear: (String) -> Unit): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            if (method.name == "clear") onClear(name)
            if (Flow::class.java.isAssignableFrom(method.returnType)) emptyFlow<Any>() else Unit
        } as T

    /** Collaborators the switch doesn't depend on: they answer nothing and emit nothing. */
    private inline fun <reified T> idle(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            if (Flow::class.java.isAssignableFrom(method.returnType)) emptyFlow<Any>() else Unit
        } as T
}
