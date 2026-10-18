package com.whisper.reach

import android.os.SystemClock
import android.content.Context
import android.net.LocalSocket
import com.whisper.call.CallProtocol
import com.whisper.crypto.CryptoManager
import com.whisper.security.PinCrypto
import com.whisper.transport.MessageFrame
import com.whisper.transport.TorLink
import com.whisper.update.UpdateManifest
import com.whisper.update.UpdateProtocol
import com.whisper.update.UpdateServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.bouncycastle.util.encoders.Hex
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import com.whisper.util.DebugLog

/**
 * Le côté « appelé » de MessageTransport, app fermée — plus le flux d'un
 * contact confié avec une mise à jour (1.3.8).
 *
 * Même poignée de main (défi, HELLO signé), mais :
 *   - seuls les contacts connus et non bloqués passent. Un inconnu qui a
 *     notre lien réessaiera quand l'app sera ouverte : l'ajouter exigerait
 *     d'écrire dans la base, verrouillée ici ;
 *   - jamais d'appel vers un contact (aucune clé privée d'identité ici) ;
 *     seul le diffuseur peut être recomposé, par ReachDownload ;
 *   - un message n'est ni vérifié ni lu : il est scellé tel quel pour la
 *     boîte (InboxSeal), puis accusé. Signature, compteur anti-rejeu et
 *     déchiffrement se font au déverrouillage, par le chemin habituel ;
 *   - un appel ou un signe d'un contact confirmé fait sonner ou vibrer,
 *     anonymement. Le reste (photos, accusés) est ignoré ;
 *   - la version signée que l'app transmet (phase 14) est offerte et servie
 *     aux contacts confirmés : un téléphone qui diffuse reste utile fermé.
 */
