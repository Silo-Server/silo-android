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
import org.siloserver.silo.network.api.SettingsApi
import org.siloserver.silo.repository.SettingsRepository
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
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
    fun `a revision 15 server resolves both switches`() = runTest {
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
            revision = 14,
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
        val api = FakeSpoilerSettingsApi(revision = 14)
        val store = storeFor(api, serverUrl = { serverUrl }, profileId = { profileId })
        store.hydrateIfNeeded()
        assertEquals(EpisodeSpoilerSupport.Unsupported, store.state.value.support)

        serverUrl = "https://new.test"
        profileId = "new-profile"
        api.serve(revision = 15, values = bothOn)
        store.hydrateIfNeeded()

        assertEquals(EpisodeSpoilerPrefs(hideImages = true, hideOverviews = true), store.state.value.prefs)
    }

    @Test
    fun `an active server or profile change resets and re-resolves`() = runTest {
        var serverUrl = "https://old.test"
        val identityChanges = MutableSharedFlow<Unit>()
        val api = FakeSpoilerSettingsApi(revision = 14)
        val store = storeFor(api, serverUrl = { serverUrl }, identityChanges = identityChanges)
        runCurrent()
        store.hydrateIfNeeded()

        serverUrl = "https://new.test"
        api.serve(revision = 15, values = bothOn)
        identityChanges.emit(Unit)
        runCurrent()

        assertEquals(EpisodeSpoilerPrefs(hideImages = true, hideOverviews = true), store.state.value.prefs)
    }

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
    ) = DefaultEpisodeSpoilerStore.forTest(
        repository = SettingsRepository(api),
        scope = backgroundScope,
        getActiveProfileId = profileId,
        getServerUrl = serverUrl,
        cache = cache,
        identityChanges = identityChanges,
    )
}

private class FakeSpoilerSettingsApi(
    revision: Int = 15,
    var capabilities: ApiResult<SettingsContractCapabilities> = capabilitiesAt(revision),
    private var values: Map<String, JsonElement> = emptyMap(),
    private val failPuts: Boolean = false,
) : SettingsApi(
    org.siloserver.silo.network.apiv2.SettingsV2Api(
        HttpClient(),
        org.siloserver.silo.network.TokenManagerImpl(),
        org.siloserver.silo.network.apiv2.ApiV2Gate.Unrestricted,
    ),
) {
    val puts = mutableListOf<Triple<String, SettingScope, JsonElement>>()
    val effectiveReads = mutableListOf<List<String>>()

    /** Become a different server: [revision] and stored [values]. */
    fun serve(revision: Int, values: Map<String, JsonElement>) {
        capabilities = capabilitiesAt(revision)
        this.values = values
    }

    override suspend fun getContractCapabilities(): ApiResult<SettingsContractCapabilities> = capabilities

    override suspend fun getEffectiveValues(
        keys: List<String>,
        libraryIds: List<Int>,
        seriesIds: List<String>,
        authority: org.siloserver.silo.network.AuthScopeSnapshot?,
    ): ApiResult<EffectiveSettingValuesResponse> {
        effectiveReads += keys
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
        if (failPuts) return ApiResult.Error(500, "boom", "Server error")
        return ApiResult.Success(StoredSettingValue(key = key, scope = scope.scope.wire))
    }
}

private fun capabilitiesAt(revision: Int): ApiResult<SettingsContractCapabilities> = ApiResult.Success(
    SettingsContractCapabilities(apiVersion = 1, manifestRevision = revision, supportsBatchedEffective = true),
)
