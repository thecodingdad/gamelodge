// The launcher for Windows: the shared UI in a desktop window, plus the PC's own games and the
// host role (former companion). Runs on any desktop JVM for development.

import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":host"))
    implementation(compose.desktop.currentOs)
    // Windows natives as well, so a build from Linux runs on Windows.
    implementation(compose.desktop.windows_x64)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.androidx.sqlite.bundled)
    implementation(libs.jna.platform)
    implementation(libs.javasteam)
    implementation(libs.qrcodegen)
    implementation(libs.jmdns)

    testImplementation(libs.junit)
}

compose.desktop {
    application {
        mainClass = "io.github.thecodingdad.gamelodge.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "GameLodge"
            packageVersion = providers.gradleProperty("launcher.versionName").get()
            vendor = "thecodingdad"
            windows {
                menuGroup = "GameLodge"
                upgradeUuid = "6f1d3a52-5c1e-4b7a-9a5e-3c2f8d6b9e41"
            }
        }
    }
}

// Portable Windows test build, made on any OS: the app's jars, a Windows Java runtime and
// GameLodge.exe to start it. Unzip anywhere and run "GameLodge.exe". The runtime is linked (jlink)
// from the Windows JDK's modules (Temurin, checksum verified) with only the modules the app uses.
val windowsJdkUrl = "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jdk_x64_windows_hotspot_21.0.12.1_1.zip"
val windowsJdkSha256 = "f9d6e191ab098c0d416e7d588a24420a8621cd2f4720dab2459b8b7b2d2d8b4e"
val windowsJdkZip = gradle.gradleUserHomeDir.resolve("caches/gamelodge/OpenJDK21U-jdk_x64_windows_hotspot_21.0.12.1_1.zip")

val downloadWindowsJdk by tasks.registering {
    outputs.file(windowsJdkZip)
    doLast {
        fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        if (windowsJdkZip.exists() && sha256(windowsJdkZip) == windowsJdkSha256) return@doLast
        windowsJdkZip.parentFile.mkdirs()
        val tmp = File(windowsJdkZip.path + ".tmp")
        uri(windowsJdkUrl).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
        check(sha256(tmp) == windowsJdkSha256) { "Checksum mismatch for the Windows JDK" }
        tmp.renameTo(windowsJdkZip)
    }
}

// What the jars need (jdeps) plus what is loaded by name: locales, charsets, DNS, zip file systems,
// accessibility. jlink of the same version links the Windows modules from any OS.
val windowsRuntimeModules = listOf(
    "java.base", "java.desktop", "java.datatransfer", "java.logging", "java.management", "java.naming",
    "java.net.http", "java.prefs", "java.sql", "java.xml", "jdk.unsupported", "jdk.httpserver", "jdk.charsets",
    "jdk.localedata", "jdk.zipfs", "jdk.accessibility", "jdk.crypto.ec", "jdk.crypto.cryptoki", "jdk.naming.dns",
    "jdk.net", "jdk.management",
)
val windowsJmods = layout.buildDirectory.dir("windows/jdk")
val windowsRuntime = layout.buildDirectory.dir("windows/runtime")

val extractWindowsJmods by tasks.registering(Sync::class) {
    dependsOn(downloadWindowsJdk)
    from(zipTree(windowsJdkZip)) {
        include("*/jmods/**")
        eachFile { relativePath = RelativePath(true, *relativePath.segments.drop(2).toTypedArray()) }
        includeEmptyDirs = false
    }
    into(windowsJmods)
}

val jlinkWindowsRuntime by tasks.registering(Exec::class) {
    dependsOn(extractWindowsJmods)
    val jlink = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
        .map { it.metadata.installationPath.file("bin/jlink").asFile.path }
    inputs.dir(windowsJmods)
    inputs.property("modules", windowsRuntimeModules)
    outputs.dir(windowsRuntime)
    doFirst {
        windowsRuntime.get().asFile.deleteRecursively()
        commandLine(
            jlink.get(), "--module-path", windowsJmods.get().asFile.path,
            "--add-modules", windowsRuntimeModules.joinToString(","), "--include-locales=en,de",
            // Native symbols stay: stripping needs the target's objcopy.
            "--strip-java-debug-attributes", "--no-header-files", "--no-man-pages", "--compress=zip-6",
            "--output", windowsRuntime.get().asFile.path,
        )
    }
}

