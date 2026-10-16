// ==================== ROOT build.gradle.kts ====================
// Fichier: build.gradle.kts (à la racine du projet, MÊME NIVEAU que app/)
// Ne pas confondre avec app/build.gradle.kts

plugins {
    // AGP 8.1 ne connaît pas compileSdk 35 : il faut au moins 8.6.
    id("com.android.application") version "8.6.0" apply false
    kotlin("android") version "1.9.22" apply false
    kotlin("kapt") version "1.9.22" apply false
    // Requis par SignalingService (kotlinx.serialization.json).
    // La version DOIT suivre celle de Kotlin ci-dessus.
    kotlin("plugin.serialization") version "1.9.22" apply false
    id("com.google.dagger.hilt.android") version "2.51.1" apply false
}

// Pas de bloc allprojects { repositories { … } } ici :
// settings.gradle.kts déclare RepositoriesMode.FAIL_ON_PROJECT_REPOS,
// donc redéclarer des dépôts au niveau projet fait échouer le build avec
// « repository 'Google' was added by build file ».
// Les dépôts vivent uniquement dans settings.gradle.kts.

// rootProject.buildDir est déprécié depuis Gradle 8.
tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
