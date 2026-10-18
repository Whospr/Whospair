package com.whisper.services

import com.whisper.R
import com.whisper.crypto.CryptoManager
import com.whisper.crypto.EncryptedData
import com.whisper.crypto.SignatureVerificationException
import com.whisper.models.Contact
import com.whisper.models.Identity
import com.whisper.models.Message
import com.whisper.models.MessageStatus
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MessageService - Chiffrement et déchiffrement authentifiés.
 *
 * Trois garanties, et il fallait les trois :
 *  - confidentialité : ECIES X25519 + AES-256-GCM,
 *  - authenticité : chaque message est signé en Ed25519, donc on sait de qui
 *    il vient — l'ancienne version était anonyme, n'importe qui pouvait
 *    écrire en se faisant passer pour un contact,
 *  - fraîcheur : un compteur monotone lié cryptographiquement au message
 *    interdit qu'un chiffré capturé soit rejoué.
 *
 * L'API prend des Identity et des Contact plutôt que des chaînes hexadécimales
 * : il devient impossible d'oublier la clé de signature ou d'intervertir
 * l'expéditeur et le destinataire.
 */
@Singleton
class MessageService @Inject constructor(
    private val cryptoManager: CryptoManager
) {

    /**
     * Chiffre et signe un message pour un contact.
     *
     * @param counter numéro réservé par ContactRepository.nextSentCounter().
     *        Doit être strictement croissant pour ce contact.
     */
    fun encryptMessage(
        plainText: String,
        sender: Identity,
        recipient: Contact,
        counter: Long,
        ttlMinutes: Int = DEFAULT_TTL_MINUTES
    ): Message {
        require(ttlMinutes in 1..MAX_TTL_MINUTES) {
            "TTL hors bornes: $ttlMinutes min (autorisé: 1..$MAX_TTL_MINUTES)"
        }

        val plainBytes = plainText.toByteArray(Charsets.UTF_8)
        return try {
            encryptBytes(plainBytes, sender, recipient, counter, ttlMinutes)
        } finally {
            cryptoManager.zeroize(plainBytes)
        }
    }

    /**
     * Même chose pour des octets bruts : un morceau de fichier (phase 16)
     * n'a pas à passer par du texte. L'appelant efface [plainBytes].
     */
    fun encryptBytes(
        plainBytes: ByteArray,
        sender: Identity,
        recipient: Contact,
        counter: Long,
        ttlMinutes: Int = DEFAULT_TTL_MINUTES
    ): Message {
        require(ttlMinutes in 1..MAX_TTL_MINUTES) {
            "TTL hors bornes: $ttlMinutes min (autorisé: 1..$MAX_TTL_MINUTES)"
        }
        return try {
            val encrypted = cryptoManager.encryptFor(
                plaintext = plainBytes,
                theirPublicKeyHex = recipient.publicKeyHex,
                ourPublicKeyHex = sender.publicKeyHex,
                ourSigningPrivateKeyHex = sender.signingPrivateKeyHex,
                counter = counter
            )

            Message(
                id = generateMessageId(),
                content = encrypted.ciphertext,
                fromPublicKeyHex = sender.publicKeyHex,
                toPublicKeyHex = recipient.publicKeyHex,
                timestamp = encrypted.timestamp,
                expiresAt = encrypted.timestamp + (ttlMinutes.toLong() * 60L * 1000L),
                status = MessageStatus.PENDING,
                nonce = encrypted.nonce,
                tag = encrypted.tag,
                ephemeralPublicKey = encrypted.ephemeralPublicKey,
                signature = encrypted.signature,
                counter = encrypted.counter
            )
        } catch (e: Exception) {
            throw MessageEncryptionException("Erreur lors du chiffrement: ${e.message}", e)
        }
    }

    /**
     * À l'ARRIVÉE d'un message : vérifie son origine et qu'il n'a jamais été
     * vu, sans le déchiffrer. C'est le seul endroit où le compteur anti-rejeu
     * est contrôlé — l'appelant le fait avancer juste après.
     *
     * @throws MessageAuthenticationException usurpation, ou rejeu.
     */
    fun verifyIncoming(message: Message, recipient: Identity, sender: Contact) {
        try {
            cryptoManager.verifyOrigin(
                encrypted = encryptedOf(message),
                ourPublicKeyHex = recipient.publicKeyHex,
                theirPublicKeyHex = sender.publicKeyHex,
                theirSigningPublicKeyHex = sender.signingPublicKeyHex,
                lastSeenCounter = sender.lastReceivedCounter
            )
        } catch (e: SignatureVerificationException) {
            throw MessageAuthenticationException(e.message ?: "Authentification échouée", e)
        }
    }

    /**
     * À l'AFFICHAGE : vérifie la signature puis déchiffre un message déjà
     * accepté par verifyIncoming().
     *
     * Pas de contrôle de rejeu ici. Le compteur a avancé à l'arrivée ; le
     * refaire à chaque affichage transformait tout message déjà lu en
     * « origine non vérifiée » à la réouverture du chat.
     *
     * @throws MessageAuthenticationException si le message ne vient pas de ce contact.
     */
    fun decryptMessage(
        message: Message,
        recipient: Identity,
        sender: Contact
    ): String {
        val decrypted = decryptBytes(message, recipient, sender)
        return try {
            String(decrypted, Charsets.UTF_8)
        } finally {
            cryptoManager.zeroize(decrypted)
        }
    }

    /** Octets bruts (morceau de fichier). L'appelant efface le résultat. */
    fun decryptBytes(
        message: Message,
        recipient: Identity,
        sender: Contact
    ): ByteArray {
        return try {
            cryptoManager.decryptFrom(
                encrypted = encryptedOf(message),
                ourPrivateKeyHex = recipient.encryptionPrivateKeyHex,
                ourPublicKeyHex = recipient.publicKeyHex,
                theirPublicKeyHex = sender.publicKeyHex,
                theirSigningPublicKeyHex = sender.signingPublicKeyHex
            )
        } catch (e: SignatureVerificationException) {
            // Remonte tel quel : « ce message ne vient pas de cette personne »
            // n'est pas la même information que « déchiffrement impossible ».
            throw MessageAuthenticationException(e.message ?: "Authentification échouée", e)
        } catch (e: MessageAuthenticationException) {
            throw e
        } catch (e: MessageDecryptionException) {
            throw e
        } catch (e: Exception) {
            throw MessageDecryptionException("Erreur lors du déchiffrement: ${e.message}", e)
        }
    }

    /** Champs obligatoires présents, contact vérifiable : sinon, exception explicite. */
    private fun encryptedOf(message: Message): EncryptedData = EncryptedData(
        ciphertext = message.content,
        nonce = message.nonce
            ?: throw MessageDecryptionException("Nonce manquant sur le message ${message.id}"),
        tag = message.tag
            ?: throw MessageDecryptionException("Tag manquant sur le message ${message.id}"),
        ephemeralPublicKey = message.ephemeralPublicKey
            ?: throw MessageDecryptionException("Clé éphémère manquante sur le message ${message.id}"),
        signature = message.signature
            ?: throw MessageAuthenticationException("Message ${message.id} non signé — origine invérifiable"),
        counter = message.counter,
        timestamp = message.timestamp
    )

    /**
     * Empreinte de paire à comparer de vive voix avec le contact.
     *
     * Les deux interlocuteurs obtiennent le MÊME nombre. S'il diffère,
     * quelqu'un s'est interposé lors de l'échange des clés.
     */
    fun safetyNumber(identity: Identity, contact: Contact): String =
        cryptoManager.pairSafetyNumber(
            encryptionKeyA = identity.publicKeyHex,
            signingKeyA = identity.signingPublicKeyHex,
            encryptionKeyB = contact.publicKeyHex,
            signingKeyB = contact.signingPublicKeyHex
        )

    /** Contenu du QR de vérification en personne (1.3.7). */
    fun safetyCode(identity: Identity, contact: Contact): String =
        cryptoManager.pairSafetyCode(
            encryptionKeyA = identity.publicKeyHex,
            signingKeyA = identity.signingPublicKeyHex,
            encryptionKeyB = contact.publicKeyHex,
            signingKeyB = contact.signingPublicKeyHex
        )

    fun isMessageExpired(message: Message): Boolean =
        System.currentTimeMillis() >= message.expiresAt

    fun getTTLPresets(): List<TTLPreset> = TTL_PRESETS

    private fun generateMessageId(): String = java.util.UUID.randomUUID().toString()

    companion object {
        /**
         * Durée de vie par défaut : 8 heures. Le plafond est « jamais »
         * (MAX_TTL_MINUTES, cent ans), au choix de l'utilisateur. Le public
         * visé partage des informations sensibles — au-delà de 8 h, un
         * message n'allonge que la fenêtre d'exposition sur l'appareil.
         */
        const val DEFAULT_TTL_MINUTES = 8 * 60
        /**
         * « Jamais » (1.3.1, pour le grand public) : cent ans. Le message reste
         * chiffré sur le téléphone, on peut toujours le supprimer à la main,
         * et la durée par défaut reste 8 h — c'est un choix, jamais imposé.
         */
        const val NEVER_TTL_MINUTES = 100 * 365 * 24 * 60
        const val MAX_TTL_MINUTES = NEVER_TTL_MINUTES

        /** Au-delà de dix ans, on parle de « jamais ». */
        fun isNever(timestamp: Long, expiresAt: Long): Boolean =
            expiresAt - timestamp > 10L * 365 * 24 * 60 * 60 * 1000

        private val TTL_PRESETS = listOf(
            TTLPreset(R.string.ttl_5m, 5),
            TTLPreset(R.string.ttl_15m, 15),
            TTLPreset(R.string.ttl_1h, 60),
            TTLPreset(R.string.ttl_5h, 5 * 60),
            TTLPreset(R.string.ttl_8h, 8 * 60),
            TTLPreset(R.string.ttl_24h, 24 * 60),
            TTLPreset(R.string.ttl_7d, 7 * 24 * 60),
            TTLPreset(R.string.ttl_never, NEVER_TTL_MINUTES)
        )
    }
}

/**
 * TTLPreset - Preset de durée de vie.
 * Porte un identifiant de ressource, pas une chaîne : le libellé se résout
 * dans la langue de l'appareil au moment de l'affichage.
 */
data class TTLPreset(
    @androidx.annotation.StringRes val labelRes: Int,
    val minutes: Int
)

class MessageEncryptionException(message: String, cause: Throwable? = null)
    : Exception(message, cause)

class MessageDecryptionException(message: String, cause: Throwable? = null)
    : Exception(message, cause)

/**
 * Le message est peut-être déchiffrable, mais son origine n'est pas celle
 * annoncée — ou il a déjà été reçu. À distinguer d'un échec de déchiffrement :
 * l'un signale une tentative d'usurpation, l'autre une donnée corrompue.
 */
class MessageAuthenticationException(message: String, cause: Throwable? = null)
    : Exception(message, cause)
