package com.whisper.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.whisper.BuildConfig
import com.whisper.R
import com.whisper.tor.TorRuntime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.whisper.security.SessionLifecycle
import com.whisper.services.StorageService
import com.whisper.transport.MessageTransport
import com.whisper.transport.TorLink
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import com.whisper.util.DebugLog

/**
 * Mises à jour sans serveur (phase 14), côté app ouverte.
 *
 * ### Recevoir
 *
 * Un contact plus à jour nous envoie son manifeste signé à l'ouverture du
 * flux. Signature vérifiée, version plus haute que la nôtre : « Mise à jour
 * disponible ». Sur accord, l'APK arrive par morceaux, chiffrés sur notre
 * disque à mesure (UpdateFile, 1.3.6), repris au morceau manquant si le flux
 * tombe, auprès d'un autre contact qui a la même version, ou au prochain
 * déverrouillage. Annuler l'installation ne perd plus rien. Empreinte vérifiée, puis
 * PackageInstaller : Android demande confirmation et revérifie que l'APK
 * est signé par la même clé que Whisper installé.
 *
 * ### Transmettre
 *
 * Le manifeste de la version installée est gardé (StorageService, chiffré).
 * Au déverrouillage, l'APK installé est comparé à son empreinte : s'il
 * correspond, on l'offre à nos contacts confirmés, app ouverte comme fermée
 * (« Rester joignable » confie le manifeste à `:reach`). Le téléphone qui
 * diffuse n'est qu'un Whisper qui a importé le paquet signé (.wup).
 *
 * Anti-retour : seule une version strictement plus haute que l'installée
 * est proposée ; Android refuse de toute façon de revenir en arrière.
 *
 * ### Le diffuseur (phase 17)
 *
 * En plus des contacts, un diffuseur sert tous les Whospair à trois adresses
 * onion fixes (BuildConfig.DIFFUSER_ONIONS) : les deux premières d'abord, la
 * troisième — dormante, réveillée seulement en cas d'urgence — en dernier.
 * On lui demande au plus toutes les 30 min, app ouverte (ou sur « Rechercher
 * une mise à jour ») : « upd-ask » avec notre numéro de version, rien d'autre. Il ne
 * sait pas qui demande. Son offre suit le même chemin que celle d'un contact :
 * même signature vérifiée, même téléchargement repris, même installation.
 */
