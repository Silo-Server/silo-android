package org.siloserver.silo.android.ui.screens.reader

import org.siloserver.silo.network.apiv2.ApiV2Gate

import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.siloserver.silo.common.data.db.SiloDatabase
import org.siloserver.silo.common.downloads.DownloadMetadataStore
import org.siloserver.silo.common.downloads.DownloadStorage
import org.siloserver.silo.common.downloads.DownloadTarget
import org.siloserver.silo.common.downloads.LegacyDownloadImporter
import org.siloserver.silo.common.downloads.OfflineMediaResolver
import org.siloserver.silo.common.ebook.EbookLocalStateStore
import org.siloserver.silo.model.book.BookFormat
import org.siloserver.silo.model.download.DownloadRecord
import org.siloserver.silo.model.download.DownloadSidecar
import org.siloserver.silo.model.ebook.EbookReadMode
import org.siloserver.silo.model.server.ServerEntry
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.api.CatalogApi
import org.siloserver.silo.network.api.EbookReaderApi
import org.siloserver.silo.network.api.ProfileApi
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.repository.CatalogRepository
import org.siloserver.silo.repository.EbookReaderRepository
import org.siloserver.silo.repository.ProfileRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ReaderViewModelReaderTargetSourceTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()

    // Download metadata is Room-backed now; the resolver reads it via this store.
    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        SiloDatabase::class.java,
    ).allowMainThreadQueries().build()

    private val metadata = DownloadMetadataStore(db)

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    @Test
    fun viewModelUsesReaderTargetSelectionInsteadOfInAppOnlySelection() {
        val source = java.io.File(
            "src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/reader/ReaderViewModel.kt",
        ).readText()

        assertTrue(source.contains("chooseReaderVersion("))
        assertFalse(source.contains("chooseEbookVersion("))
    }

    @Test
    fun externalOnlyTargetsRemainVisibleInReaderState() {
        val source = java.io.File(
            "src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/reader/ReaderViewModel.kt",
        ).readText()

        assertTrue(source.contains("EbookReadMode.ExternalOnly"))
        assertTrue(source.contains("readMode = target.support.readMode"))
        assertTrue(source.contains("Download this original to open it with another reader."))
    }

    @Test
    fun externalOnlyOnlineTargetWithoutLocalDownloadPopulatesExternalState() = runTest(dispatcher) {
        val vm = viewModel(
            catalogRepository = catalogRepository(
                responseBody = itemDetailJson(fileName = "book.mobi", container = "mobi"),
            ),
            downloadStorage = DownloadStorage(tmp.newFolder("downloads")),
        )

        advanceUntilIdle()
        val state = vm.awaitLoaded()

        assertEquals(EbookReadMode.ExternalOnly, state.readMode)
        assertEquals(BookFormat.Mobi, state.format)
        assertEquals("MOBI", state.formatDisplayName)
        assertNull(state.fileUrl)
        assertNull(state.localUri)
        assertEquals("book.mobi", state.localDisplayName)
        assertEquals("Download this original to open it with another reader.", state.error)
    }

    @Test
    fun offlineOnlyExternalOnlyLocalMediaPopulatesDownloadedOriginalState() = runTest(dispatcher) {
        val storage = DownloadStorage(tmp.newFolder("downloads"))
        storage.writeCompletedDownload(fileName = "book.mobi")

        val vm = viewModel(
            catalogRepository = catalogRepository(status = HttpStatusCode.InternalServerError),
            downloadStorage = storage,
        )

        advanceUntilIdle()
        val state = vm.awaitLoaded()

        assertEquals(EbookReadMode.ExternalOnly, state.readMode)
        assertEquals(BookFormat.Mobi, state.format)
        assertEquals("MOBI", state.formatDisplayName)
        assertTrue(state.fileUrl?.startsWith("file://") == true)
        assertTrue(state.fileUrl?.contains("book.mobi") == true)
        assertEquals(state.fileUrl, state.localUri)
        assertEquals("book.mobi", state.localDisplayName)
        assertNull(state.error)
    }

    @Test
    fun onlineMissingRequestedFileIdFallsThroughToReadableTarget() = runTest(dispatcher) {
        // chooseReaderVersion (Task 3): when the requested fileId is absent the
        // selection falls through to the best available readable version, consistent
        // with chooseEbookVersion. The catalog here only has fileId=8 (epub); the
        // requested fileId=7 is not present, so the reader should open fileId=8.
        val vm = viewModel(
            catalogRepository = catalogRepository(
                responseBody = itemDetailJson(fileId = 8, fileName = "other.epub", container = "epub"),
            ),
            downloadStorage = DownloadStorage(tmp.newFolder("downloads")),
            requestedFileId = FILE_ID,
        )

        advanceUntilIdle()
        val state = vm.awaitLoaded()

        assertEquals("Book", state.title)
        assertEquals("Ada Author", state.author)
        assertEquals(8, state.fileId)
        assertEquals(BookFormat.Epub, state.format)
        assertEquals(EbookReadMode.InApp, state.readMode)
        assertNull(state.error)
    }

    @Test
    fun onlineNoRequestUsesSelectedCatalogTargetNotDifferentLocalFallback() = runTest(dispatcher) {
        val storage = DownloadStorage(tmp.newFolder("downloads"))
        storage.writeCompletedDownload(fileId = 7, fileName = "book.mobi")

        val vm = viewModel(
            catalogRepository = catalogRepository(
                responseBody = itemDetailJson(fileId = 8, fileName = "book.epub", container = "epub"),
            ),
            downloadStorage = storage,
            requestedFileId = null,
        )

        advanceUntilIdle()
        val state = vm.awaitLoaded()

        assertEquals(8, state.fileId)
        assertEquals(BookFormat.Epub, state.format)
        assertEquals(EbookReadMode.InApp, state.readMode)
        assertEquals("/api/v2/ebooks/$CONTENT_ID/files/8/read", state.fileUrl)
        assertNull(state.localUri)
        assertEquals("book.epub", state.localDisplayName)
        assertNull(state.error)
    }

    @Test
    fun offlineOnlyNoRequestPrefersDownloadedInAppTargetOverEarlierExternalOriginal() = runTest(dispatcher) {
        val storage = DownloadStorage(tmp.newFolder("downloads"))
        storage.writeCompletedDownload(fileId = 7, fileName = "book.mobi")
        storage.writeCompletedDownload(fileId = 8, fileName = "book.epub")

        val vm = viewModel(
            catalogRepository = catalogRepository(status = HttpStatusCode.InternalServerError),
            downloadStorage = storage,
            requestedFileId = null,
        )

        advanceUntilIdle()
        val state = vm.awaitLoaded()

        assertEquals(8, state.fileId)
        assertEquals(BookFormat.Epub, state.format)
        assertEquals(EbookReadMode.InApp, state.readMode)
        assertEquals("EPUB", state.formatDisplayName)
        assertTrue(state.fileUrl?.startsWith("file://") == true)
        assertTrue(state.fileUrl?.contains("book.epub") == true)
        assertEquals(state.fileUrl, state.localUri)
        assertEquals("book.epub", state.localDisplayName)
        assertNull(state.error)
    }

    @Test
    fun pageJumpClampsToKnownPageCountBeforePersistingProgress() = runTest(dispatcher) {
        val vm = viewModel(
            catalogRepository = catalogRepository(
                responseBody = itemDetailJson(fileId = FILE_ID, fileName = "book.pdf", container = "pdf"),
            ),
            downloadStorage = DownloadStorage(tmp.newFolder("downloads")),
        )

        advanceUntilIdle()
        vm.awaitLoaded()
        vm.onPageCountKnown(10)
        vm.jumpToPage(999)
        advanceUntilIdle()
        vm.awaitSyncIdle()

        val state = vm.uiState.value
        assertEquals(9, state.currentPage)
        assertEquals("page:9", state.progressLocation)
        assertEquals(1.0, state.progressPercent)
    }

    @Test
    fun latePageCountClampsPreviouslyUnknownPageProgress() = runTest(dispatcher) {
        val vm = viewModel(
            catalogRepository = catalogRepository(
                responseBody = itemDetailJson(fileId = FILE_ID, fileName = "book.pdf", container = "pdf"),
            ),
            downloadStorage = DownloadStorage(tmp.newFolder("downloads")),
        )

        advanceUntilIdle()
        vm.awaitLoaded()
        vm.onPageChanged(999)
        vm.onPageCountKnown(10)
        advanceUntilIdle()
        vm.awaitSyncIdle()

        val state = vm.uiState.value
        assertEquals(9, state.currentPage)
        assertEquals("page:9", state.progressLocation)
        assertEquals(1.0, state.progressPercent)
    }

    @Test
    fun pendingAnnotationRecoveryRetainsCreateAndDeleteIdentities() = runTest(dispatcher) {
        val barrier = org.siloserver.silo.network.DefaultIdentityTransitionBarrier()
        val scope = org.siloserver.silo.network.AuthScopeSnapshot(SERVER_ID, PROFILE_ID, "https://reader.example", null,
            identityGeneration = barrier.generation.value, isIdentityGenerationStamped = true)
        val authority = org.siloserver.silo.network.DurableLoginAuthority("login", scope)
        val authorities = object : org.siloserver.silo.network.DurableLoginAuthorityProvider {
            override suspend fun snapshotDurableLoginAuthority() = authority
        }
        val tokens = object : TokenManager by org.siloserver.silo.network.TokenManagerImpl() {
            override suspend fun snapshotCurrentScope() = scope
        }
        val root = tmp.newFolder("annotation-state")
        val state = EbookLocalStateStore(root)
        val create = state.addBookmark(SERVER_ID, PROFILE_ID, CONTENT_ID, "page:3", loginId = "login", origin = scope.serverUrl)
        val deletion = state.markBookmarkDelete(SERVER_ID, PROFILE_ID, CONTENT_ID, "delete-me", "page:4", "login", scope.serverUrl, "old-tag")
        state.addBookmark(SERVER_ID, PROFILE_ID, CONTENT_ID, "page:5", loginId = "other-login", origin = scope.serverUrl)
        val calls = mutableListOf<String>()
        val row = """{"id":"${create.id}","content_id":"$CONTENT_ID","kind":"bookmark","location":"page:9","etag":"current"}"""
        val client = HttpClient(MockEngine { request ->
            calls += "${request.method.value} ${request.url.encodedPath}"
            when (request.method) {
                io.ktor.http.HttpMethod.Post -> {
                    val body = kotlinx.serialization.json.Json.parseToJsonElement(request.body.toByteArray().decodeToString()).toString()
                    assertTrue(body.contains(create.id)); assertFalse(body.contains(deletion.id))
                    respond(row, HttpStatusCode.OK, jsonHeaders)
                }
                io.ktor.http.HttpMethod.Delete -> {
                    assertEquals("old-tag", request.headers[HttpHeaders.IfMatch])
                    assertTrue(request.url.encodedPath.endsWith("/delete-me"))
                    respond("", HttpStatusCode.NoContent, jsonHeaders)
                }
                else -> respond("""{"items":[$row],"page":{"has_more":false}}""", HttpStatusCode.OK, jsonHeaders)
            }
        }) { install(ContentNegotiation) { json(SiloJson) } }
        try {
            val v2 = org.siloserver.silo.network.apiv2.EbookReaderV2Api(client, tokens, ApiV2Gate.Unrestricted)
            val repository = EbookReaderRepository(EbookReaderApi(v2), v2)
            val vm = viewModel(catalogRepository(responseBody = itemDetailJson(fileName = "book.epub", container = "epub")),
                DownloadStorage(tmp.newFolder("annotation-downloads")), readerRepository = repository,
                readerStore = EbookLocalStateStore(root), authorities = authorities, barrier = barrier)
            advanceUntilIdle()
            val loaded = vm.awaitLoaded()
            assertEquals(listOf(create.id), loaded.bookmarks.map { it.id })
            assertEquals("page:9", loaded.bookmarks.single().location)
            assertEquals(listOf("other-login"), state.listBookmarks(SERVER_ID, PROFILE_ID, CONTENT_ID).map { it.loginId })
            assertEquals(1, calls.count { it.startsWith("POST ") })
            assertEquals(1, calls.count { it.startsWith("DELETE ") })
        } finally { client.close() }
    }

    private fun viewModel(
        catalogRepository: CatalogRepository,
        downloadStorage: DownloadStorage,
        requestedFileId: Int? = FILE_ID,
        readerRepository: EbookReaderRepository = ebookReaderRepository(),
        readerStore: EbookLocalStateStore = EbookLocalStateStore(tmp.newFolder("reader-state")),
        authorities: org.siloserver.silo.network.DurableLoginAuthorityProvider? = null,
        barrier: org.siloserver.silo.network.IdentityTransitionBarrier? = null,
    ): ReaderViewModel =
        ReaderViewModel(
            catalogRepository = catalogRepository,
            ebookReaderRepository = readerRepository,
            ebookAuthorities = authorities,
            identityTransitions = barrier,
            // Importer over an empty dir → awaitImport is a no-op; metadata is
            // written directly to Room via [metadata] in writeCompletedDownload.
            offlineMediaResolver = OfflineMediaResolver(
                metadata,
                downloadStorage,
                LegacyDownloadImporter(tmp.newFolder(), db),
            ),
            localStateStore = readerStore,
            serverRegistry = FakeServerRegistry(),
            profileRepository = FakeProfileRepository(),
            userItemStatePort = org.siloserver.silo.repository.port.NoOpUserItemStatePort,
            outboxSyncScheduler = org.siloserver.silo.common.data.sync.OutboxSyncScheduler.NONE,
            savedStateHandle = SavedStateHandle(
                buildMap {
                    put("contentId", CONTENT_ID)
                    requestedFileId?.let { put("fileId", it.toString()) }
                },
            ),
        )

    private suspend fun ReaderViewModel.awaitLoaded(): ReaderUiState {
        withContext(Dispatchers.IO) {
            withTimeout(AWAIT_POLL_TIMEOUT_MS) {
                while (uiState.value.isLoading) {
                    delay(10)
                }
            }
        }
        return uiState.value
    }

    private suspend fun ReaderViewModel.awaitSyncIdle(): ReaderUiState {
        withContext(Dispatchers.IO) {
            withTimeout(AWAIT_POLL_TIMEOUT_MS) {
                while (uiState.value.isSyncing) {
                    delay(10)
                }
            }
        }
        return uiState.value
    }

    private fun catalogRepository(
        status: HttpStatusCode = HttpStatusCode.OK,
        responseBody: String = """{"error":"server_error","message":"offline"}""",
    ): CatalogRepository =
        CatalogRepository(
            CatalogApi(
                HttpClient(
                    MockEngine { request ->
                        val body = if (request.url.encodedPath == "/api/v2/catalog/items/$CONTENT_ID") {
                            responseBody
                        } else {
                            """{"error":"not_found","message":"not found"}"""
                        }
                        respond(
                            content = body,
                            status = if (request.url.encodedPath == "/api/v2/catalog/items/$CONTENT_ID") {
                                status
                            } else {
                                HttpStatusCode.NotFound
                            },
                            headers = jsonHeaders,
                        )
                    },
                ) {
                    install(ContentNegotiation) { json(SiloJson) }
                },
            ),
        )

    private fun ebookReaderRepository(): EbookReaderRepository {
        val v2 = org.siloserver.silo.network.apiv2.EbookReaderV2Api(
            HttpClient(
                MockEngine {
                    respond(
                        content = """{"error":"not_found","message":"not found"}""",
                        status = HttpStatusCode.NotFound,
                        headers = jsonHeaders,
                    )
                },
            ) {
                install(ContentNegotiation) { json(SiloJson) }
            },
            FakeTokenManager(),
            org.siloserver.silo.network.apiv2.ApiV2Gate.Unrestricted,
        )
        return EbookReaderRepository(EbookReaderApi(v2), v2)
    }

    private fun itemDetailJson(fileId: Int = FILE_ID, fileName: String, container: String): String =
        """
        {
          "content_id": "$CONTENT_ID",
          "type": "ebook",
          "title": "Book",
          "ebook": {
            "authors": [
              {
                "name": "Ada Author"
              }
            ]
          },
          "cast": [], "crew": [], "subtitles": [],
          "versions": [
            {
              "file_id": "$fileId",
              "file_name": "$fileName",
              "container": "$container"
            }
          ]
        }
        """.trimIndent()

    private suspend fun DownloadStorage.writeCompletedDownload(fileId: Int = FILE_ID, fileName: String) {
        val container = fileName.substringAfterLast('.', missingDelimiterValue = "")
        prepareWrite(SERVER_ID, PROFILE_ID, fileId, fileName = fileName, container = container).writeTargetBytes(
            container.encodeToByteArray(),
        )
        metadata.writeSidecar(
            SERVER_ID,
            PROFILE_ID,
            DownloadSidecar(
                record = DownloadRecord(
                    id = "dl-$fileId",
                    contentId = CONTENT_ID,
                    mediaFileId = fileId,
                    fileSize = container.length.toLong(),
                    bytesSent = container.length.toLong(),
                    kind = "queued",
                    status = "completed",
                    createdAt = "2026-06-14T00:00:00Z",
                ),
                title = "Book",
                fileName = fileName,
                container = container,
                mediaType = "ebook",
                updatedAtMs = 1L,
            ),
        )
    }

    private fun DownloadTarget.writeTargetBytes(bytes: ByteArray) {
        openOutputStream().use { it.write(bytes) }
    }

    private class FakeServerRegistry : ServerRegistry {
        override val entries: StateFlow<List<ServerEntry>> = MutableStateFlow(emptyList())
        override val activeServerId: StateFlow<String?> = MutableStateFlow(SERVER_ID)
        override val activeEntry: StateFlow<ServerEntry?> = MutableStateFlow(null)
        override suspend fun addOrUpdate(url: String, fetchedName: String?): String = SERVER_ID
        override suspend fun rename(serverId: String, userOverrideName: String?) = Unit
        override suspend fun setFetchedName(serverId: String, fetchedName: String?) = Unit
        override suspend fun setProfileId(serverId: String, profileId: String?) = Unit
        override suspend fun remove(serverId: String) = Unit
        override suspend fun signOut(serverId: String) = Unit
        override suspend fun switchTo(serverId: String) = Unit
        override suspend fun touchActive() = Unit
    }

    private class FakeProfileRepository : ProfileRepository(
        profileApi = ProfileApi(noOpClient(), ApiV2Gate.Unrestricted),
        tokenManager = FakeTokenManager(),
    ) {
        override suspend fun getActiveProfileId(): String = PROFILE_ID
    }

    private class FakeTokenManager : TokenManager {
        override val sessionExpired = MutableSharedFlow<Unit>()
        override suspend fun getAccessToken(): String? = null
        override suspend fun getRefreshToken(): String? = null
        override suspend fun saveTokens(accessToken: String, refreshToken: String, expiresIn: Long) = Unit
        override suspend fun clearTokens() = Unit
        override suspend fun invalidateSession() = Unit
        override suspend fun getProfileId(): String? = PROFILE_ID
        override suspend fun setProfileId(profileId: String?) = Unit
        override suspend fun getProfileToken(): String? = null
        override suspend fun setProfileToken(token: String?) = Unit
        override suspend fun getServerUrl(): String = "http://localhost"
        override suspend fun setServerUrl(url: String) = Unit
        override suspend fun getCurrentServerId(): String? = SERVER_ID
        override suspend fun switchActiveServer(serverId: String?) = Unit
        override suspend fun signOutCurrentServer() = Unit
    }

    private companion object {
        const val CONTENT_ID = "book-1"
        const val FILE_ID = 7
        const val SERVER_ID = "srv1"
        const val PROFILE_ID = "profA"

        val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

        fun noOpClient(): HttpClient =
            HttpClient(MockEngine { respond("{}", headers = jsonHeaders) }) {
                install(ContentNegotiation) { json(SiloJson) }
            }
    }
}

/**
 * Wall-clock backstop for the polling waits above.
 *
 * It exists to turn a hang into a failure, not to assert latency: a passing
 * test settles in milliseconds. Short deadlines here failed on a loaded CI
 * runner while the work was merely slow, which looks exactly like the race the
 * wait was written to catch.
 */
private const val AWAIT_POLL_TIMEOUT_MS = 30_000L
