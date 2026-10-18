package com.whisper.shield

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.FaceDetector
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.nio.ShortBuffer
import java.util.concurrent.Executor

/**
 * Regarde, par la caméra frontale, qui est devant l'écran. Trois modes :
 *  - LOOK_AWAY : aucun visage → masqué (l'utilisateur a détourné le regard) ;
 *  - ONLOOKER : deux visages ou plus → masqué (quelqu'un regarde par-dessus
 *    l'épaule). Image plus grande (480 px) : un visage à un mètre derrière
 *    n'est sinon qu'une poignée de pixels ;
 *  - OWNER : seulement le visage du propriétaire (FaceEngine, modèles
 *    YuNet/SFace intégrés à l'APK, image couleur de 480 px).
 *
 * Tout reste dans le téléphone. LOOK_AWAY et ONLOOKER : une petite image en
 * niveaux de gris, analysée par android.media.FaceDetector (dans Android
 * depuis toujours, pas de ML Kit), puis oubliée. Rien n'est écrit (hors
 * l'empreinte du propriétaire), rien ne sort. Une analyse toutes les 700 ms environ.
 *
 * [onChange] (true = l'écran peut rester visible) est appelé depuis le fil de la caméra.
 * Caméra perdue (prise par le scanner QR, par une autre app) : l'écran
 * reste visible — un défaut ne doit jamais masquer l'écran pour rien.
 */
