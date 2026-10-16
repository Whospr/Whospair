package com.whisper.call

import com.whisper.security.CallKeys
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
}
