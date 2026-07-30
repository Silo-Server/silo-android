package org.siloserver.silo.android.ui.screens.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerOrientationPolicyTest {
    @Test
    fun android16LargeScreensFollowTheDeviceOrientation() {
        assertFalse(supportsPlayerOrientationLock(sdkInt = 36, smallestScreenWidthDp = 600))
        assertFalse(supportsPlayerOrientationLock(sdkInt = 36, smallestScreenWidthDp = 840))
        assertFalse(supportsPlayerOrientationLock(sdkInt = 37, smallestScreenWidthDp = 600))
    }

    @Test
    fun phonesAndOlderAndroidReleasesKeepThePlayerLock() {
        assertTrue(supportsPlayerOrientationLock(sdkInt = 36, smallestScreenWidthDp = 599))
        assertTrue(supportsPlayerOrientationLock(sdkInt = 35, smallestScreenWidthDp = 840))
    }
}
