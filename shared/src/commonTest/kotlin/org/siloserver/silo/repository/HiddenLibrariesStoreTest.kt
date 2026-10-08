package org.siloserver.silo.repository

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import org.siloserver.silo.model.personal.UserLibrary
import org.siloserver.silo.model.settings.EffectiveSettingValue
import org.siloserver.silo.model.settings.EffectiveSettingValuesResponse
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.DefaultIdentityTransitionBarrier
import org.siloserver.silo.network.IdentityTransitionKind
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.api.PersonalDataApi
import org.siloserver.silo.network.api.SettingsApi
import org.siloserver.silo.repository.port.CatalogCachePort
import kotlin.test.Test
import kotlin.test.assertEquals

class HiddenLibrariesStoreTest {

    private class FakeSettings(var hidden: JsonElement = JsonNull) : SettingsApi(
        org.siloserver.silo.network.apiv2.SettingsV2Api(
            HttpClient(),
            org.siloserver.silo.network.TokenManagerImpl(),
            org.siloserver.silo.network.apiv2.ApiV2Gate.Unrestricted,
        ),
    ) {
        var beforeAnswer: suspend () -> Unit = {}
        var fails = false

        override suspend fun getEffectiveValues(
            keys: List<String>,
            libraryIds: List<Int>,
            seriesIds: List<String>,
            authority: AuthScopeSnapshot?,
        ): ApiResult<EffectiveSettingValuesResponse> {
            beforeAnswer()
            if (fails) return ApiResult.Error(503, "unavailable", "Unavailable")
            return ApiResult.Success(
                EffectiveSettingValuesResponse(
                    settings = listOf(EffectiveSettingValue(key = HiddenLibrariesStore.KEY, value = hidden)),
                ),
            )
        }
    }

    private class FakeCache : CatalogCachePort {
        var cachedLibraries: List<UserLibrary>? = null

        override suspend fun cacheLibraries(libraries: List<UserLibrary>) {
            cachedLibraries = libraries
        }
    }

    private var libraryRequests = 0

    private fun librariesClient() = HttpClient(
        MockEngine {
            libraryRequests++
            respond(
                """{"items":[{"id":"1","name":"Movies","type":"movies","sort_order":1},""" +
                    """{"id":"2","name":"TV Shows","type":"series","sort_order":2}]}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        },
    ) { install(ContentNegotiation) { json(SiloJson) } }

    private fun ids(vararg values: Int) = buildJsonArray { values.forEach { add(it) } }

    @Test
    fun parsesTheWebsAcceptedEncodings() {
        assertEquals(setOf(2, 5), HiddenLibrariesStore.parseLibraryIds(ids(2, 5, 2)))
        assertEquals(setOf(3), HiddenLibrariesStore.parseLibraryIds(JsonPrimitive("[3]")))
        assertEquals(emptySet(), HiddenLibrariesStore.parseLibraryIds(JsonNull))
        assertEquals(emptySet(), HiddenLibrariesStore.parseLibraryIds(null))
        assertEquals(emptySet(), HiddenLibrariesStore.parseLibraryIds(JsonPrimitive("not json")))
        val mixed = buildJsonArray { add("4"); add(0); add(-1); add(1.5); add(7); add(8.0) }
        assertEquals(setOf(7, 8), HiddenLibrariesStore.parseLibraryIds(mixed))
    }

    @Test
    fun libraryListLeavesOutHiddenLibrariesAndCachesWhatIsShown() = runTest {
        val barrier = DefaultIdentityTransitionBarrier()
        val cache = FakeCache()
        val repository = PersonalDataRepository(
            personalDataApi = PersonalDataApi(librariesClient()),
            catalogCache = cache,
            identityTransitions = barrier,
            hiddenLibraries = HiddenLibrariesStore(SettingsRepository(FakeSettings(ids(2))), barrier),
        )

        val result = repository.listUserLibraries()

        assertEquals(listOf(1), (result as ApiResult.Success).data.map { it.id })
        assertEquals(listOf(1), cache.cachedLibraries?.map { it.id })
    }

    @Test
    fun recheckTreatsANewlyHiddenLibraryAsRemovedWithoutReadingTwice() = runTest {
        val barrier = DefaultIdentityTransitionBarrier()
        val settings = FakeSettings()
        val store = HiddenLibrariesStore(SettingsRepository(settings), barrier)
        val repository = PersonalDataRepository(
            personalDataApi = PersonalDataApi(librariesClient()),
            identityTransitions = barrier,
            hiddenLibraries = store,
        )
        assertEquals(listOf(1, 2), (repository.listUserLibraries() as ApiResult.Success).data.map { it.id })

        settings.hidden = ids(2)
        store.refresh()
        libraryRequests = 0
        val rechecked = repository.recheckUserLibraries(knownIds = setOf(1, 2))

        assertEquals(listOf(1), (rechecked as ApiResult.Success).data.map { it.id })
        assertEquals(1, libraryRequests)
    }

    @Test
    fun onlyAChangeFoundByARefreshBumpsTheRevision() = runTest {
        val barrier = DefaultIdentityTransitionBarrier()
        val settings = FakeSettings(ids(2))
        val store = HiddenLibrariesStore(SettingsRepository(settings), barrier)

        store.ensureLoaded()
        store.refresh()
        assertEquals(0, store.revision.value)

        settings.hidden = ids()
        store.ensureLoaded()
        assertEquals(setOf(2), store.current())
        store.refresh()
        assertEquals(emptySet(), store.current())
        assertEquals(1, store.revision.value)
    }

    @Test
    fun aSuccessfulReadAfterAFailedOneBumpsTheRevision() = runTest {
        val barrier = DefaultIdentityTransitionBarrier()
        val settings = FakeSettings(ids(2)).apply { fails = true }
        val store = HiddenLibrariesStore(SettingsRepository(settings), barrier)

        store.ensureLoaded()
        assertEquals(emptySet(), store.current())

        settings.fails = false
        store.ensureLoaded()
        assertEquals(setOf(2), store.current())
        assertEquals(1, store.revision.value)
    }

    @Test
    fun aReadStartedMidSwitchAnswersForTheNewIdentity() = runTest {
        val barrier = DefaultIdentityTransitionBarrier()
        val settings = FakeSettings(ids(2))
        val store = HiddenLibrariesStore(SettingsRepository(settings), barrier)

        lateinit var read: kotlinx.coroutines.Deferred<Unit>
        barrier.changing(IdentityTransitionKind.PROFILE_SWITCH) {
            // The generation has advanced; the new identity isn't installed yet.
            read = async { store.ensureLoaded() }
            testScheduler.runCurrent()
            settings.hidden = ids(1)
        }
        read.await()

        assertEquals(setOf(1), store.current())
    }

    @Test
    fun aReadThatStraddlesAProfileSwitchIsDropped() = runTest {
        val barrier = DefaultIdentityTransitionBarrier()
        val settings = FakeSettings(ids(2))
        val store = HiddenLibrariesStore(SettingsRepository(settings), barrier)
        store.refresh()
        assertEquals(setOf(2), store.current())

        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        settings.beforeAnswer = { entered.complete(Unit); release.await() }
        settings.hidden = ids(1)
        val read = async { store.refresh() }
        entered.await()
        barrier.changing(IdentityTransitionKind.PROFILE_SWITCH) { }
        release.complete(Unit)
        read.await()

        assertEquals(emptySet(), store.current())
    }
}
