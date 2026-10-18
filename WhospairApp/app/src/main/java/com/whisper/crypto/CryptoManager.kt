package com.whisper.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.util.encoders.Hex

/**
 * Résultat d'un chiffrement ECIES authentifié.
 *
 * Trois éléments doivent voyager avec le chiffré :
 *  - ephemeralPublicKey : sans elle, le destinataire ne peut pas recalculer
 *    le secret ECDH.
 *  - signature : preuve que l'expéditeur est bien qui il prétend être.
 *    Sans elle, n'importe qui connaissant la clé publique du destinataire
 *    peut lui écrire en usurpant une identité.
 *  - counter : position dans la suite des messages, qui interdit le rejeu.
 */
data class EncryptedData(
    val ciphertext: ByteArray,
    val nonce: ByteArray,               // IV GCM, 12 bytes
    val tag: ByteArray,                 // Tag d'authentification GCM, 16 bytes
    val ephemeralPublicKey: ByteArray,  // Clé publique X25519 éphémère, 32 bytes
    val signature: ByteArray,           // Ed25519 sur le contexte canonique, 64 bytes
    val counter: Long,
    val timestamp: Long
) {
    override fun equals(other: Any?) = when {
        this === other -> true
        other !is EncryptedData -> false
        else -> ciphertext.contentEquals(other.ciphertext) &&
                nonce.contentEquals(other.nonce) &&
                tag.contentEquals(other.tag) &&
                ephemeralPublicKey.contentEquals(other.ephemeralPublicKey) &&
                signature.contentEquals(other.signature) &&
                counter == other.counter &&
                timestamp == other.timestamp
    }

    override fun hashCode(): Int {
        var result = arrayOf(ciphertext, nonce, tag, ephemeralPublicKey, signature)
            .contentDeepHashCode()
        result = 31 * result + counter.hashCode()
        result = 31 * result + timestamp.hashCode()
        return result
    }

    companion object {
        const val EPHEMERAL_KEY_SIZE = 32
        const val NONCE_SIZE = 12
        const val TAG_SIZE = 16
        const val SIGNATURE_SIZE = 64
    }
}

/**
 * Identité cryptographique complète.
 *
 * Deux paires distinctes, et c'est indispensable : X25519 sert à l'échange
 * de clés (ECDH), Ed25519 à la signature. Une même clé ne peut pas faire les
 * deux — ce sont deux courbes et deux usages.
 */
data class WhisperKeyPair(
    val encryptionPrivateKeyHex: String,  // X25519, 64 hex
    val encryptionPublicKeyHex: String,   // X25519, 64 hex
    val signingPrivateKeyHex: String,     // Ed25519, 64 hex
    val signingPublicKeyHex: String       // Ed25519, 64 hex
)

/**
 * Levée quand une signature ne correspond pas.
 * Distincte de SecurityException pour que l'appelant puisse dire à
 * l'utilisateur « ce message ne vient pas de qui il prétend » plutôt que
 * « déchiffrement impossible ».
 */
class SignatureVerificationException(message: String) : SecurityException(message)

/**
 * CryptoManager - Primitives cryptographiques de Whisper.
 *
 * - X25519 (ECDH) pour l'échange de clés
 * - Ed25519 pour l'authentification de l'expéditeur
 * - HKDF-SHA256 pour la dérivation
 * - AES-256-GCM avec données authentifiées pour le chiffrement
 *
 * Note sur le stockage : Android Keystore ne gère ni X25519 ni Ed25519 avant
 * l'API 33. Les clés privées sont donc scellées sous le PIN (IdentityService,
 * sous-clé HKDF de la clé de base), puis rangées dans
 * EncryptedSharedPreferences. Compromis assumé sur minSdk 29, documenté
 * plutôt que masqué.
 */
class CryptoManager {

    private val secureRandom = SecureRandom()

    init {
        if (java.security.Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            java.security.Security.addProvider(BouncyCastleProvider())
        }
    }

    // ==================== IDENTITÉ ====================

