package com.whisper.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpusHeaderTest {

    /** En-tête Opus standard (RFC 7845 §5.1), mono, 48 kHz. */
    private fun opusHead(preSkip: Int): ByteArray =
        ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
            .put("OpusHead".toByteArray(Charsets.US_ASCII))
            .put(1).put(1)
            .putShort(preSkip.toShort())
            .putInt(48_000)
            .putShort(0).put(0)
            .array()

    /** Forme balisée de l'encodeur d'Android : AOPUSHDR, AOPUSDLY, AOPUSPRL. */
    private fun tagged(head: ByteArray, delayNs: Long?, prerollNs: Long = 80_000_000L): ByteArray {
        fun tag(name: String, body: ByteArray) =
            ByteBuffer.allocate(16 + body.size).order(ByteOrder.LITTLE_ENDIAN)
                .put(name.toByteArray(Charsets.US_ASCII)).putLong(body.size.toLong()).put(body).array()
        fun long(v: Long) = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array()
        return tag("AOPUSHDR", head) +
            (delayNs?.let { tag("AOPUSDLY", long(it)) } ?: ByteArray(0)) +
            tag("AOPUSPRL", long(prerollNs))
    }

    @Test fun `pre-saut lu dans un OpusHead`() {
        assertEquals(3840, OpusHeader.preSkipSamples(opusHead(3840)))
        assertEquals(312, OpusHeader.preSkipSamples(opusHead(312)))
        assertEquals(0, OpusHeader.preSkipSamples(opusHead(0)))
    }

    @Test fun `pre-saut lu dans AOPUSDLY, prioritaire sur l'en-tete interne`() {
        // 312 échantillons à 48 kHz = 6,5 ms.
        val header = tagged(opusHead(999), delayNs = 6_500_000L, prerollNs = 40_000_000L)
        assertEquals(312, OpusHeader.preSkipSamples(header))
        val parsed = OpusHeader.parse(header)
        assertArrayEquals(opusHead(999), parsed.head)
        assertEquals(40_000_000L, parsed.prerollNs)
    }

    @Test fun `AOPUSHDR sans AOPUSDLY, pre-saut de l'en-tete interne`() {
        assertEquals(3840, OpusHeader.preSkipSamples(tagged(opusHead(3840), delayNs = null)))
    }

    @Test fun `valeur hors bornes ou illisible rejetee`() {
        assertNull(OpusHeader.preSkipSamples(opusHead(0xFFFF), max = 48_000))
        assertEquals(48_000, OpusHeader.preSkipSamples(opusHead(48_000), max = 48_000))
        assertNull(OpusHeader.preSkipSamples(tagged(opusHead(312), delayNs = Long.MAX_VALUE)))
        assertNull(OpusHeader.preSkipSamples(ByteArray(4)))
    }
}
