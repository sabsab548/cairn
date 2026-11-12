plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm("desktop")
    jvmToolchain(17)

    sourceSets {
        val desktopMain by getting {
            dependencies {
                implementation(project(":packages:protocol"))
                implementation(project(":packages:sync"))
                implementation(project(":packages:meshtastic"))
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

/**
 * `./gradlew :apps:desktop:runHarness` — the dev harness.
 *
 * A plain `JavaExec` rather than the `application` plugin, because the
 * multiplatform plugin does not create the `main` source set that plugin wires
 * itself to. It is also not named `desktopRun`: the Kotlin plugin already
 * creates one of those as an IDE carrier, and reusing the name makes it log a
 * warning on every build.
 *
 * The module keeps the `jvm("desktop")` layout so a Compose Desktop UI can be
 * added later without reshuffling source sets.
 */
val runHarness by tasks.registering(JavaExec::class) {
    group = "application"
    description = "Run the Cairn desktop dev harness"
    dependsOn(tasks.named("desktopJar"))
    classpath = files(tasks.named("desktopJar"), project.configurations.named("desktopRuntimeClasspath"))
    mainClass.set("org.cairn.desktop.MainKt")
    standardInput = System.`in`
}
