package org.siloserver.silo.common.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.siloserver.silo.network.AndroidServerRegistry
import org.siloserver.silo.network.DefaultIdentityTransitionBarrier
import org.siloserver.silo.network.EncryptedTokenManagerImpl
import org.siloserver.silo.network.IdentityTransition
import org.siloserver.silo.network.IdentityTransitionKind
import org.siloserver.silo.network.IdentityTransitionPhase
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsIdentityTransitionAndroidTest {
    @Test
    fun serverSwitchAndRemovalCloseGateBeforeRegistryMutation() = runTest {
        val prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("diagnostics-transition-${System.nanoTime()}", Context.MODE_PRIVATE)
        val barrier = DefaultIdentityTransitionBarrier()
        val registry = AndroidServerRegistry(prefs, barrier)
        val first = registry.addOrUpdate("https://first.example")
        val second = registry.addOrUpdate("https://second.example")
        registry.switchTo(first)
        val timeline = mutableListOf<String>()
        barrier.installGate { transition ->
            timeline += "${transition.kind}:${registry.activeServerId.value}"
        }

        registry.switchTo(second)
        registry.remove(second)

        assertEquals(
            listOf(
                "SERVER_SWITCH:$first",
                "SERVER_REMOVE:$second",
            ),
            timeline,
        )
    }

    @Test
    fun encryptedCredentialMutationsEmitOneCompleteTransition() = runTest {
        val prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("diagnostics-token-${System.nanoTime()}", Context.MODE_PRIVATE)
        val barrier = DefaultIdentityTransitionBarrier()
        val registry = AndroidServerRegistry(prefs, barrier)
        val id = registry.addOrUpdate("https://saved.example")
        registry.switchTo(id)
        val tokens = EncryptedTokenManagerImpl(prefs, registry, barrier)
        val observed = mutableListOf<IdentityTransition>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            barrier.transitions.collect { observed += it }
        }

        observed.clear()
        tokens.saveTokens("access", "refresh", 60)
        assertEquals(
            listOf(IdentityTransitionKind.SIGN_IN, IdentityTransitionKind.SIGN_IN),
            observed.map(IdentityTransition::kind),
        )
        assertEquals(
            listOf(IdentityTransitionPhase.WILL_CHANGE, IdentityTransitionPhase.DID_CHANGE),
            observed.map(IdentityTransition::phase),
        )

        observed.clear()
        tokens.saveTokens("refreshed-access", "refreshed-refresh", 60)
        assertEquals(emptyList(), observed, "background token refresh must not close the identity gate")

        observed.clear()
        tokens.invalidateSession()
        assertEquals(
            listOf(IdentityTransitionKind.SIGN_OUT, IdentityTransitionKind.SIGN_OUT),
            observed.map(IdentityTransition::kind),
        )

        observed.clear()
        tokens.saveTokens("new-access", "new-refresh", 60)
        assertEquals(
            listOf(IdentityTransitionKind.SIGN_IN, IdentityTransitionKind.SIGN_IN),
            observed.map(IdentityTransition::kind),
        )
    }
}
