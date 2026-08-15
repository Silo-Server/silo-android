package org.siloserver.silo.common.player

import android.content.Context
import android.content.Intent
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PipActionCapabilityTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun missingCapabilityCannotDispatchCustomAction() {
        val customActions = listOf(
            SiloPlaybackService.ACTION_PIP_PLAY,
            SiloPlaybackService.ACTION_PIP_PAUSE,
            SiloPlaybackService.ACTION_PIP_SKIP_BACK,
            SiloPlaybackService.ACTION_PIP_SKIP_FORWARD,
        )

        customActions.forEach { action ->
            val player = RecordingPlayer()
            val intent = Intent(context, SiloPlaybackService::class.java).setAction(action)

            assertFalse(PipActionCapability.isAuthorized(intent))
            assertTrue(SiloPlaybackService.dispatchPictureInPictureAction(intent, player.proxy))
            assertTrue(
                player.calls.isEmpty(),
                "unauthenticated custom action $action must not touch the player",
            )
        }
    }

    @Test
    fun forgedCapabilityCannotDispatchCustomAction() {
        val player = RecordingPlayer()
        val issuedToken = requireNotNull(
            PipActionCapability.intent(context, SiloPlaybackService.ACTION_PIP_PLAY)
                .getStringExtra(PipActionCapability.EXTRA_CAPABILITY),
        )
        val forgedBytes = Base64.getUrlDecoder().decode(issuedToken)
        forgedBytes[0] = (forgedBytes[0].toInt() xor 1).toByte()
        val forgedToken =
            Base64.getUrlEncoder().withoutPadding().encodeToString(forgedBytes)
        val intent = Intent(context, SiloPlaybackService::class.java)
            .setAction(SiloPlaybackService.ACTION_PIP_PLAY)
            .putExtra(PipActionCapability.EXTRA_CAPABILITY, forgedToken)

        assertFalse(PipActionCapability.isAuthorized(intent))
        assertTrue(SiloPlaybackService.dispatchPictureInPictureAction(intent, player.proxy))
        assertTrue(player.calls.isEmpty(), "a forged custom action must not touch the player")
    }

    @Test
    fun malformedCapabilityCannotDispatchCustomAction() {
        val player = RecordingPlayer()
        val intent = Intent(context, SiloPlaybackService::class.java)
            .setAction(SiloPlaybackService.ACTION_PIP_PLAY)
            .putExtra(PipActionCapability.EXTRA_CAPABILITY, "not+base64url")

        assertFalse(PipActionCapability.isAuthorized(intent))
        assertTrue(SiloPlaybackService.dispatchPictureInPictureAction(intent, player.proxy))
        assertTrue(player.calls.isEmpty(), "a malformed custom action must not touch the player")
    }

    @Test
    fun capabilityIntentDispatchesCustomAction() {
        val player = RecordingPlayer()
        val intent = PipActionCapability.intent(context, SiloPlaybackService.ACTION_PIP_PLAY)

        assertTrue(PipActionCapability.isAuthorized(intent))
        assertTrue(SiloPlaybackService.dispatchPictureInPictureAction(intent, player.proxy))
        assertEquals(listOf("setPlayWhenReady", "play"), player.calls)
        assertEquals(SiloPlaybackService::class.java.name, intent.component?.className)
    }

    @Test
    fun rotationInvalidatesPreviouslyIssuedIntentAndAuthorizesNewIntent() {
        val staleIntent =
            PipActionCapability.intent(context, SiloPlaybackService.ACTION_PIP_PAUSE)

        PipActionCapability.rotate()

        val stalePlayer = RecordingPlayer()
        assertFalse(PipActionCapability.isAuthorized(staleIntent))
        assertTrue(
            SiloPlaybackService.dispatchPictureInPictureAction(staleIntent, stalePlayer.proxy),
        )
        assertTrue(stalePlayer.calls.isEmpty(), "a stale capability must not touch the player")

        val currentPlayer = RecordingPlayer()
        val currentIntent =
            PipActionCapability.intent(context, SiloPlaybackService.ACTION_PIP_PAUSE)
        assertTrue(PipActionCapability.isAuthorized(currentIntent))
        assertTrue(
            SiloPlaybackService.dispatchPictureInPictureAction(currentIntent, currentPlayer.proxy),
        )
        assertEquals(listOf("pause"), currentPlayer.calls)
    }

    @Test
    fun capabilityExtraIsBase64UrlEncodingOfThirtyTwoBytes() {
        val intent = PipActionCapability.intent(context, SiloPlaybackService.ACTION_PIP_PLAY)
        val encoded = requireNotNull(
            intent.getStringExtra(PipActionCapability.EXTRA_CAPABILITY),
        )

        assertFalse(encoded.contains('='))
        assertEquals(32, Base64.getUrlDecoder().decode(encoded).size)
    }

    @Test
    fun nonCustomServiceActionFallsThroughWithoutCapability() {
        val player = RecordingPlayer()
        val intent = Intent(context, SiloPlaybackService::class.java)
            .setAction("androidx.media3.session.CONNECT")

        assertFalse(SiloPlaybackService.dispatchPictureInPictureAction(intent, player.proxy))
        assertTrue(player.calls.isEmpty())
    }

    @Test
    fun mediaSessionAccessRemainsAvailableToSystemControllers() {
        val source = File(
            "src/androidMain/kotlin/org/siloserver/silo/common/player/SiloPlaybackService.kt",
        ).readText()

        assertTrue(
            source.contains("        return mediaSession\n    }"),
            "capability checks must not gate trusted MediaSession controllers",
        )
        assertTrue(
            source.contains(
                "if (isMediaButtonFallbackCaller(controllerInfo.connectionHints) && " +
                    "hasNothingToServeNow()) {",
            ),
            "only the synthetic media-button caller may ever be refused a session",
        )
        assertTrue(
            source.contains("return super.onStartCommand(intent, flags, startId)"),
            "non-PiP service intents must retain MediaSessionService handling",
        )
    }

    private class RecordingPlayer : InvocationHandler {
        val calls = mutableListOf<String>()
        val proxy: Player = Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java),
            this,
        ) as Player

        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            when (method.name) {
                "setPlayWhenReady", "play", "pause", "seekTo" -> calls += method.name
            }
            return when (method.returnType) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                Long::class.javaPrimitiveType -> 60_000L
                Float::class.javaPrimitiveType -> 0f
                Double::class.javaPrimitiveType -> 0.0
                else -> null
            }
        }
    }
}
