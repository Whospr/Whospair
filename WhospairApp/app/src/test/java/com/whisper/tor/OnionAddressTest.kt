package com.whisper.tor

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.security.SecureRandom

class OnionAddressTest {

    private val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }

    @Test fun `adresse de 56 caracteres, stable, valide`() {
        val address = OnionAddress.fromSeed(seed)
        assertEquals(56, address.length)
        assertEquals(address, OnionAddress.fromSeed(seed))
        assertTrue(OnionAddress.isValid(address))
        assertTrue(OnionAddress.isValid(address.uppercase()))
        assertTrue(address.endsWith("d")) // l'octet de version 0x03 finit toujours en « d »
    }

    @Test fun `faute de frappe, troncature, alphabet hors base32 refuses`() {
        val address = OnionAddress.fromSeed(seed)
        val typo = address.substring(0, 10) + (if (address[10] == 'a') 'b' else 'a') + address.substring(11)
        assertFalse(OnionAddress.isValid(typo))
        assertFalse(OnionAddress.isValid(address.drop(1)))
        assertFalse(OnionAddress.isValid(address.dropLast(1) + "1"))
    }

    @Test fun `la cle developpee garde le prefixe et borne le scalaire`() {
        val expanded = OnionAddress.expandedSecret(seed)
        val hash = MessageDigest.getInstance("SHA-512").digest(seed)
        assertEquals(64, expanded.size)
        assertArrayEquals(hash.copyOfRange(32, 64), expanded.copyOfRange(32, 64))
        assertEquals(0, expanded[0].toInt() and 7)
        assertEquals(64, expanded[31].toInt() and 0xC0)
    }

    @Test fun `l'adresse encode bien la cle publique Ed25519 de la graine`() {
        val publicKey = Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded
        assertEquals(OnionAddress.fromPublicKey(publicKey), OnionAddress.fromSeed(seed))
    }
}
