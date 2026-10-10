package org.siloserver.silo.common.settings

import org.siloserver.silo.model.settings.EffectiveSettingValue
import org.siloserver.silo.model.settings.EffectiveSettingValuesResponse
import org.siloserver.silo.model.settings.EpisodeSpoilerPrefs
import org.siloserver.silo.model.settings.SettingKeys
import org.siloserver.silo.model.settings.SettingScope
import org.siloserver.silo.model.settings.SettingScopeIdentity
import org.siloserver.silo.model.settings.SettingsContractCapabilities
import org.siloserver.silo.model.settings.StoredSettingValue
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.api.SettingsApi
import org.siloserver.silo.repository.SettingsRepository
import io.ktor.client.HttpClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EpisodeSpoilerStoreTest {

    @Test
    fun `a revision 17 server resolves both switches`() = runTest {
        val api = FakeSpoilerSettingsApi(
            values = mapOf(SettingKeys.CATALOG_HIDE_UNWATCHED_EPISODE_IMAGES to JsonPrimitive(true)),
        )
        val store = storeFor(api)

        store.refresh()

        assertEquals(EpisodeSpoilerSupport.Supported, store.state.value.support)
        assertEquals(EpisodeSpoilerPrefs(hideImages = true, hideOverviews = false), store.state.value.prefs)
        assertEquals(listOf(SettingKeys.CATALOG_HIDE_UNWATCHED_EPISODE_IMAGES, SettingKeys.CATALOG_HIDE_UNWATCHED_EPISODE_OVERVIEWS), api.effectiveReads.single())
    }

    @Test
    fun `an older server hides nothing, never reads the keys, and never receives writes`() = runTest {
        val api = FakeSpoilerSettingsApi(
            revision = 16,
            values = mapOf(SettingKeys.CATALOG_HIDE_UNWATCHED_EPISODE_IMAGES to JsonPrimitive(true)),
        )
        val store = storeFor(api)

        store.refresh()
        store.set(EpisodeSpoilerSetting.Images, true)
        runCurrent()

        assertEquals(EpisodeSpoilerSupport.Unsupported, store.state.value.support)
        assertEquals(EpisodeSpoilerPrefs.NONE, store.state.value.prefs)
        assertTrue(api.effectiveReads.isEmpty())
        assertTrue(api.puts.isEmpty())
    }

    @Test
    fun `a server without the contract routes is unsupported`() = runTest {
        val api = FakeSpoilerSettingsApi(capabilities = ApiResult.Error(404, "not_found", "Not Found"))
        val store = storeFor(api)

        store.refresh()

        assertEquals(EpisodeSpoilerSupport.Unsupported, store.state.value.support)
    }

    @Test
    fun `a server at the revision without the keys is unsupported`() = runTest {
        val api = FakeSpoilerSettingsApi(
            effectiveError = ApiResult.Error(404, "unknown_setting", "No setting named catalog.hide_unwatched_episode_images"),
        )
        val store = storeFor(api)

        store.refresh()
        store.set(EpisodeSpoilerSetting.Images, true)
        runCurrent()

        assertEquals(EpisodeSpoilerSupport.Unsupported, store.state.value.support)
        assertEquals(EpisodeSpoilerPrefs.NONE, store.state.value.prefs)
        assertTrue(api.puts.isEmpty())
    }

    @Test
    fun `nothing is written or hidden while support is unknown`() = runTest {
        val api = FakeSpoilerSettingsApi(capabilities = ApiResult.NetworkError(RuntimeException("offline")))
        val store = storeFor(api)

        store.refresh()
        store.set(EpisodeSpoilerSetting.Overviews, true)
        runCurrent()

        assertEquals(EpisodeSpoilerSupport.Unknown, store.state.value.support)
        assertEquals(EpisodeSpoilerPrefs.NONE, store.state.value.prefs)
        assertTrue(api.puts.isEmpty())
        assertTrue(store.lastError.value != null)
    }

    @Test
    fun `a write goes to profile scope and is cached`() = runTest {
        val api = FakeSpoilerSettingsApi()
        val cache = InMemoryEpisodeSpoilerCache()
        val store = storeFor(api, cache)
        store.refresh()

        store.set(EpisodeSpoilerSetting.Overviews, true)
        assertTrue(store.state.value.hideOverviews)
        runCurrent()

        assertEquals(
            listOf(Triple(SettingKeys.CATALOG_HIDE_UNWATCHED_EPISODE_OVERVIEWS, SettingScope.PROFILE, JsonPrimitive(true) as JsonElement)),
            api.puts,
        )
        assertTrue(store.state.value.hideOverviews)
        assertEquals(true, cache.read("https://server.test|profile-1")?.hideOverviews)
    }

    @Test
    fun `a failed write rolls back to the confirmed value`() = runTest {
        val api = FakeSpoilerSettingsApi(failPuts = true)
        val store = storeFor(api)
        store.refresh()

        store.set(EpisodeSpoilerSetting.Images, true)
        runCurrent()

        assertFalse(store.state.value.hideImages)
        assertTrue(store.lastError.value != null)
        assertEquals("Couldn't save spoiler settings: Server error", store.saveError.value)

        api.failPuts = false
        store.set(EpisodeSpoilerSetting.Images, true)
        runCurrent()
        assertEquals(null, store.saveError.value)
    }

    @Test
    fun `a cached answer applies before the network answers and clear drops it`() = runTest {
        val cache = InMemoryEpisodeSpoilerCache()
        cache.write(
            "https://server.test|profile-1",
            EpisodeSpoilerState(EpisodeSpoilerSupport.Supported, hideImages = true, hideOverviews = true),
        )
        val api = FakeSpoilerSettingsApi(capabilities = ApiResult.NetworkError(RuntimeException("offline")))
        val store = storeFor(api, cache)

        store.hydrateIfNeeded()
        assertEquals(EpisodeSpoilerPrefs(hideImages = true, hideOverviews = true), store.state.value.prefs)

        store.clear()
        assertEquals(EpisodeSpoilerPrefs.NONE, store.state.value.prefs)
    }

    @Test
    fun `hydrating for another server re-resolves without a clear`() = runTest {
        // Add Server → sign in → pick profile: nothing calls clear(), and the
        // old server's Unsupported answer must not survive the switch.
        var serverUrl = "https://old.test"
        var profileId = "old-profile"
        val api = FakeSpoilerSettingsApi(revision = 16)
        val store = storeFor(api, serverUrl = { serverUrl }, profileId = { profileId })
        store.hydrateIfNeeded()
        assertEquals(EpisodeSpoilerSupport.Unsupported, store.state.value.support)

        serverUrl = "https://new.test"
        profileId = "new-profile"
        api.serve(revision = 17, values = bothOn)
        store.hydrateIfNeeded()

        assertEquals(EpisodeSpoilerPrefs(hideImages = true, hideOverviews = true), store.state.value.prefs)
    }

    @Test
    fun `an active server or profile change resets and re-resolves`() = runTest {
        var serverUrl = "https://old.test"
        val identityChanges = MutableSharedFlow<Unit>()
        val api = FakeSpoilerSettingsApi(revision = 16)
        val store = storeFor(api, serverUrl = { serverUrl }, identityChanges = identityChanges)
        runCurrent()
        store.hydrateIfNeeded()

        serverUrl = "https://new.test"
        api.serve(revision = 17, values = bothOn)
        identityChanges.emit(Unit)
        runCurrent()

        assertEquals(EpisodeSpoilerPrefs(hideImages = true, hideOverviews = true), store.state.value.prefs)
    }

    @Test
    fun `refresh preserves a toggle while its write is pending`() = runTest {
        val api = FakeSpoilerSettingsApi()
        val store = storeFor(api)
        store.refresh()
        val finishWrite = CompletableDeferred<Unit>()
        api.beforePut = { finishWrite.await() }

        store.set(EpisodeSpoilerSetting.Images, true)
        runCurrent()
        store.refresh()

        assertTrue(store.state.value.hideImages)
        finishWrite.complete(Unit)
        runCurrent()
        assertTrue(store.state.value.hideImages)
    }

    @Test
    fun `a completed write never caches its values for a later profile`() = runTest {
        var profileId = "profile-1"
        var delayIdentityRead = false
        val finishIdentityRead = CompletableDeferred<Unit>()
        val api = FakeSpoilerSettingsApi()
        val cache = InMemoryEpisodeSpoilerCache()
        val store = storeFor(api, cache, profileId = {
            if (delayIdentityRead) finishIdentityRead.await()
            profileId
        })
        store.refresh()
        delayIdentityRead = true
        store.set(EpisodeSpoilerSetting.Images, true)
        runCurrent()

        profileId = "profile-2"
        delayIdentityRead = false
        store.clear()
        store.hydrateIfNeeded()
        finishIdentityRead.complete(Unit)
        runCurrent()

        assertEquals(false, cache.read("https://server.test|profile-2")?.hideImages)
        assertEquals(true, cache.read("https://server.test|profile-1")?.hideImages)
    }

    @Test
    fun `a second identity change cancels hydration and loads its own cache`() = runTest {
        var serverUrl = "https://old.test"
        val changes = MutableSharedFlow<Unit>()
        val api = FakeSpoilerSettingsApi()
        val cache = InMemoryEpisodeSpoilerCache()
        cache.write("https://new.test|profile-1", EpisodeSpoilerState(
            EpisodeSpoilerSupport.Supported, hideImages = true, hideOverviews = true,
        ))
        val store = storeFor(api, cache, serverUrl = { serverUrl }, identityChanges = changes)
        runCurrent()
        store.refresh()
        val oldHydration = CompletableDeferred<Unit>()
        api.beforeCapabilities = { oldHydration.await() }
        changes.emit(Unit)
        runCurrent()

        serverUrl = "https://new.test"
        api.beforeCapabilities = { }
        api.serve(revision = 17, values = bothOn)
        backgroundScope.launch { changes.emit(Unit) }
        runCurrent()

        assertEquals(EpisodeSpoilerPrefs(true, true), store.state.value.prefs)
    }

    @Test
    fun `an identity change shows its cache while another caller's refresh is in flight`() = runTest {
        var serverUrl = "https://old.test"
        val changes = MutableSharedFlow<Unit>()
        val api = FakeSpoilerSettingsApi()
        val cache = InMemoryEpisodeSpoilerCache()
        cache.write("https://new.test|profile-1", EpisodeSpoilerState(
            EpisodeSpoilerSupport.Supported, hideImages = true, hideOverviews = true,
        ))
        val store = storeFor(api, cache, serverUrl = { serverUrl }, identityChanges = changes)
        runCurrent()
        val staleCapabilities = CompletableDeferred<Unit>()
        api.beforeCapabilities = { staleCapabilities.await() }
        // The Watch Next worker, say, refreshing the old server.
        backgroundScope.launch { store.refresh() }
        runCurrent()

        serverUrl = "https://new.test"
        api.beforeCapabilities = { }
        backgroundScope.launch { changes.emit(Unit) }
        runCurrent()
        assertEquals(EpisodeSpoilerPrefs(true, true), store.state.value.prefs)

        staleCapabilities.complete(Unit)
        runCurrent()
        // Only the new server's refresh read the values.
        assertEquals(1, api.effectiveReads.size)
        assertEquals(EpisodeSpoilerPrefs.NONE, store.state.value.prefs)
    }

    @Test
    fun `a queued write stays pinned to the profile that made it`() = runTest {
        val original = authorityFor("profile-1")
        var active = original
        val api = FakeSpoilerSettingsApi()
        val store = storeFor(api, captureAuthority = { active })
        store.refresh()
        val finishFirstWrite = CompletableDeferred<Unit>()
        api.beforePut = { finishFirstWrite.await() }
        store.set(EpisodeSpoilerSetting.Images, true)
        runCurrent()
        // Queued behind the first write while the active profile changes.
        store.set(EpisodeSpoilerSetting.Overviews, true)
        runCurrent()

        active = authorityFor("profile-2")
        api.beforePut = { }
        finishFirstWrite.complete(Unit)
        runCurrent()

        assertEquals(listOf<AuthScopeSnapshot?>(original, original), api.putAuthorities.toList())
        assertEquals(listOf<AuthScopeSnapshot?>(original), api.effectiveAuthorities.toList())
    }

    private fun authorityFor(profileId: String) = AuthScopeSnapshot(
        serverId = "server", profileId = profileId, serverUrl = "https://server.test", profileToken = "token-$profileId",
    )

    private val bothOn = mapOf(
        SettingKeys.CATALOG_HIDE_UNWATCHED_EPISODE_IMAGES to JsonPrimitive(true),
        SettingKeys.CATALOG_HIDE_UNWATCHED_EPISODE_OVERVIEWS to JsonPrimitive(true),
    )

    private fun TestScope.storeFor(
        api: FakeSpoilerSettingsApi,
        cache: EpisodeSpoilerCache = InMemoryEpisodeSpoilerCache(),
        serverUrl: suspend () -> String? = { "https://server.test" },
        profileId: suspend () -> String? = { "profile-1" },
        identityChanges: Flow<Unit> = emptyFlow(),
        captureAuthority: suspend () -> AuthScopeSnapshot? = { null },
    ) = DefaultEpisodeSpoilerStore.forTest(
        repository = SettingsRepository(api),
        scope = backgroundScope,
        getActiveProfileId = profileId,
        getServerUrl = serverUrl,
        captureAuthority = captureAuthority,
        cache = cache,
        identityChanges = identityChanges,
    )
}

