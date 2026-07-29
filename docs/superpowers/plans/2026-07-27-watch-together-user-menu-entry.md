# Watch Together User-Menu Entry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a top-level Watch Together entry to the authenticated profile menu on Android phone and Android TV, supporting empty vote-room hosting, join by code, and same-process room resume while preserving the existing title-detail and room-authority behavior.

**Architecture:** Add one narrow shared entry gateway and pure destination/resume policy over the existing `WatchTogetherRepository`; the current phone and TV entry ViewModels remain the platform orchestration points. Phone renders a Material modal sheet and TV renders a focusable popup, both routing into the existing lobby/player and `RoomSession`; no repository, socket, protocol, server endpoint, or persistent room store is added.

**Tech Stack:** Kotlin 2.1, Kotlin Multiplatform shared module, coroutines and `StateFlow`, Koin, Jetpack Compose Material 3, Compose for TV Material 3, Navigation Compose, JUnit/kotlin-test, Robolectric for phone route tests, Gradle, ADB.

## Global Constraints

- Android phone and Android TV only; do not change Silo Server, Apple clients, or production proxy configuration.
- Add **Watch Together** to the authenticated user/profile menu immediately after **Requests** when Requests is present; otherwise keep it in the same content/action group immediately before the settings/account divider.
- The menu entry is a transient phone sheet or TV popup, not a persistent Watch Together home.
- **Host a room** creates exactly one empty vote room with `selection_mode = "vote"` and must not call `setSelection`.
- **Join by code** must continue to use the existing repository, validation, error mapping, and lobby/player destination rules.
- **Resume current room** is visible only for a valid, non-terminal room in the same running process, server, and authenticated profile; do not add persistence or room discovery.
- The room owner remains a full participant who may suggest, vote, apply the existing host override to any room-owned suggestion, and close the room for everyone.
- Keep current server host semantics: no automatic host transfer, ownership election, original-host reclaim, or timeout changes.
- After host logout, profile/server switch, process death, or unrecovered disconnect, the server may close the room after its existing host-disconnect timeout; the clients do not extend or replace that policy.
- Navigation and backgrounding preserve the live process-scoped room; logout, profile/server switch, explicit Leave, terminal room closure, and process death clear local state through existing ownership boundaries.
- Reuse `WatchTogetherRepository`, `RoomSession`, existing lobby/player routes, websocket/reconnect behavior, voting, auth scope, cleartext consent, and error handling.
- Preserve the existing title-detail Watch Together entry and its preselected-title Host behavior.
- Room credentials remain repository-private and must not enter UI state, route parameters, logs, or tests.
- No changes to `WatchTogetherApi`, Watch Together wire models, websocket frames, or server/proxy configuration.

---

## Current `origin/main` map

The plan is based on `origin/main` `e0917cbe8cc1b021f184954e1e7cc977c06f628e`.

| Responsibility | Current file and seam |
|---|---|
| Shared room owner | `shared/src/commonMain/kotlin/org/siloserver/silo/repository/WatchTogetherRepository.kt:71-138,194-378,725-741` |
| Process/session owner | `shared/src/commonMain/kotlin/org/siloserver/silo/watchtogether/RoomSession.kt:20-104` |
| Identity teardown | `shared/src/commonMain/kotlin/org/siloserver/silo/network/IdentityTransitionBarrier.kt` and `RoomSession` transition gate |
| Shared DI | `shared/src/commonMain/kotlin/org/siloserver/silo/di/RepositoryModule.kt:96-123` |
| Phone entry controller | `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/watchtogether/WatchTogetherEntryViewModel.kt:21-125` |
| Phone title-detail sheet | `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/watchtogether/WatchTogetherEntrySheet.kt:37-138` |
| Phone profile menus | `MainAppTopBar.kt:52-181`, `HomeScreen.kt:84-100,291-305,444-525`, `LibrariesScreen.kt:617-630,1168-1183,1328-1408` |
| Phone shell | `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/MainScreen.kt:120-451` |
| Phone lobby | `WatchTogetherLobbyScreen.kt:45-203`, `WatchTogetherLobbyViewModel.kt:35-88` |
| TV entry controller | `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/watchtogether/TvWatchTogetherViewModel.kt:18-113` |
| TV title-detail dialog | `TvWatchTogetherEntryDialog.kt:31-113` and `TvItemDetailScreen.kt:1048-1104` |
| TV profile menu/shell | `TvMainShell.kt:166-180,607-610,1270-1313,1478-1563` |
| TV root navigation | `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/navigation/TvAppNavigation.kt:480-545,630-712,875-900` |
| TV lobby | `TvWatchTogetherLobbyScreen.kt:76-175,250-330,523-558`, `TvWatchTogetherLobbyViewModel.kt:17-83` |
| Existing behavior tests | `WatchTogetherRepositoryTest.kt`, `RoomSessionTest.kt`, `WatchTogetherEntryDestinationTest.kt`, `TvWatchTogetherSurfaceSourceTest.kt`, `TvShellFocusStateTest.kt` |

## Task 1: Shared entry policy and narrow repository gateway

**Files:**
- Create: `shared/src/commonMain/kotlin/org/siloserver/silo/watchtogether/WatchTogetherEntryPolicy.kt`
- Create: `shared/src/commonMain/kotlin/org/siloserver/silo/watchtogether/WatchTogetherEntryGateway.kt`
- Create: `shared/src/commonTest/kotlin/org/siloserver/silo/watchtogether/WatchTogetherEntryPolicyTest.kt`
- Modify: `shared/src/commonMain/kotlin/org/siloserver/silo/repository/WatchTogetherRepository.kt:71-100,181-284`
- Modify: `shared/src/commonMain/kotlin/org/siloserver/silo/di/RepositoryModule.kt:96-123`

**Interfaces:**
- Produces: `enum class WatchTogetherEntryTarget { Lobby, Player }`
- Produces: `fun watchTogetherEntryTarget(room: RoomSnapshot): WatchTogetherEntryTarget`
- Produces: `fun resumableWatchTogetherRoom(room: RoomSnapshot?): RoomSnapshot?`
- Produces: `interface WatchTogetherEntryGateway` with `roomSnapshot`, `createRoom`, `joinRoom`, and `setSelection`
- Preserves: the concrete singleton `WatchTogetherRepository`; the gateway is only a testable view of its existing methods.

- [ ] **Step 1: Write the failing shared policy tests**

```kotlin
package org.siloserver.silo.watchtogether

import org.siloserver.silo.model.watchtogether.MemberRole
import org.siloserver.silo.model.watchtogether.RoomPhase
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WatchTogetherEntryPolicyTest {
    @Test
    fun selectedGuestRoutesToPlayer() {
        val room = RoomSnapshot(
            roomId = "room-1",
            selectedContentId = "movie-1",
            selfRole = MemberRole.Guest,
            memberCount = 2,
        )
        assertEquals(WatchTogetherEntryTarget.Player, watchTogetherEntryTarget(room))
    }

    @Test
    fun emptyRoomAndSoloHostRouteToLobby() {
        assertEquals(
            WatchTogetherEntryTarget.Lobby,
            watchTogetherEntryTarget(RoomSnapshot(roomId = "room-1")),
        )
        assertEquals(
            WatchTogetherEntryTarget.Lobby,
            watchTogetherEntryTarget(
                RoomSnapshot(
                    roomId = "room-1",
                    selectedContentId = "movie-1",
                    selfRole = MemberRole.Host,
                    memberCount = 1,
                ),
            ),
        )
    }

    @Test
    fun onlyNonTerminalNonBlankRoomIsResumable() {
        assertNull(resumableWatchTogetherRoom(null))
        assertNull(resumableWatchTogetherRoom(RoomSnapshot(roomId = "")))
        assertNull(
            resumableWatchTogetherRoom(
                RoomSnapshot(roomId = "room-1", phase = RoomPhase.Ended),
            ),
        )
        assertEquals(
            "room-1",
            resumableWatchTogetherRoom(
                RoomSnapshot(roomId = "room-1", phase = RoomPhase.Lobby),
            )?.roomId,
        )
    }
}
```

- [ ] **Step 2: Run the policy test and verify RED**

Run:

```bash
./gradlew :shared:testDebugUnitTest \
  --tests 'org.siloserver.silo.watchtogether.WatchTogetherEntryPolicyTest'
```

Expected: FAIL to compile because `WatchTogetherEntryTarget`, `watchTogetherEntryTarget`, and `resumableWatchTogetherRoom` do not exist.

- [ ] **Step 3: Implement the pure policy**

```kotlin
package org.siloserver.silo.watchtogether

import org.siloserver.silo.model.watchtogether.MemberRole
import org.siloserver.silo.model.watchtogether.RoomPhase
import org.siloserver.silo.model.watchtogether.RoomSnapshot

enum class WatchTogetherEntryTarget {
    Lobby,
    Player,
}

fun watchTogetherEntryTarget(room: RoomSnapshot): WatchTogetherEntryTarget =
    if (
        !room.selectedContentId.isNullOrBlank() &&
        !(room.selfRole == MemberRole.Host && room.memberCount <= 1)
    ) {
        WatchTogetherEntryTarget.Player
    } else {
        WatchTogetherEntryTarget.Lobby
    }

fun resumableWatchTogetherRoom(room: RoomSnapshot?): RoomSnapshot? =
    room?.takeIf { it.roomId.isNotBlank() && it.phase != RoomPhase.Ended }
```

- [ ] **Step 4: Add the narrow gateway and bind the existing singleton**

