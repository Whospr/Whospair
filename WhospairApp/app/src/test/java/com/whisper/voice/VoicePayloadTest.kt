package com.whisper.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class VoicePayloadTest {

    /** Une minute à 16 kbit/s, avec la marge de l'en-tête des paquets. */
    private val minute = Random(1).nextBytes(125_000)

    @Test fun `decoupe puis recolle a l'identique`() {
        val texts = VoicePayload.encode("v1", 60_000, minute)
        val parts = texts.map { VoicePayload.parse(it)!! }
        assertEquals(texts.size, parts.first().count)
        assertArrayEquals(minute, VoicePayload.assemble(parts.shuffled(Random(2))))
    }

    @Test fun `chaque morceau tient dans une trame Tor`() {
        // Le chiffré a la taille du clair ; le transport l'écrit en hexadécimal
        // dans un JSON d'environ 700 octets d'en-têtes.
        VoicePayload.encode("v1", 60_000, minute).forEach {
            val frame = it.toByteArray().size * 2 + 700
            assertTrue("trame de $frame octets", frame < 64 * 1024)
        }
    }

    @Test fun `un morceau manquant bloque la lecture`() {
        val parts = VoicePayload.encode("v1", 60_000, minute).map { VoicePayload.parse(it)!! }
        assertNull(VoicePayload.assemble(parts.drop(1)))
    }

    @Test fun `un texte ordinaire n'est pas un vocal`() {
        assertFalse(VoicePayload.isVoice("Bonjour | à ce soir"))
        assertNull(VoicePayload.parse("WV1|v1|0|1|1000|AAAA"))
    }

    @Test fun `les morceaux mal formes sont refuses`() {
        val m = VoicePayload.MARK
        assertNull(VoicePayload.parse("$m|v1|0|1|1000"))            // champ manquant
        assertNull(VoicePayload.parse("$m|v1|3|2|1000|AAAA"))       // index hors bornes
        assertNull(VoicePayload.parse("$m|v1|0|99|1000|AAAA"))      // trop de morceaux
        assertNull(VoicePayload.parse("$m|v1|0|1|999999|AAAA"))     // durée absurde
        assertNull(VoicePayload.parse("$m|v1|0|1|1000|%%%"))        // base64 invalide
        assertNull(VoicePayload.parse("$m||0|1|1000|AAAA"))         // identifiant vide
    }

    @Test(expected = IllegalArgumentException::class)
    fun `un vocal trop long est refuse a l'envoi`() {
        VoicePayload.encode("v1", 60_000, ByteArray(15_000 * 13))
    }

    @Test fun `duree lisible`() {
        assertEquals("0:07", VoicePayload.durationLabel(7_200))
        assertEquals("1:00", VoicePayload.durationLabel(60_000))
        assertEquals(12_000, VoicePayload.durationMs(VoicePayload.encode("v", 12_000, ByteArray(10)).first()))
    }
}
