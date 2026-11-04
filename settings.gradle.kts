pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "cairn"

include(":packages:protocol")
include(":packages:sync")
// TODO: include(":apps:android") once apps/android exists (see apps/android/TODO.md)
