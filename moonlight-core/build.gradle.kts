// Builds the Artemis sources from the `artemis` submodule as an Android library,
// so Artemis itself stays unmodified and upstream updates are a submodule bump.

plugins {
    alias(libs.plugins.android.library)
}

val artemisMain = rootProject.file("artemis/app/src/main")
val launcherAppId = providers.gradleProperty("launcher.applicationId").get()
val launcherVersionName = providers.gradleProperty("launcher.versionName").get()

android {
    namespace = "com.limelight"
    compileSdk = 36
    ndkVersion = "27.0.12077973"

    defaultConfig {
        minSdk = 21
        consumerProguardFiles(rootProject.file("artemis/app/proguard-rules.pro"))

        // Artemis reads these from BuildConfig, which a library does not generate by itself.
        buildConfigField("String", "APPLICATION_ID", "\"$launcherAppId\"")
        buildConfigField("String", "VERSION_NAME", "\"$launcherVersionName\"")
        buildConfigField("boolean", "ROOT_BUILD", "false")

        // Normally injected by Artemis' app module via resValue.
        resValue("string", "app_label", "GameLodge")
        resValue("string", "app_label_root", "GameLodge")
        resValue("string", "app_label_game", "GameLodge")
        resValue("string", "obtainium_app_url", "https://github.com/thecodingdad/gamelodge")

        externalNativeBuild {
            ndkBuild {
                arguments += "PRODUCT_FLAVOR=nonRoot"
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildFeatures {
        buildConfig = true
        resValues = true
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile(File(artemisMain, "AndroidManifest.xml"))
            java.setSrcDirs(listOf(File(artemisMain, "java")))
            res.setSrcDirs(listOf(File(artemisMain, "res")))
            assets.setSrcDirs(listOf(File(artemisMain, "assets")))
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = File(artemisMain, "jni/Android.mk")
        }
    }

    compileOptions {
        encoding = "UTF-8"
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    lint {
        disable += "MissingTranslation"
        lintConfig = rootProject.file("artemis/app/lint.xml")
        // Upstream code: report, but don't fail our build on Artemis' existing issues.
        abortOnError = false
        checkReleaseBuilds = false
    }
}

// Keep in sync with artemis/app/build.gradle
dependencies {
    api("org.bouncycastle:bcprov-jdk18on:1.81")
    api("org.bouncycastle:bcpkix-jdk18on:1.81")
    implementation("org.jcodec:jcodec:0.2.5")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jmdns:jmdns:3.6.2")
    implementation("com.github.cgutman:ShieldControllerExtensions:1.0.1")
    implementation("com.google.code.gson:gson:2.13.1")
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("androidx.cardview:cardview:1.0.0")
    api("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.preference:preference:1.2.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("com.google.android.material:material:1.13.0")
    implementation("com.github.ByteHamster:SearchPreference:v2.5.1")
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")
    implementation("com.google.ai.edge.litert:litert:1.4.0")
    implementation("com.google.ai.edge.litert:litert-gpu:1.4.0")
    implementation("org.opencv:opencv:4.12.0")
}
