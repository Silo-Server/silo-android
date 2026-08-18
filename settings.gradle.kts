pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "SiloAndroid"
include(":shared")
include(":libass-bridge")
include(":android-shared")
include(":androidApp")
include(":androidTvApp")
include(":baselineprofile")
include(":baselineprofile-tv")
