package com.whisper.reach

import com.whisper.util.DebugLog
import com.whisper.update.UpdateManifest
import com.whisper.transport.TorLink
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import com.whisper.tor.OnionAddress
import com.whisper.tor.TorRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.torproject.jni.TorService

/**
 * « Rester joignable » : le processus `:reach`, qui garde notre adresse
 * onion en ligne pendant que l'app est fermée (option, phase 13).
 *
 * ### Un seul Tor, app ouverte comme fermée
 *
 * 1. Au déverrouillage, l'app le démarre au premier plan (Android interdit
 *    de le faire depuis l'arrière-plan) et s'y rattache (ATTACH) : il
 *    démarre Tor s'il ne tourne pas encore, publie l'adresse, et laisse
 *    l'app écouter et composer par ce Tor. Lui ne range rien.
 * 2. Quand l'app passe en arrière-plan, elle lui confie le strict
 *    nécessaire (ReachHandoff), puis se tue comme d'habitude. Il reprend
 *    l'écoute ; Tor ne bouge pas, l'adresse ne change pas de points
 *    d'introduction — les contacts la retrouvent tout de suite.
 *
 * Si l'app a tourné avec son propre Tor (option activée en cours de
 * session, ou arrière-plan perdu), il attend sa mort — un seul Tor à la
 * fois sur le même dossier — avant de démarrer le sien. Tor ne redémarre
 * pas dans un même processus : s'il meurt, le processus part avec.
 *
 * Pas de Hilt ici : rien du graphe de l'app (base, identité, dépôts) n'a à
 * exister dans ce processus.
 */
