package com.whisper.reach

import android.app.ActivityManager
import com.whisper.util.DebugLog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import com.whisper.repositories.ContactRepository
import com.whisper.services.IdentityService
import com.whisper.services.StorageService
import com.whisper.tor.TorRuntime
import com.whisper.transport.MessageTransport
import com.whisper.update.UpdateManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.bouncycastle.util.encoders.Hex
import javax.inject.Inject
import javax.inject.Singleton

/**
 * « Rester joignable », côté app ouverte : démarre le processus d'arrière-
 * plan et se sert de son Tor, lui confie l'écoute au moment de partir, la
 * reprend au retour et vide la boîte de ce qu'il a rangé. Voir ReachService.
 */
@Singleton
class Reachability @Inject constructor(
    @ApplicationContext private val context: Context,
    private val storage: StorageService,
    private val identity: IdentityService,
    private val contactRepository: ContactRepository,
    private val transport: MessageTransport,
    private val tor: TorRuntime,
    private val updates: UpdateManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _enabled = MutableStateFlow(storage.getBoolean(KEY_STAY_REACHABLE))
    val enabled = _enabled.asStateFlow()

    /** Contacts non bloqués, tenus à jour : au départ, pas le temps d'interroger la base. */
    @Volatile private var contacts: List<ReachContact> = emptyList()

    private val binder = MutableStateFlow<IBinder?>(null)
    private val service get() = binder.value
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, b: IBinder?) { binder.value = b }
        override fun onServiceDisconnected(name: ComponentName?) { binder.value = null }
    }

    /**
     * Au déverrouillage, AVANT tout Tor. Option active : on se rattache à
     * l'arrière-plan, dont le Tor sert aussi l'app — rendu vrai, l'app ne
     * lance pas le sien. Sinon il doit être mort : même adresse, même
     * dossier. Puis la boîte est vidée.
     */
    suspend fun resume(): Boolean {
        ReachNotifications.cancelAlerts(context)
        // Contacts et arrière-plan d'abord : quitter l'app pendant la vidange
        // ne doit pas laisser l'option active sans personne pour écouter.
        // Première liste lue AVANT de rendre la main : quitter l'app juste
        // après le PIN confiait une liste vide, et l'arrière-plan refusait
        // tout le monde comme « inconnu ».
        val all = contactRepository.observeAllContacts()
        contacts = toReach(all.first())
        scope.launch { all.collect { contacts = toReach(it) } }
        // hs.sock pris AVANT le rattachement : l'arrière-plan n'arrête
        // d'écouter qu'une fois l'app à l'écoute, aucune visite refusée.
        if (_enabled.value) runCatching { tor.listen() }
        val shared = _enabled.value && attach()
        sharedTor = shared
        if (shared) {
            tor.useShared(::torStatus, ::onSharedLost)
        } else {
            killBackground(context)
            // Arrière-plan muet : un neuf attend le départ, il prendra le relais de notre Tor.
            if (_enabled.value) startIdle()
        }
        // Mise à jour poursuivie app fermée (1.3.8) : l'app suit l'arrière-plan
        // s'il télécharge encore, sinon elle reprend elle-même où il en était.
        updates.afterReach(if (shared) backgroundDownload else null)
        drainInbox()
        return shared
    }

    // Mode privé : l'arrière-plan le traite en inconnu, il attend l'app ouverte (et y sera refusé).
    private fun toReach(all: List<com.whisper.models.Contact>) = all.filter { !it.isBlocked && !it.isPrivate }
        .map { ReachContact(it.publicKeyHex.lowercase(), it.signingPublicKeyHex.lowercase(), it.isTrusted) }

    /**
     * Démarre l'arrière-plan s'il le faut, et le rattache : il cesse de
     * ranger, nous écoutons. Un échec fait retomber sur deux Tor (l'adresse
     * change de points d'introduction à chaque départ) : on laisse au
     * processus le temps de naître, et on le dit dans le journal.
     */
    private suspend fun attach(): Boolean {
        startIdle()
        val b = withTimeoutOrNull(ATTACH_WAIT_MS) { binder.filterNotNull().first() } ?: run {
            DebugLog.w(TAG, "Arrière-plan pas prêt en ${ATTACH_WAIT_MS / 1000} s : l'app lance son propre Tor")
            return false
        }
        val seed = identity.onionSeed() ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInt(ReachService.VERSION)
            data.writeInt(Process.myPid())
            data.writeByteArray(seed)
            b.transact(ReachService.ATTACH, data, reply, 0) && runCatching { reply.readException() }.isSuccess
        } catch (e: Exception) {
            DebugLog.w(TAG, "Arrière-plan injoignable : l'app lance son propre Tor")
            false
        } finally {
            seed.fill(0)
            data.recycle()
            reply.recycle()
        }
    }

    /** État du Tor de l'arrière-plan ; null s'il est mort. */
    private fun torStatus(): TorRuntime.State? {
        val b = service ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            if (!b.transact(ReachService.STATUS, data, reply, 0)) return null
            reply.readException()
            ReachService.readState(reply)
        } catch (e: Exception) {
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** L'app se sert du Tor de l'arrière-plan : un flux confié lui survit. */
    @Volatile private var sharedTor = false

    /** Le téléchargement de l'arrière-plan, vu de l'app ouverte. */
    private val backgroundDownload = object : UpdateManager.Background {
        override fun status(): UpdateManager.BackgroundStatus? {
            val b = service ?: return null
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                if (!b.transact(ReachService.DOWNLOAD, data, reply, 0)) return null
                reply.readException()
                UpdateManager.BackgroundStatus(reply.readInt(), reply.readLong(), reply.readLong())
            } catch (e: Exception) {
                null
            } finally {
                data.recycle()
                reply.recycle()
            }
        }

        override fun stop() {
            val b = service ?: return
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                b.transact(ReachService.DOWNLOAD_STOP, data, reply, 0)
            } catch (e: Exception) {
                DebugLog.w(TAG, "Arrêt du téléchargement de l'arrière-plan impossible")
            } finally {
                data.recycle()
                reply.recycle()
            }
        }
    }

    /** Arrière-plan mort ou option coupée, app ouverte : elle reprend son propre Tor. */
    private fun onSharedLost() {
        killBackground(context)
        if (_enabled.value) startIdle()
        tor.start()
    }

    fun setEnabled(on: Boolean) {
        storage.saveBoolean(KEY_STAY_REACHABLE, on)
        _enabled.value = on
        if (on) startIdle() else stopIdle()
    }

    /**
     * Juste avant que l'app se tue. Synchrone : l'arrière-plan a tout reçu
     * quand l'appel rend la main. En cas d'échec, l'app se tue quand même —
     * elle ne sera simplement pas joignable.
     */
    fun handOff() {
        if (!_enabled.value) return
        val b = service ?: return
        val seed = identity.onionSeed() ?: return
        val inbox = identity.inboxPublicKey() ?: return seed.fill(0)
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            val relay = updates.relayManifest()
            val notes = updates.server.notes?.takeIf { relay != null && it.versionCode == relay.versionCode }
            ReachHandoff(
                Process.myPid(), seed, identity.getPublicKeyHex(), inbox, contacts,
                relay?.raw ?: ByteArray(0), relay?.signature ?: ByteArray(0),
                notes?.raw ?: ByteArray(0), notes?.signature ?: ByteArray(0),
                updates.ownGraceLeftMs,
                // La mise à jour en cours et son flux : elle continue app fermée.
                // Avec notre propre Tor, le flux mourrait avec l'app : on n'en confie pas.
                if (sharedTor) runCatching { updates.handover() }.getOrNull() else null
            ).writeTo(data)
            b.transact(ReachService.HANDOFF, data, reply, 0)
        } catch (e: Exception) {
            DebugLog.w(TAG, "Arrière-plan injoignable : l'app ne le sera pas non plus")
        } finally {
            seed.fill(0)
            data.recycle()
            reply.recycle()
        }
    }

    private fun startIdle() {
        val intent = Intent(context, ReachService::class.java).putExtra(ReachService.EXTRA_MAIN_PID, Process.myPid())
        runCatching {
            context.startForegroundService(intent)
            context.bindService(intent, connection, 0)
        }.onFailure { DebugLog.w(TAG, "Arrière-plan non démarré", it) }
    }

    private fun stopIdle() {
        runCatching { context.unbindService(connection) }
        binder.value = null
        context.stopService(Intent(context, ReachService::class.java))
    }

    /**
     * Chaque message passe par le chemin habituel de l'arrivée : signature,
     * compteur anti-rejeu, écriture sans écraser. Le fichier part une fois
     * rangé, ou si son sceau est illisible (il ne le sera jamais davantage).
     * Sur toute autre erreur (base), il reste, avec les suivants — l'ordre
     * compte pour le compteur — jusqu'au prochain déverrouillage : l'accusé
     * est déjà parti, l'expéditeur le croit livré.
     */
    private suspend fun drainInbox() {
        val inbox = Inbox(context)
        inbox.discardPartial()
        for (file in inbox.pending()) {
            val plain = runCatching { identity.openInbox(file.readBytes()) }.getOrNull()
            if (plain == null || plain.size <= KEY_SIZE) {
                DebugLog.w(TAG, "Message rangé illisible — écarté")
                plain?.fill(0)
                file.delete()
                continue
            }
            val stored = runCatching {
                transport.ingest(Hex.toHexString(plain, 0, KEY_SIZE), plain.copyOfRange(KEY_SIZE, plain.size))
            }
            plain.fill(0)
            if (stored.isFailure) {
                DebugLog.e(TAG, "Rangement échoué — gardé pour le prochain déverrouillage")
                return
            }
            file.delete()
        }
    }

    companion object {
        private const val TAG = "Reachability"
        private const val KEY_STAY_REACHABLE = "stay_reachable"
        private const val KEY_SIZE = 32
        private const val PROCESS_SUFFIX = ":reach"
        private const val KILL_WAIT_MS = 3_000L
        /** Processus neuf : Application, Hilt, service — plus de 5 s sur un A24 à froid. */
        private const val ATTACH_WAIT_MS = 15_000L

        fun isReachProcess(): Boolean =
            android.app.Application.getProcessName().endsWith(PROCESS_SUFFIX)

        /**
         * Tue le processus d'arrière-plan et attend sa fin (Tor lâche alors
         * son dossier). Sert au retour dans l'app et à l'effacement d'urgence.
         */
        fun killBackground(context: Context) {
            val name = context.packageName + PROCESS_SUFFIX
            val pids = context.getSystemService(ActivityManager::class.java)
                .runningAppProcesses.orEmpty()
                .filter { it.processName == name }
                .map { it.pid }
            pids.forEach(Process::killProcess)
            val deadline = System.currentTimeMillis() + KILL_WAIT_MS
            while (pids.any { isAlive(context, it) } && System.currentTimeMillis() < deadline) Thread.sleep(50)
        }

        /**
         * Processus de Whisper encore en vie. Pas /proc/<pid> : sur certains
         * téléphones, un processus n'y voit pas les autres, même de
         * la même app — `:reach` croyait l'app morte et s'arrêtait après 5 s.
         */
        fun isAlive(context: Context, pid: Int): Boolean =
            context.getSystemService(ActivityManager::class.java)
                .runningAppProcesses.orEmpty().any { it.pid == pid }
    }
}
