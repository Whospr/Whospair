package com.whisper.repositories

import com.whisper.database.ContactDao
import com.whisper.security.PinCrypto
import com.whisper.security.Vault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.util.encoders.Hex
import com.whisper.database.ContactEntity
import com.whisper.models.Contact
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Repository pour gérer les contacts
 * Abstrait la logique d'accès à la base de données
 */
class ContactRepository(private val vault: Vault) {

    // Résolu à chaque appel : la base n'existe qu'une fois le coffre ouvert.
    private val contactDao: ContactDao get() = vault.database.contactDao()

    /**
     * Ajoute un nouveau contact
     */
    suspend fun addContact(contact: Contact): Boolean {
        return if (contact.isValid()) {
            // Rescanné avec les mêmes clés : il reste vérifié. Clés changées :
            // la vérification tombe (le nouvel enregistrement part de faux).
            val old = contactDao.getContactByKey(contact.publicKeyHex)?.toContact()
            val keep = old != null && old.isVerified &&
                old.signingPublicKeyHex.equals(contact.signingPublicKeyHex, ignoreCase = true)
            contactDao.insert(ContactEntity.fromContact(if (keep) contact.copy(isVerified = true) else contact))
            true
        } else {
            false
        }
    }

    /**
     * Récupère un contact par sa clé publique
     */
    suspend fun getContact(publicKeyHex: String): Contact? {
        return contactDao.getContactByKey(publicKeyHex)?.toContact()
    }

    /**
     * Récupère tous les contacts
     */
    suspend fun getAllContacts(): List<Contact> {
        return contactDao.getAllContacts().map { it.toContact() }
    }

    /**
     * Observe tous les contacts (pour UI réactive)
     */
    fun observeAllContacts(): Flow<List<Contact>> {
        return contactDao.observeAllContacts().map { entities ->
            entities.map { it.toContact() }
        }
    }

    // La présence ne transite plus par le dépôt : elle n'est pas une propriété
    // persistée du contact. Voir MessageTransport.reachable.

    fun observeContact(publicKeyHex: String): Flow<Contact?> =
        contactDao.observeContact(publicKeyHex).map { it?.toContact() }

    /**
     * Contact qui s'est présenté seul (il avait notre lien), non confirmé.
     *
     * Plafonné : n'importe qui peut fabriquer des identités à la chaîne, et
     * sans plafond remplirait le carnet. Au-delà, les nouveaux inconnus sont
     * raccrochés jusqu'à ce que l'utilisateur fasse le tri.
     *
     * @return false si refusé (plafond atteint, contact invalide).
     */
    suspend fun addUnconfirmed(contact: Contact): Boolean {
        val candidate = contact.copy(isTrusted = false, displayName = "")
        if (!candidate.isValid()) return false
        if (contactDao.countUntrusted() >= MAX_UNCONFIRMED) return false
        contactDao.insertIfAbsent(ContactEntity.fromContact(candidate))
        return true
    }

    /** Nom local : rien ne part chez le contact. Vide = alias par défaut. */
    suspend fun rename(publicKeyHex: String, name: String) {
        contactDao.rename(publicKeyHex, name.trim().take(MAX_NAME_LENGTH))
    }

    /** Photo reçue du contact (déjà validée et réencodée), ou null s'il l'a retirée. */
    suspend fun setVerified(publicKeyHex: String, verified: Boolean) = contactDao.setVerified(publicKeyHex, verified)

    suspend fun setWipeState(publicKeyHex: String, state: Int) = contactDao.setWipeState(publicKeyHex, state)

    suspend fun setWipeOutbox(publicKeyHex: String, outbox: Int) = contactDao.setWipeOutbox(publicKeyHex, outbox)

    suspend fun setPinned(publicKeyHex: String, pinned: Boolean) = contactDao.setPinned(publicKeyHex, pinned)

    suspend fun setWipePending(publicKeyHex: String, pending: Boolean) = contactDao.setWipePending(publicKeyHex, pending)

    suspend fun setPhoto(publicKeyHex: String, photo: ByteArray?) {
        contactDao.setPhoto(publicKeyHex.lowercase(), photo)
    }

    suspend fun markTrusted(publicKeyHex: String) {
        contactDao.markTrusted(publicKeyHex)
    }

