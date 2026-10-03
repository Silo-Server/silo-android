package org.siloserver.silo.common.settings

import org.siloserver.silo.model.settings.SettingsContractCapabilities
import org.siloserver.silo.network.ApiResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SettingsContractRevisionTest {

    private fun capabilities(revision: Int) =
        ApiResult.Success(SettingsContractCapabilities(apiVersion = 1, manifestRevision = revision))

    @Test
    fun cachesTheRevisionForTheServerThatAnswered() = runTest {
        var probes = 0
        val revision = SettingsContractRevision(
            fetchCapabilities = { probes++; capabilities(14) },
            getServerUrl = { "https://a.example" },
        )

        assertEquals(14, revision.revisionFor("https://a.example"))
        assertEquals(14, revision.revisionFor("https://a.example"))
        assertEquals(1, probes)
    }

    @Test
    fun discardsAnAnswerWhenTheServerChangesDuringTheFetch() = runTest {
        var active = "https://old.example"
        val revision = SettingsContractRevision(
            fetchCapabilities = {
                // The user switched servers while this request was in flight,
                // so it reached the new server.
                active = "https://new.example"
                capabilities(14)
            },
            getServerUrl = { active },
        )

        assertNull(revision.revisionFor("https://old.example"))
        assertNull(revision.known.value)
    }

    @Test
    fun treatsAMissingCapabilitiesRouteAsAnOldServer() = runTest {
        val revision = SettingsContractRevision(
            fetchCapabilities = { ApiResult.Error(404, "not_found", "") },
            getServerUrl = { "https://a.example" },
        )

        assertEquals(0, revision.revisionFor("https://a.example"))
    }
}
