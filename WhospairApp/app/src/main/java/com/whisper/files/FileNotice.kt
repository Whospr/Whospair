package com.whisper.files

import java.util.Base64

/**
 * Ce que le destinataire a fait d'un fichier, dit à celui qui l'a envoyé
 * (phase 16) : partagé à un contact ou à une autre app, enregistré, ouvert
 * hors de Whisper, vu (vue unique). Et dans l'autre sens : l'envoyeur a
 * annulé l'envoi.
 *
 * Un message ordinaire — chiffré, signé, accusé, renvoyé s'il le faut —
 * dont le texte commence par un marqueur : l'avis arrive même si
 * l'envoyeur est hors ligne au moment du geste. Il ne dit jamais À QUI le
 * fichier a été transféré, seulement qu'il l'a été.
 *
 * Format : MARQUEUR|idFichier|événement|nom en base64.
 */
object FileNotice {

    const val MARK = "\u0000WN1"

    enum class Event(val code: String) {
        FORWARDED("fwd"),
        SHARED("app"),
        SAVED("save"),
        OPENED_ELSEWHERE("open"),
        VIEWED("seen"),
        /** L'envoyeur a annulé : le destinataire efface les morceaux déjà reçus. */
        CANCELLED("stop");

        companion object {
            fun of(code: String) = entries.firstOrNull { it.code == code }
        }
    }

    class Notice(val fileId: String, val event: Event, val name: String)

    fun isNotice(text: String?): Boolean = text?.startsWith(MARK) == true

    fun encode(fileId: String, event: Event, name: String): String =
        listOf(
            MARK, fileId, event.code,
            Base64.getUrlEncoder().withoutPadding().encodeToString(name.take(120).toByteArray(Charsets.UTF_8))
        ).joinToString("|")

    fun parse(text: String?): Notice? {
        if (!isNotice(text)) return null
        val f = text!!.split('|')
        if (f.size != 4 || f[1].isEmpty() || f[1].length > 64) return null
        val event = Event.of(f[2]) ?: return null
        val name = runCatching { String(Base64.getUrlDecoder().decode(f[3]), Charsets.UTF_8) }.getOrNull() ?: return null
        return Notice(f[1], event, name.take(120))
    }
}