```kotlin
package org.siloserver.silo.watchtogether

import org.siloserver.silo.model.watchtogether.CreateRoomRequest
import org.siloserver.silo.model.watchtogether.JoinRoomRequest
import org.siloserver.silo.model.watchtogether.RoomResponse
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.model.watchtogether.SetSelectionRequest
import org.siloserver.silo.network.ApiResult
import kotlinx.coroutines.flow.StateFlow

interface WatchTogetherEntryGateway {
    val roomSnapshot: StateFlow<RoomSnapshot?>
    suspend fun createRoom(request: CreateRoomRequest): ApiResult<RoomResponse>
    suspend fun joinRoom(request: JoinRoomRequest): ApiResult<RoomResponse>
    suspend fun setSelection(request: SetSelectionRequest): ApiResult<RoomResponse>
}
```

Change the repository declaration and existing members to implement the interface:

```kotlin
) : RoomSessionRepository, WatchTogetherEntryGateway {
    override val roomSnapshot: StateFlow<RoomSnapshot?> = _roomSnapshot.asStateFlow()
}
```

Add the `override` modifier to the existing `createRoom`, `joinRoom`, and
`setSelection` declarations. Do not alter any statement inside those three
existing method bodies; verify their body diff is empty.

Bind the same singleton in `RepositoryModule.kt` immediately after its concrete
registration:

```kotlin
import org.siloserver.silo.watchtogether.WatchTogetherEntryGateway

single<WatchTogetherEntryGateway> { get<WatchTogetherRepository>() }
```

- [ ] **Step 5: Run shared tests and compile both clients**

Run:

```bash
./gradlew \
  :shared:testDebugUnitTest \
  :androidApp:compileDebugKotlinAndroid \
  :androidTvApp:compileDebugKotlinAndroid \
  --max-workers=2
```

Expected: PASS; no new network or model source is compiled.

- [ ] **Step 6: Commit the shared boundary**

```bash
git add \
  shared/src/commonMain/kotlin/org/siloserver/silo/watchtogether/WatchTogetherEntryPolicy.kt \
  shared/src/commonMain/kotlin/org/siloserver/silo/watchtogether/WatchTogetherEntryGateway.kt \
  shared/src/commonTest/kotlin/org/siloserver/silo/watchtogether/WatchTogetherEntryPolicyTest.kt \
  shared/src/commonMain/kotlin/org/siloserver/silo/repository/WatchTogetherRepository.kt \
  shared/src/commonMain/kotlin/org/siloserver/silo/di/RepositoryModule.kt
git commit -m "refactor: define Watch Together entry boundary"
```

## Task 2: Phone entry controller behavior

**Files:**
- Create: `androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/screens/watchtogether/WatchTogetherEntryViewModelTest.kt`
- Modify: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/watchtogether/WatchTogetherEntryViewModel.kt:21-125`
- Modify: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/watchtogether/WatchTogetherEntrySheet.kt:92-112`

**Interfaces:**
- Consumes: `WatchTogetherEntryGateway`, `watchTogetherEntryTarget`, and `resumableWatchTogetherRoom`
- Produces: `val currentRoom: StateFlow<RoomSnapshot?>`
- Produces: `fun hostEmptyVoteRoom()`
- Produces: `fun resumeCurrentRoom()`
- Preserves: `fun host(contentId: String, fileId: Int?, selectionMode: RoomSelectionMode)` and `fun joinByCode(code: String)`

- [ ] **Step 1: Write the phone ViewModel fake and RED tests**

```kotlin
package org.siloserver.silo.android.ui.screens.watchtogether

import org.siloserver.silo.model.watchtogether.CreateRoomRequest
import org.siloserver.silo.model.watchtogether.JoinRoomRequest
import org.siloserver.silo.model.watchtogether.RoomPhase
import org.siloserver.silo.model.watchtogether.RoomResponse
import org.siloserver.silo.model.watchtogether.RoomSelectionMode
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.model.watchtogether.SetSelectionRequest
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.watchtogether.WatchTogetherEntryGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class WatchTogetherEntryViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun emptyHostCreatesVoteRoomWithoutSelection() = runTest(dispatcher) {
        val gateway = FakeGateway()
        val viewModel = WatchTogetherEntryViewModel(gateway)

        viewModel.hostEmptyVoteRoom()

        assertEquals(listOf(CreateRoomRequest(RoomSelectionMode.Vote.wire)), gateway.createRequests)
        assertEquals(emptyList(), gateway.selectionRequests)
        assertEquals("watch_together/room-1", viewModel.uiState.value.destination)
    }

    @Test
    fun resumeUsesCurrentRoomWithoutCreateOrJoin() = runTest(dispatcher) {
        val room = RoomSnapshot(roomId = "room-1", phase = RoomPhase.Lobby)
        val gateway = FakeGateway(room)
        val viewModel = WatchTogetherEntryViewModel(gateway)

        viewModel.resumeCurrentRoom()

        assertEquals("watch_together/room-1", viewModel.uiState.value.destination)
        assertEquals(emptyList(), gateway.createRequests)
        assertEquals(emptyList(), gateway.joinRequests)
    }

    @Test
    fun identityClearRemovesResumeState() = runTest(dispatcher) {
        val gateway = FakeGateway(RoomSnapshot(roomId = "room-1", phase = RoomPhase.Lobby))
        val viewModel = WatchTogetherEntryViewModel(gateway)

        gateway.roomSnapshot.value = null

        assertNull(viewModel.currentRoom.value)
    }

    @Test
    fun titleHostStillSetsTheSelectedTitle() = runTest(dispatcher) {
        val gateway = FakeGateway()
        val viewModel = WatchTogetherEntryViewModel(gateway)

        viewModel.host(contentId = "movie-1", fileId = 7)

        assertEquals(
            listOf(SetSelectionRequest(contentId = "movie-1", fileId = 7)),
            gateway.selectionRequests,
        )
    }

    @Test
    fun joinByCodeTrimsAndUsesExistingErrorMapping() = runTest(dispatcher) {
        val gateway = FakeGateway()
        val viewModel = WatchTogetherEntryViewModel(gateway)

        viewModel.joinByCode(" ABCD1234 ")
        assertEquals(listOf(JoinRoomRequest(code = "ABCD1234")), gateway.joinRequests)

        viewModel.consumeDestination()
        gateway.joinResult = ApiResult.NetworkError(IllegalStateException("offline"))
        viewModel.joinByCode("EFGH5678")
        assertEquals("Network error. Check your connection.", viewModel.uiState.value.error)
    }

    private class FakeGateway(
        room: RoomSnapshot? = null,
    ) : WatchTogetherEntryGateway {
        override val roomSnapshot = MutableStateFlow(room)
        val createRequests = mutableListOf<CreateRoomRequest>()
        val joinRequests = mutableListOf<JoinRoomRequest>()
        val selectionRequests = mutableListOf<SetSelectionRequest>()
        var joinResult: ApiResult<RoomResponse>? = null
        var nextRoom = RoomSnapshot(
            roomId = "room-1",
            phase = RoomPhase.Lobby,
            selectionMode = RoomSelectionMode.Vote,
        )

        override suspend fun createRoom(request: CreateRoomRequest): ApiResult<RoomResponse> {
            createRequests += request
            roomSnapshot.value = nextRoom
            return ApiResult.Success(RoomResponse(nextRoom, "test-room-token"))
        }

        override suspend fun joinRoom(request: JoinRoomRequest): ApiResult<RoomResponse> {
            joinRequests += request
            joinResult?.let { return it }
            roomSnapshot.value = nextRoom
            return ApiResult.Success(RoomResponse(nextRoom, "test-room-token"))
        }

        override suspend fun setSelection(
            request: SetSelectionRequest,
        ): ApiResult<RoomResponse> {
            selectionRequests += request
            nextRoom = nextRoom.copy(
                selectedContentId = request.contentId,
                selectedFileId = request.fileId,
            )
            roomSnapshot.value = nextRoom
            return ApiResult.Success(RoomResponse(nextRoom, "test-room-token"))
        }
    }
}
```

- [ ] **Step 2: Run the phone ViewModel test and verify RED**

Run:

```bash
./gradlew :androidApp:testDebugUnitTest \
  --tests 'org.siloserver.silo.android.ui.screens.watchtogether.WatchTogetherEntryViewModelTest'
```

Expected: FAIL because the constructor still takes `WatchTogetherRepository`,
and `hostEmptyVoteRoom`, `resumeCurrentRoom`, and `currentRoom` do not exist.

- [ ] **Step 3: Implement the phone controller additions**

Change the constructor to `WatchTogetherEntryGateway`, map current-room state
through the shared policy, and add explicit menu actions:

```kotlin
class WatchTogetherEntryViewModel(
    private val gateway: WatchTogetherEntryGateway,
) : ViewModel() {
    val currentRoom: StateFlow<RoomSnapshot?> = gateway.roomSnapshot
        .map(::resumableWatchTogetherRoom)
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            resumableWatchTogetherRoom(gateway.roomSnapshot.value),
        )

    fun hostEmptyVoteRoom() {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (
                val created = gateway.createRoom(
                    CreateRoomRequest(selectionMode = RoomSelectionMode.Vote.wire),
                )
            ) {
                is ApiResult.Success -> finish(created.data.room)
                is ApiResult.Error, is ApiResult.NetworkError ->
                    fail(created.errorMessage("Failed to create room"))
            }
        }
    }

    fun resumeCurrentRoom() {
        if (_uiState.value.busy) return
        val room = resumableWatchTogetherRoom(gateway.roomSnapshot.value)
        if (room == null) {
            fail("Current room is no longer available")
        } else {
            finish(room)
        }
    }
}
```

Within existing `host`, delegate vote mode before starting HostPick work:

```kotlin
if (selectionMode == RoomSelectionMode.Vote) {
    hostEmptyVoteRoom()
    return
}
```

