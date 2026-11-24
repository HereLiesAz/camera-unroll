rootProject.name = "CameraUnroll"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        maven("https://raw.githubusercontent.com/HereLiesAz/AzNavRail/update-maven/") {
            content { includeModule("com.github.HereLiesAz.AzNavRail", "aznavrail-updates-compat") }
        }
        google()
        mavenCentral()
    }
}

include(":composeApp")
