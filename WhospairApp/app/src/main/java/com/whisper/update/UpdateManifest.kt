package com.whisper.update

import com.whisper.crypto.CryptoManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import org.bouncycastle.util.encoders.Hex

/**
 * Une version de Whisper, signée par le développeur (phase 14).
 *
 * ### D'où vient la confiance
 *
 * Deux clés, gardées sur la machine qui compile, jamais sur un téléphone
 * qui diffuse :
 *   - la clé de signature release de l'APK : Android refuse d'installer par-
 *     dessus Whisper un APK signé par une autre ;
 *   - la clé Ed25519 du développeur, dont la partie publique est ici
 *     ([DEVELOPER_KEY]) : elle signe ce manifeste — version, taille,
 *     empreinte SHA-256 de l'APK. On vérifie AVANT de télécharger, puis
 *     l'APK reçu contre l'empreinte.
 *
 * Un téléphone qui transmet une mise à jour ne détient donc aucun secret :
 * volé ou piraté, il ne peut rien faire accepter. N'importe quel Whisper
 * à jour relaie le même paquet.
 *
 * ### Le manifeste
 *
 * Du JSON, signé octet pour octet tel qu'il circule (jamais réécrit) :
 * {"app":"com.whospair","code":7,"name":"1.1","size":…,"sha256":"…","date":…}
 * Signature : Ed25519 sur "whisper-update-v1|" ‖ manifeste.
 */
class UpdateManifest private constructor(
    /** Les octets signés, tels quels. */
    val raw: ByteArray,
    val signature: ByteArray,
    val versionCode: Long,
    val versionName: String,
    val size: Long,
    val sha256: ByteArray,
    val date: Long
) {
    val rawHex: String get() = Hex.toHexString(raw)
    val signatureHex: String get() = Hex.toHexString(signature)

    fun sameAs(other: UpdateManifest?): Boolean =
        other != null && raw.contentEquals(other.raw)

    companion object {
        /** Clé publique Ed25519 du développeur (sa partie privée n'est pas dans le dépôt). */
        const val DEVELOPER_KEY = "63b2cbe96112213307648d9a8b3882b90ca7a631f7cbc4fab5265401ee8da0cd"
        const val APP_ID = "com.whospair"
        /** Au-delà, ce n'est pas Whisper : on n'alloue pas la mémoire. */
        const val MAX_APK_SIZE = 150L * 1024 * 1024
        private const val MAX_MANIFEST = 1024
        private val CONTEXT = "whisper-update-v1|".toByteArray(Charsets.US_ASCII)

        private val json = Json { ignoreUnknownKeys = true }

        fun signedData(raw: ByteArray): ByteArray = CONTEXT + raw

        /** Manifeste vérifié ; null si la signature ou un champ ne va pas. */
        fun verify(raw: ByteArray, signature: ByteArray, key: String = DEVELOPER_KEY): UpdateManifest? {
            if (raw.isEmpty() || raw.size > MAX_MANIFEST || signature.size != 64) return null
            if (!CryptoManager().verify(signedData(raw), signature, key)) return null
            val o = runCatching { json.parseToJsonElement(raw.toString(Charsets.UTF_8)) as? JsonObject }
                .getOrNull() ?: return null
            if (o.str("app") != APP_ID) return null
            val code = o.num("code")?.takeIf { it > 0 } ?: return null
            val name = o.str("name")?.takeIf { it.length in 1..32 } ?: return null
            val size = o.num("size")?.takeIf { it in 1..MAX_APK_SIZE } ?: return null
            val sha = o.str("sha256")?.takeIf { it.length == 64 }
                ?.let { runCatching { Hex.decode(it) }.getOrNull() } ?: return null
            val date = o.num("date") ?: return null
            return UpdateManifest(raw.copyOf(), signature.copyOf(), code, name, size, sha, date)
        }

        fun verifyHex(rawHex: String?, signatureHex: String?): UpdateManifest? = runCatching {
            verify(Hex.decode(rawHex ?: return null), Hex.decode(signatureHex ?: return null))
        }.getOrNull()

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.num(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
    }
}

/**
 * Le paquet de mise à jour (.wup), tel que le produit tools/make-update.sh :
 * "WUP1" | longueur du manifeste (4 octets) | manifeste | signature (64) | APK
 * "WUP2" | longueur du manifeste | manifeste | signature | longueur des notes
 *        (4) | notes | signature des notes (64) | APK   (1.3.6 : UpdateNotes).
 * Importé une fois sur le téléphone qui diffuse ; ensuite, seul l'APK
 * installé et le manifeste circulent.
 */
object UpdatePackage {
    private val MAGIC = "WUP1".toByteArray(Charsets.US_ASCII)
    private val MAGIC_NOTES = "WUP2".toByteArray(Charsets.US_ASCII)

    class Parsed(val manifest: UpdateManifest, val apk: ByteArray, val notes: UpdateNotes? = null)

    fun parse(bytes: ByteArray): Parsed? {
        if (bytes.size < MAGIC.size + 4 + 64) return null
        val head = bytes.copyOfRange(0, MAGIC.size)
        val withNotes = head.contentEquals(MAGIC_NOTES)
        if (!withNotes && !head.contentEquals(MAGIC)) return null
        val length = readInt(bytes, MAGIC.size)
        val start = MAGIC.size + 4
        if (length <= 0 || start.toLong() + length + 64 > bytes.size) return null
        val raw = bytes.copyOfRange(start, start + length)
        val signature = bytes.copyOfRange(start + length, start + length + 64)
        val manifest = UpdateManifest.verify(raw, signature) ?: return null
        var at = start + length + 64
        var notes: UpdateNotes? = null
        if (withNotes) {
            if (at.toLong() + 4 > bytes.size) return null
            val notesLength = readInt(bytes, at)
            at += 4
            if (notesLength !in 1..UpdateNotes.MAX_NOTES || at.toLong() + notesLength + 64 > bytes.size) return null
            notes = UpdateNotes.verify(
                bytes.copyOfRange(at, at + notesLength),
                bytes.copyOfRange(at + notesLength, at + notesLength + 64),
                manifest.versionCode
            ) ?: return null
            at += notesLength + 64
        }
        val apk = bytes.copyOfRange(at, bytes.size)
        if (apk.size.toLong() != manifest.size) return null
        if (!sha256(apk).contentEquals(manifest.sha256)) return null
        return Parsed(manifest, apk, notes)
    }

    fun sha256(data: ByteArray): ByteArray = java.security.MessageDigest.getInstance("SHA-256").digest(data)

    private fun readInt(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)
}