Replace repository calls in this ViewModel with `gateway` calls. Keep
`errorMessage`, the busy guard, one-shot destination consumption, and
title-selection ordering unchanged. Change the title-detail sheet's
`onHostVote` path to `viewModel.hostEmptyVoteRoom()` so it uses the explicit
empty-vote action without dummy content.

- [ ] **Step 4: Map phone routes through the shared target policy**

Keep the public phone helper name stable:

```kotlin
fun watchTogetherDestination(room: RoomSnapshot): String =
    when (watchTogetherEntryTarget(room)) {
        WatchTogetherEntryTarget.Player -> Route.Player(
            contentId = requireNotNull(room.selectedContentId),
            fileId = room.selectedFileId,
            roomId = room.roomId,
        ).route
        WatchTogetherEntryTarget.Lobby ->
            Route.WatchTogetherLobby(roomId = room.roomId).route
    }
```

- [ ] **Step 5: Run the focused phone entry tests**

Run:

```bash
./gradlew :androidApp:testDebugUnitTest \
  --tests 'org.siloserver.silo.android.ui.screens.watchtogether.WatchTogetherEntryViewModelTest' \
  --tests 'org.siloserver.silo.android.ui.screens.watchtogether.WatchTogetherEntryDestinationTest'
```

Expected: PASS.

- [ ] **Step 6: Commit the phone controller**

```bash
git add \
  androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/watchtogether/WatchTogetherEntryViewModel.kt \
  androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/watchtogether/WatchTogetherEntrySheet.kt \
  androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/screens/watchtogether/WatchTogetherEntryViewModelTest.kt
git commit -m "feat: add phone Watch Together menu actions"
```

## Task 3: Phone transient sheet and all profile-menu rows

**Files:**
- Create: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/watchtogether/WatchTogetherMenuEntrySheet.kt`
- Create: `androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/screens/watchtogether/WatchTogetherMenuEntrySourceTest.kt`
- Modify: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/components/MainAppTopBar.kt:52-181`
- Modify: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/home/HomeScreen.kt:84-100,291-305,444-525`
- Modify: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/libraries/LibrariesScreen.kt:617-630,1168-1183,1328-1408`
- Modify: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/MainScreen.kt:120-451`

**Interfaces:**
- Consumes: `WatchTogetherEntryViewModel.currentRoom`, `hostEmptyVoteRoom`, `resumeCurrentRoom`, and `joinByCode`
- Produces: `@Composable fun WatchTogetherMenuEntrySheet(onNavigate: (String) -> Unit, onDismiss: () -> Unit, viewModel: WatchTogetherEntryViewModel = koinViewModel())`
- Produces: `onWatchTogetherClick: (() -> Unit)?` through each phone chrome/profile-menu signature, supplied only when `CLIENT_WATCH_TOGETHER_SURFACE_ENABLED` is true.

- [ ] **Step 1: Write the phone source-level RED tests**

```kotlin
package org.siloserver.silo.android.ui.screens.watchtogether

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchTogetherMenuEntrySourceTest {
    private fun source(path: String) = File("src/androidMain/kotlin/$path").readText()

    private val topBar = source("org/siloserver/silo/android/ui/components/MainAppTopBar.kt")
    private val home = source("org/siloserver/silo/android/ui/screens/home/HomeScreen.kt")
    private val libraries = source("org/siloserver/silo/android/ui/screens/libraries/LibrariesScreen.kt")
    private val main = source("org/siloserver/silo/android/ui/screens/MainScreen.kt")
    private val menuSheet = source(
        "org/siloserver/silo/android/ui/screens/watchtogether/WatchTogetherMenuEntrySheet.kt",
    )

    @Test
    fun everyPhoneProfileMenuPlacesWatchTogetherAfterRequestsAndBeforeSettings() {
        listOf(topBar, home, libraries).forEach { text ->
            val watch = text.indexOf("Text(\"Watch Together\")")
            val requests = text.indexOf("Text(\"Requests\")")
            val settings = text.indexOf("Text(\"Settings\")")
            assertTrue(watch >= 0)
            assertTrue(requests < 0 || requests < watch)
            assertTrue(watch < settings)
            if (requests >= 0) {
                assertFalse(
                    text.substring(
                        startIndex = requests + "Text(\"Requests\")".length,
                        endIndex = watch,
                    ).contains("DropdownMenuItem("),
                )
            }
        }
    }

    @Test
    fun mainShellOwnsOneTransientEntrySheet() {
        assertTrue(main.contains("var showWatchTogetherEntry by rememberSaveable"))
        assertTrue(main.contains("WatchTogetherMenuEntrySheet("))
        assertTrue(main.contains("CLIENT_WATCH_TOGETHER_SURFACE_ENABLED"))
        assertTrue(main.contains("onWatchTogetherClick = watchTogetherMenuAction"))
    }

    @Test
    fun sheetUsesOnlyTheExistingControllerAndNeverHandlesCredentials() {
        assertTrue(menuSheet.contains("viewModel.hostEmptyVoteRoom()"))
        assertTrue(menuSheet.contains("viewModel.resumeCurrentRoom()"))
        assertTrue(menuSheet.contains("viewModel.joinByCode(code)"))
        listOf("Resume current room", "Host a room", "Join by code").forEach { label ->
            assertTrue(menuSheet.contains(label))
        }
        assertFalse(menuSheet.contains("WatchTogetherApi"))
        assertFalse(menuSheet.contains("roomAccessToken"))
        assertFalse(menuSheet.contains("HttpClient"))
    }
}
```

- [ ] **Step 2: Run the phone source test and verify RED**

Run:

```bash
./gradlew :androidApp:testDebugUnitTest \
  --tests 'org.siloserver.silo.android.ui.screens.watchtogether.WatchTogetherMenuEntrySourceTest'
```

Expected: FAIL because the menu sheet/file, callbacks, and menu rows do not
exist.

- [ ] **Step 3: Implement the transient phone sheet**

The new sheet must collect `uiState` and `currentRoom`, show Resume only when
non-null, and retain the existing join-code rules:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchTogetherMenuEntrySheet(
    onNavigate: (String) -> Unit,
    onDismiss: () -> Unit,
    viewModel: WatchTogetherEntryViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val currentRoom by viewModel.currentRoom.collectAsState()
    var code by rememberSaveable { mutableStateOf("") }
    var showJoin by rememberSaveable { mutableStateOf(false) }
    val latestBusy by rememberUpdatedState(state.busy)

    LaunchedEffect(state.destination) {
        val destination = state.destination ?: return@LaunchedEffect
        viewModel.consumeDestination()
        onDismiss()
        onNavigate(destination)
    }

    ModalBottomSheet(
        onDismissRequest = {
            if (canDismissRoomEntry(state.busy)) {
                viewModel.clearError()
                onDismiss()
            }
        },
        sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { target ->
                target != SheetValue.Hidden || canDismissRoomEntry(latestBusy)
            },
        ),
    ) {
        if (!showJoin) {
            if (currentRoom != null) {
                Button(
                    onClick = viewModel::resumeCurrentRoom,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Resume current room")
                }
            }
            Button(
                onClick = viewModel::hostEmptyVoteRoom,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.busy) "Creating…" else "Host a room")
            }
            OutlinedButton(
                onClick = {
                    viewModel.clearError()
                    showJoin = true
                },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Join by code")
            }
        } else {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.uppercase().filter(Char::isLetterOrDigit).take(8) },
                label = { Text("Invite code") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { viewModel.joinByCode(code) },
                enabled = !state.busy && code.length >= 4,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.busy) "Joining…" else "Join")
            }
            OutlinedButton(
                onClick = {
                    viewModel.clearError()
                    showJoin = false
                },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Back")
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
```

Render `Text("Watch Together")` with `titleMedium`, `FontWeight.Bold`, and
`Modifier.padding(horizontal = 20.dp, vertical = 12.dp)`, followed by an
`HorizontalDivider`. Wrap the action branch in a full-width `Column` with
`Modifier.padding(20.dp)` and `Arrangement.spacedBy(12.dp)`, and end the sheet
with `Spacer(Modifier.height(24.dp))`. Use an 18.dp
`CircularProgressIndicator` for the busy Join button. Do not import
network/API/token types.

- [ ] **Step 4: Thread and render the phone menu action**

Add this exact parameter to `MainAppTopBar`, `HomeScreen`,
`HomeFloatingChrome`, `HomeProfileMenu`, `LibrariesScreen`,
`LibrariesFloatingChrome`, and `ChromeProfileMenu`:

```kotlin
onWatchTogetherClick: (() -> Unit)?,
```

In all three menu implementations, keep the conditional Requests item first,
insert Watch Together immediately after it, and keep one divider after the
content/action group. When Requests is absent, Watch Together is therefore the
last content action before the divider:

```kotlin
if (onRequestsClick != null) {
    DropdownMenuItem(
        text = { Text("Requests") },
        onClick = {
            menuExpanded = false
            onRequestsClick()
        },
    )
}
if (onWatchTogetherClick != null) {
    DropdownMenuItem(
        text = { Text("Watch Together") },
        onClick = {
            menuExpanded = false
            onWatchTogetherClick()
        },
    )
}
HorizontalDivider()
```

In `MainScreen`, add one saved surface flag and use one callback at all phone
menu call sites:

```kotlin
import org.siloserver.silo.model.feature.CLIENT_WATCH_TOGETHER_SURFACE_ENABLED

var showWatchTogetherEntry by rememberSaveable { mutableStateOf(false) }
val watchTogetherMenuAction: (() -> Unit)? =
    if (CLIENT_WATCH_TOGETHER_SURFACE_ENABLED) {
        { showWatchTogetherEntry = true }
    } else {
        null
    }
```

