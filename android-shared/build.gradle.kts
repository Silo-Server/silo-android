plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
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
            // Public player constructors accept LibassBridge, so downstream
            // phone/TV DI modules need the Java bridge type on their compile
            // classpaths. ass-media itself remains an implementation detail of
            // :libass-bridge and is not exposed to Kotlin compilation.
            api(project(":libass-bridge"))

            // DataStore (per-profile player settings store)
            implementation(libs.datastore.preferences)

            // Compose (for ThumbhashImage)
            implementation(compose.ui)
            implementation(compose.foundation)
            implementation(compose.runtime)

            // Image loading
            implementation(libs.coil.compose)
            implementation(libs.coil.network.okhttp)

            // Ktor client (DownloadWorker streaming, diagnostics upload).
            // Previously satisfied transitively by coil-network-ktor3.
            implementation(libs.ktor.client.core)

            // Media3 player infrastructure
            implementation(libs.media3.exoplayer)
            implementation(libs.media3.exoplayer.hls)
            implementation(libs.media3.datasource.okhttp)
            implementation(libs.media3.common)
            implementation(libs.media3.common.ktx)
            implementation(libs.media3.extractor)
            implementation(libs.media3.container)
            implementation(libs.media3.session)
            // SubtitleManager.applyAppearance reaches into PlayerView.subtitleView
            // and CaptionStyleCompat — both live in media3-ui.
            implementation(libs.media3.ui)
            // Media3 FFmpeg audio decoder extension. Shipped as a private
            // AAR built by scripts/build-ffmpeg-aar.sh — Google doesn't
            // publish this one on Maven (LGPL distribution compliance).
            // Classpath presence is the runtime signal used by
            // FfmpegAudioSupport.isAvailable() and by DefaultRenderersFactory's
            // internal extension-renderer reflection; the compile-time
            // BuildConfig.FFMPEG_AUDIO_ENABLED flag gates whether we *prefer*
            // it over platform decoders.
            implementation(files("libs/media3-decoder-ffmpeg-1.11.0.aar"))
            // Reproducibly built JNI bridge for client-side Dolby Vision
            // Profile 7 RPU conversion. See scripts/build-dovi-aar.sh.
            implementation(files("libs/silo-dovi-bridge-2.3.1.aar"))

            // Coroutines
            implementation(libs.kotlinx.coroutines.android)

            // DI
            implementation(libs.koin.core)
            implementation(libs.koin.android)

            // Lifecycle ViewModel (KMP). The audiobook ViewModel lives here so
            // both the phone and TV apps can consume it; `shared` pulls this in
            // only as `implementation`, so it is not transitively visible.
            implementation(libs.lifecycle.viewmodel.kmp)

            // Serialization (media auth refresh path encodes RefreshRequest / decodes RefreshResponse)
            implementation(libs.kotlinx.serialization.json)

            // WorkManager — used by DownloadWorker. Phone app installs the
            // KoinWorkerFactory; TV app does the same for its own workers.
            implementation(libs.androidx.work.runtime.ktx)

            // Room — offline-first persistence (Track B). The KSP compiler is
            // wired below via the kspAndroid configuration; the Room Gradle
            // plugin (applied above) drives schema export to $projectDir/schemas.
            implementation(libs.androidx.room.runtime)
            implementation(libs.androidx.room.ktx)

            // BouncyCastle — TLS-PSK server for the LAN companion-pairing
            // receiver (TV). Android's stdlib JSSE does not expose PSK cipher
            // suites, so the receiver drives TlsServerProtocol / PSKTlsServer.
            implementation(libs.bouncycastle.prov)
            implementation(libs.bouncycastle.tls)
        }

        // First tests in this module — JUnit 4 via kotlin-test-junit, which
        // is the KMP idiom for androidTarget unit tests. Covers the FFmpeg
        // classpath guard and the TrackSelectionPresets MIME-union logic.
        // PlaybackCapabilityDetector can't be tested at unit-test scope
        // without Robolectric (MediaCodecList + DisplayHdrProbe hit Android
        // APIs) — defer that until a Robolectric test harness is needed.
        androidUnitTest.dependencies {
            implementation(kotlin("test"))
            implementation(kotlin("test-junit"))
            implementation(libs.kotlinx.coroutines.test)

            // Room DAO tests run under Robolectric — Room's in-memory builder
            // needs a real android.content.Context (ApplicationProvider) and a
            // SQLite implementation, neither of which the default unit-test
            // stubs provide. androidx-test-core supplies ApplicationProvider.
            implementation(libs.androidx.test.core)
            implementation(libs.androidx.room.testing)
            implementation(libs.robolectric)
            implementation(libs.media3.test.utils)
            implementation(libs.media3.test.utils.robolectric)
            implementation(libs.okhttp.mockwebserver)

            // SyncEngine tests drive a real PersonalDataApi over a MockEngine
            // HttpClient to exercise the outbox drain end-to-end.
            implementation(libs.ktor.client.mock)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.okhttp.mockwebserver)
        }
    }
}

android {
    namespace = "org.siloserver.silo.common"
    compileSdk = 36
    // Room migration schemas are local-test fixtures shared by both build variants.
    sourceSets.getByName("test").assets.srcDir("$projectDir/schemas")
    defaultConfig {
        minSdk = 24
        // Gate for preferring FFmpeg audio decoders over platform decoders.
        // The AAR is always on the classpath (see dependencies above); this
        // flag only controls whether DefaultRenderersFactory is set to
        // EXTENSION_RENDERER_MODE_ON (true) or _MODE_OFF (false).
        // Flip to false at compile time to bisect regressions — with _MODE_OFF
        // FFmpeg renderers are not even instantiated, so any FFmpeg-related
        // bug can't manifest regardless of classpath presence.
        buildConfigField("boolean", "FFMPEG_AUDIO_ENABLED", "true")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        isCoreLibraryDesugaringEnabled = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // Default to safe no-op stubs for android.* classes (e.g. android.util.Log.w)
            // so tests can exercise code paths that touch them without requiring Robolectric.
            isReturnDefaultValues = true
        }
    }

    // Lint had never run on this project. The two crashes it would have caught
    // — a Spatializer call gated at API 31 when the class arrives at 32, and a
    // getAddress() call with no guard at all — both shipped from this module,
    // which app-level lint does not analyse unless asked. Hence the gate here
    // and checkDependencies in the apps.
    //
    // The baseline holds today's known findings (overwhelmingly desugared
    // java.* calls and deliberate media3 @UnstableApi usage) so that only NEW
    // violations fail. Delete it and regenerate deliberately; do not add to it
    // to make a build pass.
    lint {
        baseline = file("lint-baseline.xml")
        abortOnError = true
        fatal += setOf("NewApi", "InlinedApi")
        checkReleaseBuilds = true
    }
}

// Room schema export — the generated JSON schemas are committed under
// android-shared/schemas/ and are the source of truth for migration tests.
room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // Room annotation processor. KSP for the KMP androidTarget is configured
    // through the kspAndroid configuration (not plain `ksp(...)`); the plain
    // accessor isn't created for multiplatform modules.
    add("kspAndroid", libs.androidx.room.compiler)
}
