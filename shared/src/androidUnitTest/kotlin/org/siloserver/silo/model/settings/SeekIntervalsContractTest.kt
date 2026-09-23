package org.siloserver.silo.model.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins [SeekIntervals] to the vendored revision-9 manifest: the allowed set,
 * the defaults, the profile-only scope and the `profile > default` order are
 * contract facts, so a re-vendor that changes any of them must fail here.
 */
class SeekIntervalsContractTest {

    private val definitions by lazy {
        val raw = checkNotNull(javaClass.classLoader?.getResource("settings/v1/manifest.json")) {
            "Missing test resource settings/v1/manifest.json"
        }.readText()
        Json.parseToJsonElement(raw).jsonObject.getValue("definitions").jsonArray
            .map { it.jsonObject }
            .associateBy { it.getValue("key").jsonPrimitive.content }
    }

    @Test
    fun everyKeyMatchesTheManifest() {
        for (media in SeekMedia.entries) {
            for (direction in SeekDirection.entries) {
                val key = SeekIntervals.key(media, direction)
                val definition = assertNotNull(definitions[key], "$key missing from the manifest")

                val schema = definition.getValue("value_schema").jsonObject
                assertEquals("enum", schema.getValue("type").jsonPrimitive.content, key)
                assertEquals(
                    SeekIntervals.CHOICES,
                    schema.getValue("values").jsonArray.map { it.jsonObject.getValue("value").jsonPrimitive.int },
                    key,
                )
                assertEquals(
                    SeekIntervals.defaultSeconds(direction),
                    definition.getValue("default_value").jsonPrimitive.int,
                    key,
                )
                assertEquals(
                    listOf("profile"),
                    definition.getValue("allowed_scopes").jsonArray.map { it.jsonPrimitive.content },
                    key,
                )
                assertEquals(
                    listOf("profile", "default"),
                    definition.getValue("resolution_order").jsonArray.map { it.jsonPrimitive.content },
                    key,
                )
            }
        }
    }

    @Test
    fun keysArriveInTheRevisionThisClientGates() {
        assertTrue(SettingKeys.REVISION >= SeekIntervals.MIN_CONTRACT_REVISION)
        assertEquals(SeekIntervals.KEYS, SeekIntervals.KEYS.filter { it in definitions })
    }
}