@Singleton
class UpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val storage: StorageService,
    private val transport: MessageTransport,
    private val tor: TorRuntime,
    private val clock: GraceClock
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    sealed class State {
        data object Idle : State()
        class Available(val manifest: UpdateManifest) : State()
        class Downloading(val manifest: UpdateManifest, val received: Long) : State() {
            val percent: Int get() = (received * 100 / manifest.size).toInt()
        }
        class Installing(val manifest: UpdateManifest) : State()
        /** [message] : ressource texte ; [manifest] : ce qu'on peut retenter. */
        class Failed(val message: Int, val manifest: UpdateManifest?) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** La version que ce téléphone transmet (installée, empreinte vérifiée). */
    private val _relaying = MutableStateFlow<UpdateManifest?>(null)
    val relaying: StateFlow<UpdateManifest?> = _relaying.asStateFlow()

    val server = UpdateServer(scope)

    val installedCode: Long by lazy {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode }.getOrDefault(0L)
    }

    /** Contacts qui offrent la meilleure version vue. */
    private val offerers = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var best: UpdateManifest? = null

    private val _notes = MutableStateFlow<UpdateNotes?>(null)
    /** Notes signées de la version proposée (1.3.6) : à lire avant d'accepter. */
    val notes: StateFlow<UpdateNotes?> = _notes.asStateFlow()

    /** Retient les notes de la version proposée ; gardées pour une reprise au déverrouillage. */
    private fun keepNotes(notes: UpdateNotes?) {
        _notes.value = notes
        if (notes == null) return
        storage.saveString(KEY_NOTES_OFFER, notes.rawHex)
        storage.saveString(KEY_NOTES_OFFER_SIG, notes.signatureHex)
    }

    private fun storedNotes(key: String, sigKey: String, code: Long): UpdateNotes? =
        UpdateNotes.verifyHex(storage.getString(key).ifEmpty { null }, storage.getString(sigKey).ifEmpty { null }, code)

    // Téléchargement : gardé chiffré sur le disque (repris, survit à l'annulation).
    private val file = UpdateFile(context, storage)
    @Volatile private var received = 0L
    @Volatile private var source: String? = null
    @Volatile private var lastProgress = 0L
    private var watchdog: Job? = null
    private val lock = Any()

    @Volatile private var started = false

    /** Au déverrouillage, après MessageTransport.start. */
    fun start() {
        if (started) return
        started = true
        ContextCompat.registerReceiver(context, installResult, IntentFilter(ACTION_INSTALL), ContextCompat.RECEIVER_NOT_EXPORTED)
        scope.launch {
            loadRelay()
            loaded.complete(Unit)
            // App ouverte : la règle des 5 jours suivie, et le diffuseur
            // interrogé toutes les 30 min au plus (au déverrouillage compris).
            while (true) {
                refreshPolicy()
                askDiffuser(force = false)
                delay(POLICY_EVERY_MS)
            }
        }
    }

    /* ====================================================================== *
     *  5 jours de grâce (1.3.8)
     * ====================================================================== */

    private val _mandatory = MutableStateFlow(false)
    /** Une version plus récente est connue depuis plus de 120 h : plus rien d'autre ne s'ouvre. */
    val mandatory: StateFlow<Boolean> = _mandatory.asStateFlow()

    /** Version minimale d'un contact, relevée à la nôtre 120 h après notre installation. */
    @Volatile var minPeerVersion: Long = BuildConfig.MIN_PEER_VERSION
        private set

    /** Pour `:reach` : ce qu'il reste avant de refuser les versions plus anciennes que la nôtre. */
    @Volatile var ownGraceLeftMs: Long = BuildConfig.GRACE_MS
        private set

    /**
     * Les deux délais, sur l'horloge interne (GraceClock : la date du
     * téléphone n'y fait rien). L'utilisateur a toujours ses 120 h pleines,
     * comptées depuis que SON téléphone a appris la nouvelle version.
     */
    private fun refreshPolicy() {
        val now = clock.now()
        if (storage.getLong(KEY_INSTALLED_FOR) != installedCode) {
            storage.saveLong(KEY_INSTALLED_FOR, installedCode)
            storage.saveLong(KEY_INSTALLED_AT, now)
        }
        val left = (storage.getLong(KEY_INSTALLED_AT) + BuildConfig.GRACE_MS - now).coerceAtLeast(0L)
        ownGraceLeftMs = left
        minPeerVersion = if (left == 0L) maxOf(BuildConfig.MIN_PEER_VERSION, installedCode) else BuildConfig.MIN_PEER_VERSION
        if (best == null) {
            _mandatory.value = false
            return
        }
        if (storage.getLong(KEY_SEEN_FOR) != installedCode) {
            storage.saveLong(KEY_SEEN_FOR, installedCode)
            storage.saveLong(KEY_SEEN_AT, now)
        }
        _mandatory.value = now - storage.getLong(KEY_SEEN_AT) >= BuildConfig.GRACE_MS
    }

    /* ====================================================================== *
     *  Le diffuseur (phase 17)
     * ====================================================================== */

    /** Le flux vers le diffuseur, s'il est ouvert. */
    @Volatile private var diffuser: TorLink? = null
    private val diffuserLock = Any()
    /** Un seul appel au diffuseur à la fois : relance, recherche et demandes ne se doublent pas. */
    private val dialMutex = Mutex()
    private var retryJob: Job? = null

    private val _retrying = MutableStateFlow(false)
    /** Vrai quand le téléchargement attend un diffuseur injoignable (nouvel essai dans 10 s). */
    val retrying: StateFlow<Boolean> = _retrying.asStateFlow()

    private val _checking = MutableStateFlow(false)
    /** Vrai pendant qu'on interroge le diffuseur. */
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    /** « Rechercher une mise à jour » : tout de suite, sans attendre le jour suivant. */
    fun checkNow() {
        scope.launch { askDiffuser(force = true) }
    }

    private suspend fun askDiffuser(force: Boolean) {
        if (!force && System.currentTimeMillis() - storage.getLong(KEY_DIFFUSER_CHECK) < BuildConfig.DIFFUSER_EVERY_MS) return
        _checking.value = true
        try {
            // Tor d'abord : jusqu'à quelques minutes après le déverrouillage.
            withTimeoutOrNull(TOR_WAIT_MS) { tor.state.first { it is TorRuntime.State.Ready } } ?: return
            // Chaque essai compte, réussi ou non : diffuseur injoignable, on ne
            // recompose pas ses trois adresses toutes les 5 min.
            storage.saveLong(KEY_DIFFUSER_CHECK, System.currentTimeMillis())
            openDiffuser()
            // La réponse arrive sur le flux ; on laisse un moment avant de dire « à jour ».
            delay(ANSWER_WAIT_MS)
        } finally {
            _checking.value = false
        }
    }

    /**
     * Ouvre (ou rend) le flux vers le diffuseur et lui demande sa version.
     * Adresses 1 et 2 dans un ordre au hasard, la 3 (d'urgence) en dernier.
     */
    private suspend fun openDiffuser(): TorLink? {
        synchronized(diffuserLock) { diffuser?.takeIf { it.isOpen }?.let { return it } }
        return dialMutex.withLock {
            synchronized(diffuserLock) { diffuser?.takeIf { it.isOpen } } ?: dialDiffuser()
        }
    }

    private suspend fun dialDiffuser(): TorLink? {
        val addresses = BuildConfig.DIFFUSER_ONIONS.toList()
        val order = addresses.take(2).shuffled() + addresses.drop(2)
        for (address in order) {
            val socket = runCatching { tor.connect(address) }.getOrNull() ?: continue
            val link = TorLink(socket, DIFFUSER_SOURCE, "")
            // Il ne sert qu'aux mises à jour : toujours prêt à être confié à l'arrière-plan.
            link.detachable = true
            if (!link.send(UpdateProtocol.ask(installedCode))) continue
            synchronized(diffuserLock) { diffuser = link }
            scope.launch { readDiffuser(link) }
            DebugLog.d(TAG) { "Diffuseur joint" }
            return link
        }
        DebugLog.d(TAG) { "Aucune adresse du diffuseur ne répond" }
        return null
    }

    private fun readDiffuser(link: TorLink) {
        while (link.isOpen) {
            val payload = link.receive() ?: break
            if (UpdateProtocol.isData(payload)) {
                onData(DIFFUSER_SOURCE, payload)
                continue
            }
            val frame = runCatching {
                json.parseToJsonElement(payload.toString(Charsets.UTF_8)) as? JsonObject
            }.getOrNull() ?: continue
            if ((frame["t"] as? JsonPrimitive)?.contentOrNull == UpdateProtocol.OFFER) onOffer(DIFFUSER_SOURCE, frame)
        }
        // Confié à l'arrière-plan avec le téléchargement : l'app se tue, on n'y touche plus.
        if (link.detached) return
        link.close()
        synchronized(diffuserLock) { if (diffuser === link) diffuser = null }
        // Flux tombé en plein téléchargement (diffuseur redémarré) : on le relance.
        if (_state.value is State.Downloading && source == DIFFUSER_SOURCE) retryDiffuser()
    }

    /**
     * Téléchargement sans aucune source joignable (1.3.8) : un diffuseur qui
     * redémarre reste injoignable quelques minutes, le temps de publier ses
     * nouveaux points d'introduction. Avant, un seul essai, puis une barre
     * figée. Désormais : un essai, 10 s après la fin du précédent, jamais deux
     * à la fois, tant que l'app est ouverte. Tous les deux échecs, Tor
     * redemande le descripteur du diffuseur au lieu de garder l'ancien. Son
     * offre, au retour, relance la demande de morceaux (onOffer).
     */
    private fun retryDiffuser() {
        synchronized(lock) {
            if (retryJob?.isActive == true) return
            retryJob = scope.launch {
                var failures = 0
                val since = SystemClock.elapsedRealtime()
                try {
                    while (true) {
                        val waiting = synchronized(lock) {
                            _state.value is State.Downloading && offerers.none(::isReachable) && !isReachable(DIFFUSER_SOURCE)
                        }
                        if (!waiting) break
                        if (tor.state.value is TorRuntime.State.Ready) {
                            if (openDiffuser() != null) break
                            failures++
                            _retrying.value = true
                            if (failures % 2 == 0) BuildConfig.DIFFUSER_ONIONS.forEach(tor::refetchOnion)
                        }
                        // Ni diffuseur ni contact confirmé qui l'a, depuis 5 min :
                        // « interrompue », et nouvel essai tout seul dans 30 min.
                        if (SystemClock.elapsedRealtime() - since >= RETRY_WINDOW_MS) {
                            synchronized(lock) {
                                val s = _state.value as? State.Downloading ?: return@launch
                                reset()
                                _state.value = State.Failed(R.string.update_interrupted, s.manifest)
                                resumeLater(s.manifest)
                            }
                            return@launch
                        }
                        delay(RETRY_MS)
                    }
                } finally {
                    _retrying.value = false
                }
            }
        }
    }

    private var resumeJob: Job? = null

    /** Téléchargement interrompu : il reprend seul dans 30 min, s'il n'a pas repris avant. */
    private fun resumeLater(manifest: UpdateManifest) {
        resumeJob?.cancel()
        resumeJob = scope.launch {
            delay(RESUME_MS)
            val s = _state.value
            if (s is State.Failed && s.manifest?.sameAs(manifest) == true) download()
        }
    }

    /** Une trame vers une source : un contact (par son flux) ou le diffuseur. */
    private suspend fun sendTo(source: String, frame: ByteArray, dial: Boolean): Boolean {
        if (source != DIFFUSER_SOURCE) return transport.sendUpdateFrame(source, frame, dial)
        val link = synchronized(diffuserLock) { diffuser?.takeIf { it.isOpen } }
            ?: if (dial) openDiffuser() else null
        return link?.send(frame) == true
    }

    private fun closeDiffuser() {
        synchronized(diffuserLock) {
            diffuser?.close()
            diffuser = null
        }
    }

    private fun isReachable(source: String): Boolean =
        if (source == DIFFUSER_SOURCE) diffuser?.isOpen == true else source in transport.reachable.value

    /* ====================================================================== *
     *  Transmettre
     * ====================================================================== */

    /**
     * Le manifeste gardé, s'il décrit bien l'APK installé. Une mise à jour
     * reçue attend en « pending » jusqu'au redémarrage dans la nouvelle version.
     */
    private fun loadRelay() {
        restoreKept()
        val pending = UpdateManifest.verifyHex(storage.getString(KEY_PENDING).ifEmpty { null }, storage.getString(KEY_PENDING_SIG).ifEmpty { null })
        if (pending != null && pending.versionCode == installedCode) {
            storage.saveString(KEY_CURRENT, pending.rawHex)
            storage.saveString(KEY_CURRENT_SIG, pending.signatureHex)
            // Ses notes, reçues avec l'offre, partiront avec nos offres à notre tour.
            storedNotes(KEY_NOTES_OFFER, KEY_NOTES_OFFER_SIG, installedCode)?.let {
                storage.saveString(KEY_NOTES_CURRENT, it.rawHex)
                storage.saveString(KEY_NOTES_CURRENT_SIG, it.signatureHex)
            }
        }
        if (pending != null && pending.versionCode <= installedCode) {
            storage.remove(KEY_PENDING)
            storage.remove(KEY_PENDING_SIG)
        }
        val current = UpdateManifest.verifyHex(storage.getString(KEY_CURRENT).ifEmpty { null }, storage.getString(KEY_CURRENT_SIG).ifEmpty { null })
            ?: return
        if (current.versionCode != installedCode) return
        activateRelay(current)
    }

    /**
     * La mise à jour gardée sur le disque : installée (ou dépassée), elle est
     * effacée ; sinon elle redevient disponible — terminée, elle s'installe
     * sans rien retélécharger ; entamée, elle reprendra où elle en était.
     */
    private fun restoreKept() {
        restoreOffer()
        val kept = file.manifest() ?: return file.delete()
        if (kept.versionCode <= installedCode) return file.delete()
        synchronized(lock) {
            if (best == null || kept.versionCode >= best!!.versionCode) {
                best = kept
                _notes.value = storedNotes(KEY_NOTES_OFFER, KEY_NOTES_OFFER_SIG, kept.versionCode)
                if (_state.value is State.Idle) _state.value = State.Available(kept)
            }
        }
    }

    /**
     * La dernière proposition reçue (1.3.7) : avant, elle ne vivait qu'en
     * mémoire — l'app fermée, la bannière disparaissait, et la recherche
     * automatique n'avait pas lieu avant 24 h. Le manifeste est public et
     * signé : le garder ne révèle rien. Installée ou dépassée, elle s'efface.
     * Au téléchargement, l'offrant est recontacté (diffuseur rouvert au besoin).
     */
    private fun restoreOffer() {
        val offer = UpdateManifest.verifyHex(
            storage.getString(KEY_OFFER).ifEmpty { null },
            storage.getString(KEY_OFFER_SIG).ifEmpty { null }
        ) ?: return
        if (offer.versionCode <= installedCode) {
            storage.remove(KEY_OFFER)
            storage.remove(KEY_OFFER_SIG)
            return
        }
        synchronized(lock) {
            if (best == null || offer.versionCode > best!!.versionCode) {
                best = offer
                _notes.value = storedNotes(KEY_NOTES_OFFER, KEY_NOTES_OFFER_SIG, offer.versionCode)
                if (_state.value is State.Idle) _state.value = State.Available(offer)
            }
        }
    }

    /** Vérifie l'APK installé contre le manifeste, puis l'offre aux flux ouverts. */
    private fun activateRelay(manifest: UpdateManifest): Boolean {
        val apk = File(context.applicationInfo.sourceDir)
        val matches = runCatching { apk.length() == manifest.size && sha256(apk).contentEquals(manifest.sha256) }
            .getOrDefault(false)
        if (!matches) {
            DebugLog.w(TAG, "APK installé différent du manifeste : rien à transmettre")
            return false
        }
        server.set(manifest, apk)
        server.notes = storedNotes(KEY_NOTES_CURRENT, KEY_NOTES_CURRENT_SIG, manifest.versionCode)
        _relaying.value = manifest
        scope.launch { transport.offerUpdateToAll() }
        return true
    }

    /** Pour « Rester joignable » : ce que `:reach` offrira app fermée. */
    fun relayManifest(): UpdateManifest? = server.manifest

    /* ====================================================================== *
     *  Recevoir
     * ====================================================================== */

    fun onOffer(contactKey: String, frame: JsonObject) {
        val manifest = UpdateProtocol.offerOf(frame) ?: return
        if (manifest.versionCode <= installedCode) return
        // Déjà refusée par Android (autre signature) : un contact la repropose à
        // chaque connexion ; elle échouerait encore.
        if (manifest.rawHex == storage.getString(KEY_REFUSED)) return
        val offeredNotes = UpdateProtocol.notesOf(frame, manifest)
        // Annonce seule (contact qui ne nous a pas confirmés) : on apprend la
        // version, mais on ne la lui demandera pas.
        val serves = !UpdateProtocol.isAnnounceOnly(frame)
        synchronized(lock) {
            val known = best
            when {
                known == null || manifest.versionCode > known.versionCode -> {
                    // Une version plus haute arrive pendant un téléchargement : elle
                    // attendra — sauf si personne n'envoie l'ancienne (reprise sans
                    // offrant), ou si c'est notre source qui est passée à la nouvelle.
                    val s = _state.value
                    // L'arrière-plan écrit encore l'ancienne dans le fichier : la nouvelle attendra.
                    if (following) return
                    if (s is State.Installing || (s is State.Downloading && source != null && source != contactKey)) return
                    if (s is State.Downloading) reset()
                    best = manifest
                    keepNotes(offeredNotes)
                    storage.saveString(KEY_OFFER, manifest.rawHex)
                    storage.saveString(KEY_OFFER_SIG, manifest.signatureHex)
                    offerers.clear()
                    if (serves) offerers += contactKey
                    _state.value = State.Available(manifest)
                    scope.launch { refreshPolicy() }
                }
                manifest.sameAs(known) && serves -> {
                    offerers += contactKey
                    // Un offrant d'avant 1.3.6 n'a pas les notes ; un autre les apporte.
                    if (_notes.value == null && offeredNotes != null) keepNotes(offeredNotes)
                    // Flux rouvert pendant le téléchargement : on reprend où on en était.
                    val s = _state.value
                    if (s is State.Downloading && (source == contactKey || source == null || stalled())) request(contactKey)
                }
            }
            Unit
        }
    }

    /** Sur accord de l'utilisateur (l'écran a vérifié l'autorisation d'installer). */
    fun download() {
        synchronized(lock) {
            // L'arrière-plan télécharge encore : un seul écrit dans le fichier.
            if (following) return
            // Reprise demandée (ou lancée par resumeLater lui-même, déjà au bout) : plus d'attente.
            resumeJob?.cancel()
            val manifest = when (val s = _state.value) {
                is State.Available -> s.manifest
                is State.Failed -> s.manifest ?: return
                else -> return
            }
            if (manifest !== best && !manifest.sameAs(best)) return
            val have = runCatching { file.begin(manifest) }.getOrElse {
                _state.value = State.Failed(R.string.update_failed_install, manifest)
                return
            }
            // Déjà toute là (installation annulée, app verrouillée) : rien à retélécharger.
            if (have >= manifest.size) {
                _state.value = State.Installing(manifest)
                scope.launch { finish(manifest) }
                return
            }
            received = have
            lastProgress = SystemClock.elapsedRealtime()
            _state.value = State.Downloading(manifest, have)
            // Fermée en route, l'app reprend d'elle-même au prochain déverrouillage.
            storage.saveBoolean(KEY_RESUME, true)
            // Personne ne l'offre à l'instant (reprise au déverrouillage) : on
            // relance le diffuseur ; son offre relancera la demande (onOffer).
            pickSource()?.let(::request) ?: retryDiffuser()
            watch()
        }
    }

    fun cancel() {
        synchronized(lock) {
            val manifest = (_state.value as? State.Downloading)?.manifest ?: return
            storage.remove(KEY_RESUME)
            if (following) {
                // Le téléchargement est dans l'arrière-plan : on l'y arrête.
                followJob?.cancel()
                following = false
                runCatching { background?.stop() }
                _state.value = State.Available(manifest)
                return
            }
            val from = source
            reset()
            _state.value = State.Available(manifest)
            if (from != null) scope.launch { sendTo(from, UpdateProtocol.stop, dial = false) }
        }
    }

    private fun reset() {
        watchdog?.cancel()
        watchdog = null
        retryJob?.cancel()
        retryJob = null
        source = null
        received = 0
    }

    /** Un offrant joignable à l'instant, sinon le premier (le flux sera composé). */
    private fun pickSource(): String? =
        offerers.firstOrNull(::isReachable) ?: offerers.firstOrNull()

    private fun stalled(after: Long = STALL_MS): Boolean = SystemClock.elapsedRealtime() - lastProgress > after

    private fun request(contactKey: String) {
        if (following) return
        val manifest = (_state.value as? State.Downloading)?.manifest ?: return
        // Ancienne source seulement lente : sans « stop », elle enverrait tout l'APK pour rien.
        source?.takeIf { it != contactKey }?.let { old ->
            scope.launch { sendTo(old, UpdateProtocol.stop, dial = false) }
        }
        source = contactKey
        lastProgress = SystemClock.elapsedRealtime()
        // Diffuseur injoignable : c'est la relance qui le rappelle, toutes les 10 s.
        if (contactKey == DIFFUSER_SOURCE && !isReachable(contactKey)) return retryDiffuser()
        // Son flux pourra être confié à l'arrière-plan si l'app part en route.
        if (contactKey != DIFFUSER_SOURCE) transport.markUpdateSource(contactKey)
        val frame = UpdateProtocol.get(manifest.versionCode, received)
        scope.launch {
            if (!sendTo(contactKey, frame, dial = true)) DebugLog.d(TAG) { "Offrant injoignable pour l'instant" }
        }
    }

    /* ====================================================================== *
     *  Téléchargement poursuivi app fermée (1.3.8, « Rester joignable »)
     * ====================================================================== */

    /** Ce que l'app sait demander au téléchargement de l'arrière-plan (Reachability, par Binder). */
    interface Background {
        /** null si l'arrière-plan est injoignable. */
        fun status(): BackgroundStatus?
        /** Arrête, et oublie : un téléchargement complet déjà pris ne revient pas. */
        fun stop()
    }

    /** [state] 0 = en cours, 1 = complet, autre = rien ou arrêté ; [versionCode] de ce qu'il télécharge. */
    class BackgroundStatus(val state: Int, val received: Long, val versionCode: Long)

    private val loaded = kotlinx.coroutines.CompletableDeferred<Unit>()
    @Volatile private var background: Background? = null
    /** L'arrière-plan télécharge, l'app suit sa progression sans toucher au fichier. */
    @Volatile private var following = false
    private var followJob: Job? = null

    /**
     * Juste avant que l'app se tue (Reachability.handOff) : le téléchargement
     * en cours et son flux, arrêté entre deux trames, pour que l'arrière-plan
     * continue sur le même tunnel. Null s'il n'y a rien à confier : sans flux
     * vivant, l'app reprendra simplement au prochain déverrouillage.
     */
    fun handover(): com.whisper.reach.ReachHandoff.Download? {
        if (following) return null
        val s = _state.value as? State.Downloading ?: return null
        val from = source ?: return null
        val fd = if (from == DIFFUSER_SOURCE) diffuser?.takeIf { it.isOpen }?.detach(DETACH_WAIT_MS)
            else transport.detachLink(from, DETACH_WAIT_MS)
        fd ?: return null
        DebugLog.d(TAG) { "Téléchargement confié à l'arrière-plan" }
        return com.whisper.reach.ReachHandoff.Download(
            s.manifest.raw, s.manifest.signature, file.keyBytes(), from,
            if (from == DIFFUSER_SOURCE) 0L else transport.peerVersion(from), fd
        )
    }

    /**
     * Au déverrouillage, une fois l'arrière-plan rattaché (ou tué). Il
     * télécharge encore : l'app suit. Sinon, un téléchargement laissé en
     * route repart de lui-même, là où il en était.
     */
    fun afterReach(bg: Background?) {
        background = bg
        scope.launch {
            loaded.await()
            val kept = file.manifest()
            val remote = runCatching { bg?.status() }.getOrNull()
            if (kept != null && remote != null && remote.state in 0..1 &&
                remote.versionCode == kept.versionCode && kept.versionCode > installedCode
            ) {
                follow(kept)
                return@launch
            }
            // Ce que l'arrière-plan garde ne correspond à rien d'actuel : oublié.
            if (remote != null && remote.state in 0..1) runCatching { bg?.stop() }
            // Reprise d'elle-même, sauf si Android n'autorise plus l'installation :
            // ce serait tout télécharger pour rien.
            if (storage.getBoolean(KEY_RESUME) && context.packageManager.canRequestPackageInstalls()) download()
        }
    }

    private fun follow(manifest: UpdateManifest) {
        synchronized(lock) {
            // L'app avait commencé de son côté (« Télécharger » touché pendant le
            // rattachement) : elle s'arrête, un seul écrit dans le fichier.
            source?.let { from -> scope.launch { sendTo(from, UpdateProtocol.stop, dial = false) } }
            reset()
            following = true
            if (best == null || !manifest.sameAs(best)) best = manifest
            _state.value = State.Downloading(manifest, file.length())
        }
        followJob = scope.launch {
            while (true) {
                val remote = runCatching { background?.status() }.getOrNull() ?: break
                if (!following) return@launch
                when (remote.state) {
                    0 -> _state.value = State.Downloading(manifest, remote.received)
                    1 -> {
                        following = false
                        // Pris : l'arrière-plan l'oublie, sinon il reviendrait à chaque ouverture.
                        runCatching { background?.stop() }
                        _state.value = State.Installing(manifest)
                        finish(manifest)
                        return@launch
                    }
                    else -> break
                }
                delay(FOLLOW_EVERY_MS)
            }
            // Arrière-plan arrêté ou mort en route : l'app reprend où il en était.
            synchronized(lock) {
                // Annulé pendant qu'on interrogeait l'arrière-plan : on ne relance rien.
                if (!following) return@launch
                following = false
                _state.value = State.Available(manifest)
            }
            download()
        }
    }

    /** Plus rien depuis un moment : on redemande, au même ou à un autre. */
    private fun watch() {
        watchdog?.cancel()
        watchdog = scope.launch {
            while (true) {
                delay(STALL_MS / 2)
                synchronized(lock) {
                    if (_state.value !is State.Downloading) return@launch
                    if (stalled()) {
                        val others = offerers.filter { it != source && isReachable(it) }
                        (others.firstOrNull() ?: pickSource())?.let(::request)
                        // Personne de joignable : le diffuseur, relancé toutes les 10 s.
                        if (offerers.none(::isReachable)) retryDiffuser()
                    }
                }
            }
        }
    }

    fun onData(contactKey: String, payload: ByteArray) {
        val data = UpdateProtocol.dataOf(payload) ?: return
        val complete = synchronized(lock) {
            if (following) return
            val s = _state.value as? State.Downloading ?: return
            val size = s.manifest.size
            if (contactKey != source || data.code != s.manifest.versionCode) return
            // Hors séquence : un morceau d'une demande précédente. La nôtre suit.
            if (data.offset != received || received + data.length > size) return
            if (!file.append(received, data.payload, data.start, data.length)) {
                DebugLog.w(TAG, "Morceau non écrit sur le disque")
                storage.remove(KEY_RESUME)
                source?.let { from -> scope.launch { sendTo(from, UpdateProtocol.stop, dial = false) } }
                reset()
                _state.value = State.Failed(R.string.update_failed_install, s.manifest)
                return
            }
            received += data.length
            lastProgress = SystemClock.elapsedRealtime()
            // Flux remplacé en route (arbitrage) : le nouveau doit pouvoir être confié aussi.
            if (contactKey != DIFFUSER_SOURCE) transport.markUpdateSource(contactKey)
            _state.value = State.Downloading(s.manifest, received)
            if (received < size) return
            watchdog?.cancel()
            s.manifest
        }
        scope.launch { finish(complete) }
    }

    /** Téléchargement complet : empreinte vérifiée sur le disque, puis installation. */
    private fun finish(manifest: UpdateManifest) {
        closeDiffuser()
        val ok = runCatching { file.sha256().contentEquals(manifest.sha256) }.getOrDefault(false)
        if (!ok) {
            DebugLog.w(TAG, "Empreinte de l'APK reçu différente du manifeste — écarté")
            file.delete()
            storage.remove(KEY_RESUME)
            synchronized(lock) {
                reset()
                _state.value = State.Failed(R.string.update_failed_corrupt, manifest)
            }
            return
        }
        installFromFile(manifest)
    }

    /* ====================================================================== *
     *  Importer (téléphone qui diffuse)
     * ====================================================================== */

    /**
     * Paquet .wup choisi dans les fichiers. Plus récent que l'installé : on
     * l'installe (et on le transmettra une fois redémarré). Même version :
     * on la transmet dès maintenant.
     */
    suspend fun import(uri: Uri): Int = withContext(Dispatchers.IO) {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    out.write(chunk, 0, n)
                    if (out.size() > UpdateManifest.MAX_APK_SIZE + 4096 + UpdateNotes.MAX_NOTES + 68) return@use null
                }
                out.toByteArray()
            }
        }.getOrNull() ?: return@withContext R.string.update_import_unreadable
        val parsed = UpdatePackage.parse(bytes) ?: return@withContext R.string.update_import_invalid
        val manifest = parsed.manifest
        when {
            manifest.versionCode > installedCode -> {
                synchronized(lock) {
                    if (_state.value is State.Downloading) {
                        // Comme cancel() : l'ancien offrant cesse d'envoyer.
                        source?.let { from -> scope.launch { sendTo(from, UpdateProtocol.stop, dial = false) } }
                        reset()
                    }
                    best = manifest
                    keepNotes(parsed.notes)
                    // Aucun contact ne l'offre : refusé dans la fenêtre d'Android,
                    // il faudra le réimporter (voir STATUS_FAILURE_ABORTED).
                    offerers.clear()
                }
                install(manifest, parsed.apk)
                R.string.update_import_installing
            }
            manifest.versionCode == installedCode -> {
                storage.saveString(KEY_CURRENT, manifest.rawHex)
                storage.saveString(KEY_CURRENT_SIG, manifest.signatureHex)
                parsed.notes?.let {
                    storage.saveString(KEY_NOTES_CURRENT, it.rawHex)
                    storage.saveString(KEY_NOTES_CURRENT_SIG, it.signatureHex)
                }
                if (activateRelay(manifest)) R.string.update_import_relaying else R.string.update_import_mismatch
            }
            else -> R.string.update_import_older
        }
    }

    /* ====================================================================== *
     *  Installer
     * ====================================================================== */

    /** Paquet importé, en mémoire. */
    private fun install(manifest: UpdateManifest, apk: ByteArray) =
        commit(manifest) { out -> out.write(apk); true }

    /**
     * Mise à jour gardée : déchiffrée en flux droit dans la session du
     * système, sans fichier en clair ni APK entier en mémoire. L'empreinte est
     * revérifiée au passage ; fausse, la session est abandonnée avant commit.
     */
    private fun installFromFile(manifest: UpdateManifest) = commit(manifest) { out ->
        val digest = MessageDigest.getInstance("SHA-256")
        file.copyTo(out, digest)
        digest.digest().contentEquals(manifest.sha256).also { if (!it) file.delete() }
    }

    /**
     * L'APK passe dans la session du système ; le manifeste est gardé pour
     * être transmis à son tour. [write] rend false si le contenu est faux.
     */
    private fun commit(manifest: UpdateManifest, write: (java.io.OutputStream) -> Boolean) {
        synchronized(lock) {
            reset()
            _state.value = State.Installing(manifest)
        }
        storage.remove(KEY_RESUME)
        storage.saveString(KEY_PENDING, manifest.rawHex)
        storage.saveString(KEY_PENDING_SIG, manifest.signatureHex)
        runCatching {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                .apply { setAppPackageName(context.packageName) }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                val good = session.openWrite("whisper.apk", 0, manifest.size).use { out ->
                    write(out).also { session.fsync(out) }
                }
                if (!good) {
                    session.abandon()
                    _state.value = State.Failed(R.string.update_failed_corrupt, manifest)
                    return
                }
                val intent = Intent(ACTION_INSTALL).setPackage(context.packageName)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
            }
        }.onFailure {
            DebugLog.w(TAG, "Session d'installation impossible", it)
            _state.value = State.Failed(R.string.update_failed_install, manifest)
        }
    }

    private val installResult = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val manifest = (_state.value as? State.Installing)?.manifest
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                    // La fenêtre d'Android passe devant : ce n'est pas un départ.
                    SessionLifecycle.beforeSystemDialog(SessionLifecycle.FILE_PICKER_GRACE_MS)
                    c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                // Réussie : Android arrête l'app pour la remplacer, on ne voit rien.
                PackageInstaller.STATUS_SUCCESS -> Unit
                // Annulée : la mise à jour gardée sur le disque se réinstalle sans
                // rien retélécharger. Paquet importé sans offrant : à réimporter.
                PackageInstaller.STATUS_FAILURE_ABORTED ->
                    _state.value = manifest?.takeIf { offerers.isNotEmpty() || file.isComplete(it) }
                        ?.let { State.Available(it) } ?: State.Idle
                // Refus définitif (autre signature) : la garder la ferait reproposer
                // à chaque déverrouillage, pour échouer encore.
                PackageInstaller.STATUS_FAILURE_CONFLICT,
                PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> {
                    file.delete()
                    // État déjà changé : le manifeste gardé au commit fait foi.
                    (manifest ?: UpdateManifest.verifyHex(
                        storage.getString(KEY_PENDING).ifEmpty { null },
                        storage.getString(KEY_PENDING_SIG).ifEmpty { null }
                    ))?.let { storage.saveString(KEY_REFUSED, it.rawHex) }
                    listOf(KEY_OFFER, KEY_OFFER_SIG, KEY_NOTES_OFFER, KEY_NOTES_OFFER_SIG, KEY_SEEN_FOR, KEY_SEEN_AT).forEach(storage::remove)
                    synchronized(lock) { best = null; offerers.clear() }
                    _mandatory.value = false
                    _notes.value = null
                    _state.value = State.Failed(R.string.update_failed_signature, null)
                }
                else -> {
                    DebugLog.w(TAG, "Installation refusée : ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
                    _state.value = State.Failed(R.string.update_failed_install, manifest)
                }
            }
        }
    }

    private fun sha256(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val chunk = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                digest.update(chunk, 0, n)
            }
        }
        return digest.digest()
    }

    /** Pour MessageTransport : servir un contact confirmé. */
    fun onRequest(link: TorLink, frame: JsonObject) {
        val (code, offset) = UpdateProtocol.requestOf(frame) ?: return
        server.onRequest(link, code, offset)
    }

    companion object {
        private const val TAG = "UpdateManager"
        private const val ACTION_INSTALL = "com.whisper.update.INSTALL_RESULT"
        /** Sans morceau depuis ce délai, on redemande. */
        private const val STALL_MS = 45_000L
        /** Règle des 5 jours réévaluée, et diffuseur interrogé si 30 min ont passé. */
        private const val POLICY_EVERY_MS = 5 * 60 * 1000L
        /** Code installé quand on a appris une version plus récente, et l'instant (horloge interne). */
        private const val KEY_SEEN_FOR = "update_seen_for"
        private const val KEY_SEEN_AT = "update_seen_at"
        /** Code installé, et l'instant où on l'a vu pour la première fois (horloge interne). */
        private const val KEY_INSTALLED_FOR = "update_installed_for"
        private const val KEY_INSTALLED_AT = "update_installed_at"
        /** Téléchargement en route quand l'app a fermé : reprise au déverrouillage. */
        private const val KEY_RESUME = "update_resume"
        /** Attente d'une fin de trame pour confier le flux à l'arrière-plan (l'app se tue juste après). */
        private const val DETACH_WAIT_MS = 1_500L
        /** Progression du téléchargement de l'arrière-plan, relue à ce rythme. */
        private const val FOLLOW_EVERY_MS = 1_000L
        /** Essais vers le diffuseur pendant un téléchargement, avant « interrompue ». */
        private const val RETRY_WINDOW_MS = 5 * 60 * 1000L
        /** Téléchargement interrompu : nouvel essai tout seul au bout de ce délai. */
        private const val RESUME_MS = 30 * 60 * 1000L
        /** Délai entre deux essais vers un diffuseur injoignable, après la fin du précédent. */
        private const val RETRY_MS = 10_000L
        private const val KEY_CURRENT = "update_current"
        private const val KEY_CURRENT_SIG = "update_current_sig"
        private const val KEY_PENDING = "update_pending"
        private const val KEY_PENDING_SIG = "update_pending_sig"
        private const val KEY_DIFFUSER_CHECK = "update_diffuser_check"
        /** Manifeste refusé par Android (signature) : plus jamais proposé. */
        private const val KEY_REFUSED = "update_refused"
        private const val KEY_OFFER = "update_offer"
        private const val KEY_OFFER_SIG = "update_offer_sig"
        private const val KEY_NOTES_OFFER = "update_notes_offer"
        private const val KEY_NOTES_OFFER_SIG = "update_notes_offer_sig"
        private const val KEY_NOTES_CURRENT = "update_notes_current"
        private const val KEY_NOTES_CURRENT_SIG = "update_notes_current_sig"
        /** Nom de source du diffuseur : jamais une clé de contact (pas d'hexadécimal). */
        private const val DIFFUSER_SOURCE = "diffuseur"
        private const val TOR_WAIT_MS = 5 * 60 * 1000L
        private const val ANSWER_WAIT_MS = 20_000L
        private val json = Json { ignoreUnknownKeys = true }
    }
}
