package org.siloserver.silo.model.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
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
     * The scopes every server that knows [key] allows. An entry may be an
     * object naming the revision that added the scope; one added after the
     * key itself (revision 19 gave `player.subtitle_sync_ms` a profile row)
     * is missing on older servers, where clearing the device value falls to
     * the default, so it does not count.
     */
    private fun scopes(key: String): List<String> {
        val definition = definitions.getValue(key)
        val since = definition.getValue("introduced_in").jsonPrimitive.int
        return definition.getValue("allowed_scopes").jsonArray.mapNotNull { entry ->
            when (entry) {
                is JsonObject -> entry.getValue("scope").jsonPrimitive.content
                    .takeIf { (entry["introduced_in"]?.jsonPrimitive?.int ?: since) <= since }
                else -> entry.jsonPrimitive.content
            }
        }
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