Pass:

```kotlin
onWatchTogetherClick = watchTogetherMenuAction,
```

Render beside the existing library and SiloCast sheets:

```kotlin
if (showWatchTogetherEntry) {
    WatchTogetherMenuEntrySheet(
        onNavigate = { route ->
            navController.navigate(route) {
                launchSingleTop = true
            }
        },
        onDismiss = { showWatchTogetherEntry = false },
    )
}
```

- [ ] **Step 5: Run phone menu tests and compile**

Run:

```bash
./gradlew \
  :androidApp:testDebugUnitTest \
  :androidApp:assembleDebug \
  --tests 'org.siloserver.silo.android.ui.screens.watchtogether.WatchTogetherMenuEntrySourceTest' \
  --max-workers=2
```

Expected: PASS.

- [ ] **Step 6: Commit the phone surface**

```bash
git add \
  androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/watchtogether/WatchTogetherMenuEntrySheet.kt \
  androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/components/MainAppTopBar.kt \
  androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/home/HomeScreen.kt \
  androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/libraries/LibrariesScreen.kt \
  androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/MainScreen.kt \
  androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/screens/watchtogether/WatchTogetherMenuEntrySourceTest.kt
git commit -m "feat: add Watch Together to phone profile menus"
```

## Task 4: Phone browse-versus-leave continuity and owner authority

**Files:**
- Create: `androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/screens/watchtogether/WatchTogetherLobbyContinuitySourceTest.kt`
- Modify: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/watchtogether/WatchTogetherLobbyScreen.kt:75-167`

**Interfaces:**
- Consumes: existing `WatchTogetherLobbyViewModel.leave`, `vote`, `unvote`, `promote`, and `closeRoom`
- Produces: ordinary Back/browse invokes `onBack()` without `leave()`
- Produces: an explicit **Leave room** action invokes `viewModel.leave()` then `onBack()`
- Preserves: host Close, suggestion vote/promote, and title-detail **Suggest to Watch Together**.

- [ ] **Step 1: Write the phone lobby continuity RED test**

```kotlin
package org.siloserver.silo.android.ui.screens.watchtogether

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchTogetherLobbyContinuitySourceTest {
    private val lobby = File(
        "src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/watchtogether/WatchTogetherLobbyScreen.kt",
    ).readText()
    private val detail = File(
        "src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/detail/ItemDetailScreen.kt",
    ).readText()

    @Test
    fun ordinaryBackBrowsesWithoutLeavingAndLeaveIsExplicit() {
        val navigationIcon = lobby.substringAfter("navigationIcon = {").substringBefore("actions = {")
        assertTrue(navigationIcon.contains("onClick = onBack"))
        assertFalse(navigationIcon.contains("viewModel.leave()"))
        assertTrue(lobby.contains("Text(\"Leave room\")"))
        assertTrue(lobby.contains("viewModel.leave()"))
    }

    @Test
    fun ownerControlsAndTitleSuggestionRemainReachable() {
        assertTrue(lobby.contains("viewModel.vote(s.id)"))
        assertTrue(lobby.contains("viewModel.promote(s.id)"))
        assertTrue(lobby.contains("viewModel.closeRoom()"))
        assertTrue(detail.contains("Suggest to Watch Together"))
        assertTrue(detail.contains("suggestViewModel.suggest("))
    }
}
```

- [ ] **Step 2: Run the continuity test and verify RED**

Run:

```bash
./gradlew :androidApp:testDebugUnitTest \
  --tests 'org.siloserver.silo.android.ui.screens.watchtogether.WatchTogetherLobbyContinuitySourceTest'
```

Expected: FAIL because the visible navigation icon currently calls `leave()`
and no explicit Leave row exists.

- [ ] **Step 3: Separate browse/back from explicit Leave**

Replace the top app bar navigation behavior with:

```kotlin
navigationIcon = {
    IconButton(onClick = onBack) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back to browse",
        )
    }
},
actions = {
    TextButton(
        onClick = {
            viewModel.leave()
            onBack()
        },
    ) {
        Text("Leave room")
    }
    if (canManage) {
        TextButton(onClick = { viewModel.closeRoom() }) {
            Text("Close")
        }
    }
},
```

Do not add any reset to `onCleared` or ordinary `onBack`; `RoomSession` remains
the process owner while the user browses to a detail screen and submits the
existing suggestion action.

- [ ] **Step 4: Run phone Watch Together regression tests**

Run:

```bash
./gradlew :androidApp:testDebugUnitTest \
  --tests 'org.siloserver.silo.android.ui.screens.watchtogether.*'
```

Expected: PASS.

- [ ] **Step 5: Commit phone continuity**

```bash
git add \
  androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/watchtogether/WatchTogetherLobbyScreen.kt \
  androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/screens/watchtogether/WatchTogetherLobbyContinuitySourceTest.kt
git commit -m "fix: preserve phone room while browsing"
```

## Task 5: TV entry controller and shared destination routing

**Files:**
- Create: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/watchtogether/TvWatchTogetherDestination.kt`
- Create: `androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/watchtogether/TvWatchTogetherViewModelTest.kt`
- Create: `androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/watchtogether/TvWatchTogetherDestinationTest.kt`
- Modify: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/watchtogether/TvWatchTogetherViewModel.kt:18-113`
- Modify: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvItemDetailScreen.kt:1048-1104`
- Modify: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/navigation/TvAppNavigation.kt:630-712`
- Modify: `androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/watchtogether/TvWatchTogetherSurfaceSourceTest.kt:31-43`

**Interfaces:**
- Consumes: `WatchTogetherEntryGateway`, `watchTogetherEntryTarget`, and `resumableWatchTogetherRoom`
- Produces: `val currentRoom: StateFlow<RoomSnapshot?>`
- Produces: `fun createEmptyVoteRoom()`
- Produces: `fun resumeCurrentRoom()`
- Produces: `fun tvWatchTogetherDestination(room: RoomSnapshot): String`
- Preserves: title-bound `createRoom(contentId, fileId, selectionMode)` and `joinRoom(code)`.

- [ ] **Step 1: Write the TV RED tests**

Create `TvWatchTogetherViewModelTest` with an
`UnconfinedTestDispatcher`, `Dispatchers.setMain`/`resetMain`, these tests, and
the complete local fake below:

```kotlin
package org.siloserver.silo.tv.ui.screens.watchtogether

import org.siloserver.silo.model.watchtogether.CreateRoomRequest
import org.siloserver.silo.model.watchtogether.JoinRoomRequest
import org.siloserver.silo.model.watchtogether.RoomPhase
import org.siloserver.silo.model.watchtogether.RoomResponse
import org.siloserver.silo.model.watchtogether.RoomSelectionMode
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.model.watchtogether.SetSelectionRequest
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.watchtogether.WatchTogetherEntryGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class TvWatchTogetherViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

@Test
fun emptyHostCreatesVoteRoomWithoutSelection() = runTest(dispatcher) {
    val gateway = FakeGateway()
    val viewModel = TvWatchTogetherViewModel(gateway)

    viewModel.createEmptyVoteRoom()

    assertEquals(listOf(CreateRoomRequest(RoomSelectionMode.Vote.wire)), gateway.createRequests)
    assertEquals(emptyList(), gateway.selectionRequests)
    assertEquals("room-1", viewModel.uiState.value.result?.roomId)
}

@Test
fun resumeUsesCurrentRoomWithoutNetworkCalls() = runTest(dispatcher) {
    val room = RoomSnapshot(roomId = "room-1", phase = RoomPhase.Lobby)
    val gateway = FakeGateway(room)
    val viewModel = TvWatchTogetherViewModel(gateway)

    viewModel.resumeCurrentRoom()

    assertEquals(room, viewModel.uiState.value.result)
    assertEquals(emptyList(), gateway.createRequests)
    assertEquals(emptyList(), gateway.joinRequests)
}

@Test
fun joinCodeNormalizesAndKeepsExistingErrorCopy() = runTest(dispatcher) {
    val gateway = FakeGateway()
    val viewModel = TvWatchTogetherViewModel(gateway)

    viewModel.joinRoom(" abcd1234 ")
    assertEquals(listOf(JoinRoomRequest(code = "ABCD1234")), gateway.joinRequests)

    viewModel.consumeResult()
    gateway.joinResult = ApiResult.NetworkError(IllegalStateException("offline"))
    viewModel.joinRoom("efgh5678")
    assertEquals("Network error. Check your connection.", viewModel.uiState.value.error)
}

@Test
fun titleDetailHostStillSetsSelection() = runTest(dispatcher) {
    val gateway = FakeGateway()
    val viewModel = TvWatchTogetherViewModel(gateway)

    viewModel.createRoom(contentId = "movie-1", fileId = 7)

    assertEquals(
        listOf(SetSelectionRequest(contentId = "movie-1", fileId = 7)),
        gateway.selectionRequests,
    )
}

