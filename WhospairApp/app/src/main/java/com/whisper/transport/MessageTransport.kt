package com.whisper.transport

import kotlinx.serialization.json.JsonArray
import com.whisper.BuildConfig
import android.net.LocalSocket
import android.os.SystemClock
import com.whisper.call.CallProtocol
import com.whisper.crypto.CryptoManager
import com.whisper.files.FilePayload
import com.whisper.models.Contact
import com.whisper.models.Message
import com.whisper.models.MessageStatus
import com.whisper.repositories.ContactRepository
import com.whisper.repositories.MessageRepository
import com.whisper.services.IdentityService
import com.whisper.services.MessageService
import com.whisper.services.ProfilePhotoService
import com.whisper.alerts.InAppAlerts
import com.whisper.security.PinCrypto
import com.whisper.tor.TorRuntime
import com.whisper.update.UpdateManager
import com.whisper.update.UpdateProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.bouncycastle.util.encoders.Hex
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import com.whisper.util.DebugLog

/**
 * Le passage d'un message d'un téléphone à l'autre — par Tor, sans serveur.
 *
 * ### Ce qui transite
 *
 * Le chiffré, tel quel : le contenu est chiffré ECIES, signé et numéroté par
 * MessageService bien avant d'arriver ici. Ce fichier ne manipule jamais de
 * texte lisible, il déplace des octets opaques sur des flux TorLink.
 *
 * ### Un flux par contact, gardé ouvert
 *
 * Ouvrir un flux vers un .onion prend 5 à 30 s (circuit de rendez-vous). Il
 * est donc composé dès l'ouverture de la conversation (prepare), puis gardé
 * tant qu'il vit. S'il meurt (réseau changé, circuit tombé), le prochain
 * envoi recompose une fois.
 *
 * Si les deux côtés composent en même temps, deux flux naissent. Les deux
 * téléphones gardent le même : celui ouvert par la plus petite clé. Sans
 * règle commune, chacun fermerait le flux de l'autre et il n'en resterait
 * aucun.
 *
 * ### File d'envoi (29/09)
 *
 * Un message écrit reste « en attente » (PENDING), chiffré dans la base, tant
 * qu'il n'est pas parti — TTL compris : expiré, il disparaît sans partir.
 * Une file par contact l'envoie dans l'ordre des compteurs (l'autre refuse
 * un compteur dépassé), retente de plus en plus espacé jusqu'à 15 s, et
 * repart aussitôt qu'un flux s'ouvre avec ce contact ou que Tor revient.
 * À chaque flux neuf, les messages partis sans accusé sont renvoyés : un
 * circuit mort accepte encore des octets sans les livrer. Le contact accuse
 * un doublon sans le réécrire.
 */
