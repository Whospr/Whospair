package com.whisper.models

/**
 * État d'un message
 */
enum class MessageStatus {
    PENDING,      // En attente d'envoi
    SENT,         // Envoyé au contact
    DELIVERED,    // Reçu par le contact
    READ,         // Lu par le contact
    FAILED,       // Erreur d'envoi
    DELETED       // Expiré (TTL)
}

/**
 * Message éphémère avec TTL (Time To Live)
 * Chiffré de bout en bout via AES-256-GCM
 */
data class Message(
    val id: String,                     // UUID unique
    val content: ByteArray,             // Contenu chiffré
    val fromPublicKeyHex: String,       // Expéditeur (notre clé ou celle du contact)
    val toPublicKeyHex: String,         // Destinataire
    val timestamp: Long = System.currentTimeMillis(),
    val expiresAt: Long,                // Timestamp d'expiration (timestamp + TTL)
    val status: MessageStatus = MessageStatus.PENDING,
    val nonce: ByteArray? = null,       // Nonce GCM (12 bytes)
    val tag: ByteArray? = null,         // Tag GCM (16 bytes)
    /**
     * Clé publique X25519 éphémère de l'expéditeur (32 bytes).
     * Sans elle, le destinataire ne peut pas recalculer le secret ECDH
     * et le message est définitivement illisible.
     */
    val ephemeralPublicKey: ByteArray? = null,
    /**
     * Signature Ed25519 de l'expéditeur sur le contexte du message.
     * Sans elle, n'importe qui peut écrire en se faisant passer pour un contact.
     */
    val signature: ByteArray? = null,
    /**
     * Position dans la suite des messages échangés avec ce contact.
     * Liée cryptographiquement au chiffré : c'est ce qui interdit le rejeu.
     */
    val counter: Long = 0,
    /**
     * NOTRE copie d'un message envoyé, chiffrée par IdentityService.sealLocal.
     * Jamais transmise (MessageTransport.encode l'ignore).
     */
    val selfCopy: ByteArray? = null,
    /**
     * Heure de RÉCEPTION, sur notre horloge (1.3.8) ; 0 pour nos envois.
     * Le tri des conversations s'en sert : l'heure d'envoi vient de l'horloge
     * du contact, qui peut avancer ou retarder.
     */
    val receivedAt: Long = 0
) {
    /** Ce qui place la conversation dans la liste : réception, sinon envoi. */
    val sortTime: Long get() = if (receivedAt > 0) receivedAt else timestamp

    /**
     * Vérifie si le message a expiré
     */
    fun isExpired(): Boolean = System.currentTimeMillis() >= expiresAt

    /**
     * Calcule le TTL restant en millisecondes
     */
    fun getRemainingTtlMs(): Long = maxOf(0, expiresAt - System.currentTimeMillis())

    /**
     * Retourne une copie avec un nouveau statut
     */
    fun withStatus(newStatus: MessageStatus): Message = copy(status = newStatus)

    /**
     * Override equals pour ignorer les ByteArrays (comparaison de contenu)
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Message) return false
        return id == other.id &&
                content.contentEquals(other.content) &&
                fromPublicKeyHex == other.fromPublicKeyHex &&
                toPublicKeyHex == other.toPublicKeyHex &&
                timestamp == other.timestamp &&
                expiresAt == other.expiresAt &&
                status == other.status
    }

    /**
     * Override hashCode pour ignorer les ByteArrays
     */
    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + fromPublicKeyHex.hashCode()
        result = 31 * result + toPublicKeyHex.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + expiresAt.hashCode()
        result = 31 * result + status.hashCode()
        return result
    }
}
