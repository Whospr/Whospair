package com.whisper.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.whisper.models.Contact

/**
 * Contact Entity pour Room Database
 * Clé primaire = publicKeyHex (unique)
 *
 * Deux colonnes ont été retirées en phase 6 : `isOnline` et `lastConnectionAt`.
 * La présence est désormais tenue en mémoire par MessageTransport.reachable.
 *
 * La raison n'est pas technique. `lastConnectionAt` inscrivait dans la base,
 * conversation par conversation, la date du dernier échange — autrement dit qui
 * a parlé à qui, et quand. Le chiffrement de la base couvre le vol d'un
 * appareil éteint ; il ne couvre pas une saisie avec l'appareil ouvert. Dans
 * une app dont la promesse est de ne rien laisser derrière elle, cette colonne
 * était la trace la plus parlante du fichier.
 */
@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey
    val publicKeyHex: String,
    val displayName: String = "",
    val addedAt: Long,
    val isBlocked: Boolean = false,
    val signingPublicKeyHex: String = "",
    val onionAddress: String = "",
    val lastSentCounter: Long = 0,
    val lastReceivedCounter: Long = -1,
    val isTrusted: Boolean = false,
    val readLock: String = "",
    val photo: ByteArray? = null,
    @ColumnInfo(defaultValue = "0")
    val isPrivate: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    val ttlMinutes: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val isVerified: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    val wipeState: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val wipeOutbox: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val wipePending: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    val isPinned: Boolean = false
) {
    /**
     * Convertit en modèle Contact
     */
    fun toContact(): Contact = Contact(
        publicKeyHex = publicKeyHex,
        displayName = displayName,
        addedAt = addedAt,
        isBlocked = isBlocked,
        signingPublicKeyHex = signingPublicKeyHex,
        onionAddress = onionAddress,
        lastSentCounter = lastSentCounter,
        lastReceivedCounter = lastReceivedCounter,
        isTrusted = isTrusted,
        readLock = readLock,
        photo = photo,
        isPrivate = isPrivate,
        ttlMinutes = ttlMinutes,
        isVerified = isVerified,
        wipeState = wipeState,
        wipeOutbox = wipeOutbox,
        wipePending = wipePending,
        isPinned = isPinned
    )

    companion object {
        /**
         * Crée une entity depuis un modèle Contact
         */
        fun fromContact(contact: Contact): ContactEntity = ContactEntity(
            publicKeyHex = contact.publicKeyHex,
            displayName = contact.displayName,
            addedAt = contact.addedAt,
            isBlocked = contact.isBlocked,
            signingPublicKeyHex = contact.signingPublicKeyHex,
            onionAddress = contact.onionAddress,
            lastSentCounter = contact.lastSentCounter,
            lastReceivedCounter = contact.lastReceivedCounter,
            isTrusted = contact.isTrusted,
            readLock = contact.readLock,
            photo = contact.photo,
            isPrivate = contact.isPrivate,
            ttlMinutes = contact.ttlMinutes,
            isVerified = contact.isVerified,
            wipeState = contact.wipeState,
            wipeOutbox = contact.wipeOutbox,
            wipePending = contact.wipePending,
            isPinned = contact.isPinned
        )
    }
}
