plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    // Pinned once here so the :baselineprofile macrobenchmark module can apply
    // com.android.test (shares the AGP artifact with com.android.application) and
    // kotlin-android (shares the Kotlin artifact with kotlin-multiplatform)
    // without Gradle failing to reconcile their versions across modules.
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.androidx.baselineprofile) apply false
}
