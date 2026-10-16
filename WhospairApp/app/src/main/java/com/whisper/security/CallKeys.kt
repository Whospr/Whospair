package com.whisper.security

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.SecureRandom

/**
 * Clés de média d'un appel. En attente : la voix sur Tor (phase 11d).
 *
 * Chaque côté tire une clé X25519 éphémère par appel et l'envoie, signée par
 * son identité, dans la poignée de main de l'appel. L'accord des deux
 * éphémères donne un secret que personne d'autre ne voit ; HKDF en tire deux
 * clés, une par sens. Les éphémères sont détruites aussitôt : un vol ultérieur
 * de l'identité ne déchiffre aucun appel passé.
 */
object CallKeys {

    private const val PROTOCOL = "whisper-v1"
    const val KEY_SIZE = 32

    private val random = SecureRandom()

    class Ephemeral(val publicKey: ByteArray, internal val privateKey: ByteArray) {
        fun destroy() = privateKey.fill(0)
    }

    fun newEphemeral(): Ephemeral {
        val private = X25519PrivateKeyParameters(random)
        return Ephemeral(private.generatePublicKey().encoded, private.encoded)
    }

    /**
     * @return (clé d'envoi, clé de réception) pour CE côté.
     * @throws IllegalStateException si la clé du pair est un point dégénéré —
     *   BouncyCastle le refuse lui-même. L'appelant le traite en falsification.
     *
     * Les deux côtés calculent le même bloc de 64 octets ; l'initiateur envoie
     * avec la première moitié, le répondeur avec la seconde. Les deux clés
     * publiques éphémères entrent dans le calcul, dans un ordre fixé par le
     * rôle : une valeur substituée en route donne des clés différentes.
     */
    fun derive(
        mine: Ephemeral,
        peerPublicKey: ByteArray,
        sessionId: String,
        initiator: Boolean
    ): Pair<ByteArray, ByteArray> {
        require(peerPublicKey.size == 32) { "Clé éphémère invalide" }

        val shared = ByteArray(32)
        X25519Agreement().apply { init(X25519PrivateKeyParameters(mine.privateKey, 0)) }
            .calculateAgreement(X25519PublicKeyParameters(peerPublicKey, 0), shared, 0)

        val (initiatorKey, responderKey) =
            if (initiator) mine.publicKey to peerPublicKey else peerPublicKey to mine.publicKey
        val info = "$PROTOCOL|media|".toByteArray() + initiatorKey + responderKey

        val okm = ByteArray(2 * KEY_SIZE)
        HKDFBytesGenerator(SHA256Digest()).apply {
            init(HKDFParameters(shared, sessionId.toByteArray(), info))
        }.generateBytes(okm, 0, okm.size)
        shared.fill(0)

        val first = okm.copyOfRange(0, KEY_SIZE)
        val second = okm.copyOfRange(KEY_SIZE, 2 * KEY_SIZE)
        okm.fill(0)
        return if (initiator) first to second else second to first
    }
}
