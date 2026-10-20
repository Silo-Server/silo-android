package org.siloserver.silo.common.settings

import org.siloserver.silo.model.settings.PlaybackSettingsKeys
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
    fun aManifestDetectedKeyIsStoredOnlyWhenTheManifestDefinesIt() = runTest {
        var keys: ApiResult<Set<String>> = ApiResult.NetworkError(IllegalStateException("offline"))
        val revision = SettingsContractRevision(
            fetchCapabilities = { capabilities(30) },
            getServerUrl = { "https://a.example" },
            fetchManifestKeys = { keys },
        )
        val key = PlaybackSettingsKeys.BufferingQualityMode

        assertNull(revision.storesKey("https://a.example", key), "unknown while the manifest can't be read")
        keys = ApiResult.Success(setOf("playback.preferred_quality"))
        assertEquals(false, revision.storesKey("https://a.example", key), "a later revision alone says nothing")
        keys = ApiResult.Success(setOf(key))
        revision.refresh("https://a.example")
        assertEquals(false, revision.stores("https://a.example", key), "the same contract is not read again")
        assertEquals(true, revision.stores("https://a.example", "playback.preferred_quality"), "an ungated key")
    }

    @Test
    fun aNewContractIsReadAgain() = runTest {
        var etag = "one"
        var reads = 0
        val revision = SettingsContractRevision(
            fetchCapabilities = {
                ApiResult.Success(SettingsContractCapabilities(apiVersion = 1, manifestRevision = 23, contractEtag = etag))
            },
            getServerUrl = { "https://a.example" },
            fetchManifestKeys = { reads++; ApiResult.Success(setOf(PlaybackSettingsKeys.BufferingQualityMode)) },
        )
        revision.refresh("https://a.example")
        revision.refresh("https://a.example")
        assertEquals(1, reads)
        etag = "two"
        revision.refresh("https://a.example")
        assertEquals(2, reads)
        assertEquals(true, revision.stores("https://a.example", PlaybackSettingsKeys.BufferingQualityMode))
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
