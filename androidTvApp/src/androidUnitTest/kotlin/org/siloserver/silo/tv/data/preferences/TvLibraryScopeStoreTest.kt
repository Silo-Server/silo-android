package org.siloserver.silo.tv.data.preferences

import android.content.ContextWrapper
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import org.siloserver.silo.model.personal.UserLibrary
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.DefaultIdentityTransitionBarrier
import org.siloserver.silo.network.IdentityTransitionKind
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.TokenManagerImpl
import org.siloserver.silo.tv.ui.shell.TvLibraryTabType
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Exercises scope resolution plus account-safe, reactive preference storage. */
class TvLibraryScopeStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun lib(id: Int, type: String, name: String = "lib$id") =
        UserLibrary(id = id, name = name, type = type)

    private val movieA = lib(1, "movies", "Movies A")
    private val movieB = lib(2, "movie", "Movies B")
    private val series = lib(3, "series", "Series")
    private val movies = listOf(movieA, movieB)

    @Test
    fun `stored id resolves to that library when present`() {
        assertEquals(
            movieB,
            TvLibraryScopeStore.resolve(storedId = 2, type = TvLibraryTabType.Movies, libraries = movies),
        )
    }

    @Test
    fun `null stored id falls back to first of type`() {
        assertEquals(
            movieA,
            TvLibraryScopeStore.resolve(storedId = null, type = TvLibraryTabType.Movies, libraries = movies),
        )
    }

    @Test
    fun `stale stored id falls back to first of type`() {
        assertEquals(
            movieA,
            TvLibraryScopeStore.resolve(storedId = 999, type = TvLibraryTabType.Movies, libraries = movies),
        )
    }

    @Test
    fun `only libraries of the type are considered`() {
        // A stored id pointing at a series library must not resolve under Movies.
        val mixed = listOf(series, movieA, movieB)
        assertEquals(
            movieA,
            TvLibraryScopeStore.resolve(storedId = 3, type = TvLibraryTabType.Movies, libraries = mixed),
        )
    }

    @Test
    fun `no libraries of the type resolves to null`() {
        assertNull(
            TvLibraryScopeStore.resolve(storedId = 1, type = TvLibraryTabType.Series, libraries = movies),
        )
        assertNull(
            TvLibraryScopeStore.resolve(storedId = null, type = TvLibraryTabType.Music, libraries = movies),
        )
    }

    @Test
    fun `audiobook visibility flow rekeys after profile switch`() = runBlocking {
        val transitions = DefaultIdentityTransitionBarrier()
        val tokens = SnapshotTokenManager(scope(profileId = "profile-a"))
        val store = store(tokens, transitions)
        store.setShowAudiobooksTab(true)

        val emissions = Channel<Boolean>(Channel.UNLIMITED)
        val collector = launch(Dispatchers.Default) {
            store.showAudiobooksTabFlow().collect(emissions::send)
        }
        try {
            assertTrue(withTimeout(5_000) { emissions.receive() })

            transitions.changing(IdentityTransitionKind.PROFILE_SWITCH) {
                tokens.currentSnapshot = scope(profileId = "profile-b")
            }

            assertFalse(withTimeout(5_000) { emissions.receive() })
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun `reads and writes use one atomic snapshot identity`() = runBlocking {
        val transitions = DefaultIdentityTransitionBarrier()
        val tokens = SnapshotTokenManager(
            initial = scope(
                profileId = "snapshot-profile",
                serverId = "snapshot-server",
            ),
            legacyProfileId = "different-profile",
            legacyServerId = "different-server",
        )
        val store = store(tokens, transitions)

        store.setShowAudiobooksTab(true)
        store.setSelectedLibraryId(2, TvLibraryTabType.Movies)

        assertTrue(store.getShowAudiobooksTab())
        assertEquals(2, store.getSelectedLibraryId(TvLibraryTabType.Movies))
        assertEquals(0, tokens.separateIdentityReadCount)

        tokens.currentSnapshot = scope(
            profileId = "snapshot-profile",
            serverId = "different-server",
        )
        assertFalse(store.getShowAudiobooksTab())
        assertNull(store.getSelectedLibraryId(TvLibraryTabType.Movies))
    }

    @Test
    fun `replacement account cannot inherit the previous owners preferences`() = runBlocking {
        val transitions = DefaultIdentityTransitionBarrier()
        val ownerA = scope(profileId = "shared-profile", credentialOwnerId = "owner-a")
        val ownerB = scope(profileId = "shared-profile", credentialOwnerId = "owner-b")
        val tokens = SnapshotTokenManager(ownerA)
        val store = store(tokens, transitions)
        store.setShowAudiobooksTab(true)
        store.setSelectedLibraryId(2, TvLibraryTabType.Movies)

        val emissions = Channel<Boolean>(Channel.UNLIMITED)
        val collector = launch(Dispatchers.Default) {
            store.showAudiobooksTabFlow().collect(emissions::send)
        }
        try {
            assertTrue(withTimeout(5_000) { emissions.receive() })

            transitions.changing(IdentityTransitionKind.SIGN_IN) {
                tokens.currentSnapshot = ownerB
            }

            assertFalse(withTimeout(5_000) { emissions.receive() })
            assertNull(store.getSelectedLibraryId(TvLibraryTabType.Movies))

            transitions.changing(IdentityTransitionKind.SIGN_IN) {
                tokens.currentSnapshot = ownerA
            }

            assertTrue(withTimeout(5_000) { emissions.receive() })
            assertEquals(2, store.getSelectedLibraryId(TvLibraryTabType.Movies))
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun `temporary overlay enter and exit preserve both preference namespaces`() = runBlocking {
        val transitions = DefaultIdentityTransitionBarrier()
        val persistent = scope(profileId = "profile-a", credentialOwnerId = "owner-a")
        val temporary = scope(
            profileId = "profile-a",
            credentialOwnerId = null,
            credentialGenerationId = "temporary-generation-a",
        )
        val tokens = SnapshotTokenManager(persistent)
        val store = store(tokens, transitions)
        store.setSelectedLibraryId(1, TvLibraryTabType.Movies)

        val emissions = Channel<Boolean>(Channel.UNLIMITED)
        val collector = launch(Dispatchers.Default) {
            store.showAudiobooksTabFlow().collect(emissions::send)
        }
        try {
            assertFalse(withTimeout(5_000) { emissions.receive() })

            transitions.changing(IdentityTransitionKind.TEMPORARY_SCOPE_BEGIN) {
                tokens.currentSnapshot = temporary
            }
            assertNull(store.getSelectedLibraryId(TvLibraryTabType.Movies))

            store.setShowAudiobooksTab(true)
            assertTrue(withTimeout(5_000) { emissions.receive() })
            store.setSelectedLibraryId(2, TvLibraryTabType.Movies)

            transitions.changing(IdentityTransitionKind.TEMPORARY_SCOPE_END) {
                tokens.currentSnapshot = persistent
            }
            assertFalse(withTimeout(5_000) { emissions.receive() })
            assertEquals(1, store.getSelectedLibraryId(TvLibraryTabType.Movies))

            transitions.changing(IdentityTransitionKind.TEMPORARY_SCOPE_BEGIN) {
                tokens.currentSnapshot = temporary
            }
            assertTrue(withTimeout(5_000) { emissions.receive() })
            assertEquals(2, store.getSelectedLibraryId(TvLibraryTabType.Movies))
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun `visibility flow hides the previous owner before loading the next owner`() = runBlocking {
        val transitions = DefaultIdentityTransitionBarrier()
        val persistent = scope(profileId = "profile-a", credentialOwnerId = "owner-a")
        val temporary = scope(
            profileId = "profile-a",
            credentialOwnerId = null,
            credentialGenerationId = "temporary-generation-a",
        )
        val tokens = SnapshotTokenManager(persistent)
        val store = store(tokens, transitions)
        store.setShowAudiobooksTab(true)
        tokens.currentSnapshot = temporary
        store.setShowAudiobooksTab(true)
        tokens.currentSnapshot = persistent

        val emissions = Channel<Boolean>(Channel.UNLIMITED)
        val collector = launch(Dispatchers.Default) {
            store.showAudiobooksTabFlow().collect(emissions::send)
        }
        try {
            assertTrue(withTimeout(5_000) { emissions.receive() })

            transitions.changing(IdentityTransitionKind.TEMPORARY_SCOPE_BEGIN) {
                tokens.currentSnapshot = temporary
            }

            assertFalse(withTimeout(5_000) { emissions.receive() })
            assertTrue(withTimeout(5_000) { emissions.receive() })
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun `subscriber created inside transition stays hidden until real did change`() = runBlocking {
        val transitions = DefaultIdentityTransitionBarrier()
        val persistent = scope(profileId = "profile-a", credentialOwnerId = "owner-a")
        val temporary = scope(
            profileId = "profile-a",
            credentialOwnerId = null,
            credentialGenerationId = "temporary-generation-a",
        )
        val tokens = SnapshotTokenManager(persistent)
        val store = store(tokens, transitions)
        store.setShowAudiobooksTab(true)
        tokens.currentSnapshot = temporary
        store.setShowAudiobooksTab(true)
        tokens.currentSnapshot = persistent

        val emissions = Channel<Boolean>(Channel.UNLIMITED)
        var collector: Job? = null
        try {
            transitions.changing(IdentityTransitionKind.TEMPORARY_SCOPE_BEGIN) {
                // Start collecting after WILL_CHANGE was published but before
                // the identity snapshot changes. The replayed WILL_CHANGE must
                // suppress the previous owner's true value.
                collector = launch(Dispatchers.Default) {
                    store.showAudiobooksTabFlow().collect(emissions::send)
                }
                assertFalse(withTimeout(5_000) { emissions.receive() })
                tokens.currentSnapshot = temporary
            }

            assertTrue(withTimeout(5_000) { emissions.receive() })
        } finally {
            collector?.cancel()
        }
    }

    @Test
    fun `same owner survives profile token and snapshot generation changes`() = runBlocking {
        val transitions = DefaultIdentityTransitionBarrier()
        val initial = scope(
            profileId = "profile-a",
            credentialOwnerId = "durable-owner",
            profileToken = "pin-token-a",
            identityGeneration = 1,
            credentialEpoch = 10,
        )
        val tokens = SnapshotTokenManager(initial)
        val store = store(tokens, transitions)
        store.setShowAudiobooksTab(true)
        store.setSelectedLibraryId(2, TvLibraryTabType.Movies)

        tokens.currentSnapshot = initial.copy(
            profileToken = "pin-token-b",
            identityGeneration = 99,
            credentialEpoch = 11,
        )

        assertTrue(store.getShowAudiobooksTab())
        assertEquals(2, store.getSelectedLibraryId(TvLibraryTabType.Movies))
    }

    @Test
    fun `preferences written before owner namespacing survive the upgrade`() = runBlocking {
        val factory = TestStoreFactory(tempFolder.root)
        factory.seedLegacy(profileId = "profile-a", movieScopeId = 7, showAudiobooks = true)

        val tokens = SnapshotTokenManager(
            scope(profileId = "profile-a", credentialOwnerId = "owner-a"),
        )
        val store = store(tokens, DefaultIdentityTransitionBarrier(), factory)

        assertTrue(store.getShowAudiobooksTab())
        assertEquals(7, store.getSelectedLibraryId(TvLibraryTabType.Movies))
        factory.shutdown()
    }

    @Test
    fun `a second owner on the same profile does not inherit legacy preferences`() = runBlocking {
        val factory = TestStoreFactory(tempFolder.root)
        factory.seedLegacy(profileId = "shared-profile", movieScopeId = 7, showAudiobooks = true)

        val tokens = SnapshotTokenManager(
            scope(profileId = "shared-profile", credentialOwnerId = "owner-a"),
        )
        val store = store(tokens, DefaultIdentityTransitionBarrier(), factory)
        assertEquals(7, store.getSelectedLibraryId(TvLibraryTabType.Movies))
        assertTrue(store.getShowAudiobooksTab())

        // A genuinely different account reusing the profile id starts clean.
        tokens.currentSnapshot = scope(profileId = "shared-profile", credentialOwnerId = "owner-b")
        assertNull(store.getSelectedLibraryId(TvLibraryTabType.Movies))
        assertFalse(store.getShowAudiobooksTab())

        // ...and claiming stays with the owner that upgraded.
        tokens.currentSnapshot = scope(profileId = "shared-profile", credentialOwnerId = "owner-a")
        assertEquals(7, store.getSelectedLibraryId(TvLibraryTabType.Movies))
        factory.shutdown()
    }

    @Test
    fun `absent legacy file is a clean no-op and is not created`() = runBlocking {
        val factory = TestStoreFactory(tempFolder.root)
        val tokens = SnapshotTokenManager(
            scope(profileId = "profile-a", credentialOwnerId = "owner-a"),
        )
        val store = store(tokens, DefaultIdentityTransitionBarrier(), factory)

        assertNull(store.getSelectedLibraryId(TvLibraryTabType.Movies))
        assertFalse(store.getShowAudiobooksTab())
        assertFalse(legacyFile("profile-a").exists())
        factory.shutdown()
    }

    @Test
    fun `legacy import runs once and does not clobber a later write`() = runBlocking {
        val firstRun = TestStoreFactory(tempFolder.root)
        firstRun.seedLegacy(profileId = "profile-a", movieScopeId = 7, showAudiobooks = true)
        val first = store(
            SnapshotTokenManager(scope(profileId = "profile-a", credentialOwnerId = "owner-a")),
            DefaultIdentityTransitionBarrier(),
            firstRun,
        )
        assertEquals(7, first.getSelectedLibraryId(TvLibraryTabType.Movies))
        assertTrue(first.getShowAudiobooksTab())

        first.setSelectedLibraryId(9, TvLibraryTabType.Movies)
        first.setShowAudiobooksTab(false)
        // Release every file so the "next launch" can reopen them.
        firstRun.shutdown()

        val secondRun = TestStoreFactory(tempFolder.root)
        val second = store(
            SnapshotTokenManager(scope(profileId = "profile-a", credentialOwnerId = "owner-a")),
            DefaultIdentityTransitionBarrier(),
            secondRun,
        )
        assertEquals(9, second.getSelectedLibraryId(TvLibraryTabType.Movies))
        assertFalse(second.getShowAudiobooksTab())
        secondRun.shutdown()
    }

    @Test
    fun `temporary overlay neither inherits nor consumes the legacy import`() = runBlocking {
        val factory = TestStoreFactory(tempFolder.root)
        factory.seedLegacy(profileId = "profile-a", movieScopeId = 7, showAudiobooks = true)

        val tokens = SnapshotTokenManager(
            scope(
                profileId = "profile-a",
                credentialOwnerId = null,
                credentialGenerationId = "temporary-generation-a",
            ),
        )
        val store = store(tokens, DefaultIdentityTransitionBarrier(), factory)
        assertNull(store.getSelectedLibraryId(TvLibraryTabType.Movies))
        assertFalse(store.getShowAudiobooksTab())

        // The durable owner still gets the one import the overlay must not spend.
        tokens.currentSnapshot = scope(profileId = "profile-a", credentialOwnerId = "owner-a")
        assertEquals(7, store.getSelectedLibraryId(TvLibraryTabType.Movies))
        assertTrue(store.getShowAudiobooksTab())
        factory.shutdown()
    }

    private fun legacyFile(profileId: String) = File(
        tempFolder.root,
        "${TvLibraryScopeStore.legacyFileNameFor(profileId)}.preferences_pb",
    )

    private suspend fun TestStoreFactory.seedLegacy(
        profileId: String,
        serverId: String = "server-a",
        movieScopeId: Int? = null,
        showAudiobooks: Boolean? = null,
    ) {
        // Written with the pre-upgrade key spellings on purpose: the wire
        // format of the old file is exactly what the import has to understand.
        create(TvLibraryScopeStore.legacyFileNameFor(profileId), emptyList()).edit { prefs ->
            movieScopeId?.let { prefs[intPreferencesKey("scope_${serverId}_movies")] = it }
            showAudiobooks?.let { prefs[booleanPreferencesKey("show_audiobooks_$serverId")] = it }
        }
    }

    /**
     * Mirrors the production factory: one live DataStore per file (DataStore
     * rejects a second instance over the same file, and the legacy import
     * opens a file these tests also seed) and migrations forwarded through.
     * Each store owns a cancellable scope so a restart can be simulated.
     */
    private class TestStoreFactory(private val root: File) {
        private val stores = mutableMapOf<String, DataStore<Preferences>>()
        private val scopes = mutableListOf<CoroutineScope>()

        fun create(
            fileName: String,
            migrations: List<DataMigration<Preferences>>,
        ): DataStore<Preferences> = synchronized(stores) {
            stores.getOrPut(fileName) {
                val scope = CoroutineScope(Dispatchers.IO + Job())
                scopes += scope
                PreferenceDataStoreFactory.create(
                    migrations = migrations,
                    scope = scope,
                    produceFile = { File(root, "$fileName.preferences_pb") },
                )
            }
        }

        /** Releases every file so a restarted factory can reopen them. */
        suspend fun shutdown() {
            val active = synchronized(stores) {
                val running = scopes.toList()
                stores.clear()
                scopes.clear()
                running
            }
            active.forEach { it.coroutineContext[Job]!!.cancelAndJoin() }
        }
    }

    private fun store(
        tokens: TokenManager,
        transitions: DefaultIdentityTransitionBarrier,
        factory: TestStoreFactory = TestStoreFactory(tempFolder.root),
    ) = TvLibraryScopeStore(
        context = object : ContextWrapper(null) {},
        tokenManager = tokens,
        identityTransitions = transitions,
        dataStoreFactory = { fileName, migrations -> factory.create(fileName, migrations) },
    )

    private fun scope(
        profileId: String,
        serverId: String = "server-a",
        credentialOwnerId: String? = "owner-a",
        credentialGenerationId: String? = null,
        profileToken: String? = "profile-token",
        identityGeneration: Long = 1,
        credentialEpoch: Long = 1,
    ) = AuthScopeSnapshot(
        serverId = serverId,
        profileId = profileId,
        serverUrl = "https://$serverId.example.test",
        profileToken = profileToken,
        credentialGenerationId = credentialGenerationId,
        identityGeneration = identityGeneration,
        credentialOwnerId = credentialOwnerId,
        credentialEpoch = credentialEpoch,
    )

    private class SnapshotTokenManager(
        initial: AuthScopeSnapshot,
        private val legacyProfileId: String? = initial.profileId,
        private val legacyServerId: String? = initial.serverId,
    ) : TokenManager by TokenManagerImpl() {
        @Volatile
        var currentSnapshot: AuthScopeSnapshot? = initial

        var separateIdentityReadCount: Int = 0
            private set

        override suspend fun snapshotCurrentScope(): AuthScopeSnapshot? = currentSnapshot

        override suspend fun getProfileId(): String? {
            separateIdentityReadCount += 1
            return legacyProfileId
        }

        override suspend fun getCurrentServerId(): String? {
            separateIdentityReadCount += 1
            return legacyServerId
        }
    }
}
