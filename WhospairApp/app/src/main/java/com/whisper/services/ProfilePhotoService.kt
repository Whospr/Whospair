package com.whisper.services

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import com.whisper.models.Contact
import com.whisper.repositories.ContactRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * La photo de profil : la nôtre, et celles que les contacts nous envoient.
 *
 * ### Ce qui part
 *
 * Jamais le fichier choisi : l'image est redécodée, recadrée au carré,
 * réduite à 256 px et réencodée en JPEG. Bitmap.compress n'écrit aucune
 * métadonnée — plus de position GPS, de modèle d'appareil ni de date.
 * Rangée scellée sous le PIN, comme l'identité.
 *
 * Envoyée à l'ouverture de chaque flux Tor, selon le réglage : à tout le
 * monde (contacts non confirmés compris), aux contacts confirmés, ou à
 * personne. Une trame sans image dit « retirée » : l'autre revient à
 * l'avatar neutre.
 *
 * ### Ce qui arrive
 *
 * Une image venue d'ailleurs est une entrée hostile : taille plafonnée,
 * dimensions lues avant tout décodage, puis réencodée chez nous. On ne
 * garde jamais les octets tels que reçus.
 */
@Singleton
class ProfilePhotoService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val storage: StorageService,
    private val identityService: IdentityService,
    private val contactRepository: ContactRepository
) {
    enum class Visibility { EVERYONE, CONFIRMED, HIDDEN }

    /** Photo ou réglage changé : les flux ouverts doivent la recevoir. */
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    val visibility: Visibility
        get() = runCatching {
            Visibility.valueOf(storage.getString(StorageService.KEY_PROFILE_PHOTO_VISIBILITY))
        }.getOrDefault(Visibility.CONFIRMED)

    fun setVisibility(value: Visibility) {
        storage.saveString(StorageService.KEY_PROFILE_PHOTO_VISIBILITY, value.name)
        _changes.tryEmit(Unit)
    }

    /** Notre photo, en clair en mémoire seulement ; null si aucune ou coffre fermé. */
    fun own(): ByteArray? {
        val sealed = storage.getString(StorageService.KEY_PROFILE_PHOTO).takeIf { it.isNotEmpty() } ?: return null
        return runCatching { identityService.openLocalBytes(Base64.getDecoder().decode(sealed)) }.getOrNull()
    }

    /** @return la nouvelle photo, ou null si l'image est illisible. */
    suspend fun setFromUri(uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        val jpeg = runCatching { fromUri(uri) }.getOrNull() ?: return@withContext null
        val sealed = identityService.sealLocalBytes(jpeg) ?: return@withContext null
        storage.saveString(StorageService.KEY_PROFILE_PHOTO, Base64.getEncoder().encodeToString(sealed))
        _changes.tryEmit(Unit)
        jpeg
    }

    fun clear() {
        storage.remove(StorageService.KEY_PROFILE_PHOTO)
        _changes.tryEmit(Unit)
    }

    /** La trame à envoyer à ce contact : avec l'image s'il y a droit, sans sinon. */
    fun frameFor(contact: Contact): ByteArray {
        val allowed = when (visibility) {
            Visibility.EVERYONE -> true
            Visibility.CONFIRMED -> contact.isTrusted
            Visibility.HIDDEN -> false
        }
        val photo = if (allowed) own() else null
        return buildJsonObject {
            put("v", 1)
            put("t", FRAME_TYPE)
            photo?.let { put("img", Base64.getEncoder().encodeToString(it)) }
        }.toString().toByteArray(Charsets.UTF_8)
    }

    /** Trame reçue d'un contact authentifié par le flux. Tout écart : ignorée. */
    suspend fun receive(contactPublicKey: String, frame: JsonObject) = withContext(Dispatchers.Default) {
        val img = (frame["img"] as? JsonPrimitive)?.contentOrNull
        if (img == null) {
            contactRepository.setPhoto(contactPublicKey, null)
            return@withContext
        }
        if (img.length > MAX_BASE64) return@withContext
        val bytes = runCatching { Base64.getDecoder().decode(img) }.getOrNull() ?: return@withContext
        val clean = sanitize(bytes) ?: return@withContext
        contactRepository.setPhoto(contactPublicKey, clean)
    }

    /** Dimensions d'abord (rien n'est alloué), puis décodage et réencodage. */
    private fun sanitize(bytes: ByteArray): ByteArray? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..SIZE || bounds.outHeight !in 1..SIZE) return null
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        compress(bitmap)
    }.getOrNull()

    private fun fromUri(uri: Uri): ByteArray? {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        // ImageDecoder applique l'orientation EXIF puis l'oublie.
        val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val scale = SIZE.toFloat() / minOf(w, h)
            // Réduit dès le décodage : une photo de 50 Mpx ne tient pas en mémoire.
            if (scale < 1f) {
                decoder.setTargetSize(maxOf(SIZE, (w * scale).roundToInt()), maxOf(SIZE, (h * scale).roundToInt()))
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val side = minOf(decoded.width, decoded.height)
        val square = Bitmap.createBitmap(decoded, (decoded.width - side) / 2, (decoded.height - side) / 2, side, side)
        val sized = if (side == SIZE) square else Bitmap.createScaledBitmap(square, SIZE, SIZE, true)
        return compress(sized)
    }

    /** Qualité dégressive jusqu'à tenir dans une trame Tor. */
    private fun compress(bitmap: Bitmap): ByteArray? {
        for (quality in intArrayOf(85, 70, 55)) {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (out.size() <= MAX_BYTES) return out.toByteArray()
        }
        return null
    }

    companion object {
        const val FRAME_TYPE = "photo"
        const val SIZE = 256
        /** Base64 de 40 Ko ≈ 54 Ko : sous le plafond de 64 Kio d'une trame TorLink. */
        private const val MAX_BYTES = 40_000
        private const val MAX_BASE64 = (MAX_BYTES + 2) / 3 * 4
    }
}
