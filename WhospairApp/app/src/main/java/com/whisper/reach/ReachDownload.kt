package com.whisper.reach

import android.content.Context
import android.os.SystemClock
import com.whisper.BuildConfig
import com.whisper.tor.TorRuntime
import com.whisper.transport.TorLink
import com.whisper.update.UpdateFile
import com.whisper.update.UpdateManifest
import com.whisper.update.UpdateProtocol
import com.whisper.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * La mise à jour qui continue app fermée (1.3.8), dans `:reach`.
 *
 * Au départ, l'app confie le flux en cours (diffuseur ou contact), déjà
 * ouvert et authentifié, avec la clé du fichier et le manifeste signé. Les
 * morceaux continuent d'arriver sur le même tunnel ; chacun dit où il va
 * (décalage), ceux d'une autre place sont ignorés.
 *
 * Tunnel tombé (contact parti sans « Rester joignable ») : le diffuseur est
 * rappelé — il ne demande aucune clé — toutes les 10 s pendant 5 min ; un
 * contact confirmé qui nous joint et offre la même version sert aussi. Ce
 * processus ne peut pas appeler un contact : il n'a pas nos clés.
 *
 * Tunnel ouvert mais muet (source occupée, ou passée à une autre version) :
 * trois relances, puis arrêt — l'app reprendra à sa réouverture.
 *
 * À la réouverture, le diffuseur continue jusqu'au bout et l'app suit la
 * progression ; un contact, lui, est rendu à l'app, qui va se reconnecter à
 * lui de toute façon (et le contact garde le flux le plus récent). Fichier
 * complet : l'app vérifie l'empreinte et lance l'installation.
 */
