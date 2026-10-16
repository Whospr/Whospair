package com.whisper.reach

import com.whisper.security.PinCrypto
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.SecureRandom

/**
 * Le sceau de la boîte de réception d'arrière-plan.
 *
 * Asymétrique, exprès : le processus d'arrière-plan ne détient que la clé
 * PUBLIQUE. Il peut ranger ce qui arrive, jamais le relire. La clé privée
 * est tirée de la clé du coffre : seul le PIN rouvre la boîte. Un téléphone
 * saisi app fermée, mémoire comprise, ne livre donc aucun message rangé —
 * ni son contenu, ni qui l'a envoyé, ni quand.
 *
 *   [clé éphémère X25519, 32][PinCrypto.wrap(contenu, HKDF(ECDH ‖ éphémère ‖ destinataire))]
 */
object InboxSeal {

    private const val KEY_SIZE = 32
    private val random = SecureRandom()

    /** Clé privée de la boîte, tirée d'une sous-clé du coffre. */
    fun privateKey(vaultSubKey: ByteArray): ByteArray = PinCrypto.subKey(vaultSubKey, "inbox")

    fun publicKey(privateKey: ByteArray): ByteArray =
        X25519PrivateKeyParameters(privateKey, 0).generatePublicKey().encoded

    fun seal(plain: ByteArray, recipient: ByteArray): ByteArray {
        val ephemeral = X25519PrivateKeyParameters(random)
        val epk = ephemeral.generatePublicKey().encoded
        val key = derive(ephemeral, X25519PublicKeyParameters(recipient, 0), epk, recipient)
        return try {
            epk + PinCrypto.wrap(plain, key)
        } finally {
            key.fill(0)
        }
    }

    /** null : sceau abîmé, ou fait pour une autre clé (identité remplacée). */
    fun open(blob: ByteArray, privateKey: ByteArray): ByteArray? {
        if (blob.size <= KEY_SIZE) return null
        val epk = blob.copyOfRange(0, KEY_SIZE)
        val mine = X25519PrivateKeyParameters(privateKey, 0)
        val key = runCatching {
            derive(mine, X25519PublicKeyParameters(epk, 0), epk, mine.generatePublicKey().encoded)
        }.getOrNull() ?: return null
        return try {
            PinCrypto.unwrap(blob.copyOfRange(KEY_SIZE, blob.size), key)
        } finally {
            key.fill(0)
        }
    }

    private fun derive(
        ours: X25519PrivateKeyParameters,
        theirs: X25519PublicKeyParameters,
        epk: ByteArray,
        recipient: ByteArray
    ): ByteArray {
        val shared = ByteArray(KEY_SIZE)
        X25519Agreement().apply { init(ours) }.calculateAgreement(theirs, shared, 0)
        // Clé publique « tout à zéro » ou d'ordre faible : secret nul, refusé.
        require(shared.any { it != 0.toByte() }) { "Clé de boîte invalide" }
        val material = shared + epk + recipient
        shared.fill(0)
        return PinCrypto.subKey(material, "inbox-seal").also { material.fill(0) }
    }
}
