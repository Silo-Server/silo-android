package org.siloserver.silo.common.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.siloserver.silo.network.TokenManagerImpl
import org.siloserver.silo.network.CleartextOriginConsent
import org.siloserver.silo.network.CleartextOriginNotApprovedException
import org.siloserver.silo.common.io.ContentLimitExceeded
import androidx.media3.datasource.DataSource

@RunWith(RobolectricTestRunner::class)
class AuthenticatedDataSourceFactoryTest {
    private val closeables = mutableListOf<OkHttpClient>()

    @AfterTest
    fun tearDown() {
        closeables.forEach { client ->
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    @Test
    fun streamRelativeUrlWithoutApiMountResolvesAgainstOrigin() {
        assertEquals(
            "https://silo.example/stream/session-1",
            resolveRoutedDataSourceUrl("https://silo.example", "/stream/session-1"),
        )
    }

    @Test
    fun apiRelativeFallbackIsNotDoublePrefixed() {
        assertEquals(
            "https://silo.example/api/v2/stream/session-1",
            resolveRoutedDataSourceUrl("https://silo.example", "/api/v2/stream/session-1"),
        )
    }

    @Test
    fun absoluteFallbackUrlsArePreserved() {
        assertEquals(
            "https://cdn.example/stream/session-1",
            resolveRoutedDataSourceUrl("https://silo.example", "https://cdn.example/stream/session-1"),
        )
    }

    @Test
    fun `unapproved cleartext stream is rejected before explicit plan headers reach transport`() {
        val transport = FakeHttpDataSource()
        val client = OkHttpClient().also { closeables += it }
        val tokens = TokenManagerImpl()
        runBlocking { tokens.setServerUrl("https://silo.example") }
        val source = RefreshingHttpDataSource(
            factory = FakeHttpDataSourceFactory(ArrayDeque(listOf(transport))),
            authSession = MediaAuthSession(
                tokenManager = tokens,
                refreshClient = client,
                cleartextOriginConsent = object : CleartextOriginConsent {
                    override suspend fun isApproved(origin: String): Boolean = false
                },
            ),
        )
        val plan = DataSpec.Builder()
            .setUri(Uri.parse("http://cdn.example/video"))
            .setHttpRequestHeaders(
                mapOf(
                    "Authorization" to "Bearer stream-plan",
                    "X-Stream-Signature" to "secret",
                ),
            )
            .build()

        assertFailsWith<CleartextOriginNotApprovedException> {
            source.open(plan)
        }
        assertEquals(emptyList(), transport.openedDataSpecs)
    }

    @Test
    fun explicitPlanHeadersOverrideSessionAuthCaseInsensitively() {
        val merged = authenticatedHeadersFor(
            serverUrl = "https://silo.example",
            requestUrl = "https://silo.example/video",
            sessionHeaders = mapOf(
                "Authorization" to "Bearer silo-session",
                "X-Profile-Id" to "profile-1",
            ),
            explicitHeaders = mapOf(
                "authorization" to "Signed cdn-credential",
                "X-Stream-Scope" to "route-7",
            ),
        )

        assertEquals("Signed cdn-credential", merged["authorization"])
        assertFalse(merged.containsKey("Authorization"))
        assertEquals("profile-1", merged["X-Profile-Id"])
        assertEquals("route-7", merged["X-Stream-Scope"])
    }

    @Test
    fun foreignOriginReceivesOnlyExplicitTargetHeaders() {
        assertEquals(
            mapOf("X-Stream-Scope" to "route-7"),
            authenticatedHeadersFor(
                serverUrl = "https://silo.example",
                requestUrl = "https://cdn.example/video",
                sessionHeaders = mapOf(
                    "Authorization" to "Bearer silo-session",
                    "X-Profile-Id" to "profile-1",
                    "X-Profile-Token" to "profile-token",
                ),
                explicitHeaders = mapOf("X-Stream-Scope" to "route-7"),
            ),
        )
    }

    @Test
    fun explicitAuthorizationIsPreservedOnlyForItsIssuedTarget() {
        assertEquals(
            mapOf("Authorization" to "Signed cdn-route-7"),
            authenticatedHeadersFor(
                serverUrl = "https://silo.example",
                requestUrl = "https://cdn.example/video",
                sessionHeaders = mapOf("Authorization" to "Bearer silo-session"),
                explicitHeaders = mapOf("Authorization" to "Signed cdn-route-7"),
            ),
        )
    }

    @Test
    fun sessionHeadersRequireExactSchemeHostAndPort() {
        val sessionHeaders = mapOf(
            "Authorization" to "Bearer silo-session",
            "X-Profile-Id" to "profile-1",
            "X-Profile-Token" to "profile-token",
        )
        listOf(
            "https://cdn.silo.example/video",
            "https://silo.example:444/video",
            "http://silo.example/video",
            "file:///tmp/video",
            "://malformed",
        ).forEach { requestUrl ->
            assertEquals(
                emptyMap(),
                authenticatedHeadersFor(
                    serverUrl = "https://silo.example",
                    requestUrl = requestUrl,
                    sessionHeaders = sessionHeaders,
                    explicitHeaders = emptyMap(),
                ),
                "Session credentials leaked to $requestUrl",
            )
        }
    }

    @Test
    fun relativeMediaAndSubtitleUrlsResolveBeforeOriginPolicy() {
        val sessionHeaders = mapOf("Authorization" to "Bearer silo-session")

        listOf(
            "/api/v2/stream/session-1",
            "api/v2/stream/session-1/subtitles/4.srt",
        ).forEach { requestUrl ->
            assertEquals(
                sessionHeaders,
                authenticatedHeadersFor(
                    serverUrl = "https://silo.example",
                    requestUrl = requestUrl,
                    sessionHeaders = sessionHeaders,
                    explicitHeaders = emptyMap(),
                ),
            )
        }
    }

    @Test
    fun foreignUnauthorizedMediaResponseDoesNotRefresh() {
        assertFalse(
            shouldRefreshMediaRequest(
                serverUrl = "https://silo.example",
                requestUrl = "https://cdn.example/video",
                responseCode = 401,
            ),
        )
        assertTrue(
            shouldRefreshMediaRequest(
                serverUrl = "https://silo.example",
                requestUrl = "https://silo.example/video",
                responseCode = 401,
            ),
        )
    }

    @Test
    fun srtSubtitlePayloadBytesAreNormalizedBeforeMedia3ParsesThem() {
        val loose = """
            00:00:01.000 --> 00:00:02.000
            Hello.
            00:00:03.000 --> 00:00:04.000
            Goodbye.
        """.trimIndent().encodeToByteArray()

        val text = normalizeSubripDataIfNeeded(loose).decodeToString()

        assertTrue(text.startsWith("1\n00:00:01,000 --> 00:00:02,000"))
        assertTrue(text.contains("\n\n2\n00:00:03,000 --> 00:00:04,000"))
    }

    @Test
    fun srtSubtitleUrlsAreDetectedForWholeFileReads() {
        assertTrue(shouldNormalizeSubripPath("/api/v2/stream/session/subtitles/4.srt", 0L))
    }

    @Test
    fun nonSrtPayloadsDoNotUseSubtitleNormalization() {
        assertFalse(shouldNormalizeSubripPath("/video/segment.ts", 0L))
        assertFalse(shouldNormalizeSubripPath("/api/v2/stream/session/subtitles/4.srt", 512L))
    }

    @Test
    fun `guard disabled skips if-range and entity-change validation`() {
        val initial = FakeHttpDataSource(responseHeaders = mapOf("ETag" to listOf("\"v1\"")))
        val changed = FakeHttpDataSource(responseHeaders = mapOf("ETag" to listOf("\"v2\"")))
        val source = refreshingSource(
            initial,
            changed,
            isResumableDirectPlayUri = { false },
        )
        val uri = Uri.parse("https://cdn.example/video.mp4")

        source.open(DataSpec(uri))
        source.close()
        source.open(DataSpec.Builder().setUri(uri).setPosition(4_096L).build())

        assertFalse(initial.openedDataSpecs.single().httpRequestHeaders.containsKey("If-Range"))
        assertFalse(changed.openedDataSpecs.single().httpRequestHeaders.containsKey("If-Range"))
        assertFalse(changed.closed)
    }

    @Test
    fun `weak etag is captured but not sent as if-range on ranged reopen`() {
        val initial = FakeHttpDataSource(responseHeaders = mapOf("ETag" to listOf("""W/"v1"""")))
        val resumed = FakeHttpDataSource(
            responseHeaders = mapOf("etag" to listOf("""W/"v1"""")),
            responseCode = 200,
        )
        val source = refreshingSource(initial, resumed)
        val uri = Uri.parse("https://cdn.example/video.mp4")

        source.open(DataSpec(uri))
        source.close()
        source.open(DataSpec.Builder().setUri(uri).setPosition(4_096L).build())

        assertFalse(resumed.openedDataSpecs.single().httpRequestHeaders.containsKey("If-Range"))
    }

    @Test
    fun `strong etag is captured and attached as if-range on ranged reopen`() {
        val initial = FakeHttpDataSource(responseHeaders = mapOf("ETag" to listOf("\"v1\"")))
        val resumed = FakeHttpDataSource(
            responseHeaders = mapOf("etag" to listOf("\"v1\"")),
            responseCode = 206,
        )
        val source = refreshingSource(initial, resumed)
        val uri = Uri.parse("https://cdn.example/video.mp4")

        source.open(DataSpec(uri))
        source.close()
        source.open(DataSpec.Builder().setUri(uri).setPosition(4_096L).build())

        assertFalse(initial.openedDataSpecs.single().httpRequestHeaders.containsKey("If-Range"))
        assertEquals("\"v1\"", resumed.openedDataSpecs.single().httpRequestHeaders["If-Range"])
    }

    @Test
    fun `etag mismatch aborts ranged reopen and closes response`() {
        val initial = FakeHttpDataSource(responseHeaders = mapOf("ETag" to listOf("\"v1\"")))
        val changed = FakeHttpDataSource(responseHeaders = mapOf("ETag" to listOf("\"v2\"")))
        val source = refreshingSource(initial, changed)
        val uri = Uri.parse("https://cdn.example/video.mp4")

        source.open(DataSpec(uri))
        source.close()

        assertFailsWith<EntityChangedException> {
            source.open(DataSpec.Builder().setUri(uri).setPosition(8_192L).build())
        }
        assertTrue(changed.closed)
        assertEquals(C.RESULT_END_OF_INPUT, source.read(ByteArray(1), 0, 1))
    }

    @Test
    fun `ranged reopen with prior etag rejects 200 without matching etag`() {
        val initial = FakeHttpDataSource(responseHeaders = mapOf("ETag" to listOf("\"v1\"")))
        val full = FakeHttpDataSource(responseCode = 200)
        val source = refreshingSource(initial, full)
        val uri = Uri.parse("https://cdn.example/video.mp4")

        source.open(DataSpec(uri))
        source.close()

        assertFailsWith<EntityChangedException> {
            source.open(DataSpec.Builder().setUri(uri).setPosition(8_192L).build())
        }
        assertTrue(full.closed)
        assertEquals(C.RESULT_END_OF_INPUT, source.read(ByteArray(1), 0, 1))
    }

    @Test
    fun `ranged reopen with prior etag accepts 200 with matching etag`() {
        val initial = FakeHttpDataSource(responseHeaders = mapOf("ETag" to listOf("\"v1\"")))
        val full = FakeHttpDataSource(
            responseHeaders = mapOf("ETag" to listOf("\"v1\"")),
            responseCode = 200,
        )
        val source = refreshingSource(initial, full)
        val uri = Uri.parse("https://cdn.example/video.mp4")

        source.open(DataSpec(uri))
        source.close()
        source.open(DataSpec.Builder().setUri(uri).setPosition(8_192L).build())

        assertEquals("\"v1\"", full.openedDataSpecs.single().httpRequestHeaders["If-Range"])
        assertFalse(full.closed)
    }

    @Test
    fun `ranged reopen with prior etag accepts 206 without response etag`() {
        val initial = FakeHttpDataSource(responseHeaders = mapOf("ETag" to listOf("\"v1\"")))
        val partial = FakeHttpDataSource(responseCode = 206)
        val source = refreshingSource(initial, partial)
        val uri = Uri.parse("https://cdn.example/video.mp4")

        source.open(DataSpec(uri))
        source.close()
        source.open(DataSpec.Builder().setUri(uri).setPosition(8_192L).build())

        assertEquals("\"v1\"", partial.openedDataSpecs.single().httpRequestHeaders["If-Range"])
        assertFalse(partial.closed)
    }

    @Test
    fun `changing uri resets the stored entity validator`() {
        val first = FakeHttpDataSource(responseHeaders = mapOf("ETag" to listOf("\"v1\"")))
        val second = FakeHttpDataSource(responseHeaders = mapOf("ETag" to listOf("\"v2\"")))
        val source = refreshingSource(first, second)

        source.open(DataSpec(Uri.parse("https://cdn.example/video-a.mp4")))
        source.close()
        source.open(
            DataSpec.Builder()
                .setUri(Uri.parse("https://cdn.example/video-b.mp4"))
                .setPosition(1_024L)
                .build(),
        )

        assertFalse(second.openedDataSpecs.single().httpRequestHeaders.containsKey("If-Range"))
    }

    @Test
    fun `single flight 401 refresh still retries once with fresh authorization`() {
        val unauthorized = FakeHttpDataSource { dataSpec ->
            throw HttpDataSource.InvalidResponseCodeException(
                401,
                "Unauthorized",
                null,
                emptyMap(),
                dataSpec,
                byteArrayOf(),
            )
        }
        val retried = FakeHttpDataSource()
        val tokens = TokenManagerImpl()
        runBlocking {
            tokens.setServerUrl("https://silo.example")
            tokens.saveTokens("expired-access", "refresh-token", 3_600)
        }
        val refreshClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        """{"access_token":"fresh-access","refresh_token":"fresh-refresh","expires_in":3600}"""
                            .toResponseBody(null),
                    )
                    .build()
            }
            .build()
            .also {
                closeables += it
            }
        val source = RefreshingHttpDataSource(
            FakeHttpDataSourceFactory(ArrayDeque(listOf(unauthorized, retried))),
            MediaAuthSession(tokens, refreshClient),
        )

        source.open(DataSpec(Uri.parse("https://silo.example/api/v2/stream/session")))

        assertEquals(
            "Bearer expired-access",
            unauthorized.openedDataSpecs.single().httpRequestHeaders["Authorization"],
        )
        assertEquals(
            "Bearer fresh-access",
            retried.openedDataSpecs.single().httpRequestHeaders["Authorization"],
        )
        assertTrue(unauthorized.closed)
    }

    private fun refreshingSource(
        vararg dataSources: FakeHttpDataSource,
        isResumableDirectPlayUri: (Uri) -> Boolean = { true },
    ): RefreshingHttpDataSource {
        val client = OkHttpClient().also {
            closeables += it
        }
        return RefreshingHttpDataSource(
            factory = FakeHttpDataSourceFactory(ArrayDeque(dataSources.toList())),
            authSession = MediaAuthSession(TokenManagerImpl(), client),
            isResumableDirectPlayUri = isResumableDirectPlayUri,
        )
    }

    private class FakeHttpDataSourceFactory(
        private val dataSources: ArrayDeque<FakeHttpDataSource>,
    ) : HttpDataSource.Factory {
        override fun createDataSource(): HttpDataSource = dataSources.removeFirst()

        override fun setDefaultRequestProperties(
            defaultRequestProperties: Map<String, String>,
        ): HttpDataSource.Factory = this
    }

    private class FakeHttpDataSource(
        private val responseHeaders: Map<String, List<String>> = emptyMap(),
        private val responseCode: Int = 200,
        private val onOpen: (DataSpec) -> Long = { C.LENGTH_UNSET.toLong() },
    ) : HttpDataSource {
        val openedDataSpecs = mutableListOf<DataSpec>()
        var closed = false

        override fun addTransferListener(transferListener: TransferListener) = Unit

        override fun open(dataSpec: DataSpec): Long {
            openedDataSpecs += dataSpec
            return onOpen(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            C.RESULT_END_OF_INPUT

        override fun getUri(): Uri? = openedDataSpecs.lastOrNull()?.uri

        override fun getResponseCode(): Int = responseCode

        override fun getResponseHeaders(): Map<String, List<String>> = responseHeaders

        override fun setRequestProperty(name: String, value: String) = Unit

        override fun clearRequestProperty(name: String) = Unit

        override fun clearAllRequestProperties() = Unit

        override fun close() {
            closed = true
        }
    }

    @Test
    fun subripNormalizationAcceptsExactlyTheStreamLimit() {
        val upstream = ByteArrayDataSource(byteArrayOf(1, 2, 3, 4))
        val source = SubripNormalizingDataSource(upstream, maxBytes = 4)

        assertEquals(4, source.open(subripDataSpec()))
        assertTrue(upstream.closed)
    }

    @Test
    fun subripNormalizationRejectsDeclaredLimitPlusOneBeforeReading() {
        val upstream = ByteArrayDataSource(
            bytes = byteArrayOf(1),
            declaredLength = 5,
        )
        val source = SubripNormalizingDataSource(upstream, maxBytes = 4)

        assertFailsWith<ContentLimitExceeded> {
            source.open(subripDataSpec())
        }
        assertEquals(0, upstream.readCalls)
        assertTrue(upstream.closed)
    }

    @Test
    fun subripNormalizationRejectsStreamedLimitPlusOneAndClosesUpstream() {
        val upstream = ByteArrayDataSource(
            bytes = byteArrayOf(1, 2, 3, 4, 5),
            declaredLength = C.LENGTH_UNSET.toLong(),
        )
        val source = SubripNormalizingDataSource(upstream, maxBytes = 4)

        assertFailsWith<ContentLimitExceeded> {
            source.open(subripDataSpec())
        }
        assertTrue(upstream.closed)
    }

    private fun subripDataSpec(): DataSpec =
        DataSpec(Uri.parse("https://silo.example/subtitles/1.srt"))

    private class ByteArrayDataSource(
        private val bytes: ByteArray,
        private val declaredLength: Long = bytes.size.toLong(),
    ) : DataSource {
        private var position = 0
        var readCalls = 0
        var closed = false

        override fun addTransferListener(transferListener: TransferListener) = Unit

        override fun open(dataSpec: DataSpec): Long = declaredLength

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            readCalls += 1
            if (position == bytes.size) return C.RESULT_END_OF_INPUT
            val count = minOf(length, bytes.size - position)
            bytes.copyInto(buffer, offset, position, position + count)
            position += count
            return count
        }

        override fun getUri(): Uri = Uri.parse("https://silo.example/subtitles/1.srt")

        override fun close() {
            closed = true
        }
    }
}
