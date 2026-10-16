package com.whisper.update

import com.whisper.crypto.CryptoManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class UpdateNotesTest {

    private val crypto = CryptoManager()
    private val developer = crypto.generateIdentity()
    private val key = developer.signingPublicKeyHex

    private fun raw(code: Long, notes: String = """{"fr":"Bonjour","en":"Hello","zh-Hant":"你好"}""") =
        """{"app":"${UpdateManifest.APP_ID}","code":$code,"notes":$notes}""".toByteArray()

    private fun sign(raw: ByteArray) = crypto.sign(UpdateNotes.signedData(raw), developer.signingPrivateKeyHex)

    @Test fun `notes signees acceptees pour leur version seulement`() {
        val r = raw(13)
        val sig = sign(r)
        assertNotNull(UpdateNotes.verify(r, sig, 13, key))
        // Recollées sur une autre version.
        assertNull(UpdateNotes.verify(r, sig, 14, key))
        // Texte modifié en route.
        val tampered = r.copyOf().also { it[it.size - 5] = 'X'.code.toByte() }
        assertNull(UpdateNotes.verify(tampered, sig, 13, key))
        // Signées par quelqu'un d'autre.
        val other = crypto.generateIdentity()
        assertNull(UpdateNotes.verify(r, crypto.sign(UpdateNotes.signedData(r), other.signingPrivateKeyHex), 13, key))
        // Pour une autre app.
        val foreign = """{"app":"com.autre","code":13,"notes":{"fr":"x"}}""".toByteArray()
        assertNull(UpdateNotes.verify(foreign, sign(foreign), 13, key))
    }

    @Test fun `texte dans la langue de l'app, sinon anglais`() {
        val r = raw(13)
        val notes = UpdateNotes.verify(r, sign(r), 13, key)!!
        assertEquals("Bonjour", notes.textFor(Locale.FRENCH))
        assertEquals("你好", notes.textFor(Locale.forLanguageTag("zh-Hant-TW")))
        assertEquals("Hello", notes.textFor(Locale.forLanguageTag("ko")))
    }

    @Test fun `notes vides ou trop longues refusees`() {
        val empty = raw(13, "{}")
        assertNull(UpdateNotes.verify(empty, sign(empty), 13, key))
        val huge = raw(13, """{"fr":"${"a".repeat(UpdateNotes.MAX_NOTES)}"}""")
        assertNull(UpdateNotes.verify(huge, sign(huge), 13, key))
    }
}
