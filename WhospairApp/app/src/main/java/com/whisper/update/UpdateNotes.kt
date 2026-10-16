package com.whisper.update

import com.whisper.crypto.CryptoManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import org.bouncycastle.util.encoders.Hex
import java.util.Locale

/**
 * Les notes d'une version (1.3.6) : ce qu'elle contient, lu avant d'accepter.
 *
 * Signées par la clé du développeur, comme le manifeste, et liées à son
 * numéro de version : un diffuseur volé ou un contact ne peut ni réécrire le
 * texte (« installez vite, c'est urgent »), ni recoller les notes d'une autre
 * version. Ils ne font que les relayer.
 *
 * Du JSON, signé octet pour octet tel qu'il circule :
 * {"app":"com.whospair","code":13,"notes":{"fr":"…","en":"…",…}}
 * Signature : Ed25519 sur "whisper-notes-v1|" ‖ notes.
 *
 * Elles voyagent à part du manifeste (plafonné à 1 Kio dans les apps déjà
 * installées) : champs « n » et « ns » de la trame « upd », ignorés par les
 * versions qui ne les connaissent pas.
 */
class UpdateNotes private constructor(
    val raw: ByteArray,
    val signature: ByteArray,
    val versionCode: Long,
    private val texts: Map<String, String>
) {
    val rawHex: String get() = Hex.toHexString(raw)
    val signatureHex: String get() = Hex.toHexString(signature)

    /**
     * Le texte dans la langue de l'app (« zh-Hant », « sr-Latn » d'abord, puis
     * la langue seule), sinon l'anglais, sinon le français.
     */
    fun textFor(locale: Locale): String? {
        val language = locale.language.let { if (it == "iw") "he" else it }
        val withScript = locale.script.takeIf { it.isNotEmpty() }?.let { "$language-$it" }
        return listOfNotNull(withScript, language, "en", "fr")
            .firstNotNullOfOrNull { texts[it] }
            ?: texts.values.firstOrNull()
    }

    companion object {
        /** Au-delà, ce ne sont pas des notes : on n'alloue pas. */
        const val MAX_NOTES = 24 * 1024
        private const val MAX_TEXT = 2_000
        private val CONTEXT = "whisper-notes-v1|".toByteArray(Charsets.US_ASCII)
        private val json = Json { ignoreUnknownKeys = true }

        fun signedData(raw: ByteArray): ByteArray = CONTEXT + raw

        /** Notes vérifiées pour la version [code] ; null si quoi que ce soit ne va pas. */
        fun verify(raw: ByteArray, signature: ByteArray, code: Long, key: String = UpdateManifest.DEVELOPER_KEY): UpdateNotes? {
            if (raw.isEmpty() || raw.size > MAX_NOTES || signature.size != 64) return null
            if (!CryptoManager().verify(signedData(raw), signature, key)) return null
            val o = runCatching { json.parseToJsonElement(raw.toString(Charsets.UTF_8)) as? JsonObject }
                .getOrNull() ?: return null
            if ((o["app"] as? JsonPrimitive)?.contentOrNull != UpdateManifest.APP_ID) return null
            if ((o["code"] as? JsonPrimitive)?.longOrNull != code) return null
            val notes = o["notes"] as? JsonObject ?: return null
            val texts = notes.mapNotNull { (lang, value) ->
                val text = (value as? JsonPrimitive)?.contentOrNull?.trim()
                if (lang.length in 2..8 && !text.isNullOrEmpty() && text.length <= MAX_TEXT) lang to text else null
            }.toMap()
            if (texts.isEmpty()) return null
            return UpdateNotes(raw.copyOf(), signature.copyOf(), code, texts)
        }

        fun verifyHex(rawHex: String?, signatureHex: String?, code: Long): UpdateNotes? = runCatching {
            verify(Hex.decode(rawHex ?: return null), Hex.decode(signatureHex ?: return null), code)
        }.getOrNull()
    }
}
