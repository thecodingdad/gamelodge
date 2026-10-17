pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "gamelodge"

// -PserverOnly: only the metadata server and what it needs (Docker build, no Android SDK).
val serverOnly = providers.gradleProperty("serverOnly").isPresent
include(":protocol")
include(":host")
include(":server")
if (!serverOnly) {
    include(":app")
    include(":shared")
    include(":desktop")
    include(":moonlight-core")
}
