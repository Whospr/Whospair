package com.whisper.reach

import com.whisper.security.PinCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class InboxSealTest {

    private val vault = PinCrypto.randomBytes(32)
    private val private = InboxSeal.privateKey(vault)
    private val public = InboxSeal.publicKey(private)
    private val message = "contact ‖ trame chiffrée".toByteArray()

    @Test fun `la cle privee du coffre rouvre ce que la cle publique a scelle`() {
        assertArrayEquals(message, InboxSeal.open(InboxSeal.seal(message, public), private))
    }

    @Test fun `meme coffre, meme boite`() {
        assertArrayEquals(public, InboxSeal.publicKey(InboxSeal.privateKey(vault)))
    }

    @Test fun `autre coffre, rien ne s ouvre`() {
        val other = InboxSeal.privateKey(PinCrypto.randomBytes(32))
        assertNull(InboxSeal.open(InboxSeal.seal(message, public), other))
    }

    @Test fun `un octet change, rien ne s ouvre`() {
        val sealed = InboxSeal.seal(message, public)
        for (i in listOf(0, 31, 32, sealed.size - 1)) {
            val bent = sealed.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertNull("octet $i", InboxSeal.open(bent, private))
        }
    }

    @Test fun `deux sceaux du meme message different`() {
        assertFalse(InboxSeal.seal(message, public).contentEquals(InboxSeal.seal(message, public)))
    }

    @Test fun `blob trop court refuse`() {
        assertNull(InboxSeal.open(ByteArray(10), private))
        assertEquals(32, public.size)
    }
}
