package org.siloserver.silo.network

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EncryptedTokenManagerScopeGenerationTest {

    @Test
    fun signOutTargetUsesTheLiveRegistryServerEvenBeforeTheCacheObserverRuns() = runTest {
        val transitions = DefaultIdentityTransitionBarrier()
        val prefs = seededRegistryPreferences(activeServerId = "server-a")
        val registry = AndroidServerRegistry(prefs, identityTransitions = transitions)
        val observed = mutableListOf<IdentityTransition>()
        transitions.installObserverForTests(observed::add)
        val manager = EncryptedTokenManagerImpl(
            prefs = prefs,
            registry = registry,
            identityTransitions = transitions,
        )
        manager.signIn("server-a", "server-a-access", "server-a-refresh")

        registry.switchTo("server-b")
        observed.clear()
        manager.clearTokens()

        assertEquals(listOf("server-b", "server-b"), observed.map(IdentityTransition::targetServerId))
        assertEquals(listOf(true, true), observed.map(IdentityTransition::affectsCurrentIdentity))
    }

    @Test
    fun staleSameServerScopeCannotReadOrRestoreReloggedCredentials() = runTest {
        val prefs = seededRegistryPreferences(activeServerId = "server-a")
        val registry = AndroidServerRegistry(prefs)
        val manager = EncryptedTokenManagerImpl(
            prefs = prefs,
            registry = registry,
        )
        manager.signIn("server-a", "old-access", "old-refresh")
        val staleScope = checkNotNull(manager.snapshotCurrentScope())

        manager.clearTokens()
        manager.signIn("server-a", "new-access", "new-refresh")

        assertNull(manager.getAccessTokenForScope(staleScope))
        assertNull(manager.getRefreshTokenForScope(staleScope))

        manager.saveTokensForScope(staleScope, "stale-access", "stale-refresh", 3600)

        assertEquals("new-access", manager.getAccessToken())
        assertEquals("new-refresh", manager.getRefreshToken())
    }

    @Test
    fun startupSnapshotOfPreloadedCredentialsCannotReadOrRestoreReloggedCredentials() = runTest {
        // Credentials already on disk at startup: a pre-accounts slot the
        // registry migrates into server-a's account before the manager loads it.
        val prefs = seededRegistryPreferences(
            activeServerId = "server-a",
            key("server-a", EncryptedTokenManagerImpl.KEY_ACCESS_TOKEN) to "old-access",
            key("server-a", EncryptedTokenManagerImpl.KEY_REFRESH_TOKEN) to "old-refresh",
        )
        val registry = AndroidServerRegistry(prefs)
        val manager = EncryptedTokenManagerImpl(
            prefs = prefs,
            registry = registry,
        )
        assertEquals("old-access", manager.getAccessToken())
        val staleScope = checkNotNull(manager.snapshotCurrentScope())

        manager.clearTokens()
        manager.signIn("server-a", "new-access", "new-refresh")

        assertNull(manager.getAccessTokenForScope(staleScope))
        assertNull(manager.getRefreshTokenForScope(staleScope))
        manager.saveTokensForScope(staleScope, "stale-access", "stale-refresh", 3600)
        assertEquals("new-access", manager.getAccessToken())
        assertEquals("new-refresh", manager.getRefreshToken())
    }

    @Test
    fun registryFirstSwitchInvalidatesSnapshotBeforeManagerSwitchCall() = runTest {
        val prefs = seededRegistryPreferences(activeServerId = "server-a")
        val registry = AndroidServerRegistry(prefs)
        val manager = EncryptedTokenManagerImpl(
            prefs = prefs,
            registry = registry,
        )
        manager.signIn("server-a", "server-a-access", "server-a-refresh")
        val staleScope = checkNotNull(manager.snapshotCurrentScope())

        registry.switchTo("server-b")
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
        val prefs = seededRegistryPreferences(activeServerId = "server-a")
        val registry = AndroidServerRegistry(prefs)
        val manager = EncryptedTokenManagerImpl(
            prefs = prefs,
            registry = registry,
        )
        manager.signIn("server-a", "server-a-access", "server-a-refresh")

        registry.switchTo("server-b")

        assertEquals("server-b", manager.snapshotCurrentScope()?.serverId)
    }

    /** An overlay owns identity outright; a switch underneath must not retarget it. */
    @Test
    fun aTemporaryOverlaySurvivesARegistryFirstSwitch() = runTest {
        val prefs = seededRegistryPreferences(activeServerId = "server-a")
        val registry = AndroidServerRegistry(prefs)
        val manager = EncryptedTokenManagerImpl(
            prefs = prefs,
            registry = registry,
        )
        manager.signIn("server-a", "server-a-access", "server-a-refresh")
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

        registry.switchTo("server-b")

        assertEquals("overlay-server", manager.snapshotCurrentScope()?.serverId)
    }

    @Test
    fun clearingATemporaryOverlayDoesNotAuthorizePersistentIdentityPurge() = runTest {
        val transitions = DefaultIdentityTransitionBarrier()
        val prefs = seededRegistryPreferences(activeServerId = "server-a")
        val registry = AndroidServerRegistry(prefs, identityTransitions = transitions)
        val observed = mutableListOf<IdentityTransition>()
        transitions.installObserverForTests(observed::add)
        val manager = EncryptedTokenManagerImpl(
            prefs = prefs,
            registry = registry,
            identityTransitions = transitions,
        )
        manager.signIn("server-a", "saved-access", "saved-refresh")
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
        val transitions = DefaultIdentityTransitionBarrier()
        // server-b holds a saved account (migrated at startup) that the
        // companion scope, which names no account, refreshes.
        val prefs = seededRegistryPreferences(activeServerId = "server-a", *savedSession("server-b", null))
        val registry = AndroidServerRegistry(prefs, identityTransitions = transitions)
        val manager = EncryptedTokenManagerImpl(
            prefs = prefs,
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
        val transitions = DefaultIdentityTransitionBarrier()
        val prefs = seededRegistryPreferences(activeServerId = "server-a", *savedSession("server-b", null))
        val registry = AndroidServerRegistry(prefs, identityTransitions = transitions)
        val manager = EncryptedTokenManagerImpl(
            prefs = prefs,
            registry = registry,
            identityTransitions = transitions,
        )
        val scope = AuthScopeSnapshot(
            serverId = "server-b",
            profileId = null,
            serverUrl = "https://server-b.example",
            profileToken = null,
        )

        registry.remove("server-b")
        manager.saveTokensForScope(scope, "stale-access", "stale-refresh", 3600)

        val prefix = AndroidServerRegistry.serverScopedKey("server-b", "")
        assertTrue(prefs.all.keys.none { it.startsWith(prefix) })
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
        manager.signIn(serverB, "server-b-access", "server-b-refresh")
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
        manager.signIn(serverId, "account-a-access", "account-a-refresh")
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

    // accessTokenExpiresWithin: whether the approver renews a scope's token
    // before looking a TV's code up (TV sign-in spec).

    /** A pre-accounts per-server slot, which the registry migrates into an account at startup. */
    private fun key(serverId: String, name: String) = AndroidServerRegistry.serverScopedKey(serverId, name)

    private fun savedSession(serverId: String, expiresInMs: Long?): Array<Pair<String, Any?>> = listOfNotNull(
        key(serverId, EncryptedTokenManagerImpl.KEY_ACCESS_TOKEN) to "$serverId-access",
        key(serverId, EncryptedTokenManagerImpl.KEY_REFRESH_TOKEN) to "$serverId-refresh",
        expiresInMs?.let { key(serverId, EncryptedTokenManagerImpl.KEY_TOKEN_EXPIRY) to System.currentTimeMillis() + it },
        expiresInMs?.let { key(serverId, EncryptedTokenManagerImpl.KEY_TOKEN_LIFETIME) to 3_600_000L },
    ).toTypedArray()

    private fun scope(serverId: String, credentialGenerationId: String? = null) =
        AuthScopeSnapshot(serverId, null, "https://$serverId.example", null, credentialGenerationId = credentialGenerationId)

    @Test
    fun aSavedLoginLendsAnInactiveServersCredentialsOnlyUntilTheNextIdentityTransition() = runTest {
        val transitions = DefaultIdentityTransitionBarrier()
        val prefs = seededRegistryPreferences("server-a", *savedSession("server-a", null), *savedSession("server-b", null))
        val registry = AndroidServerRegistry(prefs, identityTransitions = transitions)
        val manager = EncryptedTokenManagerImpl(prefs, registry, transitions)

        val inactive = checkNotNull(manager.savedLoginAuthority("server-b", "kids", "kids-proof"))
        assertEquals(manager.loginSessionId("server-b"), inactive.loginId)
        assertEquals("https://server-b.example", inactive.scope.serverUrl)
        assertEquals("server-b-access", manager.getAccessTokenForScope(inactive.scope))

        transitions.changing(IdentityTransitionKind.PROFILE_SWITCH) {}
        assertNull(manager.getAccessTokenForScope(inactive.scope))

        manager.clearTokens()
        assertNull(manager.savedLoginAuthority("server-a", "kids", "kids-proof"), "signed out there")
    }

    @Test
    fun aSignOutClearsOnlyTheServerAndLoginItWasMeantFor() = runTest {
        val prefs = seededRegistryPreferences("server-a", *savedSession("server-a", null), *savedSession("server-b", null))
        val registry = AndroidServerRegistry(prefs)
        val manager = EncryptedTokenManagerImpl(prefs, registry)
        val loginA = manager.loginSessionId("server-a")

        registry.switchTo("server-b")
        assertFalse(manager.signOutServer("server-a", loginA), "server-b became active first")
        assertEquals("server-b-access", manager.getAccessToken())

        registry.switchTo("server-a")
        // A phone's borrowed identity installed while the sign-out waited.
        manager.beginTemporaryScope(
            TemporaryAuthScope(
                generationId = "overlay-1",
                serverId = "server-a",
                serverUrl = "https://server-a.example",
                accessToken = "overlay-access",
                refreshToken = "overlay-refresh",
                profileId = "overlay-profile",
                profileToken = "overlay-profile-token",
                expiresAtEpochMs = Long.MAX_VALUE,
            ),
        )
        assertFalse(manager.signOutServer("server-a", loginA), "a borrowed identity is active")
        assertEquals("overlay-access", manager.getAccessToken(), "the borrowed identity is not ended here")
        assertEquals(loginA, manager.loginSessionId("server-a"), "the saved login is not signed out")

        manager.endTemporaryScope()
        assertTrue(manager.signOutServer("server-a", loginA))
        assertNull(manager.getAccessToken())
        assertNull(manager.loginSessionId("server-a"))
    }

    @Test
    fun theActiveServersNearExpiryAsksForARefresh() = runTest {
        val near = managerOver(*savedSession("server-a", 10_000L))
        assertTrue(near.accessTokenExpiresWithin(scope("server-a"), marginMs = 60_000L))

        val far = managerOver(*savedSession("server-a", 3_000_000L))
        assertFalse(far.accessTokenExpiresWithin(scope("server-a"), marginMs = 60_000L))
    }

    @Test
    fun aNonActiveServersNearExpiryIsReadFromItsOwnSlot() = runTest {
        val manager = managerOver(*savedSession("server-a", 3_000_000L), *savedSession("server-b", 10_000L))
        assertTrue(manager.accessTokenExpiresWithin(scope("server-b"), marginMs = 60_000L))
        assertFalse(manager.accessTokenExpiresWithin(scope("server-a"), marginMs = 60_000L), "the active slot is separate")
    }

    @Test
    fun aNonActiveServerWithoutAStoredExpiryDoesNotAsk() = runTest {
        val manager = managerOver(*savedSession("server-a", 10_000L), *savedSession("server-b", expiresInMs = null))
        assertFalse(manager.accessTokenExpiresWithin(scope("server-b"), marginMs = 60_000L))
    }

    @Test
    fun anOverlayScopeDoesNotAsk() = runTest {
        val manager = managerOver(*savedSession("server-a", 10_000L))
        assertFalse(manager.accessTokenExpiresWithin(scope("server-a", credentialGenerationId = "overlay-1"), marginMs = 60_000L))
    }

    /** server-a active with [legacySlots] migrated into accounts at startup. */
    private fun managerOver(vararg legacySlots: Pair<String, Any?>): EncryptedTokenManagerImpl {
        val prefs = seededRegistryPreferences("server-a", *legacySlots)
        val registry = AndroidServerRegistry(prefs)
        return EncryptedTokenManagerImpl(prefs, registry)
    }

    /** Signs in to [serverId] the way a login does: a fresh account with its own session. */
    private suspend fun EncryptedTokenManagerImpl.signIn(serverId: String, access: String, refresh: String) {
        replaceAccountSession(serverId = serverId, accessToken = access, refreshToken = refresh, expiresIn = 3600)
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
        vararg legacySlots: Pair<String, Any?>,
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
            *legacySlots,
        )
    }
}
