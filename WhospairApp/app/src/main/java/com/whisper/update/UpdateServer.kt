package com.whisper.update

import com.whisper.transport.TorLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.yield
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/**
 * Transmet un APK signé à qui le demande (phase 14) — app ouverte
 * (MessageTransport) comme fermée (ReachListener, processus `:reach`), et
 * l'app diffuseur (à part, non publiée), qui sert tous les Whospair via
 * [maxStreams], [onProgress] et l'APK en mémoire. Aucun secret
 * ici : le manifeste est signé ailleurs, l'APK est lu tel quel.
 *
 * Côté Whospair, seuls les contacts confirmés sont servis : un inconnu qui a
 * notre lien ne peut pas nous faire envoyer des dizaines de Mo. Le diffuseur,
 * lui, sert tout le monde, dans la limite de [maxStreams] envois à la fois.
 *
 * Un envoi par flux ; une nouvelle demande remplace la précédente (reprise
 * au morceau manquant). Les messages passent entre deux morceaux.
 */
class UpdateServer(
    private val scope: CoroutineScope,
    /** Envois simultanés au plus ; au-delà, la demande est ignorée (le client redemande). */
    private val maxStreams: Int = Int.MAX_VALUE,
    /** Suivi d'un envoi : octets envoyés à ce jour, fini ou non. Le diffuseur en fait ses compteurs. */
    private val onProgress: ((link: TorLink, sent: Long, total: Long, done: Boolean) -> Unit)? = null
) {

    /** L'APK à servir, relu à chaque envoi. */
    private interface Reader : Closeable {
        val length: Long
        fun read(offset: Long, buffer: ByteArray): Int
    }

    private class Offer(val manifest: UpdateManifest, val open: () -> Reader)

    @Volatile private var offer: Offer? = null
    private val sending = ConcurrentHashMap<TorLink, Job>()

    val manifest: UpdateManifest? get() = offer?.manifest

    /**
     * [apk] doit être l'APK décrit par [manifest] (vérifié par l'appelant :
     * taille et empreinte). null : plus rien à transmettre.
     */
    fun set(manifest: UpdateManifest?, apk: File?) {
        offer = if (manifest != null && apk != null) Offer(manifest) { fileReader(apk) } else null
    }

    /** Le diffuseur : l'APK déchiffré, en mémoire seulement. */
    fun set(manifest: UpdateManifest, apk: ByteArray) {
        offer = Offer(manifest) { bytesReader(apk) }
    }

    /** Notes de la version servie (1.3.6), jointes à l'offre ; ignorées si d'une autre version. */
    @Volatile var notes: UpdateNotes? = null

    fun offerFrame(announceOnly: Boolean = false): ByteArray? = offer?.manifest?.let { UpdateProtocol.offer(it, notes, announceOnly) }

    fun onRequest(link: TorLink, code: Long, offset: Long) {
        val current = offer ?: return
        if (code != current.manifest.versionCode || offset >= current.manifest.size) return
        if (!sending.containsKey(link) && sending.size >= maxStreams) return
        val job = scope.launch { stream(link, current, offset) }
        sending.put(link, job)?.cancel()
    }

    fun stop(link: TorLink) {
        sending.remove(link)?.cancel()
    }

    val activeStreams: Int get() = sending.size

    private suspend fun stream(link: TorLink, current: Offer, from: Long) {
        val code = current.manifest.versionCode
        val total = current.manifest.size
        var offset = from
        runCatching {
            current.open().use { reader ->
                // L'APK a changé sous nos pieds (mise à jour installée entre-temps).
                if (reader.length != total) return@use
                val buffer = ByteArray(UpdateProtocol.CHUNK)
                while (currentCoroutineContext().isActive && link.isOpen && offset < total) {
                    val n = reader.read(offset, buffer)
                    if (n <= 0) break
                    if (!link.send(UpdateProtocol.data(code, offset, buffer, n))) break
                    offset += n
                    onProgress?.invoke(link, offset, total, false)
                    yield()
                }
            }
        }
        onProgress?.invoke(link, offset, total, true)
        // Seulement le nôtre : une demande plus récente a pu le remplacer.
        currentCoroutineContext()[Job]?.let { sending.remove(link, it) }
    }

    private fun fileReader(apk: File): Reader {
        val file = RandomAccessFile(apk, "r")
        return object : Reader {
            override val length: Long get() = file.length()
            override fun read(offset: Long, buffer: ByteArray): Int {
                file.seek(offset)
                return file.read(buffer)
            }
            override fun close() = file.close()
        }
    }

    private fun bytesReader(apk: ByteArray): Reader = object : Reader {
        override val length: Long get() = apk.size.toLong()
        override fun read(offset: Long, buffer: ByteArray): Int {
            val n = minOf(buffer.size.toLong(), apk.size - offset).toInt()
            if (n <= 0) return -1
            apk.copyInto(buffer, 0, offset.toInt(), offset.toInt() + n)
            return n
        }
        override fun close() = Unit
    }
}
