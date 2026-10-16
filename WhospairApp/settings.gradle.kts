// ==================== settings.gradle.kts ====================
// Fichier: settings.gradle.kts (à la racine du projet, MÊME NIVEAU que app/)

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
        maven(url = "https://jitpack.io")
    }
}

rootProject.name = "WhisperApp"
include(":app")