@Singleton
class MessageTransport @Inject constructor(
    private val tor: TorRuntime,
    private val messageRepository: MessageRepository,
    private val contactRepository: ContactRepository,
    private val identityService: IdentityService,
    private val cryptoManager: CryptoManager,
    private val messageService: MessageService,
    private val photos: ProfilePhotoService,
    private val alerts: InAppAlerts,
    /** Lazy : UpdateManager envoie par ce transport (cycle rompu). */
    private val updates: dagger.Lazy<UpdateManager>
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    private val links = ConcurrentHashMap<String, TorLink>()

    /**
     * Effacements « pour tous » pas encore remis (contact injoignable) :
     * des identifiants seuls, en mémoire — repartent à la prochaine ouverture
     * du flux, meurent avec l'app.
     */
    private val pendingDeletes = ConcurrentHashMap<String, MutableSet<String>>()

    /**
     * Contacts avec qui un flux Tor authentifié est ouvert À L'INSTANT.
     *
     * C'est la seule présence que l'app connaisse : elle ne vient d'aucun
     * serveur, elle constate un flux vivant. Rien n'est écrit nulle part, et
     * l'autre ne reçoit aucun signal de plus que la connexion elle-même.
     */
    private val _reachable = MutableStateFlow<Set<String>>(emptySet())
    val reachable: StateFlow<Set<String>> = _reachable.asStateFlow()

    /**
     * Version minimale (1.3) : la version de Whospair annoncée par chaque
     * contact sur son flux (« ver », première trame). En dessous de
     * BuildConfig.MIN_PEER_VERSION — correctifs de sécurité —, plus rien ne
     * passe dans un sens ni dans l'autre, sauf les mises à jour : sinon un
     * contact en retard ne pourrait plus jamais se mettre à jour par nous.
     */
    private val peerVersions = ConcurrentHashMap<String, Long>()
    private val _outdated = MutableStateFlow<Set<String>>(emptySet())
    /** Contacts dont la version est trop ancienne : leurs messages attendent qu'ils se mettent à jour. */
    val outdated: StateFlow<Set<String>> = _outdated.asStateFlow()

    /** Le minimum du moment : relevé à notre version 120 h après notre installation (1.3.8). */
    private fun minPeer(): Long = runCatching { updates.get().minPeerVersion }.getOrDefault(BuildConfig.MIN_PEER_VERSION)

    private fun markOutdated(key: String, outdated: Boolean) {
        _outdated.value = if (outdated) _outdated.value + key else _outdated.value - key
    }

    /** Version du contact connue et suffisante ; on l'attend quelques secondes après l'ouverture du flux. */
    private suspend fun upToDate(key: String, link: TorLink): Boolean {
        withTimeoutOrNull(VERSION_WAIT_MS) {
            while (!peerVersions.containsKey(key) && link.isOpen) delay(100)
        }
        val ok = (peerVersions[key] ?: 0L) >= minPeer()
        if (!ok && link.isOpen) markOutdated(key, true)
        return ok
    }

    private fun versionFrame(): ByteArray = buildJsonObject {
        put("v", 1)
        put("t", VERSION)
        put("code", BuildConfig.VERSION_CODE.toLong())
    }.toString().toByteArray(Charsets.UTF_8)

    private fun publishReachable() {
        _reachable.value = links.filterValues { it.isOpen }.keys + graced
    }

    /**
     * Flux tombé sans qu'on l'ait fermé : sur une 4G faible, c'est courant.
     * Le contact reste « joignable » quelques secondes, le temps de
     * recomposer — sinon la présence clignote à chaque coupure. Au-delà,
     * on cesse de l'affirmer, même si les tentatives continuent.
     */
    private val graced = ConcurrentHashMap.newKeySet<String>()
    /**
     * Un sursis et une série de tentatives par contact : une nouvelle coupure
     * les relance en entier (10 s pleines, quatre tentatives).
     */
    private val graceTimers = ConcurrentHashMap<String, Job>()
    private val redials = ConcurrentHashMap<String, Job>()

    /**
     * Avance à chaque fermeture voulue (bloqué, supprimé, usurpateur). Un flux
     * composé avant et abouti après n'est pas retenu : sans ça, une
     * recomposition en cours ressuscitait le contact qu'on venait de retirer.
     */
    private val epochs = ConcurrentHashMap<String, Int>()

    private fun reconnect(contactKey: String) {
        graced += contactKey
        publishReachable()
        graceTimers.put(contactKey, scope.launch {
            delay(PRESENCE_GRACE_MS)
            if (graced.remove(contactKey)) publishReachable()
        })?.cancel()
        redials.put(contactKey, scope.launch {
            for (wait in REDIAL_DELAYS_MS) {
                delay(wait)
                // L'autre côté a peut-être recomposé avant nous.
                if (links[contactKey]?.isOpen == true) break
                if (isQuiet(contactKey)) break
                if (runCatching { linkTo(contactKey) }.isSuccess) break
            }
        })?.cancel()
    }
    /**
     * Trames d'appel reçues : (clé du contact, trame). CallManager les lit.
     * Déposées sans attendre : la lecture du flux ne bloque jamais sur l'appel.
     */
    private val _callFrames = MutableSharedFlow<Pair<String, ByteArray>>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val callFrames: SharedFlow<Pair<String, ByteArray>> = _callFrames.asSharedFlow()

    /** Échecs de connexion de suite, par contact (voir dial). */
    private val dialFailures = ConcurrentHashMap<String, Int>()

    /** Un seul appel sortant à la fois par contact. */
    private val dialing = ConcurrentHashMap<String, Mutex>()

    /** Poignées de main entrantes en cours, au plus. */
    private val handshakes = Semaphore(MAX_PENDING_HANDSHAKES)

    /** Arrivée des messages, un à la fois (compteur anti-rejeu). */
    private val intake = Mutex()

    fun start() {
        // Envois restés en attente (app fermée, contact absent) : la file reprend.
        scope.launch {
            val me = identityService.getIdentity() ?: return@launch
            runCatching { messageRepository.pendingRecipients(me.publicKeyHex) }
                .getOrDefault(emptyList()).forEach { flush(it) }
        }
        // Tor revenu (démarrage, fin de coupure, Tor relancé) : toutes les
        // files retentent, et les canaux sont recomposés sans attendre qu'on
        // rouvre chaque conversation.
        scope.launch {
            var wasReady = false
            tor.state.collect {
                val ready = it is TorRuntime.State.Ready
                if (ready) wakes.values.forEach { w -> w.trySend(Unit) }
                if (ready && !wasReady) rejoin()
                wasReady = ready
            }
        }
        // Photo ou réglage changé : chaque flux ouvert reçoit la nouvelle trame.
        scope.launch {
            photos.changes.collect { links.values.filter { it.isOpen }.forEach { sendPhoto(it) } }
        }
        // Contact confirmé flux ouvert : il a peut-être droit à la photo désormais.
        scope.launch {
            var trusted: Set<String>? = null
            contactRepository.observeAllContacts()
                .map { all -> all.filter { it.isTrusted }.map { it.publicKeyHex }.toSet() }
                .distinctUntilChanged()
                .collect { now ->
                    val before = trusted
                    trusted = now
                    if (before != null) (now - before).forEach { key -> links[key]?.takeIf { it.isOpen }?.let { sendPhoto(it) } }
                }
        }
        scope.launch {
            for (socket in tor.incoming) {
                // Plafond : sans lui, quiconque a l'adresse (elle est dans le
                // QR) ouvre des centaines de connexions muettes, chacune
                // bloque un fil d'E/S jusqu'au délai, et l'app entière gèle.
                if (!handshakes.tryAcquire()) {
                    runCatching { socket.close() }
                    continue
                }
                scope.launch {
                    try {
                        runCatching { accept(socket) }.onFailure {
                            DebugLog.d(TAG) { "Connexion entrante refusée" }
                            runCatching { socket.close() }
                        }
                    } finally {
                        handshakes.release()
                    }
                }
            }
        }
    }

    /**
     * Tor prêt : un flux vers chaque contact confirmé, quelques-uns à la fois.
     * Sans ça, après un Tor relancé (« Rester joignable » coupé), il fallait
     * rouvrir les conversations une à une — l'autre côté, lui, garde un temps
     * l'ancienne publication de notre adresse et ne nous trouve pas.
     * Les non confirmés ne sont pas composés : ils nous rejoignent eux-mêmes.
     * Ceux en mode privé non plus : ils nous voient hors ligne.
     */
    private fun rejoin() = scope.launch {
        val gate = Semaphore(MAX_PARALLEL_DIALS)
        runCatching { contactRepository.observeAllContacts().first() }.getOrDefault(emptyList())
            .filter { it.isTrusted && !it.isBlocked && !it.isPrivate }
            .forEach { contact ->
                launch { gate.withPermit { runCatching { linkTo(contact.publicKeyHex.lowercase()) } } }
            }
    }

    /** À l'ouverture d'une conversation : le premier message n'attendra pas le circuit. */
    fun prepare(contactPublicKey: String) {
        val key = contactPublicKey.lowercase()
        scope.launch { if (!isQuiet(key)) runCatching { linkTo(key) } }
    }

    /* ====================================================================== *
     *  Mode privé
     * ====================================================================== */

    /**
     * Dernier échange avec chaque contact (horloge du système, en mémoire
     * seulement) : un envoi de notre part, ou une trame reçue sur le flux.
     */
    private val lastActivity = ConcurrentHashMap<String, Long>()

    private fun touch(key: String) {
        lastActivity[key] = SystemClock.elapsedRealtime()
    }

    private fun recentlyActive(key: String): Boolean =
        lastActivity[key]?.let { SystemClock.elapsedRealtime() - it < PRIVATE_IDLE_MS } ?: false

    /** Mode privé, hors échange : ni composé, ni recomposé, ni accepté. */
    private suspend fun isQuiet(key: String): Boolean =
        !recentlyActive(key) && runCatching { contactRepository.getContact(key)?.isPrivate == true }.getOrDefault(false)

    /** Active ou coupe le mode privé. Activé : le flux ouvert se ferme tout de suite. */
    suspend fun setPrivate(contactPublicKey: String, on: Boolean) {
        val key = contactPublicKey.lowercase()
        contactRepository.setPrivate(key, on)
        if (on) {
            lastActivity.remove(key)
            disconnect(key)
        } else {
            prepare(key)
        }
    }

    /** Flux d'un contact en mode privé : fermé après [PRIVATE_IDLE_MS] sans échange. */
    private fun closeWhenIdle(link: TorLink) = scope.launch {
        val key = link.contactPublicKey
        // Contact ordinaire : rien à surveiller (activer le mode privé ferme le flux).
        if (runCatching { contactRepository.getContact(key)?.isPrivate }.getOrNull() != true) return@launch
        while (links[key] === link && link.isOpen) {
            delay(PRIVATE_CHECK_MS)
            if (links[key] === link && isQuiet(key)) {
                disconnect(key)
                break
            }
        }
    }

    /* ====================================================================== *
     *  Émission
     * ====================================================================== */

    /**
     * Envoie un message déjà chiffré au contact.
     *
     * @return true si les octets sont partis sur le flux. Ce n'est pas un
     *   accusé de réception : le pair peut fermer l'app dans la seconde.
     */
    private suspend fun send(key: String, message: Message): Sent {
        touch(key) // c'est nous qui écrivons : le mode privé laisse passer
        val payload = frameOf(message)
        repeat(2) {
            val link = runCatching { linkTo(key) }
                .onFailure { DebugLog.d(TAG) { "Contact injoignable : ${it.message}" } }
                .getOrNull() ?: return Sent.STUCK
            // Flux neuf : les messages partis sans accusé passent avant celui-ci.
            if (key in fresh) return Sent.FRESH
            // Contact trop ancien : le message attend sa mise à jour.
            if (!upToDate(key, link)) return Sent.STUCK
            if (withContext(Dispatchers.IO) { link.send(payload) }) return Sent.OK
        }
        return Sent.STUCK
    }

    private enum class Sent { OK, STUCK, FRESH }

    /**
     * Contacts dont un flux neuf vient d'être retenu : leur file remet d'abord
     * en attente ce qui est parti sans accusé. Fait par la file elle-même,
     * jamais à côté — sinon un message plus récent partait avant, et l'ancien,
     * renvoyé ensuite avec un compteur dépassé, était refusé pour de bon.
     */
    private val fresh = ConcurrentHashMap.newKeySet<String>()

    /** Une file par contact, réveillée par [flush], un flux neuf ou le retour de Tor. */
    private val wakes = ConcurrentHashMap<String, Channel<Unit>>()

    /**
     * Fait partir ce qui attend pour ce contact. À appeler après avoir rangé
     * un message PENDING ; sans effet s'il n'y a rien.
     */
    fun flush(contactPublicKey: String) {
        val key = contactPublicKey.lowercase()
        var created = false
        val wake = wakes.computeIfAbsent(key) { created = true; Channel(Channel.CONFLATED) }
        if (created) scope.launch { drain(key, wake) }
        wake.trySend(Unit)
    }

    /**
     * La file d'un contact : vit tant que l'app est ouverte, dort quand elle
     * est vide. Un échec arrête le tour (l'ordre des compteurs compte), puis
     * on retente après [OUTBOX_RETRY_MS] — ou dès qu'on la réveille.
     */
    private suspend fun drain(key: String, wake: Channel<Unit>) {
        var attempt = 0
        while (true) {
            val stuck = runCatching { sendPending(key) }.getOrDefault(true)
            if (!stuck) {
                attempt = 0
                wake.receive()
            } else {
                val wait = OUTBOX_RETRY_MS[minOf(attempt++, OUTBOX_RETRY_MS.lastIndex)]
                withTimeoutOrNull(wait) { wake.receive() }
            }
        }
    }

    /** @return vrai s'il reste des messages bloqués (contact injoignable). */
    private suspend fun sendPending(key: String): Boolean {
        val me = identityService.getIdentity() ?: return false
        val contact = contactRepository.getContact(key)
        if (contact == null || contact.isBlocked) return false
        round@ while (true) {
            if (fresh.remove(key)) {
                runCatching { messageRepository.requeueUnacked(me.publicKeyHex, key) }
                    .onFailure { fresh += key; throw it } // retenté au prochain tour
            }
            val now = System.currentTimeMillis()
            for (message in messageRepository.pendingTo(me.publicKeyHex, key)) {
                if (message.expiresAt <= now) continue // la purge TTL l'emportera
                if (frameOf(message).size > TorLink.MAX_FRAME) {
                    DebugLog.w(TAG, "Message trop volumineux pour un envoi unique")
                    messageRepository.updateStatus(message.id, MessageStatus.FAILED)
                    continue
                }
                when (send(key, message)) {
                    Sent.OK -> messageRepository.updateStatus(message.id, MessageStatus.SENT)
                    Sent.STUCK -> return true
                    Sent.FRESH -> continue@round
                }
            }
            return false
        }
    }

    /**
     * Trame d'appel. [dial] : composer le flux s'il n'existe pas (sonnerie) ;
     * sinon, flux déjà ouvert ou rien (la voix n'attend pas un circuit).
     */
    suspend fun sendCallFrame(contactPublicKey: String, frame: ByteArray, dial: Boolean): Boolean =
        sendFrame(contactPublicKey, dial, gated = true) { frame }

    /**
     * Trame construite une fois le flux ouvert et la version du contact connue
     * (OFFRE/ACCEPTE : leur format dépend de cette version). [build] reçoit
     * cette version ; null = rien à envoyer.
     */
    suspend fun sendCallFrame(contactPublicKey: String, dial: Boolean, build: (peerVersion: Long) -> ByteArray?): Boolean =
        sendFrame(contactPublicKey, dial, gated = true) { build(peerVersion(it)) }

    private suspend fun sendFrame(contactPublicKey: String, frame: ByteArray, dial: Boolean, gated: Boolean): Boolean =
        sendFrame(contactPublicKey, dial, gated) { frame }

    /** [gated] : refusé à un contact trop ancien (tout sauf les mises à jour). */
    private suspend fun sendFrame(
        contactPublicKey: String, dial: Boolean, gated: Boolean, build: (key: String) -> ByteArray?
    ): Boolean {
        val key = contactPublicKey.lowercase()
        touch(key)
        val link = if (dial) {
            runCatching { linkTo(key) }.getOrNull()
        } else {
            links[key]?.takeIf { it.isOpen }
        } ?: return false
        if (gated && !upToDate(key, link)) return false
        val frame = build(key) ?: return false
        return withContext(Dispatchers.IO) { link.send(frame) }
    }

    /**
     * Le flux du contact : celui qui existe, le nôtre une fois composé, ou
     * le sien s'il nous joint pendant que notre circuit se construit — sans
     * ça, un envoi restait bloqué jusqu'au délai de connexion (60 s) alors
     * que le contact était là.
     * Notre composition continue à part ; l'arbitrage de [register] tranche.
     */
    private suspend fun linkTo(contactKey: String): TorLink {
        links[contactKey]?.takeIf { it.isOpen }?.let { return it }
        val dialed = scope.async {
            dialing.getOrPut(contactKey) { Mutex() }.withLock {
                links[contactKey]?.takeIf { it.isOpen } ?: dial(contactKey)
            }
        }
        val arrived = scope.async {
            var link = links[contactKey]?.takeIf { it.isOpen }
            while (link == null) {
                delay(LINK_POLL_MS)
                link = links[contactKey]?.takeIf { it.isOpen }
            }
            link
        }
        return try {
            select {
                dialed.onAwait { it }
                arrived.onAwait { it }
            }
        } finally {
            arrived.cancel()
        }
    }

    /** Côté appelant : on lit le défi, on signe, on envoie HELLO. */
    private suspend fun dial(contactKey: String): TorLink {
        val contact = contactRepository.getContact(contactKey) ?: throw IOException("Contact inconnu")
        if (contact.isBlocked) throw IOException("Contact bloqué")
        val me = identityService.getIdentity() ?: throw IOException("Coffre fermé")
        val epoch = epochs[contactKey] ?: 0

        // Tor peut encore s'amorcer si l'on écrit juste après le déverrouillage.
        val ready = withTimeout(TOR_WAIT_MS) {
            tor.state.first { it is TorRuntime.State.Ready || it is TorRuntime.State.Failed }
        }
        if (ready !is TorRuntime.State.Ready) throw IOException("Tor indisponible")

        val socket = try {
            tor.connect(contact.onionAddress)
        } catch (e: Throwable) {
            // Deux échecs de suite : son descripteur onion a sans doute changé
            // (Tor neuf après une mise à jour) ; on le fait rechercher (1.3.6).
            if (dialFailures.merge(contactKey, 1, Int::plus)!! % 2 == 0) tor.refetchOnion(contact.onionAddress)
            throw e
        }
        dialFailures.remove(contactKey)
        return withContext(Dispatchers.IO) {
            try {
                socket.soTimeout = HANDSHAKE_TIMEOUT_MS
                val challenge = TorLink.readFrame(DataInputStream(socket.inputStream))
                if (challenge.size != 1 + TorLink.CHALLENGE_SIZE || challenge[0] != TorLink.VERSION) {
                    throw IOException("Défi illisible")
                }
                val mine = Hex.decode(me.publicKeyHex)
                val mySigning = Hex.decode(me.signingPublicKeyHex)
                val myOnion = (identityService.onionAddress() ?: throw IOException("Coffre fermé"))
                    .toByteArray(Charsets.US_ASCII)
                val binding = TorLink.helloBinding(
                    challenge.copyOfRange(1, challenge.size), mine, mySigning, myOnion, Hex.decode(contactKey)
                )
                val proof = cryptoManager.helloKeyProof(me.encryptionPrivateKeyHex, contactKey, binding)
                val signature = cryptoManager.sign(binding, me.signingPrivateKeyHex)
                TorLink.writeFrame(
                    DataOutputStream(socket.outputStream),
                    byteArrayOf(TorLink.VERSION) + mine + mySigning + myOnion + proof + signature
                )
                socket.soTimeout = 0
                // Le circuit a pu prendre 30 s : le contact a pu être bloqué
                // ou supprimé entre-temps (recomposition automatique).
                // Rescanné entre-temps : l'onion composé peut être celui d'un usurpateur.
                val still = contactRepository.getContact(contactKey)
                if (still == null || still.isBlocked ||
                    !still.onionAddress.equals(contact.onionAddress, ignoreCase = true)
                ) throw IOException("Contact retiré pendant la connexion")
            } catch (e: Throwable) {
                socket.close()
                throw e
            }
            register(TorLink(socket, contactKey, me.publicKeyHex.lowercase()), epoch)
                ?: throw IOException("Contact retiré pendant la connexion")
        }
    }

    /* ====================================================================== *
     *  Réception
     * ====================================================================== */

    /**
     * Côté appelé : défi, puis HELLO signé.
     *
     * Contact connu : la signature doit venir de SA clé enregistrée, et le
     * HELLO doit redire la même clé — sinon quelqu'un tente de parler sous
     * l'identité d'un contact.
     *
     * Inconnu : il avait notre lien (un seul scan suffit). La signature
     * prouve qu'il détient la clé Ed25519 annoncée ; il est ajouté comme non
     * confirmé, l'app le signale, l'utilisateur confirme ou bloque.
     */
    private suspend fun accept(socket: LocalSocket) = withContext(Dispatchers.IO) {
        val me = identityService.getIdentity() ?: throw IOException("Coffre fermé")
        socket.soTimeout = HANDSHAKE_TIMEOUT_MS
        val challenge = PinCrypto.randomBytes(TorLink.CHALLENGE_SIZE)
        TorLink.writeFrame(DataOutputStream(socket.outputStream), byteArrayOf(TorLink.VERSION) + challenge)

        val hello = TorLink.readFrame(DataInputStream(socket.inputStream))
        if (hello.size != TorLink.HELLO_SIZE || hello[0] != TorLink.VERSION) throw IOException("HELLO illisible")
        val theirKey = hello.copyOfRange(1, 33)
        val theirSigning = hello.copyOfRange(33, 65)
        val theirOnion = hello.copyOfRange(65, 65 + TorLink.ONION_SIZE)
        val proof = hello.copyOfRange(TorLink.PROOF_OFFSET, TorLink.SIGNATURE_OFFSET)
        val signature = hello.copyOfRange(TorLink.SIGNATURE_OFFSET, hello.size)
        val theirHex = Hex.toHexString(theirKey)
        val theirSigningHex = Hex.toHexString(theirSigning)
        if (theirHex.equals(me.publicKeyHex, ignoreCase = true)) throw IOException("Soi-même")

        val signed = TorLink.helloBinding(challenge, theirKey, theirSigning, theirOnion, Hex.decode(me.publicKeyHex))
        val known = contactRepository.getContact(theirHex)
        if (known != null) {
            if (known.isBlocked) throw IOException("Bloqué")
            if (known.isPrivate && !recentlyActive(theirHex)) throw IOException("Mode privé")
            if (!known.signingPublicKeyHex.equals(theirSigningHex, ignoreCase = true)) {
                throw IOException("Clé de signature différente de celle du contact")
            }
        }
        if (!cryptoManager.verify(signed, signature, theirSigningHex)) {
            throw IOException("Signature HELLO invalide")
        }
        // Il détient bien la clé X25519 annoncée : sinon, avec la clé publique
        // d'un autre (dans son QR), il occuperait sa place (v3, 1.3.5).
        if (!cryptoManager.verifyHelloKeyProof(me.encryptionPrivateKeyHex, theirHex, signed, proof)) {
            throw IOException("Preuve X25519 invalide")
        }
        if (known == null) {
            val onion = String(theirOnion, Charsets.US_ASCII)
            val stranger = Contact.fromPublicKeys(theirHex, theirSigningHex, onion)
                ?: throw IOException("Identité mal formée")
            if (!contactRepository.addUnconfirmed(stranger)) throw IOException("Trop de contacts non confirmés")
            // Une course avec un autre flux a pu écrire autre chose : on relit.
            val stored = contactRepository.getContact(theirHex)
            if (stored == null || stored.isBlocked ||
                !stored.signingPublicKeyHex.equals(theirSigningHex, ignoreCase = true)
            ) throw IOException("Contact non retenu")
        }
        socket.soTimeout = 0
        register(TorLink(socket, theirHex, theirHex), epochs[theirHex] ?: 0)
            ?: throw IOException("Contact retiré pendant la connexion")
    }

    /**
     * Ferme le flux d'un contact (bloqué, supprimé) : il n'est plus joignable
     * à l'instant. Une trame « bye » part d'abord : l'autre côté sait que la
     * fermeture est voulue et ne recompose pas — sinon, chez qui supprime,
     * le contact revenait aussitôt « non confirmé » (un seul scan suffit).
     */
    suspend fun disconnect(contactPublicKey: String) {
        val key = contactPublicKey.lowercase()
        epochs.merge(key, 1, Int::plus)
        redials.remove(key)?.cancel()
        graceTimers.remove(key)?.cancel()
        graced.remove(key)
        links.remove(key)?.let { link ->
            withContext(Dispatchers.IO) { link.send(BYE_FRAME) }
            link.close()
        }
        publishReachable()
    }

    /**
     * Range le flux, ou le ferme s'il perd l'arbitrage.
     * @return le flux retenu pour ce contact (pas forcément celui-ci) ;
     *   null si le contact a été retiré pendant la connexion ([epoch] dépassé).
     */
    private fun register(link: TorLink, epoch: Int): TorLink? {
        val kept = links.compute(link.contactPublicKey) { _, existing ->
            when {
                (epochs[link.contactPublicKey] ?: 0) != epoch -> existing
                existing == null || !existing.isOpen -> link
                // Même côté qui recompose : l'autre bout a vu l'ancien mourir.
                existing.initiatorPublicKey == link.initiatorPublicKey -> link.also { existing.close() }
                link.initiatorPublicKey < existing.initiatorPublicKey -> link.also { existing.close() }
                else -> existing
            }.also {
                // Neuf AVANT d'être visible : une file qui le trouverait dans
                // `links` sans la marque enverrait un message récent avant de
                // remettre en attente l'ancien, alors refusé (compteur dépassé).
                if (it === link) fresh += link.contactPublicKey
            }
        }
        if (kept == null) {
            link.close()
            return null
        }
        if (kept === link) {
            // Notre version en premier, avant toute autre trame du flux.
            peerVersions.remove(link.contactPublicKey)
            link.send(versionFrame())
            scope.launch { readLoop(link) }
            scope.launch { sendPhoto(link) }
            scope.launch { offerUpdate(link) }
            closeWhenIdle(link)
            flush(link.contactPublicKey)
            scope.launch { flushWipe(link.contactPublicKey) }
            pendingDeletes.remove(link.contactPublicKey)?.let { ids ->
                // Un nouvel échec les remet de côté (voir sendDelete).
                scope.launch { sendDelete(link.contactPublicKey, ids.toList()) }
            }
        } else {
            link.close()
        }
        publishReachable()
        return kept
    }

    private suspend fun readLoop(link: TorLink) = withContext(Dispatchers.IO) {
        var farewell = false
        while (link.isOpen) {
            val payload = link.receive() ?: break
            touch(link.contactPublicKey)
            val current = (peerVersions[link.contactPublicKey] ?: 0L) >= minPeer()
            if (CallProtocol.isCallFrame(payload)) {
                if (!current) continue
                _callFrames.tryEmit(link.contactPublicKey to payload)
                continue
            }
            if (UpdateProtocol.isData(payload)) {
                runCatching { updates.get().onData(link.contactPublicKey, payload) }
                continue
            }
            val control = controlFrame(payload)
            if (control != null) {
                runCatching {
                    val type = control.str("t")
                    // Contact trop ancien : seules les mises à jour (et l'au revoir) passent.
                    if (!current && type !in UPDATE_TYPES) return@runCatching
                    when (type) {
                        VERSION -> control.num("code")?.let { code ->
                            peerVersions[link.contactPublicKey] = code
                            markOutdated(link.contactPublicKey, code < minPeer())
                        }
                        ACK -> onAck(link.contactPublicKey, control)
                        STALE -> onStale(link.contactPublicKey, control)
                        ProfilePhotoService.FRAME_TYPE -> photos.receive(link.contactPublicKey, control)
                        NUDGE -> onNudge(link.contactPublicKey)
                        DELETE -> onDelete(link.contactPublicKey, control)
                        WIPE_REQ, WIPE_OK, WIPE_OFF, WIPE -> onWipe(link.contactPublicKey, type)
                        UpdateProtocol.OFFER -> updates.get().onOffer(link.contactPublicKey, control)
                        UpdateProtocol.GET -> if (isTrusted(link.contactPublicKey)) updates.get().onRequest(link, control)
                        UpdateProtocol.STOP -> updates.get().server.stop(link)
                        BYE -> farewell = true
                    }
                }.onFailure { DebugLog.w(TAG, "Trame de contrôle écartée") }
                if (farewell) break
                continue
            }
            if (!current) continue
            val stored = runCatching { onPayload(link.contactPublicKey, payload) }
                .onFailure { DebugLog.w(TAG, "Charge entrante écartée") }
                .getOrNull()
            // Hors du verrou d'arrivée : l'envoi peut bloquer sur un flux lent.
            if (stored?.stale == true) {
                link.send(receipt(STALE, stored.id))
            } else if (stored != null) {
                link.send(receipt(ACK, stored.id))
                // Morceau de fichier qui n'est pas le dernier : ni bip ni bandeau.
                if (stored.fresh && !FilePayload.isPartial(stored.id)) alerts.post(link.contactPublicKey, InAppAlerts.Kind.MESSAGE)
            }
        }
        // Confié à l'arrière-plan avec la mise à jour (1.3.8) : l'app se tue
        // dans l'instant, on ne touche plus à rien — surtout pas au flux.
        if (link.detached) return@withContext
        // false si on l'a fermé nous-mêmes (bloqué, supprimé) ou remplacé
        // par l'arbitrage : dans ces cas, rien à recomposer. Pas non plus
        // quand l'autre nous a dit au revoir (il nous a retiré).
        if (links.remove(link.contactPublicKey, link) && !farewell) reconnect(link.contactPublicKey)
        link.close()
        runCatching { updates.get().server.stop(link) }
        publishReachable()
    }

    /**
     * Range un message reçu, après avoir vérifié qu'il est légitime.
     *
     * Ordre : en-têtes cohérents avec le flux → signature et compteur
     * (verifyIncoming, sans déchiffrer) → le compteur avance → le message est
     * écrit, sans jamais écraser un message existant. Le compteur avance AVANT
     * l'écriture : un plantage entre les deux perd un message, il n'en laisse
     * jamais passer deux fois.
     *
     * Sous verrou : deux flux du même contact (pendant un arbitrage) ne
     * doivent pas lire le même compteur en même temps.
     *
     * @return le message à accuser (fresh : rangé à l'instant, pas un doublon) ;
     *   null s'il est écarté.
     */
    private suspend fun onPayload(contactPublicKey: String, payload: ByteArray, deferred: Boolean = false): Stored? = intake.withLock {
        val message = decode(payload) ?: run {
            DebugLog.w(TAG, "Message entrant malformé")
            return@withLock null
        }

        val me = identityService.getIdentity() ?: return@withLock null

        // Le flux est authentifié par la poignée de main, mais rien n'empêche
        // un pair de mentir sur les en-têtes du message lui-même. Il ne peut
        // prétendre être quelqu'un d'autre que celui dont on a ouvert la
        // session, ni écrire dans la conversation d'un tiers.
        if (!message.fromPublicKeyHex.equals(contactPublicKey, ignoreCase = true)) {
            DebugLog.w(TAG, "Expéditeur incohérent avec le canal — rejeté")
            return@withLock null
        }
        if (!message.toPublicKeyHex.equals(me.publicKeyHex, ignoreCase = true)) {
            DebugLog.w(TAG, "Message adressé à un tiers — rejeté")
            return@withLock null
        }

        val sender = contactRepository.getContact(contactPublicKey)
        if (sender == null || sender.isBlocked) {
            DebugLog.d(TAG) { "Message d'un contact non retenu — ignoré" }
            return@withLock null
        }

        // Déjà rangé : notre accusé s'est perdu, l'expéditeur renvoie. On
        // accuse de nouveau, sans rien réécrire ni avancer le compteur.
        if (messageRepository.isReceivedFrom(message.id, contactPublicKey)) {
            return@withLock if (deferred) null else Stored(message.id, fresh = false)
        }

        try {
            messageService.verifyIncoming(message, me, sender)
        } catch (e: Exception) {
            // Bien de ce contact, mais compteur dépassé : déjà reçu puis effacé
            // ici (conversation supprimée, TTL), ou arrivé trop tard. Refusé
            // quand même ; on le dit, sinon l'expéditeur le renvoie à chaque flux.
            val stale = !deferred && message.counter <= sender.lastReceivedCounter && runCatching {
                messageService.verifyIncoming(message, me, sender.copy(lastReceivedCounter = message.counter - 1))
            }.isSuccess
            if (stale) return@withLock Stored(message.id, fresh = false, stale = true)
            DebugLog.w(TAG, "Origine non vérifiée ou rejeu — rejeté")
            return@withLock null
        }
        contactRepository.markReceivedCounter(contactPublicKey, message.counter)

        // La durée de vie annoncée est recomptée sur NOTRE horloge, à
        // l'arrivée : l'horloge de l'expéditeur, en avance ou en retard, n'y
        // fait rien (un message daté d'un an dans le futur, avec 8 h de TTL,
        // reste ici 8 h). Rangé app fermée : le TTL part du déverrouillage,
        // pas de l'envoi — un message ne meurt pas avant d'avoir pu être lu.
        val ttl = (message.expiresAt - message.timestamp).coerceIn(0L, MessageService.MAX_TTL_MINUTES * 60_000L)
        val sane = message.copy(
            expiresAt = System.currentTimeMillis() + ttl,
            status = MessageStatus.DELIVERED
        )

        if (!messageRepository.addIncoming(sane)) {
            DebugLog.w(TAG, "Identifiant déjà pris — message écarté")
            return@withLock null
        }
        Stored(message.id, fresh = true)
    }

    /** [stale] : refusé (compteur dépassé), à signaler plutôt qu'à accuser. */
    private class Stored(val id: String, val fresh: Boolean, val stale: Boolean = false)

    /**
     * Message rangé app fermée par l'arrière-plan (phase 13), rouvert au
     * déverrouillage : même chemin qu'à l'arrivée, TTL compté à partir de
     * maintenant. L'accusé est déjà parti.
     */
    suspend fun ingest(contactPublicKey: String, payload: ByteArray) {
        val key = contactPublicKey.lowercase()
        val stored = onPayload(key, payload, deferred = true)
        if (stored != null && !FilePayload.isPartial(stored.id)) alerts.post(key, InAppAlerts.Kind.MESSAGE)
    }

    /**
     * L'accusé du contact : le message est rangé chez lui. Aucune signature
     * de plus — le flux est déjà authentifié, et seul ce contact peut
     * marquer NOS messages envoyés à LUI (voir MessageDao.markDelivered).
     */
    private suspend fun onAck(contactPublicKey: String, frame: JsonObject) {
        val id = frame.str("id")?.takeIf { it.length <= MAX_ID_LENGTH } ?: return
        val me = identityService.getIdentity() ?: return
        messageRepository.markDeliveredBy(id, me.publicKeyHex, contactPublicKey)
    }

    /**
     * Le contact a déjà dépassé ce compteur : il a reçu ce message, puis l'a
     * effacé (vue unique, durée de vie, conversation supprimée) — l'accusé
     * s'était perdu. Les envois partent dans l'ordre des compteurs (file
     * unique, flux neuf marqué avant publication) : un compteur dépassé ne
     * peut plus venir d'un message sauté. On le tient pour remis, sans renvoi.
     */
    private suspend fun onStale(contactPublicKey: String, frame: JsonObject) {
        val id = frame.str("id")?.takeIf { it.length <= MAX_ID_LENGTH } ?: return
        val me = identityService.getIdentity() ?: return
        messageRepository.markDeliveredBy(id, me.publicKeyHex, contactPublicKey)
    }

    /* ====================================================================== *
     *  Sérialisation
     * ====================================================================== */

    /**
     * Accusé (ACK) ou refus (STALE) : l'identifiant seul. L'accusé dit
     * « rangé sur le téléphone », jamais « lu » — aucun accusé de lecture ne part.
     */
    private fun receipt(type: String, messageId: String): ByteArray =
        buildJsonObject {
            put("v", 1)
            put("t", type)
            put("id", messageId)
        }.toString().toByteArray(Charsets.UTF_8)

    /**
     * Trame de contrôle (accusé, photo) : un objet JSON avec un champ « t ».
     * Un message n'en a jamais. Le préfixe évite d'analyser deux fois
     * chaque message.
     */
    private fun controlFrame(payload: ByteArray): JsonObject? {
        if (payload.size < CONTROL_PREFIX.size ||
            !payload.copyOfRange(0, CONTROL_PREFIX.size).contentEquals(CONTROL_PREFIX)
        ) return null
        return runCatching {
            json.parseToJsonElement(payload.toString(Charsets.UTF_8)) as? JsonObject
        }.getOrNull()?.takeIf { it.str("t") != null }
    }

    /** Un signe : seuls les contacts confirmés font vibrer, comme pour les appels. */
    private suspend fun onNudge(contactPublicKey: String) {
        val contact = contactRepository.getContact(contactPublicKey) ?: return
        if (contact.isTrusted && !contact.isBlocked) alerts.post(contactPublicKey, InAppAlerts.Kind.NUDGE)
    }

    /**
     * Faire signe : une trame sans contenu, sur le flux du contact (composé
     * s'il le faut). Arrive s'il a l'app ouverte, ou « Rester joignable »
     * actif ; sinon rien n'attend.
     */
    suspend fun sendNudge(contactPublicKey: String): Boolean {
        val frame = buildJsonObject {
            put("v", 1)
            put("t", NUDGE)
        }.toString().toByteArray(Charsets.UTF_8)
        return sendCallFrame(contactPublicKey, frame, dial = true)
    }

    /**
     * Effacer « pour tous » : nos messages, chez le contact aussi, sans
     * bandeau ni bip — la bulle disparaît, rien ne dit qu'elle a existé.
     * Injoignable : gardé en mémoire et renvoyé à la prochaine ouverture du flux.
     * @return true si tout est parti.
     */
    suspend fun sendDelete(contactPublicKey: String, ids: List<String>): Boolean {
        val key = contactPublicKey.lowercase()
        val clean = ids.filter { it.length <= MAX_ID_LENGTH }.distinct()
        if (clean.isEmpty()) return true
        for (batch in clean.chunked(MAX_DELETE_IDS)) {
            val frame = buildJsonObject {
                put("v", 1)
                put("t", DELETE)
                put("ids", JsonArray(batch.map { JsonPrimitive(it) }))
            }.toString().toByteArray(Charsets.UTF_8)
            if (!sendCallFrame(key, frame, dial = true)) {
                pendingDeletes.getOrPut(key) { ConcurrentHashMap.newKeySet() }.addAll(clean)
                return false
            }
        }
        return true
    }

    /** Le contact efface ses propres messages chez nous : jamais les nôtres, jamais ceux d'un tiers. */
    private suspend fun onDelete(contactPublicKey: String, frame: JsonObject) {
        val ids = (frame["ids"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { id -> id.length <= MAX_ID_LENGTH } }
            ?.take(MAX_DELETE_IDS)
            ?.takeIf { it.isNotEmpty() } ?: return
        val me = identityService.getIdentity() ?: return
        messageRepository.deleteReceived(ids, from = contactPublicKey, to = me.publicKeyHex)
    }

    /* ---------------- Effacement à deux (1.3.7) ----------------
     *
     * Chacun peut tout effacer, pour les deux, à tout moment — mais seulement
     * si LES DEUX y ont consenti. A propose (wipe-req), B accepte (wipe-ok) ou
     * refuse (wipe-off) ; chacun peut retirer son accord seul (wipe-off).
     * « wipe » n'est appliqué que si notre propre accord est actif : un pair
     * ne peut rien effacer chez nous sans notre consentement. Une trame qui
     * n'a pas pu partir est gardée en base et renvoyée à l'ouverture du flux.
     * Une version d'avant 1.3.7 ignore ces trames : la demande reste en attente.
     */

    /** Proposer — ou accepter, si c'est lui qui l'a proposé. */
    suspend fun wipeEnable(contactPublicKey: String) {
        val key = contactPublicKey.lowercase()
        val contact = contactRepository.getContact(key) ?: return
        when (contact.wipeState) {
            Contact.WIPE_TO_ANSWER -> {
                contactRepository.setWipeState(key, Contact.WIPE_ON)
                sendWipeControl(key, WIPE_OK, Contact.WIPE_SEND_OK)
            }
            Contact.WIPE_OFF, Contact.WIPE_ASKED -> {
                contactRepository.setWipeState(key, Contact.WIPE_ASKED)
                sendWipeControl(key, WIPE_REQ, Contact.WIPE_SEND_REQ)
            }
        }
    }

    /** Refuser sa proposition, ou retirer notre accord : seul, sans lui demander. */
    suspend fun wipeDisable(contactPublicKey: String) {
        val key = contactPublicKey.lowercase()
        contactRepository.setWipeState(key, Contact.WIPE_OFF)
        contactRepository.setWipePending(key, false)
        sendWipeControl(key, WIPE_OFF, Contact.WIPE_SEND_OFF)
    }

    /**
     * Tout effacer, chez nous et chez lui. Seulement si les deux ont consenti.
     * @return false si le consentement n'est pas actif.
     */
    suspend fun wipeAll(contactPublicKey: String): Boolean {
        val key = contactPublicKey.lowercase()
        val contact = contactRepository.getContact(key) ?: return false
        if (contact.wipeState != Contact.WIPE_ON) return false
        val me = identityService.getIdentity() ?: return false
        messageRepository.deleteConversation(me.publicKeyHex, key)
        if (!sendCallFrame(key, controlOf(WIPE), dial = true)) contactRepository.setWipePending(key, true)
        return true
    }

    private suspend fun onWipe(contactPublicKey: String, type: String?) {
        val key = contactPublicKey.lowercase()
        val contact = contactRepository.getContact(key) ?: return
        when (type) {
            WIPE_REQ -> when (contact.wipeState) {
                Contact.WIPE_OFF -> contactRepository.setWipeState(key, Contact.WIPE_TO_ANSWER)
                // Proposé des deux côtés à la fois : c'est un double accord.
                Contact.WIPE_ASKED -> {
                    contactRepository.setWipeState(key, Contact.WIPE_ON)
                    sendWipeControl(key, WIPE_OK, Contact.WIPE_SEND_OK)
                }
                Contact.WIPE_ON -> sendWipeControl(key, WIPE_OK, Contact.WIPE_SEND_OK)
            }
            WIPE_OK -> when (contact.wipeState) {
                Contact.WIPE_ASKED -> contactRepository.setWipeState(key, Contact.WIPE_ON)
                // Il nous croit d'accord alors que nous ne le sommes plus : on le redit.
                Contact.WIPE_OFF -> sendWipeControl(key, WIPE_OFF, Contact.WIPE_SEND_OFF)
            }
            WIPE_OFF -> {
                contactRepository.setWipeState(key, Contact.WIPE_OFF)
                contactRepository.setWipePending(key, false)
            }
            // Accepté aussi si c'est nous qui l'avions proposé : notre accord est
            // donné, et son « wipe-ok » a pu arriver après son « wipe ».
            WIPE -> if (contact.wipeState == Contact.WIPE_ON || contact.wipeState == Contact.WIPE_ASKED) {
                if (contact.wipeState == Contact.WIPE_ASKED) contactRepository.setWipeState(key, Contact.WIPE_ON)
                val me = identityService.getIdentity() ?: return
                messageRepository.deleteConversation(me.publicKeyHex, key)
            }
        }
    }

    /** Envoie, ou garde pour la prochaine ouverture du flux. */
    private suspend fun sendWipeControl(key: String, type: String, outbox: Int) {
        val sent = sendCallFrame(key, controlOf(type), dial = true)
        contactRepository.setWipeOutbox(key, if (sent) 0 else outbox)
    }

    /** Flux neuf : ce qui n'avait pas pu partir part maintenant. */
    private suspend fun flushWipe(key: String) {
        val contact = runCatching { contactRepository.getContact(key) }.getOrNull() ?: return
        val type = when (contact.wipeOutbox) {
            Contact.WIPE_SEND_REQ -> WIPE_REQ
            Contact.WIPE_SEND_OK -> WIPE_OK
            Contact.WIPE_SEND_OFF -> WIPE_OFF
            else -> null
        }
        if (type != null) sendWipeControl(key, type, contact.wipeOutbox)
        if (contact.wipePending && contact.wipeState == Contact.WIPE_ON &&
            sendCallFrame(key, controlOf(WIPE), dial = false)
        ) contactRepository.setWipePending(key, false)
    }

    private fun controlOf(type: String): ByteArray = buildJsonObject {
        put("v", 1)
        put("t", type)
    }.toString().toByteArray(Charsets.UTF_8)

    private suspend fun isTrusted(key: String): Boolean =
        runCatching { contactRepository.getContact(key)?.let { it.isTrusted && !it.isBlocked } }.getOrNull() == true

    /**
     * Notre version signée, si on en a une à transmettre (phase 14) : offerte
     * aux contacts confirmés — les seuls qu'on servira. Les non confirmés
     * (1.3.8 et plus) en reçoivent l'annonce seule : leurs 5 jours commencent,
     * ils la prendront au diffuseur ou chez un contact qui les a confirmés.
     * Une version plus ancienne prendrait l'annonce pour une offre et nous
     * demanderait le fichier en vain : elle n'a rien.
     */
    private suspend fun offerUpdate(link: TorLink) {
        val server = runCatching { updates.get().server }.getOrNull() ?: return
        val key = link.contactPublicKey
        if (isTrusted(key)) {
            server.offerFrame()?.let { frame -> withContext(Dispatchers.IO) { link.send(frame) } }
            return
        }
        val contact = runCatching { contactRepository.getContact(key) }.getOrNull() ?: return
        if (contact.isBlocked) return
        withTimeoutOrNull(VERSION_WAIT_MS) { while (!peerVersions.containsKey(key) && link.isOpen) delay(100) }
        if ((peerVersions[key] ?: 0L) < ANNOUNCE_MIN_VERSION) return
        server.offerFrame(announceOnly = true)?.let { frame -> withContext(Dispatchers.IO) { link.send(frame) } }
    }

    /** Paquet importé en cours de session : les flux déjà ouverts l'apprennent aussi. */
    suspend fun offerUpdateToAll() {
        links.values.filter { it.isOpen }.forEach { offerUpdate(it) }
    }

    /** Ce contact nous envoie la mise à jour : son flux doit pouvoir être confié à l'arrière-plan. */
    fun markUpdateSource(contactPublicKey: String) {
        links[contactPublicKey]?.detachable = true
    }

    /** Version annoncée par ce contact sur son flux (0 si inconnue). */
    fun peerVersion(contactPublicKey: String): Long = peerVersions[contactPublicKey] ?: 0L

    /**
     * Juste avant que l'app se tue (1.3.8) : le flux de ce contact, arrêté
     * entre deux trames, pour que l'arrière-plan continue le téléchargement
     * dessus. Null s'il n'y en a pas, ou pas à temps.
     */
    fun detachLink(contactPublicKey: String, timeoutMs: Long): android.os.ParcelFileDescriptor? =
        links[contactPublicKey]?.takeIf { it.isOpen }?.detach(timeoutMs)

    /** Trame de mise à jour vers un contact ; [dial] : composer le flux s'il n'existe pas. */
    suspend fun sendUpdateFrame(contactPublicKey: String, frame: ByteArray, dial: Boolean): Boolean =
        sendFrame(contactPublicKey, frame, dial, gated = false)

    /** Notre photo (ou son absence) à ce contact, selon le réglage de visibilité. */
    private suspend fun sendPhoto(link: TorLink) {
        runCatching {
            val contact = contactRepository.getContact(link.contactPublicKey) ?: return
            if (!upToDate(link.contactPublicKey, link)) return
            val frame = photos.frameFor(contact)
            withContext(Dispatchers.IO) { link.send(frame) }
        }.onFailure { DebugLog.w(TAG, "Photo non envoyée") }
    }

    private fun encode(message: Message): JsonObject = buildJsonObject {
        put("v", 1)
        put("id", message.id)
        put("from", message.fromPublicKeyHex)
        put("to", message.toPublicKeyHex)
        put("ts", message.timestamp)
        put("exp", message.expiresAt)
        put("ctr", message.counter)
        put("content", Hex.toHexString(message.content))
        message.nonce?.let { put("nonce", Hex.toHexString(it)) }
        message.tag?.let { put("tag", Hex.toHexString(it)) }
        message.ephemeralPublicKey?.let { put("epk", Hex.toHexString(it)) }
        message.signature?.let { put("sig", Hex.toHexString(it)) }
    }

    /**
     * La trame d'un message : JSON hexadécimal s'il tient (comme toujours),
     * sinon trame binaire (morceau de fichier, voir MessageFrame).
     */
    private fun frameOf(message: Message): ByteArray {
        val fields = encode(message)
        val text = fields.toString().toByteArray(Charsets.UTF_8)
        if (text.size <= TorLink.MAX_FRAME) return text
        val header = JsonObject(fields - "content").toString().toByteArray(Charsets.UTF_8)
        return MessageFrame.build(header, message.content)
    }

    private fun decode(payload: ByteArray): Message? {
        val binary = MessageFrame.split(payload)
        val frame = runCatching {
            json.parseToJsonElement((binary?.first ?: payload).toString(Charsets.UTF_8)) as? JsonObject
        }.getOrNull() ?: return null

        val id = frame.str("id") ?: return null
        val from = frame.str("from")?.lowercase() ?: return null
        val to = frame.str("to")?.lowercase() ?: return null
        val content = binary?.second ?: frame.str("content")?.hexOrNull() ?: return null
        val timestamp = frame.num("ts") ?: return null
        val expiresAt = frame.num("exp") ?: return null

        if (from.length != 64 || to.length != 64) return null
        if (content.isEmpty()) return null

        return Message(
            id = id,
            content = content,
            fromPublicKeyHex = from,
            toPublicKeyHex = to,
            timestamp = timestamp,
            expiresAt = expiresAt,
            status = MessageStatus.DELIVERED,
            nonce = frame.str("nonce")?.hexOrNull(),
            tag = frame.str("tag")?.hexOrNull(),
            ephemeralPublicKey = frame.str("epk")?.hexOrNull(),
            signature = frame.str("sig")?.hexOrNull(),
            counter = frame.num("ctr") ?: 0L,
            // Notre horloge : celle du contact peut avancer ou retarder (1.3.8).
            receivedAt = System.currentTimeMillis()
        )
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.num(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull

    private fun String.hexOrNull(): ByteArray? = runCatching { Hex.decode(this) }.getOrNull()

    companion object {
        private const val TAG = "MessageTransport"
        private const val TOR_WAIT_MS = 180_000L
        /** Un défi et une signature : quelques centaines d'octets, même sur Tor lent. */
        private const val HANDSHAKE_TIMEOUT_MS = 10_000
        private const val MAX_PENDING_HANDSHAKES = 8
        /** Circuits de rendez-vous construits en même temps par [rejoin]. */
        private const val MAX_PARALLEL_DIALS = 4
        /** Mode privé : le flux se ferme après une minute sans échange. */
        private const val PRIVATE_IDLE_MS = 60_000L
        private const val PRIVATE_CHECK_MS = 10_000L
        private const val ACK = "ack"
        private const val STALE = "stale"
        private const val PRESENCE_GRACE_MS = 10_000L
        /** Quatre tentatives en une quarantaine de secondes, puis on attend le prochain envoi. */
        private val REDIAL_DELAYS_MS = longArrayOf(2_000, 5_000, 10_000, 20_000)
        private const val NUDGE = "nudge"
        /** Effacer pour tous (1.3.2). */
        private const val DELETE = "del"
        private const val WIPE_REQ = "wipe-req"
        private const val WIPE_OK = "wipe-ok"
        private const val WIPE_OFF = "wipe-off"
        private const val WIPE = "wipe"
        /** Identifiants par trame : bien sous le plafond de 64 Kio. */
        private const val MAX_DELETE_IDS = 200
        private const val BYE = "bye"
        private const val VERSION = "ver"
        /** Ce qui passe encore avec un contact trop ancien. */
        private val UPDATE_TYPES = setOf(VERSION, BYE, UpdateProtocol.OFFER, UpdateProtocol.GET, UpdateProtocol.STOP)
        /** Attente de la version du contact après l'ouverture du flux. */
        private const val VERSION_WAIT_MS = 8_000L
        /** Première version qui comprend l'annonce seule (1.3.8). */
        private const val ANNOUNCE_MIN_VERSION = 15L
        private val BYE_FRAME = "{\"v\":1,\"t\":\"bye\"}".toByteArray(Charsets.UTF_8)
        /** Début commun de nos trames de contrôle (buildJsonObject garde l'ordre). */
        private val CONTROL_PREFIX = "{\"v\":1,\"t\":\"".toByteArray(Charsets.UTF_8)
        private const val MAX_ID_LENGTH = 64
        /** Entre deux tentatives de la file : vite au début, puis toutes les 15 s. */
        // 1.3.6 : 15 s au plus (30 avant) ; avec l'attente de connexion, l'écart montait à 2 min.
        private val OUTBOX_RETRY_MS = longArrayOf(3_000, 5_000, 10_000, 15_000)
        private const val LINK_POLL_MS = 500L
    }
}
