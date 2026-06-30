package com.continuum.app.tv

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvAndroidManifestPolicyTest {
    private val manifest = File("src/androidMain/AndroidManifest.xml").readText()
    private val buildFile = File("build.gradle.kts").readText()

    @Test
    fun tvDisablesAndroidBackupForTokenAndProfileState() {
        assertTrue(manifest.contains("""android:allowBackup="false""""))
        assertFalse(manifest.contains("""android:allowBackup="true""""))
    }

    @Test
    fun tvKeepsAndroid7InstallFloor() {
        assertTrue(buildFile.contains("minSdk = 24"))
        assertTrue(buildFile.contains("targetSdk = 35"))
        assertTrue(buildFile.contains("compileSdk = 36"))
    }

    @Test
    fun tvDocumentsMpvOverrideForAndroid7Media3Fallback() {
        assertTrue(manifest.contains("""tools:overrideLibrary="dev.jdtech.mpv""""))
        assertTrue(manifest.contains("libmpv declares minSdk 26"))
        assertTrue(manifest.contains("uses Media3 on Android 7/API 24-25"))
    }
}
