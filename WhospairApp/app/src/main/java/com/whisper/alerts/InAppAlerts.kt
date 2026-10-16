package com.whisper.alerts

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Les alertes, sans notification système.
 *
 * Une notification Android laisse des traces hors de l'app : historique des
 * notifications, écran verrouillé, montres connectées. Et l'app meurt en
 * arrière-plan, donc elle n'aurait rien à annoncer une fois fermée. Ici,
 * un bandeau anonyme dans l'app, et une courte vibration : rien n'est écrit nulle part.
 *
 * Deux sortes : un message arrivé dans une AUTRE conversation que celle
 * ouverte, et un signe (le « bip ») d'un contact confirmé.
 */
@Singleton
class InAppAlerts @Inject constructor(@ApplicationContext private val context: Context) {

    enum class Kind { MESSAGE, NUDGE }

    data class Alert(val contactPublicKey: String, val kind: Kind, val at: Long)

    private val _current = MutableStateFlow<Alert?>(null)
    val current: StateFlow<Alert?> = _current.asStateFlow()

    /**
     * Signes reçus (contact → heure), en mémoire seulement : la liste affiche
     * « X vous a fait signe » jusqu'à ce qu'on ouvre la conversation.
     */
    private val _nudges = MutableStateFlow<Map<String, Long>>(emptyMap())
    val nudges: StateFlow<Map<String, Long>> = _nudges.asStateFlow()

    /** Conversation à l'écran : ses messages s'affichent déjà, pas d'alerte. L'ouvrir efface son signe. */
    @Volatile var openConversation: String? = null
        set(value) {
            field = value
            value?.lowercase()?.let { key -> _nudges.update { it - key } }
        }

    private val last = ConcurrentHashMap<String, Long>()

    fun post(contactPublicKey: String, kind: Kind) {
        val key = contactPublicKey.lowercase()
        if (kind == Kind.MESSAGE && key == openConversation) return
        if (kind == Kind.NUDGE && key != openConversation?.lowercase()) {
            _nudges.update { it + (key to System.currentTimeMillis()) }
        }
        // Un vocal arrive en dizaines de morceaux : une seule alerte. Un
        // contact qui fait signe en boucle : une alerte toutes les 10 s.
        val window = if (kind == Kind.NUDGE) NUDGE_INTERVAL_MS else THROTTLE_MS
        val now = SystemClock.elapsedRealtime()
        val previous = last[key + kind]
        if (previous != null && now - previous < window) return
        last[key + kind] = now
        _current.value = Alert(key, kind, now)
        buzz(kind)
    }

    fun dismiss(alert: Alert) {
        _current.compareAndSet(alert, null)
    }

    /** Silencieux si le téléphone l'est. */
    private fun buzz(kind: Kind) {
        val audio = context.getSystemService(AudioManager::class.java)
        if (audio?.ringerMode == AudioManager.RINGER_MODE_SILENT) return
        val pattern = if (kind == Kind.NUDGE) longArrayOf(0, 80, 120, 80) else longArrayOf(0, 40)
        runCatching {
            context.getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(pattern, -1))
        }
    }

    companion object {
        private const val THROTTLE_MS = 3_000L
        /** Un signe reçu au plus toutes les 10 s par contact ; l'envoi attend autant. */
        const val NUDGE_INTERVAL_MS = 10_000L
    }
}
