package com.whisper.update

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Les trames de mise à jour, sur le flux Tor d'un contact (phase 14).
 *
 *   upd       {"v":1,"t":"upd","m":<manifeste hex>,"s":<signature hex>,
 *              "n":<notes base64>,"ns":<signature des notes hex>,"a":1}
 *             « a » (1.3.8, facultatif) : annonce seule, pas de fichier servi ;
 *             envoyée à l'ouverture de chaque flux par qui a une version
 *             signée à transmettre ; « n »/« ns » (1.3.6, facultatifs) : les
 *             notes de la version (UpdateNotes), ignorées avant 1.3.6 ;
 *   upd-get   {"v":1,"t":"upd-get","code":N,"off":N} — « envoie-moi l'APK
 *             de la version N à partir de l'octet off » (reprise comprise) ;
 *   upd-stop  {"v":1,"t":"upd-stop"} — annulé ;
 *   upd-ask   {"v":1,"t":"upd-ask","code":N} — au diffuseur (phase 17) :
 *             « j'ai la version N, as-tu plus récent ? ». Rien d'autre :
 *             ni clé, ni adresse, ni nom — le diffuseur ne sait pas qui demande ;
 *   données   binaire : "WUD1" | code (8) | décalage (8) | octets.
 *
 * Une version ancienne ignore les trames de contrôle inconnues ; « WUD1 »
 * n'est ni un message (« { », « WBM1 ») ni un appel (0xC1–0xC4) : écartée.
 */
object UpdateProtocol {
    const val OFFER = "upd"
    const val GET = "upd-get"
    const val STOP = "upd-stop"
    const val ASK = "upd-ask"

    /** Morceau d'APK : sous le plafond de 64 Kio d'une trame TorLink. */
    const val CHUNK = 60 * 1024

    private val MAGIC = "WUD1".toByteArray(Charsets.US_ASCII)
    private const val HEADER = 4 + 8 + 8

    /**
     * [announceOnly] (1.3.8) : pour un contact non confirmé — il apprend que la
     * version existe (ses 5 jours commencent), mais on ne la lui servira pas ;
     * il la prendra au diffuseur ou chez un contact qui l'a confirmé.
     */
    fun offer(manifest: UpdateManifest, notes: UpdateNotes? = null, announceOnly: Boolean = false): ByteArray = buildJsonObject {
        put("v", 1)
        put("t", OFFER)
        if (announceOnly) put("a", 1)
        put("m", manifest.rawHex)
        put("s", manifest.signatureHex)
        if (notes != null && notes.versionCode == manifest.versionCode) {
            put("n", java.util.Base64.getEncoder().encodeToString(notes.raw))
            put("ns", notes.signatureHex)
        }
    }.bytes()

    fun get(code: Long, offset: Long): ByteArray = buildJsonObject {
        put("v", 1)
        put("t", GET)
        put("code", code)
        put("off", offset)
    }.bytes()

    fun ask(installedCode: Long): ByteArray = buildJsonObject {
        put("v", 1)
        put("t", ASK)
        put("code", installedCode)
    }.bytes()

    /** Version annoncée par un « upd-ask » ; null si mal formé. */
    fun askedCode(frame: JsonObject): Long? = frame.num("code")?.takeIf { it >= 0 }

    val stop: ByteArray = buildJsonObject {
        put("v", 1)
        put("t", STOP)
    }.bytes()

    /** Annonce seule : l'offrant ne servira pas le fichier. */
    fun isAnnounceOnly(frame: JsonObject): Boolean = frame.num("a") == 1L

    fun offerOf(frame: JsonObject): UpdateManifest? =
        UpdateManifest.verifyHex(frame.str("m"), frame.str("s"))

    /** Notes jointes à une offre, vérifiées pour [manifest] ; null si absentes ou fausses. */
    fun notesOf(frame: JsonObject, manifest: UpdateManifest): UpdateNotes? = runCatching {
        val raw = java.util.Base64.getDecoder().decode(frame.str("n") ?: return null)
        UpdateNotes.verify(raw, org.bouncycastle.util.encoders.Hex.decode(frame.str("ns") ?: return null), manifest.versionCode)
    }.getOrNull()

    /** (version, décalage) d'une demande ; null si mal formée. */
    fun requestOf(frame: JsonObject): Pair<Long, Long>? {
        val code = frame.num("code") ?: return null
        val offset = frame.num("off")?.takeIf { it >= 0 } ?: return null
        return code to offset
    }

    fun isData(payload: ByteArray): Boolean =
        payload.size > HEADER && MAGIC.indices.all { payload[it] == MAGIC[it] }

    fun data(code: Long, offset: Long, bytes: ByteArray, length: Int): ByteArray {
        val out = ByteArray(HEADER + length)
        MAGIC.copyInto(out)
        writeLong(out, 4, code)
        writeLong(out, 12, offset)
        bytes.copyInto(out, HEADER, 0, length)
        return out
    }

    class Data(val code: Long, val offset: Long, val payload: ByteArray, val start: Int) {
        val length: Int get() = payload.size - start
    }

    fun dataOf(payload: ByteArray): Data? {
        if (!isData(payload)) return null
        return Data(readLong(payload, 4), readLong(payload, 12), payload, HEADER)
    }

    private fun writeLong(b: ByteArray, at: Int, v: Long) {
        for (i in 0 until 8) b[at + i] = (v ushr (56 - 8 * i)).toByte()
    }

    private fun readLong(b: ByteArray, at: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
        return v
    }

    private fun JsonObject.bytes(): ByteArray = toString().toByteArray(Charsets.UTF_8)
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.num(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
}
