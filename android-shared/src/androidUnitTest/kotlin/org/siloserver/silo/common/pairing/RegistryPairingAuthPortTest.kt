package org.siloserver.silo.common.pairing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import org.siloserver.silo.model.server.ServerContract
import org.siloserver.silo.network.api.AuthApi
import org.siloserver.silo.network.apiv2.ApiV2Probe
import org.siloserver.silo.repository.AuthRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.siloserver.silo.network.AndroidServerRegistry
import org.siloserver.silo.network.DefaultIdentityTransitionBarrier
import org.siloserver.silo.network.EncryptedTokenManagerImpl
import org.siloserver.silo.network.IdentityTransitionKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.siloserver.silo.network.CleartextOriginConsent
import org.siloserver.silo.network.CleartextOriginNotApprovedException

@RunWith(RobolectricTestRunner::class)
class RegistryPairingAuthPortTest {
    @Test
    fun unapprovedCleartextPairingCannotPersistCredentials() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("pairing-cleartext-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val registry = AndroidServerRegistry(prefs)
        val tokens = EncryptedTokenManagerImpl(prefs, registry)
        val consent = object : CleartextOriginConsent {
            override suspend fun isApproved(origin: String): Boolean = false
        }

        assertFailsWith<CleartextOriginNotApprovedException> {
            RegistryPairingAuthPort(tokens, registry, consent).persistApprovedSession(
                serverUrl = "http://silo.lan",
                serverName = "Unsafe",
                accessToken = "access",
                refreshToken = "refresh",
                expiresIn = 3600,
            )
        }

        assertNull(registry.activeEntry.value)
        assertNull(tokens.getAccessToken())
    }

    @Test
    fun approvedSameUrlSessionClearsOldProfileAndReplacesTokens() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("pairing-auth-port-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val registry = AndroidServerRegistry(prefs)
        val tokens = EncryptedTokenManagerImpl(prefs, registry)
        val serverUrl = "https://silo.example"
        val serverId = registry.addOrUpdate(serverUrl, fetchedName = "Old Server Name")
        registry.rename(serverId, "Living Room")
        registry.switchTo(serverId)
        tokens.switchActiveServer(serverId)
        tokens.saveTokens("old-access", "old-refresh", 3600)
        tokens.setProfileId("old-profile")
        tokens.setProfileToken("old-profile-token")
        registry.setProfileId(serverId, "old-profile")

        RegistryPairingAuthPort(tokens, registry).persistApprovedSession(
            serverUrl = serverUrl,
            serverName = "New Server Name",
            accessToken = "new-access",
            refreshToken = "new-refresh",
            expiresIn = 7200,
        )

        assertEquals(serverId, registry.activeServerId.value)
        assertNull(registry.activeEntry.value?.profileId)
        assertEquals("Living Room", registry.activeEntry.value?.userOverrideName)
        assertEquals("New Server Name", registry.activeEntry.value?.fetchedName)
        assertEquals("new-access", tokens.getAccessToken())
        assertEquals("new-refresh", tokens.getRefreshToken())
        assertNull(tokens.getProfileId())
        assertNull(tokens.getProfileToken())
    }

    @Test
    fun sameServerReplacementWaitsForASuspendedCreateFence() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("pairing-create-fence", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val transitions = DefaultIdentityTransitionBarrier()
        val registry = AndroidServerRegistry(prefs, transitions)
        val serverUrl = "https://silo.example"
        val serverId = registry.addOrUpdate(serverUrl)
        registry.switchTo(serverId)
        val tokens = EncryptedTokenManagerImpl(prefs, registry, transitions)
        tokens.saveTokens("old-access", "old-refresh", 3600)
        val createStarted = CompletableDeferred<Unit>()
        val releaseCreate = CompletableDeferred<Unit>()
        val expectedGeneration = transitions.generation.value
        val create = async {
            transitions.withCurrentGeneration(expectedGeneration) {
                createStarted.complete(Unit)
                releaseCreate.await()
                checkNotNull(tokens.getAccessToken())
            }
        }
        createStarted.await()

        val replacement = async {
            RegistryPairingAuthPort(tokens, registry).persistApprovedSession(
                serverUrl = serverUrl,
                serverName = null,
                accessToken = "new-access",
                refreshToken = "new-refresh",
                expiresIn = 7200,
            )
        }
        runCurrent()

        assertFalse(replacement.isCompleted)
        assertEquals("old-access", tokens.getAccessToken())
        releaseCreate.complete(Unit)
        assertEquals("old-access", create.await())
        replacement.await()
        assertEquals("new-access", tokens.getAccessToken())
        assertTrue(transitions.generation.value > expectedGeneration)
    }