class GazeWatcher(
    private val context: Context,
    private val face: FaceEngine,
    private val onChange: (Boolean) -> Unit
) {

    /** Enregistrement du visage en cours : chaque prise à un seul visage y va, rien ne masque. */
    @Volatile private var enrollSink: ((FloatArray) -> Unit)? = null

    /** Démarre la caméra pour enregistrer le visage du propriétaire. */
    fun enroll(sink: (FloatArray) -> Unit) {
        enrollSink = sink
        if (active) stop()
        start(ScreenShield.GazeMode.OWNER)
    }

    fun stopEnroll() {
        enrollSink = null
        stop()
    }

    /** Un seul fil pour toute la vie de l'app : un rappel tardif trouve toujours où s'exécuter. */
    private val handler by lazy { Handler(HandlerThread("whisper-gaze").apply { start() }.looper) }
    private val executor = Executor { handler.post(it) }

    /** Change à chaque start/stop : un rappel d'une session précédente se reconnaît et se ferme. */
    @Volatile private var generation = 0
    @Volatile private var active = false

    // Touchés sur le fil de la caméra seulement.
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var sensorOrientation = 270
    private var lastCheck = 0L
    private var misses = 0
    @Volatile private var mode = ScreenShield.GazeMode.LOOK_AWAY

    @SuppressLint("MissingPermission") // vérifiée juste avant
    fun start(mode: ScreenShield.GazeMode) {
        if (active && mode == this.mode) return
        if (active) stop() // autre mode, autre taille d'image : on rouvre
        this.mode = mode
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        active = true
        val gen = ++generation
        handler.post {
            runCatching { open(gen) }.onFailure { release(); onChange(true) }
        }
    }

    fun stop() {
        if (!active) return
        active = false
        generation++
        handler.post { release() }
        onChange(true)
    }

    @SuppressLint("MissingPermission")
    private fun open(gen: Int) {
        if (gen != generation) return
        val manager = context.getSystemService(CameraManager::class.java) ?: return
        val id = manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        } ?: return
        val characteristics = manager.getCameraCharacteristics(id)
        sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 270
        // La plus petite image utile : assez pour les visages cherchés, rien de plus.
        // Reconnaître un visage demande plus de détails que le compter.
        val minSide = if (mode == ScreenShield.GazeMode.LOOK_AWAY) MIN_SIDE else ONLOOKER_MIN_SIDE
        val size = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.YUV_420_888)
            ?.filter { it.width >= minSide && it.height >= minSide }
            ?.minByOrNull { it.width * it.height } ?: return

        val imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
        imageReader.setOnImageAvailableListener({ onImage(it) }, handler)
        reader = imageReader
        misses = 0

        manager.openCamera(id, executor, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                if (gen != generation) { device.close(); return }
                camera = device
                val config = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(OutputConfiguration(imageReader.surface)),
                    executor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            if (gen != generation) { s.close(); return }
                            session = s
                            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                .apply { addTarget(imageReader.surface) }
                                .build()
                            runCatching { s.setRepeatingRequest(request, null, handler) }
                        }

                        override fun onConfigureFailed(s: CameraCaptureSession) {
                            release()
                            onChange(true)
                        }
                    }
                )
                runCatching { device.createCaptureSession(config) }.onFailure { release(); onChange(true) }
            }

            override fun onDisconnected(device: CameraDevice) {
                device.close()
                if (camera === device) camera = null
                onChange(true)
            }

            override fun onError(device: CameraDevice, error: Int) {
                device.close()
                if (camera === device) camera = null
                onChange(true)
            }
        })
    }

    private fun release() {
        runCatching { session?.close() }
        runCatching { camera?.close() }
        runCatching { reader?.close() }
        session = null
        camera = null
        reader = null
    }

    private fun onImage(source: ImageReader) {
        val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return
        val gen = generation
        try {
            if (!active) return
            val now = SystemClock.elapsedRealtime()
            if (now - lastCheck < CHECK_INTERVAL_MS) return
            lastCheck = now
            if (mode == ScreenShield.GazeMode.OWNER) return checkOwner(image, gen)
            val onlooker = mode == ScreenShield.GazeMode.ONLOOKER
            val faces = countFaces(image, if (onlooker) 3 else 1)
            // Arrêtée pendant l'analyse : son verdict ne compte plus.
            if (gen != generation) return
            val alarm = if (onlooker) faces >= 2 else faces == 0
            if (!alarm) {
                misses = 0
                onChange(true)
            } else if (++misses >= MISSES_TO_HIDE) {
                onChange(false)
            }
        } finally {
            image.close()
        }
    }

    /**
     * « Seulement mon visage » : visible si le propriétaire est là et personne
     * d'autre. Dans le doute (aucun visage, un inconnu, visage non reconnu),
     * masqué. Moteur absent : comme une caméra perdue, on ne masque pas pour rien.
     */
    private fun checkOwner(image: Image, gen: Int) {
        val upright = face.faces(image, sensorOrientation)
        val faces = upright?.takeIf { it.isNotEmpty() }
            ?: face.faces(image, (sensorOrientation + 180) % 360)
        if (gen != generation) return
        if (faces == null) { onChange(true); return }
        enrollSink?.let { sink ->
            if (faces.size == 1) sink(faces[0])
            return
        }
        val owner = faces.isNotEmpty() && faces.all(face::isOwner)
        if (owner) {
            misses = 0
            onChange(true)
        } else if (++misses >= MISSES_TO_HIDE) {
            onChange(false)
        }
    }

    /**
     * Dans le sens de l'écran d'abord (orientation du capteur), puis à
     * l'envers : selon les téléphones, le capteur frontal est monté dans un
     * sens ou dans l'autre. La seconde passe ne coûte que quand personne
     * n'est trouvé.
     */
    private fun countFaces(image: Image, max: Int): Int {
        val plane = image.planes[0]
        val w = image.width
        val h = image.height
        val luma = ByteArray(w * h)
        val buffer = plane.buffer
        for (y in 0 until h) {
            buffer.position(y * plane.rowStride)
            if (plane.pixelStride == 1) {
                buffer.get(luma, y * w, w)
            } else {
                for (x in 0 until w) luma[y * w + x] = buffer.get(y * plane.rowStride + x * plane.pixelStride)
            }
        }
        val upright = detect(luma, w, h, sensorOrientation, max)
        return if (upright > 0) upright else detect(luma, w, h, (sensorOrientation + 180) % 360, max)
    }

    /** FaceDetector veut du RGB_565, une largeur paire, et un visage à l'endroit. */
    private fun detect(luma: ByteArray, w: Int, h: Int, rotation: Int, max: Int): Int {
        val turned = rotation == 90 || rotation == 270
        val outW = (if (turned) h else w) and 1.inv()
        val outH = if (turned) w else h
        val pixels = ShortArray(outW * outH)
        for (dy in 0 until outH) {
            for (dx in 0 until outW) {
                // Pixel source de (dx, dy), image tournée dans le sens horaire.
                val source = when (rotation) {
                    90 -> (h - 1 - dx) * w + dy
                    180 -> (h - 1 - dy) * w + (w - 1 - dx)
                    270 -> dx * w + (w - 1 - dy)
                    else -> dy * w + dx
                }
                val v = luma[source].toInt() and 0xff
                pixels[dy * outW + dx] = (((v shr 3) shl 11) or ((v shr 2) shl 5) or (v shr 3)).toShort()
            }
        }
        val bitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.RGB_565)
        bitmap.copyPixelsFromBuffer(ShortBuffer.wrap(pixels))
        return try {
            FaceDetector(outW, outH, max).findFaces(bitmap, arrayOfNulls(max))
        } finally {
            bitmap.recycle()
        }
    }

    companion object {
        private const val MIN_SIDE = 240
        private const val ONLOOKER_MIN_SIDE = 480
        private const val CHECK_INTERVAL_MS = 700L
        /** Deux analyses d'alerte de suite (≈ 1,5 s) : un clignement ne masque rien. */
        private const val MISSES_TO_HIDE = 2
    }
}
