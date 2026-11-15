pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal {
            content {
                // The portal now serves a repackaged copy of this artifact whose
                // checksums differ from the pinned ones in
                // gradle/verification-metadata.xml; Maven Central serves the
                // pinned files. Only the plugin marker comes from the portal.
                excludeModule("org.jetbrains.compose", "compose-gradle-plugin")
            }
        }
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
