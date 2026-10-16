package com.whisper.repositories

import androidx.room.withTransaction
import com.whisper.database.MessageDao
import com.whisper.files.FilePayload
import com.whisper.security.Vault
import com.whisper.database.MessageEntity
import com.whisper.models.Message
import com.whisper.models.MessageStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Repository pour gérer les messages éphémères
 * Auto-cleanup des messages expirés via TTL
 */
class MessageRepository(private val vault: Vault) {

    // Résolu à chaque appel : la base n'existe qu'une fois le coffre ouvert.
    private val messageDao: MessageDao get() = vault.database.messageDao()

    /**
     * Ajoute un nouveau message
     */
    suspend fun addMessage(message: Message): Boolean {
        return if (message.id.isNotBlank() && message.content.isNotEmpty()) {
            messageDao.insert(MessageEntity.fromMessage(message))
            true
        } else {
            false
        }
    }

    /**
     * Range un message reçu. false si son identifiant est déjà pris : il est
     * alors écarté, jamais substitué à l'existant.
     */
    suspend fun addIncoming(message: Message): Boolean =
        messageDao.insertIncoming(MessageEntity.fromMessage(message)) != -1L

    /** Pour l'écran de chat : morceaux de fichier sans contenu une fois leur en-tête gardé. */
    fun observeConversationLight(publicKeyA: String, publicKeyB: String): Flow<List<Message>> =
        messageDao.observeConversationLight(publicKeyA, publicKeyB, FilePayload.PARTIAL_ID_PREFIX).map { entities ->
            entities.map { it.toMessage() }.filter { !it.isExpired() }
        }

    suspend fun contentsOf(ids: List<String>): Map<String, ByteArray> =
        ids.chunked(500).flatMap { messageDao.getContents(it) }.associate { it.id to it.content }

    /** En-têtes de morceaux reçus, gardés en une seule transaction. */
    suspend fun keepHeaders(headers: Map<String, ByteArray>) = vault.database.withTransaction {
        headers.forEach { (id, sealed) -> messageDao.keepHeader(id, sealed) }
    }

    fun observeChanges(): Flow<String> = messageDao.observeChanges().distinctUntilChanged()

    suspend fun lastVisible(me: String, contact: String): Message? = messageDao.lastVisible(me, contact, FilePayload.PARTIAL_ID_PREFIX)?.toMessage()

    suspend fun unreadFrom(me: String, contact: String): List<Message> =
        messageDao.unreadFrom(me, contact, MessageStatus.DELIVERED.name, FilePayload.PARTIAL_ID_PREFIX).map { it.toMessage() }

    private suspend fun updateMessageStatus(messageId: String, newStatus: MessageStatus) {
        messageDao.updateMessageStatus(messageId, newStatus.name)
    }

    /** Issue d'un envoi : SENT ou FAILED, sauf si l'accusé est déjà arrivé. */
    suspend fun updateStatus(messageId: String, status: MessageStatus) =
        messageDao.settlePending(messageId, status.name)

    /** Accusé de réception du contact. @return true si un message a changé. */
    suspend fun markDeliveredBy(messageId: String, me: String, contact: String): Boolean =
        messageDao.markDelivered(messageId, me.lowercase(), contact.lowercase()) > 0

    /** Nos messages en attente vers ce contact, dans l'ordre des compteurs. */
    suspend fun pendingTo(me: String, contact: String): List<Message> =
        messageDao.pendingTo(me.lowercase(), contact.lowercase()).map { it.toMessage() }

    /** Contacts à qui il reste quelque chose à envoyer. */
    suspend fun pendingRecipients(me: String): List<String> =
        messageDao.pendingRecipients(me.lowercase())

    /** Envoyés sans accusé : à renvoyer (le contact accuse les doublons sans les réécrire). */
    suspend fun requeueUnacked(me: String, contact: String): Int =
        messageDao.requeueUnacked(me.lowercase(), contact.lowercase())

    /** Ce message de ce contact est-il déjà rangé ? */
    suspend fun isReceivedFrom(messageId: String, contact: String): Boolean =
        messageDao.getMessageById(messageId)?.fromPublicKeyHex.equals(contact, ignoreCase = true)

    /**
     * Marque un message comme lu
     */
    suspend fun markMessageAsRead(messageId: String) {
        updateMessageStatus(messageId, MessageStatus.READ)
    }

    /**
     * Marque un message comme livré
     */
    suspend fun markMessageAsDelivered(messageId: String) {
        updateMessageStatus(messageId, MessageStatus.DELIVERED)
    }

    /**
     * Supprime un message manuellement
     */
    /** Effacé « pour tous » par son auteur : seulement ses messages, adressés à nous. */
    suspend fun deleteReceived(ids: List<String>, from: String, to: String): Int =
        messageDao.deleteFrom(ids, from.lowercase(), to.lowercase())

    suspend fun deleteMessage(messageId: String) {
        val message = messageDao.getMessageById(messageId)
        if (message != null) {
            messageDao.delete(message)
        }
    }

    /**
     * Supprime une conversation entière
     */
    suspend fun deleteConversation(publicKeyA: String, publicKeyB: String) {
        messageDao.deleteConversation(publicKeyA, publicKeyB)
    }

    /**
     * Nettoie automatiquement les messages expirés
     * Retourne le nombre de messages supprimés
     */
    suspend fun cleanupExpiredMessages(): Int {
        return messageDao.deleteExpiredMessages()
    }

    /** Nouvelle identité : aucun message ne survit à l'ancienne. */
    suspend fun deleteAll() {
        messageDao.deleteAll()
    }
}
