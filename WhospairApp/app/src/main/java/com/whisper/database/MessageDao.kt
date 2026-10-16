package com.whisper.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * DAO pour accéder aux messages éphémères
 */
@Dao
interface MessageDao {

    /**
     * Insère un nouveau message
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    /**
     * Insère un message REÇU. IGNORE, jamais REPLACE : l'identifiant est
     * choisi par l'expéditeur, et REPLACE lui permettait d'écraser n'importe
     * quel message déjà stocké (les nôtres compris) en réutilisant son id.
     *
     * @return -1 si un message de même id existait déjà (rien n'est écrit).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIncoming(message: MessageEntity): Long

    /**
     * Met à jour un message existant
     */
    @Update
    suspend fun update(message: MessageEntity)

    /**
     * Supprime un message
     */
    @Delete
    suspend fun delete(message: MessageEntity)

    /**
     * Récupère un message par ID
     */
    @Query("SELECT * FROM messages WHERE id = :messageId")
    suspend fun getMessageById(messageId: String): MessageEntity?

    /**
     * Conversation pour l'affichage. Un morceau de fichier dont l'en-tête est
     * déjà gardé (selfCopy) arrive sans son contenu : relire des Mo de
     * morceaux à chaque changement rendait l'écran lent. Le contenu se lit à
     * l'ouverture du fichier (getContents).
     */
    @Query("""
        SELECT id,
               CASE WHEN substr(id, 1, length(:partialPrefix)) = :partialPrefix AND selfCopy IS NOT NULL THEN X'' ELSE content END AS content,
               fromPublicKeyHex, toPublicKeyHex, timestamp, expiresAt, status,
               nonce, tag, ephemeralPublicKey, signature, counter, selfCopy, receivedAt
        FROM messages
        WHERE (fromPublicKeyHex = :publicKeyA AND toPublicKeyHex = :publicKeyB)
           OR (fromPublicKeyHex = :publicKeyB AND toPublicKeyHex = :publicKeyA)
        ORDER BY timestamp ASC
    """)
    fun observeConversationLight(publicKeyA: String, publicKeyB: String, partialPrefix: String): Flow<List<MessageEntity>>

    @Query("SELECT id, content FROM messages WHERE id IN (:ids)")
    suspend fun getContents(ids: List<String>): List<IdContent>

    /** Garde l'en-tête d'un morceau reçu, scellé par la clé locale. */
    @Query("UPDATE messages SET selfCopy = :sealed WHERE id = :messageId AND selfCopy IS NULL")
    suspend fun keepHeader(messageId: String, sealed: ByteArray)

    /**
     * Signal de changement pour la liste : une ligne, relue à chaque écriture.
     * Seul ce que la liste montre y entre (nombre, dernier horodatage, états) :
     * garder un en-tête ne change pas la valeur.
     */
    @Query("""
        SELECT (SELECT COUNT(*) || ':' || IFNULL(MAX(timestamp), 0) FROM messages) || ':' ||
               IFNULL((SELECT group_concat(status || '=' || n) FROM
                   (SELECT status, COUNT(*) AS n FROM messages GROUP BY status ORDER BY status)), '')
    """)
    fun observeChanges(): Flow<String>

    /** Dernier message visible (les morceaux reçus non finaux ne comptent pas). */
    @Query("""
        SELECT * FROM messages
        WHERE ((fromPublicKeyHex = :me AND toPublicKeyHex = :contact)
            OR (fromPublicKeyHex = :contact AND toPublicKeyHex = :me))
          AND NOT (toPublicKeyHex = :me AND substr(id, 1, length(:partialPrefix)) = :partialPrefix)
        ORDER BY CASE WHEN receivedAt > 0 THEN receivedAt ELSE timestamp END DESC LIMIT 1
    """)
    suspend fun lastVisible(me: String, contact: String, partialPrefix: String): MessageEntity?

