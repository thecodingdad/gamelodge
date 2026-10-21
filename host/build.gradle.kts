// The server side shared by the Windows launcher's host role and the headless metadata server:
// client API (mutual TLS), J-PAKE pairing, device registry, metadata proxy, web admin, identity.

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":protocol"))
    api(libs.okhttp)
    api(libs.bouncycastle.pkix)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.qrcodegen)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