private class FakeGateway(
    room: RoomSnapshot? = null,
) : WatchTogetherEntryGateway {
    override val roomSnapshot = MutableStateFlow(room)
    val createRequests = mutableListOf<CreateRoomRequest>()
    val joinRequests = mutableListOf<JoinRoomRequest>()
    val selectionRequests = mutableListOf<SetSelectionRequest>()
    var joinResult: ApiResult<RoomResponse>? = null
    var nextRoom = RoomSnapshot(
        roomId = "room-1",
        phase = RoomPhase.Lobby,
        selectionMode = RoomSelectionMode.Vote,
    )

    override suspend fun createRoom(
        request: CreateRoomRequest,
    ): ApiResult<RoomResponse> {
        createRequests += request
        roomSnapshot.value = nextRoom
        return ApiResult.Success(RoomResponse(nextRoom, "test-room-token"))
    }

    override suspend fun joinRoom(
        request: JoinRoomRequest,
    ): ApiResult<RoomResponse> {
        joinRequests += request
        joinResult?.let { return it }
        roomSnapshot.value = nextRoom
        return ApiResult.Success(RoomResponse(nextRoom, "test-room-token"))
    }

    override suspend fun setSelection(
        request: SetSelectionRequest,
    ): ApiResult<RoomResponse> {
        selectionRequests += request
        nextRoom = nextRoom.copy(
            selectedContentId = request.contentId,
            selectedFileId = request.fileId,
        )
        roomSnapshot.value = nextRoom
        return ApiResult.Success(RoomResponse(nextRoom, "test-room-token"))
    }
}
}
```

Add route-policy coverage:

```kotlin
package org.siloserver.silo.tv.ui.screens.watchtogether

import org.siloserver.silo.model.watchtogether.MemberRole
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.tv.ui.navigation.TvRoute
import kotlin.test.Test
import kotlin.test.assertEquals

class TvWatchTogetherDestinationTest {
    @Test
    fun emptyAndSoloHostRoomsUseLobby() {
        assertEquals(
            TvRoute.WatchTogetherLobby("room-1").route,
            tvWatchTogetherDestination(RoomSnapshot(roomId = "room-1")),
        )
        assertEquals(
            TvRoute.WatchTogetherLobby("room-1").route,
            tvWatchTogetherDestination(
                RoomSnapshot(
                    roomId = "room-1",
                    selectedContentId = "movie-1",
                    selfRole = MemberRole.Host,
                    memberCount = 1,
                ),
            ),
        )
    }

    @Test
    fun selectedJoinedRoomUsesSyncedPlayer() {
        val room = RoomSnapshot(
            roomId = "room-1",
            selectedContentId = "movie-1",
            selectedFileId = 7,
            selfRole = MemberRole.Guest,
            memberCount = 2,
            anchorPositionSeconds = 12.5,
        )
        assertEquals(
            TvRoute.Player(
                contentId = "movie-1",
                fileId = 7,
                roomId = "room-1",
                resumePositionSeconds = 12.5,
            ).route,
            tvWatchTogetherDestination(room),
        )
    }
}
```

- [ ] **Step 2: Run TV controller tests and verify RED**

Run:

```bash
./gradlew :androidTvApp:testDebugUnitTest \
  --tests 'org.siloserver.silo.tv.ui.screens.watchtogether.TvWatchTogetherViewModelTest' \
  --tests 'org.siloserver.silo.tv.ui.screens.watchtogether.TvWatchTogetherDestinationTest'
```

Expected: FAIL because the gateway constructor, menu methods, and destination
helper do not exist.

- [ ] **Step 3: Implement the TV controller additions**

Mirror the phone controller names through TV's existing naming:

```kotlin
class TvWatchTogetherViewModel(
    private val gateway: WatchTogetherEntryGateway,
) : ViewModel() {
    val currentRoom: StateFlow<RoomSnapshot?> = gateway.roomSnapshot
        .map(::resumableWatchTogetherRoom)
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            resumableWatchTogetherRoom(gateway.roomSnapshot.value),
        )

    fun createEmptyVoteRoom() {
        if (_uiState.value.isBusy) return
        _uiState.update { it.copy(isBusy = true, error = null) }
        viewModelScope.launch {
            when (
                val created = gateway.createRoom(
                    CreateRoomRequest(selectionMode = RoomSelectionMode.Vote.wire),
                )
            ) {
                is ApiResult.Success -> finish(created.data.room)
                is ApiResult.Error, is ApiResult.NetworkError ->
                    fail(created.errorMessage("Failed to create room"))
            }
        }
    }

    fun resumeCurrentRoom() {
        if (_uiState.value.isBusy) return
        val room = resumableWatchTogetherRoom(gateway.roomSnapshot.value)
        if (room == null) {
            fail("Current room is no longer available")
        } else {
            finish(room)
        }
    }
}
```

Delegate `RoomSelectionMode.Vote` in existing `createRoom` to
`createEmptyVoteRoom`, replace repository calls with gateway calls, and keep
the title HostPick sequence unchanged.

- [ ] **Step 4: Implement one TV destination helper and use it from detail**

```kotlin
package org.siloserver.silo.tv.ui.screens.watchtogether

import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.tv.ui.navigation.TvRoute
import org.siloserver.silo.watchtogether.WatchTogetherEntryTarget
import org.siloserver.silo.watchtogether.watchTogetherEntryTarget

fun tvWatchTogetherDestination(room: RoomSnapshot): String =
    when (watchTogetherEntryTarget(room)) {
        WatchTogetherEntryTarget.Lobby ->
            TvRoute.WatchTogetherLobby(room.roomId).route
        WatchTogetherEntryTarget.Player ->
            TvRoute.Player(
                contentId = requireNotNull(room.selectedContentId),
                fileId = room.selectedFileId,
                roomId = room.roomId,
                resumePositionSeconds = room.anchorPositionSeconds
                    .takeIf { it.isFinite() && it > 0.0 },
            ).route
    }
```

Replace the inline target calculation in `TvAppNavigation`'s existing detail
callback with:

```kotlin
onWatchTogether = { snapshot ->
    navController.navigate(tvWatchTogetherDestination(snapshot))
},
```

Change the title-detail vote callback to `createEmptyVoteRoom()`; keep its
normal Host callback title-bound.

Update `TvWatchTogetherSurfaceSourceTest.aResolvedRoomReachesTheNavigationCallback`
to assert:

```kotlin
assertTrue(appNavigation.contains("tvWatchTogetherDestination(snapshot)"))
```

- [ ] **Step 5: Run TV controller, route, and existing surface tests**

Run:

```bash
./gradlew :androidTvApp:testDebugUnitTest \
  --tests 'org.siloserver.silo.tv.ui.screens.watchtogether.*'
```

Expected: PASS.

- [ ] **Step 6: Commit TV controller/routing**

```bash
git add \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/watchtogether/TvWatchTogetherDestination.kt \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/watchtogether/TvWatchTogetherViewModel.kt \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvItemDetailScreen.kt \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/navigation/TvAppNavigation.kt \
  androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/watchtogether/TvWatchTogetherViewModelTest.kt \
  androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/watchtogether/TvWatchTogetherDestinationTest.kt \
  androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/watchtogether/TvWatchTogetherSurfaceSourceTest.kt
git commit -m "feat: add TV Watch Together menu actions"
```

## Task 6: TV profile row, focusable popup, and Back restoration

**Files:**
- Create: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/watchtogether/TvWatchTogetherMenuEntryDialog.kt`
- Create: `androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/watchtogether/TvWatchTogetherMenuEntrySourceTest.kt`
- Modify: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/shell/TvMainShell.kt:166-180,607-610,1270-1313,1478-1563`
- Modify: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/navigation/TvAppNavigation.kt:480-545`
- Modify: `androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/shell/TvShellFocusStateTest.kt`

**Interfaces:**
- Produces: `enum class TvWatchTogetherMenuInitialAction { Resume, Host }`
- Produces: `fun tvWatchTogetherMenuInitialAction(canResume: Boolean): TvWatchTogetherMenuInitialAction`
- Produces: `@Composable fun TvWatchTogetherMenuEntryDialog(...)`
- Adds to `TvMainShell`: `onOpenWatchTogether: (RoomSnapshot) -> Unit`
- Consumes: existing `TvJoinCodeDialog`, `TvWatchTogetherViewModel`, `TvProfileDropdown`, and `TvShellFocusState`.

- [ ] **Step 1: Write TV popup/menu/focus RED tests**

```kotlin
package org.siloserver.silo.tv.ui.screens.watchtogether

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvWatchTogetherMenuEntrySourceTest {
    private val shell = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/shell/TvMainShell.kt",
    ).readText()
    private val dialog = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/watchtogether/TvWatchTogetherMenuEntryDialog.kt",
    ).readText()

    @Test
    fun profileRowIsImmediatelyAfterRequestsAndBeforeSettings() {
        val profile = shell.substringAfter("private fun TvProfileDropdown(")
        val watch = profile.indexOf("label = \"Watch Together\"")
        val requests = profile.indexOf("label = \"Requests\"")
        val settings = profile.indexOf("label = \"Settings\"")
        assertTrue(watch >= 0)
        assertTrue(requests < watch)
        assertTrue(watch < settings)
        assertFalse(
            profile.substring(
                startIndex = requests + "label = \"Requests\"".length,
                endIndex = watch,
            ).contains("ProfileDropdownRow("),
        )
        assertTrue(shell.contains("CLIENT_WATCH_TOGETHER_SURFACE_ENABLED"))
    }

    @Test
    fun popupOwnsFocusAndBackRestoresProfileFocus() {
        assertTrue(dialog.contains("PopupProperties("))
        assertTrue(dialog.contains("focusable = true"))
        assertTrue(dialog.contains("rememberTvDialogInitialFocus(initialFocus)"))
        assertTrue(shell.contains("focusState.closeProfileMenuForContent()"))
        assertTrue(shell.contains("focusState.dismissProfileMenu()"))
    }

    @Test
    fun menuSurfaceUsesExistingControllerAndNoCredentials() {
        assertTrue(shell.contains("watchTogetherViewModel.createEmptyVoteRoom()"))
        assertTrue(shell.contains("watchTogetherViewModel.resumeCurrentRoom()"))
        assertTrue(shell.contains("TvJoinCodeDialog("))
        listOf("Resume current room", "Host a room", "Join by code").forEach { label ->
            assertTrue(dialog.contains(label))
        }
        assertFalse(dialog.contains("WatchTogetherApi"))
        assertFalse(dialog.contains("roomAccessToken"))
        assertFalse(dialog.contains("HttpClient"))
    }

    @Test
    fun initialActionPrefersResumeOnlyWhenAvailable() {
        assertEquals(
            TvWatchTogetherMenuInitialAction.Resume,
            tvWatchTogetherMenuInitialAction(canResume = true),
        )
        assertEquals(
            TvWatchTogetherMenuInitialAction.Host,
            tvWatchTogetherMenuInitialAction(canResume = false),
        )
    }
}
```

