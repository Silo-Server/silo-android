plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "org.siloserver.silo.libass"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    // Kept behind this Java-only module because ass-media 0.4.0 is compiled
    // with Kotlin 2.2 while the Silo application remains on Kotlin 2.1. The
    // wrapper's public API exposes only Java/Media3 types, so Kotlin metadata
    // from the implementation never enters android-shared's compile classpath.
    implementation(libs.ass.media)
    implementation(libs.ass.kt)
    implementation(libs.media3.common)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.extractor)
    implementation(libs.media3.ui)
    testImplementation(libs.junit4)
}
