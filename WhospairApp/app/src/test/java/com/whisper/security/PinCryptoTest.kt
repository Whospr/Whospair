package com.whisper.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PinCryptoTest {

    private val salt = PinCrypto.randomBytes(PinCrypto.SALT_SIZE)
    private val baseKey = PinCrypto.randomBytes(PinCrypto.KEY_SIZE)
    private val kek = PinCrypto.deriveKey("482913".toCharArray(), salt)

    @Test fun `derivation deterministe par PIN et sel`() {
        assertArrayEquals(kek, PinCrypto.deriveKey("482913".toCharArray(), salt))
        assertFalse(kek.contentEquals(PinCrypto.deriveKey("482914".toCharArray(), salt)))
        assertFalse(kek.contentEquals(PinCrypto.deriveKey("482913".toCharArray(), PinCrypto.randomBytes(16))))
    }

    @Test fun `bon PIN rend la cle de base`() {
        val blob = PinCrypto.wrap(baseKey, kek)
        assertEquals(1 + 12 + 32 + 16, blob.size)
        assertArrayEquals(baseKey, PinCrypto.unwrap(blob, kek))
    }

    @Test fun `mauvais PIN, blob altere, version inconnue, blob tronque sont refuses`() {
        val blob = PinCrypto.wrap(baseKey, kek)
        assertNull(PinCrypto.unwrap(blob, PinCrypto.deriveKey("000000".toCharArray(), salt)))
        assertNull(PinCrypto.unwrap(blob.copyOf().also { it[20] = (it[20].toInt() xor 1).toByte() }, kek))
        assertNull(PinCrypto.unwrap(blob.copyOf().also { it[0] = 2 }, kek))
        assertNull(PinCrypto.unwrap(ByteArray(5), kek))
    }

    @Test fun `politique de PIN`() {
        assertFalse(Vault.isAcceptable("12345".toCharArray()))
        assertFalse(Vault.isAcceptable("12345a".toCharArray()))
        assertTrue(Vault.isAcceptable("123456".toCharArray()))
    }

    @Test fun `sous-cles - stables, separees par usage, jamais la cle de base`() {
        val id = PinCrypto.subKey(baseKey, "identity")
        assertArrayEquals(id, PinCrypto.subKey(baseKey, "identity"))
        assertFalse(id.contentEquals(PinCrypto.subKey(baseKey, "onion")))
        assertFalse(id.contentEquals(baseKey))
    }

    @Test fun `identite scellee - 64 octets aller-retour, illisible avec une autre base`() {
        val secrets = PinCrypto.randomBytes(64)
        val sealed = PinCrypto.wrap(secrets, PinCrypto.subKey(baseKey, "identity"))
        assertArrayEquals(secrets, PinCrypto.unwrap(sealed, PinCrypto.subKey(baseKey, "identity")))
        assertNull(PinCrypto.unwrap(sealed, PinCrypto.subKey(PinCrypto.randomBytes(32), "identity")))
    }
}
