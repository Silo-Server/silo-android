package org.siloserver.silo.android

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
    fun mobileKeepsAndroid7InstallFloorAndTargetsApi36() {
        assertTrue(buildFile.contains("minSdk = 24"))
        assertTrue(buildFile.contains("targetSdk = 36"))
        assertTrue(buildFile.contains("compileSdk = 36"))
    }

    @Test
    fun mobileHasNoRemovedNativePlayerManifestOverride() {
        assertFalse(manifest.contains("dev.jdtech"))
        assertFalse(manifest.contains("overrideLibrary"))
    }
}
