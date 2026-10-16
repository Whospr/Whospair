package com.whisper.viewmodels

import com.whisper.util.ReplyPayload
import com.whisper.util.DebugLog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whisper.alerts.InAppAlerts
import com.whisper.models.Contact
import com.whisper.models.ConversationItem
import com.whisper.models.Identity
import com.whisper.models.Message
import com.whisper.network.NetworkPrivacyMonitor
import com.whisper.tor.TorRuntime
import com.whisper.transport.MessageTransport
import com.whisper.repositories.ContactRepository
import com.whisper.repositories.MessageRepository
import com.whisper.services.IdentityService
import com.whisper.services.MessageService
import com.whisper.voice.VoicePayload
import com.whisper.files.FilePayload
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * MessagesViewState - État de la liste des conversations
 */
data class MessagesViewState(
    val conversations: List<ConversationItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val searchQuery: String = "",
    val filteredConversations: List<ConversationItem> = emptyList()
)

/**
 * MessagesViewModel - Liste des conversations.
 */
@HiltViewModel
class MessagesViewModel @Inject constructor(
    private val messageRepository: MessageRepository,
    private val contactRepository: ContactRepository,
    private val messageService: MessageService,
    private val identityService: IdentityService,
    private val messageTransport: MessageTransport,
    private val alerts: InAppAlerts,
    tor: TorRuntime,
    networkPrivacyMonitor: NetworkPrivacyMonitor,
    reachability: com.whisper.reach.Reachability,
    updates: com.whisper.update.UpdateManager
) : ViewModel() {

    /** Pour l'indicateur discret de l'en-tête. */
    val torState = tor.state
    val tunnelled = networkPrivacyMonitor.tunnelled
    val stayReachable = reachability.enabled
    val update = updates.state

    private val _state = MutableStateFlow(MessagesViewState())
    val state: StateFlow<MessagesViewState> = _state.asStateFlow()

    /**
     * Conversations sans la présence.
     *
     * Étape séparée exprès : construire un item déchiffre l'aperçu du dernier
     * message, ce qui coûte cher. La présence, elle, peut changer plusieurs
     * fois par minute. Tout refaire à chaque va-et-vient d'un contact ferait
     * tourner l'ECIES pour rien et hacherait le défilement de la liste.
     */
    private val baseConversations = MutableStateFlow<List<ConversationItem>>(emptyList())

    init {
        loadConversations()
        observePresence()
    }

    /**
     * Applique la présence par-dessus les conversations déjà construites.
     */
    private fun observePresence() {
        viewModelScope.launch {
            combine(baseConversations, messageTransport.reachable, alerts.nudges) { items, online, nudges ->
                // Un signe plus récent que le dernier message remonte la conversation.
                fun latest(item: ConversationItem): Long = maxOf(
                    item.lastMessage?.sortTime ?: 0L,
                    nudges[item.contact.publicKeyHex.lowercase()] ?: 0L
                )
                items.map { item ->
                    val key = item.contact.publicKeyHex.lowercase()
                    val nudgedAt = nudges[key]
                    item.copy(
                        isOnline = key in online,
                        nudged = nudgedAt != null && nudgedAt > (item.lastMessage?.sortTime ?: 0L)
                    )
                }.sortedWith(compareByDescending<ConversationItem> { it.contact.isPinned }.thenByDescending(::latest))
            }.collect { withPresence ->
                _state.value = _state.value.copy(conversations = withPresence)
                applyFilter(_state.value.searchQuery)
            }
        }
    }

    /**
     * Charge les conversations : contacts + dernier message de chacun.
     */
    private fun loadConversations() {
        viewModelScope.launch {
            try {
                _state.value = _state.value.copy(isLoading = true, error = null)

                val identity = identityService.getIdentity()
                if (identity == null) {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        error = "Identité locale non initialisée"
                    )
                    return@launch
                }

                // Contacts ET messages : sans le second flux, un message
                // arrivé ne remontait jamais sa conversation dans la liste.
                // Le second n'est qu'un signal (un compte) : relire tous les
                // messages, morceaux de fichiers compris, prenait des secondes.
                combine(
                    contactRepository.observeAllContacts(),
                    messageRepository.observeChanges()
                ) { contacts, _ -> contacts }.collect { contacts ->
                    // Déchiffrer les aperçus : hors du fil de l'écran.
                    baseConversations.value = withContext(Dispatchers.Default) {
                        contacts
                            .map { contact -> buildConversationItem(contact, identity) }
                            .sortedWith(compareByDescending<ConversationItem> { it.contact.isPinned }
                                .thenByDescending { it.lastMessage?.sortTime ?: 0L })
                    }

                    // La présence est recollée par observePresence(), qui
                    // publie l'état final.
                    _state.value = _state.value.copy(isLoading = false)
                    // Un fichier arrive en centaines de morceaux : Room relit
                    // au rythme de ce collecteur, quelques fois par seconde au plus.
                    kotlinx.coroutines.delay(REFRESH_GAP_MS)
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = "Erreur chargement conversations: ${e.message}"
                )
            }
        }
    }

    /**
     * Construit l'item d'une conversation.
     */
    private suspend fun buildConversationItem(
        contact: Contact,
        identity: Identity
    ): ConversationItem {
        val userKey = identity.publicKeyHex
        // Morceaux intermédiaires d'un fichier reçu : invisibles ici. Le
        // fichier n'apparaît (aperçu, remontée, non-lu) qu'avec son dernier
        // morceau, c'est-à-dire complet. La base ne rend que ce qui sert.
        val lastMessage = messageRepository.lastVisible(userKey, contact.publicKeyHex)

        // Un vocal arrive en plusieurs morceaux : il ne compte qu'une fois.
        // Chaque non-lu n'est déchiffré qu'une fois (cache).
        val unread = messageRepository.unreadFrom(userKey, contact.publicKeyHex)
            .map { message ->
                groupOf.getOrPut(message.id) {
                    runCatching { messageService.decryptMessage(message, identity, contact) }.getOrNull()
                        ?.let { text -> VoicePayload.parse(text)?.voiceId ?: FilePayload.header(text)?.fileId }
                        ?: message.id
                }
            }
            .distinct()
            .size

        val preview = previewOf(lastMessage, identity, contact)

        return ConversationItem.fromContactAndMessage(
            contact = contact,
            message = lastMessage,
            preview = preview,
            unreadCount = unread
        )
    }

    /**
     * Aperçu en clair du dernier message.
     *
     * Conversation masquée : aucun aperçu, la liste ne doit pas trahir ce
     * que la conversation cache. Message envoyé : notre copie locale (la clé
     * éphémère ECIES est détruite à l'envoi, le chiffré est illisible pour nous).
     */
    /** Message → vocal ou fichier dont il est un morceau (ou lui-même). En mémoire, jamais écrit. */
    private val groupOf = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun previewOf(message: Message?, identity: Identity, sender: Contact): String? {
        if (message == null) return null
        if (sender.hasReadLock) return null
        if (message.toPublicKeyHex != identity.publicKeyHex) {
            return message.selfCopy?.let { identityService.openLocal(it) }?.let(ReplyPayload::body)
        }
        if (messageService.isMessageExpired(message)) return null

        return try {
            // Le compteur anti-rejeu n'est contrôlé qu'à l'arrivée du message
            // (MessageTransport) : l'aperçu ne fait que déchiffrer.
            messageService.decryptMessage(message, identity, sender)
                .let { FilePayload.headerLine(it) ?: ReplyPayload.body(it) }
        } catch (e: Exception) {
            DebugLog.w(TAG, "Aperçu indisponible pour ${message.id}")
            null
        }
    }

    /**
     * Filtre les conversations par nom de contact ou clé publique.
     */
    fun filterConversations(query: String) {
        _state.value = _state.value.copy(searchQuery = query)
        applyFilter(query)
    }

    private fun applyFilter(query: String) {
        val source = _state.value.conversations
        val filtered = if (query.isBlank()) source else source.filter { matches(it, query) }
        _state.value = _state.value.copy(filteredConversations = filtered)
    }

    private fun matches(conversation: ConversationItem, query: String): Boolean =
        conversation.contact.displayLabel.contains(query, ignoreCase = true) ||
                conversation.contact.publicKeyHex.contains(query, ignoreCase = true)

    fun getConversation(publicKeyHex: String): ConversationItem? =
        _state.value.conversations.find { it.contact.publicKeyHex == publicKeyHex }

    /**
     * Supprime une conversation.
     */
    fun deleteConversation(publicKeyHex: String) {
        viewModelScope.launch {
            try {
                val userKey = identityService.getPublicKeyHex()
                if (userKey.isBlank()) return@launch

                // Là aussi, la clé utilisateur était une chaîne vide :
                // la suppression ne touchait aucune ligne.
                messageRepository.deleteConversation(userKey, publicKeyHex)

                // On retire de la source, pas de l'état affiché : l'état est
                // recalculé à chaque changement de présence, et une ligne
                // supprimée uniquement dans l'affichage réapparaîtrait dès
                // qu'un contact se connecte.
                baseConversations.value = baseConversations.value.filterNot {
                    it.contact.publicKeyHex == publicKeyHex
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = "Erreur suppression: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "MessagesViewModel"
        private const val REFRESH_GAP_MS = 400L
    }
}