    @Test
    fun accountPurgeFailureAbortsSameServerCredentialReplacement() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("pairing-purge-failure", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val transitions = DefaultIdentityTransitionBarrier()
        val registry = AndroidServerRegistry(prefs, transitions)
        val serverUrl = "https://silo.example"
        val serverId = registry.addOrUpdate(serverUrl)
        registry.switchTo(serverId)
        val tokens = EncryptedTokenManagerImpl(prefs, registry, transitions)
        tokens.saveTokens("old-access", "old-refresh", 3600)
        tokens.setProfileIdentity("old-profile", "old-profile-token")
        registry.setProfileId(serverId, "old-profile")
        transitions.installGate { transition ->
            if (transition.kind == IdentityTransitionKind.ACCOUNT_REPLACE) {
                error("injected diagnostics purge failure")
            }
        }

        assertFailsWith<IllegalStateException> {
            RegistryPairingAuthPort(tokens, registry).persistApprovedSession(
                serverUrl = serverUrl,
                serverName = null,
                accessToken = "new-access",
                refreshToken = "new-refresh",
                expiresIn = 7200,
            )
        }

        assertEquals(serverId, registry.activeServerId.value)
        assertEquals("old-access", tokens.getAccessToken())
        assertEquals("old-refresh", tokens.getRefreshToken())
        assertEquals("old-profile", tokens.getProfileId())
        assertEquals("old-profile-token", tokens.getProfileToken())
    }

    @Test
    fun processDeathAfterAtomicCommitReconstructsOneCompleteNewIdentity() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("pairing-account-commit-reconstruction", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val registry = AndroidServerRegistry(prefs)
        val oldId = registry.addOrUpdate("https://old.example")
        val newId = registry.addOrUpdate("https://new.example")
        registry.switchTo(oldId)
        val simulatedDeath = EncryptedTokenManagerImpl(
            prefs = prefs,
            registry = registry,
            afterAccountSessionCommit = { error("simulated process death") },
        )
        simulatedDeath.saveTokens("old-access", "old-refresh", 3600)

        assertFailsWith<IllegalStateException> {
            simulatedDeath.replaceAccountSession(
                serverId = newId,
                accessToken = "new-access",
                refreshToken = "new-refresh",
                expiresIn = 7200,
                profileId = "new-profile",
                profileToken = "new-profile-token",
            )
        }

        val reconstructedRegistry = AndroidServerRegistry(prefs)
        val reconstructedTokens = EncryptedTokenManagerImpl(prefs, reconstructedRegistry)
        assertEquals(newId, reconstructedRegistry.activeServerId.value)
        assertEquals("new-profile", reconstructedRegistry.activeEntry.value?.profileId)
        assertEquals("new-access", reconstructedTokens.getAccessToken())
        assertEquals("new-refresh", reconstructedTokens.getRefreshToken())
        assertEquals("new-profile", reconstructedTokens.getProfileId())
        assertEquals("new-profile-token", reconstructedTokens.getProfileToken())
    }

