package com.whisper.transport

/**
 * Trame binaire d'un message trop gros pour l'hexadécimal (phase 16 :
 * morceaux de fichier). Tout ce qui tient en JSON hexadécimal part comme
 * avant ; seul ce qui ne tiendrait pas prend cette forme — une version
 * ancienne reçoit donc toujours tout ce qu'elle savait recevoir.
 *
 * MAGIE (4 octets) | longueur de l'en-tête (2 octets) | en-tête JSON (les
 * champs habituels, sans « content ») | chiffré brut.
 *
 * La magie commence par « W » : ni « { » (JSON), ni un octet d'appel (0xC1–0xC4).
 */
object MessageFrame {

    private val MAGIC = "WBM1".toByteArray(Charsets.US_ASCII)
    private const val LENGTH_SIZE = 2

    fun isBinary(payload: ByteArray): Boolean =
        payload.size > MAGIC.size + LENGTH_SIZE &&
            (MAGIC.indices).all { payload[it] == MAGIC[it] }

    fun build(header: ByteArray, content: ByteArray): ByteArray {
        require(header.size <= 0xFFFF) { "En-tête trop long" }
        val length = byteArrayOf((header.size shr 8).toByte(), header.size.toByte())
        return MAGIC + length + header + content
    }

    /** (en-tête JSON, chiffré) ; null si la trame est mal formée. */
    fun split(payload: ByteArray): Pair<ByteArray, ByteArray>? {
        if (!isBinary(payload)) return null
        val at = MAGIC.size
        val length = ((payload[at].toInt() and 0xFF) shl 8) or (payload[at + 1].toInt() and 0xFF)
        val start = at + LENGTH_SIZE
        if (length == 0 || start + length >= payload.size) return null
        return payload.copyOfRange(start, start + length) to payload.copyOfRange(start + length, payload.size)
    }
}
