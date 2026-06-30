package com.continuum.app.android

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidManifestPolicyTest {
    private val manifest = File("src/androidMain/AndroidManifest.xml").readText()
    private val buildFile = File("build.gradle.kts").readText()

    @Test
    fun mobileDisablesAndroidBackupForTokenAndProfileState() {
        assertTrue(manifest.contains("""android:allowBackup="false""""))
        assertFalse(manifest.contains("""android:allowBackup="true""""))
    }

    @Test
    fun mobileKeepsAndroid7InstallFloor() {
        assertTrue(buildFile.contains("minSdk = 24"))
        assertTrue(buildFile.contains("targetSdk = 35"))
        assertTrue(buildFile.contains("compileSdk = 36"))
    }

    @Test
    fun mobileDocumentsMpvOverrideForAndroid7Media3Fallback() {
        assertTrue(manifest.contains("""tools:overrideLibrary="dev.jdtech.mpv""""))
        assertTrue(manifest.contains("libmpv declares minSdk 26"))
        assertTrue(manifest.contains("uses Media3 on Android 7/API 24-25"))
    }
}
