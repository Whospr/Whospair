package com.whisper.services

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * Persistance chiffrée : EncryptedSharedPreferences, clé maîtresse dans le Keystore.
 *
 * Toutes les écritures passent par `commit()`, pas `apply()`. `apply()` écrit
 * en différé : un processus tué dans la foulée perd l'écriture. Pour la
 * passphrase de base, ça veut dire une base créée avec une clé jamais
 * sauvegardée — irrécupérable. Pour l'effacement d'urgence, une passphrase
 * « supprimée » qui survit sur le disque. Ces écritures sont rares et
 * minuscules : le coût du synchrone est nul.
 */
class StorageService(private val context: Context) {

    private val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)

    private val prefs = EncryptedSharedPreferences.create(
        FILE_NAME,
        masterKeyAlias,
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveString(key: String, value: String) = prefs.edit().putString(key, value).commit()
    fun getString(key: String, default: String = ""): String = prefs.getString(key, default) ?: default

    fun saveLong(key: String, value: Long) = prefs.edit().putLong(key, value).commit()
    fun getLong(key: String, default: Long = 0L): Long = prefs.getLong(key, default)

    fun saveBoolean(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).commit()
    fun getBoolean(key: String, default: Boolean = false): Boolean = prefs.getBoolean(key, default)

    fun saveInt(key: String, value: Int) = prefs.edit().putInt(key, value).commit()
    fun getInt(key: String, default: Int = 0): Int = prefs.getInt(key, default)

    fun remove(key: String) = prefs.edit().remove(key).commit()
    fun contains(key: String): Boolean = prefs.contains(key)

    /** Effacement d'urgence : clés, identité, réglages — tout. */
    fun clearAll() = prefs.edit().clear().commit()

    /**
     * Effacement total, au-delà du contenu : le fichier (qui garde aussi les
     * jeux de clés internes d'EncryptedSharedPreferences) et la clé maîtresse
     * du Keystore. Plus rien ne subsiste, ni sur le disque ni dans la puce.
     *
     * Rend CETTE instance inutilisable : à n'appeler que juste avant de tuer
     * le processus.
     */
    fun destroy() {
        clearAll()
        context.deleteSharedPreferences(FILE_NAME)
        java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            .deleteEntry(masterKeyAlias)
    }

    companion object {
        private const val FILE_NAME = "whisper_encrypted_storage"

        const val KEY_IDENTITY_PUBKEY = "identity_pubkey"

        /**
         * Clés privées X25519 et Ed25519. Ici parce que le Keystore ne gère
         * ni l'une ni l'autre avant l'API 33.
         */
        const val KEY_IDENTITY_PRIVKEY = "identity_privkey"
        const val KEY_IDENTITY_SIGN_PRIV = "identity_sign_priv"
        const val KEY_IDENTITY_SIGN_PUB = "identity_sign_pub"
        const val KEY_IDENTITY_CREATED_AT = "identity_created_at"

        /** Les deux clés privées, scellées sous une sous-clé du coffre. */
        const val KEY_IDENTITY_SEALED = "identity_sealed"
        const val KEY_FIRST_LAUNCH = "first_launch"

        /** Étapes du premier lancement, pour reprendre au bon endroit. */
        const val KEY_TERMS_ACCEPTED = "terms_accepted"
        const val KEY_TUTORIAL_COMPLETED = "tutorial_completed"

        /** Passphrase SQLCipher. La supprimer rend la base illisible. */
        const val KEY_DB_PASSPHRASE = "db_passphrase"
        /** Durée de vie choisie dans les Paramètres, appliquée à chaque nouvelle conversation. */
        const val KEY_DEFAULT_TTL = "default_ttl_minutes"

        /** Notre photo de profil (JPEG 256 px), scellée sous le PIN, en Base64. */
        const val KEY_PROFILE_PHOTO = "profile_photo"
        /** Qui la reçoit : EVERYONE, CONFIRMED ou HIDDEN (ProfilePhotoService.Visibility). */
        const val KEY_PROFILE_PHOTO_VISIBILITY = "profile_photo_visibility"

        /** Écran masqué par la caméra frontale : OFF, LOOK_AWAY ou ONLOOKER (ScreenShield.GazeMode). */
        const val KEY_GAZE_MODE = "gaze_mode"
    }
}
