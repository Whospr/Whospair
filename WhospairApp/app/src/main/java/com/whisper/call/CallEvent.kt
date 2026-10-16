package com.whisper.call

/**
 * La trace d'un appel dans la conversation : « Appel sortant · 2:31 »,
 * « Appel manqué »…
 *
 * Rangée comme NOTRE copie d'un message (chiffrée par sealLocal), jamais
 * transmise : chaque téléphone écrit la sienne. Elle expire comme un message,
 * avec la durée par défaut des Paramètres. Pas de journal d'appels à part.
 *
 * Format : MARQUEUR|sortant 0/1|décroché 0/1|motif de fin|durée en s.
 * Le marqueur commence par un caractère nul, qu'aucun texte tapé ne contient.
 */
data class CallEvent(
    val outgoing: Boolean,
    val answered: Boolean,
    val reason: EndReason,
    val seconds: Long
) {
    fun encode(): String =
        listOf(MARK, flag(outgoing), flag(answered), reason.name, seconds).joinToString("|")

    companion object {
        const val MARK = "\u0000WC1"

        fun isCall(text: String?): Boolean = text?.startsWith(MARK) == true

        fun parse(text: String?): CallEvent? {
            if (!isCall(text)) return null
            val f = text!!.split('|')
            if (f.size != 5) return null
            val reason = runCatching { EndReason.valueOf(f[3]) }.getOrNull() ?: return null
            val seconds = f[4].toLongOrNull()?.takeIf { it >= 0 } ?: return null
            return CallEvent(f[1] == "1", f[2] == "1", reason, seconds)
        }

        private fun flag(b: Boolean) = if (b) "1" else "0"
    }
}
