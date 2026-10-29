pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "ghostforge"

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

include(":core")
include(":app")
