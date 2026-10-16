package com.whisper.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Le fichier choisi, lu en mémoire (phase 16).
 *
 * Une photo est réencodée avant de partir : les métadonnées (position GPS,
 * modèle du téléphone, date) ne survivent pas à un nouvel encodage. Le
 * reste part tel quel. Rien n'est copié sur le disque.
 */
object FileSource {

    class Picked(val name: String, val mime: String, val data: ByteArray)

    class TooBig : Exception()

    /** Réencodées : les formats photo usuels. Un GIF animé perdrait son animation, il part tel quel. */
    private val PHOTOS = setOf("image/jpeg", "image/png", "image/webp", "image/heic", "image/heif")
    private const val MAX_SIDE = 2560
    private const val JPEG_QUALITY = 85

    suspend fun read(context: Context, uri: Uri): Picked = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var name = "fichier"
        var declared = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) declared = c.getLong(1)
            }
        }
        if (declared > FilePayload.MAX_BYTES) throw TooBig()
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val raw = resolver.openInputStream(uri)?.use(::readCapped) ?: throw java.io.IOException("Illisible")

        if (mime in PHOTOS) reencode(raw, mime)?.let { (data, newMime) ->
            raw.fill(0)
            val newName = if (newMime == "image/jpeg" && mime != "image/jpeg") name.substringBeforeLast('.') + ".jpg" else name
            return@withContext Picked(newName, newMime, data)
        }
        Picked(name, mime, raw)
    }

    /** Lit au plus la taille permise : un fournisseur qui ment sur la taille ne remplit pas la mémoire. */
    private fun readCapped(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > FilePayload.MAX_BYTES) throw TooBig()
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    /**
     * Nouvelle image, sans métadonnées, redressée selon l'orientation EXIF
     * (sinon une photo prise en portrait arriverait couchée). PNG reste PNG
     * (transparence) ; le reste devient JPEG. null si ce n'est pas une image lisible.
     */
    private fun reencode(raw: ByteArray, mime: String): Pair<ByteArray, String>? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null

        val degrees = runCatching {
            when (ExifInterface(ByteArrayInputStream(raw)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        val longest = maxOf(decoded.width, decoded.height)
        val scale = if (longest > MAX_SIDE) MAX_SIDE.toFloat() / longest else 1f
        val matrix = Matrix().apply { postScale(scale, scale); postRotate(degrees) }
        val upright = if (degrees == 0f && scale == 1f) decoded
        else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also { decoded.recycle() }

        val png = mime == "image/png"
        val out = ByteArrayOutputStream()
        upright.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        upright.recycle()
        val bytes = out.toByteArray()
        if (bytes.size > FilePayload.MAX_BYTES) return null
        bytes to (if (png) "image/png" else "image/jpeg")
    }.getOrNull()
}
