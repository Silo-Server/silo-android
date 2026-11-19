package org.siloserver.silo.common.pairing

import kotlin.test.Test
import kotlin.test.assertEquals

class TvDeviceNameTest {
    private fun utf8(text: String) = text.toByteArray(Charsets.UTF_8).size

    @Test
    fun keepsAShortNameAndTrimsIt() {
        assertEquals("Living Room TV", TvDeviceName.fitted("  Living Room TV "))
    }

    @Test
    fun cutsALongNameToADnsSdLabelOnACharacterBoundary() {
        val cases = listOf(
            "a".repeat(129), // over the server's 128-character device_name limit
            "電".repeat(84), // 252 bytes: an NSD TXT value this long throws
            "TV " + "📺".repeat(30), // a server-side cut by characters would split an emoji
        )
        for (name in cases) {
            val fitted = TvDeviceName.fitted(name)
            assert(utf8(fitted) <= 63) { "$name -> ${utf8(fitted)} bytes" }
            assert(name.startsWith(fitted)) { "$name -> $fitted" }
            assertEquals(fitted, String(fitted.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
        }
        assertEquals("電".repeat(21), TvDeviceName.fitted("電".repeat(84)))
        assertEquals("TV " + "📺".repeat(15), TvDeviceName.fitted("TV " + "📺".repeat(30)))
    }
}
