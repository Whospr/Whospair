package com.whisper.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whisper.models.Contact
import com.whisper.repositories.ContactRepository
import com.whisper.services.IdentityService
import com.whisper.transport.MessageTransport
import com.whisper.util.ContactKeyParser
import com.whisper.util.PendingContactLink
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import com.whisper.util.DebugLog

/**
 * Issue d'une tentative d'ajout. Chaque cas mérite un message distinct :
 * « échec » sans raison laisse l'utilisateur sans recours.
 */
sealed class AddContactOutcome {
    data class Added(val contact: Contact) : AddContactOutcome()
    data class AlreadyKnown(val contact: Contact) : AddContactOutcome()
    data class WasBlocked(val contact: Contact) : AddContactOutcome()
    data class Unblocked(val contact: Contact) : AddContactOutcome()
    object SelfKey : AddContactOutcome()
    object MalformedKey : AddContactOutcome()
    object NotAWhisperKey : AddContactOutcome()
    data class Failed(val reason: String?) : AddContactOutcome()
}

data class DiscoverViewState(
    val myPublicKeyHex: String = "",
    val mySigningPublicKeyHex: String = "",
    val myOnionAddress: String = "",
    val manualInput: String = "",
    val isAdding: Boolean = false,
    val outcome: AddContactOutcome? = null
)

/**
 * DiscoverViewModel - Ajout de contacts par QR, lien ou saisie.
 *
 * Remplace les trois suggestions codées en dur de l'écran Discover, qui
 * n'étaient reliées à aucun dépôt.
 */
