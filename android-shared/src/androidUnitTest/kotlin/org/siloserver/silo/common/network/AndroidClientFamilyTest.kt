package org.siloserver.silo.common.network

import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidClientFamilyTest {
    @Test
    fun mapsTvPhoneAndTabletToCanonicalFamilies() {
        assertEquals("tv", androidClientFamily("android-tv", smallestScreenWidthDp = 0))
        assertEquals("mobile", androidClientFamily("android", smallestScreenWidthDp = 599))
        assertEquals("tablet", androidClientFamily("android", smallestScreenWidthDp = 600))
    }

    @Test
    fun foldableCanReclassifyBetweenMobileAndTabletAcrossRestarts() {
        assertEquals("mobile", androidClientFamily("android", smallestScreenWidthDp = 420))
        assertEquals("tablet", androidClientFamily("android", smallestScreenWidthDp = 720))
        assertEquals("mobile", androidClientFamily("android", smallestScreenWidthDp = 420))
    }

    @Test
    fun unknownAndroidPlatformStillReturnsACanonicalMobileFamily() {
        assertEquals("mobile", androidClientFamily("future-android", smallestScreenWidthDp = 420))
        assertEquals("tablet", androidClientFamily("future-android", smallestScreenWidthDp = 700))
    }
}
