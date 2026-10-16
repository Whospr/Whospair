package com.whisper.transport

import android.net.LocalSocket
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Un flux Tor ouvert avec UN contact, authentifié.
 *
 * ### Qui est au bout ?
 *
 * Celui qui appelle sait qui il joint : Tor garantit que le flux aboutit au
 * détenteur de la clé de l'adresse onion, celle du QR. Celui qui reçoit, lui,
 * ne sait rien — un client Tor est anonyme par construction. D'où la poignée
 * de main, un seul aller-retour à l'ouverture :
 *
 *   B → A   DÉFI   [version][32 octets aléatoires]
 *   A → B   HELLO  [version][X25519 d'A][Ed25519 d'A][onion d'A, 56 car.][preuve X25519 d'A][signature Ed25519 d'A]
 *           lien : "whisper-v3|tor-hello|" ‖ défi ‖ X25519 d'A ‖ Ed25519 d'A ‖ onion d'A ‖ X25519 de B
 *           preuve : HMAC du lien sous une clé tirée de X25519(A, B) — A détient bien sa clé X25519
 *           signature : Ed25519 d'A sur le lien
 *
 * HELLO porte toute l'identité d'A (celle de son QR) : si B ne connaît pas
 * encore A — A a scanné B, pas l'inverse —, B peut l'ajouter comme contact
 * non confirmé et lui répondre. Un seul scan suffit.
 *
 * Le défi, frais à chaque connexion, empêche de rejouer un HELLO. La clé de B
 * dans la signature empêche B de le resservir à un tiers. La preuve X25519
 * (v3, 1.3.5) empêche de se présenter avec la clé X25519 d'un autre — elle
 * est publique, dans son QR — pour occuper sa place. Un contact bloqué
 * est raccroché avant d'avoir envoyé le moindre message.
 *
 * ### Pourquoi pas de chiffrement de session en plus
 *
 * Le circuit de rendez-vous Tor est déjà chiffré de bout en bout avec des
 * clés éphémères (confidentialité persistante comprise), et chaque message
 * est en plus chiffré, signé et numéroté par MessageService. Une troisième
 * couche ne protégerait contre rien de plus.
 *
 * ### Trames
 *
 * [longueur sur 4 octets][contenu], plafonnée : sans plafond, un pair qui
 * annonce 4 Go fait tomber l'app par manque de mémoire.
 *
 * ### Passer le flux à l'arrière-plan (1.3.8)
 *
 * Une mise à jour en cours de téléchargement continue dans `:reach` quand
 * l'app part : le flux ouvert lui est confié tel quel (son descripteur, par
 * Binder), sans recomposer ni s'authentifier de nouveau. Il doit l'être
 * ENTRE deux trames : un flux coupé au milieu d'une trame serait illisible.
 * D'où [detachable] : le fil de lecture n'attend plus bloqué, il regarde
 * toutes les 30 ms si des octets sont là, et s'arrête proprement entre deux
 * trames quand [detach] le lui demande.
 */