@HiltViewModel
class DiscoverViewModel @Inject constructor(
    private val contactRepository: ContactRepository,
    private val identityService: IdentityService,
    private val messageTransport: MessageTransport
) : ViewModel() {

    private val _state = MutableStateFlow(DiscoverViewState())
    val state: StateFlow<DiscoverViewState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val identity = withContext(Dispatchers.IO) { identityService.getIdentity() }
            if (identity != null) {
                _state.update {
                    it.copy(
                        myPublicKeyHex = identity.publicKeyHex,
                        mySigningPublicKeyHex = identity.signingPublicKeyHex,
                        myOnionAddress = identityService.onionAddress().orEmpty()
                    )
                }
            }
        }

    }

    /**
     * Lien touché ou QR scanné : ajouté tout de suite, sans repasser par le
     * champ (1.4.1 : pré-remplir laissait croire que le lien n'avait pas
     * marché). Appelé par WhisperApp, seul lecteur de [PendingContactLink].
     */
    fun addPending(identity: PendingContactLink.PendingIdentity) {
        viewModelScope.launch {
            // Le refus de soi-même a besoin de notre clé : pas de course avec init.
            val me = withContext(Dispatchers.IO) { identityService.getIdentity() }
            if (me == null) {
                _state.update { it.copy(outcome = AddContactOutcome.Failed(null)) }
                return@launch
            }
            _state.update { it.copy(myPublicKeyHex = me.publicKeyHex) }
            val parsed = ContactKeyParser.Result.Valid(
                identity.publicKeyHex, identity.signingPublicKeyHex, identity.onionAddress
            )
            persist(parsed, alias = "", fromLink = !identity.scanned)
        }
    }

    fun onManualInputChange(value: String) {
        _state.update { it.copy(manualInput = value, outcome = null) }
    }

    /**
     * Ajoute depuis la saisie manuelle.
     */
    fun addFromManualInput(alias: String = "") {
        addFromRaw(_state.value.manualInput, alias)
    }

    /**
     * Chemin unique d'ajout : QR, lien et saisie convergent ici, donc les
     * garde-fous ne peuvent pas être contournés par l'une des entrées.
     */
    private fun addFromRaw(raw: String, alias: String) {
        if (_state.value.isAdding) return

        when (val parsed = ContactKeyParser.parse(raw)) {
            is ContactKeyParser.Result.NotAWhisperKey ->
                _state.update { it.copy(outcome = AddContactOutcome.NotAWhisperKey) }

            is ContactKeyParser.Result.MalformedKey ->
                _state.update { it.copy(outcome = AddContactOutcome.MalformedKey) }

            is ContactKeyParser.Result.Valid -> persist(parsed, alias, fromLink = false)
        }
    }

    /**
     * [fromLink] : venu par un lien touché, donc de n'importe qui. Ajouté non
     * confirmé, et jamais de confirmation ni de remplacement d'un contact déjà
     * dans le carnet — ça, seul un scan (ou un collage à la main) le fait.
     */
    private fun persist(parsed: ContactKeyParser.Result.Valid, alias: String, fromLink: Boolean) {
        val publicKeyHex = parsed.publicKeyHex
        viewModelScope.launch {
            _state.update { it.copy(isAdding = true, outcome = null) }
            try {
                // S'ajouter soi-même créerait une conversation avec soi dont
                // aucun message ne serait déchiffrable : la clé éphémère ECIES
                // vise l'autre, pas l'expéditeur.
                if (publicKeyHex.equals(_state.value.myPublicKeyHex, ignoreCase = true)) {
                    _state.update { it.copy(isAdding = false, outcome = AddContactOutcome.SelfKey) }
                    return@launch
                }

                val existing = withContext(Dispatchers.IO) {
                    contactRepository.getContact(publicKeyHex)
                }

                // Venu seul, non confirmé : le scan fait foi. Mêmes clés →
                // confirmé ; clés différentes → celui du carnet usurpait
                // cette clé X25519, le scan le remplace.
                if (existing != null && !existing.isTrusted && !existing.isBlocked && !fromLink) {
                    val scanned = Contact.fromPublicKeys(
                        publicKeyHex, parsed.signingPublicKeyHex, parsed.onionAddress, alias
                    )
                    val same = existing.signingPublicKeyHex.equals(parsed.signingPublicKeyHex, ignoreCase = true) &&
                        existing.onionAddress.equals(parsed.onionAddress, ignoreCase = true)
                    withContext(Dispatchers.IO) {
                        if (same) {
                            contactRepository.markTrusted(publicKeyHex)
                            if (alias.isNotBlank()) contactRepository.rename(publicKeyHex, alias)
                        } else if (scanned != null) {
                            contactRepository.addContact(scanned)
                            // Le flux ouvert était celui de l'usurpateur.
                            messageTransport.disconnect(publicKeyHex)
                        }
                    }
                    val confirmed = withContext(Dispatchers.IO) { contactRepository.getContact(publicKeyHex) }
                    _state.update {
                        it.copy(
                            isAdding = false,
                            manualInput = "",
                            outcome = confirmed?.let { c -> AddContactOutcome.Added(c) }
                                ?: AddContactOutcome.Failed(null)
                        )
                    }
                    return@launch
                }

                if (existing != null) {
                    // Rescanner un contact bloqué est le geste naturel pour le
                    // débloquer : on le signale au lieu d'échouer en silence.
                    val outcome = if (existing.isBlocked) {
                        AddContactOutcome.WasBlocked(existing)
                    } else {
                        AddContactOutcome.AlreadyKnown(existing)
                    }
                    _state.update { it.copy(isAdding = false, outcome = outcome) }
                    return@launch
                }

                val contact = Contact.fromPublicKeys(
                    publicKeyHex, parsed.signingPublicKeyHex, parsed.onionAddress, alias
                )
                if (contact == null) {
                    _state.update {
                        it.copy(isAdding = false, outcome = AddContactOutcome.MalformedKey)
                    }
                    return@launch
                }

                // Par lien : comme un inconnu qui se présente, plafond compris.
                val saved = withContext(Dispatchers.IO) {
                    if (fromLink) contactRepository.addUnconfirmed(contact) else contactRepository.addContact(contact)
                }
                _state.update {
                    it.copy(
                        isAdding = false,
                        manualInput = if (saved && !fromLink) "" else it.manualInput,
                        outcome = if (saved) AddContactOutcome.Added(contact)
                        else AddContactOutcome.Failed(null)
                    )
                }
                if (saved) DebugLog.d(TAG) { "Contact ajouté: ${publicKeyHex.take(8)}…" }
            } catch (e: Exception) {
                DebugLog.e(TAG, "Erreur ajout contact", e)
                _state.update {
                    it.copy(isAdding = false, outcome = AddContactOutcome.Failed(e.message))
                }
            }
        }
    }

    /**
     * Débloque un contact déjà connu (suite à AddContactOutcome.WasBlocked).
     */
    fun unblock(publicKeyHex: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { contactRepository.unblockContact(publicKeyHex) }
                val contact = withContext(Dispatchers.IO) {
                    contactRepository.getContact(publicKeyHex)
                }
                _state.update {
                    it.copy(outcome = contact?.let { c -> AddContactOutcome.Unblocked(c) })
                }
            } catch (e: Exception) {
                _state.update { it.copy(outcome = AddContactOutcome.Failed(e.message)) }
            }
        }
    }

    /** Payload compact pour le QR. */
    fun myQrPayload(): String = ContactKeyParser.toQrPayload(
        _state.value.myPublicKeyHex, _state.value.mySigningPublicKeyHex, _state.value.myOnionAddress
    )

    /** Lien cliquable à partager par message. */
    fun myShareableLink(): String = ContactKeyParser.toShareableLink(
        _state.value.myPublicKeyHex, _state.value.mySigningPublicKeyHex, _state.value.myOnionAddress
    )

    fun clearOutcome() {
        _state.update { it.copy(outcome = null) }
    }

    companion object {
        private const val TAG = "DiscoverViewModel"
    }
}
