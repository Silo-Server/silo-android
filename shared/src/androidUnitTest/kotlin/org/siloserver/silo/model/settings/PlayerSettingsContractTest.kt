package org.siloserver.silo.model.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Pins the client-side restatements of manifest facts to the vendored
 * manifest, so a re-vendor that changes them fails here.
 */
class PlayerSettingsContractTest {

    private val definitions by lazy {
        val raw = checkNotNull(javaClass.classLoader?.getResource("settings/v1/manifest.json")) {
            "Missing test resource settings/v1/manifest.json"
        }.readText()
        Json.parseToJsonElement(raw).jsonObject.getValue("definitions").jsonArray
            .map { it.jsonObject }
            .associateBy { it.getValue("key").jsonPrimitive.content }
    }

    @Test
    fun playbackSpeedBoundsMatchTheManifest() {
        val schema = definitions.getValue(SettingKeys.PLAYER_PLAYBACK_SPEED)
            .getValue("value_schema").jsonObject
        assertEquals(PlaybackSpeedRange.MIN, schema.getValue("minimum").jsonPrimitive.double)
        assertEquals(PlaybackSpeedRange.MAX, schema.getValue("maximum").jsonPrimitive.double)
        assertEquals(PlaybackSpeedRange.STEP, schema.getValue("step").jsonPrimitive.double)
    }

    @Test
    fun profile7FallbackDefaultsOff() {
        // AndroidPlayerSettingsStore falls back to this before the first
        // refresh; it has to agree with what the server would resolve.
        assertFalse(
            definitions.getValue(SettingKeys.PLAYER_DV_PROFILE7_HDR10_FALLBACK)
                .getValue("default_value").jsonPrimitive.boolean,
        )
    }

    @Test
    fun normalizeClampsAndSnapsToTheStep() {
        assertEquals(3.0, PlaybackSpeedRange.normalize(4.0))
        assertEquals(0.25, PlaybackSpeedRange.normalize(0.01))
        assertEquals(1.35, PlaybackSpeedRange.normalize(1.33))
        assertEquals(1.25, PlaybackSpeedRange.normalize(1.25))
        assertEquals(2.5, PlaybackSpeedRange.normalize(2.5))
        assertEquals(1.0, PlaybackSpeedRange.normalize(Double.NaN))
    }
}
