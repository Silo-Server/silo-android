package org.siloserver.silo.tv

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
    fun tvKeepsAndroid7InstallFloorAndTargetsApi36() {
        assertTrue(buildFile.contains("minSdk = 24"))
        assertTrue(buildFile.contains("targetSdk = 36"))
        assertTrue(buildFile.contains("compileSdk = 36"))
    }

    @Test
    fun tvManifestHasNoNativePlayerOverride() {
        assertFalse(manifest.contains("dev.jdtech"))
        assertFalse(manifest.contains("overrideLibrary"))
        assertFalse(manifest.contains("libmpv"))
    }

    @Test
    fun tvManifestKeepsSquareIconAndBannerAsDistinctResources() {
        assertTrue(manifest.contains("""android:icon="@mipmap/ic_launcher""""))
        assertTrue(manifest.contains("""android:banner="@drawable/tv_banner""""))
        assertFalse(manifest.contains("""android:icon="@drawable/tv_banner""""))
    }
}
