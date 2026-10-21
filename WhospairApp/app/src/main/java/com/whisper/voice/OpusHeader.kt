package com.whisper.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * L'en-tête que rend l'encodeur Opus d'Android, sous l'une de deux formes :
 * l'en-tête Opus standard (« OpusHead »), ou un paquet balisé (« AOPUSHDR »…)
 * qui contient aussi le délai initial et la pré-lecture. Commun aux vocaux
 * (VoicePlayer) et aux appels (CallAudio).
 */
object OpusHeader {

    /** Opus compte toujours à 48 kHz, quel que soit le débit d'entrée. */
    const val RATE = 48_000
    private const val DEFAULT_PREROLL_NS = 80_000_000L

    /**
     * @property head l'en-tête Opus seul, pour csd-0.
     * @property delayNs pré-saut en nanosecondes, -1 s'il est illisible.
     * @property prerollNs pré-lecture en nanosecondes, pour csd-2.
     */
    class Parsed(val head: ByteArray, val delayNs: Long, val prerollNs: Long)

    fun parse(header: ByteArray): Parsed {
        var head = header
        var delayNs = -1L
        var prerollNs = DEFAULT_PREROLL_NS
        if (String(header, 0, minOf(8, header.size), Charsets.US_ASCII) == "AOPUSHDR") {
            val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            while (b.remaining() >= 16) {
                val tag = ByteArray(8).also { b.get(it) }.toString(Charsets.US_ASCII)
                val len = b.long.toInt()
                if (len < 0 || len > b.remaining()) break
                val body = ByteArray(len).also { b.get(it) }
                when (tag) {
                    "AOPUSHDR" -> head = body
                    "AOPUSDLY" -> if (len >= 8) delayNs = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN).long
                    "AOPUSPRL" -> if (len >= 8) prerollNs = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN).long
                }
            }
        }
        if (delayNs < 0 && head.size >= 12) {
            // Pré-saut de l'en-tête Opus (octets 10-11), en échantillons à 48 kHz.
            val preSkip = (head[10].toInt() and 0xFF) or ((head[11].toInt() and 0xFF) shl 8)
            delayNs = preSkip * 1_000_000_000L / RATE
        }
        return Parsed(head, delayNs, prerollNs)
    }

    /** Pré-saut en échantillons à 48 kHz, null s'il est illisible ou hors de [0, max]. */
    fun preSkipSamples(header: ByteArray, max: Int = RATE): Int? {
        val delayNs = parse(header).delayNs
        // Borne AVANT la multiplication : un délai démesuré la ferait déborder.
        if (delayNs < 0 || delayNs > max * 1_000_000_000L / RATE + 1) return null
        val samples = (delayNs * RATE + 500_000_000L) / 1_000_000_000L
        return samples.takeIf { it <= max }?.toInt()
    }
}