private class FakeSpoilerSettingsApi(
    revision: Int = 17,
    var capabilities: ApiResult<SettingsContractCapabilities> = capabilitiesAt(revision),
    private var values: Map<String, JsonElement> = emptyMap(),
    var failPuts: Boolean = false,
    private val effectiveError: ApiResult.Error? = null,
) : SettingsApi(
    org.siloserver.silo.network.apiv2.SettingsV2Api(
        HttpClient(),
        org.siloserver.silo.network.TokenManagerImpl(),
        org.siloserver.silo.network.apiv2.ApiV2Gate.Unrestricted,
    ),
) {
    val puts = mutableListOf<Triple<String, SettingScope, JsonElement>>()
    val putAuthorities = mutableListOf<AuthScopeSnapshot?>()
    val effectiveReads = mutableListOf<List<String>>()
    val effectiveAuthorities = mutableListOf<AuthScopeSnapshot?>()
    var beforeCapabilities: suspend () -> Unit = { }
    var beforePut: suspend () -> Unit = { }

    /** Become a different server: [revision] and stored [values]. */
    fun serve(revision: Int, values: Map<String, JsonElement>) {
        capabilities = capabilitiesAt(revision)
        this.values = values
    }

    override suspend fun getContractCapabilities(): ApiResult<SettingsContractCapabilities> {
        beforeCapabilities()
        return capabilities
    }

    override suspend fun getEffectiveValues(
        keys: List<String>,
        libraryIds: List<Int>,
        seriesIds: List<String>,
        authority: org.siloserver.silo.network.AuthScopeSnapshot?,
    ): ApiResult<EffectiveSettingValuesResponse> {
        effectiveReads += keys
        effectiveAuthorities += authority
        effectiveError?.let { return it }
        return ApiResult.Success(
            EffectiveSettingValuesResponse(
                settings = keys.map { key ->
                    EffectiveSettingValue(
                        key = key,
                        value = values[key] ?: JsonPrimitive(false),
                        source = if (key in values) "profile" else "default",
                    )
                },
            ),
        )
    }

    override suspend fun putValue(
        key: String,
        scope: SettingScopeIdentity,
        value: JsonElement,
        profileId: String?,
        authority: org.siloserver.silo.network.AuthScopeSnapshot?,
    ): ApiResult<StoredSettingValue> {
        puts += Triple(key, scope.scope, value)
        putAuthorities += authority
        beforePut()
        if (failPuts) return ApiResult.Error(500, "boom", "Server error")
        return ApiResult.Success(StoredSettingValue(key = key, scope = scope.scope.wire))
    }
}

private fun capabilitiesAt(revision: Int): ApiResult<SettingsContractCapabilities> = ApiResult.Success(
    SettingsContractCapabilities(apiVersion = 1, manifestRevision = revision, supportsBatchedEffective = true),
)
