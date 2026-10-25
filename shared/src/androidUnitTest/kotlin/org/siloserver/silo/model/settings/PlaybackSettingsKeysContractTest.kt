package org.siloserver.silo.model.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * "Use profile setting" is only honest for a device setting the profile can
 * also hold; for the rest, clearing the device value falls to the default.
 * Pins [PlaybackSettingsKeys.ProfileLayeredDeviceSettings] to the vendored
 * manifest.
 */
class PlaybackSettingsKeysContractTest {

    private val definitions by lazy {
        val raw = checkNotNull(javaClass.classLoader?.getResource("settings/v1/manifest.json")) {
            "Missing test resource settings/v1/manifest.json"
        }.readText()
        Json.parseToJsonElement(raw).jsonObject.getValue("definitions").jsonArray
            .map { it.jsonObject }
            .associateBy { it.getValue("key").jsonPrimitive.content }
    }

    /**
     * The scopes [key] allows. An entry may be an object naming the revision
     * that added the scope (revision 19 gave `player.subtitle_sync_ms` a
     * profile row); it counts, because the store gates both the profile write
     * and the device clear on that revision.
     */
    private fun scopes(key: String): List<String> =
        definitions.getValue(key).getValue("allowed_scopes").jsonArray.map { entry ->
            (entry as? JsonObject)?.getValue("scope")?.jsonPrimitive?.content
                ?: entry.jsonPrimitive.content
        }

    @Test
    fun profileLayeredKeysAreExactlyTheDeviceKeysWithAProfileScope() {
        val expected = PlaybackSettingsKeys.DeviceSettings
            .filter { it in definitions }
            .filter { "profile_device" in scopes(it) && "profile" in scopes(it) }
            .toSet()
        assertEquals(expected, PlaybackSettingsKeys.ProfileLayeredDeviceSettings)
    }

    @Test
    fun overrideGroupsStayWithinTheProfileLayer() {
        for (key in PlaybackSettingsKeys.ProfileLayeredDeviceSettings) {
            val group = PlaybackSettingsKeys.deviceOverrideGroup(key)
            assertTrue(key in group, key)
            assertTrue(group.all { it in PlaybackSettingsKeys.ProfileLayeredDeviceSettings }, key)
        }
    }
}
