package org.siloserver.silo.common.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.siloserver.silo.network.AndroidServerRegistry
import org.siloserver.silo.network.EncryptedTokenManagerImpl
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The outbox is scoped by `(serverId, profileId)`, and the retry chain that
 * keeps a backed-off scope alive is `SyncWorker`'s `Result.retry()`, driven by
 * what `SyncEngine` counts as remaining for the scope active at the *end* of a
 * drain. Nothing re-triggers a drain for a scope that stops being active, so a
 * scope change is the only thing standing between queued work and silence.
 *
 * Server changes had a trigger. Profile changes did not — and a profile switch
 * leaves `activeServerId` untouched, so watched marks, ratings, favourites and
 * resume positions from the outgoing profile simply stopped syncing. On a TV,
 * which is never relaunched, permanently.
 *
 * These run on real dispatchers rather than `runTest`'s scheduler: the starter's
 * collector lives on its own scope and `advanceUntilIdle` does not drive it, so
 * a virtual-time test observes nothing at all and passes for the wrong reason.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class OutboxSyncStarterScopeTriggerTest {

    private fun registry(name: String): Pair<AndroidServerRegistry, String> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val registry = AndroidServerRegistry(prefs)
        val serverId = runBlocking {
            val id = registry.addOrUpdate("https://silo.example", fetchedName = "Living Room")
            registry.switchTo(id)
            // A profile belongs to a signed-in account, so sign one in as p1.
            EncryptedTokenManagerImpl(prefs, registry)
                .replaceAccountSession(serverId = id, accessToken = "access", refreshToken = "refresh", expiresIn = 3600, profileId = "p1")
            id
        }
        return registry to serverId
    }

    /** Waits for [drains] to reach [target], or gives up so the assertion reports. */
    private suspend fun awaitDrains(drains: AtomicInteger, target: Int) {
        withTimeoutOrNull(5_000) {
            while (drains.get() < target) delay(5)
        }
    }

    @Test
    fun `a profile switch on the same server triggers a drain`() = runBlocking {
        val (registry, serverId) = registry("outbox-starter-profile")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val drains = AtomicInteger()
        OutboxSyncStarter(
            context = ApplicationProvider.getApplicationContext(),
            registry = registry,
            scope = scope,
            enqueueDrain = { drains.incrementAndGet() },
        ).start()
        awaitDrains(drains, 1)
        val afterLaunch = drains.get()

        registry.setProfileId(serverId, "p2")
        awaitDrains(drains, afterLaunch + 1)

        assertEquals(
            afterLaunch + 1,
            drains.get(),
            "the household switched profile and the outgoing profile's queued work went quiet",
        )
        scope.cancel()
    }

    @Test
    fun `a change that is not a scope change does not trigger`() = runBlocking {
        val (registry, serverId) = registry("outbox-starter-idempotent")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val drains = AtomicInteger()
        OutboxSyncStarter(
            context = ApplicationProvider.getApplicationContext(),
            registry = registry,
            scope = scope,
            enqueueDrain = { drains.incrementAndGet() },
        ).start()
        awaitDrains(drains, 1)
        val afterLaunch = drains.get()

        registry.setProfileId(serverId, "p1")
        registry.rename(serverId, "Den")
        // Nothing to await — give any spurious emission room to land.
        delay(200)

        assertEquals(
            afterLaunch,
            drains.get(),
            "only the scope pair may trigger — a rename is not a scope change",
        )
        scope.cancel()
    }

    @Test
    fun `profile switch before collector starts is not swallowed as initial state`() = runTest {
        val (registry, serverId) = registry("outbox-starter-immediate-profile")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val drains = AtomicInteger()
        OutboxSyncStarter(
            context = ApplicationProvider.getApplicationContext(),
            registry = registry,
            scope = scope,
            enqueueDrain = { drains.incrementAndGet() },
        ).start()

        // The collector is queued on [dispatcher] but has not run. Reproduce the
        // real startup race by switching before its first StateFlow read.
        registry.setProfileId(serverId, "p2")
        advanceUntilIdle()

        assertEquals(2, drains.get(), "launch plus the captured p1-to-p2 scope change")
        scope.cancel()
    }
}
