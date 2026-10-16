package com.whisper.shield

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.whisper.services.StorageService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * L'écran masqué : noir quand personne ne doit le voir.
 *
 *  - Téléphone retourné face contre la table (accéléromètre + proximité) :
 *    noir tout de suite. Toujours actif, ne coûte presque rien.
 *  - Par la caméra frontale, en option dans Paramètres, au choix : regard
 *    détourné (aucun visage), ou curieux (un second visage derrière
 *    l'utilisateur), ou seulement le visage du propriétaire (1.3.7,
 *    FaceEngine). La caméra reste allumée tant que l'app est ouverte —
 *    Android affiche son voyant, et la batterie s'en ressent.
 *
 * Seulement app déverrouillée et au premier plan (ShieldedContent).
 *
 * Code PIN sur l'écran noir (1.3.8), une porte de sortie pour chaque mode :
 *  - regard détourné, manuel : coupé jusqu'à ce qu'on le réactive (pièce
 *    sombre) ; automatique : coupé de 20 h à 6 h sans rien demander, et le
 *    code le coupe jusqu'au prochain 6 h ;
 *  - curieux : pause de 10 min (pour montrer l'écran), ou arrêt complet. La
 *    pause finie, l'app propose de la prolonger ou d'arrêter. Passer en
 *    arrière-plan met fin à la pause.
 */
@Singleton
class ScreenShield @Inject constructor(
    @ApplicationContext context: Context,
    private val storage: StorageService
) : SensorEventListener {

    private val sensors = context.getSystemService(SensorManager::class.java)
    private val accelerometer = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val proximity = sensors?.getDefaultSensor(Sensor.TYPE_PROXIMITY)

    private val _hidden = MutableStateFlow(false)
    val hidden: StateFlow<Boolean> = _hidden.asStateFlow()

    @Volatile private var faceDown = false
    @Volatile private var cameraAlert = false
    @Volatile private var screenDown = false
    // Sans capteur de proximité, l'accéléromètre seul décide.
    @Volatile private var near = proximity == null
    @Volatile private var running = false

    /** Reconnaissance du propriétaire (« seulement mon visage », 1.3.7). */
    val face = FaceEngine(context, storage)

    private val gaze = GazeWatcher(context, face) { visible ->
        cameraAlert = !visible
        publish()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    enum class Schedule { MANUAL, AUTO }

    /** Regard détourné : manuel, ou automatique (coupé de 20 h à 6 h). */
    var schedule: Schedule
        get() = runCatching { Schedule.valueOf(storage.getString(KEY_SCHEDULE)) }.getOrDefault(Schedule.MANUAL)
        set(value) {
            storage.saveString(KEY_SCHEDULE, value.name)
            storage.remove(KEY_AUTO_OFF_UNTIL)
            refreshGaze()
        }

    /** Curieux en pause jusqu'à cet instant (elapsedRealtime) ; 0 : pas de pause. */
    @Volatile private var pausedUntil = 0L

    private val _pauseOver = MutableStateFlow(false)
    /** La pause de 10 min vient de finir : l'écran propose de prolonger ou d'arrêter. */
    val pauseOver: StateFlow<Boolean> = _pauseOver.asStateFlow()

    private var clockJob: kotlinx.coroutines.Job? = null

    init {
        // Le scanner QR prend la caméra arrière : sur beaucoup de téléphones,
        // les deux ne tournent pas ensemble.
        scope.launch {
            cameraClaimed.collect { claimed -> if (claimed) stopGaze() else startGazeIfWanted() }
        }
    }

    enum class GazeMode { OFF, LOOK_AWAY, ONLOOKER, OWNER }

    /** « Seulement mon visage » est-il possible dans cet APK (module natif présent) ? */
    val ownerAvailable: Boolean get() = FaceEngine.loaded

    /**
     * Enregistre le visage du propriétaire : quelques prises où un seul
     * visage est vu, en [timeoutMs] au plus. Pendant ce temps, rien ne masque.
     * @return true si assez de prises ont été faites.
     */
    suspend fun enrollOwner(timeoutMs: Long = 15_000L): Boolean {
        val samples = java.util.Collections.synchronizedList(mutableListOf<FloatArray>())
        val done = kotlinx.coroutines.CompletableDeferred<Unit>()
        enrolling = true
        gaze.enroll { sample ->
            samples += sample
            if (samples.size >= ENROLL_SAMPLES) done.complete(Unit)
        }
        try {
            kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { done.await() }
        } finally {
            // Même interrompu (écran quitté) : sinon la caméra resterait allumée
            // et les images iraient encore à l'enregistrement, sans rien masquer.
            gaze.stopEnroll()
            enrolling = false
            startGazeIfWanted()
        }
        if (samples.size < ENROLL_SAMPLES / 2) return false
        face.enroll(samples.toList())
        return true
    }

    private val _mode by lazy { MutableStateFlow(gazeMode) }
    /** Le mode en cours, pour Réglages (le code PIN peut le changer depuis l'écran noir). */
    val mode: StateFlow<GazeMode> get() = _mode.asStateFlow()

    var gazeMode: GazeMode
        get() {
            // Ancien réglage (oui/non, regard détourné seulement) : repris une fois.
            if (storage.contains(LEGACY_GAZE_KEY)) {
                val was = storage.getBoolean(LEGACY_GAZE_KEY)
                storage.remove(LEGACY_GAZE_KEY)
                if (was) storage.saveString(StorageService.KEY_GAZE_MODE, GazeMode.LOOK_AWAY.name)
            }
            return runCatching { GazeMode.valueOf(storage.getString(StorageService.KEY_GAZE_MODE)) }
                .getOrDefault(GazeMode.OFF)
        }
        set(value) {
            storage.saveString(StorageService.KEY_GAZE_MODE, value.name)
            _mode.value = value
            pausedUntil = 0L
            _pauseOver.value = false
            if (value == GazeMode.OFF) stopGaze() else startGazeIfWanted()
        }

    fun start() {
        if (running) return
        running = true
        accelerometer?.let { sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        proximity?.let { sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        startGazeIfWanted()
        // Fin de pause, 20 h, 6 h : la caméra suit l'heure tant que l'app est ouverte.
        clockJob = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(CLOCK_TICK_MS)
                if (pausedUntil != 0L && android.os.SystemClock.elapsedRealtime() >= pausedUntil) {
                    pausedUntil = 0L
                    if (gazeMode == GazeMode.ONLOOKER) _pauseOver.value = true
                }
                refreshGaze()
            }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        sensors?.unregisterListener(this)
        clockJob?.cancel()
        clockJob = null
        // Arrière-plan : la pause du mode curieux prend fin.
        pausedUntil = 0L
        _pauseOver.value = false
        stopGaze()
        screenDown = false
        faceDown = false
        publish()
    }

    /** Caméra coupée : plus d'alerte possible de sa part. */
    private fun stopGaze() {
        gaze.stop()
        cameraAlert = false
        publish()
    }

    private fun startGazeIfWanted() {
        val mode = gazeMode
        // « Seulement mon visage » sans module natif ni visage enregistré : rien à reconnaître.
        if (mode == GazeMode.OWNER && (!ownerAvailable || !face.hasOwner())) return
        if (!resting(mode) && running && mode != GazeMode.OFF && !cameraClaimed.value) gaze.start(mode)
    }

    /** Caméra en sommeil pour ce mode : pause du curieux, nuit du regard détourné automatique. */
    private fun resting(mode: GazeMode): Boolean = when (mode) {
        GazeMode.ONLOOKER -> pausedUntil != 0L
        GazeMode.LOOK_AWAY -> schedule == Schedule.AUTO &&
            (isNight() || System.currentTimeMillis() < storage.getLong(KEY_AUTO_OFF_UNTIL))
        else -> false
    }

    /** Enregistrement du visage en cours : la caméra lui appartient, la surveillance de l'heure n'y touche pas. */
    @Volatile private var enrolling = false

    private fun refreshGaze() {
        if (!running || enrolling) return
        if (resting(gazeMode)) stopGaze() else startGazeIfWanted()
    }

    /** Ce que fait le code PIN sur l'écran noir. Curieux : l'écran demande d'abord pause ou arrêt. */
    fun escapeWithPin() {
        when (gazeMode) {
            GazeMode.LOOK_AWAY -> if (schedule == Schedule.AUTO) {
                storage.saveLong(KEY_AUTO_OFF_UNTIL, nextMorning())
                refreshGaze()
            } else gazeMode = GazeMode.OFF
            GazeMode.ONLOOKER -> Unit
            else -> gazeMode = GazeMode.OFF
        }
    }

    /** Curieux : 10 min sans caméra, pour montrer l'écran. */
    fun pauseOnlooker() {
        pausedUntil = android.os.SystemClock.elapsedRealtime() + PAUSE_MS
        _pauseOver.value = false
        stopGaze()
    }

    /** Fin de pause : on garde le masquage (rien à faire, la caméra a repris). */
    fun keepShield() {
        _pauseOver.value = false
        pausedUntil = 0L
        refreshGaze()
    }

    private fun isNight(): Boolean {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return hour >= NIGHT_FROM || hour < NIGHT_UNTIL
    }

    /** Le prochain 6 h, date du téléphone. */
    private fun nextMorning(): Long = java.util.Calendar.getInstance().apply {
        if (get(java.util.Calendar.HOUR_OF_DAY) >= NIGHT_UNTIL) add(java.util.Calendar.DAY_OF_YEAR, 1)
        set(java.util.Calendar.HOUR_OF_DAY, NIGHT_UNTIL)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            // z négatif : l'écran regarde le sol.
            Sensor.TYPE_ACCELEROMETER -> screenDown = event.values[2] < FACE_DOWN_Z
            Sensor.TYPE_PROXIMITY -> near = event.values[0] < minOf(event.sensor.maximumRange, NEAR_CM)
        }
        faceDown = screenDown && near
        publish()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun publish() {
        _hidden.value = running && (faceDown || cameraAlert)
    }

    companion object {
        /** Environ 50° au-delà de l'horizontale, écran vers le bas. */
        private const val FACE_DOWN_Z = -7.5f
        private const val NEAR_CM = 5f
        private const val ENROLL_SAMPLES = 8
        private const val LEGACY_GAZE_KEY = "gaze_shield"
        private const val KEY_SCHEDULE = "gaze_schedule"
        /** Regard détourné automatique coupé au code PIN : jusqu'à cette date (prochain 6 h). */
        private const val KEY_AUTO_OFF_UNTIL = "gaze_auto_off_until"
        private const val PAUSE_MS = 10 * 60 * 1000L
        private const val CLOCK_TICK_MS = 15_000L
        /** Regard détourné automatique : coupé de 20 h à 6 h (lumière insuffisante). */
        private const val NIGHT_FROM = 20
        private const val NIGHT_UNTIL = 6

        /** Vrai pendant qu'un autre écran de l'app se sert de la caméra (scanner QR). */
        val cameraClaimed = MutableStateFlow(false)
    }
}
