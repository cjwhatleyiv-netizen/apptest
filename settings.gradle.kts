pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.0.21"
        id("org.jetbrains.kotlin.android") version "2.0.21"
        id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
        id("com.android.application") version "8.9.1"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        // Android artifacts; not needed (and not reachable in some sandboxes) for `-PcoreOnly`.
        if (!providers.gradleProperty("coreOnly").isPresent) google()
    }
}

rootProject.name = "pksm-droid"
include(":core")
// `-PcoreOnly` builds/tests just the save-editing library (no Android SDK needed).
if (!providers.gradleProperty("coreOnly").isPresent) include(":app")
