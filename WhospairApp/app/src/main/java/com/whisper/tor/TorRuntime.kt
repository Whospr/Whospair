package com.whisper.tor

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.Network
import android.os.IBinder
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import com.whisper.services.IdentityService
import com.whisper.util.CrashReport
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.freehaven.tor.control.TorControlConnection
import org.torproject.jni.TorService
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tor, embarqué dans l'app. Aucun serveur, aucun Orbot.
 *
 * ### Ce qui tourne
 *
 * Le démon Tor du Guardian Project (tor-android), DANS notre processus : il
 * démarre au déverrouillage et meurt avec l'app en arrière-plan. Pas de
 * service de premier plan, pas de notification — il n'y a rien à maintenir.
 *
 * ### Aucun port réseau ouvert sur le téléphone
 *
 * Un port TCP sur 127.0.0.1, n'importe quelle autre app du téléphone peut s'y
 * connecter : se servir de notre Tor, ou sonder notre service onion. Ici tout
 * passe par des sockets Unix rangées dans un dossier privé de l'app (0700) :
 *   - socks.sock : nos connexions SORTANTES vers les .onion des contacts.
 *     Marquée OnionTrafficOnly — impossible d'en sortir vers l'internet clair.
 *   - hs.sock    : les connexions ENTRANTES de notre service onion.
 *   - le port de contrôle, lui aussi en socket Unix (géré par TorService).
 *
 * ### La clé onion ne touche pas le disque
 *
 * Pas de HiddenServiceDir (qui écrirait la clé en clair dans un fichier) :
 * le service est créé par ADD_ONION, la clé passe en mémoire par le port de
 * contrôle, et disparaît avec la connexion de contrôle — donc avec le
 * processus. DiscardPK : Tor ne nous la renvoie même pas.
 *
 * ### Un seul Tor avec « Rester joignable » (phase 13)
 *
 * L'option active, le démon tourne dans le processus `:reach`, app ouverte
 * comme fermée : l'adresse n'est publiée qu'une fois, ses points
 * d'introduction ne changent pas quand on ouvre ou ferme l'app. L'app
 * ouverte s'en sert (useShared) : même socks.sock, et elle prend hs.sock —
 * Tor livre chaque connexion entrante à qui écoute sur ce chemin.
 *
 * ### Réseau perdu
 *
 * Seul, Tor met longtemps à revenir après une coupure : il garde ses
 * connexions mortes et espace ses tentatives. Un chien de garde l'interroge
 * toutes les 15 s (circuit établi ? réseau vivant ?) et affiche la
 * reconnexion ; au retour du réseau, ou s'il reste bloqué, DisableNetwork
 * 1 puis 0 le fait tout rouvrir sur-le-champ.
 */
