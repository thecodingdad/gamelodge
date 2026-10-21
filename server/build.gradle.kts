// GameLodge Metadata Server: SteamGridDB/IGDB for paired launchers, headless (Docker). The same
// client API, pairing and web admin as the Windows launcher's host role (:host).

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":host"))
    testImplementation(libs.junit)
}

application {
    applicationName = "gamelodge-server"
    mainClass.set("io.github.thecodingdad.gamelodge.server.MainKt")
    applicationDefaultJvmArgs = listOf("-Xmx256m", "-XX:+UseSerialGC")
}

tasks.jar {
    manifest { attributes("Implementation-Version" to providers.gradleProperty("launcher.versionName").get()) }
}