Add a focus-state regression to `TvShellFocusStateTest`:

```kotlin
@Test
fun closingMenuForPopupThenDismissingPopupRefocusesAvatar() {
    val state = TvShellFocusState()
    state.previewProfileMenu()
    state.enterProfileMenu()
    val before = state.profileFocusRequest

    state.closeProfileMenuForContent()
    assertEquals(before, state.profileFocusRequest)

    state.dismissProfileMenu()
    assertEquals(before + 1, state.profileFocusRequest)
}
```

- [ ] **Step 2: Run TV popup tests and verify RED**

Run:

```bash
./gradlew :androidTvApp:testDebugUnitTest \
  --tests 'org.siloserver.silo.tv.ui.screens.watchtogether.TvWatchTogetherMenuEntrySourceTest' \
  --tests 'org.siloserver.silo.tv.ui.shell.TvShellFocusStateTest'
```

Expected: FAIL because the dialog, row, callback, and initial-focus policy do
not exist.

- [ ] **Step 3: Implement the focused TV popup**

```kotlin
enum class TvWatchTogetherMenuInitialAction {
    Resume,
    Host,
}

fun tvWatchTogetherMenuInitialAction(
    canResume: Boolean,
): TvWatchTogetherMenuInitialAction =
    if (canResume) {
        TvWatchTogetherMenuInitialAction.Resume
    } else {
        TvWatchTogetherMenuInitialAction.Host
    }
```

The popup uses stable requesters and selects the initial requester from the
pure policy:

```kotlin
@Composable
fun TvWatchTogetherMenuEntryDialog(
    canResume: Boolean,
    isBusy: Boolean,
    error: String?,
    onResume: () -> Unit,
    onHost: () -> Unit,
    onJoin: () -> Unit,
    onDismiss: () -> Unit,
) {
    val resumeFocus = remember { FocusRequester() }
    val hostFocus = remember { FocusRequester() }
    val initialAction = tvWatchTogetherMenuInitialAction(canResume)
    val initialFocus = when (initialAction) {
        TvWatchTogetherMenuInitialAction.Resume -> resumeFocus
        TvWatchTogetherMenuInitialAction.Host -> hostFocus
    }

    Popup(
        alignment = Alignment.Center,
        onDismissRequest = {
            if (canDismissRoomEntry(isBusy)) onDismiss()
        },
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = canDismissRoomEntry(isBusy),
            dismissOnClickOutside = canDismissRoomEntry(isBusy),
            clippingEnabled = false,
        ),
    ) {
        Column(
            modifier = Modifier
                .width(340.dp)
                .then(rememberTvDialogInitialFocus(initialFocus)),
        ) {
            if (canResume) {
                TvDialogActionRow(
                    title = "Resume current room",
                    enabled = !isBusy,
                    onClick = onResume,
                    modifier = Modifier.focusRequester(resumeFocus),
                )
            }
            TvDialogActionRow(
                title = if (isBusy) "Working…" else "Host a room",
                enabled = !isBusy,
                onClick = onHost,
                modifier = Modifier.focusRequester(hostFocus),
            )
            TvDialogActionRow(
                title = "Join by code",
                enabled = !isBusy,
                onClick = onJoin,
            )
            error?.let { Text(it, color = Color(0xFFEF4444)) }
        }
    }
}
```

Wrap the column in a full-screen centered `Box` padded
`start = 36.dp, top = 50.dp, end = 36.dp, bottom = 42.dp`. Give the 340.dp
column a 14.dp rounded shape, `DarkBackground.copy(alpha = 0.68f)`, a 0.6.dp
`Color.White.copy(alpha = 0.20f)` border, 14.dp horizontal/vertical padding,
and 10.dp item spacing. Render the heading as `"WATCH TOGETHER"` using
`labelMedium`, 16.sp, 1.1.sp letter spacing, bold weight, and white at 0.58
alpha.

- [ ] **Step 4: Wire popup state and the profile row into `TvMainShell`**

Add the callback:

```kotlin
import org.koin.compose.viewmodel.koinViewModel
import org.siloserver.silo.model.feature.CLIENT_WATCH_TOGETHER_SURFACE_ENABLED
import org.siloserver.silo.tv.ui.screens.watchtogether.TvWatchTogetherMenuEntryDialog
import org.siloserver.silo.tv.ui.screens.watchtogether.TvWatchTogetherViewModel

onOpenWatchTogether: (RoomSnapshot) -> Unit,
```

Collect the existing controller and own transient flags in the shell:

```kotlin
val watchTogetherViewModel = koinViewModel<TvWatchTogetherViewModel>()
val watchTogetherState by watchTogetherViewModel.uiState.collectAsState()
val currentWatchTogetherRoom by watchTogetherViewModel.currentRoom.collectAsState()
var watchTogetherEntryOpen by rememberSaveable { mutableStateOf(false) }
var watchTogetherJoinOpen by rememberSaveable { mutableStateOf(false) }
```

Consume one-shot results:

```kotlin
LaunchedEffect(watchTogetherState.result) {
    val room = watchTogetherState.result ?: return@LaunchedEffect
    watchTogetherViewModel.consumeResult()
    watchTogetherEntryOpen = false
    watchTogetherJoinOpen = false
    onOpenWatchTogether(room)
}
```

Add `showWatchTogether: Boolean` and `onWatchTogether: () -> Unit` to
`TvProfileDropdown`; pass
`showWatchTogether = CLIENT_WATCH_TOGETHER_SURFACE_ENABLED`, and invoke
`focusState.closeProfileMenuForContent()` before opening the popup. Insert:

```kotlin
onWatchTogether = {
    focusState.closeProfileMenuForContent()
    watchTogetherViewModel.clearError()
    watchTogetherEntryOpen = true
},

if (showWatchTogether) {
    ProfileDropdownRow(
        label = "Watch Together",
        icon = Icons.Filled.People,
        onClick = onWatchTogether,
    )
}
```

Place it immediately after the conditional Requests row. When Requests is
hidden, Watch Together remains after History and before the content/settings
divider.

Render `TvJoinCodeDialog` or `TvWatchTogetherMenuEntryDialog` after the profile
dropdown:

```kotlin
if (watchTogetherEntryOpen) {
    if (watchTogetherJoinOpen) {
        TvJoinCodeDialog(
            isBusy = watchTogetherState.isBusy,
            error = watchTogetherState.error,
            onJoin = watchTogetherViewModel::joinRoom,
            onDismiss = {
                watchTogetherViewModel.clearError()
                watchTogetherJoinOpen = false
            },
        )
    } else {
        TvWatchTogetherMenuEntryDialog(
            canResume = currentWatchTogetherRoom != null,
            isBusy = watchTogetherState.isBusy,
            error = watchTogetherState.error,
            onResume = watchTogetherViewModel::resumeCurrentRoom,
            onHost = watchTogetherViewModel::createEmptyVoteRoom,
            onJoin = {
                watchTogetherViewModel.clearError()
                watchTogetherJoinOpen = true
            },
            onDismiss = {
                watchTogetherViewModel.clearError()
                watchTogetherEntryOpen = false
                watchTogetherJoinOpen = false
                focusState.dismissProfileMenu()
            },
        )
    }
}
```

Switching from entry to join does not restore avatar focus; only dismissal of
the outer entry popup does.

- [ ] **Step 5: Route shell results through the existing root destinations**

At the `TvMainShell` call in `TvAppNavigation`, pass:

```kotlin
onOpenWatchTogether = { room ->
    navController.navigate(tvWatchTogetherDestination(room)) {
        launchSingleTop = true
    }
},
```

Do not add a new `TvRoute`.

- [ ] **Step 6: Run TV popup, shell focus, and compile gates**

Run:

```bash
./gradlew \
  :androidTvApp:testDebugUnitTest \
  :androidTvApp:assembleDebug \
  --tests 'org.siloserver.silo.tv.ui.screens.watchtogether.*' \
  --tests 'org.siloserver.silo.tv.ui.shell.TvShellFocusStateTest' \
  --max-workers=2
```

Expected: PASS.

- [ ] **Step 7: Commit the TV surface**

```bash
git add \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/watchtogether/TvWatchTogetherMenuEntryDialog.kt \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/shell/TvMainShell.kt \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/navigation/TvAppNavigation.kt \
  androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/watchtogether/TvWatchTogetherMenuEntrySourceTest.kt \
  androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/shell/TvShellFocusStateTest.kt
git commit -m "feat: add Watch Together to TV profile menu"
```

## Task 7: TV browse-versus-leave continuity and owner authority

