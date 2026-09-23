package org.siloserver.silo.common.settings

import org.siloserver.silo.domain.settings.SeekIntervalController
import org.siloserver.silo.model.settings.EffectiveSettingValue
import org.siloserver.silo.model.settings.EffectiveSettingValuesResponse
import org.siloserver.silo.model.settings.LegacyAudiobookIntervals
import org.siloserver.silo.model.settings.SeekDirection
import org.siloserver.silo.model.settings.SeekImportOutcome
import org.siloserver.silo.model.settings.SeekIntervalPair
import org.siloserver.silo.model.settings.SeekIntervalState
import org.siloserver.silo.model.settings.SeekIntervalSupport
import org.siloserver.silo.model.settings.SeekMedia
import org.siloserver.silo.model.settings.SettingKeys
import org.siloserver.silo.model.settings.SettingScopeIdentity
import org.siloserver.silo.model.settings.SettingsContractCapabilities
import org.siloserver.silo.model.settings.StoredSettingValue
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.api.SettingsApi
import org.siloserver.silo.repository.SettingsRepository
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeekIntervalStoreTest {

    @Test
    fun `refresh adopts the profile values on a revision 9 server`() = runTest {
        val api = FakeSeekSettingsApi(values = mapOf(SettingKeys.PLAYER_VIDEO_SKIP_FORWARD_SECONDS to 60))
        val store = storeFor(api)

        store.refresh()

        assertEquals(
            SeekIntervalState(
                support = SeekIntervalSupport.Supported,
                videoIntervals = SeekIntervalPair(10, 60),
                audiobookIntervals = SeekIntervalPair(10, 30),
            ),
            store.state.value,
        )
    }

    @Test
    fun `an older server is unsupported and never receives writes`() = runTest {
        val api = FakeSeekSettingsApi(capabilities = ApiResult.Error(404, "not_found", "Not Found"))
        val store = storeFor(api)

        store.refresh()
        val save = store.save(SeekMedia.Video, SeekDirection.Back, 15)
        val import = store.importLegacyAudiobook(LegacyAudiobookIntervals(15, 60))

        assertEquals(SeekIntervalSupport.Unsupported, store.state.value.support)
        assertIs<SeekIntervalController.SaveResult.Failed>(save)
        assertNull(import)
        assertTrue(api.puts.isEmpty())
    }

    @Test
    fun `nothing is written while support is still unknown`() = runTest {
        val api = FakeSeekSettingsApi()
        val store = storeFor(api)

        val save = store.save(SeekMedia.Audiobook, SeekDirection.Forward, 45)

        assertIs<SeekIntervalController.SaveResult.Failed>(save)
        assertEquals(SeekIntervalSupport.Unknown, store.state.value.support)
        assertTrue(api.puts.isEmpty())
    }

    @Test
    fun `a transient failure keeps the last known answer`() = runTest {
        val api = FakeSeekSettingsApi(values = mapOf(SettingKeys.PLAYER_VIDEO_SKIP_BACK_SECONDS to 5))
        val store = storeFor(api)
        store.refresh()

        api.capabilities = ApiResult.NetworkError(RuntimeException("offline"))
        store.refresh()

        assertEquals(SeekIntervalSupport.Supported, store.state.value.support)
        assertEquals(5, store.state.value.videoIntervals.backSeconds)
    }

    @Test
    fun `a failed first check is unavailable, allows device editing, and writes nothing`() = runTest {
        val api = FakeSeekSettingsApi(capabilities = ApiResult.NetworkError(RuntimeException("offline")))
        val cache = InMemorySeekIntervalCache()
        val store = storeFor(api, cache)

        store.refresh()
        val save = store.save(SeekMedia.Audiobook, SeekDirection.Back, 15)
        val import = store.importLegacyAudiobook(LegacyAudiobookIntervals(15, 60))

        assertEquals(SeekIntervalSupport.Unavailable, store.state.value.support)
        assertTrue(store.state.value.allowsLegacyAudiobookEditing)
        assertEquals(LEGACY_PAIR, store.state.value.audiobook(LEGACY_PAIR))
        assertIs<SeekIntervalController.SaveResult.Failed>(save)
        assertNull(import)
        assertTrue(api.puts.isEmpty())
        assertNull(cache.read("https://server.test|profile-1"))
        assertTrue(store.lastError.value != null)
    }

    @Test
    fun `a later successful check replaces unavailable`() = runTest {
        val api = FakeSeekSettingsApi(
            capabilities = ApiResult.NetworkError(RuntimeException("offline")),
            values = mapOf(SettingKeys.PLAYER_AUDIOBOOK_SKIP_BACK_SECONDS to 45),
        )
        val store = storeFor(api)
        store.refresh()

        api.capabilities = ApiResult.Success(
            SettingsContractCapabilities(apiVersion = 1, manifestRevision = 9, supportsBatchedEffective = true),
        )
        store.refresh()

        assertEquals(SeekIntervalSupport.Supported, store.state.value.support)
        assertEquals(45, store.state.value.audiobookIntervals.backSeconds)
        assertNull(store.lastError.value)
    }

    @Test
    fun `refresh after a reset restores the cached answer while offline`() = runTest {
        val api = FakeSeekSettingsApi(values = mapOf(SettingKeys.PLAYER_VIDEO_SKIP_FORWARD_SECONDS to 90))
        val cache = InMemorySeekIntervalCache()
        val store = storeFor(api, cache)
        store.refresh()

        store.clear()
        api.capabilities = ApiResult.NetworkError(RuntimeException("offline"))
        store.refresh()

        assertEquals(SeekIntervalSupport.Supported, store.state.value.support)
        assertEquals(90, store.state.value.videoIntervals.forwardSeconds)
    }

    @Test
    fun `a saved value applies immediately and a failed one rolls back`() = runTest {
        val api = FakeSeekSettingsApi()
        val store = storeFor(api)
        store.refresh()

        assertEquals<SeekIntervalController.SaveResult>(
            SeekIntervalController.SaveResult.Saved(90),
            store.save(SeekMedia.Video, SeekDirection.Forward, 90),
        )
        assertEquals(90, store.state.value.videoIntervals.forwardSeconds)
        assertEquals<List<Pair<String, JsonElement>>>(
            listOf(SettingKeys.PLAYER_VIDEO_SKIP_FORWARD_SECONDS to JsonPrimitive(90)),
            api.puts,
        )

        api.failPuts = true
        assertIs<SeekIntervalController.SaveResult.Failed>(store.save(SeekMedia.Video, SeekDirection.Forward, 45))
        assertEquals(90, store.state.value.videoIntervals.forwardSeconds)
    }

    @Test
    fun `import applies only the directions that succeeded`() = runTest {
        val api = FakeSeekSettingsApi(failKey = SettingKeys.PLAYER_AUDIOBOOK_SKIP_FORWARD_SECONDS)
        val store = storeFor(api)
        store.refresh()

        val result = store.importLegacyAudiobook(LegacyAudiobookIntervals(backSeconds = 15, forwardSeconds = 60))

        assertEquals(SeekImportOutcome.Imported(15), result?.back)
        assertIs<SeekImportOutcome.Failed>(result?.forward)
        assertEquals(SeekIntervalPair(15, 30), store.state.value.audiobookIntervals)
    }

    @Test
    fun `clear drops the previous profile's answer`() = runTest {
        val store = storeFor(FakeSeekSettingsApi())
        store.refresh()

        store.clear()

        assertEquals(SeekIntervalState(), store.state.value)
    }

    private fun kotlinx.coroutines.test.TestScope.storeFor(
        api: FakeSeekSettingsApi,
        cache: SeekIntervalCache = InMemorySeekIntervalCache(),
    ) = DefaultSeekIntervalStore.forTest(
        controller = SeekIntervalController(SettingsRepository(api)),
        scope = backgroundScope,
        getActiveProfileId = { "profile-1" },
        cache = cache,
    )

    private companion object {
        val LEGACY_PAIR = SeekIntervalPair(30, 30)
    }
}

