plugins {
    kotlin("multiplatform")
    id("com.android.library")
}

kotlin {
    androidTarget()
    jvm()
    iosArm64()
    iosSimulatorArm64()
    iosX64()

    sourceSets {
        commonMain.dependencies {
            // `RadioTransport` (public API) and `SyncEngine` both expose
            // protocol types, so they must be visible to consumers too.
            api(project(":packages:protocol"))
            // RadioTransport.state is a StateFlow.
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "org.cairn.sync"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }
}