class ReachListener(
    private val context: Context,
    private val handoff: ReachHandoff,
    private val scope: CoroutineScope,
    /** Mise à jour qui continue app fermée (1.3.8), si l'app en a confié une. */
    private val download: ReachDownload? = null
) {
    private val crypto = CryptoManager()
    private val inbox = Inbox(context)
    private val json = Json { ignoreUnknownKeys = true }
    private val contacts = handoff.contacts.associateBy { it.keyHex }
    private val myKey = Hex.decode(handoff.myKeyHex)
    private val handshakes = Semaphore(MAX_PENDING_HANDSHAKES)

    /** L'app a vérifié l'APK installé contre ce manifeste avant de le confier. */
    private val updates = UpdateServer(scope).apply {
        UpdateManifest.verify(handoff.updateManifest, handoff.updateSignature)?.let {
            set(it, java.io.File(context.applicationInfo.sourceDir))
            notes = com.whisper.update.UpdateNotes.verify(handoff.notes, handoff.notesSignature, it.versionCode)
        }
    }

    /**
     * Fin des 5 jours de grâce (1.3.8), sur l'horloge du démarrage en cours :
     * passé cet instant, les versions plus anciennes que la nôtre sont refusées.
     */
    private val graceEndsAt: Long = SystemClock.elapsedRealtime().let { now ->
        if (handoff.graceLeftMs > Long.MAX_VALUE - now) Long.MAX_VALUE else now + handoff.graceLeftMs
    }

    private fun minPeer(): Long =
        if (SystemClock.elapsedRealtime() >= graceEndsAt) maxOf(com.whisper.BuildConfig.MIN_PEER_VERSION, com.whisper.BuildConfig.VERSION_CODE.toLong())
        else com.whisper.BuildConfig.MIN_PEER_VERSION

    /** Un flux par contact : le dernier arrivé remplace l'ancien. */
    private val links = ConcurrentHashMap<String, TorLink>()

    /** Appels déjà signalés : l'offre est répétée toutes les 5 s. */
    private val rung = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var closed = false
    private var job: Job? = null

    fun listen(incoming: ReceiveChannel<LocalSocket>) {
        job = scope.launch { accepting(incoming) }
    }

    /**
     * L'app ouverte reprend la main : plus rien n'est rangé, et les flux
     * tombent — les contacts recomposent, et trouvent l'app.
     */
    fun close() {
        closed = true
        job?.cancel()
        links.values.forEach { it.close() }
        links.clear()
    }

    /**
     * Flux d'un contact confié par l'app avec le téléchargement (1.3.8) : déjà
     * authentifié, sa version déjà annoncée. Il sert aussi aux messages.
     */
    fun adopt(link: TorLink, peerVersion: Long) {
        if (closed) return link.close()
        links.put(link.contactPublicKey, link)?.close()
        scope.launch { readLoop(link, peerVersion) }
    }

    private suspend fun accepting(incoming: ReceiveChannel<LocalSocket>) {
        for (socket in incoming) {
            if (!handshakes.tryAcquire()) {
                runCatching { socket.close() }
                continue
            }
            scope.launch {
                try {
                    val link = runCatching { accept(socket) }.getOrNull()
                    if (link == null) {
                        runCatching { socket.close() }
                    } else {
                        links.put(link.contactPublicKey, link)?.close()
                        // Notre version d'abord, comme l'app ouverte : sans elle,
                        // l'expéditeur nous croit trop ancien et garde tout (1.3.3).
                        link.send(versionFrame())
                        // Fermé pendant la poignée de main : ce flux-là aussi.
                        if (closed) link.close() else scope.launch { readLoop(link) }
                        if (contacts[link.contactPublicKey]?.trusted == true) updates.offerFrame()?.let(link::send)
                    }
                } finally {
                    handshakes.release()
                }
            }
        }
    }

    private fun accept(socket: LocalSocket): TorLink {
        socket.soTimeout = HANDSHAKE_TIMEOUT_MS
        val challenge = PinCrypto.randomBytes(TorLink.CHALLENGE_SIZE)
        TorLink.writeFrame(DataOutputStream(socket.outputStream), byteArrayOf(TorLink.VERSION) + challenge)

        val hello = TorLink.readFrame(DataInputStream(socket.inputStream))
        if (hello.size != TorLink.HELLO_SIZE || hello[0] != TorLink.VERSION) throw IOException("HELLO illisible")
        val theirKey = hello.copyOfRange(1, 33)
        val theirSigning = hello.copyOfRange(33, 65)
        val theirOnion = hello.copyOfRange(65, 65 + TorLink.ONION_SIZE)
        // La preuve X25519 (v3) n'est pas vérifiée ici : le coffre est fermé,
        // pas de clé privée. Inutile : seuls les contacts déjà connus passent,
        // et leur clé de signature enregistrée doit avoir signé le HELLO.
        val signature = hello.copyOfRange(TorLink.SIGNATURE_OFFSET, hello.size)
        val theirHex = Hex.toHexString(theirKey)

        val known = contacts[theirHex] ?: throw IOException("Inconnu : attendra l'app ouverte")
        if (!known.signingHex.equals(Hex.toHexString(theirSigning), ignoreCase = true)) {
            throw IOException("Clé de signature différente de celle du contact")
        }
        val signed = TorLink.helloBinding(challenge, theirKey, theirSigning, theirOnion, myKey)
        if (!crypto.verify(signed, signature, known.signingHex)) throw IOException("Signature HELLO invalide")

        socket.soTimeout = 0
        return TorLink(socket, theirHex, theirHex)
    }

    private fun versionFrame(): ByteArray = buildJsonObject {
        put("v", 1)
        put("t", "ver")
        put("code", com.whisper.BuildConfig.VERSION_CODE.toLong())
    }.toString().toByteArray(Charsets.UTF_8)

    private fun readLoop(link: TorLink, knownVersion: Long = 0L) {
        val contact = contacts[link.contactPublicKey] ?: return link.close()
        // Version annoncée par le contact (« ver », sa première trame). En
        // dessous du minimum, comme app ouverte : rien n'est rangé ni ne
        // sonne, seules les mises à jour passent (1.3.5).
        var peerVersion = knownVersion
        while (link.isOpen) {
            val payload = link.receive() ?: break
            val current = peerVersion >= minPeer()
            when {
                payload.isControl() && payload.controlType() == VERSION ->
                    parse(payload)?.str("code")?.toLongOrNull()?.let { peerVersion = it }
                CallProtocol.isCallFrame(payload) -> if (current) onCallFrame(contact, payload)
                payload.isControl() -> when (payload.controlType()) {
                    NUDGE -> if (contact.trusted && current) ReachNotifications.alert(context)
                    UpdateProtocol.GET -> if (contact.trusted) {
                        parse(payload)?.let(UpdateProtocol::requestOf)?.let { (code, offset) -> updates.onRequest(link, code, offset) }
                    }
                    UpdateProtocol.STOP -> updates.stop(link)
                    // Un contact confirmé qui a la version qu'on télécharge : il peut servir.
                    UpdateProtocol.OFFER -> if (contact.trusted && download != null) {
                        parse(payload)?.takeIf { !UpdateProtocol.isAnnounceOnly(it) }
                            ?.let(UpdateProtocol::offerOf)?.let { download.onOffer(link.contactPublicKey, it, link) }
                    }
                    BYE -> link.close()
                }
                // Morceau de la mise à jour qui continue app fermée (1.3.8).
                UpdateProtocol.isData(payload) -> download?.onData(link.contactPublicKey, payload)
                else -> if (current) store(link, payload)
            }
        }
        links.remove(link.contactPublicKey, link)
        updates.stop(link)
        download?.onLinkClosed(link.contactPublicKey)
    }

    /** Scellé tel quel, puis accusé : « rangé sur le téléphone », comme app ouverte. */
    private fun store(link: TorLink, payload: ByteArray) {
        // Diagnostic (phase 16, bip des fichiers) : tailles seulement, ni contenu ni identité.
        val binary = MessageFrame.isBinary(payload)
        val id = messageId(payload) ?: run {
            DebugLog.d(TAG) { "Écarté : ni message ni signature (${payload.size} o, binaire=$binary)" }
            return
        }
        if (!inbox.hasRoom(payload.size)) {
            DebugLog.w(TAG, "Boîte pleine — message non rangé")
            return
        }
        val sealed = InboxSeal.seal(Hex.decode(link.contactPublicKey) + payload, handoff.inboxPublicKey)
        if (!inbox.put(sealed)) {
            DebugLog.w(TAG, "Rangement impossible (${sealed.size} o)")
            return
        }
        DebugLog.d(TAG) { "Rangé (${payload.size} o, binaire=$binary)" }
        link.send(
            buildJsonObject {
                put("v", 1)
                put("t", ACK)
                put("id", id)
            }.toString().toByteArray(Charsets.UTF_8)
        )
        // Morceau de fichier qui n'est pas le dernier : rangé sans bip — le
        // fichier ne s'annonce qu'une fois complet.
        if (com.whisper.files.FilePayload.isPartial(id)) return
        // Un bip toutes les 30 s au plus.
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastAlert >= ALERT_GAP_MS) {
            lastAlert = now
            ReachNotifications.alert(context)
        }
    }

    @Volatile private var lastAlert = Long.MIN_VALUE / 2

    private fun onCallFrame(contact: ReachContact, frame: ByteArray) {
        when (frame[0]) {
            // Seuls les contacts confirmés sonnent, comme app ouverte. La
            // signature de l'offre sera vérifiée par l'app, quand le contact
            // la renverra après le PIN.
            CallProtocol.OFFER -> {
                val callId = CallProtocol.callIdOf(frame) ?: return
                if (contact.trusted && rung.add(Hex.toHexString(callId))) ReachNotifications.call(context)
            }
            CallProtocol.END -> ReachNotifications.cancelCall(context)
        }
    }

    private fun ByteArray.isControl(): Boolean =
        size >= CONTROL_PREFIX.size && copyOfRange(0, CONTROL_PREFIX.size).contentEquals(CONTROL_PREFIX)

    private fun ByteArray.controlType(): String? = parse(this)?.str("t")

    /**
     * Un message porte un identifiant et une signature (vérifiée au
     * déverrouillage) ; sans elle, rien à ranger ni à signaler.
     */
    private fun messageId(payload: ByteArray): String? {
        // Morceau de fichier : trame binaire, l'en-tête JSON seul suffit ici.
        val frame = parse(MessageFrame.split(payload)?.first ?: payload) ?: return null
        if (frame.str("sig").isNullOrEmpty()) return null
        return frame.str("id")?.takeIf { it.isNotEmpty() && it.length <= MAX_ID_LENGTH }
    }

    private fun parse(payload: ByteArray): JsonObject? = runCatching {
        json.parseToJsonElement(payload.toString(Charsets.UTF_8)) as? JsonObject
    }.getOrNull()

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val TAG = "ReachListener"
        const val HANDSHAKE_TIMEOUT_MS = 10_000
        const val MAX_PENDING_HANDSHAKES = 8
        const val MAX_ID_LENGTH = 64
        const val ALERT_GAP_MS = 30_000L
        const val ACK = "ack"
        const val NUDGE = "nudge"
        const val BYE = "bye"
        const val VERSION = "ver"
        val CONTROL_PREFIX = "{\"v\":1,\"t\":\"".toByteArray(Charsets.UTF_8)
    }
}
