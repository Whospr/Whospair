package com.whisper.viewmodels

import kotlinx.coroutines.flow.combine
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whisper.models.Contact
import com.whisper.models.Identity
import com.whisper.models.Message
import com.whisper.models.MessageStatus
import com.whisper.repositories.MessageRepository
import com.whisper.repositories.ContactRepository
import com.whisper.transport.MessageTransport
import com.whisper.services.MessageService
import com.whisper.services.MessageAuthenticationException
import com.whisper.services.TTLPreset
import com.whisper.services.IdentityService
import com.whisper.services.StorageService
import com.whisper.voice.VoicePayload
import com.whisper.files.FilePayload
import com.whisper.files.FileNotice
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

/**
 * ChatViewState - État de la conversation avec un contact
 */
data class ChatViewState(
    val messages: List<Message> = emptyList(),
    val contact: Contact? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val selectedTTLMinutes: Int = MessageService.DEFAULT_TTL_MINUTES,
    /** Source unique des presets : plus de liste dupliquée dans l'UI. */
    val ttlPresets: List<TTLPreset> = emptyList(),
    val decryptedMessages: Map<String, String> = emptyMap(), // ID -> plaintext
    /** MA clé : c'est elle qui décide si une bulle est « envoyée » ou « reçue ». */
    val selfPublicKeyHex: String = "",
    /**
     * Le contact est-il joignable à l'instant.
     *
     * Vrai tant qu'un flux Tor authentifié est ouvert avec lui (voir
     * MessageTransport.reachable). L'ancienne version mettait cette
     * valeur à `true` au simple fait d'ouvrir la conversation — c'est-à-dire
     * qu'elle affichait « en ligne » sur la foi de MON geste, jamais du sien,
     * et rien ne la remettait jamais à false. Elle affirmait donc en
     * permanence une chose qu'elle n'avait aucun moyen de savoir.
     */
    val isPeerOnline: Boolean = false,
    /** Version du contact trop ancienne : rien ne passe tant qu'il ne l'a pas mise à jour. */
    val isPeerOutdated: Boolean = false,
    /** Contact supprimé depuis ses paramètres : l'écran se ferme. */
    val isDeleted: Boolean = false,
    /** Code de lecture saisi pendant CETTE visite ; quitter la conversation le perd. */
    val readUnlocked: Boolean = false,
    /** Issue de la dernière saisie du code : null = aucune ou en cours. */
    val codeResult: Boolean? = null,
    /** Argon2 en cours sur un code. */
    val checkingCode: Boolean = false
) {
    /** Conversation masquée : aucune bulle ne montre son texte. */
    val isMasked: Boolean get() = contact?.hasReadLock == true && !readUnlocked
}

