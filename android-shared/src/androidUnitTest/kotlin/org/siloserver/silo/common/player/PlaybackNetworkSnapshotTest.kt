package org.siloserver.silo.common.player

import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.TRANSPORT_CELLULAR
import android.net.NetworkCapabilities.TRANSPORT_VPN
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which networks get the phone's cellular quality pair, from Android's own capabilities. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackNetworkSnapshotTest {

    @Test
    fun `mobile data uses the cellular pair`() {
        assertTrue(cellularPair(caps(TRANSPORT_CELLULAR, metered = true)))
        // A carrier that marks its plan unmetered is still mobile data.
        assertTrue(cellularPair(caps(TRANSPORT_CELLULAR, metered = false)))
    }

    @Test
    fun `metered Wi-Fi such as a phone hotspot uses the cellular pair`() {
        assertTrue(cellularPair(caps(TRANSPORT_WIFI, metered = true)))
    }

    @Test
    fun `unmetered Wi-Fi uses the main pair`() {
        assertFalse(cellularPair(caps(TRANSPORT_WIFI, metered = false)))
    }

    @Test
    fun `a VPN over Wi-Fi uses the main pair even though Android marks it metered`() {
        assertFalse(cellularPair(caps(TRANSPORT_VPN, TRANSPORT_WIFI, metered = true)))
        assertFalse(cellularPair(caps(TRANSPORT_VPN, TRANSPORT_WIFI, metered = false)))
    }

    @Test
    fun `a VPN over mobile data uses the cellular pair`() {
        assertTrue(cellularPair(caps(TRANSPORT_VPN, TRANSPORT_CELLULAR, metered = true)))
        assertTrue(cellularPair(caps(TRANSPORT_VPN, TRANSPORT_CELLULAR, metered = false)))
    }

    @Test
    fun `no connection uses the main pair`() {
        assertFalse(cellularPair(null))
    }

    private fun cellularPair(caps: NetworkCapabilities?): Boolean =
        playbackNetworkSnapshot(caps).usesCellularQuality

    /** A VPN reports its own transport plus those of the networks it runs over. */
    private fun caps(vararg transports: Int, metered: Boolean): NetworkCapabilities =
        ShadowNetworkCapabilities.newInstance().also { caps ->
            val shadow = shadowOf(caps)
            transports.forEach { shadow.addTransportType(it) }
            if (TRANSPORT_VPN in transports) shadow.removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            if (metered) {
                shadow.removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            } else {
                shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            }
        }
}
