package org.siloserver.silo.viewmodel

import androidx.lifecycle.ViewModelStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.siloserver.silo.network.*
import org.siloserver.silo.network.apiv2.ApiV2Gate
import org.siloserver.silo.network.apiv2.ProfileSectionsV2Api
import org.siloserver.silo.repository.HomeLayoutChanges
import org.siloserver.silo.repository.ProfileHomeLayoutRepository
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class HomeSectionsEditorViewModelTest {
    private val owner = AuthScopeSnapshot("s", "p", "https://example.invalid", "pin", identityGeneration = 1)
    private val tokens = object : TokenManager by TokenManagerImpl() {
        override suspend fun snapshotCurrentScope(): AuthScopeSnapshot = owner
    }
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val settings = """{"items":[
        {"id":"recent","section_type":"recently_added","title":"New this week","default_title":"Recently Added","hidden":false,"is_custom":false,"position":0},
        {"id":"continue","section_type":"continue_watching","title":"Continue Watching","default_title":"Continue Watching","hidden":false,"is_custom":false,"position":1}
    ]}"""
    private val overrides = """{"items":[
        {"id":"ov-recent","section_id":"recent","position":null,"hidden":false,"removed":false,"section_type":"","title":"New this week","featured":null,"item_limit":null,"is_user_added":false,"user_section_type":"","user_title":"","created_at":null,"updated_at":null},
        {"id":"ov-gone","section_id":"gone","position":null,"hidden":false,"removed":true,"section_type":"","title":"","featured":null,"item_limit":null,"is_user_added":false,"user_section_type":"","user_title":"","created_at":null,"updated_at":null}
    ]}"""
    private var putStatus = HttpStatusCode.NoContent
    /** Statuses for the next PUTs in turn; [putStatus] once they run out. */
    private val putStatuses = ArrayDeque<HttpStatusCode>()
    private var putGate: CompletableDeferred<Unit>? = null
    private var settingsReads = 0
    private var settingsStatus = HttpStatusCode.OK
    private val puts = mutableListOf<JsonElement>()
    private val changes = HomeLayoutChanges()

    private fun scenario(block: suspend TestScope.(HomeSectionsEditorViewModel) -> Unit) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val client = HttpClient(MockEngine(MockEngineConfig().apply {
            this.dispatcher = dispatcher
            addHandler { request ->
                when {
                    request.method == HttpMethod.Put -> {
                        puts += Json.parseToJsonElement((request.body as TextContent).text)
                        putGate?.await()
                        respond("", putStatuses.removeFirstOrNull() ?: putStatus)
                    }
                    request.url.encodedPath.endsWith("/settings") -> {
                        settingsReads++
                        if (settingsStatus == HttpStatusCode.OK) respond(settings, HttpStatusCode.OK, json)
                        else respond("", settingsStatus)
                    }
                    else -> respond(overrides, HttpStatusCode.OK, json)
                }
            }
        }))
        val store = ViewModelStore()
        try {
            val repository = ProfileHomeLayoutRepository(
                ProfileSectionsV2Api(client, tokens, ApiV2Gate.Unrestricted),
                changes,
                newId = { "new-continue" },
            )
            val vm = HomeSectionsEditorViewModel(repository)
            store.put("editor", vm)
            vm.load()
            block(vm)
        } finally {
            store.clear(); client.close(); Dispatchers.resetMain()
        }
    }

    @Test
    fun movingARowSavesTheWholeLayoutToTheProfileAndRefreshesHome() = scenario { vm ->
        runCurrent()
        assertEquals(listOf("recent", "continue"), vm.state.value.rows.map { it.id })
        assertFalse(vm.state.value.isLoading)

        vm.move("continue", -1)
        assertEquals(listOf("continue", "recent"), vm.state.value.rows.map { it.id })
        runCurrent()

        assertEquals(
            Json.parseToJsonElement(
                """{"overrides":[
                {"id":"ov-gone","section_id":"gone","hidden":false,"removed":true,"section_type":"","title":"","is_user_added":false,"user_section_type":"","user_title":""},
                {"id":"new-continue","section_id":"continue","position":0,"hidden":false,"removed":false,"section_type":"","title":"","is_user_added":false,"user_section_type":"","user_title":""},
                {"id":"ov-recent","section_id":"recent","position":1,"hidden":false,"removed":false,"section_type":"","title":"New this week","is_user_added":false,"user_section_type":"","user_title":""}
                ]}""",
            ),
            puts.single(),
        )
        assertEquals(1L, changes.revision.value)
        assertNull(vm.state.value.saveError)
    }

    @Test
    fun hidingARowSavesItHidden() = scenario { vm ->
        runCurrent()
        vm.setHidden("recent", true)
        runCurrent()

        val rows = puts.single().jsonObject.getValue("overrides").jsonArray.map { it.jsonObject }
        val recent = rows.single { it["section_id"]?.jsonPrimitive?.content == "recent" }
        assertEquals(JsonPrimitive(true), recent["hidden"])
        assertTrue(vm.state.value.rows.single { it.id == "recent" }.hidden)
    }

    @Test
    fun aFailedSaveRestoresThePreviousOrderAndSaysSo() = scenario { vm ->
        runCurrent()
        putStatus = HttpStatusCode.InternalServerError

        vm.move("continue", -1)
        runCurrent()

        assertEquals(listOf("recent", "continue"), vm.state.value.rows.map { it.id })
        assertNotNull(vm.state.value.saveError)
        assertEquals(0L, changes.revision.value)
    }

    @Test
    fun aSecondFailedSaveRestoresTheLastSavedLayoutNotTheFirstUnsavedChange() = scenario { vm ->
        runCurrent()
        putStatus = HttpStatusCode.InternalServerError

        vm.move("continue", -1)
        vm.setHidden("recent", true)
        runCurrent()

        assertEquals(listOf("recent", "continue"), vm.state.value.rows.map { it.id })
        assertFalse(vm.state.value.rows.single { it.id == "recent" }.hidden)
        assertNotNull(vm.state.value.saveError)
    }

    @Test
    fun aFailedSaveKeepsALaterEditThatReturnsToTheSameRows() = scenario { vm ->
        runCurrent()
        putStatuses += HttpStatusCode.InternalServerError

        vm.move("continue", -1)
        vm.setHidden("recent", true)
        vm.setHidden("recent", false)
        runCurrent()

        assertEquals(3, puts.size)
        assertEquals(listOf("continue", "recent"), vm.state.value.rows.map { it.id })
        assertNull(vm.state.value.saveError, "a later save stored the change the failed one carried")
    }

    @Test
    fun aReloadDropsTheErrorOfASaveThatFailedBeforeIt() = scenario { vm ->
        runCurrent()
        putStatus = HttpStatusCode.InternalServerError
        putGate = CompletableDeferred()

        vm.move("continue", -1)
        runCurrent()
        vm.load()
        putGate!!.complete(Unit)
        runCurrent()

        assertEquals(listOf("recent", "continue"), vm.state.value.rows.map { it.id })
        assertNull(vm.state.value.saveError)
    }

    @Test
    fun reopeningTheEditorWaitsForAPendingSaveBeforeReadingTheLayout() = scenario { vm ->
        runCurrent()
        val readsAfterOpen = settingsReads
        putGate = CompletableDeferred()

        vm.move("continue", -1)
        runCurrent()
        vm.load()
        runCurrent()
        assertEquals(readsAfterOpen, settingsReads, "the layout was read while the save was still running")

        putGate!!.complete(Unit)
        runCurrent()
        assertEquals(readsAfterOpen + 1, settingsReads)
    }

    @Test
    fun aFailedReloadShowsTheErrorInsteadOfTheOldRowsAndCannotSave() = scenario { vm ->
        runCurrent()
        settingsStatus = HttpStatusCode.InternalServerError

        vm.load()
        runCurrent()

        assertTrue(vm.state.value.rows.isEmpty())
        assertNotNull(vm.state.value.loadError)
        vm.setHidden("recent", true)
        runCurrent()
        assertTrue(puts.isEmpty())
    }
}