    /** Non-lus visibles : un fichier compte par son dernier morceau. */
    @Query("""
        SELECT * FROM messages
        WHERE fromPublicKeyHex = :contact AND toPublicKeyHex = :me
          AND status = :delivered AND substr(id, 1, length(:partialPrefix)) != :partialPrefix
    """)
    suspend fun unreadFrom(me: String, contact: String, delivered: String, partialPrefix: String): List<MessageEntity>

    @Query("UPDATE messages SET status = :newStatus WHERE id = :messageId")
    suspend fun updateMessageStatus(messageId: String, newStatus: String)

    @Query("UPDATE messages SET status = :newStatus WHERE status = :oldStatus")
    suspend fun replaceStatus(oldStatus: String, newStatus: String)

    /** Issue d'un envoi : ne touche qu'un message encore en cours (un accusé a pu passer avant). */
    @Query("UPDATE messages SET status = :newStatus WHERE id = :messageId AND status = 'PENDING'")
    suspend fun settlePending(messageId: String, newStatus: String)

    /**
     * Accusé de réception. Seulement un message que NOUS avons envoyé à CE
     * contact : un pair ne peut pas marquer la conversation d'un autre.
     * FAILED compris : l'écriture a pu échouer après que les octets sont partis.
     */
    @Query("""
        UPDATE messages SET status = 'DELIVERED'
        WHERE id = :messageId AND fromPublicKeyHex = :me AND toPublicKeyHex = :contact
          AND status IN ('PENDING', 'SENT', 'FAILED')
    """)
    suspend fun markDelivered(messageId: String, me: String, contact: String): Int

    @Query("DELETE FROM messages WHERE id IN (:ids) AND fromPublicKeyHex = :from AND toPublicKeyHex = :to")
    suspend fun deleteFrom(ids: List<String>, from: String, to: String): Int

    /**
     * Supprime tous les messages expirés
     */
    /**
     * Nos messages en attente vers ce contact, dans l'ordre des compteurs :
     * l'autre refuse tout compteur déjà dépassé. Signés seulement : une ligne
     * locale (trace d'appel) n'est jamais transmise.
     */
    @Query("""
        SELECT * FROM messages
        WHERE fromPublicKeyHex = :me AND toPublicKeyHex = :contact AND status = 'PENDING'
          AND signature IS NOT NULL
        ORDER BY counter ASC
    """)
    suspend fun pendingTo(me: String, contact: String): List<MessageEntity>

    @Query("SELECT DISTINCT toPublicKeyHex FROM messages WHERE fromPublicKeyHex = :me AND status = 'PENDING' AND signature IS NOT NULL")
    suspend fun pendingRecipients(me: String): List<String>

    /**
     * Partis mais jamais accusés : remis en attente, à renvoyer sur un flux neuf.
     * Pas la trace d'un appel (locale, sans signature, rangée SENT) : renvoyée
     * à chaque flux, elle faisait biper `:reach` pour rien.
     */
    @Query("""
        UPDATE messages SET status = 'PENDING'
        WHERE fromPublicKeyHex = :me AND toPublicKeyHex = :contact AND status = 'SENT'
          AND signature IS NOT NULL
    """)
    suspend fun requeueUnacked(me: String, contact: String): Int

    @Query("DELETE FROM messages WHERE expiresAt <= :now")
    suspend fun deleteExpiredMessages(now: Long = System.currentTimeMillis()): Int

    /**
     * Supprime tous les messages d'une conversation
     */
    @Query("""
        DELETE FROM messages
        WHERE (fromPublicKeyHex = :publicKeyA AND toPublicKeyHex = :publicKeyB)
           OR (fromPublicKeyHex = :publicKeyB AND toPublicKeyHex = :publicKeyA)
    """)
    suspend fun deleteConversation(publicKeyA: String, publicKeyB: String)

    @Query("DELETE FROM messages")
    suspend fun deleteAll()
}

class IdContent(val id: String, val content: ByteArray)
