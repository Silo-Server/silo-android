plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.multiplatform)
}

val siloVersionName = providers
    .gradleProperty("siloVersionName")
    .orElse(providers.environmentVariable("SILO_VERSION_NAME"))
    .orElse("1.0.0")

val siloVersionCode = providers
    .gradleProperty("siloVersionCode")
    .orElse(providers.environmentVariable("SILO_VERSION_CODE"))
    .map { value ->
        val code = value.toIntOrNull() ?: error("siloVersionCode must be an integer.")
        require(code > 0) { "siloVersionCode must be positive." }
        // The *2 (+1 for TV) form-factor multiplier applied at versionCode
        // assignment must stay under Google Play's 2_100_000_000 ceiling.
        require(code <= 1_049_999_999) {
            "siloVersionCode must be <= 1_049_999_999 so the form-factor multiplier " +
                "keeps both artifacts under Google Play's 2_100_000_000 versionCode limit."
        }
        code
    }
    // Bump this per release. base -> phone = base*2, TV = base*2+1. base 5 was
    // the 10/11 build; base 6 -> phone 12, TV 13.
    .orElse(6)

// See androidApp/build.gradle.kts: APK ABI splits break the App Bundle build,
// so disable them whenever a bundle task is invoked.
val isBuildingBundle = gradle.startParameter.taskNames.any {
    it.contains("bundle", ignoreCase = true)
}

kotlin {
    androidTarget {
        compilations.all {
            kotlinOptions {
                jvmTarget = "21"
            }
        }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(project(":shared"))
            implementation(project(":android-shared"))
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(libs.activity.compose)
            implementation(libs.lifecycle.runtime.compose)
            implementation(libs.lifecycle.viewmodel.compose)
            // ProcessLifecycleOwner for the notifications foreground starter.
            implementation(libs.lifecycle.process)
            implementation(libs.navigation.compose)
            implementation(libs.koin.android)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor)
            implementation(libs.media3.exoplayer)
            implementation(libs.media3.exoplayer.hls)
            implementation(libs.media3.datasource.okhttp)
            implementation(libs.media3.ui)
            implementation(libs.media3.session)
            implementation(libs.media3.common.ktx)
            implementation(libs.media3.ui.compose)
            implementation(libs.kotlinx.coroutines.android)

            // Preferences persistence (playback quality, subtitle defaults, etc.).
            implementation(libs.datastore.preferences)

            // Compose for TV — focus-aware TV-optimized components.
            implementation(libs.tv.material)

            // Palette — bitmap accent-color extraction for the ambient hero backdrop (A.2).
            implementation(libs.androidx.palette)

            // Watch Next launcher tiles (sub-project B).
            implementation(libs.androidx.tvprovider)
            implementation(libs.androidx.work.runtime.ktx)
            implementation(libs.koin.androidx.workmanager)

            // QR-code rendering for device-login (sub-project C).
            implementation(libs.zxing.core)
            // Installs the Baseline Profile into the app at first run so hot paths
            // are AOT-compiled — faster cold start on TV hardware.
            implementation(libs.androidx.profileinstaller)
        }

        // First tests in this module — JUnit 4 via kotlin-test-junit, mirroring
        // the android-shared setup. Covers the AmbientBackdropTintState stale-
        // result guard (A.2). Tests that need android.* APIs would require
        // Robolectric; the current suite is pure JVM.
        androidUnitTest.dependencies {
            implementation(kotlin("test"))
            implementation(kotlin("test-junit"))
            implementation(libs.kotlinx.coroutines.test)
            // NotificationRow's constructor default uses JsonObject; the inbox
            // formatter test constructs rows directly, so json must be on the
            // test classpath.
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.mock)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
        }
    }
}

android {
    val allowDebugReleaseSigning = providers
        .gradleProperty("allowDebugReleaseSigning")
        .orElse(providers.environmentVariable("ALLOW_DEBUG_RELEASE_SIGNING"))
        .map(String::toBoolean)
        .getOrElse(false)

    namespace = "org.siloserver.silo.tv"
    compileSdk = 36
    defaultConfig {
        // Shares one Play listing with the phone app, so both must use the
        // same applicationId. Play routes phone vs TV builds by manifest
        // feature filtering (this app requires android.software.leanback; the
        // phone app requires android.hardware.touchscreen). The `namespace`
        // above stays distinct so the generated R/BuildConfig classes don't
        // collide with the phone module.
        applicationId = "org.siloserver.silo"
        minSdk = 24
        targetSdk = 35
        // Two artifacts under one listing need distinct versionCodes: phone =
        // base*2, TV = base*2+1, so each release bumps both by 2 with no reuse.
        versionCode = siloVersionCode.get() * 2 + 1
        versionName = siloVersionName.get()
        // Shadow the android-shared BuildConfig field so per-app flavors can
        // override without rebuilding the shared module. See androidApp's
        // build.gradle.kts for rationale.
        buildConfigField("boolean", "FFMPEG_AUDIO_ENABLED", "true")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    buildTypes {
        release {
            // Launch-prep: full R8 + resource shrinking, sharing the root
            // proguard-rules.pro with :androidApp (same reflection/JNI-heavy
            // shared + android-shared stack). R8 breakage is runtime-only, so a
            // minified install must be smoke-tested on a TV before shipping.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                rootProject.file("proguard-rules.pro"),
            )
            // No release keystore yet (pre-1.0). Only debug-sign release builds
            // for local smoke tests when explicitly opted in.
            if (allowDebugReleaseSigning) {
                signingConfig = signingConfigs.getByName("debug")
            }
        }
    }
    // Mirror androidApp's ABI split strategy. See that file for rationale.
    bundle {
        abi {
            enableSplit = true
        }
    }
    splits {
        abi {
            // Off for bundle builds — the AAB handles per-ABI delivery itself.
            isEnable = !isBuildingBundle
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        isCoreLibraryDesugaringEnabled = true
    }
    testOptions {
        unitTests {
            // Default to safe no-op stubs for android.* classes (e.g. android.util.Log.w)
            // so tests can exercise code paths that touch them without requiring Robolectric.
            isReturnDefaultValues = true
        }
    }
    packaging {
        resources {
            // BouncyCastle (bcprov/bctls/bcutil) + jspecify each ship this OSGi
            // multi-release stub; drop the duplicates so the APK packages.
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
