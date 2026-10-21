package com.whisper.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * PendingContactLink - Relais entre un lien profond entrant et l'UI.
 *
 * La première version stockait la clé dans un `var` statique de MainActivity :
 * elle était bien écrite, mais rien ne la lisait et Compose ne pouvait pas
 * réagir à un simple champ mutable. Le lien profond ne menait donc nulle part.
 *
 * Un StateFlow règle les deux problèmes : observable, et consommable une seule
 * fois. Ne contient qu'une chaîne de 64 caractères, aucun risque de fuite de
 * contexte.
 */
object PendingContactLink {

    /** Identité complète en attente : clé de chiffrement + clé de signature. */
    data class PendingIdentity(
        val publicKeyHex: String,
        val signingPublicKeyHex: String,
        val onionAddress: String,
        /** Scanné en face (QR) : fait foi. Sinon : lien, qui peut venir de n'importe qui. */
        val scanned: Boolean
    ) {
        /** Forme concaténée, telle qu'elle apparaît dans un QR ou un lien. */
        fun asPayload(): String = publicKeyHex + signingPublicKeyHex + onionAddress
    }

    private val _pending = MutableStateFlow<PendingIdentity?>(null)
    val pending: StateFlow<PendingIdentity?> = _pending.asStateFlow()

    /**
     * Dépose une clé reçue par lien profond.
     *
     * Ajouté dès l'app déverrouillée (WhisperApp). N'importe quelle page web
     * ou app peut faire ouvrir un lien : un contact venu par lien arrive
     * « non confirmé » (bandeau Confirmer / Bloquer) et ne touche jamais à
     * un contact déjà connu. Seul le scan du QR fait foi.
     */
    fun submit(identity: ContactKeyParser.Result.Valid, scanned: Boolean) {
        _pending.value = PendingIdentity(
            identity.publicKeyHex, identity.signingPublicKeyHex, identity.onionAddress, scanned
        )
    }

    /**
     * Récupère la clé et la retire, pour qu'un retour sur l'écran ne
     * repropose pas indéfiniment le même contact.
     */
    fun consume(): PendingIdentity? = _pending.value.also { _pending.value = null }

    fun clear() {
        _pending.value = null
    }
}