@Singleton
class TorRuntime(
    private val context: Context,
    /** Graine de la clé onion, copie effacée après usage. Null coffre fermé. */
    private val onionSeed: () -> ByteArray?,
    /** TorService dans l'app, ReachTorService dans le processus d'arrière-plan. */
    private val serviceClass: Class<out TorService>,
    /**
     * Adresses de plus, servies par la même écoute (le diffuseur publie sa
     * seconde adresse, de secours). Copies effacées après usage.
     */
    private val extraSeeds: () -> List<ByteArray> = { emptyList() }
) {
    @Inject constructor(@ApplicationContext context: Context, identity: IdentityService) :
        this(context, identity::onionSeed, TorService::class.java)

    sealed interface State {
        data object Off : State
        /** Amorçage du réseau Tor, 0 à 100. [reconnecting] : déjà publié, réseau perdu. */
        data class Starting(val progress: Int, val reconnecting: Boolean = false) : State
        /** Service onion publié, joignable par les contacts. */
        data class Ready(val onionAddress: String) : State
        /** [fatal] : le démon est mort, il ne redémarre pas dans ce processus. */
        data class Failed(val reason: String, val fatal: Boolean = false) : State
    }

    private val _state = MutableStateFlow<State>(State.Off)
    val state = _state.asStateFlow()

    /**
     * Connexions entrantes, brutes. Rien n'est authentifié à ce stade :
     * n'importe qui connaissant l'adresse peut se connecter. C'est la couche
     * au-dessus qui exige la poignée de main signée par un contact connu.
     */
    private val _incoming = Channel<LocalSocket>(capacity = 16)
    val incoming: ReceiveChannel<LocalSocket> = _incoming

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val socketDir: File by lazy {
        context.getDir(SOCKET_DIR, Context.MODE_PRIVATE).also {
            // Tor refuse une socket Unix dans un dossier lisible par d'autres.
            Os.chmod(it.absolutePath, 448) // 0700
        }
    }
    private val socksPath get() = File(socketDir, "socks.sock").absolutePath
    private val servicePath get() = File(socketDir, "hs.sock").absolutePath

    @Volatile private var serviceAlive = false

    /** Faux dans `:reach` pendant que l'app ouverte écoute elle-même. */
    @Volatile var serveIncoming = true

    /** Vrai pendant que l'app se sert du Tor de `:reach`. */
    @Volatile private var shared = false

    /** Connexion de contrôle, dès qu'elle existe : [kick] s'en sert. */
    @Volatile private var control: TorControlConnection? = null
    @Volatile private var lastKick = 0L

    /** Réseau Android par défaut, suivi pour réagir à sa perte et à son retour. */
    @Volatile private var hasNetwork = true
    @Volatile private var network: Network? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(net: Network) {
            // Le premier appel décrit le réseau déjà là ; les suivants, un réseau neuf.
            val changed = network !== null || !hasNetwork
            network = net
            hasNetwork = true
            if (changed) scope.launch { kick() }
        }

        override fun onLost(net: Network) {
            if (net != network) return
            network = null
            hasNetwork = false
            if (_state.value is State.Ready) _state.value = State.Starting(100, reconnecting = true)
        }
    }

    private val connectivity get() =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as TorService.LocalBinder).service
            serviceAlive = true
            // Un échec (réseau absent, amorçage trop long) n'est pas définitif :
            // on retente tant que le démon tourne. Avant, Tor restait en
            // échec jusqu'au redémarrage de l'app.
            scope.launch {
                var wait = RETRY_MIN_MS
                while (serviceAlive) {
                    val result = runCatching { publish(service) }
                    if (result.isSuccess) break
                    _state.value = State.Failed(result.exceptionOrNull()?.message ?: "Tor")
                    delay(wait)
                    wait = minOf(wait * 2, RETRY_MAX_MS)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // Le démon est mort : il ne redémarre pas dans le même processus.
            serviceAlive = false
            control = null
            runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
            _state.value = State.Failed("Tor s'est arrêté", fatal = true)
        }
    }

    /** Au déverrouillage. Une seule fois par processus. */
    fun start() {
        if (_state.value != State.Off || shared) return
        _state.value = State.Starting(0)
        // Écrit AVANT le démarrage : TorService lit ce fichier en se lançant.
        // Nos lignes remplacent celles du torrc par défaut de la bibliothèque
        // (SOCKSPort TCP, HTTPTunnelPort), qui ouvriraient des ports locaux.
        //
        // SocksPort 0 ici, la socket Unix vient plus tard (openSocks) : au
        // démarrage, TorService lit le port SOCKS avec Integer.parseInt, et
        // un chemin unix: fait planter son fil, donc l'app entière.
        TorService.getTorrc(context).writeText(
            """
            SocksPort 0
            HTTPTunnelPort 0
            SafeLogging 1
            AvoidDiskWrites 1
            """.trimIndent() + "\n" + debugLog()
        )
        context.bindService(Intent(context, serviceClass), connection, Context.BIND_AUTO_CREATE)
        // Sans surveillance réseau, le chien de garde suffit : on ne perd que la réaction immédiate.
        runCatching { connectivity?.registerDefaultNetworkCallback(networkCallback) }
    }

    /**
     * App ouverte, « Rester joignable » actif : le démon est celui de
     * `:reach`, [status] en lit l'état (null : processus mort). On écoute
     * tout de suite — Tor doit trouver hs.sock dès la première visite. S'il
     * meurt, [onLost] relance un Tor dans l'app.
     */
    fun useShared(status: () -> State?, onLost: () -> Unit) {
        if (_state.value != State.Off || shared) return
        shared = true
        _state.value = State.Starting(0)
        scope.launch {
            while (true) {
                val s = runCatching(status).getOrNull()
                if (s == null || (s is State.Failed && s.fatal)) break
                // Sans hs.sock, personne ne nous joint : l'état de `:reach`
                // (souvent « prêt ») ne doit pas le masquer. Retenté à chaque relevé.
                val heard = runCatching { listen() }
                _state.value = heard.exceptionOrNull()
                    ?.let { State.Failed("Écoute onion impossible : ${it.message}") } ?: s
                delay(if (s is State.Ready) SHARED_POLL_READY_MS else SHARED_POLL_MS)
            }
            shared = false
            _state.value = State.Off
            onLost()
        }
    }

    /** Debug uniquement : journal de Tor récupéré par CrashReport au lancement suivant. */
    private fun debugLog(): String =
        if (CrashReport.isEnabled(context)) {
            "Log notice file ${File(context.filesDir, CrashReport.TOR_LOG_FILE).absolutePath}\n"
        } else ""

    private suspend fun publish(service: TorService) {
        val control = withTimeout(CONTROL_TIMEOUT_MS) {
            var c: TorControlConnection? = service.torControlConnection
            while (c == null) { delay(250); c = service.torControlConnection }
            c
        }
        this.control = control
        // TorService publie la connexion AVANT de s'authentifier : une commande
        // envoyée dans cet intervalle ferait fermer la connexion par Tor.
        delay(CONTROL_SETTLE_MS)

        withTimeout(BOOTSTRAP_TIMEOUT_MS) {
            while (true) {
                val phase = control.getInfo("status/bootstrap-phase")
                val progress = PROGRESS.find(phase)?.groupValues?.get(1)?.toInt() ?: 0
                _state.value = State.Starting(progress)
                if (progress >= 100) break
                delay(500)
            }
        }

        // Longtemps après la lecture des ports par TorService (voir start()).
        control.setConf("SocksPort", "unix:$socksPath OnionTrafficOnly")

        // L'écoute d'abord : Tor doit trouver la socket dès la première visite.
        if (serveIncoming) listen()

        val seed = onionSeed() ?: error("Coffre fermé")
        val expected = OnionAddress.fromSeed(seed)
        val expanded = OnionAddress.expandedSecret(seed)
        seed.fill(0)
        val reply = try {
            control.addOnion(
                "ED25519-V3:" + Base64.getEncoder().encodeToString(expanded),
                mapOf(VIRTUAL_PORT to "unix:$servicePath"),
                listOf("DiscardPK")
            )
        } finally {
            expanded.fill(0)
        }

        // Garde-fou : Tor doit publier EXACTEMENT l'adresse du QR. Sinon la
        // conversion de clé est fausse, et les contacts sonneraient dans le vide.
        val published = reply.values.map { it.removeSuffix(".onion").lowercase() }
        check(expected in published) { "Tor publie une autre adresse que celle du QR" }

        for (extra in extraSeeds()) {
            val address = OnionAddress.fromSeed(extra)
            val secret = OnionAddress.expandedSecret(extra)
            extra.fill(0)
            val added = try {
                control.addOnion(
                    "ED25519-V3:" + Base64.getEncoder().encodeToString(secret),
                    mapOf(VIRTUAL_PORT to "unix:$servicePath"),
                    listOf("DiscardPK")
                )
            } finally {
                secret.fill(0)
            }
            check(address in added.values.map { it.removeSuffix(".onion").lowercase() }) {
                "Tor publie une autre adresse que celle attendue"
            }
        }

        _state.value = State.Ready(expected)
        scope.launch { watch(control, expected) }
    }

    /**
     * Chien de garde : Tor est-il encore relié ? Relevé toutes les 15 s, 5 s
     * pendant une coupure pour voir le retour plus tôt. Tor ne republie rien
     * à la main : le service onion survit tant que la connexion de contrôle
     * vit, Tor refait lui-même ses points d'introduction.
     */
    private suspend fun watch(control: TorControlConnection, address: String) {
        var downSince = 0L
        while (serviceAlive) {
            val live = hasNetwork && runCatching {
                control.getInfo("network-liveness") == "up" &&
                    control.getInfo("status/circuit-established") == "1"
            }.getOrElse { return } // contrôle fermé : le démon meurt, onServiceDisconnected le dira
            val now = SystemClock.elapsedRealtime()
            val s = _state.value
            if (live) {
                downSince = 0L
                if (s is State.Starting && s.reconnecting) _state.value = State.Ready(address)
            } else {
                if (s is State.Ready) _state.value = State.Starting(100, reconnecting = true)
                if (downSince == 0L) downSince = now
                // Réseau revenu mais Tor toujours coupé : on le secoue, au plus une fois par minute.
                else if (hasNetwork && now - downSince >= STUCK_MS && now - lastKick >= KICK_GAP_MS) kick()
            }
            delay(if (live) WATCH_MS else WATCH_DOWN_MS)
        }
    }

    /**
     * DisableNetwork 1 puis 0 : Tor ferme ses connexions mortes (celles de
     * l'ancien réseau) et repart tout de suite, sans attendre ses délais de
     * nouvelle tentative. Les flux ouverts tombent — ils l'étaient déjà.
     */
    private fun kick() {
        val c = control ?: return
        lastKick = SystemClock.elapsedRealtime()
        runCatching {
            c.setConf("DisableNetwork", "1")
            c.setConf("DisableNetwork", "0")
        }
    }

    // Gardées en champ : la socket liée porte le descripteur du serveur, et
    // le ramasse-miettes la fermerait sinon.
    private var boundSocket: LocalSocket? = null
    @Volatile private var server: LocalServerSocket? = null

    /**
     * Prend hs.sock. Le fichier d'un autre processus est remplacé : les
     * connexions suivantes arrivent ici, l'ancien serveur n'en reçoit plus.
     */
    @Synchronized
    fun listen() {
        if (server != null) return // déjà à l'écoute (nouvelle tentative de publication)
        File(servicePath).delete() // reste d'un processus précédent : sinon « adresse déjà utilisée »
        val bound = LocalSocket(LocalSocket.SOCKET_STREAM).apply {
            bind(LocalSocketAddress(servicePath, LocalSocketAddress.Namespace.FILESYSTEM))
        }
        val listening = LocalServerSocket(bound.fileDescriptor)
        boundSocket = bound
        server = listening
        scope.launch {
            // Une exception non rattrapée ici ferait planter l'app entière.
            runCatching {
                while (isActive) {
                    val client = listening.accept()
                    if (server !== listening) { client.close(); break } // stopListening
                    // File pleine = afflux anormal : on raccroche plutôt que d'empiler.
                    if (_incoming.trySend(client).isFailure) client.close()
                }
            }.onFailure {
                // Écoute morte : server remis à null pour qu'une nouvelle tentative la reprenne.
                if (server === listening) {
                    stopListening()
                    _state.value = State.Failed("Écoute onion interrompue : ${it.message}")
                }
            }
        }
    }

    /** `:reach`, quand l'app ouverte reprend l'écoute. Tor, lui, continue. */
    @Synchronized
    fun stopListening() {
        val listening = server ?: return
        server = null
        // close() ne réveille pas accept() sous Linux ; shutdown() si. Pas de
        // connexion factice par le chemin : l'app l'a déjà repris, elle la
        // recevrait.
        runCatching { Os.shutdown(listening.fileDescriptor, OsConstants.SHUT_RDWR) }
        runCatching { listening.close() }
        runCatching { boundSocket?.close() }
        boundSocket = null
    }

    /**
     * Ouvre un flux vers le .onion d'un contact, via la socket SOCKS de Tor.
     *
     * Lent la première fois (5 à 30 s : Tor construit un circuit de
     * rendez-vous). Le flux rendu doit être gardé ouvert tant que la
     * conversation l'est — en rouvrir un par message coûterait ce délai à
     * chaque fois.
     */
    suspend fun connect(onionAddress: String): LocalSocket = withContext(Dispatchers.IO) {
        check(_state.value is State.Ready) { "Tor n'est pas prêt" }
        require(OnionAddress.isValid(onionAddress)) { "Adresse onion invalide" }

        val socket = LocalSocket(LocalSocket.SOCKET_STREAM)
        try {
            socket.connect(LocalSocketAddress(socksPath, LocalSocketAddress.Namespace.FILESYSTEM))
            socket.soTimeout = CONNECT_TIMEOUT_MS
            val out = socket.outputStream
            val input = DataInputStream(socket.inputStream)

            // SOCKS5 (RFC 1928), sans authentification.
            out.write(byteArrayOf(5, 1, 0)); out.flush()
            if (input.readUnsignedByte() != 5 || input.readUnsignedByte() != 0) throw IOException("SOCKS refusé")

            val host = "${onionAddress.lowercase()}.onion".toByteArray(Charsets.US_ASCII)
            out.write(
                byteArrayOf(5, 1, 0, 3, host.size.toByte()) + host +
                    byteArrayOf((VIRTUAL_PORT shr 8).toByte(), VIRTUAL_PORT.toByte())
            )
            out.flush()

            val version = input.readUnsignedByte()
            val reply = input.readUnsignedByte()
            input.readUnsignedByte() // réservé
            if (version != 5 || reply != 0) throw IOException("Contact injoignable par Tor (SOCKS $reply)")
            val addressLength = when (input.readUnsignedByte()) {
                1 -> 4
                4 -> 16
                3 -> input.readUnsignedByte()
                else -> throw IOException("Réponse SOCKS illisible")
            }
            input.readFully(ByteArray(addressLength + 2))

            socket.soTimeout = 0 // flux établi : la couche au-dessus gère ses délais
            socket
        } catch (e: Throwable) {
            socket.close()
            throw e
        }
    }

    /**
     * Fait rechercher par Tor le descripteur à jour d'une adresse onion.
     * Après une mise à jour, le contact relance un Tor neuf : nouveaux points
     * d'introduction, et le nôtre insistait sur les anciens, morts, pendant
     * des minutes. Sans effet si le démon est celui de `:reach` (pas de port
     * de contrôle ici) : le prochain essai suivra le chemin normal.
     */
    fun refetchOnion(onionAddress: String) {
        runCatching { control?.hsFetch(onionAddress.lowercase().removeSuffix(".onion")) }
    }

    companion object {
        /**
         * Effacement d'urgence : l'état de Tor (gardes, annuaire en cache) et
         * nos sockets. Aucune clé là-dedans — la clé onion n'est jamais
         * écrite — mais la simple présence de ces fichiers dit « cet
         * appareil a utilisé Tor ».
         */
        fun deleteFiles(context: Context) {
            TorService.getTorrc(context).parentFile?.deleteRecursively()
            context.getDir(SOCKET_DIR, Context.MODE_PRIVATE).deleteRecursively()
        }

        private const val SOCKET_DIR = "tor_sockets"
        /** Port virtuel du service onion. Rien n'écoute vraiment dessus : c'est hs.sock. */
        private const val VIRTUAL_PORT = 80
        private const val CONTROL_TIMEOUT_MS = 30_000L
        private const val CONTROL_SETTLE_MS = 2_000L
        private const val RETRY_MIN_MS = 15_000L
        private const val RETRY_MAX_MS = 120_000L
        private const val BOOTSTRAP_TIMEOUT_MS = 180_000L
        // 60 s (1.3.6, 90 avant) : un essai raté bloquait la file jusqu'à 2 min.
        private const val CONNECT_TIMEOUT_MS = 60_000
        private const val SHARED_POLL_MS = 500L
        private const val SHARED_POLL_READY_MS = 3_000L
        private const val WATCH_MS = 15_000L
        private const val WATCH_DOWN_MS = 5_000L
        private const val STUCK_MS = 30_000L
        private const val KICK_GAP_MS = 60_000L
        private val PROGRESS = Regex("PROGRESS=(\\d+)")
    }
}
