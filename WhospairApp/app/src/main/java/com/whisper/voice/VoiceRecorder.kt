package com.whisper.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Enregistre la voix et l'encode en Opus, entièrement en mémoire.
 *
 * Pas de MediaRecorder : il écrit dans un fichier, et un fichier audio en
 * clair sur le disque, même temporaire, survit à un effacement ordinaire.
 * Ici, le micro (AudioRecord) nourrit l'encodeur Opus d'Android (MediaCodec,
 * disponible dès Android 10) ; seuls les paquets encodés sont gardés, et le
 * son brut est effacé au fil de l'eau.
 *
 * Format produit (lu par VoicePlayer) :
 *   [u16 taille][en-tête Opus de l'encodeur] puis, par paquet, [u16 taille][paquet]
 */
class VoiceRecorder(private val scope: CoroutineScope) {

    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsedMs.asStateFlow()

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    private var job: Job? = null
    @Volatile private var stopRequested = false
    @Volatile private var result: Clip? = null

    class Clip(val bytes: ByteArray, val durationMs: Int)

    /**
     * Chaque enregistrement a son numéro : un ancien, encore en train de
     * vider l'encodeur après une annulation, ne touche plus à l'état.
     */
    @Volatile private var generation = 0

    /**
     * Appelant : la permission RECORD_AUDIO est déjà accordée.
     * Refusé tant que le précédent n'a pas lâché le micro (jusqu'à 2 s
     * après une annulation) : deux enregistrements ne tournent jamais ensemble.
     */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (job?.isCompleted == false) return false
        val mine = ++generation
        stopRequested = false
        result = null
        _elapsedMs.value = 0
        _recording.value = true
        job = scope.launch(Dispatchers.IO) {
            val clip = runCatching { record(mine) }.getOrNull()
            if (mine == generation) {
                result = clip
                _recording.value = false
            }
        }
        return true
    }

    /** Arrête et rend l'enregistrement ; null s'il est trop court ou a échoué. */
    suspend fun stop(): Clip? {
        val mine = generation
        stopRequested = true
        job?.join()
        if (mine != generation) return null
        return result?.takeIf { it.durationMs >= MIN_MS }.also { result = null }
    }

    /** Arrête et jette tout. */
    fun cancel() {
        generation++
        stopRequested = true
        job?.cancel()
        result = null
        _elapsedMs.value = 0
        _recording.value = false
    }

    @SuppressLint("MissingPermission")
    private suspend fun record(mine: Int): Clip {
        val minBuffer = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val mic = AudioRecord(
            MediaRecorder.AudioSource.MIC, RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, FRAME_BYTES * 4)
        )
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, RATE, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME_BYTES * 4)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        var header: ByteArray? = null
        val packets = ByteArrayOutputStream()
        val packetData = DataOutputStream(packets)
        val pcm = ByteArray(FRAME_BYTES)
        var samples = 0L
        var lastPtsUs = 0L
        val info = MediaCodec.BufferInfo()

        /** Récupère les paquets prêts ; en fin d'enregistrement, attend la fin du flux (2 s au plus). */
        fun drain(untilEos: Boolean) {
            var waits = 0
            while (true) {
                val index = codec.dequeueOutputBuffer(info, if (untilEos) 10_000 else 0)
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!untilEos || ++waits > 200) return
                    continue
                }
                if (index < 0) continue // changement de format : rien à lire
                val buffer = codec.getOutputBuffer(index)!!
                val bytes = ByteArray(info.size).also { buffer.position(info.offset); buffer.get(it) }
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    header = bytes
                } else if (bytes.isNotEmpty()) {
                    packetData.writeShort(bytes.size)
                    packetData.write(bytes)
                    lastPtsUs = info.presentationTimeUs
                }
                codec.releaseOutputBuffer(index, false)
                if (eos) return
            }
        }

        try {
            codec.start()
            mic.startRecording()
            while (!stopRequested && samples < RATE.toLong() * MAX_MS / 1000 && kotlinx.coroutines.currentCoroutineContext().isActive) {
                val read = mic.read(pcm, 0, pcm.size)
                if (read <= 0) continue
                val input = codec.dequeueInputBuffer(10_000)
                if (input >= 0) {
                    codec.getInputBuffer(input)!!.apply { clear(); put(pcm, 0, read) }
                    codec.queueInputBuffer(input, 0, read, samples * 1_000_000 / RATE, 0)
                    samples += read / 2
                    if (mine == generation) _elapsedMs.value = samples * 1000 / RATE
                }
                drain(untilEos = false)
            }
            mic.stop()
            val eosInput = codec.dequeueInputBuffer(100_000)
            if (eosInput >= 0) {
                codec.queueInputBuffer(eosInput, 0, 0, samples * 1_000_000 / RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                drain(untilEos = true)
            }
        } finally {
            pcm.fill(0)
            runCatching { mic.release() }
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }

        val head = header ?: error("Encodeur Opus sans en-tête")
        data.writeShort(head.size)
        data.write(head)
        data.write(packets.toByteArray())
        return Clip(out.toByteArray(), (maxOf(lastPtsUs / 1000, samples * 1000 / RATE)).toInt())
    }

    companion object {
        const val RATE = 48_000
        const val BITRATE = 16_000
        const val MAX_MS = 60_000
        private const val MIN_MS = 700
        /** 20 ms de son mono 16 bits. */
        private const val FRAME_BYTES = RATE / 50 * 2
    }
}
