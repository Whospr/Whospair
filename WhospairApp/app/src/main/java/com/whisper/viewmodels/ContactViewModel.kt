package com.whisper.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whisper.models.Contact
import com.whisper.repositories.ContactRepository
import com.whisper.repositories.MessageRepository
import com.whisper.services.IdentityService
import com.whisper.transport.MessageTransport
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * État du ContactViewModel
 */
data class ContactViewState(
    val contacts: List<Contact> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

/**
 * ViewModel pour gérer les contacts
 * Expose des StateFlows réactifs pour la UI
 */
@HiltViewModel
class ContactViewModel @Inject constructor(
    private val contactRepository: ContactRepository,
    private val messageRepository: MessageRepository,
    private val identityService: IdentityService,
    private val messageTransport: MessageTransport
) : ViewModel() {

    // État UI
    private val _viewState = MutableStateFlow(ContactViewState())
    val viewState: StateFlow<ContactViewState> = _viewState.asStateFlow()

    init {
        loadAllContacts()
    }

    /**
     * Suit le carnet : un contact venu seul, renommé ou supprimé apparaît
     * sans rouvrir l'onglet.
     */
    private fun loadAllContacts() {
        viewModelScope.launch {
            _viewState.update { it.copy(isLoading = true, error = null) }
            try {
                contactRepository.observeAllContacts().collect { all ->
                    _viewState.update { it.copy(contacts = all, isLoading = false) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _viewState.update {
                    it.copy(error = "Erreur de chargement: ${e.message}", isLoading = false)
                }
            }
        }
    }

    /* ------- Appui long dans les listes : mêmes gestes que les paramètres ------- */

    /** Nom local seulement : le contact n'en sait rien. */
    fun rename(key: String, name: String) = act { contactRepository.rename(key, name) }

    fun block(key: String) = act {
        contactRepository.blockContact(key)
        messageTransport.disconnect(key)
    }

    fun unblock(key: String) = act { contactRepository.unblockContact(key) }

    fun setPrivate(key: String, on: Boolean) = act { messageTransport.setPrivate(key, on) }

    fun setPinned(key: String, on: Boolean) = act { contactRepository.setPinned(key, on) }

    fun confirmTrust(key: String) = act { contactRepository.markTrusted(key) }

    /** Faire signe depuis le carnet. @param onResult true si le signe est parti. */
    fun nudge(key: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            onResult(runCatching { messageTransport.sendNudge(key) }.getOrDefault(false))
        }
    }

    /** Contact ET messages : rien ne reste de la conversation. */
    fun deleteWithConversation(key: String) = act {
        messageTransport.disconnect(key)
        identityService.getIdentity()?.let { messageRepository.deleteConversation(it.publicKeyHex, key) }
        contactRepository.deleteContact(key)
    }

    private fun act(action: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                action()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _viewState.update { it.copy(error = "Erreur: ${e.message}") }
            }
        }
    }
}
