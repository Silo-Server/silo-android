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
include(":android-shared")
include(":androidApp")
include(":androidTvApp")
include(":baselineprofile")
