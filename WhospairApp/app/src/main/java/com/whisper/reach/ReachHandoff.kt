package com.whisper.reach

import android.os.Parcel
import android.os.ParcelFileDescriptor

/**
 * Ce que l'app ouverte confie au processus d'arrière-plan, au moment de
 * se tuer. Le strict nécessaire pour ACCEPTER des connexions et ranger ce
 * qui arrive — rien pour en ouvrir, rien pour lire :
 *
 *   - la graine de l'adresse onion (Tor la publie, puis elle est effacée) ;
 *   - notre clé publique X25519 (la poignée de main la signe côté appelant) ;
 *   - la clé publique de la boîte de réception (voir InboxSeal) ;
 *   - les contacts non bloqués : clés publiques et « confirmé » ;
 *   - le manifeste signé de la version installée, s'il y en a un à
 *     transmettre (phase 14) : public, il ne sert qu'à servir l'APK ;
 *   - ses notes signées (1.3.6), publiques elles aussi ;
 *   - le temps qu'il reste avant de refuser les versions plus anciennes que
 *     la nôtre (5 jours de grâce, 1.3.8) ;
 *   - la mise à jour en cours de téléchargement, s'il y en a une (1.3.8) :
 *     son manifeste signé, la clé de son fichier (l'APK est public, le
 *     chiffrement ne sert qu'à ne rien laisser en clair) et le flux ouvert
 *     vers sa source, pour qu'elle continue app fermée.
 *
 * Aucune clé privée d'identité, aucune clé de base, aucun message.
 *
 * Transmis par un appel Binder direct entre nos deux processus : rien ne
 * passe par un Intent, que le système garderait.
 */
class ReachHandoff(
    val mainPid: Int,
    val onionSeed: ByteArray,
    val myKeyHex: String,
    val inboxPublicKey: ByteArray,
    val contacts: List<ReachContact>,
    val updateManifest: ByteArray = ByteArray(0),
    val updateSignature: ByteArray = ByteArray(0),
    val notes: ByteArray = ByteArray(0),
    val notesSignature: ByteArray = ByteArray(0),
    val graceLeftMs: Long = Long.MAX_VALUE,
    val download: Download? = null
) {
    /** Téléchargement confié : [source] = « diffuseur » ou clé du contact ; [link] = son flux. */
    class Download(
        val manifest: ByteArray,
        val signature: ByteArray,
        val fileKey: ByteArray,
        val source: String,
        val peerVersion: Long,
        val link: ParcelFileDescriptor?
    )

    fun writeTo(parcel: Parcel) {
        parcel.writeInt(VERSION)
        parcel.writeInt(mainPid)
        parcel.writeByteArray(onionSeed)
        parcel.writeString(myKeyHex)
        parcel.writeByteArray(inboxPublicKey)
        parcel.writeInt(contacts.size)
        contacts.forEach {
            parcel.writeString(it.keyHex)
            parcel.writeString(it.signingHex)
            parcel.writeInt(if (it.trusted) 1 else 0)
        }
        parcel.writeByteArray(updateManifest)
        parcel.writeByteArray(updateSignature)
        parcel.writeByteArray(notes)
        parcel.writeByteArray(notesSignature)
        parcel.writeLong(graceLeftMs)
        parcel.writeInt(if (download != null) 1 else 0)
        download?.let {
            parcel.writeByteArray(it.manifest)
            parcel.writeByteArray(it.signature)
            parcel.writeByteArray(it.fileKey)
            parcel.writeString(it.source)
            parcel.writeLong(it.peerVersion)
            parcel.writeInt(if (it.link != null) 1 else 0)
            it.link?.let { fd -> parcel.writeFileDescriptor(fd.fileDescriptor) }
        }
    }

    companion object {
        private const val VERSION = 5
        private const val MAX_CONTACTS = 4096

        fun readFrom(parcel: Parcel): ReachHandoff? = runCatching {
            if (parcel.readInt() != VERSION) return null
            val pid = parcel.readInt()
            val seed = parcel.createByteArray() ?: return null
            val me = parcel.readString() ?: return null
            val inbox = parcel.createByteArray() ?: return null
            val count = parcel.readInt()
            if (count !in 0..MAX_CONTACTS || seed.size != 32 || inbox.size != 32) return null
            val contacts = List(count) {
                ReachContact(
                    parcel.readString()!!.lowercase(),
                    parcel.readString()!!.lowercase(),
                    parcel.readInt() == 1
                )
            }
            val manifest = parcel.createByteArray() ?: ByteArray(0)
            val signature = parcel.createByteArray() ?: ByteArray(0)
            val notes = parcel.createByteArray() ?: ByteArray(0)
            val notesSignature = parcel.createByteArray() ?: ByteArray(0)
            val graceLeft = parcel.readLong()
            val download = if (parcel.readInt() == 1) {
                val m = parcel.createByteArray() ?: ByteArray(0)
                val sig = parcel.createByteArray() ?: ByteArray(0)
                val key = parcel.createByteArray() ?: ByteArray(0)
                val source = parcel.readString().orEmpty()
                val version = parcel.readLong()
                val link = if (parcel.readInt() == 1) parcel.readFileDescriptor() else null
                Download(m, sig, key, source, version, link)
            } else null
            ReachHandoff(pid, seed, me.lowercase(), inbox, contacts, manifest, signature, notes, notesSignature, graceLeft, download)
        }.getOrNull()
    }
}

class ReachContact(val keyHex: String, val signingHex: String, val trusted: Boolean)
