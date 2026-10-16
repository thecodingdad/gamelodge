// The launcher for Windows: the shared UI in a desktop window, plus the PC's own games and the
// host role (former companion). Runs on any desktop JVM for development.

import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.security.MessageDigest

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
    implementation(libs.javasteam)
    implementation(libs.qrcodegen)
    implementation(libs.jmdns)

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

// Portable Windows test build, made on any OS: the app's jars, a Windows Java runtime (Temurin,
// checksum verified) and a start file. Unzip anywhere and run "Moonlight Launcher.cmd".
val windowsJreUrl = "https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jre_x64_windows_hotspot_17.0.20.1_1.zip"
val windowsJreSha256 = "bc21a93923103cdaac93ee337b0ae4365e739fde36df823dd456bc67c8a9d352"
val windowsJreZip = gradle.gradleUserHomeDir.resolve("caches/moonlight-launcher/OpenJDK17U-jre_x64_windows_hotspot_17.0.20.1_1.zip")

val downloadWindowsJre by tasks.registering {
    outputs.file(windowsJreZip)
    doLast {
        fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        if (windowsJreZip.exists() && sha256(windowsJreZip) == windowsJreSha256) return@doLast
        windowsJreZip.parentFile.mkdirs()
        val tmp = File(windowsJreZip.path + ".tmp")
        uri(windowsJreUrl).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
        check(sha256(tmp) == windowsJreSha256) { "Checksum mismatch for the Windows runtime" }
        tmp.renameTo(windowsJreZip)
    }
}

val windowsApp by tasks.registering(Sync::class) {
    dependsOn(downloadWindowsJre)
    into(layout.buildDirectory.dir("windows/MoonlightLauncher"))
    into("lib") {
        from(tasks.named("jar"))
        from(configurations.runtimeClasspath) {
            // Windows needs only its own natives of the UI toolkit.
            exclude("skiko-awt-runtime-linux*", "skiko-awt-runtime-macos*")
        }
        // Different groups publish jars of the same name (e.g. androidx and JetBrains lifecycle):
        // the group in front keeps all of them.
        eachFile {
            val parts = file.path.split(File.separatorChar)
            val cache = parts.indexOf("files-2.1")
            if (cache >= 0) name = "${parts[cache + 1]}-$name"
        }
    }
    into("runtime") {
        from(zipTree(windowsJreZip)) {
            // runtime/jdk-…-jre/bin/… -> runtime/bin/…
            eachFile { relativePath = RelativePath(true, *(relativePath.segments.take(1) + relativePath.segments.drop(2)).toTypedArray()) }
            includeEmptyDirs = false
        }
    }
    // The streaming client: Moonlight-Qt (submodule, built on Windows by tools/build-moonlight.sh).
    val moonlightZip = rootProject.layout.projectDirectory.file("build/moonlight/MoonlightPortable-x64.zip").asFile
    if (moonlightZip.exists()) {
        into("moonlight") {
            from(zipTree(moonlightZip)) {
                // Built on Windows: the entries use backslashes.
                eachFile { path = path.replace('\\', '/') }
                includeEmptyDirs = false
            }
        }
    } else {
        logger.warn("build/moonlight/MoonlightPortable-x64.zip missing: package without streaming client (tools/build-moonlight.sh)")
    }
    from(layout.projectDirectory.file("windows/Moonlight Launcher.cmd"))
}

val windowsZip by tasks.registering(Zip::class) {
    from(windowsApp)
    into("MoonlightLauncher")
    archiveFileName.set("MoonlightLauncher-windows.zip")
    destinationDirectory.set(rootProject.layout.projectDirectory.dir("bin"))
}
