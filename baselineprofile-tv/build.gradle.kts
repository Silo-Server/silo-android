plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.androidx.baselineprofile)
}

/**
 * Baseline Profile generator for :androidTvApp — the TV twin of :baselineprofile.
 *
 * Why it matters on TV: ART refuses to AOT-compile debuggable builds, and a
 * release build only gets compiled by the device's idle-time dexopt, so first
 * launches JIT-compile the big Compose screens (Home feed, top bar, cascades)
 * while the user is navigating. Measured on a Shield: 47% janky / p90 121ms
 * warm-JIT vs 26% / p90 29ms once AOT-compiled. The profile bakes that
 * compilation into the install.
 *
 * Generation is DEVICE-GATED and needs a signed-in TV running API 33+ (or a
 * rooted API 28+ one — androidx.benchmark refuses to collect otherwise; the
 * Android 11 Shield cannot). A headless managed emulator would only ever
 * record the login screen, so run it against a connected device — the local
 * TV AVD (API 36) after pairing it once:
 *
 *   ./gradlew :baselineprofile-tv:generateBaselineProfile -PallowDebugReleaseSigning=true
 *
 * The output lands in androidTvApp/src/main/generated/baselineProfiles/ and is
 * merged into the release APK by the plugin applied in :androidTvApp.
 */
android {
    namespace = "org.siloserver.silo.baselineprofile.tv"
    compileSdk = 36

    defaultConfig {
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

    targetProjectPath = ":androidTvApp"
}

baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
