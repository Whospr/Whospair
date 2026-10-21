package com.whisper.call

import com.whisper.security.CallKeys
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallProtocolTest {

    private val callId = ByteArray(CallProtocol.CALL_ID_SIZE) { it.toByte() }

    /** (envoi d'Alice, réception de Bob) : un sens de l'appel. */
    private fun pair(): Pair<CallProtocol.Direction, CallProtocol.Direction> {
        val alice = CallKeys.newEphemeral()
        val bob = CallKeys.newEphemeral()
        val a = CallProtocol.directions(alice, bob.publicKey, callId, initiator = true)
        val b = CallProtocol.directions(bob, alice.publicKey, callId, initiator = false)
        return a.first to b.second
    }

    @Test fun `la voix passe d'un cote a l'autre`() {
        val (send, receive) = pair()
        val voice = byteArrayOf(1, 2, 3, 4)
        assertArrayEquals(voice, receive.open(send.seal(0, voice)))
    }

    @Test fun `une trame rejouee est refusee`() {
        val (send, receive) = pair()
        val frame = send.seal(0, byteArrayOf(9))
        assertNotNull(receive.open(frame))
        assertNull(receive.open(frame))
    }

    @Test fun `une trame modifiee est refusee`() {
        val (send, receive) = pair()
        val frame = send.seal(0, byteArrayOf(9, 9, 9))
        frame[frame.size - 1] = (frame[frame.size - 1] + 1).toByte()
        assertNull(receive.open(frame))
    }

    @Test fun `la cle avance et les deux cotes suivent`() {
        val (send, receive) = pair()
        val far = CallProtocol.PACKETS_PER_EPOCH * 3 + 5
        assertNotNull(receive.open(send.seal(1, byteArrayOf(1))))
        assertArrayEquals(byteArrayOf(7), receive.open(send.seal(far, byteArrayOf(7))))
        assertArrayEquals(byteArrayOf(8), receive.open(send.seal(far + 1, byteArrayOf(8))))
    }

    @Test fun `une trame forgee lointaine ne fait pas perdre la cle`() {
        val (send, receive) = pair()
        val forged = send.seal(0, byteArrayOf(1)).copyOf()
        // Numéro lointain, étiquette fausse.
        forged[1 + CallProtocol.CALL_ID_SIZE + 3] = 100
        assertNull(receive.open(forged))
        assertNotNull(receive.open(send.seal(1, byteArrayOf(2))))
    }

    @Test fun `apres destruction plus rien ne passe`() {
        val (send, receive) = pair()
        val frame = send.seal(0, byteArrayOf(1))
        receive.destroy()
        assertNull(receive.open(frame))
    }

    @Test fun `regroupement aller retour`() {
        val packets = listOf(byteArrayOf(1), byteArrayOf(2, 3), byteArrayOf(4, 5, 6))
        val back = CallProtocol.ungroup(CallProtocol.group(packets))!!
        assertEquals(3, back.size)
        packets.zip(back).forEach { (a, b) -> assertArrayEquals(a, b) }
        assertNull(CallProtocol.ungroup(byteArrayOf(2, 0, 5, 1)))
    }

    @Test fun `trames d'appel reconnues, JSON non`() {
        assertTrue(CallProtocol.isCallFrame(CallProtocol.end(callId, CallProtocol.END_HANGUP)))
        assertFalse(CallProtocol.isCallFrame("{\"v\":1}".toByteArray()))
        val (id, reason) = CallProtocol.parseEnd(CallProtocol.end(callId, CallProtocol.END_BUSY))!!
        assertArrayEquals(callId, id)
        assertEquals(CallProtocol.END_BUSY, reason)
    }

    /* ---------------- Pré-saut dans OFFRE / ACCEPTE ---------------- */

    private val ephemeral = ByteArray(32) { (it + 40).toByte() }
    private val alice = ByteArray(32) { 1 }
    private val bob = ByteArray(32) { 2 }
    private val sig = ByteArray(64) { 7 }

    @Test fun `ancien format inchange octet pour octet, pre-saut par defaut`() {
        val frame = CallProtocol.signal(CallProtocol.OFFER, callId, ephemeral, sig, preSkip = null)
        assertEquals(CallProtocol.SIGNAL_SIZE, frame.size)
        assertArrayEquals(byteArrayOf(CallProtocol.OFFER) + callId + ephemeral + sig, frame)
        val parsed = CallProtocol.parseSignal(frame)!!
        assertEquals(CallProtocol.DEFAULT_PRE_SKIP, parsed.preSkip)
        assertFalse(parsed.preSkipSigned)
        assertArrayEquals(sig, parsed.signature)
        // Lien signé de l'ancien format : identique à celui d'avant le pré-saut.
        assertArrayEquals(
            "whisper-v1|call|offer|".toByteArray(Charsets.US_ASCII) + callId + ephemeral + alice + bob,
            CallProtocol.signalBinding(CallProtocol.OFFER, callId, ephemeral, alice, bob, null)
        )
    }

    @Test fun `nouveau format, pre-saut little-endian en fin de trame`() {
        val frame = CallProtocol.signal(CallProtocol.ACCEPT, callId, ephemeral, sig, preSkip = 3840)
        assertEquals(CallProtocol.SIGNAL_SIZE + 2, frame.size)
        assertEquals(0x00.toByte(), frame[CallProtocol.SIGNAL_SIZE])
        assertEquals(0x0F.toByte(), frame[CallProtocol.SIGNAL_SIZE + 1])
        val parsed = CallProtocol.parseSignal(frame)!!
        assertEquals(CallProtocol.ACCEPT, parsed.type)
        assertEquals(3840, parsed.preSkip)
        assertTrue(parsed.preSkipSigned)
        assertArrayEquals(sig, parsed.signature)
        assertArrayEquals(ephemeral, parsed.ephemeral)
    }

    @Test fun `taille invalide refusee`() {
        val frame = CallProtocol.signal(CallProtocol.OFFER, callId, ephemeral, sig, preSkip = 312)
        assertNull(CallProtocol.parseSignal(frame.copyOf(CallProtocol.SIGNAL_SIZE + 1)))
        assertNull(CallProtocol.parseSignal(frame.copyOf(CallProtocol.SIGNAL_SIZE + 3)))
        assertNull(CallProtocol.parseSignal(frame.copyOf(CallProtocol.SIGNAL_SIZE - 1)))
        assertNull(CallProtocol.parseSignal(CallProtocol.end(callId, CallProtocol.END_HANGUP)))
    }

    @Test fun `pre-saut hors bornes refuse`() {
        val frame = CallProtocol.signal(CallProtocol.OFFER, callId, ephemeral, sig, preSkip = 312)
        frame[CallProtocol.SIGNAL_SIZE] = 0x81.toByte()        // 48 001 = 0xBB81
        frame[CallProtocol.SIGNAL_SIZE + 1] = 0xBB.toByte()
        assertNull(CallProtocol.parseSignal(frame))
        frame[CallProtocol.SIGNAL_SIZE] = 0x80.toByte()        // 48 000 : la borne passe
        assertEquals(48_000, CallProtocol.parseSignal(frame)!!.preSkip)
        frame[CallProtocol.SIGNAL_SIZE] = 0xFF.toByte()        // 65 535
        frame[CallProtocol.SIGNAL_SIZE + 1] = 0xFF.toByte()
        assertNull(CallProtocol.parseSignal(frame))
    }

    @Test fun `nouveau format seulement pour un contact assez recent`() {
        assertFalse(CallProtocol.sendsPreSkip(CallProtocol.PRE_SKIP_MIN_VERSION - 1))
        assertFalse(CallProtocol.sendsPreSkip(0))
        assertTrue(CallProtocol.sendsPreSkip(CallProtocol.PRE_SKIP_MIN_VERSION))
        // On n'envoie jamais ce que l'autre côté refuserait.
        val refused = runCatching { CallProtocol.signal(CallProtocol.OFFER, callId, ephemeral, sig, 70_000) }
        assertTrue(refused.isFailure)
    }

    @Test fun `signature qui echoue si le pre-saut est modifie`() {
        val private = Ed25519PrivateKeyParameters(java.security.SecureRandom())
        val public = private.generatePublicKey()
        fun sign(data: ByteArray) = Ed25519Signer().run { init(true, private); update(data, 0, data.size); generateSignature() }
        fun verify(signal: CallProtocol.Signal): Boolean {
            val binding = CallProtocol.signalBinding(
                signal.type, signal.callId, signal.ephemeral, alice, bob, signal.preSkip.takeIf { signal.preSkipSigned }
            )
            return Ed25519Signer().run { init(false, public); update(binding, 0, binding.size); verifySignature(signal.signature) }
        }

        val binding = CallProtocol.signalBinding(CallProtocol.OFFER, callId, ephemeral, alice, bob, 312)
        val frame = CallProtocol.signal(CallProtocol.OFFER, callId, ephemeral, sign(binding), 312)
        assertTrue(verify(CallProtocol.parseSignal(frame)!!))

        val tampered = frame.copyOf().also { it[CallProtocol.SIGNAL_SIZE] = 0x39 }   // 312 → 313
        assertFalse(verify(CallProtocol.parseSignal(tampered)!!))

        // Pré-saut retiré pour se faire passer pour l'ancien format : refusé aussi.
        val stripped = frame.copyOf(CallProtocol.SIGNAL_SIZE)
        assertFalse(verify(CallProtocol.parseSignal(stripped)!!))
    }
}
