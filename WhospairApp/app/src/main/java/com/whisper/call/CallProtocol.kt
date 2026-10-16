package com.whisper.call

import com.whisper.security.CallKeys
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Trames d'un appel, sur le flux Tor déjà ouvert avec le contact.
 *
 * Les messages y voyagent en JSON (premier octet « { ») ; une trame d'appel
 * commence par un octet de type ≥ 0xC1, qu'aucun JSON ne produit.
 *
 *   OFFRE    [C1][appel 16][éphémère X25519 32][signature Ed25519 64]
 *   ACCEPTE  [C2][appel 16][éphémère X25519 32][signature Ed25519 64]
 *   FIN      [C3][appel 16][motif 1]
 *   VOIX     [C4][appel 16][numéro u32][AES-GCM(paquets Opus) + étiquette 16]
 *
 * Signé : "whisper-v1|call|offer|" (ou accept) ‖ appel ‖ éphémère ‖ X25519
 * de l'émetteur ‖ X25519 du destinataire. Le flux est déjà authentifié par la
 * poignée de main ; la signature lie en plus l'éphémère à l'identité du QR, et
 * ne peut pas être resservie dans un autre appel ni à un autre contact.
 *
 * FIN n'est pas signée : seul le contact au bout du flux peut l'envoyer, et
 * il ne peut raccrocher que son propre appel.
 */
object CallProtocol {

    const val OFFER: Byte = 0xC1.toByte()
    const val ACCEPT: Byte = 0xC2.toByte()
    const val END: Byte = 0xC3.toByte()
    const val AUDIO: Byte = 0xC4.toByte()

    const val CALL_ID_SIZE = 16
    private const val KEY_SIZE = 32
    private const val SIG_SIZE = 64
    private const val TAG_BITS = 128
    const val SIGNAL_SIZE = 1 + CALL_ID_SIZE + KEY_SIZE + SIG_SIZE
    private const val AUDIO_HEADER = 1 + CALL_ID_SIZE + 4

    /** Motifs de FIN. */
    const val END_HANGUP: Byte = 0
    const val END_DECLINE: Byte = 1
    const val END_BUSY: Byte = 2
    const val END_NO_ANSWER: Byte = 3

    fun isCallFrame(payload: ByteArray): Boolean =
        payload.isNotEmpty() && payload[0] in OFFER..AUDIO

    fun signalBinding(type: Byte, callId: ByteArray, ephemeral: ByteArray, from: ByteArray, to: ByteArray): ByteArray {
        val label = if (type == OFFER) "offer" else "accept"
        return "whisper-v1|call|$label|".toByteArray(Charsets.US_ASCII) + callId + ephemeral + from + to
    }

    fun signal(type: Byte, callId: ByteArray, ephemeral: ByteArray, signature: ByteArray): ByteArray =
        byteArrayOf(type) + callId + ephemeral + signature

    class Signal(val type: Byte, val callId: ByteArray, val ephemeral: ByteArray, val signature: ByteArray)

    fun parseSignal(frame: ByteArray): Signal? {
        if (frame.size != SIGNAL_SIZE || (frame[0] != OFFER && frame[0] != ACCEPT)) return null
        return Signal(
            frame[0],
            frame.copyOfRange(1, 1 + CALL_ID_SIZE),
            frame.copyOfRange(1 + CALL_ID_SIZE, 1 + CALL_ID_SIZE + KEY_SIZE),
            frame.copyOfRange(1 + CALL_ID_SIZE + KEY_SIZE, frame.size)
        )
    }

    fun end(callId: ByteArray, reason: Byte): ByteArray = byteArrayOf(END) + callId + reason

    /** (appel, motif) ou null. */
    fun parseEnd(frame: ByteArray): Pair<ByteArray, Byte>? {
        if (frame.size != 2 + CALL_ID_SIZE || frame[0] != END) return null
        return frame.copyOfRange(1, 1 + CALL_ID_SIZE) to frame[frame.size - 1]
    }

    fun callIdOf(frame: ByteArray): ByteArray? =
        if (frame.size >= 1 + CALL_ID_SIZE) frame.copyOfRange(1, 1 + CALL_ID_SIZE) else null

    /* ====================================================================== *
     *  Paquets de voix regroupés
     * ====================================================================== */

    /** [nombre][u16 taille][paquet]… — plusieurs trames Opus dans une seule trame Tor. */
    fun group(packets: List<ByteArray>): ByteArray {
        val buffer = ByteBuffer.allocate(1 + packets.sumOf { 2 + it.size })
        buffer.put(packets.size.toByte())
        packets.forEach { buffer.putShort(it.size.toShort()); buffer.put(it) }
        return buffer.array()
    }

    fun ungroup(bytes: ByteArray): List<ByteArray>? = runCatching {
        val buffer = ByteBuffer.wrap(bytes)
        val count = buffer.get().toInt() and 0xFF
        if (count !in 1..MAX_GROUP) return null
        val packets = List(count) {
            val size = buffer.short.toInt() and 0xFFFF
            if (size == 0 || size > buffer.remaining()) return null
            ByteArray(size).also { buffer.get(it) }
        }
        if (buffer.hasRemaining()) null else packets
    }.getOrNull()

    private const val MAX_GROUP = 8

    /* ====================================================================== *
     *  Chiffrement de la voix, clé qui avance
     * ====================================================================== */

    /**
     * Un sens de l'appel. La clé avance toutes les [PACKETS_PER_EPOCH]
     * trames (~10 s) : chaque nouvelle clé est tirée de la précédente, qui est
     * aussitôt effacée. Voler la clé en cours ne déchiffre rien de ce qui
     * précède.
     *
     * Tor livre dans l'ordre et sans perte : le numéro d'une trame reçue doit
     * donc toujours monter. Un numéro qui ne monte pas est un rejeu.
     */
    class Direction(initialKey: ByteArray, private val callId: ByteArray) {
        private var key = initialKey.copyOf().also { initialKey.fill(0) }
        private var epoch = 0L
        private var lastSeq = -1L
        private var destroyed = false

        @Synchronized fun seal(seq: Long, clear: ByteArray): ByteArray {
            check(!destroyed) { "Appel terminé" }
            require(seq > lastSeq && seq <= MAX_SEQ) { "Numéro de trame réutilisé" }
            lastSeq = seq
            advanceTo(seq / PACKETS_PER_EPOCH)
            val header = ByteBuffer.allocate(AUDIO_HEADER).put(AUDIO).put(callId).putInt(seq.toInt()).array()
            return header + cipher(Cipher.ENCRYPT_MODE, seq, header).doFinal(clear)
        }

        /** null : trame d'un autre appel, rejouée, trop en avance, ou falsifiée. */
        @Synchronized fun open(frame: ByteArray): ByteArray? {
            if (destroyed) return null
            if (frame.size <= AUDIO_HEADER + TAG_BITS / 8 || frame[0] != AUDIO) return null
            if (!frame.copyOfRange(1, 1 + CALL_ID_SIZE).contentEquals(callId)) return null
            val seq = ByteBuffer.wrap(frame, 1 + CALL_ID_SIZE, 4).int.toLong() and 0xFFFFFFFFL
            if (seq <= lastSeq) return null
            val target = seq / PACKETS_PER_EPOCH
            if (target - epoch > MAX_EPOCH_JUMP) return null
            // Avancer la clé est sans retour : on essaie sur une copie, et on
            // ne garde que si l'étiquette est bonne. Sinon une trame forgée
            // avec un numéro lointain ferait perdre la clé au vrai flux.
            val trialKey = ratchetFrom(key, target - epoch)
            val clear = runCatching {
                val header = frame.copyOfRange(0, AUDIO_HEADER)
                cipherWith(trialKey, Cipher.DECRYPT_MODE, seq, header).doFinal(frame, AUDIO_HEADER, frame.size - AUDIO_HEADER)
            }.getOrNull()
            if (clear == null) {
                if (trialKey !== key) trialKey.fill(0)
                return null
            }
            if (trialKey !== key) { key.fill(0); key = trialKey; epoch = target }
            lastSeq = seq
            return clear
        }

        @Synchronized fun destroy() {
            destroyed = true
            key.fill(0)
        }

        private fun advanceTo(target: Long) {
            if (target == epoch) return
            val next = ratchetFrom(key, target - epoch)
            key.fill(0)
            key = next
            epoch = target
        }

        private fun cipher(mode: Int, seq: Long, aad: ByteArray) = cipherWith(key, mode, seq, aad)

        /** Nonce : le numéro de trame, unique par clé et par sens. */
        private fun cipherWith(k: ByteArray, mode: Int, seq: Long, aad: ByteArray): Cipher {
            val nonce = ByteBuffer.allocate(12).putInt(0).putLong(seq).array()
            return Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(mode, SecretKeySpec(k, "AES"), GCMParameterSpec(TAG_BITS, nonce))
                updateAAD(aad)
            }
        }
    }

    /** Rend la clé elle-même si steps == 0, sinon une nouvelle clé (l'ancienne est intacte). */
    internal fun ratchetFrom(start: ByteArray, steps: Long): ByteArray {
        var current = start
        repeat(steps.toInt()) {
            val next = ByteArray(KEY_SIZE)
            HKDFBytesGenerator(SHA256Digest()).apply {
                init(HKDFParameters(current, null, "whisper-v1|media-ratchet".toByteArray()))
            }.generateBytes(next, 0, KEY_SIZE)
            if (current !== start) current.fill(0)
            current = next
        }
        return current
    }

    /** Des trames de 60 ms : 167 trames ≈ 10 s. */
    const val PACKETS_PER_EPOCH = 167L
    /** Au-delà d'une minute de silence réseau, l'appel est de toute façon mort. */
    private const val MAX_EPOCH_JUMP = 6L
    private const val MAX_SEQ = 0xFFFFFFFFL

    /** Les deux sens d'un appel, tirés des éphémères. Les éphémères sont détruites. */
    fun directions(
        mine: CallKeys.Ephemeral,
        peerEphemeral: ByteArray,
        callId: ByteArray,
        initiator: Boolean
    ): Pair<Direction, Direction> {
        val (send, receive) = try {
            CallKeys.derive(mine, peerEphemeral, callId.toHex(), initiator)
        } finally {
            mine.destroy()
        }
        return Direction(send, callId) to Direction(receive, callId)
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
