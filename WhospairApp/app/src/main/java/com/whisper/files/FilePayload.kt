package com.whisper.files

import java.util.Base64

/**
 * Un fichier, découpé pour voyager comme des messages (phase 16).
 *
 * Chaque morceau est un message ordinaire — chiffré, signé, numéroté,
 * accusé, renvoyé s'il manque, expirant — comme les morceaux d'un vocal.
 * La reprise après coupure vient de là : un morceau non accusé repart sur
 * le flux suivant, les autres ne bougent pas.
 *
 * Le clair d'un morceau : une ligne d'en-tête en ASCII, un saut de ligne,
 * puis les octets du fichier, bruts. Pas de base64 : 48 000 octets de
 * fichier font un chiffré de 48 000 octets, envoyé en trame binaire (voir
 * MessageFrame). Le vocal, lui, garde son format : rien ne change pour
 * une version qui ne connaît pas les fichiers.
 *
 * En-tête : MARQUEUR|idFichier|index|total|taille|options|type|nom en base64.
 * Le marqueur commence par un caractère nul, qu'aucun texte tapé ne contient.
 * L'en-tête ne contient ni « | » ni saut de ligne ailleurs qu'aux séparateurs :
 * le nom est en base64, le type est filtré.
 */
object FilePayload {

    const val MARK = "\u0000WF1"
    const val CHUNK_BYTES = 48_000
    const val MAX_BYTES = 25L * 1024 * 1024
    private val MAX_PARTS = ((MAX_BYTES + CHUNK_BYTES - 1) / CHUNK_BYTES).toInt()
    private const val MAX_NAME = 120
    private const val MAX_MIME = 100

    /** L'envoyeur autorise « Enregistrer » et « Ouvrir avec… ». Absent : lecture dans Whisper seulement. */
    const val FLAG_SAVABLE = 1
    /** Effacé chez le destinataire après la première ouverture. */
    const val FLAG_VIEW_ONCE = 2

    /**
     * Morceau qui n'est pas le dernier : son identifiant commence par ceci.
     * Le destinataire (app ou `:reach`) le range sans biper ni le montrer ;
     * seul le dernier morceau — envoyé en dernier, les compteurs l'imposent —
     * fait biper et apparaître le fichier. L'identifiant ne voyage que jusqu'au
     * téléphone destinataire, il ne dit rien à personne d'autre.
     */
    const val PARTIAL_ID_PREFIX = "p-"

    fun isPartial(messageId: String): Boolean = messageId.startsWith(PARTIAL_ID_PREFIX)

    class Header(
        val fileId: String,
        val index: Int,
        val count: Int,
        val size: Long,
        val flags: Int,
        val mime: String,
        val name: String
    ) {
        val savable: Boolean get() = flags and FLAG_SAVABLE != 0
        val viewOnce: Boolean get() = flags and FLAG_VIEW_ONCE != 0
        /** Partager, transférer, enregistrer : jamais un fichier en vue unique, même si l'envoyeur a coché les deux. */
        val shareable: Boolean get() = savable && !viewOnce
    }

    fun isFile(text: String?): Boolean = text?.startsWith(MARK) == true

    /**
     * Les clairs des morceaux, prêts à chiffrer. L'appelant efface [data]
     * une fois les morceaux chiffrés.
     */
    fun encode(fileId: String, name: String, mime: String, flags: Int, data: ByteArray): List<ByteArray> {
        require(data.isNotEmpty() && data.size <= MAX_BYTES) { "Fichier vide ou trop gros" }
        val count = (data.size + CHUNK_BYTES - 1) / CHUNK_BYTES
        val safeName = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(name.take(MAX_NAME).toByteArray(Charsets.UTF_8))
        val safeMime = cleanMime(mime)
        return (0 until count).map { i ->
            val from = i * CHUNK_BYTES
            val to = minOf(from + CHUNK_BYTES, data.size)
            val header = listOf(MARK, fileId, i, count, data.size, flags, safeMime, safeName)
                .joinToString("|") + "\n"
            header.toByteArray(Charsets.US_ASCII) + data.copyOfRange(from, to)
        }
    }

    /**
     * La ligne d'en-tête seule, sans les octets : c'est elle qu'on garde
     * pour l'affichage (nom, taille, progression), jamais le contenu.
     */
    fun headerLine(text: String): String? =
        if (!isFile(text)) null else text.substringBefore('\n', missingDelimiterValue = "").ifEmpty { null }

    /** null si ce n'est pas un en-tête de morceau bien formé. */
    fun header(text: String): Header? {
        val line = headerLine(text) ?: text.takeIf { isFile(it) && '\n' !in it } ?: return null
        val f = line.split('|')
        if (f.size != 8) return null
        val index = f[2].toIntOrNull() ?: return null
        val count = f[3].toIntOrNull() ?: return null
        val size = f[4].toLongOrNull() ?: return null
        val flags = f[5].toIntOrNull() ?: return null
        if (count !in 1..MAX_PARTS || index !in 0 until count) return null
        if (size !in 1..MAX_BYTES || count != ((size + CHUNK_BYTES - 1) / CHUNK_BYTES).toInt()) return null
        if (f[1].isEmpty() || f[1].length > 64) return null
        val name = runCatching { String(Base64.getUrlDecoder().decode(f[7]), Charsets.UTF_8) }.getOrNull() ?: return null
        return Header(f[1], index, count, size, flags, cleanMime(f[6]), name.take(MAX_NAME))
    }

    /** Les octets d'un morceau déchiffré, après l'en-tête ; null s'il est mal formé. */
    fun dataOf(plain: ByteArray): ByteArray? {
        val newline = plain.indexOf('\n'.code.toByte())
        if (newline <= 0) return null
        return plain.copyOfRange(newline + 1, plain.size)
    }

    /** Recolle les morceaux ; null tant qu'il en manque un ou si la taille ne tombe pas juste. */
    fun assemble(parts: Map<Int, ByteArray>, count: Int, size: Long): ByteArray? {
        if (parts.size != count || (0 until count).any { it !in parts }) return null
        if (parts.values.sumOf { it.size.toLong() } != size) return null
        val out = ByteArray(size.toInt())
        var at = 0
        for (i in 0 until count) {
            val part = parts.getValue(i)
            part.copyInto(out, at)
            at += part.size
        }
        return out
    }

    /** Type MIME réduit à ce qu'il doit être : rien qui puisse casser l'en-tête. */
    private fun cleanMime(mime: String): String =
        mime.lowercase().filter { it.isLetterOrDigit() || it in "./+-" }.take(MAX_MIME)
            .ifEmpty { "application/octet-stream" }

    /** « 2,4 Mo », « 830 Ko ». */
    fun sizeLabel(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "%.1f Mo".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> "${bytes / 1024} Ko"
        else -> "$bytes o"
    }
}