internal class FakeSeekSettingsApi(
    var capabilities: ApiResult<SettingsContractCapabilities> = ApiResult.Success(
        SettingsContractCapabilities(apiVersion = 1, manifestRevision = 9, supportsBatchedEffective = true),
    ),
    private val values: Map<String, Int> = emptyMap(),
    private val failKey: String? = null,
) : SettingsApi(
    org.siloserver.silo.network.apiv2.SettingsV2Api(
        HttpClient(),
        org.siloserver.silo.network.TokenManagerImpl(),
        org.siloserver.silo.network.apiv2.ApiV2Gate.Unrestricted,
    ),
) {
    var failPuts = false
    val puts = mutableListOf<Pair<String, JsonElement>>()

    override suspend fun getContractCapabilities(): ApiResult<SettingsContractCapabilities> = capabilities

    override suspend fun getEffectiveValues(
        keys: List<String>,
        libraryIds: List<Int>,
        seriesIds: List<String>,
        authority: org.siloserver.silo.network.AuthScopeSnapshot?,
    ): ApiResult<EffectiveSettingValuesResponse> = ApiResult.Success(
        EffectiveSettingValuesResponse(
            settings = values.map { (key, seconds) ->
                EffectiveSettingValue(key = key, value = JsonPrimitive(seconds), source = "profile")
            },
        ),
    )

    override suspend fun putValue(
        key: String,
        scope: SettingScopeIdentity,
        value: JsonElement,
        profileId: String?,
        authority: org.siloserver.silo.network.AuthScopeSnapshot?,
    ): ApiResult<StoredSettingValue> {
        puts += key to value
        if (failPuts || key == failKey) return ApiResult.Error(500, "boom", "Server error")
        return ApiResult.Success(StoredSettingValue(key = key, scope = scope.scope.wire))
    }
}

/** Minimal in-memory [SeekIntervalStore] for consumers of the store. */
internal class FakeSeekIntervalStore(
    initial: SeekIntervalState = SeekIntervalState(),
    private val importResult: org.siloserver.silo.model.settings.SeekImportResult? = null,
) : SeekIntervalStore {
    /** When set, [save] returns this without changing state (a rejected or failed save). */
    var saveFailure: SeekIntervalController.SaveResult? = null
    private val mutableState = MutableStateFlow(initial)
    override val state: StateFlow<SeekIntervalState> = mutableState
    override val lastError: StateFlow<String?> = MutableStateFlow(null)
    var refreshCalls = 0
    val saves = mutableListOf<Triple<SeekMedia, SeekDirection, Int>>()
    val imports = mutableListOf<LegacyAudiobookIntervals>()

    override suspend fun hydrateIfNeeded() = Unit
    override suspend fun refresh() {
        refreshCalls += 1
    }

    override suspend fun save(
        media: SeekMedia,
        direction: SeekDirection,
        seconds: Int,
    ): SeekIntervalController.SaveResult {
        saves += Triple(media, direction, seconds)
        saveFailure?.let { return it }
        mutableState.value = mutableState.value.with(media, direction, seconds)
        return SeekIntervalController.SaveResult.Saved(seconds)
    }

    override suspend fun importLegacyAudiobook(
        legacy: LegacyAudiobookIntervals,
    ): org.siloserver.silo.model.settings.SeekImportResult? {
        imports += legacy
        return importResult
    }

    override fun clear() {
        mutableState.value = SeekIntervalState()
    }
}
