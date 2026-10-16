import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.dagger.hilt.android")
}

android {
    // Android 13+ : la langue de Whospair se choisit dans les réglages du
    // système, indépendamment de celle du téléphone (liste tirée de res/).
    androidResources {
        generateLocaleConfig = true
    }
    namespace = "com.whisper"
    compileSdk = 34

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES"
        )
    }

    defaultConfig {
        // Whospair (30/09) : nouvel identifiant, réinstallation acceptée.
        // Le code, lui, garde son espace de noms com.whisper (invisible).
        applicationId = "com.whospair"
        minSdk = 29 // Android 10 : encodeur Opus natif (MediaCodec) pour la voix
        targetSdk = 34
        // Phase 14 : chaque sortie monte versionCode — les mises à jour
        // refusent toute version égale ou plus ancienne.
        versionCode = 15
        versionName = "1.3.8"
        // Version minimale d'un contact (versionCode) : en dessous, plus aucun
        // message, appel ni signe dans un sens ou dans l'autre — seules les
        // mises à jour passent. À relever pour une sortie qui corrige une faille ;
        // à laisser pour un simple correctif d'affichage.
        buildConfigField("long", "MIN_PEER_VERSION", "12L")
        // Recherche de mise à jour auprès du diffuseur : 30 min au plus souvent
        // (1.3.8 ; 24 h avant — trop rare pour qui ouvre l'app une heure par
        // jour). Compilation de test (-PupdateTest) : 5 min, et « -test » dans
        // le nom de version — impossible de sortir une version de test par mégarde.
        val updateTest = project.hasProperty("updateTest")
        buildConfigField("long", "DIFFUSER_EVERY_MS", if (updateTest) "300000L" else "1800000L")
        // 5 jours de grâce (1.3.8), comptés par l'horloge interne : une nouvelle
        // version devient obligatoire 120 h après qu'on l'a apprise ; une app à
        // jour refuse les plus anciennes 120 h après sa propre installation.
        // Compilation de test : 10 min.
        buildConfigField("long", "GRACE_MS", if (updateTest) "600000L" else "432000000L")
        if (updateTest) versionNameSuffix = "-test"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Les trois adresses onion du diffuseur de mises à jour, fixes. Aucune
        // clé dans le dépôt ni dans l'APK public. Les adresses doivent être dans l'app AVANT sa
        // publication : un Whospair ne peut pas les apprendre après.
        buildConfigField(
            "String[]", "DIFFUSER_ONIONS",
            "{\"s6wi3qsqq6uznsx7ne46y6sb5padlhxfnzbark7cge3tv3hsrxn54oad\", \"tjdhr5xd7ekswji56xvy6exaiufuntykqil3kvhknuabahmrdepk6wyd\", \"tenqfmbowbr657ccvsm7qc4mvwgjofdmu5wfjm4kxukaj6uaoridcfqd\"}"
        )
    }

    // public : Whospair. Aucun import de paquet .wup, mises à jour par Tor
    // (contacts et diffuseur). Le diffuseur, lui, n'est pas publié.
    flavorDimensions += "role"
    productFlavors {
        create("public") {
            dimension = "role"
            buildConfigField("boolean", "IS_DIFFUSER", "false")
        }
    }

    // Clé release : hors du dépôt, sur la machine qui compile seulement
    // (chemin du fichier dans ~/.gradle/gradle.properties : whisperSigning=…).
    // Perdue, plus aucune mise à jour ne s'installe par-dessus : à sauvegarder.
    val signing = (findProperty("whisperSigning") as String?)?.let { file(it) }?.takeIf { it.exists() }
        ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }
    signingConfigs {
        if (signing != null) create("release") {
            storeFile = file(signing.getProperty("storeFile"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
            // v3 (1.3.5) : permet un jour de changer de clé (fuite, cession de
            // l'app) sans casser les mises à jour des apps installées.
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            // arm64 seul : l'APK passe de ~120 à ~20 Mo, et c'est lui qui
            // circule par Tor à chaque mise à jour.
            ndk { abiFilters += "arm64-v8a" }
            // R8 : retire le code et les ressources inutilisés, et applique
            // proguard-rules.pro — dont le retrait des Log.d/v/i en release.
            // Mis en pause (29/09) : R8 manque de mémoire sur la machine de
            // compilation. À réactiver avec -PwhisperR8=true. Sans lui, les
            // Log.d/v/i restent dans l'APK release.
            val r8 = findProperty("whisperR8") == "true"
            isMinifyEnabled = r8
            isShrinkResources = r8
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Core
    implementation("androidx.core:core-ktx:1.12.0")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    // ProcessLifecycleOwner : SessionLifecycle voit l'app passer en
    // arrière-plan et tue le processus. Même version que le reste du bloc.
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")

    // Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    // Icônes : aucun paquet. Les ~70 dont l'app se sert sont copiées dans
    // ui/icons (Apache 2.0). material3 tire material-icons-core sans jamais
    // s'en servir : on le retire.
    implementation("androidx.compose.material3:material3") {
        exclude(group = "androidx.compose.material", module = "material-icons-core")
        exclude(group = "androidx.compose.material", module = "material-icons-core-android")
    }
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.51.1")
    kapt("com.google.dagger:hilt-compiler:2.51.1")

    // Room
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    kapt("androidx.room:room-compiler:$roomVersion")

    // Crypto : X25519, Ed25519, HKDF, Argon2id (Bouncy Castle).
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")
    // EncryptedSharedPreferences, clé maîtresse dans le Keystore.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // SQLCipher
    // sqlcipher-android : la bibliothèque maintenue (l'ancienne
    // android-database-sqlcipher est abandonnée). C'est elle qu'utilise
    // WhisperDatabase (net.zetetic.database.sqlcipher).
    implementation("net.zetetic:sqlcipher-android:4.6.1@aar")
    implementation("androidx.sqlite:sqlite-ktx:2.4.0")

    // Tor embarqué (Guardian Project) + port de contrôle. 0.4.9.13 : l'AAR
    // officiel exige Java 24 et compileSdk 37 sans en avoir besoin ; il est
    // vérifié puis ajusté par tools/tor-aar.py (libtor.so intact). Sa
    // dépendance kotlin-stdlib 2.x n'est pas reprise : aucune classe Kotlin.
    val torAar = file("libs/tor-android-0.4.9.13-j17.aar")
    if (!torAar.exists()) throw GradleException("Lancer d'abord : python3 tools/tor-aar.py")
    implementation(files(torAar))
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")
    implementation("info.guardianproject:jtorctl:0.4.5.7")

    // QR (scan et génération)
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // Sérialisation (trames de messages)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    // Test
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.02.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

kapt {
    correctErrorTypes = true
}
