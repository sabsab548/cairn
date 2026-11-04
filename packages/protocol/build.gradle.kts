plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm()
    android {
        publishLibraryVariants("release")
    }
    iosArm64()
    iosSimulatorArm64()
    iosX64()

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