/**
 * ChatViewModel - Gère l'état d'une conversation
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val messageRepository: MessageRepository,
    private val contactRepository: ContactRepository,
    private val messageService: MessageService,
    private val identityService: IdentityService,
    private val messageTransport: MessageTransport,
    private val storageService: StorageService
) : ViewModel() {

    private val _state = MutableStateFlow(ChatViewState())
    val state: StateFlow<ChatViewState> = _state.asStateFlow()

    private var currentContactPublicKey: String? = null
    private var presenceJob: Job? = null
    private var contactJob: Job? = null

    /**
     * Le ViewModel vit aussi longtemps que l'activité : ouvrir A puis B
     * réutilise la même instance. Sans annulation, la collecte de A
     * continuait et ses messages se mêlaient à ceux de B.
     */
    private var chatJob: Job? = null

    /**
     * Identité locale complète : il faut la clé privée pour déchiffrer ET la
     * clé de signature pour authentifier ce qu'on envoie. Une simple clé
     * publique ne suffit plus.
     */
    private var identity: Identity? = null

    /**
     * Initialise la conversation avec un contact
     */
    fun initializeChat(contactPublicKeyHex: String) {
        chatJob?.cancel()
        presenceJob?.cancel()
        contactJob?.cancel()
        // Repart d'un état vierge : aucun clair de la conversation précédente,
        // et la durée par défaut des Paramètres.
        _state.value = ChatViewState(selectedTTLMinutes = defaultTtl())
        chatJob = viewModelScope.launch {
            try {
                _state.update { s -> s.copy(isLoading = true, error = null) }

                // Presets servis par le service : l'UI n'en possède plus de copie.
                _state.update { s -> s.copy(ttlPresets = messageService.getTTLPresets()) }

                currentContactPublicKey = contactPublicKeyHex
                identity = identityService.getIdentity()
                if (identity == null) {
                    _state.update { s -> s.copy(
                        isLoading = false,
                        error = "Identité locale introuvable"
                    ) }
                    return@launch
                }
                _state.update { s -> s.copy(selfPublicKeyHex = identity!!.publicKeyHex) }

                // Charge le contact, et la durée de vie gardée pour cette conversation.
                val contact = contactRepository.getContact(contactPublicKeyHex)
                _state.update { s -> s.copy(
                    contact = contact,
                    selectedTTLMinutes = contact?.ttlMinutes?.takeIf { it in 1..MessageService.MAX_TTL_MINUTES }
                        ?: s.selectedTTLMinutes
                ) }

                // Composer le flux Tor dès maintenant : 5 à 30 s de circuit,
                // pendant que l'utilisateur lit ou tape plutôt qu'après « envoyer ».
                if (contact != null) messageTransport.prepare(contactPublicKeyHex)

                // La présence est observée, jamais écrite.
                observePresence(contactPublicKeyHex)
                // Renommé, confirmé, bloqué : l'en-tête suit sans recharger.
                contactJob = viewModelScope.launch {
                    contactRepository.observeContact(contactPublicKeyHex).collect { c ->
                        if (c != null) _state.update { s -> s.copy(contact = c) }
                    }
                }

                // Collecte sans fin : tout ce qui suivrait serait du code mort.
                loadConversation(contactPublicKeyHex)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { s -> s.copy(
                    isLoading = false,
                    error = "Erreur lors du chargement: ${e.message}"
                ) }
            }
        }
    }

    /**
     * Suit la présence du contact ouvert.
     *
     * Une coroutine distincte de celle des messages : elle doit continuer à
     * vivre pendant toute la conversation, et être remplacée proprement si
     * l'utilisateur passe d'un contact à l'autre sans quitter l'écran.
     */
    private fun observePresence(contactPublicKeyHex: String) {
        presenceJob?.cancel()
        presenceJob = viewModelScope.launch {
            val key = contactPublicKeyHex.lowercase()
            combine(messageTransport.reachable, messageTransport.outdated) { open, old -> (key in open) to (key in old) }
                .collect { (online, outdated) ->
                    _state.update { s -> s.copy(isPeerOnline = online, isPeerOutdated = outdated) }
                }
        }
    }

    /**
     * Charge la conversation bidirectionnelle
     */
    private suspend fun loadConversation(contactPublicKeyHex: String) {
        try {
            val me = identity ?: return

            messageRepository.observeConversationLight(
                me.publicKeyHex,
                contactPublicKeyHex
            ).collect { messages ->
                val sender = contactRepository.getContact(contactPublicKeyHex) ?: return@collect

                // On conserve les plaintexts déjà connus (messages qu'on vient
                // d'envoyer) : l'expéditeur ne peut pas les redéchiffrer, la clé
                // éphémère ECIES ayant été détruite après l'envoi.
                val known = _state.value.decryptedMessages
                // Des centaines de déchiffrements possibles : hors du fil de l'écran.
                val headers = HashMap<String, ByteArray>()
                val decrypted = withContext(Dispatchers.Default) {
                    decryptNew(messages, known, me, sender, headers)
                }
                // Une seule transaction pour tous les en-têtes gardés. Pas de
                // runCatching : il avalerait l'annulation, et un passage à un
                // autre contact pendant la transaction écrirait les clairs de
                // celui-ci dans l'écran du suivant.
                if (headers.isNotEmpty()) {
                    try {
                        messageRepository.keepHeaders(headers)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // En-têtes non gardés : ils seront relus au prochain affichage.
                    }
                }

                // isLoading retombe ICI, à la première émission.
                //
                // Il était remis à false après l'appel à loadConversation(),
                // dans initializeChat() — sauf que cet appel collecte un Flow
                // et ne rend jamais la main. La ligne qui suivait n'était
                // jamais atteinte : l'écran restait en chargement pour
                // l'éternité, alors même que les messages s'affichaient.
                _state.update { s ->
                    // Pendant le déchiffrement : un message supprimé ne revient
                    // pas, un envoi déjà affiché reste.
                    val gone = known.keys - s.decryptedMessages.keys
                    val ids = messages.mapTo(HashSet()) { it.id }
                    val sentMeanwhile = s.messages.filter { it.id !in ids && it.id !in known && it.id in s.decryptedMessages }
                    s.copy(
                        messages = (messages.filter { it.id !in gone } + sentMeanwhile).sortedBy { it.timestamp },
                        decryptedMessages = (decrypted - gone) + s.decryptedMessages,
                        isLoading = false
                    )
                }

                // Envoi annulé par le contact : ses morceaux déjà reçus
                // s'effacent, et l'avis avec eux — rien n'en reste ici.
                val fromThem = messages.filter { it.fromPublicKeyHex.equals(contactPublicKeyHex, ignoreCase = true) }
                val cancelNotices = fromThem.filter { m -> FileNotice.parse(decrypted[m.id])?.event == FileNotice.Event.CANCELLED }
                if (cancelNotices.isNotEmpty()) {
                    val cancelled = cancelNotices.mapNotNull { FileNotice.parse(decrypted[it.id])?.fileId }.toSet()
                    val doomed = fromThem.filter { m -> FilePayload.header(decrypted[m.id] ?: "")?.fileId in cancelled }.map { it.id } +
                        cancelNotices.map { it.id }
                    deleteMessages(doomed)
                }

                // Conversation ouverte et lisible : ce qui est arrivé est lu.
                // Local seulement — aucun accusé de lecture ne part (11e).
                if (!_state.value.isMasked) {
                    messages.filter {
                        it.toPublicKeyHex == me.publicKeyHex && it.status == MessageStatus.DELIVERED
                    }.forEach { messageRepository.markMessageAsRead(it.id) }
                }
                // Room relit au rythme de ce collecteur : pendant un transfert
                // de fichier, quelques relectures par seconde au plus.
                kotlinx.coroutines.delay(REFRESH_GAP_MS)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { s -> s.copy(error = "Erreur conversation: ${e.message}") }
        }
    }

    /**
     * Envoie un message texte.
     */
    fun sendMessage(plainText: String) {
        if (plainText.isBlank()) return
        viewModelScope.launch { sendAll(listOf(plainText)) }
    }

    /**
     * Envoie un fichier (phase 16) : ses morceaux partent comme des messages,
     * dans l'ordre, et s'affichent tout de suite en une seule bulle. Chez
     * nous, seul l'en-tête est gardé (nom, taille) : aucune seconde copie
     * du contenu ne reste sur le téléphone de l'envoyeur.
     */
    fun sendFile(name: String, mime: String, data: ByteArray, flags: Int) {
        val parts = runCatching {
            FilePayload.encode(java.util.UUID.randomUUID().toString(), name, mime, flags, data)
        }.getOrElse {
            _state.update { s -> s.copy(error = "Fichier trop gros (25 Mo au plus)") }
            return
        }
        data.fill(0)
        // Des centaines de chiffrements : hors du fil de l'écran, qui figeait.
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            sendPrepared(parts.map { part ->
                Outgoing(part, FilePayload.headerLine(String(part, Charsets.ISO_8859_1)) ?: "", partial = part !== parts.last())
            })
        }
    }

    /**
     * Dit à l'envoyeur ce qu'on a fait de son fichier (FileNotice). Part
     * comme un message : il arrive même si l'envoyeur est hors ligne.
     */
    fun notifySender(header: FilePayload.Header, event: FileNotice.Event) {
        viewModelScope.launch { sendAll(listOf(FileNotice.encode(header.fileId, event, header.name))) }
    }

    /** Contacts à qui transférer un fichier : confirmés, non bloqués, pas celui-ci. */
    suspend fun forwardTargets(): List<Contact> =
        contactRepository.getAllContacts().filter {
            it.isTrusted && !it.isBlocked && !it.publicKeyHex.equals(currentContactPublicKey, ignoreCase = true)
        }

    /**
     * Transfère un fichier reçu (enregistrable seulement) à un autre contact :
     * nouveau fichier, mêmes options, puis l'envoyeur est prévenu — sans
     * savoir à qui.
     */
    fun forwardFile(header: FilePayload.Header, data: ByteArray, to: String) {
        if (!header.shareable) return
        val parts = runCatching {
            FilePayload.encode(java.util.UUID.randomUUID().toString(), header.name, header.mime, header.flags, data)
        }.getOrElse { return }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            sendPrepared(parts.map { part ->
                Outgoing(part, FilePayload.headerLine(String(part, Charsets.ISO_8859_1)) ?: "", partial = part !== parts.last())
            }, to = to.lowercase())
            messageTransport.flush(to.lowercase())
            notifySender(header, FileNotice.Event.FORWARDED)
        }
    }

    /**
     * Annuler l'envoi d'un fichier : nos morceaux sont effacés (ceux pas
     * encore partis ne partent plus), et le destinataire est prévenu — il
     * efface ce qu'il avait déjà reçu.
     */
    fun cancelFile(header: FilePayload.Header, ids: List<String>) {
        deleteMessages(ids)
        // Sans le nom, et en silence (identifiant « partiel » : ni bip ni
        // aperçu) : le destinataire n'a jamais rien vu du fichier, il ne doit
        // rien apprendre de son annulation. Chez lui, l'avis efface les
        // morceaux reçus, puis s'efface lui-même.
        val notice = FileNotice.encode(header.fileId, FileNotice.Event.CANCELLED, "")
        viewModelScope.launch { sendPrepared(listOf(Outgoing(notice.toByteArray(Charsets.UTF_8), notice, partial = true))) }
    }

    /**
     * Supprimer soi-même, sans attendre la durée de vie : effacé de ce
     * téléphone (secure_delete : les octets ne restent pas sur le disque).
     * Un envoi pas encore parti ne part plus. La copie du contact, elle,
     * vit jusqu'à la fin de sa propre durée de vie.
     */
    fun deleteMessages(ids: List<String>) {
        if (ids.isEmpty()) return
        val gone = ids.toSet()
        _state.update { s -> s.copy(
            messages = s.messages.filter { it.id !in gone },
            decryptedMessages = s.decryptedMessages - gone
        ) }
        viewModelScope.launch { ids.forEach { runCatching { messageRepository.deleteMessage(it) } } }
    }

    /**
     * Effacer pour tous : ici, puis chez le contact — nos messages seulement.
     * Ce qui n'était pas encore parti ne partira plus.
     */
    fun deleteForEveryone(ids: List<String>) {
        val me = identity ?: return
        val key = currentContactPublicKey ?: return
        val mine = _state.value.messages
            .filter { it.id in ids && it.fromPublicKeyHex.equals(me.publicKeyHex, ignoreCase = true) }
            .map { it.id }
        deleteMessages(ids)
        if (mine.isNotEmpty()) viewModelScope.launch { runCatching { messageTransport.sendDelete(key, mine) } }
    }

    /** Vue unique : les morceaux du fichier sont effacés chez nous après la première ouverture. */
    fun forgetFile(fileId: String) {
        val me = identity ?: return
        val state = _state.value
        val ids = state.messages.filter {
            it.toPublicKeyHex == me.publicKeyHex &&
                FilePayload.header(state.decryptedMessages[it.id] ?: return@filter false)?.fileId == fileId
        }.map { it.id }
        viewModelScope.launch { ids.forEach { messageRepository.deleteMessage(it) } }
    }

    /**
     * Texte affichable des messages pas encore vus. Morceau de fichier reçu :
     * l'en-tête seul, gardé scellé par la clé locale (selfCopy) la première
     * fois — aux ouvertures suivantes, un petit AES au lieu d'un ECIES et
     * d'une signature par morceau, et la base ne relit plus son contenu.
     */
    private suspend fun decryptNew(
        messages: List<Message>,
        known: Map<String, String>,
        me: Identity,
        sender: Contact,
        headers: MutableMap<String, ByteArray>
    ): Map<String, String> {
        val decrypted = known.toMutableMap()
        messages.forEach { message ->
            if (message.id in decrypted) return@forEach
            if (message.toPublicKeyHex != me.publicKeyHex) {
                // Envoyé : notre copie locale, s'il y en a une.
                message.selfCopy?.let { copy ->
                    identityService.openLocal(copy)?.let { decrypted[message.id] = it }
                }
                return@forEach
            }
            val partial = FilePayload.isPartial(message.id)
            if (partial) message.selfCopy?.let { copy ->
                identityService.openLocal(copy)?.let { decrypted[message.id] = it; return@forEach }
            }

            // Le rejeu a été écarté à l'arrivée (MessageTransport) ;
            // ici, seulement la signature et le déchiffrement.
            decrypted[message.id] = try {
                // Morceau de fichier : l'en-tête seul (nom, taille,
                // progression). Les octets restent chiffrés en base
                // jusqu'à l'ouverture (openFile).
                // En-tête gardé mais illisible : l'écran n'a pas le contenu, on le relit.
                val full = if (message.content.isEmpty())
                    message.copy(content = messageRepository.contentsOf(listOf(message.id))[message.id] ?: message.content)
                else message
                messageService.decryptMessage(full, me, sender)
                    .let { FilePayload.headerLine(it) ?: it }
                    .also { line ->
                        if (partial && message.selfCopy == null) identityService.sealLocal(line)?.let { headers[message.id] = it }
                    }
            } catch (e: MessageAuthenticationException) {
                // Distinct d'un échec de déchiffrement : ici quelqu'un
                // usurpe une identité.
                "[Origine non vérifiée]"
            } catch (e: Exception) {
                "[Erreur déchiffrement]"
            }
        }
        return decrypted
    }

    /**
     * Ouvre un fichier reçu : ses morceaux sont déchiffrés et recollés en
     * mémoire, jamais écrits en clair. null s'il en manque encore un.
     */
    suspend fun openFile(fileId: String): Pair<FilePayload.Header, ByteArray>? {
        val me = identity ?: return null
        val sender = _state.value.contact ?: return null
        val state = _state.value
        val chunks = state.messages.filter { it.toPublicKeyHex == me.publicKeyHex }
            .mapNotNull { m -> FilePayload.header(state.decryptedMessages[m.id] ?: return@mapNotNull null)?.takeIf { it.fileId == fileId }?.let { m to it } }
        val header = chunks.firstOrNull()?.second ?: return null
        // L'écran n'a pas le contenu des morceaux (observeConversationLight) : relu ici.
        val contents = messageRepository.contentsOf(chunks.map { it.first.id })
        return withContext(Dispatchers.Default) {
            val parts = HashMap<Int, ByteArray>()
            for ((light, h) in chunks) {
                val message = light.copy(content = contents[light.id] ?: continue)
                val plain = runCatching { messageService.decryptBytes(message, me, sender) }.getOrNull() ?: continue
                FilePayload.dataOf(plain)?.let { parts[h.index] = it }
                plain.fill(0)
            }
            FilePayload.assemble(parts, header.count, header.size)?.let { header to it }
                .also { parts.values.forEach { it.fill(0) } }
        }
    }

    /**
     * Envoie un vocal : ses morceaux partent comme des messages ordinaires,
     * l'un après l'autre, et s'affichent tout de suite en une seule bulle.
     */
    fun sendVoice(audio: ByteArray, durationMs: Int) {
        val parts = runCatching {
            VoicePayload.encode(java.util.UUID.randomUUID().toString(), durationMs, audio)
        }.getOrElse {
            _state.update { s -> s.copy(error = "Vocal trop long") }
            return
        }
        viewModelScope.launch { sendAll(parts) }
    }

    /**
     * Les envois quittent le téléphone dans l'ordre exact de leurs compteurs.
     *
     * Le destinataire refuse tout message dont le compteur ne dépasse pas le
     * dernier reçu (anti-rejeu). Un texte tapé pendant qu'un vocal part
     * prendrait sinon de l'avance, et les morceaux restants seraient rejetés
     * comme des rejeux — marqués « envoyés » ici, jamais lisibles là-bas.
     * D'où : compteurs réservés sous verrou, puis une seule file d'envoi.
     */
    private val prepareLock = Mutex()
    /**
     * Chiffre, range « en attente » et affiche tout de suite (horloge), puis
     * confie à la file de MessageTransport : elle envoie dans l'ordre des
     * compteurs et retente tant que le contact est injoignable.
     */
    private suspend fun sendAll(plainTexts: List<String>) =
        sendPrepared(plainTexts.map { Outgoing(it.toByteArray(Charsets.UTF_8), it) })

    /** Un envoi : le clair à chiffrer, et ce qu'on en garde pour l'affichage. */
    /** [partial] : morceau de fichier qui n'est pas le dernier (voir FilePayload.PARTIAL_ID_PREFIX). */
    private class Outgoing(val plain: ByteArray, val display: String, val partial: Boolean = false)

    private suspend fun sendPrepared(items: List<Outgoing>, to: String? = null) {
        val me = identity ?: return
        val current = currentContactPublicKey ?: return
        val contactKey = to ?: current
        val here = contactKey.equals(current, ignoreCase = true)
        try {
            prepareLock.withLock {
                val recipient = contactRepository.getContact(contactKey)
                    ?: throw IllegalStateException("Contact introuvable")
                // Transfert : la durée de vie gardée pour CETTE conversation-là.
                val ttl = if (here) _state.value.selectedTTLMinutes
                else recipient.ttlMinutes.takeIf { it in 1..MessageService.MAX_TTL_MINUTES } ?: defaultTtl()

                val pending = items.map { item ->
                    // Numéro réservé atomiquement en base.
                    val counter = contactRepository.nextSentCounter(contactKey)
                    val encrypted = try {
                        messageService.encryptBytes(
                            plainBytes = item.plain,
                            sender = me,
                            recipient = recipient,
                            counter = counter,
                            ttlMinutes = ttl
                        )
                    } finally {
                        item.plain.fill(0)
                    }
                    // Le clair est gardé ici : l'expéditeur ne peut pas relire son
                    // propre chiffré, la clé éphémère ECIES est détruite à l'envoi.
                    // Pour un fichier, l'en-tête seulement.
                    encrypted.copy(
                        id = if (item.partial) FilePayload.PARTIAL_ID_PREFIX + encrypted.id else encrypted.id,
                        selfCopy = identityService.sealLocal(item.display)
                    ) to item.display
                }
                pending.forEach { messageRepository.addMessage(it.first) }
                if (here) _state.update { s -> s.copy(
                    // La base a pu les renvoyer entre-temps : pas de doublon.
                    messages = (s.messages + pending.map { it.first }).distinctBy { it.id },
                    decryptedMessages = s.decryptedMessages + pending.associate { it.first.id to it.second },
                    error = null
                ) }
                messageTransport.flush(contactKey)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { s -> s.copy(error = "Erreur envoi: ${e.message}") }
        }
    }

    /**
     * Réessayer un message non envoyé, sur geste explicite : l'ancien est
     * retiré, le même contenu repart comme un nouveau message (nouveau
     * compteur, nouveau chiffré). Pour un vocal, tous ses morceaux.
     */
    fun retry(message: Message) {
        val text = _state.value.decryptedMessages[message.id] ?: return
        if (message.status != MessageStatus.FAILED) return
        // Fichier : seul l'en-tête est gardé chez l'envoyeur, il faut le renvoyer.
        if (FilePayload.isFile(text)) {
            _state.update { s -> s.copy(error = "Fichier non parti : envoyez-le de nouveau") }
            return
        }
        viewModelScope.launch {
            try {
                // Pour un vocal : seuls ses morceaux non partis, sous le même
                // identifiant — le destinataire recolle avec ceux déjà reçus.
                val voiceId = VoicePayload.parse(text)?.voiceId
                val failed = if (voiceId == null) listOf(message) else _state.value.messages.filter {
                    it.status == MessageStatus.FAILED &&
                        VoicePayload.parse(_state.value.decryptedMessages[it.id] ?: "")?.voiceId == voiceId
                }
                val texts = failed.mapNotNull { _state.value.decryptedMessages[it.id] }
                val ids = failed.map { it.id }.toSet()
                failed.forEach { messageRepository.deleteMessage(it.id) }
                _state.update { s -> s.copy(messages = s.messages.filter { it.id !in ids }) }
                sendAll(texts)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { s -> s.copy(error = "Erreur envoi: ${e.message}") }
            }
        }
    }

    /**
     * Empreinte de paire à comparer de vive voix avec le contact.
     * Identique des deux côtés — c'est ce qui permet de détecter qu'un tiers
     * s'est interposé lors de l'échange des clés.
     */
    fun safetyNumber(): String? {
        val me = identity ?: return null
        val contact = _state.value.contact ?: return null
        return messageService.safetyNumber(me, contact)
    }

    /**
     * Met à jour le TTL sélectionné
     */
    fun setSelectedTTL(minutes: Int) {
        _state.update { s -> s.copy(selectedTTLMinutes = minutes) }
        // Gardée pour cette conversation : pas à rechoisir à chaque ouverture.
        onContact { contactRepository.setTtl(it, minutes) }
    }

    /**
     * Marque un message comme lu
     */
    fun markMessageAsRead(messageId: String) {
        viewModelScope.launch {
            try {
                messageRepository.markMessageAsRead(messageId)
            } catch (e: Exception) {
                // Silent fail pour UX
            }
        }
    }

    /**
     * Marque un message comme livré
     */
    fun markMessageAsDelivered(messageId: String) {
        viewModelScope.launch {
            try {
                messageRepository.markMessageAsDelivered(messageId)
            } catch (e: Exception) {
                // Silent fail
            }
        }
    }

    /**
     * Supprime un message (hard delete)
     */
    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            try {
                messageRepository.deleteMessage(messageId)
                _state.update { s -> s.copy(messages = s.messages.filter { it.id != messageId }) }
            } catch (e: Exception) {
                _state.update { s -> s.copy(error = "Erreur suppression: ${e.message}") }
            }
        }
    }

    /**
     * Supprime la conversation entière
     */
    fun deleteConversation() {
        viewModelScope.launch {
            try {
                if (currentContactPublicKey != null) {
                    messageRepository.deleteConversation(
                        identity?.publicKeyHex ?: return@launch,
                        currentContactPublicKey!!
                    )
                    _state.update { s -> s.copy(messages = emptyList()) }
                }
            } catch (e: Exception) {
                _state.update { s -> s.copy(error = "Erreur suppression conv: ${e.message}") }
            }
        }
    }

    /* ---------------- Paramètres de la conversation ---------------- */

    /** Nom local seulement : le contact n'en sait rien. */
    fun rename(name: String) = onContact { contactRepository.rename(it, name) }

    fun confirmTrust() = onContact { contactRepository.markTrusted(it) }

    /* ---------------- Vérification en personne (1.3.7) ---------------- */

    fun safetyCode(): String? {
        val me = identity ?: return null
        val contact = _state.value.contact ?: return null
        return messageService.safetyCode(me, contact)
    }

    /**
     * Son code, scanné face à face. Identique au nôtre : vérifié (et confirmé).
     * @return true si les codes correspondent.
     */
    fun verifyScanned(scanned: String): Boolean {
        val expected = safetyCode() ?: return false
        val ok = java.security.MessageDigest.isEqual(
            expected.toByteArray(Charsets.US_ASCII),
            scanned.trim().lowercase().toByteArray(Charsets.US_ASCII)
        )
        if (ok) onContact {
            contactRepository.markTrusted(it)
            contactRepository.setVerified(it, true)
        }
        return ok
    }

    /* ---------------- Effacement à deux (1.3.7) ---------------- */

    fun wipeEnable() = onContact { messageTransport.wipeEnable(it) }

    fun wipeDisable() = onContact { messageTransport.wipeDisable(it) }

    fun wipeAll() = onContact { key ->
        if (messageTransport.wipeAll(key)) _state.update { s -> s.copy(messages = emptyList()) }
    }

    /** Faire signe. @param onResult true si le signe est parti sur le flux. */
    fun nudge(onResult: (Boolean) -> Unit) {
        val key = currentContactPublicKey ?: return onResult(false)
        viewModelScope.launch {
            onResult(runCatching { messageTransport.sendNudge(key) }.getOrDefault(false))
        }
    }

    fun block() = onContact {
        contactRepository.blockContact(it)
        messageTransport.disconnect(it)
    }

    fun unblock() = onContact { contactRepository.unblockContact(it) }

    fun setPrivate(on: Boolean) = onContact { messageTransport.setPrivate(it, on) }

    /** Contact ET messages : rien ne reste de la conversation. */
    fun deleteContact() = onContact { key ->
        messageTransport.disconnect(key)
        identity?.let { messageRepository.deleteConversation(it.publicKeyHex, key) }
        contactRepository.deleteContact(key)
        chatJob?.cancel()
        contactJob?.cancel()
        presenceJob?.cancel()
        _state.value = ChatViewState(isDeleted = true)
    }

    /* ---------------- Masquage de la lecture ---------------- */

    /**
     * Le code est effacé ici, quoi qu'il arrive.
     *
     * Le contact est RELU en base (1.3.8) : la copie de l'écran pouvait dater
     * d'avant l'activation. Activer puis désactiver aussitôt vérifiait alors le
     * code contre une copie sans code — refusé, et la conversation restait masquée.
     */
    private fun withCode(code: CharArray, action: suspend (Contact) -> Boolean) {
        val key = _state.value.contact?.publicKeyHex ?: run { code.fill('\u0000'); return }
        _state.update { s -> s.copy(checkingCode = true, codeResult = null) }
        viewModelScope.launch {
            val ok = try {
                contactRepository.getContact(key)?.let { action(it) } ?: false
            } catch (e: Exception) {
                false
            } finally {
                code.fill('\u0000')
            }
            _state.update { s -> s.copy(checkingCode = false, codeResult = ok) }
        }
    }

    fun unlockReading(code: CharArray) = withCode(code) { contact ->
        contactRepository.checkReadLock(contact, code).also { ok ->
            if (ok) _state.update { s -> s.copy(readUnlocked = true) }
        }
    }

    /** Activé par qui connaît le code : la visite en cours reste lisible. */
    fun enableReadLock(code: CharArray) = withCode(code) { contact ->
        contactRepository.setReadLock(contact.publicKeyHex, code)
        _state.update { s -> s.copy(readUnlocked = true) }
        true
    }

    /** Désactiver exige l'ancien code : sinon il ne protégerait rien. */
    fun disableReadLock(code: CharArray) = withCode(code) { contact ->
        contactRepository.checkReadLock(contact, code).also { ok ->
            if (ok) {
                contactRepository.clearReadLock(contact.publicKeyHex)
                // Lisible tout de suite, sans attendre que la base renvoie le contact.
                _state.update { s -> s.copy(readUnlocked = true, contact = s.contact?.copy(readLock = "")) }
            }
        }
    }

    fun resetCodeResult() {
        _state.update { s -> s.copy(codeResult = null) }
    }

    private fun defaultTtl(): Int =
        storageService.getInt(StorageService.KEY_DEFAULT_TTL, MessageService.DEFAULT_TTL_MINUTES)
            .takeIf { it in 1..MessageService.MAX_TTL_MINUTES } ?: MessageService.DEFAULT_TTL_MINUTES

    private fun onContact(action: suspend (String) -> Unit) {
        val key = currentContactPublicKey ?: return
        viewModelScope.launch {
            try {
                action(key)
            } catch (e: Exception) {
                _state.update { s -> s.copy(error = "Erreur: ${e.message}") }
            }
        }
    }

    fun clearError() {
        _state.update { s -> s.copy(error = null) }
    }

    private companion object {
        const val REFRESH_GAP_MS = 250L
    }
}
