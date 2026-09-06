package org.siloserver.silo.model.feature

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.siloserver.silo.model.metadata.MetadataAiOnView
import org.siloserver.silo.model.metadata.MetadataAiStatus
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.api.MetadataAiApi
import org.siloserver.silo.repository.MetadataAiRepository

class MetadataAiFeatureStoreTest {

    @Test
    fun startsDisabledAndFollowsSuccessfulStatusProbe() = runTest {
        val store = MetadataAiFeatureStore(
            MetadataAiRepository(
                FakeMetadataAiApi(
                    ApiResult.Success(MetadataAiStatus(enabled = true, onView = MetadataAiOnView.Button)),
                ),
            ),
        )

        assertFalse(store.status.value.enabled)
        assertEquals(MetadataAiOnView.Off, store.status.value.onView)

        store.refresh()

        assertTrue(store.status.value.enabled)
        assertEquals(MetadataAiOnView.Button, store.status.value.onView)
    }

    @Test
    fun transientFailureKeepsPreviousCapabilityValue() = runTest {
        val api = FakeMetadataAiApi(
            ApiResult.Success(MetadataAiStatus(enabled = true, onView = MetadataAiOnView.Auto)),
            ApiResult.NetworkError(IllegalStateException("offline")),
        )
        val store = MetadataAiFeatureStore(MetadataAiRepository(api))

        store.refresh()
        store.refresh()

        assertEquals(MetadataAiOnView.Auto, store.status.value.onView)
    }

    @Test
    fun resetHidesTranslationBeforeNextProbe() = runTest {
        val store = MetadataAiFeatureStore(
            MetadataAiRepository(
                FakeMetadataAiApi(
                    ApiResult.Success(MetadataAiStatus(enabled = true, onView = MetadataAiOnView.Button)),
                ),
            ),
        )

        store.refresh()
        store.reset()

        assertFalse(store.status.value.enabled)
        assertEquals(MetadataAiOnView.Off, store.status.value.onView)
    }

    @Test
    fun statusDecodesServerWireShape() {
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
        // Server contract: GET /api/v1/metadata/ai/status → {enabled, on_view}
        val status = json.decodeFromString(
            MetadataAiStatus.serializer(),
            """{"enabled":true,"on_view":"auto"}""",
        )
        assertEquals(MetadataAiOnView.Auto, status.onView)

        // Disabled fallback shape from WriteMetadataAIDisabledStatus.
        val disabled = json.decodeFromString(
            MetadataAiStatus.serializer(),
            """{"enabled":false,"on_view":"off"}""",
        )
        assertFalse(disabled.enabled)
        assertEquals(MetadataAiOnView.Off, disabled.onView)

        // Unknown future on_view values coerce to Off rather than crashing.
        val unknown = json.decodeFromString(
            MetadataAiStatus.serializer(),
            """{"enabled":true,"on_view":"hover"}""",
        )
        assertEquals(MetadataAiOnView.Off, unknown.onView)
    }
}

private class FakeMetadataAiApi(
    vararg statusResults: ApiResult<MetadataAiStatus>,
) : MetadataAiApi {
    private val statusResults = statusResults.toMutableList()

    override suspend fun status(): ApiResult<MetadataAiStatus> =
        if (statusResults.isNotEmpty()) {
            statusResults.removeAt(0)
        } else {
            ApiResult.NetworkError(IllegalStateException("no fake status result"))
        }

    override suspend fun translateDescription(
        contentId: String,
        targetLanguage: String,
        scope: org.siloserver.silo.network.AuthScopeSnapshot?,
    ): ApiResult<org.siloserver.silo.model.metadata.MetadataTranslationJob> = ApiResult.NetworkError(IllegalStateException("not used"))
}