class TorLink private constructor(
    private val socket: LocalSocket?,
    private val raw: InputStream,
    rawOut: OutputStream,
    private val closer: Closeable,
    /** Clé X25519 (hex) du contact au bout. */
    val contactPublicKey: String,
    /** Clé X25519 de celui qui a ouvert le flux : sert à départager deux ouvertures simultanées. */
    val initiatorPublicKey: String
) {
    constructor(socket: LocalSocket, contactPublicKey: String, initiatorPublicKey: String) :
        this(socket, socket.inputStream, socket.outputStream, socket, contactPublicKey, initiatorPublicKey)

    private val input = DataInputStream(raw)
    private val output = DataOutputStream(rawOut)

    @Volatile var isOpen = true
        private set

    /** Lecture par sondage, pour pouvoir être confié entre deux trames (voir [detach]). */
    @Volatile var detachable = false
    @Volatile private var detaching = false
    /** Vrai une fois confié : le fil de lecture a rendu la main sans fermer le flux. */
    @Volatile var detached = false
        private set
    private val parked = CountDownLatch(1)

    /** Bloquant. Rend null quand le flux se ferme, ou quand il vient d'être confié ([detached]). */
    fun receive(): ByteArray? {
        try {
            while (true) {
                // Entre deux trames : on peut s'arrêter ici, les octets non lus
                // restent dans le flux et partent avec lui.
                if (detaching) {
                    detached = true
                    parked.countDown()
                    return null
                }
                if (!detachable || ready()) break
                if (!isOpen) return null
            }
            return readFrame(input)
        } catch (e: IOException) {
            close()
            return null
        }
    }

    /**
     * Des octets à lire, ou le flux fermé par l'autre bout (la lecture le dira) :
     * attendu au plus POLL_MS. available() ne suffit pas : il ne voit pas la fermeture.
     */
    private fun ready(): Boolean {
        val fd = socket?.fileDescriptor ?: return true
        val poll = android.system.StructPollfd().apply {
            this.fd = fd
            events = (android.system.OsConstants.POLLIN or android.system.OsConstants.POLLHUP).toShort()
        }
        return try {
            android.system.Os.poll(arrayOf(poll), POLL_MS.toInt()) > 0
        } catch (e: android.system.ErrnoException) {
            false
        }
    }

    /**
     * Confie le flux à un autre processus : attend que le fil de lecture
     * s'arrête entre deux trames, puis rend une copie du descripteur. Le flux
     * n'est pas fermé (il le sera avec ce processus, la copie le garde
     * ouvert). Null si ce n'est pas possible à temps : l'appelant fait sans.
     */
    fun detach(timeoutMs: Long): ParcelFileDescriptor? {
        val fd = socket?.fileDescriptor ?: return null
        if (!isOpen || !detachable) return null
        detaching = true
        if (!parked.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            detaching = false
            return null
        }
        return runCatching { ParcelFileDescriptor.dup(fd) }.getOrNull()
    }

    /** @return false si le flux est mort : l'appelant rouvre et réessaie une fois. */
    fun send(payload: ByteArray): Boolean = try {
        synchronized(output) { writeFrame(output, payload) }
        true
    } catch (e: IOException) {
        close()
        false
    }

    fun close() {
        isOpen = false
        runCatching { closer.close() }
    }

    companion object {
        private const val POLL_MS = 30

        /** Un flux reçu d'un autre processus (passation de `:reach`, 1.3.8). */
        fun adopt(fd: ParcelFileDescriptor, contactPublicKey: String, initiatorPublicKey: String): TorLink {
            val read = ParcelFileDescriptor.AutoCloseInputStream(fd)
            val write = ParcelFileDescriptor.AutoCloseOutputStream(fd.dup())
            val both = Closeable {
                // Réveille un fil de lecture bloqué : fermer seul ne le ferait pas toujours.
                runCatching { android.system.Os.shutdown(fd.fileDescriptor, android.system.OsConstants.SHUT_RDWR) }
                runCatching { read.close() }
                runCatching { write.close() }
            }
            return TorLink(null, read, write, both, contactPublicKey, initiatorPublicKey)
        }

        /** 3 (1.3.5) : preuve X25519 dans le HELLO. Ne parle pas à la v2. */
        const val VERSION: Byte = 3
        const val CHALLENGE_SIZE = 32
        const val MAX_FRAME = 64 * 1024

        const val ONION_SIZE = 56
        const val PROOF_SIZE = 32
        /** [version][X25519 32][Ed25519 32][onion 56][preuve 32][signature 64] */
        const val HELLO_SIZE = 1 + 32 + 32 + ONION_SIZE + PROOF_SIZE + 64
        const val PROOF_OFFSET = 1 + 32 + 32 + ONION_SIZE
        const val SIGNATURE_OFFSET = PROOF_OFFSET + PROOF_SIZE

        fun helloBinding(
            challenge: ByteArray,
            initiatorKey: ByteArray,
            initiatorSigningKey: ByteArray,
            initiatorOnion: ByteArray,
            responderKey: ByteArray
        ): ByteArray =
            "whisper-v3|tor-hello|".toByteArray(Charsets.US_ASCII) +
                challenge + initiatorKey + initiatorSigningKey + initiatorOnion + responderKey

        fun writeFrame(out: DataOutputStream, payload: ByteArray) {
            require(payload.size in 1..MAX_FRAME) { "Trame hors bornes" }
            out.writeInt(payload.size)
            out.write(payload)
            out.flush()
        }

        fun readFrame(input: DataInputStream): ByteArray {
            val size = input.readInt()
            if (size !in 1..MAX_FRAME) throw IOException("Trame hors bornes : $size")
            return ByteArray(size).also { input.readFully(it) }
        }
    }
}
