package com.whisper.util

/**
 * Répondre à un message (1.3.1), comme on le fait en glissant une bulle.
 *
 * La citation voyage À L'INTÉRIEUR du message chiffré : un en-tête au début
 * du texte, invisible à l'écran. Rien de lisible n'est ajouté à ce qui
 * circule ; le protocole ne change pas.
 *
 *   "WRP1" 0x01 identifiant 0x01 o|t 0x01 extrait 0x02 texte
 *
 *   o : le message cité est de l'auteur de la réponse ; t : de l'autre.
 *   extrait : quelques mots, pour l'afficher même si l'original a expiré.
 */
object ReplyPayload {
    private const val MAGIC = "WRP1"
    private const val SEP = '\u0001'
    private const val END = '\u0002'
    const val SNIPPET = 90

    class Reply(
        val quotedId: String,
        /** Vrai si le message cité a été écrit par l'auteur de cette réponse. */
        val quotedByAuthor: Boolean,
        val snippet: String,
        val body: String
    )

    fun encode(quotedId: String, quotedByAuthor: Boolean, snippet: String, body: String): String {
        val clean = snippet.replace(SEP, ' ').replace(END, ' ').replace('\n', ' ').trim().take(SNIPPET)
        return MAGIC + SEP + quotedId + SEP + (if (quotedByAuthor) "o" else "t") + SEP + clean + END + body
    }

    fun parse(text: String?): Reply? {
        if (text == null || !text.startsWith(MAGIC + SEP)) return null
        val end = text.indexOf(END)
        if (end < 0) return null
        val parts = text.substring(MAGIC.length + 1, end).split(SEP)
        if (parts.size != 3 || parts[0].isEmpty() || parts[1] !in setOf("o", "t")) return null
        return Reply(parts[0], parts[1] == "o", parts[2], text.substring(end + 1))
    }

    /** Le texte à montrer : sans l'en-tête s'il y en a un. */
    fun body(text: String): String = parse(text)?.body ?: text
}
