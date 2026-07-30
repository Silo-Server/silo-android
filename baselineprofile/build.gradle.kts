plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.androidx.baselineprofile)
}

/**
 * Baseline Profile generator for :androidApp. This module is a macrobenchmark
 * (`com.android.test`) that records the app's hot startup/scroll paths into a
 * `baseline-prof.txt`, which the app then bakes into its release APK and
 * `androidx.profileinstaller` AOT-compiles on first run.
 *
 * Generation is DEVICE-GATED — run it on a connected device or the managed
 * device below (it boots a headless emulator):
 *
 *   ./gradlew :baselineprofile:generateBaselineProfile
 *
 * The TV app shares the same profileinstaller wiring; it can add its own
 * generator (targetProjectPath = ":androidTvApp") later.
 */
android {
    namespace = "org.siloserver.silo.baselineprofile"
    compileSdk = 36

    defaultConfig {
        // Baseline Profile generation requires API 28+ (33+ recommended).
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions {
        jvmTarget = "21"
    }

    // The phone app whose journeys we record.
    targetProjectPath = ":androidApp"

    // Headless device for reproducible CI/local generation.
    @Suppress("UnstableApiUsage")
    testOptions.managedDevices.devices {
        create<com.android.build.api.dsl.ManagedVirtualDevice>("pixel6Api34") {
            device = "Pixel 6"
            apiLevel = 34
            systemImageSource = "aosp"
        }
    }
}

baselineProfile {
    // Generate on the managed device above; flip to connected devices with
    // `-Pandroid.testInstrumentationRunnerArguments...` or useConnectedDevices.
    managedDevices += "pixel6Api34"
    useConnectedDevices = false
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