    /**
     * Génère une identité complète : paire de chiffrement + paire de signature.
     * Les quatre clés font 32 octets bruts, soit 64 caractères hex chacune.
     */
    fun generateIdentity(): WhisperKeyPair {
        val x = X25519KeyPairGenerator().apply {
            init(X25519KeyGenerationParameters(secureRandom))
        }.generateKeyPair()

        val ed = Ed25519KeyPairGenerator().apply {
            init(Ed25519KeyGenerationParameters(secureRandom))
        }.generateKeyPair()

        return WhisperKeyPair(
            encryptionPrivateKeyHex = Hex.toHexString((x.private as X25519PrivateKeyParameters).encoded),
            encryptionPublicKeyHex = Hex.toHexString((x.public as X25519PublicKeyParameters).encoded),
            signingPrivateKeyHex = Hex.toHexString((ed.private as Ed25519PrivateKeyParameters).encoded),
            signingPublicKeyHex = Hex.toHexString((ed.public as Ed25519PublicKeyParameters).encoded)
        )
    }

    /** Recalcule la clé publique X25519 à partir de la privée. */
    fun derivePublicKey(privateKeyHex: String): String =
        Hex.toHexString(
            X25519PrivateKeyParameters(Hex.decode(privateKeyHex), 0).generatePublicKey().encoded
        )

    /** Recalcule la clé publique Ed25519 à partir de la privée. */
    fun deriveSigningPublicKey(privateKeyHex: String): String =
        Hex.toHexString(
            Ed25519PrivateKeyParameters(Hex.decode(privateKeyHex), 0).generatePublicKey().encoded
        )

    // ==================== SIGNATURE ====================

