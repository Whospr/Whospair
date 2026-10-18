package com.whisper.call

import android.content.Context
import com.whisper.util.DebugLog
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import com.whisper.crypto.CryptoManager
import com.whisper.models.Contact
import com.whisper.models.Message
import com.whisper.models.MessageStatus
import com.whisper.repositories.MessageRepository
import com.whisper.services.MessageService
import com.whisper.services.StorageService
import com.whisper.repositories.ContactRepository
import com.whisper.security.CallKeys
import com.whisper.security.PinCrypto
import com.whisper.services.CallService
import com.whisper.services.IdentityService
import com.whisper.transport.MessageTransport
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.bouncycastle.util.encoders.Hex
import javax.inject.Inject
import javax.inject.Singleton

/** Le contact d'un appel, tel que l'écran le montre. */
data class CallPeer(
    val publicKeyHex: String,
    val label: String,
    val avatarName: String,
    val photo: ByteArray? = null
)

sealed interface CallState {
    data object Idle : CallState
    /** Circuit Tor en cours, puis sonnerie chez le contact. */
    data class Outgoing(val peer: CallPeer, val ringing: Boolean) : CallState
    data class Incoming(val peer: CallPeer) : CallState
    data class Active(val peer: CallPeer, val startedAt: Long, val muted: Boolean, val speaker: Boolean) : CallState
    data class Ended(val peer: CallPeer, val reason: EndReason) : CallState
}

enum class EndReason { HUNG_UP, DECLINED, BUSY, NO_ANSWER, UNREACHABLE, LOST }

