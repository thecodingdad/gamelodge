// The launcher for Windows: the shared UI in a desktop window, plus the PC's own games and the
// host role (former companion). Runs on any desktop JVM for development.

import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    // Windows natives as well, so a build from Linux runs on Windows.
    implementation(compose.desktop.windows_x64)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.androidx.sqlite.bundled)
    implementation(libs.jamepad)
    implementation(libs.jna.platform)

    testImplementation(libs.junit)
}

compose.desktop {
    application {
        mainClass = "io.github.thecodingdad.moonlightlauncher.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "Moonlight Launcher"
            packageVersion = providers.gradleProperty("launcher.versionName").get()
            vendor = "thecodingdad"
            windows {
                menuGroup = "Moonlight Launcher"
                upgradeUuid = "6f1d3a52-5c1e-4b7a-9a5e-3c2f8d6b9e41"
            }
        }
    }
}