class ReachDownload(
    context: Context,
    private val scope: CoroutineScope,
    private val manifest: UpdateManifest,
    fileKey: ByteArray,
    private val tor: () -> TorRuntime?
) {
    enum class State { RUNNING, COMPLETE, STOPPED }

    private val file = UpdateFile(context, null, fileKey)
    private val lock = Any()

    @Volatile var state = State.RUNNING
        private set
    /** Source en cours : DIFFUSER, une clé de contact, ou null (on cherche). */
    @Volatile private var source: String? = null
    @Volatile private var link: TorLink? = null
    @Volatile private var lastData = SystemClock.elapsedRealtime()
    /** Relances restées sans réponse sur le flux en cours. */
    @Volatile private var stalls = 0
    private var fallbackJob: Job? = null

    val received: Long get() = file.length()
    val size: Long get() = manifest.size
    val versionCode: Long get() = manifest.versionCode

    /** Le flux confié par l'app. Diffuseur : lu ici ; contact : lu par ReachListener. */
    fun start(handed: TorLink?, from: String) {
        watchStall()
        if (handed == null) return fallback()
        synchronized(lock) {
            source = from
            link = handed
        }
        if (from == DIFFUSER) scope.launch { if (readDiffuser(handed)) fallback() }
    }

    /** Un morceau, d'où qu'il vienne : écrit s'il suit exactement ce qui est là. */
    fun onData(from: String, payload: ByteArray) {
        val data = UpdateProtocol.dataOf(payload) ?: return
        synchronized(lock) {
            if (state != State.RUNNING || from != source || data.code != manifest.versionCode) return
            val have = file.length()
            if (data.offset != have || have + data.length > manifest.size) return
            if (!file.append(have, data.payload, data.start, data.length)) {
                DebugLog.w(TAG, "Morceau non écrit")
                return stop()
            }
            lastData = SystemClock.elapsedRealtime()
            stalls = 0
            if (file.length() >= manifest.size) {
                state = State.COMPLETE
                DebugLog.d(TAG) { "Mise à jour complète, app fermée" }
                if (source == DIFFUSER) link?.close()
            }
        }
    }

    /** Flux d'un contact fermé (ReachListener) : s'il nous servait, on cherche ailleurs. */
    fun onLinkClosed(from: String) {
        if (from == source && state == State.RUNNING) fallback()
    }

    /** Un contact confirmé nous joint et offre la même version : il sert, si personne d'autre. */
    fun onOffer(from: String, offered: UpdateManifest, via: TorLink) {
        if (!offered.sameAs(manifest)) return
        synchronized(lock) {
            if (state != State.RUNNING || link?.isOpen == true) return
            fallbackJob?.cancel()
            source = from
            link = via
            lastData = SystemClock.elapsedRealtime()
            stalls = 0
        }
        via.send(UpdateProtocol.get(manifest.versionCode, file.length()))
    }

    /**
     * L'app rouvre. Diffuseur qui envoie : on continue, elle suit. Contact,
     * ou personne : elle reprend la main (elle a les clés, donc plus de sources).
     * @return vrai si le téléchargement continue ici.
     */
    fun onAttach(): Boolean {
        synchronized(lock) {
            if (state != State.RUNNING) return state == State.COMPLETE
            if (source == DIFFUSER && link?.isOpen == true) return true
            stop()
            return false
        }
    }

    /** Annulé par l'app, ou rendu : plus rien n'est écrit. */
    fun stop() {
        synchronized(lock) {
            if (state == State.RUNNING) state = State.STOPPED
            fallbackJob?.cancel()
            // Un contact enverrait sinon tout l'APK pour rien.
            if (source != DIFFUSER) link?.send(UpdateProtocol.stop)
            if (source == DIFFUSER) link?.close()
            link = null
            source = null
        }
    }

    /** @return vrai si le flux est tombé en route (il faut chercher ailleurs). */
    private fun readDiffuser(diffuser: TorLink): Boolean {
        while (diffuser.isOpen) {
            val payload = diffuser.receive() ?: break
            if (UpdateProtocol.isData(payload)) onData(DIFFUSER, payload)
        }
        diffuser.close()
        return synchronized(lock) {
            (link === diffuser && state == State.RUNNING).also { lost -> if (lost) { link = null; source = null } }
        }
    }

    /**
     * Plus rien depuis 45 s sur un flux encore ouvert : on redemande où on en
     * est. Trois relances sans réponse (source occupée, ou passée à une autre
     * version) : on arrête, l'app reprendra à sa réouverture.
     */
    private fun watchStall() {
        scope.launch {
            while (state == State.RUNNING) {
                delay(STALL_MS / 3)
                val current = link
                if (current != null && current.isOpen && SystemClock.elapsedRealtime() - lastData > STALL_MS) {
                    if (synchronized(lock) { ++stalls > MAX_STALLS }) return@launch stop()
                    lastData = SystemClock.elapsedRealtime()
                    current.send(UpdateProtocol.get(manifest.versionCode, file.length()))
                }
            }
        }
    }

    /** Diffuseur rappelé toutes les 10 s pendant 5 min ; ensuite l'app reprendra à sa réouverture. */
    private fun fallback() {
        synchronized(lock) {
            link = null
            source = null
            if (state != State.RUNNING || fallbackJob?.isActive == true) return
            fallbackJob = scope.launch {
                var since = SystemClock.elapsedRealtime()
                while (state == State.RUNNING && SystemClock.elapsedRealtime() - since < RETRY_WINDOW_MS) {
                    if (link?.isOpen == true) return@launch
                    val runtime = tor()
                    if (runtime != null && runtime.state.value is TorRuntime.State.Ready) {
                        val opened = dialDiffuser(runtime)
                        if (opened != null) {
                            val taken = synchronized(lock) {
                                if (state != State.RUNNING || link?.isOpen == true) false
                                else { source = DIFFUSER; link = opened; lastData = SystemClock.elapsedRealtime(); stalls = 0; true }
                            }
                            if (!taken) { opened.close(); return@launch }
                            opened.send(UpdateProtocol.get(manifest.versionCode, file.length()))
                            // Retombé en route : nouvelle série d'essais, dans ce même travail.
                            if (!readDiffuser(opened)) return@launch
                            since = SystemClock.elapsedRealtime()
                            continue
                        }
                    }
                    delay(RETRY_MS)
                }
                DebugLog.d(TAG) { "Aucune source app fermée : l'app reprendra à sa réouverture" }
                synchronized(lock) { if (state == State.RUNNING && link == null) state = State.STOPPED }
            }
        }
    }

    private suspend fun dialDiffuser(runtime: TorRuntime): TorLink? {
        val addresses = BuildConfig.DIFFUSER_ONIONS.toList()
        for (address in addresses.take(2).shuffled() + addresses.drop(2)) {
            val socket = runCatching { runtime.connect(address) }.getOrNull() ?: continue
            return TorLink(socket, DIFFUSER, "")
        }
        return null
    }

    companion object {
        private const val TAG = "ReachDownload"
        const val DIFFUSER = "diffuseur"
        private const val STALL_MS = 45_000L
        private const val MAX_STALLS = 3
        private const val RETRY_MS = 10_000L
        private const val RETRY_WINDOW_MS = 5 * 60 * 1000L
    }
}
