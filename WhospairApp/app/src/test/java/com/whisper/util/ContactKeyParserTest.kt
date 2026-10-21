package com.whisper.util

import com.whisper.tor.OnionAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactKeyParserTest {

    private val x = "ab".repeat(32)
    private val ed = "cd".repeat(32)
    private val onion = OnionAddress.fromSeed(ByteArray(32) { it.toByte() })

    @Test fun `QR, lien et collage brut donnent la meme identite`() {
        val expected = ContactKeyParser.Result.Valid(x, ed, onion)
        assertEquals(expected, ContactKeyParser.parse(ContactKeyParser.toQrPayload(x, ed, onion)))
        assertEquals(expected, ContactKeyParser.parse(ContactKeyParser.toShareableLink(x, ed, onion)))
        assertEquals(expected, ContactKeyParser.parse((x + ed + onion).uppercase()))
        assertEquals(expected, ContactKeyParser.parse(" whisper:$x$ed\n$onion "))
        assertEquals(expected, ContactKeyParser.parse("whospair:$x$ed$onion"))
        assertEquals(expected, ContactKeyParser.parse("whospair://add_contact?key=$x$ed$onion"))
        assertEquals(expected, ContactKeyParser.parse("https://whospair.github.io/a#$x$ed$onion"))
    }

    @Test fun `le message d'invitation colle en entier est lu`() {
        val link = ContactKeyParser.toShareableLink(x, ed, onion)
        assertTrue(link.startsWith("https://"))
        assertEquals(
            ContactKeyParser.Result.Valid(x, ed, onion),
            ContactKeyParser.parse("Parlons sur Whospair.\nAjoute-moi : $link\nPas encore l'app ?")
        )
        // « İ » s'allonge en minuscules : l'indice ne doit pas se décaler.
        assertEquals(ContactKeyParser.Result.Valid(x, ed, onion), ContactKeyParser.parse("İşte İzmir $link"))
    }

    @Test fun `sans onion, ou onion corrompue, c'est refuse`() {
        assertTrue(ContactKeyParser.parse("whisper:$x$ed") is ContactKeyParser.Result.MalformedKey)
        val broken = onion.dropLast(2) + (if (onion[54] == 'a') "bd" else "ad")
        assertTrue(ContactKeyParser.parse("whisper:$x$ed$broken") is ContactKeyParser.Result.MalformedKey)
        assertTrue(ContactKeyParser.parse("bonjour") is ContactKeyParser.Result.NotAWhisperKey)
    }
}
