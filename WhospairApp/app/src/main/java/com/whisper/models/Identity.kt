package com.whisper.models

/**
 * Identité utilisateur unique.
 *
 * Deux paires de clés, et c'est indispensable :
 *  - X25519 (publicKeyHex / encryptionPrivateKeyHex) pour l'échange de clés.
 *    C'est cette clé publique qui identifie l'utilisateur et circule en QR.
 *  - Ed25519 (signingPublicKeyHex / signingPrivateKeyHex) pour prouver qu'un
 *    message vient bien de nous. Une même clé ne peut pas faire les deux :
 *    ce sont deux courbes et deux usages.
 *
 * Android Keystore ne gère ni X25519 ni Ed25519 avant l'API 33. Les deux clés
 * privées sont donc persistées par StorageService (EncryptedSharedPreferences,
 * adossé à une clé AES du Keystore). Compromis assumé sur minSdk 29.
 */
data class Identity(
    val publicKeyHex: String,             // Clé publique X25519 (64 hex)
    val encryptionPrivateKeyHex: String,  // Clé privée X25519 (64 hex)
    val signingPublicKeyHex: String,      // Clé publique Ed25519 (64 hex)
    val signingPrivateKeyHex: String,     // Clé privée Ed25519 (64 hex)
    val createdAt: Long = System.currentTimeMillis()
) {
    /**
     * Valide que la clé publique est bien formée.
     * Clé publique X25519 brute = 32 bytes = 64 caractères hex.
     */
    fun isValid(): Boolean {
        return publicKeyHex.length == 64 && publicKeyHex.matches(HEX_REGEX)
    }

    /**
     * Valide que la clé privée est exploitable.
     */
    fun hasUsablePrivateKey(): Boolean {
        return encryptionPrivateKeyHex.length == 64 &&
                encryptionPrivateKeyHex.matches(HEX_REGEX) &&
                signingPrivateKeyHex.length == 64 &&
                signingPrivateKeyHex.matches(HEX_REGEX)
    }

    /**
     * Version sûre pour les logs : jamais la clé privée.
     */
    override fun toString(): String =
        "Identity(publicKey=${publicKeyHex.take(8)}…, createdAt=$createdAt)"

    companion object {
        private val HEX_REGEX = Regex("[0-9a-fA-F]+")
    }
}
