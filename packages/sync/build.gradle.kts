plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm()
    iosArm64()
    iosSimulatorArm64()
    iosX64()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":packages:protocol"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