// Material icons: the extended set is one class per icon (37 MB); only the ones the app's code
// refers to are packaged.
val iconsJarName = "material-icons-extended"
val slimIconsJar by tasks.registering {
    val appJars = files(tasks.named("jar"), configurations.runtimeClasspath.map { cp -> cp.filter { it.name.startsWith("shared") || it.name.startsWith("host") } })
    val iconsJar = configurations.runtimeClasspath.map { cp -> cp.single { it.name.startsWith(iconsJarName) } }
    val output = layout.buildDirectory.file("windows/icons/$iconsJarName-slim.jar")
    inputs.files(appJars)
    inputs.file(iconsJar)
    outputs.file(output)
    doLast {
        val ref = Regex("androidx/compose/material/icons/[A-Za-z0-9_/$]+")
        val used = mutableSetOf<String>()
        appJars.files.forEach { jar ->
            ZipFile(jar).use { zip ->
                zip.entries().asSequence().filter { it.name.endsWith(".class") }.forEach { e ->
                    ref.findAll(String(zip.getInputStream(e).readBytes(), Charsets.ISO_8859_1)).forEach { used += it.value.substringBefore('$') }
                }
            }
        }
        val out = output.get().asFile.apply { parentFile.mkdirs() }
        ZipFile(iconsJar.get()).use { zip ->
            ZipOutputStream(out.outputStream()).use { dst ->
                zip.entries().asSequence().filter { e ->
                    !e.name.endsWith(".class") || e.name.substringBefore('$').removeSuffix(".class") in used
                }.forEach { e ->
                    dst.putNextEntry(ZipEntry(e.name))
                    zip.getInputStream(e).use { it.copyTo(dst) }
                    dst.closeEntry()
                }
            }
        }
        logger.lifecycle("Material icons: ${used.size} kept")
    }
}

// The version shown in the launcher and its logs.
tasks.named<Jar>("jar") {
    manifest { attributes("Implementation-Version" to providers.gradleProperty("launcher.versionName").get()) }
}

val windowsApp by tasks.registering(Sync::class) {
    into(layout.buildDirectory.dir("windows/GameLodge"))
    into("lib") {
        from(tasks.named("jar"))
        from(configurations.runtimeClasspath) {
            // Windows needs only its own natives of the UI toolkit; the icons come slimmed.
            exclude("skiko-awt-runtime-linux*", "skiko-awt-runtime-macos*", "$iconsJarName*")
        }
        from(slimIconsJar)
        // Different groups publish jars of the same name (e.g. androidx and JetBrains lifecycle):
        // the group in front keeps all of them.
        eachFile {
            val parts = file.path.split(File.separatorChar)
            val cache = parts.indexOf("files-2.1")
            if (cache >= 0) name = "${parts[cache + 1]}-$name"
        }
    }
    into("runtime") { from(jlinkWindowsRuntime) }
    // The streaming client: Moonlight-Qt (submodule, built on Windows by tools/build-moonlight.sh).
    val moonlightZip = rootProject.layout.projectDirectory.file("build/moonlight/MoonlightPortable-x64.zip").asFile
    if (moonlightZip.exists()) {
        into("moonlight") {
            from(zipTree(moonlightZip)) {
                // Built on Windows: the entries use backslashes.
                eachFile { path = path.replace('\\', '/') }
                // Qt's own translations: German and English only.
                exclude { it.path.replace('\\', '/').let { p -> p.startsWith("translations/") && !p.endsWith("_de.qm") && !p.endsWith("_en.qm") } }
                includeEmptyDirs = false
            }
        }
    } else {
        logger.warn("build/moonlight/MoonlightPortable-x64.zip missing: package without streaming client (tools/build-moonlight.sh)")
    }
    // The Xbox player: its page (xbox-player/dist, committed) and the WebView2 window around it (built on
    // Windows by tools/build-xbox-player-exe.sh).
    into("xbox") { from(rootProject.layout.projectDirectory.dir("xbox-player/dist/xbox-player")) }
    val xboxPlayer = rootProject.layout.projectDirectory.file("build/xbox-player/gamelodge-xbox-player.exe").asFile
    if (xboxPlayer.exists()) {
        into("xbox") { from(xboxPlayer) }
    } else {
        logger.warn("build/xbox-player/gamelodge-xbox-player.exe missing: package without Xbox player (tools/build-xbox-player-exe.sh)")
    }
    // The native starter and the runtime under the app's name (built on Windows by
    // tools/build-windows-launcher.sh); without them the package starts with the .cmd file.
    val starter = rootProject.layout.projectDirectory.dir("build/windows-launcher").asFile
    if (File(starter, "GameLodge.exe").exists()) {
        from(File(starter, "GameLodge.exe"))
        // Shown by the starter until the window is there.
        from(layout.projectDirectory.file("windows/splash.png"))
        into("runtime/bin") { from(File(starter, "runtime-GameLodge.exe")) { rename { "GameLodge.exe" } } }
    } else {
        logger.warn("build/windows-launcher/GameLodge.exe missing: package starts with GameLodge.cmd (tools/build-windows-launcher.sh)")
        from(layout.projectDirectory.file("windows/GameLodge.cmd"))
    }
}

val windowsZip by tasks.registering(Zip::class) {
    from(windowsApp)
    into("GameLodge")
    archiveFileName.set("GameLodge-windows.zip")
    destinationDirectory.set(rootProject.layout.projectDirectory.dir("bin"))
}

// The render tests build many launchers that show the hosts page: without this each one would
// find the real hosts in the network by mDNS and keep polling them while the tests run.
tasks.withType<Test>().configureEach {
    systemProperty("gamelodge.hostDiscovery", "off")
}
