package com.whisper.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.whisper.models.Message
import com.whisper.models.MessageStatus

/**
 * Message Entity pour Room Database
 * Clé primaire = id (UUID)
 * Index sur: expiresAt (pour cleanup TTL), fromPublicKeyHex/toPublicKeyHex (pour filtrage)
 *
 * Pas de clé étrangère vers `contacts` : chaque message a NOTRE clé d'un côté
 * (expéditeur ou destinataire), et nous ne sommes jamais notre propre contact.
 * Les deux contraintes d'avant faisaient donc échouer TOUTE insertion — aucun
 * message, envoyé ou reçu, ne pouvait être enregistré.
 */
@Entity(
    tableName = "messages",
    indices = [
        Index("expiresAt"),
        Index("fromPublicKeyHex"),
        Index("toPublicKeyHex"),
        Index(value = ["fromPublicKeyHex", "toPublicKeyHex"], name = "idx_conversation")
    ]
)
data class MessageEntity(
    @PrimaryKey
    val id: String,
    val content: ByteArray,             // Chiffré
    val fromPublicKeyHex: String,
    val toPublicKeyHex: String,
    val timestamp: Long,
    val expiresAt: Long,
    val status: String = "PENDING",     // Stocké en String dans Room
    val nonce: ByteArray? = null,       // Nonce GCM (12 bytes)
    val tag: ByteArray? = null,         // Tag GCM (16 bytes)
    val ephemeralPublicKey: ByteArray? = null, // Clé éphémère X25519 (32 bytes)
    val signature: ByteArray? = null,          // Signature Ed25519 (64 bytes)
    val counter: Long = 0,                     // Compteur anti-rejeu
    val selfCopy: ByteArray? = null,           // Notre copie d'un envoi, chiffrée
    @androidx.room.ColumnInfo(defaultValue = "0")
    val receivedAt: Long = 0                   // Réception, notre horloge (1.3.8)
) {
    /**
     * Convertit en modèle Message
     */
    fun toMessage(): Message = Message(
        id = id,
        content = content,
        fromPublicKeyHex = fromPublicKeyHex,
        toPublicKeyHex = toPublicKeyHex,
        timestamp = timestamp,
        expiresAt = expiresAt,
        status = runCatching { MessageStatus.valueOf(status) }
            .getOrDefault(MessageStatus.FAILED),
        nonce = nonce,
        tag = tag,
        ephemeralPublicKey = ephemeralPublicKey,
        signature = signature,
        counter = counter,
        selfCopy = selfCopy,
        receivedAt = receivedAt
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MessageEntity) return false
        return id == other.id &&
                content.contentEquals(other.content) &&
                fromPublicKeyHex == other.fromPublicKeyHex &&
                toPublicKeyHex == other.toPublicKeyHex &&
                timestamp == other.timestamp &&
                expiresAt == other.expiresAt &&
                status == other.status
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + fromPublicKeyHex.hashCode()
        result = 31 * result + toPublicKeyHex.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + expiresAt.hashCode()
        result = 31 * result + status.hashCode()
        return result
    }

    companion object {
        /**
         * Crée une entity depuis un modèle Message
         */
        fun fromMessage(message: Message): MessageEntity = MessageEntity(
            id = message.id,
            content = message.content,
            fromPublicKeyHex = message.fromPublicKeyHex,
            toPublicKeyHex = message.toPublicKeyHex,
            timestamp = message.timestamp,
            expiresAt = message.expiresAt,
            status = message.status.name,
            nonce = message.nonce,
            tag = message.tag,
            ephemeralPublicKey = message.ephemeralPublicKey,
            signature = message.signature,
            counter = message.counter,
            selfCopy = message.selfCopy,
            receivedAt = message.receivedAt
        )
    }
}