class ReachService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** L'app a confié l'écoute (HANDOFF) ; faux pendant qu'elle est ouverte. */
    @Volatile private var armed = false
    @Volatile private var mainPid = 0
    @Volatile private var seed: ByteArray? = null
    private var tor: TorRuntime? = null
    private var listener: ReachListener? = null
    /** Mise à jour qui continue app fermée (1.3.8), et que l'app suit à sa réouverture. */
    @Volatile private var download: ReachDownload? = null

    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code !in HANDOFF..REFETCH) return super.onTransact(code, data, reply, flags)
            // Service non exporté ; on vérifie quand même que c'est nous.
            if (Binder.getCallingUid() != Process.myUid()) return false
            reply?.writeNoException()
            when (code) {
                HANDOFF -> arm(ReachHandoff.readFrom(data) ?: return false)
                ATTACH -> {
                    if (data.readInt() != VERSION) return false
                    val pid = data.readInt()
                    val attachSeed = data.createByteArray()?.takeIf { it.size == 32 } ?: return false
                    attach(pid, attachSeed)
                }
                STATUS -> reply?.let { writeState(it, tor?.state?.value ?: TorRuntime.State.Off) }
                DOWNLOAD -> reply?.let { out ->
                    val d = download
                    out.writeInt(d?.state?.ordinal ?: -1)
                    out.writeLong(d?.received ?: 0L)
                    out.writeLong(d?.versionCode ?: 0L)
                }
                DOWNLOAD_STOP -> {
                    download?.stop()
                    download = null
                }
                REFETCH -> data.readString()?.takeIf(OnionAddress::isValid)?.let { tor?.refetchOnion(it) }
            }
            return true
        }
    }

    override fun onCreate() {
        super.onCreate()
        ReachNotifications.createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = ReachNotifications.service(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(ReachNotifications.SERVICE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        } else {
            startForeground(ReachNotifications.SERVICE_ID, notification)
        }
        intent?.getIntExtra(EXTRA_MAIN_PID, 0)?.takeIf { it > 0 }?.let {
            mainPid = it
            watchMain(it)
        }
        // Tué par le système : sans l'app, il n'a plus rien pour renaître.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /** Option coupée ou app qui reprend la main : le processus entier part, Tor avec. */
    override fun onDestroy() {
        seed?.fill(0)
        Process.killProcess(Process.myPid())
    }

    /**
     * L'app est morte sans rien confier (plantage, « Verrouiller » d'une
     * version d'avant) : attendre ne servirait à rien, et la notification
     * resterait affichée pour rien.
     */
    private fun watchMain(pid: Int) {
        scope.launch {
            while (Reachability.isAlive(this@ReachService, pid)) delay(MAIN_POLL_MS)
            delay(HANDOFF_GRACE_MS)
            if (!armed && mainPid == pid) stopSelf()
        }
    }

    /** L'app ouverte reprend l'écoute ; Tor démarre s'il ne tourne pas encore. */
    @Synchronized
    private fun attach(pid: Int, attachSeed: ByteArray) {
        armed = false
        mainPid = pid
        // Le diffuseur continue (l'app suit) ; un contact est rendu à l'app.
        download?.let { if (!it.onAttach() && it.state != ReachDownload.State.COMPLETE) download = null }
        listener?.close()
        listener = null
        val running = tor
        if (running != null) {
            attachSeed.fill(0)
            running.serveIncoming = false
            running.stopListening()
            return
        }
        // L'app n'a pas de Tor à elle ce coup-ci : rien à attendre.
        startTor(attachSeed, serve = false, waitFor = 0)
    }

    @Synchronized
    private fun arm(handoff: ReachHandoff) {
        if (armed) return handoff.onionSeed.fill(0)
        armed = true
        val running = tor
        if (running == null) {
            // L'app avait son propre Tor : il tient le verrou du dossier jusqu'à sa mort.
            startTor(handoff.onionSeed, serve = true, waitFor = handoff.mainPid)
        } else {
            handoff.onionSeed.fill(0)
            running.serveIncoming = true
            running.listen()
        }
        // Nouveau téléchargement confié ; sinon celui que l'app suivait (diffuseur) continue.
        handoff.download?.let { d ->
            download?.stop()
            download = startDownload(d)
        }
        listener = ReachListener(applicationContext, handoff, scope, download).also { it.listen((tor ?: return).incoming) }
        // Flux d'un contact confié avec le téléchargement : il sert aussi aux messages.
        handoff.download?.takeIf { it.source != ReachDownload.DIFFUSER }?.let { d ->
            adoptedLink?.let { listener?.adopt(it, d.peerVersion) }
        }
        adoptedLink = null
    }

    /** Flux de contact repris à la passation, en attente du listener. */
    private var adoptedLink: TorLink? = null

    /** Le téléchargement confié par l'app : même tunnel, sinon on cherche une source. */
    private fun startDownload(d: ReachHandoff.Download): ReachDownload? {
        val manifest = UpdateManifest.verify(d.manifest, d.signature) ?: return null
        if (d.fileKey.size != 32) return null
        val job = ReachDownload(applicationContext, scope, manifest, d.fileKey) { tor }
        val link = d.link?.let { TorLink.adopt(it, if (d.source == ReachDownload.DIFFUSER) ReachDownload.DIFFUSER else d.source, "") }
        if (d.source != ReachDownload.DIFFUSER) adoptedLink = link
        job.start(link, d.source)
        DebugLog.d(TAG) { "Mise à jour confiée, elle continue app fermée" }
        return job
    }

    private fun startTor(onionSeed: ByteArray, serve: Boolean, waitFor: Int) {
        seed = onionSeed
        val runtime = TorRuntime(applicationContext, { seed?.copyOf() }, ReachTorService::class.java)
        runtime.serveIncoming = serve
        tor = runtime
        scope.launch {
            val deadline = System.currentTimeMillis() + MAIN_EXIT_WAIT_MS
            while (waitFor > 0 && Reachability.isAlive(applicationContext, waitFor) && System.currentTimeMillis() < deadline) delay(100)
            runtime.start()
            // Publiée : Tor garde la clé en mémoire, la nôtre ne sert plus.
            runtime.state.first { it is TorRuntime.State.Ready }
            seed?.fill(0)
            seed = null
        }
        scope.launch {
            // Démon mort : l'app, ouverte, relance le sien ; fermée, personne à servir.
            runtime.state.first { it is TorRuntime.State.Failed && it.fatal }
            // Pas stopSelf : l'app ouverte nous tient liés, le service ne partirait pas.
            seed?.fill(0)
            Process.killProcess(Process.myPid())
        }
    }

    companion object {
        const val HANDOFF = IBinder.FIRST_CALL_TRANSACTION
        /** L'app ouverte se rattache : pid, graine onion. */
        const val ATTACH = IBinder.FIRST_CALL_TRANSACTION + 1
        /** État de Tor, pour l'app ouverte. */
        const val STATUS = IBinder.FIRST_CALL_TRANSACTION + 2
        /** Téléchargement confié : état (ordinal, -1 = aucun), octets reçus, version. */
        const val DOWNLOAD = IBinder.FIRST_CALL_TRANSACTION + 3
        /** L'app annule ou reprend la main sur le téléchargement. */
        const val DOWNLOAD_STOP = IBinder.FIRST_CALL_TRANSACTION + 4
        /** L'app, sur notre Tor : redemander le descripteur de cette adresse onion. */
        const val REFETCH = IBinder.FIRST_CALL_TRANSACTION + 5
        private const val TAG = "ReachService"
        const val VERSION = 1
        const val EXTRA_MAIN_PID = "main_pid"
        private const val MAIN_EXIT_WAIT_MS = 5_000L
        private const val MAIN_POLL_MS = 2_000L
        private const val HANDOFF_GRACE_MS = 5_000L

        private fun writeState(parcel: Parcel, state: TorRuntime.State) {
            when (state) {
                TorRuntime.State.Off -> parcel.writeInt(0)
                is TorRuntime.State.Starting -> { parcel.writeInt(1); parcel.writeInt(state.progress); parcel.writeInt(if (state.reconnecting) 1 else 0) }
                is TorRuntime.State.Ready -> { parcel.writeInt(2); parcel.writeString(state.onionAddress) }
                is TorRuntime.State.Failed -> { parcel.writeInt(3); parcel.writeString(state.reason); parcel.writeInt(if (state.fatal) 1 else 0) }
            }
        }

        fun readState(parcel: Parcel): TorRuntime.State? = runCatching {
            when (parcel.readInt()) {
                0 -> TorRuntime.State.Off
                1 -> TorRuntime.State.Starting(parcel.readInt(), parcel.readInt() == 1)
                2 -> TorRuntime.State.Ready(parcel.readString()!!)
                3 -> TorRuntime.State.Failed(parcel.readString().orEmpty(), parcel.readInt() == 1)
                else -> null
            }
        }.getOrNull()
    }
}

/**
 * Le démon Tor, dans le processus `:reach`. Même classe que celle de l'app,
 * déclarée une seconde fois : un composant Android ne vit que dans un seul
 * processus.
 */
class ReachTorService : TorService()