**Files:**
- Create: `androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/watchtogether/TvWatchTogetherLobbyContinuitySourceTest.kt`
- Modify: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/watchtogether/TvWatchTogetherLobbyScreen.kt:120-175,250-330`

**Interfaces:**
- Consumes: existing `onBack`, `TvWatchTogetherLobbyViewModel.leave`, `vote`, `unvote`, `promote`, and `closeRoom`
- Produces: D-pad Back and **Browse titles** return without leaving.
- Produces: explicit **Leave room** clears through `RoomSession.depart`.
- Preserves: host Close, host override, suggestion voting, and title-detail suggestion entry.

- [ ] **Step 1: Write the TV lobby continuity RED test**

```kotlin
package org.siloserver.silo.tv.ui.screens.watchtogether

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvWatchTogetherLobbyContinuitySourceTest {
    private val lobby = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/watchtogether/TvWatchTogetherLobbyScreen.kt",
    ).readText()
    private val detail = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvItemDetailScreen.kt",
    ).readText()

    @Test
    fun backAndBrowsePreserveRoomWhileLeaveIsExplicit() {
        val backHandler = lobby.substringAfter("BackHandler(enabled = true)")
            .substringBefore("Box(")
        assertTrue(backHandler.contains("onBack()"))
        assertFalse(backHandler.contains("viewModel.leave()"))
        assertTrue(lobby.contains("title = \"Browse titles\""))
        assertTrue(lobby.contains("title = \"Leave room\""))
        assertTrue(lobby.contains("viewModel.leave()"))
    }

    @Test
    fun existingOwnerAuthorityAndSuggestionPathRemain() {
        assertTrue(lobby.contains("CloseRoomButton(onClick = viewModel::closeRoom)"))
        assertTrue(lobby.contains("viewModel.vote(s.id)"))
        assertTrue(lobby.contains("viewModel.promote(s.id)"))
        assertTrue(detail.contains("Suggest to Watch Together"))
        assertTrue(detail.contains("suggestViewModel.suggest("))
    }
}
```

- [ ] **Step 2: Run the TV continuity test and verify RED**

Run:

```bash
./gradlew :androidTvApp:testDebugUnitTest \
  --tests 'org.siloserver.silo.tv.ui.screens.watchtogether.TvWatchTogetherLobbyContinuitySourceTest'
```

Expected: FAIL because TV Back currently calls `leave()` and the two explicit
rows do not exist.

- [ ] **Step 3: Make TV Back browse and add explicit Browse/Leave rows**

Change ordinary Back:

```kotlin
BackHandler(enabled = true) {
    onBack()
}
```

Before host-only policy/close controls, add:

```kotlin
TvDialogActionRow(
    title = "Browse titles",
    onClick = onBack,
)
TvDialogActionRow(
    title = "Leave room",
    onClick = {
        viewModel.leave()
        onBack()
    },
)
```

Keep the terminal `closedReason` effect calling `leave()` and keep the explicit
host `CloseRoomButton`; those are teardown actions, unlike ordinary browse.

- [ ] **Step 4: Run all TV Watch Together and focus tests**

Run:

```bash
./gradlew :androidTvApp:testDebugUnitTest \
  --tests 'org.siloserver.silo.tv.ui.screens.watchtogether.*' \
  --tests 'org.siloserver.silo.tv.ui.shell.TvShellFocusStateTest'
```

Expected: PASS.

- [ ] **Step 5: Commit TV continuity**

```bash
git add \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/watchtogether/TvWatchTogetherLobbyScreen.kt \
  androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/watchtogether/TvWatchTogetherLobbyContinuitySourceTest.kt
git commit -m "fix: preserve TV room while browsing"
```

## Task 8: Preserve owner operations, identity teardown, auth, cleartext, and error boundaries

**Files:**
- Modify: `shared/src/commonTest/kotlin/org/siloserver/silo/repository/WatchTogetherRepositoryTest.kt:40-143,776-866`
- Modify: `shared/src/commonTest/kotlin/org/siloserver/silo/watchtogether/RoomSessionTest.kt:122-184`
- Modify: `androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/screens/watchtogether/WatchTogetherMenuEntrySourceTest.kt`
- Modify: `androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/watchtogether/TvWatchTogetherMenuEntrySourceTest.kt`

**Interfaces:**
- Consumes only existing repository methods: `addSuggestion`, `vote`, `promoteSuggestion`, `closeRoom`, and identity-transition reset.
- Produces test evidence that the top-level entry changes presentation only.
- Must not modify `WatchTogetherApi.kt`, `WatchTogetherModels.kt`, `WatchTogetherRealtimeClient.kt`, `CleartextOriginConsent.kt`, or server/proxy files.

- [ ] **Step 1: Add a repository authority characterization**

Extend the existing `FakeApi` with counters for create, suggestion, vote,
promote, and close, then add:

```kotlin
import org.siloserver.silo.model.watchtogether.MemberRole
import org.siloserver.silo.model.watchtogether.RoomSelectionMode

var createCalls = 0
var addSuggestionCalls = 0
var voteCalls = 0
var promoteCalls = 0
var closeCalls = 0

override suspend fun createRoom(
    request: CreateRoomRequest,
    scope: AuthScopeSnapshot,
): ApiResult<RoomResponse> {
    createCalls++
    lastAuthScope = scope
    return createResult?.await() ?: createResponse
}

override suspend fun addSuggestion(
    roomId: String,
    roomToken: String,
    request: AddSuggestionRequest,
    scope: AuthScopeSnapshot,
): ApiResult<SuggestionsResponse> {
    addSuggestionCalls++
    lastRoomToken = roomToken
    lastAuthScope = scope
    return ApiResult.Success(SuggestionsResponse())
}

override suspend fun vote(
    roomId: String,
    roomToken: String,
    suggestionId: String,
    scope: AuthScopeSnapshot,
): ApiResult<SuggestionsResponse> {
    voteCalls++
    lastRoomToken = roomToken
    lastAuthScope = scope
    return ApiResult.Success(SuggestionsResponse())
}

override suspend fun promoteSuggestion(
    roomId: String,
    roomToken: String,
    request: PromoteSuggestionRequest,
    scope: AuthScopeSnapshot,
): ApiResult<RoomResponse> {
    promoteCalls++
    lastRoomToken = roomToken
    lastAuthScope = scope
    return createResponse
}

override suspend fun closeRoom(
    roomId: String,
    roomToken: String,
    scope: AuthScopeSnapshot,
): ApiResult<Unit> {
    closeCalls++
    lastRoomToken = roomToken
    lastAuthScope = scope
    return ApiResult.Success(Unit)
}

@Test
fun `empty vote room owner keeps existing suggestion vote override and close authority`() = runTest {
    val api = FakeApi(
        createResponse = ApiResult.Success(
            RoomResponse(
                room = RoomSnapshot(
                    roomId = "room-1",
                    selectionMode = RoomSelectionMode.Vote,
                    selfRole = MemberRole.Host,
                    selfCanManageRoom = true,
                ),
                roomAccessToken = "room-token",
            ),
        ),
    )
    val repository = WatchTogetherRepository(
        api = api,
        authScopeProvider = { scopeA },
    )

    repository.createRoom(CreateRoomRequest(selectionMode = RoomSelectionMode.Vote.wire))
    repository.addSuggestion(
        AddSuggestionRequest(
            contentId = "movie-1",
            contentType = "movie",
            title = "Movie One",
        ),
    )
    repository.vote("suggestion-1")
    repository.promoteSuggestion(PromoteSuggestionRequest("suggestion-1"))
    repository.closeRoom()

    assertEquals(1, api.createCalls)
    assertEquals(1, api.addSuggestionCalls)
    assertEquals(1, api.voteCalls)
    assertEquals(1, api.promoteCalls)
    assertEquals(1, api.closeCalls)
    assertEquals("room-token", api.lastRoomToken)
}
```

This is characterization of current authority; it should pass without
production changes.

- [ ] **Step 2: Add an explicit same-profile/session lifecycle assertion**

Add to `RoomSessionTest`:

```kotlin
@Test
fun `room remains adopted until explicit leave or identity transition`() = runTest {
    val repository = FakeRoomSessionRepository()
    val barrier = DefaultIdentityTransitionBarrier()
    val session = RoomSession(repository, backgroundScope, barrier)

    session.adopt("room-a").join()
    runCurrent()
    assertTrue(session.isActive())
    assertEquals(0, repository.resetCount)

    barrier.changing(IdentityTransitionKind.PROFILE_SWITCH) {
        assertTrue(!session.isActive())
        assertEquals(1, repository.resetCount)
    }
}
```

Add a dedicated logout assertion so the profile-switch example is not treated
as sufficient coverage for sign-out:

```kotlin
@Test
fun `sign out clears the adopted room before identity mutation`() = runTest {
    val repository = FakeRoomSessionRepository()
    val barrier = DefaultIdentityTransitionBarrier()
    val session = RoomSession(repository, backgroundScope, barrier)
    session.adopt("room-a").join()
    runCurrent()

    barrier.changing(IdentityTransitionKind.SIGN_OUT) {
        assertTrue(!session.isActive())
        assertEquals(1, repository.resetCount)
    }
}
```

Retain the existing all-transition-kinds regression so `SERVER_SWITCH`,
`PROFILE_SWITCH`, and future identity transition kinds remain covered as well.

- [ ] **Step 3: Strengthen source boundary assertions**

In both menu source tests, assert that the new UI files do not contain:

```kotlin
assertFalse(source.contains("room_token"))
assertFalse(source.contains("roomAccessToken"))
assertFalse(source.contains("Authorization"))
assertFalse(source.contains("CleartextOriginConsent"))
```

Also assert error presentation still comes from each existing ViewModel state:

```kotlin
assertTrue(source.contains("state.error"))
```

- [ ] **Step 4: Run authority, lifecycle, cleartext, and entry error suites**

Run:

```bash
./gradlew :shared:testDebugUnitTest \
  --tests 'org.siloserver.silo.repository.WatchTogetherRepositoryTest' \
  --tests 'org.siloserver.silo.watchtogether.RoomSessionTest'
