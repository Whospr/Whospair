package com.whisper.security

import android.os.Handler
import com.whisper.util.DebugLog
import android.os.Looper
import android.os.Process
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.whisper.call.CallManager
import com.whisper.network.NetworkPrivacyMonitor
import com.whisper.reach.Reachability
import com.whisper.repositories.MessageRepository
import com.whisper.tor.TorRuntime
import com.whisper.transport.MessageTransport
import com.whisper.update.UpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Déverrouillé = au premier plan. Rien d'autre.
 *
 * Au déverrouillage, tout démarre : Tor, transport des messages, purge TTL.
 * En arrière-plan, le processus meurt — c'est ça, le verrouillage.
 *
 * Pourquoi tuer plutôt que fermer proprement : la mémoire du processus
 * contient la base ouverte, les ViewModels, et des messages déchiffrés en
 * clair. Fermer la base pendant que des collecteurs tournent encore provoque
 * des plantages ; laisser les ViewModels vivre laisse du clair en RAM. Et en
 * mode strictement vivant, il n'y a rien à préserver. Un processus mort ne fuit rien.
 */
@Singleton
class SessionLifecycle @Inject constructor(
    private val tor: TorRuntime,
    private val networkPrivacyMonitor: NetworkPrivacyMonitor,
    private val messageTransport: MessageTransport,
    private val messageRepository: MessageRepository,
    private val callManager: CallManager,
    private val reachability: Reachability,
    private val updates: UpdateManager
) : DefaultLifecycleObserver {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun attach() {
        current = this
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    /**
     * Verrouiller : confier l'adresse à l'arrière-plan si « Rester
     * joignable » est actif (phase 13), puis tuer le processus.
     */
    fun lock() {
        runCatching { reachability.handOff() }
        Process.killProcess(Process.myPid())
    }

    /** Appelé par l'écran de verrouillage, base ouverte. */
    fun onUnlocked() {
        // Écouteurs AVANT le réseau : les flux ne rejouent rien, un évènement
        // arrivé dans la première seconde tomberait sinon dans le vide.
        messageTransport.start()
        callManager.start()
        updates.start()
        networkPrivacyMonitor.start()
        scope.launch {
            // « Rester joignable » : le Tor de l'arrière-plan sert aussi
            // l'app, l'adresse reste publiée. Sinon il doit être mort avant
            // que notre Tor la reprenne. Ce qu'il a rangé est vidé dans la
            // base, par le chemin habituel de l'arrivée.
            val shared = runCatching { reachability.resume() }
                .onFailure { DebugLog.e(TAG, "Reprise de l'arrière-plan échouée", it) }
                .getOrDefault(false)
            // Tor seul, sans serveur (décision du 21/09). Hors de LockGate
            // désormais : une erreur ici ne doit pas faire planter l'app.
            if (!shared) runCatching { tor.start() }
                .onFailure { DebugLog.e(TAG, "Démarrage de Tor échoué", it) }
        }

        // Purge TTL : au déverrouillage (des messages ont expiré app fermée,
        // sans qu'on puisse les effacer — la base était verrouillée), puis
        // chaque minute. Plus de WorkManager : en arrière-plan, la base est
        // de toute façon illisible sans le PIN.
        // Les envois en attente (app fermée avant leur départ) restent en
        // file : MessageTransport les reprend dès le démarrage.
        scope.launch {
            while (true) {
                runCatching { messageRepository.cleanupExpiredMessages() }
                    .onFailure { DebugLog.e(TAG, "Purge TTL échouée", it) }
                delay(PURGE_INTERVAL_MS)
            }
        }
    }

    /** Retour au premier plan : aucune fenêtre de permission ne peut plus être ouverte. */
    override fun onStart(owner: LifecycleOwner) {
        systemDialogOpen = false
        // Un sursis encore en attente tuerait l'app lors d'une fenêtre suivante.
        main.removeCallbacks(graceExpired)
    }

    private val main = Handler(Looper.getMainLooper())
    private val graceExpired = Runnable {
        systemDialogOpen = false
        lockIfInBackground()
    }

    /** Arrière-plan : on verrouille en tuant le processus. */
    override fun onStop(owner: LifecycleOwner) {
        if (systemDialogOpen) {
            // La fenêtre de permission d'Android passe devant l'app : ce n'est
            // pas un départ. Un court sursis seulement : si l'app n'est pas
            // revenue au premier plan d'ici là, on verrouille — l'utilisateur
            // a pu partir pendant que la fenêtre était ouverte. Au pire, il
            // retrouve l'écran du PIN ; Android garde son choix de permission.
            main.removeCallbacks(graceExpired)
            main.postDelayed(graceExpired, systemDialogGraceMs)
            return
        }
        lock()
    }

    companion object {
        private const val TAG = "SessionLifecycle"
        private const val PURGE_INTERVAL_MS = 60_000L
        private const val SYSTEM_DIALOG_GRACE_MS = 10_000L
        const val PHOTO_PICKER_GRACE_MS = 60_000L
        /**
         * Sélecteur de documents, « Enregistrer », « Ouvrir avec… » : parcourir
         * des dossiers prend du temps. Avec 10 s, l'app se tuait pendant le
         * choix, et il fallait le PIN puis rouvrir la conversation (phase 16).
         */
        const val FILE_PICKER_GRACE_MS = 120_000L

        /** Une demande de permission (micro, caméra) est affichée par Android. */
        @Volatile private var systemDialogOpen = false

        @Volatile private var systemDialogGraceMs = SYSTEM_DIALOG_GRACE_MS

        @Volatile private var current: SessionLifecycle? = null

        /** « Verrouiller maintenant », depuis les Paramètres. */
        fun lockNow() {
            current?.lock() ?: Process.killProcess(Process.myPid())
        }

        /**
         * Juste avant de lancer une demande de permission — ou le sélecteur
         * de photos, plus long à parcourir ([graceMs] plus large).
         */
        fun beforeSystemDialog(graceMs: Long = SYSTEM_DIALOG_GRACE_MS) {
            systemDialogGraceMs = graceMs
            systemDialogOpen = true
        }

        /** Réponse reçue : si l'app n'est plus au premier plan, on verrouille. */
        fun afterSystemDialog() {
            systemDialogOpen = false
            lockIfInBackground()
        }

        private fun lockIfInBackground() {
            val state = ProcessLifecycleOwner.get().lifecycle.currentState
            if (!state.isAtLeast(Lifecycle.State.STARTED)) lockNow()
        }
    }
}
