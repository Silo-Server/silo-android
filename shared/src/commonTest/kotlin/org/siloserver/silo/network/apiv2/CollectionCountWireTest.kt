package org.siloserver.silo.network.apiv2

import kotlinx.serialization.encodeToString
import org.siloserver.silo.model.personal.Collection
import org.siloserver.silo.network.SiloJson
import kotlin.test.Test
import kotlin.test.assertTrue

class CollectionCountWireTest {
    @Test
    fun unavailableStatusSurvivesDecodingInsteadOfLeavingOnlyStaleCount() {
        val collection = SiloJson.decodeFromString<Collection>(
            """{"id":"c1","name":"Synthetic picks","item_count":42,"item_count_status":"unavailable"}""",
        )
        assertTrue(SiloJson.encodeToString(collection).contains("\"item_count_status\":\"unavailable\""))
    }
}
