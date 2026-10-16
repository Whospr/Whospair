package com.whisper.voice

import java.util.Base64

/**
 * Un message vocal, découpé pour voyager comme des messages texte.
 *
 * Chaque morceau est un message ordinaire — chiffré, signé, numéroté,
 * expirant — dont le texte commence par un marqueur. Le transport et la
 * cryptographie n'ont donc pas bougé : un vocal n'ouvre aucune porte de plus.
 *
 * Pourquoi découper : une trame Tor est plafonnée à 64 Kio (anti-saturation),
 * et le contenu y voyage en hexadécimal. 15 000 octets d'audio → 20 000
 * caractères base64 → 40 000 en hex : large marge sous le plafond. Une minute
 * de voix à 16 kbit/s fait 120 Ko, soit 8 morceaux.
 *
 * Format du texte : MARQUEUR|idVocal|index|total|duréeMs|audio en base64.
 * Le marqueur commence par un caractère nul, qu'aucun texte tapé ne contient.
 */
object VoicePayload {

    const val MARK = "\u0000WV1"
    private const val CHUNK_BYTES = 15_000
    /** 60 s à 16 kbit/s, avec marge : au-delà, le morceau est refusé. */
    private const val MAX_PARTS = 12

    class Part(
        val voiceId: String,
        val index: Int,
        val count: Int,
        val durationMs: Int,
        val data: ByteArray
    )

    fun isVoice(text: String?): Boolean = text?.startsWith(MARK) == true

    /** Découpe un enregistrement en textes prêts à chiffrer, un par morceau. */
    fun encode(voiceId: String, durationMs: Int, audio: ByteArray): List<String> {
        val chunks = audio.toList().chunked(CHUNK_BYTES).map { it.toByteArray() }
        require(chunks.size in 1..MAX_PARTS) { "Vocal trop long" }
        return chunks.mapIndexed { i, chunk ->
            listOf(MARK, voiceId, i, chunks.size, durationMs, Base64.getEncoder().encodeToString(chunk))
                .joinToString("|")
        }
    }

    /** null si le texte n'est pas un morceau de vocal bien formé. */
    fun parse(text: String): Part? {
        if (!isVoice(text)) return null
        val f = text.split('|', limit = 6)
        if (f.size != 6) return null
        val index = f[2].toIntOrNull() ?: return null
        val count = f[3].toIntOrNull() ?: return null
        val duration = f[4].toIntOrNull() ?: return null
        if (count !in 1..MAX_PARTS || index !in 0 until count || duration !in 0..120_000) return null
        if (f[1].isEmpty() || f[1].length > 64) return null
        val data = runCatching { Base64.getDecoder().decode(f[5]) }.getOrNull() ?: return null
        return Part(f[1], index, count, duration, data)
    }

    /** Durée annoncée, sans décoder l'audio : pour les aperçus. */
    fun durationMs(text: String): Int? =
        if (!isVoice(text)) null else text.split('|', limit = 6).getOrNull(4)?.toIntOrNull()

    /** Recolle les morceaux ; null tant qu'il en manque un. */
    fun assemble(parts: Collection<Part>): ByteArray? {
        val count = parts.firstOrNull()?.count ?: return null
        val byIndex = parts.associateBy { it.index }
        if (byIndex.size != count || (0 until count).any { it !in byIndex }) return null
        return (0 until count).fold(ByteArray(0)) { acc, i -> acc + byIndex.getValue(i).data }
    }

    fun durationLabel(ms: Int): String {
        val s = (ms + 500) / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }
}