    @Test
    fun failedAtomicCommitLeavesOldRegistryAndCredentialsVisible() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("pairing-account-commit-failure", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val transitions = org.siloserver.silo.network.DefaultIdentityTransitionBarrier()
        val initialRegistry = AndroidServerRegistry(prefs, transitions)
        val oldId = initialRegistry.addOrUpdate("https://old.example")
        val newId = initialRegistry.addOrUpdate("https://new.example")
        initialRegistry.switchTo(oldId)
        val initialTokens = EncryptedTokenManagerImpl(prefs, initialRegistry, transitions)
        initialTokens.saveTokens("old-access", "old-refresh", 3600)
        initialTokens.setProfileIdentity("old-profile", "old-profile-token")
        initialRegistry.setProfileId(oldId, "old-profile")

        val failingRegistry = AndroidServerRegistry(prefs, transitions, commitEditor = { false })
        val failingTokens = EncryptedTokenManagerImpl(prefs, failingRegistry, transitions)
        var gateRan = false
        transitions.installGate { transition ->
            if (transition.kind == org.siloserver.silo.network.IdentityTransitionKind.ACCOUNT_REPLACE) gateRan = true
        }

        assertFailsWith<IllegalStateException> {
            failingTokens.replaceAccountSession(
                serverId = newId,
                accessToken = "new-access",
                refreshToken = "new-refresh",
                expiresIn = 7200,
            )
        }

        assertTrue(gateRan)
        assertEquals(oldId, failingRegistry.activeServerId.value)
        assertEquals("old-access", failingTokens.getAccessToken())
        assertEquals("old-refresh", failingTokens.getRefreshToken())
        assertEquals("old-profile", failingTokens.getProfileId())
        assertFalse(prefs.contains(AndroidServerRegistry.serverScopedKey(newId, "access_token")))
    }

    @Test
    fun rollbackToPreviousServerReprobesItsContractExactlyOnce() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("pairing-rollback-reprobe", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val transitions = DefaultIdentityTransitionBarrier()
        val registry = AndroidServerRegistry(prefs, transitions)
        val oldId = registry.addOrUpdate("https://old.example")
        registry.switchTo(oldId)
        // The commit hook runs after the registry has already switched to the
        // new server, so failing it exercises the restore-and-reprobe branch.
        val tokens = EncryptedTokenManagerImpl(
            prefs,
            registry,
            transitions,
            afterAccountSessionCommit = { error("injected post-commit failure") },
        )
        tokens.saveTokens("old-access", "old-refresh", 3600)
        var probes = 0
        val client = HttpClient(
            MockEngine { request ->
                check(request.url.encodedPath == ApiV2Probe.PATH) { "unexpected request ${request.url}" }
                probes += 1
                respond("404 page not found\n", HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, "text/plain"))
            },
        )
        val authRepository = AuthRepository(
            authApi = AuthApi(client),
            tokenManager = tokens,
            serverRegistry = registry,
            apiV2Probe = ApiV2Probe(client),
        )

        // Real clock: the probe is bounded, and under the test scheduler the
        // virtual-time bound would fire before the engine thread answers.
        assertFailsWith<IllegalStateException> {
            withContext(Dispatchers.Default) {
                RegistryPairingAuthPort(tokens, registry, authRepository = authRepository).persistApprovedSession(
                    serverUrl = "https://new.example",
                    serverName = null,
                    accessToken = "new-access",
                    refreshToken = "new-refresh",
                    expiresIn = 7200,
                )
            }
        }

