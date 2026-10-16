package com.whisper.models

import java.time.LocalDate

/**
 * ConversationItem - Représente une conversation dans la liste
 * Utilisé pour afficher la dernière interaction avec un contact
 */
data class ConversationItem(
    val contact: Contact,
    val lastMessage: Message?,
    val unreadCount: Int = 0,
    val isOnline: Boolean = false,
    /**
     * Aperçu EN CLAIR du dernier message, fourni par la couche qui possède
     * la clé privée (MessagesViewModel). null tant qu'il n'a pas été déchiffré.
     *
     * Message.content est du ciphertext : le lire avec String(...) affichait
     * des octets binaires dans la liste des conversations.
     */
    val lastMessagePreview: String? = null,
    /** Un signe reçu depuis le dernier message : il remplace l'aperçu. */
    val nudged: Boolean = false
) {
    val lastMessageText: String
        get() = when {
            lastMessage == null -> "No messages"
            lastMessagePreview != null -> lastMessagePreview.take(50) +
                    if (lastMessagePreview.length > 50) "…" else ""
            lastMessage.status == MessageStatus.DELETED -> "Message expired"
            else -> "Encrypted message"
        }

    val lastMessageTime: String
        get() = lastMessage?.let { formatMessageTime(it.timestamp) } ?: ""

    companion object {
        /**
         * `isOnline` est passé explicitement, et ne vient plus du contact.
         *
         * Le contact ne porte plus cette information : elle n'est vraie qu'à
         * l'instant où on la lit, et la stocker revenait à consigner qui
         * fréquente qui. C'est MessageTransport.reachable qui la fournit, en mémoire.
         */
        fun fromContactAndMessage(
            contact: Contact,
            message: Message?,
            preview: String? = null,
            unreadCount: Int = 0,
            isOnline: Boolean = false
        ): ConversationItem {
            return ConversationItem(
                contact = contact,
                lastMessage = message,
                unreadCount = unreadCount,
                isOnline = isOnline,
                lastMessagePreview = preview
            )
        }

        private fun formatMessageTime(timestamp: Long): String {
            val date = java.time.Instant.ofEpochMilli(timestamp)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDate()
            val today = LocalDate.now()

            return when {
                date == today -> {
                    val time = java.time.Instant.ofEpochMilli(timestamp)
                        .atZone(java.time.ZoneId.systemDefault())
                        .toLocalTime()
                    "%02d:%02d".format(time.hour, time.minute)
                }
                date == today.minusDays(1) -> "Yesterday"
                date.year == today.year -> date.format(java.time.format.DateTimeFormatter.ofPattern("MMM d"))
                else -> date.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"))
            }
        }
    }
}

/**
 * MessageBubbleType - Type de bulle pour le styling
 */
enum class MessageBubbleType {
    SENT,        // Sombre, aligné à droite
    RECEIVED,    // Bleu léger, aligné à gauche
    SYSTEM       // Gris, centré (pour les notifications)
}
