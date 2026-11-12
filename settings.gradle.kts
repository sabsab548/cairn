pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "cairn"

include(":packages:protocol")
include(":packages:sync")
include(":packages:meshtastic")
include(":apps:android:app")
include(":apps:desktop")
