package org.siloserver.silo.network.apiv2

import kotlin.test.*

class ProxyAuxiliaryV2Test {
    private val stream = "https://proxy.example/stream/v3/session-1"
    @Test fun exactReferencesPreservePinsAndPgsOptions() {
        for (query in listOf("file_id=42&embedded_stream_index=0", "file_id=42&external_subtitle_key=a%2Fb", "file_id=42&downloaded_subtitle_id=7", "file_id=42&embedded_stream_index=2&windowed=true&position=30&duration=10", "file_id=42&embedded_stream_index=2&embedded_stream_index=3")) {
            val url = "$stream/subtitles/0.sup?$query"
            validateProxySubtitleUrl(url, stream, "session-1")
            val captured = ProxyAuxiliaryRequestHeaders(stream, "session-1", setOf(url), mapOf("Authorization" to "Bearer captured", "X-Profile-Id" to "profile")) { true }
            assertEquals(setOf(url), captured.references)
        }
    }
    @Test fun wrongOriginSessionPathAndCredentialQueriesAreDenied() {
        val valid = "$stream/subtitles/0.ass?file_id=42&embedded_stream_index=0"
        for (url in listOf(valid.replace("proxy.example", "other.example"), valid.replace("session-1/subtitles", "session-2/subtitles"), valid.replace("/0.ass", "/-1.ass"), valid.replace("/0.ass", "/0.ass/fonts"), valid.replace("/0.ass", "/%30.ass"), valid + "&access_token=secret", valid + "&st=opaque", valid + "#fragment", valid.replace("file_id", "source_file_id"), valid.replace("https://", "https://user@"))) {
            assertFailsWith<IllegalArgumentException>(url) { validateProxySubtitleUrl(url, stream, "session-1") }
        }
        assertFailsWith<IllegalArgumentException> { validateProxySubtitleUrl(valid, stream.replace("session-1", "foreign-session"), "session-1") }
        assertFalse(isProxyAuxiliaryUrl("https://proxy.example/stream/subtitles/signed/0.ass"))
        assertFalse(isProxyAuxiliaryUrl("https://api.example/api/v2/stream/session/subtitles/0.ass?st=opaque"))
    }
}
