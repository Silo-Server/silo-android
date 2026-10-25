package org.siloserver.silo.android.cast

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.siloserver.silo.common.cast.SiloCastPairingLink
import org.siloserver.silo.common.cast.SiloCastTarget
import org.siloserver.silo.common.cast.SiloCastTransport

class SiloCastRememberedRokuTest {
    private val link = checkNotNull(
        SiloCastPairingLink.parse(
            "silo://cast?v=2&host=192.168.1.20&port=52817&id=roku-1&name=Den%20Roku&server=c3J2&security=sealed&key=AAAQEAYEAUDAOCAJBIFQYDIOB4",
        ),
    )

    @Test
    fun aRememberedRokuDialsAsItsLinkSaid() {
        val target = SiloCastRememberedRoku.from(link).toTarget()
        assertEquals(link.target.copy(serviceName = target.serviceName), target)
        assertEquals(SiloCastTransport.Sealed, target.transport)
        assertEquals("be45cb26", target.keyId)
    }

    @Test
    fun discoveryWinsOverTheRememberedAddress() {
        val remembered = listOf(SiloCastRememberedRoku.from(link), SiloCastRememberedRoku.from(link.target.copy(deviceId = "roku-2", name = "Attic"), null))
        val found = SiloCastTarget(deviceId = "roku-1", name = "Den Roku", host = "192.168.1.44", port = 52818, version = 2, transport = SiloCastTransport.Sealed)
        val merged = SiloCastRememberedRoku.merge(listOf(found), remembered)
        assertEquals(listOf("Attic", "Den Roku"), merged.map { it.name })
        assertEquals("192.168.1.44", merged.single { it.deviceId == "roku-1" }.host)
        assertEquals(SiloCastTransport.Plain, merged.single { it.deviceId == "roku-2" }.transport)
    }

    @Test
    fun thePairingKeyNeverReachesALogLine() {
        val roku = SiloCastRememberedRoku.from(link)
        assertFalse("AAAQ" in roku.toString())
    }
}