    /**
     * Supprime vraiment le contact. Les messages sont effacés par l'appelant
     * (MessageRepository.deleteConversation) : pas de clés étrangères.
     * S'il avait notre lien, il pourra réapparaître comme non confirmé ;
     * pour l'en empêcher, c'est bloquer.
     */
    suspend fun deleteContact(publicKeyHex: String) {
        contactDao.deleteByKey(publicKeyHex)
    }

    /**
     * Réserve le prochain compteur d'envoi pour ce contact.
     * Incrément atomique côté base, puis relecture : deux envois concurrents
     * ne peuvent pas obtenir le même numéro.
     */
    suspend fun nextSentCounter(publicKeyHex: String): Long {
        contactDao.incrementSentCounter(publicKeyHex)
        return contactDao.getSentCounter(publicKeyHex) ?: 1L
    }

    /**
     * Enregistre le compteur du dernier message accepté de ce contact.
     */
    suspend fun markReceivedCounter(publicKeyHex: String, counter: Long) {
        contactDao.updateReceivedCounter(publicKeyHex, counter)
    }

    /**
     * Bloque un contact.
     *
     * Le DAO exposait updateBlockStatus() depuis le début, mais le dépôt ne
     * proposait que le déblocage : on pouvait débloquer un contact qu'aucun
     * chemin de code n'avait jamais pu bloquer.
     */
    suspend fun blockContact(publicKeyHex: String) {
        contactDao.updateBlockStatus(publicKeyHex, true)
    }

    /** Durée de vie de la conversation, gardée d'une ouverture à l'autre. */
    suspend fun setTtl(publicKeyHex: String, minutes: Int) {
        contactDao.setTtl(publicKeyHex, minutes)
    }

    /** Mode privé de la conversation (voir Contact.isPrivate). */
    suspend fun setPrivate(publicKeyHex: String, on: Boolean) {
        contactDao.setPrivate(publicKeyHex, on)
    }

    /**
     * Débloque un contact
     */
    suspend fun unblockContact(publicKeyHex: String) {
        contactDao.updateBlockStatus(publicKeyHex, false)
    }

    /* ---------------- Masquage de la lecture ---------------- */

    /**
     * Code à 4 chiffres, propre à la conversation. Seule son empreinte
     * Argon2 est gardée, avec un sel à elle : deux conversations au même
     * code ne se ressemblent pas en base.
     *
     * Ce code protège la LECTURE face à quelqu'un qui tient le téléphone
     * déverrouillé ; les messages eux-mêmes sont déjà chiffrés par la base.
     */
    suspend fun setReadLock(publicKeyHex: String, code: CharArray) = withContext(Dispatchers.Default) {
        contactDao.setReadLock(publicKeyHex, readLockOf(code))
    }

    /** Toutes les conversations encore lisibles reçoivent ce code. */
    suspend fun setReadLockWhereMissing(code: CharArray) = withContext(Dispatchers.Default) {
        contactDao.getAllContacts().filter { it.readLock.isEmpty() }.forEach {
            contactDao.setReadLock(it.publicKeyHex, readLockOf(code))
        }
    }

    suspend fun checkReadLock(contact: Contact, code: CharArray): Boolean = withContext(Dispatchers.Default) {
        val raw = runCatching { Hex.decode(contact.readLock) }.getOrNull()
        if (raw == null || raw.size != PinCrypto.SALT_SIZE + PinCrypto.KEY_SIZE) return@withContext false
        val candidate = PinCrypto.deriveKey(code, raw.copyOfRange(0, PinCrypto.SALT_SIZE))
        PinCrypto.same(candidate, raw.copyOfRange(PinCrypto.SALT_SIZE, raw.size)).also { candidate.fill(0) }
    }

    suspend fun clearReadLock(publicKeyHex: String) {
        contactDao.setReadLock(publicKeyHex, "")
    }

    /** Nouvelle identité : les contacts connaissaient l'ancienne. */
    suspend fun deleteAll() {
        contactDao.deleteAll()
    }

    private fun readLockOf(code: CharArray): String {
        require(code.size == READ_CODE_LENGTH && code.all { it.isDigit() }) { "Code à 4 chiffres" }
        val salt = PinCrypto.randomBytes(PinCrypto.SALT_SIZE)
        val check = PinCrypto.deriveKey(code, salt)
        return Hex.toHexString(salt + check).also { check.fill(0) }
    }

    companion object {
        const val READ_CODE_LENGTH = 4
        const val MAX_UNCONFIRMED = 32
        const val MAX_NAME_LENGTH = 40
    }
}
