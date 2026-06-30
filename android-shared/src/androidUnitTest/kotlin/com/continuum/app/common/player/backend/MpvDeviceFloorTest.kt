package com.continuum.app.common.player.backend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MpvDeviceFloorTest {
    @Test
    fun supportedOnModern64BitDevice() {
        assertTrue(MpvDeviceFloor.isMpvSupported(sdkInt = 30, supportedAbis = listOf("arm64-v8a")))
        assertTrue(MpvDeviceFloor.isMpvSupported(sdkInt = 30, supportedAbis = listOf("x86_64")))
    }

    @Test
    fun unsupportedOnAndroid7RegardlessOfAbi() {
        val android7Sdks = listOf(24, 25)
        val abiSets = listOf(
            listOf("arm64-v8a"),
            listOf("x86_64"),
            listOf("armeabi-v7a"),
            listOf("arm64-v8a", "armeabi-v7a"),
        )

        android7Sdks.forEach { sdk ->
            abiSets.forEach { abis ->
                assertFalse(
                    MpvDeviceFloor.isMpvSupported(sdkInt = sdk, supportedAbis = abis),
                    "sdk=$sdk abis=$abis must stay Media3-only",
                )
            }
        }
    }

    @Test
    fun unsupportedOn32BitOnlyDevice() {
        assertFalse(MpvDeviceFloor.isMpvSupported(sdkInt = 30, supportedAbis = listOf("armeabi-v7a")))
    }

    @Test
    fun minimumSdkConstantDocumentsLibmpvFloor() {
        assertEquals(26, MpvDeviceFloor.MIN_SDK_FOR_MPV)
    }
}
