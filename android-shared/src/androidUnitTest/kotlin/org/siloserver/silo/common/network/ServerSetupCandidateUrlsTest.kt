package org.siloserver.silo.common.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ServerSetupCandidateUrlsTest {
    @Test
    fun autoModeTriesHttpsHttpAndDefaultSiloPort() {
        val candidates = buildServerSetupCandidateUrls(
            rawInput = "media.example.com",
            selectedScheme = ServerSetupScheme.Auto,
            port = "",
        )

        assertEquals(
            listOf(
                "https://media.example.com",
                "http://media.example.com",
                "http://media.example.com:8090",
            ),
            candidates,
        )
    }

    @Test
    fun explicitSchemeAndPortBuildsSingleCandidate() {
        val candidates = buildServerSetupCandidateUrls(
            rawInput = "media.example.com",
            selectedScheme = ServerSetupScheme.Http,
            port = "8090",
        )

        assertEquals(listOf("http://media.example.com:8090"), candidates)
    }

    @Test
    fun typedSchemeIsRespectedInAutoMode() {
        val candidates = buildServerSetupCandidateUrls(
            rawInput = "http://box.local:8090/base/",
            selectedScheme = ServerSetupScheme.Auto,
            port = "",
        )

        assertEquals(listOf("http://box.local:8090/base"), candidates)
    }

    @Test
    fun invalidPortIsRejectedBeforeConnecting() {
        val error = assertFailsWith<ServerSetupValidationException> {
            buildServerSetupCandidateUrls(
                rawInput = "media.example.com",
                selectedScheme = ServerSetupScheme.Https,
                port = "99999",
            )
        }

        assertEquals("Port must be a number between 1 and 65535.", error.message)
    }
}
