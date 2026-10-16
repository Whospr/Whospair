package com.whisper.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyCodeTest {

    private val crypto = CryptoManager()
    private val alice = crypto.generateIdentity()
    private val bob = crypto.generateIdentity()

    private fun code(a: WhisperKeyPair, b: WhisperKeyPair) = crypto.pairSafetyCode(
        a.encryptionPublicKeyHex, a.signingPublicKeyHex, b.encryptionPublicKeyHex, b.signingPublicKeyHex
    )

    @Test fun `meme code des deux cotes`() {
        assertEquals(code(alice, bob), code(bob, alice))
        assertTrue(code(alice, bob).startsWith(CryptoManager.SAFETY_QR_PREFIX))
        assertEquals(CryptoManager.SAFETY_QR_PREFIX.length + 64, code(alice, bob).length)
    }

    @Test fun `un intrus au milieu change le code`() {
        val mallory = crypto.generateIdentity()
        assertNotEquals(code(alice, bob), code(alice, mallory))
        assertNotEquals(code(alice, bob), code(mallory, bob))
    }
}
