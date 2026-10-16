package com.whisper.tor

import org.bouncycastle.crypto.digests.SHA3Digest
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import java.security.MessageDigest

/**
 * L'adresse onion v3 : le « sceau intemporel » du QR.
 *
 * Une adresse v3, c'est une clé publique Ed25519 écrite en base32, plus une
 * somme de contrôle et un octet de version (rend-spec-v3 §6) :
 *
 *   base32( clé publique[32] ‖ somme[2] ‖ 0x03 ) = 56 caractères
 *   somme = SHA3-256(".onion checksum" ‖ clé publique ‖ 0x03)[0..2]
 *
 * La clé est une graine de 32 octets DÉDIÉE, distincte de la clé de signature
 * de l'identité : une clé, un usage. Elle est scellée avec l'identité, sous le
 * PIN, et ne touche jamais le disque en clair — Tor la reçoit par le port de
 * contrôle, en mémoire.
 *
 * Tout est calculé ici, sans Tor : l'adresse s'affiche dans le QR avant même
 * que Tor ait démarré. Et au démarrage, TorRuntime vérifie que Tor publie
 * bien CETTE adresse — la preuve que la conversion de clé ci-dessous est juste.
 */
object OnionAddress {

    const val LENGTH = 56
    private const val VERSION: Byte = 3
    private val CHECKSUM_PREFIX = ".onion checksum".toByteArray(Charsets.US_ASCII)
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"

    /** Adresse sans le suffixe « .onion », en minuscules. */
    fun fromSeed(seed: ByteArray): String =
        fromPublicKey(Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded)

    fun fromPublicKey(publicKey: ByteArray): String {
        require(publicKey.size == 32)
        return base32(publicKey + checksum(publicKey) + VERSION)
    }

    /** Longueur, alphabet, version et somme de contrôle : une faute de frappe ne passe pas. */
    fun isValid(address: String): Boolean {
        if (address.length != LENGTH) return false
        val raw = unbase32(address.lowercase()) ?: return false
        if (raw[34] != VERSION) return false
        val publicKey = raw.copyOfRange(0, 32)
        return MessageDigest.isEqual(raw.copyOfRange(32, 34), checksum(publicKey))
    }

    /**
     * Le format que Tor attend (« ED25519-V3 » d'ADD_ONION) : la clé Ed25519
     * DÉVELOPPÉE — SHA-512 de la graine, les 32 premiers octets bornés selon
     * RFC 8032 (le scalaire), suivis des 32 autres (le préfixe). C'est
     * exactement ce qu'Ed25519 calcule en interne ; Tor ne sait pas partir
     * de la graine.
     */
    fun expandedSecret(seed: ByteArray): ByteArray {
        require(seed.size == 32)
        return MessageDigest.getInstance("SHA-512").digest(seed).also {
            it[0] = (it[0].toInt() and 248).toByte()
            it[31] = (it[31].toInt() and 127 or 64).toByte()
        }
    }

    private fun checksum(publicKey: ByteArray): ByteArray {
        val digest = SHA3Digest(256)
        val input = CHECKSUM_PREFIX + publicKey + VERSION
        digest.update(input, 0, input.size)
        return ByteArray(32).also { digest.doFinal(it, 0) }.copyOf(2)
    }

    /** 35 octets → 56 caractères pile, jamais de remplissage. */
    private fun base32(data: ByteArray): String {
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                out.append(ALPHABET[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) out.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return out.toString()
    }

    private fun unbase32(text: String): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in text) {
            val v = ALPHABET.indexOf(c)
            if (v < 0) return null
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                out.write((buffer shr (bits - 8)) and 0xff)
                bits -= 8
            }
        }
        return out.toByteArray().takeIf { it.size == 35 }
    }
}
