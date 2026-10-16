# ==================== WHISPER — RÈGLES PROGUARD ====================
# Le buildType release active isMinifyEnabled et référence ce fichier.
# Son absence faisait échouer le build release.

# -------------------- CRYPTO --------------------
# Les classes crypto sont atteintes par réflexion via les providers JCE.
-keep class com.whisper.crypto.** { *; }
-keep class com.whisper.security.** { *; }

# Bouncy Castle : X25519, HKDF, AES-GCM.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
# Les providers JCE sont chargés par nom, jamais référencés statiquement.
-keep class org.bouncycastle.jce.provider.BouncyCastleProvider { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }

# -------------------- SECURITY-CRYPTO (Tink) --------------------
# Tink référence des annotations de compilation absentes à l'exécution :
# sans ces lignes, R8 échoue en release sur « Missing class ».
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# -------------------- SQLCIPHER --------------------
# Pont JNI : ces classes sont appelées depuis le code natif, R8 ne peut pas
# voir ces références et les supprimerait.
-keep class net.zetetic.database.** { *; }
-keep class net.sqlcipher.** { *; }
-dontwarn net.zetetic.database.**

# -------------------- ROOM --------------------
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# -------------------- TOR --------------------
# TorService appelle le démon Tor en JNI ; jtorctl lit les réponses par
# réflexion légère. R8 ne voit pas ces chemins et les supprimerait.
-keep class org.torproject.jni.** { *; }
-keep class net.freehaven.tor.control.** { *; }
-dontwarn org.torproject.jni.**

# -------------------- HILT / DAGGER --------------------
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-dontwarn dagger.hilt.**

# -------------------- SÉRIALISATION --------------------
# kotlinx.serialization génère des serializers référencés par réflexion.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.whisper.** {
    *** Companion;
}
-keepclasseswithmembers class com.whisper.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# -------------------- ZXING (QR) --------------------
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# -------------------- HYGIÈNE --------------------
# Retire tous les appels de log en release : même un Log.d anodin peut
# révéler des identifiants de messages ou des clés publiques dans logcat.
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# Conserve les numéros de ligne pour des stack traces exploitables,
# tout en masquant les noms de fichiers source.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# -------------------- 1.3.7 : R8 sans obfuscation --------------------
# Décision de l'auteur : pas d'obfuscation, la sécurité repose sur la crypto.
# R8 réduit et optimise seulement (APK plus léger).
-dontobfuscate

# Module natif de reconnaissance du visage : méthodes appelées par JNI.
-keep class com.whisper.shield.FaceEngine {
    native <methods>;
}