        assertEquals(oldId, registry.activeServerId.value)
        assertEquals(oldId, tokens.getCurrentServerId())
        assertEquals("old-access", tokens.getAccessToken())
        assertEquals(1, probes)
        assertEquals(ServerContract.UPDATE_REQUIRED, registry.entries.value.first { it.id == oldId }.contract)
    }

    @Test
    fun successfulPairingProbesTheNewServerExactlyOnce() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("pairing-success-probe", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val transitions = DefaultIdentityTransitionBarrier()
        val registry = AndroidServerRegistry(prefs, transitions)
        val oldId = registry.addOrUpdate("https://old.example")
        registry.switchTo(oldId)
        val tokens = EncryptedTokenManagerImpl(prefs, registry, transitions)
        tokens.saveTokens("old-access", "old-refresh", 3600)
        var probes = 0
        val client = HttpClient(
            MockEngine { request ->
                check(request.url.encodedPath == ApiV2Probe.PATH) { "unexpected request ${request.url}" }
                probes += 1
                respond("404 page not found\n", HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, "text/plain"))
            },
        )
        val authRepository = AuthRepository(
            authApi = AuthApi(client),
            tokenManager = tokens,
            serverRegistry = registry,
            apiV2Probe = ApiV2Probe(client),
        )

        // Real clock: the probe is bounded, and under the test scheduler the
        // virtual-time bound would fire before the engine thread answers.
        withContext(Dispatchers.Default) {
            RegistryPairingAuthPort(tokens, registry, authRepository = authRepository).persistApprovedSession(
                serverUrl = "https://new.example",
                serverName = null,
                accessToken = "new-access",
                refreshToken = "new-refresh",
                expiresIn = 7200,
            )
        }

        val newId = registry.entries.value.first { it.url == "https://new.example" }.id
        assertEquals(newId, registry.activeServerId.value)
        assertEquals("new-access", tokens.getAccessToken())
        // Exactly one probe, and its verdict lands on the newly paired
        // server's entry — not the old one's.
        assertEquals(1, probes)
        assertEquals(ServerContract.UPDATE_REQUIRED, registry.entries.value.first { it.id == newId }.contract)
        assertEquals(ServerContract.UNKNOWN, registry.entries.value.first { it.id == oldId }.contract)
    }

    /**
     * The companion waits ~30 s for ServerResult while an unanswered probe
     * would sit on the client's ~60 s socket timeout. The success-path probe
     * must therefore be bounded so the port reports SignedIn on time even
     * when /api/v2/system/info stalls; the verdict stays UNKNOWN (passes the
     * gate) and is re-probed on the next switch or launch.
     *
     * Runs on runTest's virtual clock: the never-answering engine leaves the
     * test scheduler idle, so it skips straight to the 3 s bound instead of
     * really waiting.
     */
    @Test
    fun successfulPairingReportsSignedInWhenTheProbeNeverAnswers() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("pairing-stalled-probe", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val transitions = DefaultIdentityTransitionBarrier()
        val registry = AndroidServerRegistry(prefs, transitions)
        val tokens = EncryptedTokenManagerImpl(prefs, registry, transitions)
        // Whether the engine thread even reaches this handler before the
        // virtual clock jumps to the bound is a race, so the test asserts on
        // the outcome (bounded return, intact session, no verdict), not on a
        // request count.
        val client = HttpClient(
            MockEngine { request ->
                check(request.url.encodedPath == ApiV2Probe.PATH) { "unexpected request ${request.url}" }
                awaitCancellation()
            },
        )
        val authRepository = AuthRepository(
            authApi = AuthApi(client),
            tokenManager = tokens,
            serverRegistry = registry,
            apiV2Probe = ApiV2Probe(client),
        )

        val startedAt = testScheduler.currentTime
        RegistryPairingAuthPort(tokens, registry, authRepository = authRepository).persistApprovedSession(
            serverUrl = "https://new.example",
            serverName = null,
            accessToken = "new-access",
            refreshToken = "new-refresh",
            expiresIn = 7200,
        )
        val elapsed = testScheduler.currentTime - startedAt

        // The commit completed inside the probe bound, well under the
        // companion's 30 s ServerResult wait, with the session intact.
        assertTrue(elapsed <= 3_000L, "persistApprovedSession took ${elapsed}ms of virtual time")
        val newId = registry.entries.value.first { it.url == "https://new.example" }.id
        assertEquals(newId, registry.activeServerId.value)
        assertEquals("new-access", tokens.getAccessToken())
        // The stalled probe recorded nothing: no verdict, which the gate passes.
        assertEquals(ServerContract.UNKNOWN, registry.entries.value.first { it.id == newId }.contract)
    }

    @Test
    fun rollbackToPreviousServerWithoutAuthRepositorySkipsTheProbe() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("pairing-rollback-no-repository", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val transitions = DefaultIdentityTransitionBarrier()
        val registry = AndroidServerRegistry(prefs, transitions)
        val oldId = registry.addOrUpdate("https://old.example")
        registry.switchTo(oldId)
        val tokens = EncryptedTokenManagerImpl(
            prefs,
            registry,
            transitions,
            afterAccountSessionCommit = { error("injected post-commit failure") },
        )
        tokens.saveTokens("old-access", "old-refresh", 3600)

        assertFailsWith<IllegalStateException> {
            RegistryPairingAuthPort(tokens, registry).persistApprovedSession(
                serverUrl = "https://new.example",
                serverName = null,
                accessToken = "new-access",
                refreshToken = "new-refresh",
                expiresIn = 7200,
            )
        }

        assertEquals(oldId, registry.activeServerId.value)
        assertEquals(oldId, tokens.getCurrentServerId())
        assertEquals("old-access", tokens.getAccessToken())
        assertEquals(ServerContract.UNKNOWN, registry.entries.value.first { it.id == oldId }.contract)
    }

    @Test
    fun processDeathAfterAtomicSignOutReconstructsNoCredentialsOrProfile() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("pairing-sign-out-reconstruction", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val registry = AndroidServerRegistry(prefs)
        val serverId = registry.addOrUpdate("https://signed-in.example")
        registry.switchTo(serverId)
        EncryptedTokenManagerImpl(prefs, registry).apply {
            saveTokens("old-access", "old-refresh", 3600)
            setProfileIdentity("old-profile", "old-profile-token")
        }
        registry.setProfileId(serverId, "old-profile")
        val simulatedDeath = EncryptedTokenManagerImpl(
            prefs = prefs,
            registry = AndroidServerRegistry(prefs),
            afterAccountSignOutCommit = { error("simulated process death") },
        )

        assertFailsWith<IllegalStateException> { simulatedDeath.signOutCurrentServer() }

        val reconstructedRegistry = AndroidServerRegistry(prefs)
        val reconstructedTokens = EncryptedTokenManagerImpl(prefs, reconstructedRegistry)
        assertEquals(serverId, reconstructedRegistry.activeServerId.value)
        assertNull(reconstructedRegistry.activeEntry.value?.profileId)
        assertNull(reconstructedTokens.getAccessToken())
        assertNull(reconstructedTokens.getRefreshToken())
        assertNull(reconstructedTokens.getProfileId())
        assertNull(reconstructedTokens.getProfileToken())
    }

    @Test
    fun processDeathAfterAtomicServerRemovalReconstructsTargetAbsentWithoutTouchingOtherCredentials() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("pairing-server-remove-reconstruction", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val initialRegistry = AndroidServerRegistry(prefs)
        val serverA = initialRegistry.addOrUpdate("https://a.example")
        val serverB = initialRegistry.addOrUpdate("https://b.example")
        val initialTokens = EncryptedTokenManagerImpl(prefs, initialRegistry)
        initialTokens.replaceAccountSession(
            serverId = serverA,
            accessToken = "a-access",
            refreshToken = "a-refresh",
            expiresIn = 3600,
        )
        initialTokens.replaceAccountSession(
            serverId = serverB,
            accessToken = "b-access",
            refreshToken = "b-refresh",
            expiresIn = 3600,
        )
        initialRegistry.switchTo(serverA)
        initialTokens.switchActiveServer(serverA)
        val simulatedDeathRegistry = AndroidServerRegistry(
            prefs = prefs,
            afterServerRemovalCommit = { error("simulated process death") },
        )

        assertFailsWith<IllegalStateException> { simulatedDeathRegistry.remove(serverB) }

        val reconstructedRegistry = AndroidServerRegistry(prefs)
        val reconstructedTokens = EncryptedTokenManagerImpl(prefs, reconstructedRegistry)
        assertEquals(serverA, reconstructedRegistry.activeServerId.value)
        assertTrue(reconstructedRegistry.entries.value.none { it.id == serverB })
        assertEquals("a-access", reconstructedTokens.getAccessToken())
        assertEquals("a-refresh", reconstructedTokens.getRefreshToken())
        assertFalse(
            prefs.all.keys.any { key -> key.startsWith(AndroidServerRegistry.serverScopedKey(serverB, "")) },
        )
    }
}