    fun sign(data: ByteArray, signingPrivateKeyHex: String): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(Hex.decode(signingPrivateKeyHex), 0))
        signer.update(data, 0, data.size)
        return signer.generateSignature()
    }

    fun verify(data: ByteArray, signature: ByteArray, signingPublicKeyHex: String): Boolean {
        return try {
            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(Hex.decode(signingPublicKeyHex), 0))
            verifier.update(data, 0, data.size)
            verifier.verifySignature(signature)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Contexte canonique d'un message.
     *
     * Sert DEUX fois : il est signé, et il sert de données authentifiées (AAD)
     * du chiffrement GCM. Chaque champ ferme une attaque précise —
     *  - expéditeur et destinataire : un message ne peut pas être rejoué vers
     *    un autre destinataire, ni réfléchi vers son auteur,
     *  - clé éphémère : elle ne peut pas être substituée en route,
     *  - compteur : le même message ne passe pas deux fois,
     *  - horodatage : signé, donc infalsifiable, mais jamais comparé à une
     *    fenêtre de temps. La fraîcheur vient du compteur, et la durée de
     *    vie annoncée est recomptée sur l'horloge du destinataire
     *    (MessageTransport).
     *
     * Le format suit la discipline du prototype : une chaîne, des champs
     * séparés par « | », un préfixe de version en tête.
     */
    private fun bindingBytes(
        senderPublicKeyHex: String,
        recipientPublicKeyHex: String,
        ephemeralPublicKey: ByteArray,
        counter: Long,
        timestamp: Long
    ): ByteArray = listOf(
        PROTOCOL_VERSION,
        "msg",
        senderPublicKeyHex.lowercase(),
        recipientPublicKeyHex.lowercase(),
        Hex.toHexString(ephemeralPublicKey),
        counter.toString(),
        timestamp.toString()
    ).joinToString("|").toByteArray(Charsets.UTF_8)

    // ==================== CHIFFREMENT AUTHENTIFIÉ ====================

    /**
     * Chiffre et signe pour un destinataire.
     *
     * @param counter position du message dans la suite envoyée à ce contact.
     *        Doit être strictement croissant : c'est ce qui interdit le rejeu.
     */
    fun encryptFor(
        plaintext: ByteArray,
        theirPublicKeyHex: String,
        ourPublicKeyHex: String,
        ourSigningPrivateKeyHex: String,
        counter: Long
    ): EncryptedData {
        val theirPublic = X25519PublicKeyParameters(Hex.decode(theirPublicKeyHex), 0)

        val ephemeral = X25519KeyPairGenerator().apply {
            init(X25519KeyGenerationParameters(secureRandom))
        }.generateKeyPair()
        val ephemeralPrivate = ephemeral.private as X25519PrivateKeyParameters
        val ephemeralPublic = (ephemeral.public as X25519PublicKeyParameters).encoded

        val timestamp = System.currentTimeMillis()
        val binding = bindingBytes(
            ourPublicKeyHex, theirPublicKeyHex, ephemeralPublic, counter, timestamp
        )

        val sharedSecret = agree(ephemeralPrivate, theirPublic)
        val sessionKey = hkdfSha256(
            ikm = sharedSecret,
            salt = null,
            info = ephemeralPublic + theirPublic.encoded,
            length = 32
        )

        return try {
            val (ciphertext, nonce, tag) = aesGcmEncrypt(plaintext, sessionKey, binding)
            EncryptedData(
                ciphertext = ciphertext,
                nonce = nonce,
                tag = tag,
                ephemeralPublicKey = ephemeralPublic,
                signature = sign(binding, ourSigningPrivateKeyHex),
                counter = counter,
                timestamp = timestamp
            )
        } finally {
            sharedSecret.fill(0)
            sessionKey.fill(0)
        }
    }

    /**
     * Vérifie la signature puis déchiffre.
     *
     * La signature est contrôlée AVANT toute opération de déchiffrement :
     * inutile de dériver une clé pour un message dont on sait déjà qu'il ne
     * vient pas de la bonne personne.
     *
     * @param lastSeenCounter dernier compteur accepté de ce contact.
     *        Tout message de compteur inférieur ou égal est un rejeu.
     */
    fun decryptFrom(
        encrypted: EncryptedData,
        ourPrivateKeyHex: String,
        ourPublicKeyHex: String,
        theirPublicKeyHex: String,
        theirSigningPublicKeyHex: String,
        lastSeenCounter: Long = -1
    ): ByteArray {
        val binding = verifyOrigin(
            encrypted, ourPublicKeyHex, theirPublicKeyHex, theirSigningPublicKeyHex, lastSeenCounter
        )

        val ourPrivate = X25519PrivateKeyParameters(Hex.decode(ourPrivateKeyHex), 0)
        val ephemeralPublic = X25519PublicKeyParameters(encrypted.ephemeralPublicKey, 0)

        val sharedSecret = agree(ourPrivate, ephemeralPublic)
        val sessionKey = hkdfSha256(
            ikm = sharedSecret,
            salt = null,
            info = encrypted.ephemeralPublicKey + ourPrivate.generatePublicKey().encoded,
            length = 32
        )

        return try {
            aesGcmDecrypt(encrypted, sessionKey, binding)
        } finally {
            sharedSecret.fill(0)
            sessionKey.fill(0)
        }
    }

    /**
     * Vérifie l'origine et la fraîcheur SANS déchiffrer : compteur, puis
     * signature du contexte. Sert à l'arrivée d'un message, où l'on veut
     * savoir s'il est légitime sans produire de clair.
     *
     * Le chiffré lui-même n'est pas couvert par la signature, mais le contexte
     * signé est l'AAD de GCM : un chiffré altéré échouera au déchiffrement.
     *
     * @return le contexte signé, réutilisé comme AAD par decryptFrom.
     * @throws SignatureVerificationException rejeu ou signature invalide.
     */
    fun verifyOrigin(
        encrypted: EncryptedData,
        ourPublicKeyHex: String,
        theirPublicKeyHex: String,
        theirSigningPublicKeyHex: String,
        lastSeenCounter: Long = -1
    ): ByteArray {
        if (encrypted.counter <= lastSeenCounter) {
            throw SignatureVerificationException(
                "Rejeu détecté: compteur ${encrypted.counter} <= dernier vu $lastSeenCounter"
            )
        }
        val binding = bindingBytes(
            theirPublicKeyHex, ourPublicKeyHex,
            encrypted.ephemeralPublicKey, encrypted.counter, encrypted.timestamp
        )
        if (!verify(binding, encrypted.signature, theirSigningPublicKeyHex)) {
            throw SignatureVerificationException(
                "Signature invalide : ce message ne vient pas de ce contact"
            )
        }
        return binding
    }

    /**
     * Preuve de possession de la clé X25519 dans le HELLO (v3, 1.3.5).
     *
     * La signature Ed25519 du HELLO prouve la clé de signature, pas la clé
     * X25519 annoncée — publique, elle figure dans tout QR. Sans cette
     * preuve, quiconque avait notre lien pouvait se présenter avec la clé
     * X25519 d'un autre et occuper sa place comme contact non confirmé.
     *
     * HMAC-SHA256 du lien HELLO, sous une clé tirée (HKDF) du secret X25519
     * entre appelant et appelé : seul le détenteur de la clé privée annoncée
     * peut la calculer, et l'appelé la recalcule avec la sienne.
     */
    fun helloKeyProof(ourPrivateKeyHex: String, theirPublicKeyHex: String, binding: ByteArray): ByteArray {
        val shared = agree(
            X25519PrivateKeyParameters(Hex.decode(ourPrivateKeyHex), 0),
            X25519PublicKeyParameters(Hex.decode(theirPublicKeyHex), 0)
        )
        val key = hkdfSha256(shared, null, HELLO_PROOF_INFO, 32)
        zeroize(shared)
        return try {
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(key, "HmacSHA256"))
                doFinal(binding)
            }
        } finally {
            zeroize(key)
        }
    }

    /** Comparaison en temps constant : ne pas révéler combien d'octets étaient justes. */
    fun verifyHelloKeyProof(ourPrivateKeyHex: String, theirPublicKeyHex: String, binding: ByteArray, proof: ByteArray): Boolean =
        runCatching {
            java.security.MessageDigest.isEqual(helloKeyProof(ourPrivateKeyHex, theirPublicKeyHex, binding), proof)
        }.getOrDefault(false)

    /**
     * ECDH X25519.
     */
    private fun agree(
        ourPrivate: X25519PrivateKeyParameters,
        theirPublic: X25519PublicKeyParameters
    ): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(ourPrivate)
        val secret = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(theirPublic, secret, 0)
        return secret
    }

    // ==================== AES-256-GCM ====================

    /**
     * Chiffre avec AES-256-GCM en liant le contexte via l'AAD.
     *
     * L'AAD n'est pas chiffrée mais elle est authentifiée : modifier un seul
     * de ses champs fait échouer le déchiffrement. C'est ce qui manquait —
     * sans elle, un chiffré valide restait valide quel que soit le contexte
     * dans lequel on le rejouait.
     */
    private fun aesGcmEncrypt(
        plaintext: ByteArray,
        sessionKey: ByteArray,
        aad: ByteArray
    ): Triple<ByteArray, ByteArray, ByteArray> {
        require(sessionKey.size == 32) { "Session key must be 32 bytes (got ${sessionKey.size})" }

        val nonce = ByteArray(EncryptedData.NONCE_SIZE).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance(AES_GCM).apply {
            init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(sessionKey, "AES"),
                GCMParameterSpec(128, nonce)
            )
            updateAAD(aad)
        }

        val combined = cipher.doFinal(plaintext)
        val cutoff = combined.size - EncryptedData.TAG_SIZE
        return Triple(
            combined.copyOfRange(0, cutoff),
            nonce,
            combined.copyOfRange(cutoff, combined.size)
        )
    }

    private fun aesGcmDecrypt(
        encrypted: EncryptedData,
        sessionKey: ByteArray,
        aad: ByteArray
    ): ByteArray {
        require(sessionKey.size == 32) { "Session key must be 32 bytes" }
        require(encrypted.nonce.size == EncryptedData.NONCE_SIZE) { "Nonce must be 12 bytes" }
        require(encrypted.tag.size == EncryptedData.TAG_SIZE) { "Tag must be 16 bytes" }

        val cipher = Cipher.getInstance(AES_GCM).apply {
            init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(sessionKey, "AES"),
                GCMParameterSpec(128, encrypted.nonce)
            )
            updateAAD(aad)
        }

        return try {
            cipher.doFinal(encrypted.ciphertext + encrypted.tag)
        } catch (e: Exception) {
            throw SecurityException("Decryption failed or tag verification failed", e)
        }
    }

    // ==================== SAFETY NUMBER ====================

    /**
     * Empreinte de paire, à comparer de vive voix.
     *
     * Ordre-indépendante : Alice et Bob obtiennent exactement le MÊME nombre.
     * C'est toute la différence avec une empreinte d'une seule clé, que chacun
     * verrait différente et qui ne permet donc aucune comparaison.
     *
     * Les deux clés de chaque partie entrent dans le calcul : substituer l'une
     * ou l'autre change le résultat.
     *
     * Format : 8 groupes de 5 chiffres — des chiffres plutôt que de
     * l'hexadécimal, parce que ça se dicte au téléphone sans avoir à épeler.
     * 8 groupes consomment 32 octets, soit exactement la taille d'un SHA-256 ;
     * en demander davantage exigerait de hacher en plusieurs passes.
     */
    fun pairSafetyNumber(
        encryptionKeyA: String, signingKeyA: String,
        encryptionKeyB: String, signingKeyB: String
    ): String {
        val digest = pairDigest(encryptionKeyA, signingKeyA, encryptionKeyB, signingKeyB)

        return digest.take(SAFETY_NUMBER_GROUPS * 4)
            .chunked(4) { chunk ->
                var value = 0L
                chunk.forEach { value = (value shl 8) or (it.toLong() and 0xFF) }
                (value % 100_000).toString().padStart(5, '0')
            }
            .joinToString(" ")
    }

    /**
     * Le code de sécurité en QR (1.3.7) : la même empreinte que le numéro de
     * sécurité, en entier. Chacun scanne celui de l'autre, face à face : s'il
     * est identique au sien, aucune clé n'a été échangée en route.
     */
    fun pairSafetyCode(
        encryptionKeyA: String, signingKeyA: String,
        encryptionKeyB: String, signingKeyB: String
    ): String = SAFETY_QR_PREFIX + Hex.toHexString(pairDigest(encryptionKeyA, signingKeyA, encryptionKeyB, signingKeyB))

    /** SHA-256 des deux identités, triées : identique quel que soit l'appelant. */
    private fun pairDigest(
        encryptionKeyA: String, signingKeyA: String,
        encryptionKeyB: String, signingKeyB: String
    ): ByteArray {
        val partyA = encryptionKeyA.lowercase() + signingKeyA.lowercase()
        val partyB = encryptionKeyB.lowercase() + signingKeyB.lowercase()
        val ordered = listOf(partyA, partyB).sorted()
        val merged = Hex.decode(ordered[0]) + Hex.decode(ordered[1])
        val digest = ByteArray(32)
        SHA256Digest().apply {
            update(merged, 0, merged.size)
            doFinal(digest, 0)
        }
        return digest
    }

    // ==================== HKDF ====================

    private fun hkdfSha256(
        ikm: ByteArray,
        salt: ByteArray?,
        info: ByteArray,
        length: Int
    ): ByteArray {
        val hashLen = 32
        require(length <= 255 * hashLen) { "Longueur HKDF demandée trop grande" }

        val mac = Mac.getInstance("HmacSHA256")

        mac.init(SecretKeySpec(salt ?: ByteArray(hashLen), "HmacSHA256"))
        val prk = mac.doFinal(ikm)

        val output = ByteArray(length)
        var previous = ByteArray(0)
        var generated = 0
        var counter = 1

        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        while (generated < length) {
            mac.reset()
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()

            val toCopy = minOf(previous.size, length - generated)
            previous.copyInto(output, generated, 0, toCopy)
            generated += toCopy
            counter++
        }

        prk.fill(0)
        previous.fill(0)
        return output
    }

    /** Efface un tableau sensible en mémoire. */
    fun zeroize(data: ByteArray?) {
        data?.fill(0)
    }

    companion object {
        private const val AES_GCM = "AES/GCM/NoPadding"
        const val PROTOCOL_VERSION = "whisper-v1"
        /** 8 groupes × 4 octets = 32, la taille exacte d'un SHA-256. */
        private const val SAFETY_NUMBER_GROUPS = 8
        private val HELLO_PROOF_INFO = "whisper-v3|hello-x25519".toByteArray(Charsets.US_ASCII)
        /** Contenu du QR de vérification : préfixe + empreinte hex (64). */
        const val SAFETY_QR_PREFIX = "whospair-verify:"
    }
}
