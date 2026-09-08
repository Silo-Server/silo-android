package org.siloserver.silo.common.player

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.siloserver.silo.network.TokenManagerImpl
import org.siloserver.silo.network.apiv2.ProxyAuxiliaryRequestHeaders

@RunWith(RobolectricTestRunner::class)
class ProxyAuxiliaryDataSourceTest {
    @Test fun exactSubtitleRequestUsesCapturedHeadersWithoutAmbientSubstitutionOrRefresh() {
        val server = MockWebServer(); server.start()
        val client = buildPlayerOkHttpClient()
        try {
            val stream = server.url("/stream/v3/session-1").toString()
            val url = "$stream/subtitles/0.ass?file_id=42&external_subtitle_key=a%2Fb"
            var current = true
            val captured = ProxyAuxiliaryRequestHeaders(stream, "session-1", setOf(url), mapOf("Authorization" to "Bearer original", "X-Profile-Id" to "original-profile")) { current }
            val tokens = TokenManagerImpl()
            runBlocking { tokens.setServerUrl(server.url("/").toString()) }
            val transport = OkHttpDataSource.Factory(auxiliaryAwareCallFactory(client))
            val auth = MediaAuthSession(tokens, client)
            val source = AuthenticatedDataSourceFactory(
                org.robolectric.RuntimeEnvironment.getApplication(), transport, auth,
                serverUrlProvider = { server.url("/").toString() },
                requestHeadersProvider = { scopedProxyRequestHeaders(it.toString(), captured) },
            ).createDataSource()
            val spec = DataSpec.Builder().setUri(url).build()
            server.enqueue(MockResponse().setResponseCode(401))
            assertFailsWith<IOException> { source.open(spec) }; source.close()
            val actual = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("Bearer original", actual.getHeader("Authorization"))
            assertEquals("original-profile", actual.getHeader("X-Profile-Id"))
            assertEquals("/stream/v3/session-1/subtitles/0.ass?file_id=42&external_subtitle_key=a%2Fb", actual.path)
            assertEquals(1, server.requestCount)
            current = false
            assertFailsWith<IOException> { source.open(spec) }
            assertEquals(1, server.requestCount)
            assertFailsWith<IOException> { RefreshingHttpDataSource(transport, auth).open(spec) }
            assertEquals(1, server.requestCount)
        } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll(); server.shutdown() }
    }

    @Test fun signedPrimaryUsesExplicitSessionWithoutGrantingHeadersToSignedOrUnissuedRoutes() {
        val auxiliary = "https://proxy.example/stream/v3/session-1/subtitles/0.ass?file_id=42&external_subtitle_key=a%2Fb"
        for (stream in listOf("https://proxy.example/stream/direct/opaque-reference", "https://proxy.example/stream/transcode/opaque-reference/master.m3u8")) {
            val captured = ProxyAuxiliaryRequestHeaders(stream, "session-1", setOf(auxiliary),
                mapOf("Authorization" to "Bearer captured", "X-Profile-Id" to "profile")) { true }
            assertSame(captured, scopedProxyRequestHeaders(auxiliary, captured))
            assertEquals(stream, captured.streamUrl)
            for (unissued in listOf(stream, "https://proxy.example/stream/v3/session-1/master.m3u8",
                auxiliary.replace("session-1", "foreign-session"), auxiliary.replace("proxy.example", "foreign.example"),
                auxiliary.replace("file_id=42", "file_id=43"), auxiliary + "&windowed=true")) {
                assertTrue(scopedProxyRequestHeaders(unissued, captured).isEmpty(), unissued)
            }
            assertFailsWith<IllegalArgumentException> {
                ProxyAuxiliaryRequestHeaders(stream, "foreign-session", setOf(auxiliary),
                    mapOf("Authorization" to "Bearer captured", "X-Profile-Id" to "profile")) { true }
            }
        }
    }

    @Test fun scopeDeniesChangedPinsSessionOriginAndUnissuedFonts() {
        val stream = "https://proxy.example/stream/v3/session-1"
        val url = "$stream/subtitles/0.ass?file_id=42&embedded_stream_index=0"
        val captured = ProxyAuxiliaryRequestHeaders(stream, "session-1", setOf(url), mapOf("Authorization" to "Bearer original", "X-Profile-Id" to "profile")) { true }
        assertSame(captured, scopedProxyRequestHeaders(url, captured))
        for (target in listOf(url.replace("file_id=42", "file_id=43"), url.replace("session-1", "session-2"), url.replace("proxy.example", "other.example"), "$stream/subtitles/0.ass/fonts?file_id=42", "https://proxy.example/private")) {
            assertTrue(scopedProxyRequestHeaders(target, captured).isEmpty(), target)
        }
        assertFailsWith<IllegalArgumentException> {
            authenticatedHeadersFor("https://proxy.example", url, mapOf("Authorization" to "Bearer ambient", "X-Profile-Id" to "ambient"), emptyMap())
        }
        val headers = authenticatedHeadersFor("https://proxy.example", url, mapOf("Authorization" to "Bearer ambient", "X-Profile-Id" to "ambient", "X-Profile-Token" to "ambient"), captured)
        assertEquals("Bearer original", headers["Authorization"])
        assertEquals("profile", headers["X-Profile-Id"])
        assertNull(headers["X-Profile-Token"])
    }

    @Test fun auxiliaryRedirectsNeverReachSameOrOtherOrigin() {
        val origin = MockWebServer(); val target = MockWebServer(); origin.start(); target.start()
        val client = buildPlayerOkHttpClient()
        try {
            val calls = auxiliaryAwareCallFactory(client)
            for (location in listOf(origin.url("/unissued"), target.url("/unissued"))) {
                origin.enqueue(MockResponse().setResponseCode(302).addHeader("Location", location))
                val request = okhttp3.Request.Builder().url(origin.url("/stream/v3/session-1/subtitles/0.ass?file_id=42"))
                    .header("Authorization", "Bearer captured").header("X-Profile-Id", "profile").build()
                calls.newCall(request).execute().use { assertEquals(302, it.code) }
            }
            assertEquals(2, origin.requestCount); assertEquals(0, target.requestCount)
        } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll(); origin.shutdown(); target.shutdown() }
    }
}
