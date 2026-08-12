package org.siloserver.silo.network

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.server.ServerEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EncryptedTokenManagerScopeGenerationTest {

    @Test
    fun signOutTargetUsesTheLiveRegistryServerEvenBeforeTheCacheObserverRuns() = runTest {
        val registry = FakeServerRegistry()
        val transitions = DefaultIdentityTransitionBarrier()
        val observed = mutableListOf<IdentityTransition>()
        transitions.installObserverForTests(observed::add)
        val manager = EncryptedTokenManagerImpl(
            prefs = inMemoryPreferences(),
            registry = registry,
            identityTransitions = transitions,
        )
        manager.saveTokens("server-a-access", "server-a-refresh", 3600)

        registry.switchExternally("server-b")
        observed.clear()
        manager.clearTokens()

        assertEquals(listOf("server-b", "server-b"), observed.map(IdentityTransition::targetServerId))
        assertEquals(listOf(true, true), observed.map(IdentityTransition::affectsCurrentIdentity))
    }

    @Test
    fun staleSameServerScopeCannotReadOrRestoreReloggedCredentials() = runTest {
        val registry = FakeServerRegistry()
        val manager = EncryptedTokenManagerImpl(
            prefs = inMemoryPreferences(),
            registry = registry,
        )
        manager.saveTokens("old-access", "old-refresh", 3600)
        val staleScope = checkNotNull(manager.snapshotCurrentScope())

        manager.clearTokens()
        manager.saveTokens("new-access", "new-refresh", 3600)

        assertNull(manager.getAccessTokenForScope(staleScope))
        assertNull(manager.getRefreshTokenForScope(staleScope))

        manager.saveTokensForScope(staleScope, "stale-access", "stale-refresh", 3600)

        assertEquals("new-access", manager.getAccessToken())
        assertEquals("new-refresh", manager.getRefreshToken())
    }

    @Test
    fun startupSnapshotOfPreloadedCredentialsCannotReadOrRestoreReloggedCredentials() = runTest {
        val registry = FakeServerRegistry()
        val manager = EncryptedTokenManagerImpl(
            prefs = inMemoryPreferences(
                AndroidServerRegistry.serverScopedKey("server-a", EncryptedTokenManagerImpl.KEY_ACCESS_TOKEN) to
                    "old-access",
                AndroidServerRegistry.serverScopedKey("server-a", EncryptedTokenManagerImpl.KEY_REFRESH_TOKEN) to
                    "old-refresh",
            ),
            registry = registry,
        )
        val staleScope = checkNotNull(manager.snapshotCurrentScope())

        manager.clearTokens()
        manager.saveTokens("new-access", "new-refresh", 3600)

        assertNull(manager.getAccessTokenForScope(staleScope))
        assertNull(manager.getRefreshTokenForScope(staleScope))
        manager.saveTokensForScope(staleScope, "stale-access", "stale-refresh", 3600)
        assertEquals("new-access", manager.getAccessToken())
        assertEquals("new-refresh", manager.getRefreshToken())
    }

    @Test
    fun registryFirstSwitchInvalidatesSnapshotBeforeManagerSwitchCall() = runTest {
        val registry = FakeServerRegistry()
        val manager = EncryptedTokenManagerImpl(
            prefs = inMemoryPreferences(),
            registry = registry,
        )
        manager.saveTokens("server-a-access", "server-a-refresh", 3600)
        val staleScope = checkNotNull(manager.snapshotCurrentScope())

        registry.switchExternally("server-b")
        manager.getAccessToken()
        manager.switchActiveServer("server-b")

        assertNull(manager.getAccessTokenForScope(staleScope))
        manager.saveTokensForScope(staleScope, "stale-access", "stale-refresh", 3600)
        assertNull(manager.getAccessTokenForScope(staleScope))
    }

    /**
     * The snapshot was the ONE identity read that did not reconcile with the
     * registry first, so immediately after a registry-driven switch it still
     * described the previous server — and every guard built on it then decided
     * against a server the app had already left. Deliberately no intervening
     * `getAccessToken()`: that read reconciles as a side effect and hid this.
     */
    @Test
    fun snapshotReportsTheNewServerImmediatelyAfterARegistryFirstSwitch() = runTest {
        val registry = FakeServerRegistry()
        val manager = EncryptedTokenManagerImpl(
            prefs = inMemoryPreferences(),
            registry = registry,
        )
        manager.saveTokens("server-a-access", "server-a-refresh", 3600)

        registry.switchExternally("server-b")

        assertEquals("server-b", manager.snapshotCurrentScope()?.serverId)
    }

    /** An overlay owns identity outright; a switch underneath must not retarget it. */
    @Test
    fun aTemporaryOverlaySurvivesARegistryFirstSwitch() = runTest {
        val registry = FakeServerRegistry()
        val manager = EncryptedTokenManagerImpl(
            prefs = inMemoryPreferences(),
            registry = registry,
        )
        manager.saveTokens("server-a-access", "server-a-refresh", 3600)
        manager.beginTemporaryScope(
            TemporaryAuthScope(
                generationId = "overlay-1",
                serverId = "overlay-server",
                serverUrl = "https://overlay.example",
                accessToken = "overlay-access",
                refreshToken = "overlay-refresh",
                profileId = "overlay-profile",
                profileToken = "overlay-token",
                expiresAtEpochMs = Long.MAX_VALUE,
            ),
        )

        registry.switchExternally("server-b")

        assertEquals("overlay-server", manager.snapshotCurrentScope()?.serverId)
    }

    @Test
    fun clearingATemporaryOverlayDoesNotAuthorizePersistentIdentityPurge() = runTest {
        val registry = FakeServerRegistry()
        val transitions = DefaultIdentityTransitionBarrier()
        val observed = mutableListOf<IdentityTransition>()
        transitions.installObserverForTests(observed::add)
        val manager = EncryptedTokenManagerImpl(
            prefs = inMemoryPreferences(),
            registry = registry,
            identityTransitions = transitions,
        )
        manager.saveTokens("saved-access", "saved-refresh", 3600)
        manager.beginTemporaryScope(
            TemporaryAuthScope(
                generationId = "overlay-1",
                serverId = "overlay-server",
                serverUrl = "https://overlay.example",
                accessToken = "overlay-access",
                refreshToken = "overlay-refresh",
                profileId = "overlay-profile",
                profileToken = "overlay-profile-token",
                expiresAtEpochMs = Long.MAX_VALUE,
            ),
        )
        observed.clear()

        manager.clearTokens()

        assertEquals(listOf(false, false), observed.map(IdentityTransition::purgesPersistentIdentity))
        assertFalse(manager.hasTemporaryScope())
        assertEquals("saved-access", manager.getAccessToken())
        assertEquals("saved-refresh", manager.getRefreshToken())
    }

    @Test
    fun freshGenerationZeroCompanionScopeRefreshesButTrueUnversionedResponseFailsClosed() = runTest {
        val registry = FakeServerRegistry()
        val transitions = DefaultIdentityTransitionBarrier()
        val manager = EncryptedTokenManagerImpl(
            prefs = inMemoryPreferences(),
            registry = registry,
            identityTransitions = transitions,
        )
        val scope = AuthScopeSnapshot(
            serverId = "server-b",
            profileId = null,
            serverUrl = "https://server-b.example",
            profileToken = null,
            identityGeneration = transitions.generation.value,
            isIdentityGenerationStamped = true,
        )
        assertEquals(0L, scope.identityGeneration)
        assertTrue(scope.isIdentityGenerationStamped)

        manager.saveTokensForScope(scope, "rotated-access", "rotated-refresh", 3600)

        assertEquals("rotated-access", manager.getAccessTokenForScope(scope))
        assertEquals("rotated-refresh", manager.getRefreshTokenForScope(scope))

        val unversioned = scope.copy(isIdentityGenerationStamped = false)
        manager.saveTokensForScope(unversioned, "unproven-access", "unproven-refresh", 3600)
        assertEquals("rotated-access", manager.getAccessTokenForScope(scope))
        assertEquals("rotated-refresh", manager.getRefreshTokenForScope(scope))
    }

    @Test
    fun removedInactiveServerCannotBeRecreatedByAStaleUnversionedRefresh() = runTest {
        val registry = FakeServerRegistry()
        val transitions = DefaultIdentityTransitionBarrier()
        val preferences = inMemoryPreferences()
        val manager = EncryptedTokenManagerImpl(
            prefs = preferences,
            registry = registry,
            identityTransitions = transitions,
        )
        val scope = AuthScopeSnapshot(
            serverId = "server-b",
            profileId = null,
            serverUrl = "https://server-b.example",
            profileToken = null,
        )

        transitions.changing(IdentityTransitionKind.SERVER_REMOVE) {
            registry.removeExternally("server-b")
        }
        manager.saveTokensForScope(scope, "stale-access", "stale-refresh", 3600)

        assertFalse(
            preferences.contains(
                AndroidServerRegistry.serverScopedKey("server-b", EncryptedTokenManagerImpl.KEY_ACCESS_TOKEN),
            ),
        )
    }

    @Test
    fun refreshSuspendedAtServerRemovalCommitCannotReviveRemovedTokenPrefix() = runTest {
        val preferences = seededRegistryPreferences(activeServerId = "server-b")
        val transitions = DefaultIdentityTransitionBarrier()
        val removalCommitted = CountDownLatch(1)
        val releaseRemoval = CountDownLatch(1)
        val registry = AndroidServerRegistry(
            prefs = preferences,
            identityTransitions = transitions,
            afterServerRemovalCommit = {
                removalCommitted.countDown()
                check(releaseRemoval.await(5, TimeUnit.SECONDS))
            },
        )
        val serverB = "server-b"
        val manager = EncryptedTokenManagerImpl(preferences, registry, identityTransitions = transitions)
        manager.saveTokens("server-b-access", "server-b-refresh", 3600)
        val scope = AuthScopeSnapshot(
            serverId = serverB,
            profileId = null,
            serverUrl = "https://server-b.example",
            profileToken = null,
        )
        val removal = backgroundScope.async(Dispatchers.Default) { registry.remove(serverB) }
        assertTrue(removalCommitted.await(5, TimeUnit.SECONDS))

        val staleSave = backgroundScope.async(Dispatchers.Default) {
            manager.saveTokensForScope(scope, "stale-access", "stale-refresh", 3600)
        }
        val staleRead = backgroundScope.async(Dispatchers.Default) {
            manager.getAccessTokenForScope(scope)
        }
        assertFalse(staleRead.isCompleted)
        releaseRemoval.countDown()
        removal.await()
        staleSave.await()
        assertNull(staleRead.await())

        val prefix = AndroidServerRegistry.serverScopedKey(serverB, "")
        assertTrue(preferences.all.keys.none { it.startsWith(prefix) })
    }

    @Test
    fun stampedHandBuiltRefreshCannotOverwriteSameServerAccountReplacement() = runTest {
        val preferences = seededRegistryPreferences(activeServerId = "server-a", includeServerB = false)
        val transitions = DefaultIdentityTransitionBarrier()
        val registry = AndroidServerRegistry(preferences, identityTransitions = transitions)
        val serverId = "server-a"
        val manager = EncryptedTokenManagerImpl(
            prefs = preferences,
            registry = registry,
            identityTransitions = transitions,
        )
        manager.saveTokens("account-a-access", "account-a-refresh", 3600)
        val companionScope = AuthScopeSnapshot(
            serverId = serverId,
            profileId = null,
            serverUrl = "https://server-a.example",
            profileToken = null,
            identityGeneration = transitions.generation.value,
            isIdentityGenerationStamped = true,
        )

        manager.replaceAccountSession(
            serverId = serverId,
            accessToken = "account-b-access",
            refreshToken = "account-b-refresh",
            expiresIn = 3600,
            profileId = "account-b-profile",
            profileToken = "account-b-profile-token",
        )
        manager.saveTokensForScope(companionScope, "late-a-access", "late-a-refresh", 3600)

        assertEquals("account-b-access", manager.getAccessToken())
        assertEquals("account-b-refresh", manager.getRefreshToken())
    }

    @Test
    fun committedRegistryAndTokenStatePublishesEvenWhenPostCommitCallbacksFail() = runTest {
        val preferences = seededRegistryPreferences(activeServerId = "server-a")
        val transitions = DefaultIdentityTransitionBarrier()
        val registry = AndroidServerRegistry(
            prefs = preferences,
            identityTransitions = transitions,
            afterServerRemovalCommit = { error("after removal") },
        )

        assertFailsWith<IllegalStateException> { registry.remove("server-b") }
        assertTrue(registry.entries.value.none { it.id == "server-b" })

        val manager = EncryptedTokenManagerImpl(
            prefs = preferences,
            registry = registry,
            identityTransitions = transitions,
            afterAccountSessionCommit = { error("after session") },
        )
        assertFailsWith<IllegalStateException> {
            manager.replaceAccountSession(
                serverId = "server-a",
                accessToken = "committed-access",
                refreshToken = "committed-refresh",
                expiresIn = 3600,
                profileId = "overlay-profile",
                profileToken = "overlay-profile-token",
            )
        }
        assertEquals("committed-access", manager.getAccessToken())
        assertEquals("committed-refresh", manager.getRefreshToken())
    }

    @Test
    fun rejectedAccountReplacementDoesNotRunPrivacyGatesOrAdvanceGeneration() = runTest {
        val preferences = seededRegistryPreferences(activeServerId = "server-a", includeServerB = false)
        val transitions = DefaultIdentityTransitionBarrier()
        val observed = mutableListOf<IdentityTransition>()
        transitions.installObserverForTests(observed::add)
        val registry = AndroidServerRegistry(preferences, identityTransitions = transitions)
        val manager = EncryptedTokenManagerImpl(preferences, registry, identityTransitions = transitions)
        manager.beginTemporaryScope(
            TemporaryAuthScope(
                generationId = "overlay-1",
                serverId = "overlay-server",
                serverUrl = "https://overlay.example",
                accessToken = "overlay-access",
                refreshToken = "overlay-refresh",
                profileId = "overlay-profile",
                profileToken = "overlay-profile-token",
                expiresAtEpochMs = Long.MAX_VALUE,
            ),
        )
        observed.clear()
        val generation = transitions.generation.value

        assertFailsWith<IllegalStateException> {
            manager.replaceAccountSession(
                serverId = "server-a",
                accessToken = "new-access",
                refreshToken = "new-refresh",
                expiresIn = 3600,
                profileId = null,
                profileToken = null,
            )
        }

        assertEquals(generation, transitions.generation.value)
        assertTrue(observed.isEmpty())
    }

    private class FakeServerRegistry : ServerRegistry {
        private val serverA = ServerEntry(id = "server-a", url = "https://server-a.example")
        private val serverB = ServerEntry(id = "server-b", url = "https://server-b.example")
        private val entriesFlow = MutableStateFlow(listOf(serverA, serverB))
        private val activeServerIdFlow = MutableStateFlow<String?>(serverA.id)
        private val activeEntryFlow = MutableStateFlow<ServerEntry?>(serverA)
        override val entries: StateFlow<List<ServerEntry>> = entriesFlow
        override val activeServerId: StateFlow<String?> = activeServerIdFlow
        override val activeEntry: StateFlow<ServerEntry?> = activeEntryFlow
        override suspend fun addOrUpdate(url: String, fetchedName: String?): String = serverA.id
        override suspend fun rename(serverId: String, userOverrideName: String?) = Unit
        override suspend fun setFetchedName(serverId: String, fetchedName: String?) = Unit
        override suspend fun setProfileId(serverId: String, profileId: String?) = Unit
        override suspend fun remove(serverId: String) = Unit
        override suspend fun signOut(serverId: String) = Unit
        override suspend fun switchTo(serverId: String) = Unit
        override suspend fun touchActive() = Unit

        fun switchExternally(serverId: String) {
            activeServerIdFlow.value = serverId
            activeEntryFlow.value = entriesFlow.value.first { it.id == serverId }
        }

        fun removeExternally(serverId: String) {
            entriesFlow.value = entriesFlow.value.filterNot { it.id == serverId }
            if (activeServerIdFlow.value == serverId) switchExternally(entriesFlow.value.first().id)
        }
    }

    private fun inMemoryPreferences(vararg initialValues: Pair<String, Any?>): SharedPreferences {
        val values = mutableMapOf<String, Any?>(*initialValues)
        lateinit var preferences: SharedPreferences
        preferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getString" -> values[args!![0]] as? String ?: args[1]
                "getLong" -> values[args!![0]] as? Long ?: args[1]
                "getBoolean" -> values[args!![0]] as? Boolean ?: args[1]
                "contains" -> values.containsKey(args!![0])
                "getAll" -> values.toMap()
                "edit" -> editor(values)
                "registerOnSharedPreferenceChangeListener",
                "unregisterOnSharedPreferenceChangeListener" -> Unit
                else -> method.defaultValue()
            }
        } as SharedPreferences
        return preferences
    }

    private fun editor(values: MutableMap<String, Any?>): SharedPreferences.Editor {
        lateinit var editor: SharedPreferences.Editor
        editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { _, method, args ->
            when (method.name) {
                "putString", "putLong", "putBoolean" ->
                    editor.also { values[args!![0] as String] = args[1] }
                "remove" -> editor.also { values.remove(args!![0] as String) }
                "clear" -> editor.also { values.clear() }
                "apply" -> Unit
                "commit" -> true
                else -> editor
            }
        } as SharedPreferences.Editor
        return editor
    }

    private fun java.lang.reflect.Method.defaultValue(): Any? = when (returnType) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        else -> null
    }

    private fun seededRegistryPreferences(
        activeServerId: String,
        includeServerB: Boolean = true,
    ): SharedPreferences {
        val entries = buildList {
            add("""{"id":"server-a","url":"https://server-a.example","lastUsedAtEpochMs":1}""")
            if (includeServerB) {
                add("""{"id":"server-b","url":"https://server-b.example","lastUsedAtEpochMs":2}""")
            }
        }.joinToString(",")
        return inMemoryPreferences(
            AndroidServerRegistry.KEY_MIGRATED to true,
            AndroidServerRegistry.KEY_REGISTRY_STATE to
                """{"entries":[$entries],"activeServerId":"$activeServerId"}""",
        )
    }
}
