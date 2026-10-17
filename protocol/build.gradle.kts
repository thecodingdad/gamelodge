// The launcher's wire protocol with its servers (companion, Windows launcher, metadata server):
// messages, J-PAKE pairing, fingerprints, host games; plus the log and the secret store they use.
// Kept free of UI so the headless metadata server shares it with both apps.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.serialization)
}

// The metadata server's Docker build (-PserverOnly) has no Android SDK: JVM only there.
val withAndroid = !providers.gradleProperty("serverOnly").isPresent
if (withAndroid) apply(plugin = "com.android.library")

kotlin {
    if (withAndroid) androidTarget {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    jvm {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
            api(libs.bouncycastle.prov)
        }
    }
}

if (withAndroid) extensions.configure<com.android.build.gradle.LibraryExtension>("android") {
    namespace = "io.github.thecodingdad.moonlightlauncher.protocol"
    compileSdk = 36
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
