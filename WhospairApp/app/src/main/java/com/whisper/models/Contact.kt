package com.whisper.models

import com.whisper.tor.OnionAddress

/**
 * Contact - Représentation d'une personne connue
 * Identifié par sa clé publique X25519, vérifié par sa clé Ed25519.
 *
 * Le contact ne porte AUCUNE information de présence ni de dernière connexion.
 * « En ligne » est une propriété de l'instant, pas du contact : elle vit dans
 * MessageTransport.reachable, en mémoire, et disparaît avec le processus. Un contact
 * stocké ne dit donc jamais quand on lui a parlé pour la dernière fois.
 */
data class Contact(
    val publicKeyHex: String,           // Clé publique X25519 (chiffrement)
    val signingPublicKeyHex: String = "", // Clé publique Ed25519 (vérification)
    val onionAddress: String = "",      // Où le joindre, via Tor (sans « .onion »)
    val displayName: String = "",       // Alias optionnel (par défaut: premiers 8 chars de la clé)
    val addedAt: Long = System.currentTimeMillis(),
    val isBlocked: Boolean = false,     // Bloquer = rejeter messages
    /**
     * Position du dernier message ENVOYÉ à ce contact.
     * Strictement croissante : c'est ce compteur, lié cryptographiquement au
     * message, qui interdit qu'un chiffré capturé soit rejoué.
     */
    val lastSentCounter: Long = 0,
    /**
     * Compteur le plus élevé REÇU de ce contact. Tout message d'un compteur
     * inférieur ou égal est un rejeu et doit être rejeté.
     */
    val lastReceivedCounter: Long = -1,
    /**
     * Identité confirmée : ajouté par QR ou lien, ou confirmé à la main.
     * Faux quand le contact s'est présenté seul (il avait notre lien) :
     * on garde ses messages, mais l'app le signale jusqu'à confirmation.
     */
    val isTrusted: Boolean = false,
    /**
     * Masquage de la lecture : sel ‖ empreinte Argon2 du code à 4 chiffres,
     * en hex. Vide = conversation lisible après un retour.
     */
    val readLock: String = "",
    /**
     * Sa photo de profil, JPEG 256 px réencodé chez nous. Envoyée par le
     * contact lui-même à l'ouverture du flux ; null = avatar neutre.
     */
    val photo: ByteArray? = null,
    /**
     * Mode privé : on ne lui ouvre aucun canal de soi-même et on refuse le
     * sien — il nous voit hors ligne. Le canal s'ouvre quand NOUS écrivons ou
     * appelons, et se ferme après une minute sans échange. Local, jamais envoyé.
     */
    val isPrivate: Boolean = false,
    /** Durée de vie choisie pour cette conversation, gardée d'une ouverture à l'autre ; 0 = celle des Paramètres. */
    val ttlMinutes: Int = 0,
    /**
     * Vérifié en personne (1.3.7) : on a scanné son code de sécurité et il
     * correspond. Revient à faux si ses clés changent. Local, jamais envoyé.
     */
    val isVerified: Boolean = false,
    /** Effacement à deux (1.3.7) : WIPE_OFF, WIPE_ASKED, WIPE_TO_ANSWER ou WIPE_ON. */
    val wipeState: Int = WIPE_OFF,
    /** Trame d'effacement à deux qui n'a pas pu partir (WIPE_SEND_*), renvoyée à la prochaine ouverture du flux. */
    val wipeOutbox: Int = 0,
    /** Un « tout effacer » qui n'a pas pu partir. */
    val wipePending: Boolean = false,
    /** Épinglée en haut de la liste (1.3.8). Local, sans date. */
    val isPinned: Boolean = false
) {

    val hasReadLock: Boolean get() = readLock.isNotEmpty()

    /**
     * Nom affiché (alias ou abbréviation clé)
     */
    val displayLabel: String
        get() = if (displayName.isNotBlank()) displayName else "User ${publicKeyHex.take(8)}"

    /**
     * Valide que la clé publique est bien formée
     */
    fun isValid(): Boolean {
        return publicKeyHex.length == 64 && publicKeyHex.matches(HEX) &&
                signingPublicKeyHex.length == 64 && signingPublicKeyHex.matches(HEX) &&
                OnionAddress.isValid(onionAddress)
    }


    companion object {
        /** Désactivé. */
        const val WIPE_OFF = 0
        /** Nous l'avons proposé ; il n'a pas encore accepté. */
        const val WIPE_ASKED = 1
        /** Il l'a proposé ; à nous d'accepter ou de refuser. */
        const val WIPE_TO_ANSWER = 2
        /** Les deux ont consenti : chacun peut tout effacer, pour les deux. */
        const val WIPE_ON = 3

        const val WIPE_SEND_REQ = 1
        const val WIPE_SEND_OK = 2
        const val WIPE_SEND_OFF = 3

        /**
         * Crée un contact depuis une chaîne QR (clé publique)
         */
        private val HEX = Regex("[0-9a-fA-F]+")

        fun fromPublicKeys(
            publicKeyHex: String,
            signingPublicKeyHex: String,
            onionAddress: String,
            alias: String = ""
        ): Contact? = Contact(
            publicKeyHex = publicKeyHex.lowercase(),
            signingPublicKeyHex = signingPublicKeyHex.lowercase(),
            onionAddress = onionAddress.lowercase(),
            displayName = alias,
            isTrusted = true
        ).takeIf { it.isValid() }
    }
}
