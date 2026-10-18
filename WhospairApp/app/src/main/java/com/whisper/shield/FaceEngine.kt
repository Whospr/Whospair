package com.whisper.shield

import android.content.Context
import android.media.Image
import com.whisper.services.StorageService
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * « Seulement mon visage » (1.3.7) : reconnaître le propriétaire devant l'écran.
 *
 * Le calcul est fait par libwhospairface.so (native/face) : OpenCV réduit,
 * YuNet trouve les visages, SFace en tire une empreinte de 128 nombres. Ici,
 * on compare ces empreintes à celle du propriétaire (similarité cosinus).
 *
 * L'empreinte du propriétaire — des nombres, pas une photo — est gardée
 * dans le stockage chiffré par le Keystore. Aucune image n'est écrite ni ne
 * sort du téléphone.
 *
 * Le module natif (native/face) n'est pas compilé par Gradle, il l'est à
 * part ; sans lui, [loaded] est faux et l'option n'est pas proposée.
 */
class FaceEngine(private val context: Context, private val storage: StorageService) {

    private var handle = 0L

    @Synchronized
    private fun ensure(): Boolean {
        if (handle != 0L) return true
        if (!loaded) return false
        val dir = File(context.noBackupFilesDir, "face").apply { mkdirs() }
        val paths = MODELS.map { name ->
            val target = File(dir, name)
            // Modèles publics (opencv_zoo) : copiés une fois depuis l'APK, lus par OpenCV.
            // Copie dans un fichier temporaire, renommé une fois complet : une copie
            // interrompue ne laisse jamais un modèle tronqué à sa place.
            if (!target.exists()) runCatching {
                val part = File(dir, "$name.part")
                context.assets.open("face/$name").use { input -> part.outputStream().use { input.copyTo(it) } }
                check(part.renameTo(target))
            }.onFailure { return false }
            target.absolutePath
        }
        handle = runCatching { nativeInit(paths[0], paths[1]) }.getOrDefault(0L)
        return handle != 0L
    }

    /** Empreintes des visages de l'image (au plus 3) ; null si le moteur est indisponible. */
    fun faces(image: Image, rotation: Int): List<FloatArray>? {
        if (!ensure()) return null
        val nv21 = toNv21(image)
        val flat = runCatching { nativeFaces(handle, nv21, image.width, image.height, rotation) }.getOrNull()
        nv21.fill(0)
        flat ?: return null
        return (0 until flat.size / DIM).map { i -> flat.copyOfRange(i * DIM, (i + 1) * DIM) }
    }

    fun hasOwner(): Boolean = owner() != null

    /** Le visage est-il celui du propriétaire ? */
    fun isOwner(face: FloatArray): Boolean {
        val reference = owner() ?: return false
        return cosine(reference, face) >= MATCH
    }

    /** Empreinte moyenne de plusieurs prises : plus stable qu'une seule. */
    fun enroll(samples: List<FloatArray>) {
        require(samples.isNotEmpty())
        val mean = FloatArray(DIM)
        samples.forEach { s -> normalized(s).forEachIndexed { i, v -> mean[i] += v } }
        val bytes = ByteBuffer.allocate(DIM * 4).order(ByteOrder.LITTLE_ENDIAN)
        normalized(mean).forEach { bytes.putFloat(it) }
        storage.saveString(KEY_OWNER, java.util.Base64.getEncoder().encodeToString(bytes.array()))
    }

    fun forgetOwner() = storage.remove(KEY_OWNER)

    private fun owner(): FloatArray? = runCatching {
        val raw = java.util.Base64.getDecoder().decode(storage.getString(KEY_OWNER).ifEmpty { return null })
        val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        FloatArray(DIM) { buffer.float }
    }.getOrNull()

    /** YUV_420_888 → NV21 (Y puis V/U entrelacés), ce qu'attend le module natif. */
    private fun toNv21(image: Image): ByteArray {
        val w = image.width
        val h = image.height
        val out = ByteArray(w * h * 3 / 2)
        val y = image.planes[0]
        for (row in 0 until h) {
            val base = row * y.rowStride
            for (col in 0 until w) out[row * w + col] = y.buffer.get(base + col * y.pixelStride)
        }
        val u = image.planes[1]
        val v = image.planes[2]
        var at = w * h
        for (row in 0 until h / 2) {
            for (col in 0 until w / 2) {
                out[at++] = v.buffer.get(row * v.rowStride + col * v.pixelStride)
                out[at++] = u.buffer.get(row * u.rowStride + col * u.pixelStride)
            }
        }
        return out
    }

    private fun normalized(v: FloatArray): FloatArray {
        val norm = sqrt(v.fold(0f) { acc, x -> acc + x * x }).takeIf { it > 0f } ?: 1f
        return FloatArray(v.size) { v[it] / norm }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        val na = normalized(a)
        val nb = normalized(b)
        return na.indices.fold(0f) { acc, i -> acc + na[i] * nb[i] }
    }

    private external fun nativeInit(detector: String, recognizer: String): Long
    private external fun nativeFaces(handle: Long, nv21: ByteArray, width: Int, height: Int, rotation: Int): FloatArray?
    private external fun nativeRelease(handle: Long)

    companion object {
        private const val DIM = 128
        /** Seuil recommandé pour SFace (similarité cosinus, opencv_zoo). */
        private const val MATCH = 0.363f
        private const val KEY_OWNER = "face_owner"
        private val MODELS = listOf("face_detection_yunet_2023mar.onnx", "face_recognition_sface_2021dec_int8.onnx")

        /** Le module natif est-il dans cet APK ? */
        val loaded: Boolean by lazy { runCatching { System.loadLibrary("whospairface") }.isSuccess }
    }
}
