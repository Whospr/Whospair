package com.whisper.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * DAO pour accéder aux contacts
 */
@Dao
interface ContactDao {

    /**
     * Insère un nouveau contact
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(contact: ContactEntity)

    /**
     * Met à jour un contact existant
     */
    @Update
    suspend fun update(contact: ContactEntity)

    /**
     * Supprime un contact
     */
    @Delete
    suspend fun delete(contact: ContactEntity)

    /**
     * Récupère un contact par sa clé publique
     */
    @Query("SELECT * FROM contacts WHERE publicKeyHex = :publicKeyHex")
    suspend fun getContactByKey(publicKeyHex: String): ContactEntity?

    /**
     * Récupère tous les contacts
     */
    @Query("SELECT * FROM contacts ORDER BY addedAt DESC")
    suspend fun getAllContacts(): List<ContactEntity>

    /**
     * Observe tous les contacts (pour UI réactive)
     */
    @Query("SELECT * FROM contacts ORDER BY addedAt DESC")
    fun observeAllContacts(): Flow<List<ContactEntity>>

    // Les requêtes de présence ont disparu avec les colonnes isOnline et
    // lastConnectionAt : demander « qui est en ligne » à la base reviendrait à
    // lire une valeur périmée, et la stocker reviendrait à tenir un journal de
    // qui parle à qui. MessageTransport.reachable répond à cette question en mémoire.

    /**
     * Incrémente et renvoie le compteur d'envoi de façon atomique.
     * Un lire-puis-écrire depuis le code applicatif laisserait deux envois
     * simultanés réutiliser le même compteur — ce qui casserait la garantie
     * anti-rejeu au moment précis où elle compte.
     */
    @Query("UPDATE contacts SET lastSentCounter = lastSentCounter + 1 WHERE publicKeyHex = :publicKeyHex")
    suspend fun incrementSentCounter(publicKeyHex: String)

    @Query("SELECT lastSentCounter FROM contacts WHERE publicKeyHex = :publicKeyHex")
    suspend fun getSentCounter(publicKeyHex: String): Long?

    /**
     * N'avance jamais à reculons : MAX protège contre un message arrivé
     * dans le désordre qui ferait régresser la borne.
     */
    @Query("UPDATE contacts SET lastReceivedCounter = MAX(lastReceivedCounter, :counter) WHERE publicKeyHex = :publicKeyHex")
    suspend fun updateReceivedCounter(publicKeyHex: String, counter: Long)

    /**
     * Contact venu seul. IGNORE, jamais REPLACE : deux connexions simultanées
     * du même inconnu remettraient sinon ses compteurs à zéro.
     * @return -1 s'il existait déjà.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(contact: ContactEntity): Long

    @Query("SELECT COUNT(*) FROM contacts WHERE isTrusted = 0")
    suspend fun countUntrusted(): Int

    @Query("SELECT * FROM contacts WHERE publicKeyHex = :publicKeyHex")
    fun observeContact(publicKeyHex: String): Flow<ContactEntity?>

    @Query("UPDATE contacts SET displayName = :name WHERE publicKeyHex = :publicKeyHex")
    suspend fun rename(publicKeyHex: String, name: String)

    @Query("UPDATE contacts SET isTrusted = 1 WHERE publicKeyHex = :publicKeyHex")
    suspend fun markTrusted(publicKeyHex: String)

    @Query("UPDATE contacts SET readLock = :readLock WHERE publicKeyHex = :publicKeyHex")
    suspend fun setReadLock(publicKeyHex: String, readLock: String)

    @Query("UPDATE contacts SET ttlMinutes = :minutes WHERE publicKeyHex = :publicKeyHex")
    suspend fun setTtl(publicKeyHex: String, minutes: Int)

    @Query("UPDATE contacts SET isPrivate = :isPrivate WHERE publicKeyHex = :publicKeyHex")
    suspend fun setPrivate(publicKeyHex: String, isPrivate: Boolean)

    @Query("UPDATE contacts SET isVerified = :verified WHERE publicKeyHex = :publicKeyHex")
    suspend fun setVerified(publicKeyHex: String, verified: Boolean)

    @Query("UPDATE contacts SET wipeState = :state WHERE publicKeyHex = :publicKeyHex")
    suspend fun setWipeState(publicKeyHex: String, state: Int)

    @Query("UPDATE contacts SET wipeOutbox = :outbox WHERE publicKeyHex = :publicKeyHex")
    suspend fun setWipeOutbox(publicKeyHex: String, outbox: Int)

    @Query("UPDATE contacts SET isPinned = :pinned WHERE publicKeyHex = :publicKeyHex")
    suspend fun setPinned(publicKeyHex: String, pinned: Boolean)

    @Query("UPDATE contacts SET wipePending = :pending WHERE publicKeyHex = :publicKeyHex")
    suspend fun setWipePending(publicKeyHex: String, pending: Boolean)

    @Query("UPDATE contacts SET photo = :photo WHERE publicKeyHex = :publicKeyHex")
    suspend fun setPhoto(publicKeyHex: String, photo: ByteArray?)

    @Query("DELETE FROM contacts")
    suspend fun deleteAll()

    @Query("DELETE FROM contacts WHERE publicKeyHex = :publicKeyHex")
    suspend fun deleteByKey(publicKeyHex: String)

    @Query("UPDATE contacts SET isBlocked = :isBlocked WHERE publicKeyHex = :publicKeyHex")
    suspend fun updateBlockStatus(publicKeyHex: String, isBlocked: Boolean)
}
