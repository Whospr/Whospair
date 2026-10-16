package com.whisper.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class CallKeysTest {

    private val sid = "a1b2c3"
    private val alice = CallKeys.newEphemeral()
    private val bob = CallKeys.newEphemeral()

    @Test fun `les deux cotes tombent sur les memes cles, croisees`() {
        val (aSend, aRecv) = CallKeys.derive(alice, bob.publicKey, sid, initiator = true)
        val (bSend, bRecv) = CallKeys.derive(bob, alice.publicKey, sid, initiator = false)
        assertArrayEquals(aSend, bRecv)
        assertArrayEquals(bSend, aRecv)
        assertFalse(aSend.contentEquals(aRecv))
    }

    @Test fun `ephemere substituee par le relais donne des cles incoherentes`() {
        val mallory = CallKeys.newEphemeral()
        val (aSend, _) = CallKeys.derive(alice, mallory.publicKey, sid, initiator = true)
        val (_, bRecv) = CallKeys.derive(bob, mallory.publicKey, sid, initiator = false)
        assertFalse(aSend.contentEquals(bRecv))
    }

    @Test fun `point degenere refuse`() {
        assertThrows(IllegalStateException::class.java) {
            CallKeys.derive(alice, ByteArray(32), sid, initiator = true)
        }
    }
}