./gradlew :androidApp:testDebugUnitTest \
  --tests 'org.siloserver.silo.android.ui.screens.auth.ServerSetupCleartextWarningTest' \
  --tests 'org.siloserver.silo.android.ui.screens.watchtogether.*'
./gradlew :androidTvApp:testDebugUnitTest \
  --tests 'org.siloserver.silo.tv.ui.screens.auth.TvServerSetupCleartextWarningTest' \
  --tests 'org.siloserver.silo.tv.ui.screens.watchtogether.*' \
  --max-workers=2
```

Expected: PASS.

- [ ] **Step 5: Verify no protocol, cleartext-policy, or server files changed**

Run:

```bash
test -z "$(git diff --name-only origin/main...HEAD -- \
  shared/src/commonMain/kotlin/org/siloserver/silo/network/api/WatchTogetherApi.kt \
  shared/src/commonMain/kotlin/org/siloserver/silo/model/watchtogether/WatchTogetherModels.kt \
  shared/src/commonMain/kotlin/org/siloserver/silo/network/WatchTogetherRealtimeClient.kt \
  shared/src/commonMain/kotlin/org/siloserver/silo/network/CleartextOriginConsent.kt)"
```

Expected: exit 0 and no output.

- [ ] **Step 6: Commit the preservation tests**

```bash
git add \
  shared/src/commonTest/kotlin/org/siloserver/silo/repository/WatchTogetherRepositoryTest.kt \
  shared/src/commonTest/kotlin/org/siloserver/silo/watchtogether/RoomSessionTest.kt \
  androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/screens/watchtogether/WatchTogetherMenuEntrySourceTest.kt \
  androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/watchtogether/TvWatchTogetherMenuEntrySourceTest.kt
git commit -m "test: lock Watch Together menu boundaries"
```

## Task 9: Full verification, two-client device smoke, and review gate

**Files:**
- Verify only; do not create a tracked evidence file containing device IDs,
  invite codes, tokens, URLs, or logs.

**Interfaces:**
- Consumes the complete feature from Tasks 1-8.
- Produces fresh build/test/device evidence for review.

- [ ] **Step 1: Run formatting/diff and supply-chain policy checks**

Run:

```bash
git diff --check origin/main...HEAD
./scripts/test-check-build-supply-chain.sh
./scripts/check-build-supply-chain.sh
```

Expected: all commands exit 0.

- [ ] **Step 2: Run focused tests once more**

Run:

```bash
./gradlew :shared:testDebugUnitTest \
  --tests 'org.siloserver.silo.watchtogether.WatchTogetherEntryPolicyTest' \
  --tests 'org.siloserver.silo.repository.WatchTogetherRepositoryTest' \
  --tests 'org.siloserver.silo.watchtogether.RoomSessionTest'
./gradlew :androidApp:testDebugUnitTest \
  --tests 'org.siloserver.silo.android.ui.screens.watchtogether.*' \
  --max-workers=2
./gradlew :androidTvApp:testDebugUnitTest \
  --tests 'org.siloserver.silo.tv.ui.screens.watchtogether.*' \
  --tests 'org.siloserver.silo.tv.ui.shell.TvShellFocusStateTest' \
  --max-workers=2
```

Expected: PASS with zero failed tests.

- [ ] **Step 3: Run the full unit and debug build gate**

Run:

```bash
./gradlew \
  test \
  :androidApp:assembleDebug \
  :androidTvApp:assembleDebug \
  --max-workers=2
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Run minified release assembly for both clients**

Use the repository-supported local signing path only:

```bash
./gradlew \
  :androidApp:assembleRelease \
  :androidTvApp:assembleRelease \
  -PallowDebugReleaseSigning=true \
  --max-workers=2
```

Expected: `BUILD SUCCESSFUL`. Do not expose keystore paths, passwords, or
certificate material.

- [ ] **Step 5: Start or select only dedicated test emulators**

Prefer the existing `Silo_Phone` and `Silo_TV` AVDs and fixed serials:

```bash
"${ANDROID_HOME}/emulator/emulator" -avd Silo_TV -port 5554 -no-snapshot-save &
"${ANDROID_HOME}/emulator/emulator" -avd Silo_Phone -port 5556 -no-snapshot-save &
adb -s emulator-5554 wait-for-device
adb -s emulator-5556 wait-for-device
adb -s emulator-5554 shell getprop ro.build.characteristics
adb -s emulator-5556 shell getprop ro.build.characteristics
```

Expected: `emulator-5554` reports TV characteristics and `emulator-5556`
reports a phone profile. If either serial belongs to another running device,
stop and choose unused even-numbered emulator ports; never issue an unscoped
`adb install`, `adb shell`, clear-data, or uninstall command.

- [ ] **Step 6: Install debug APKs only on the matching dedicated emulators**

Run:

```bash
adb -s emulator-5556 install -r \
  androidApp/build/outputs/apk/debug/androidApp-arm64-v8a-debug.apk
adb -s emulator-5554 install -r \
  androidTvApp/build/outputs/apk/debug/androidTvApp-arm64-v8a-debug.apk
adb -s emulator-5556 shell am start \
  -n org.siloserver.silo/.android.MainActivity
adb -s emulator-5554 shell am start \
  -n org.siloserver.silo/.tv.MainTvActivity
```

If the output APK names differ, resolve only within each module's
`build/outputs/apk/debug/` directory with `find`, verify ABI using `apkanalyzer`,
then repeat the serial-scoped install.

- [ ] **Step 7: Execute the phone touch smoke matrix**

On the authenticated phone test profile:

1. Open profile menus from Home, Libraries, and For You; verify Requests appears
   immediately before Watch Together when enabled, and Watch Together remains
   immediately before the settings/account divider when Requests is disabled.
2. Open the sheet; with no room verify Host receives the primary position,
   Join opens code input, Back returns to entry, and dismissal returns to the
   same tab.
3. Host a room; verify the lobby shows vote mode and no selected title.
4. Use Back to browse without leaving, open a movie/episode detail, and verify
   **Suggest to Watch Together** is present and can submit a suggestion.
5. Reopen the profile menu; verify **Resume current room** appears and returns
   to the same room.
6. Verify the owner can vote, use the existing host override, and close the
   room for everyone.
7. Verify explicit **Leave room** removes Resume.
8. Recreate a room, background/foreground the app, and verify Resume remains.
9. Switch profile or log out on the dedicated test account and verify Resume is
   absent after returning to an authenticated shell.
10. Recreate a room, run
    `adb -s emulator-5556 shell am force-stop org.siloserver.silo`, relaunch the
    phone activity, and verify Resume is absent while persisted login/profile
    data remains intact.
11. Enter an invalid join code and verify the existing inline error appears
    without navigation or crash.

- [ ] **Step 8: Execute the TV D-pad and cross-client smoke matrix**

On the authenticated TV test profile:

1. Open the profile dropdown; verify Watch Together follows History and
   precedes Requests/Settings.
2. With no room, select the row and verify Host receives initial focus. Press
   Back and verify focus returns to the profile avatar without entering content
   behind the popup.
3. Host an empty room and join it from the phone by code.
4. Use TV Back or **Browse titles**; verify the room remains active. Open a
   title detail and submit the existing **Suggest to Watch Together** action.
5. Reopen the TV profile popup; verify Resume is first and initially focused.
6. Resume the lobby; vote from both participants, verify the TV owner can
   override any room-owned suggestion, then close the room and verify both
   clients receive closure.
7. Recreate a room, background and foreground TV with Home/Recent, and verify
   Resume persists.
8. Use explicit Leave and verify Resume disappears.
9. Verify Join by code handles D-pad entry, Back-to-entry focus, invalid-code
   errors, and selected-room routing to the synchronized player.
10. Confirm the title-detail Watch Together action still hosts with that title
    preselected rather than creating an empty room.
11. Recreate a room, run
    `adb -s emulator-5554 shell am force-stop org.siloserver.silo`, relaunch the
    TV activity, and verify the process-scoped Resume action is absent.

Do not alter production proxy settings or server host-timeout configuration.

- [ ] **Step 9: Capture non-secret crash/ANR evidence**

Run immediately after the smoke matrix:

```bash
adb -s emulator-5556 shell pidof org.siloserver.silo
adb -s emulator-5554 shell pidof org.siloserver.silo
adb -s emulator-5556 logcat -d -t 1500 |
  rg -i 'FATAL EXCEPTION|ANR in org\.siloserver\.silo|am_crash.*org\.siloserver\.silo' || true
adb -s emulator-5554 logcat -d -t 1500 |
  rg -i 'FATAL EXCEPTION|ANR in org\.siloserver\.silo|am_crash.*org\.siloserver\.silo' || true
```

Expected: both PIDs exist and neither filtered log contains an app crash or
ANR. Do not save raw logcat if it includes URLs, invite codes, or tokens.

- [ ] **Step 10: Request independent code and security review**

Ask the reviewer to inspect:

- shared gateway/policy as a view over the existing singleton, not a second
  owner;
- empty vote-room creation for exactly one create and zero `setSelection`;
- identity and explicit-leave teardown;
- phone/TV route parity;
- TV initial focus and Back restoration;
- title-detail preselection regression;
- absence of credentials, direct API calls, server/protocol changes, and
  cleartext bypasses.

Address findings test-first, rerun the smallest affected focused test, then
rerun Steps 1-4.

- [ ] **Step 11: Verify final branch state**

Run:

```bash
git status --short
git log --oneline --decorate origin/main..HEAD
git diff --stat origin/main...HEAD
git diff --check origin/main...HEAD
```

Expected: clean worktree, the spec/plan plus small feature commits, and no
uncommitted or generated files.