/**
 * Un appel vocal en direct, sur le flux Tor du contact. Un seul à la fois.
 *
 * Aucun journal d'appels à part : seule une ligne « Appel » dans la
 * conversation, chiffrée et expirante (CallEvent).
 * L'appel existe tant que les deux apps sont ouvertes — mode strictement
 * vivant : quitter l'app tue le processus, donc raccroche.
 *
 * Seul un contact CONFIRMÉ peut faire sonner ce téléphone. Un inconnu qui a
 * notre lien peut écrire (il apparaît « non confirmé ») ; sonner, non.
 *
 * Tout passe par un seul fil ([serial]) : les évènements d'un appel ne se
 * croisent jamais. Après chaque attente (circuit Tor), on revérifie que
 * l'appel est toujours le même.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class CallManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val transport: MessageTransport,
    private val contactRepository: ContactRepository,
    private val identityService: IdentityService,
    private val cryptoManager: CryptoManager,
    private val callService: CallService,
    private val messageRepository: MessageRepository,
    private val storageService: StorageService
) {
    private val serial = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + serial)

    private val _state = MutableStateFlow<CallState>(CallState.Idle)
    val state: StateFlow<CallState> = _state.asStateFlow()

    private class Session(
        val peer: CallPeer,
        val callId: ByteArray,
        val initiator: Boolean
    ) {
        var ephemeral: CallKeys.Ephemeral? = null
        var peerEphemeral: ByteArray? = null
        var send: CallProtocol.Direction? = null
        var receive: CallProtocol.Direction? = null
        var audio: CallAudio? = null
        var seq = 0L
        /** Le flux Tor porte déjà l'appel : sa chute raccroche. */
        var linked = false
        /** Décroché à cet instant ; 0 tant que personne n'a répondu. */
        var activeAt = 0L
        /** Dernière trame de voix reçue : le silence voyage aussi, son absence dit la coupure. */
        @Volatile var lastHeardAt = 0L
        var timeout: Job? = null
        var sender: Job? = null
        val outbox = Channel<ByteArray>(OUTBOX, BufferOverflow.DROP_OLDEST)
    }

    private var session: Session? = null
    private var started = false

    private var ringtone: Ringtone? = null
    private var ringback: ToneGenerator? = null
    private var proximity: PowerManager.WakeLock? = null

    /** Au déverrouillage : écoute les trames et la présence. */
    fun start() = scope.launch {
        if (started) return@launch
        started = true
        launch { transport.callFrames.collect { (from, frame) -> onFrame(from, frame) } }
        launch {
            transport.reachable.collect { open ->
                val s = session ?: return@collect
                // Un appel sortant qui sonne survit à la chute du flux : chez
                // un contact « joignable » app fermée (phase 13), le flux
                // tombe quand il ouvre l'app pour taper son PIN. L'offre
                // repart sur le nouveau flux ; la sonnerie a son propre délai.
                val ringingOut = s.initiator && s.send == null
                if (s.linked && !ringingOut && s.peer.publicKeyHex !in open) finish(s, EndReason.LOST, notify = null)
            }
        }
    }

    /* ====================================================================== *
     *  Gestes de l'utilisateur
     * ====================================================================== */

    fun call(contact: Contact) = scope.launch {
        if (session != null) return@launch
        val s = Session(peerOf(contact), PinCrypto.randomBytes(CallProtocol.CALL_ID_SIZE), initiator = true)
        session = s
        _state.value = CallState.Outgoing(s.peer, ringing = false)
        callService.start()

        val ephemeral = CallKeys.newEphemeral().also { s.ephemeral = it }
        val offer = signedSignal(CallProtocol.OFFER, s, ephemeral) ?: return@launch finish(s, EndReason.LOST, null)
        // Jusqu'à 60 s : le circuit de rendez-vous, si aucun flux n'est ouvert.
        // Un premier circuit raté est courant (adresse à rechercher, réseau
        // qui revient) : on insiste le temps d'une sonnerie avant de dire
        // « injoignable ».
        val dialDeadline = System.currentTimeMillis() + RING_TIMEOUT_MS
        var sent = transport.sendCallFrame(s.peer.publicKeyHex, offer, dial = true)
        while (!sent && session === s && System.currentTimeMillis() < dialDeadline) {
            delay(CALL_REDIAL_MS)
            if (session === s) sent = transport.sendCallFrame(s.peer.publicKeyHex, offer, dial = true)
        }
        if (session !== s) {
            // Annulé pendant la connexion : l'offre est partie quand même, le
            // flux existe maintenant — on raccroche pour de bon chez le contact.
            if (sent) transport.sendCallFrame(s.peer.publicKeyHex, CallProtocol.end(s.callId, CallProtocol.END_HANGUP), dial = false)
            return@launch
        }
        if (!sent) return@launch finish(s, EndReason.UNREACHABLE, null)
        s.linked = true
        _state.value = CallState.Outgoing(s.peer, ringing = true)
        startRingback()
        s.timeout = launch {
            // L'offre est renvoyée toutes les 5 s : si deux flux se sont
            // croisés, celui qui la portait a pu être fermé par l'arbitrage
            // sans être lu. Même numéro d'appel : le contact ignore les doublons.
            val deadline = System.currentTimeMillis() + RING_TIMEOUT_MS
            var redial: Job? = null
            while (System.currentTimeMillis() < deadline) {
                delay(OFFER_REPEAT_MS)
                if (session !== s || s.send != null) return@launch
                if (!transport.sendCallFrame(s.peer.publicKeyHex, offer, dial = false) && redial?.isActive != true) {
                    // Flux tombé : on recompose à part, la boucle garde son rythme.
                    redial = launch(Dispatchers.IO) { transport.sendCallFrame(s.peer.publicKeyHex, offer, dial = true) }
                }
            }
            redial?.cancel()
            if (session === s && s.send == null) finish(s, EndReason.NO_ANSWER, CallProtocol.END_NO_ANSWER)
        }
    }

    fun accept() = scope.launch {
        val s = session?.takeIf { !it.initiator && it.send == null } ?: return@launch
        val peerEphemeral = s.peerEphemeral ?: return@launch
        stopRinging()
        val ephemeral = CallKeys.newEphemeral()
        val answer = signedSignal(CallProtocol.ACCEPT, s, ephemeral)
        val keys = answer?.let { runCatching { CallProtocol.directions(ephemeral, peerEphemeral, s.callId, initiator = false) }.getOrNull() }
        if (keys == null) {
            ephemeral.destroy()
            return@launch finish(s, EndReason.LOST, CallProtocol.END_HANGUP)
        }
        s.send = keys.first
        s.receive = keys.second
        callService.start()
        if (!transport.sendCallFrame(s.peer.publicKeyHex, answer, dial = false) || session !== s) {
            return@launch finish(s, EndReason.LOST, null)
        }
        goActive(s)
    }

    /** Raccrocher, refuser ou annuler : le même bouton rouge. */
    fun hangUp() = scope.launch {
        val s = session ?: return@launch
        val incoming = !s.initiator && s.send == null
        finish(
            s,
            if (incoming) EndReason.DECLINED else EndReason.HUNG_UP,
            if (incoming) CallProtocol.END_DECLINE else CallProtocol.END_HANGUP
        )
    }

    fun setMuted(muted: Boolean) = scope.launch {
        val active = _state.value as? CallState.Active ?: return@launch
        session?.audio?.muted = muted
        _state.value = active.copy(muted = muted)
    }

    fun setSpeaker(on: Boolean) = scope.launch {
        val active = _state.value as? CallState.Active ?: return@launch
        callService.setSpeaker(on)
        // Écouteur : l'écran s'éteint contre la joue. Haut-parleur : il reste allumé.
        if (on) releaseProximity() else acquireProximity()
        _state.value = active.copy(speaker = on)
    }

    /* ====================================================================== *
     *  Trames reçues
     * ====================================================================== */

    private suspend fun onFrame(from: String, frame: ByteArray) {
        when (frame[0]) {
            CallProtocol.AUDIO -> {
                val s = session ?: return
                if (s.peer.publicKeyHex != from) return
                s.receive?.open(frame)?.let {
                    s.lastHeardAt = System.currentTimeMillis()
                    s.audio?.receive(it)
                }
            }
            CallProtocol.OFFER -> CallProtocol.parseSignal(frame)?.let { onOffer(from, it) }
            CallProtocol.ACCEPT -> CallProtocol.parseSignal(frame)?.let { answer ->
                val s = session
                if (s != null && s.initiator && s.peer.publicKeyHex == from && s.callId.contentEquals(answer.callId)) {
                    onAccept(from, answer)
                } else {
                    // Réponse à un appel qui n'existe plus ici : sans cette FIN,
                    // le contact resterait seul dans un appel muet.
                    transport.sendCallFrame(from, CallProtocol.end(answer.callId, CallProtocol.END_HANGUP), dial = false)
                }
            }
            CallProtocol.END -> CallProtocol.parseEnd(frame)?.let { (callId, reason) ->
                val s = session ?: return
                if (s.peer.publicKeyHex != from || !s.callId.contentEquals(callId)) return
                finish(s, reasonOf(reason, s), notify = null)
            }
        }
    }

    private suspend fun onOffer(from: String, offer: CallProtocol.Signal) {
        val contact = contactRepository.getContact(from)
        if (contact == null || contact.isBlocked || !contact.isTrusted) {
            transport.sendCallFrame(from, CallProtocol.end(offer.callId, CallProtocol.END_DECLINE), dial = false)
            return
        }
        if (!verifySignal(offer, contact)) {
            DebugLog.w(TAG, "Offre d'appel mal signée — ignorée")
            return
        }

        val current = session
        if (current != null && current.callId.contentEquals(offer.callId)) return
        if (current != null) {
            val glare = current.initiator && current.send == null && current.peer.publicKeyHex == from
            if (!glare) {
                transport.sendCallFrame(from, CallProtocol.end(offer.callId, CallProtocol.END_BUSY), dial = false)
                return
            }
            // Les deux s'appellent en même temps. Règle commune aux deux
            // téléphones : l'appel de la plus petite clé l'emporte, l'autre
            // décroche. Sans elle, chacun raccrocherait l'appel de l'autre.
            val me = identityService.getIdentity()?.publicKeyHex?.lowercase() ?: return
            if (me < from) return
            current.timeout?.cancel()
            current.ephemeral?.destroy()
            session = null
            stopRinging()
            startIncoming(contact, offer)
            accept()
            return
        }
        startIncoming(contact, offer)
    }

    private fun startIncoming(contact: Contact, offer: CallProtocol.Signal) {
        val s = Session(peerOf(contact), offer.callId, initiator = false)
        s.peerEphemeral = offer.ephemeral
        s.linked = true
        session = s
        _state.value = CallState.Incoming(s.peer)
        startRinging()
        s.timeout = scope.launch {
            delay(RING_TIMEOUT_MS)
            if (session === s && s.send == null) finish(s, EndReason.NO_ANSWER, CallProtocol.END_NO_ANSWER)
        }
    }

    private suspend fun onAccept(from: String, answer: CallProtocol.Signal) {
        val s = session ?: return
        if (!s.initiator || s.send != null || s.peer.publicKeyHex != from || !s.callId.contentEquals(answer.callId)) return
        val contact = contactRepository.getContact(from) ?: return
        if (!verifySignal(answer, contact)) {
            DebugLog.w(TAG, "Réponse d'appel mal signée — ignorée")
            return
        }
        val ephemeral = s.ephemeral ?: return
        val keys = runCatching { CallProtocol.directions(ephemeral, answer.ephemeral, s.callId, initiator = true) }
            .getOrElse { return finish(s, EndReason.LOST, CallProtocol.END_HANGUP) }
        s.send = keys.first
        s.receive = keys.second
        goActive(s)
    }

    /* ====================================================================== *
     *  Appel en cours
     * ====================================================================== */

    private fun goActive(s: Session) {
        s.timeout?.cancel()
        stopRinging()
        val send = s.send ?: return
        s.lastHeardAt = System.currentTimeMillis()
        s.audio = CallAudio(onFailure = {
            scope.launch { if (session === s) finish(s, EndReason.LOST, CallProtocol.END_HANGUP) }
        }) { group ->
            // Fil du micro. seal est synchronisé ; la boîte d'envoi jette le
            // plus ancien si Tor n'avale pas : trame en retard = trame perdue.
            val frame = runCatching { send.seal(s.seq++, group) }.getOrNull() ?: return@CallAudio
            s.outbox.trySend(frame)
        }.also { it.start() }
        s.sender = scope.launch(Dispatchers.IO) {
            for (frame in s.outbox) {
                if (!transport.sendCallFrame(s.peer.publicKeyHex, frame, dial = false)) {
                    scope.launch { if (session === s) finish(s, EndReason.LOST, null) }
                    break
                }
            }
        }
        // Un contact qui perd le réseau ne ferme pas le flux : Tor le garde
        // « ouvert » des minutes. Plus aucune voix = appel perdu.
        s.timeout = scope.launch {
            while (session === s) {
                delay(WATCHDOG_MS)
                if (System.currentTimeMillis() - s.lastHeardAt > SILENCE_LIMIT_MS) {
                    finish(s, EndReason.LOST, CallProtocol.END_HANGUP)
                }
            }
        }
        acquireProximity()
        s.activeAt = System.currentTimeMillis()
        _state.value = CallState.Active(s.peer, s.activeAt, muted = false, speaker = false)
    }

    /**
     * Fin de l'appel, quelle qu'en soit la raison. [notify] : motif à envoyer
     * au contact, null s'il le sait déjà (c'est lui qui a raccroché, ou le
     * flux est mort).
     */
    private fun finish(s: Session, reason: EndReason, notify: Byte?) {
        if (session !== s) return
        session = null
        s.timeout?.cancel()
        stopRinging()
        releaseProximity()
        s.outbox.close()
        s.sender?.cancel()
        callService.stop()
        // État posé AVANT toute attente : pendant l'arrêt du son, un nouvel
        // appel peut commencer, et rien d'ici ne doit plus l'écraser.
        val ended = CallState.Ended(s.peer, reason)
        _state.value = ended
        scope.launch(Dispatchers.IO) {
            s.audio?.stop()
            s.send?.destroy()
            s.receive?.destroy()
            s.ephemeral?.destroy()
        }
        record(s, reason)
        if (notify != null) {
            scope.launch(Dispatchers.IO) {
                transport.sendCallFrame(s.peer.publicKeyHex, CallProtocol.end(s.callId, notify), dial = false)
            }
        }
        scope.launch {
            delay(ENDED_SHOWN_MS)
            if (_state.value === ended) _state.value = CallState.Idle
        }
    }

    /** La ligne « Appel » de la conversation. Locale, chiffrée, expirante. */
    private fun record(s: Session, reason: EndReason) {
        val event = CallEvent(
            outgoing = s.initiator,
            answered = s.activeAt > 0,
            reason = reason,
            seconds = if (s.activeAt > 0) (System.currentTimeMillis() - s.activeAt) / 1000 else 0
        )
        scope.launch(Dispatchers.IO) {
            runCatching {
                val me = identityService.getIdentity() ?: return@runCatching
                val sealed = identityService.sealLocal(event.encode()) ?: return@runCatching
                val ttl = storageService.getInt(StorageService.KEY_DEFAULT_TTL, MessageService.DEFAULT_TTL_MINUTES)
                    .takeIf { it in 1..MessageService.MAX_TTL_MINUTES } ?: MessageService.DEFAULT_TTL_MINUTES
                val now = System.currentTimeMillis()
                messageRepository.addMessage(
                    Message(
                        id = java.util.UUID.randomUUID().toString(),
                        // Rien à transmettre : seule notre copie compte.
                        content = byteArrayOf(0),
                        fromPublicKeyHex = me.publicKeyHex,
                        toPublicKeyHex = s.peer.publicKeyHex,
                        timestamp = now,
                        expiresAt = now + ttl * 60_000L,
                        status = MessageStatus.SENT,
                        selfCopy = sealed
                    )
                )
            }.onFailure { DebugLog.w(TAG, "Trace d'appel non écrite") }
        }
    }

    /* ====================================================================== *
     *  Signatures
     * ====================================================================== */

    private fun signedSignal(type: Byte, s: Session, ephemeral: CallKeys.Ephemeral): ByteArray? {
        val me = identityService.getIdentity() ?: return null
        val binding = CallProtocol.signalBinding(
            type, s.callId, ephemeral.publicKey, Hex.decode(me.publicKeyHex), Hex.decode(s.peer.publicKeyHex)
        )
        return CallProtocol.signal(type, s.callId, ephemeral.publicKey, cryptoManager.sign(binding, me.signingPrivateKeyHex))
    }

    private fun verifySignal(signal: CallProtocol.Signal, contact: Contact): Boolean {
        val me = identityService.getIdentity() ?: return false
        val binding = CallProtocol.signalBinding(
            signal.type, signal.callId, signal.ephemeral, Hex.decode(contact.publicKeyHex), Hex.decode(me.publicKeyHex)
        )
        return cryptoManager.verify(binding, signal.signature, contact.signingPublicKeyHex)
    }

    /* ====================================================================== *
     *  Sonnerie, écran
     * ====================================================================== */

    private fun startRinging() {
        val audio = context.getSystemService(AudioManager::class.java)
        if (audio.ringerMode == AudioManager.RINGER_MODE_SILENT) return
        if (audio.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
            ringtone = runCatching {
                RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))?.apply {
                    audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    isLooping = true
                    play()
                }
            }.getOrNull()
        }
        context.getSystemService(Vibrator::class.java)
            ?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 900), 0))
    }

    private fun startRingback() {
        ringback = runCatching {
            ToneGenerator(AudioManager.STREAM_VOICE_CALL, 60).apply { startTone(ToneGenerator.TONE_SUP_RINGTONE) }
        }.getOrNull()
    }

    private fun stopRinging() {
        ringtone?.stop()
        ringtone = null
        ringback?.release()
        ringback = null
        context.getSystemService(Vibrator::class.java)?.cancel()
    }

    private fun acquireProximity() {
        if (proximity?.isHeld == true) return
        val power = context.getSystemService(PowerManager::class.java)
        if (!power.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) return
        proximity = power.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "whisper:call")
            .apply { acquire(MAX_CALL_MS) }
    }

    private fun releaseProximity() {
        proximity?.takeIf { it.isHeld }?.release()
        proximity = null
    }

    private fun peerOf(contact: Contact) =
        CallPeer(contact.publicKeyHex.lowercase(), contact.displayLabel, contact.displayName, contact.photo)

    private fun reasonOf(code: Byte, s: Session): EndReason = when (code) {
        CallProtocol.END_DECLINE -> EndReason.DECLINED
        CallProtocol.END_BUSY -> EndReason.BUSY
        CallProtocol.END_NO_ANSWER -> EndReason.NO_ANSWER
        else -> if (s.send == null && !s.initiator) EndReason.NO_ANSWER else EndReason.HUNG_UP
    }

    companion object {
        private const val TAG = "CallManager"
        /** Une minute : le temps, chez un contact app fermée, d'ouvrir, taper le PIN et relancer Tor. */
        private const val RING_TIMEOUT_MS = 60_000L
        private const val CALL_REDIAL_MS = 3_000L
        private const val ENDED_SHOWN_MS = 2_500L
        private const val MAX_CALL_MS = 4 * 60 * 60 * 1000L
        /** Six envois de 60 ms en attente, au plus. */
        private const val OUTBOX = 6
        private const val OFFER_REPEAT_MS = 5_000L
        private const val WATCHDOG_MS = 2_000L
        /** Au-delà de 15 s sans voix, même Tor n'explique plus le retard. */
        private const val SILENCE_LIMIT_MS = 15_000L
    }
}
