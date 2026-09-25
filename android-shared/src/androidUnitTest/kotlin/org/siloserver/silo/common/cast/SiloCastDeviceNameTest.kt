package org.siloserver.silo.common.cast

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SiloCastDeviceNameTest {
    @Test
    fun shortNamesAreUnchanged() {
        assertEquals("Living Room", SiloCastDeviceName.instanceName("Living Room"))
        assertEquals("Living Room Setup", SiloCastDeviceName.instanceName("Living Room", suffix = " Setup"))
    }

    @Test
    fun longNamesFitDnsSdWithoutSplittingCharacters() {
        val name = "Wohnzimmer " + "📺".repeat(20)
        for (suffix in listOf("", " Setup")) {
            val instance = SiloCastDeviceName.instanceName(name, suffix)
            assertTrue(instance.toByteArray(Charsets.UTF_8).size <= 63)
            assertTrue(instance.endsWith(suffix))
            assertTrue(name.startsWith(instance.removeSuffix(suffix)))
            // No lone surrogate left behind by the cut.
            assertEquals(instance, String(instance.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
        }
    }
}
