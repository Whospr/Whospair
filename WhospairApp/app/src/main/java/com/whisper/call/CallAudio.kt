package com.whisper.call

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

/**
 * Le son d'un appel : micro → Opus → trames, et trames → Opus → écouteur.
 * Tout en mémoire, rien sur le disque.
 *
 * ### Mode dégradé, pour Tor
 *
 * Tor ajoute 0,5 à 2 s de latence, avec des à-coups. Donc :
 *  - trois trames Opus de 20 ms par envoi (60 ms) : trois fois moins de
 *    cellules Tor, pour la même voix ;
 *  - un tampon anti-gigue : la lecture démarre avec [START_PACKETS] envois
 *    d'avance, et si le retard dépasse [MAX_PACKETS], le plus ancien est
 *    jeté — mieux vaut un mot perdu qu'une conversation qui dérive ;
 *  - l'annulation d'écho et le réducteur de bruit d'Android, quand le
 *    téléphone les a.
 *
 * Deux fils : [start] les lance, [stop] les arrête. Pas de réutilisation.
 */
class CallAudio(
    /** Micro ou haut-parleur en panne : l'appel ne peut plus continuer. */
    private val onFailure: () -> Unit,
    /** Appelé depuis le fil du micro, un envoi toutes les 60 ms. */
    private val onPacket: (ByteArray) -> Unit
) {
    @Volatile private var running = false
    @Volatile var muted = false

    private val jitter = LinkedBlockingDeque<ByteArray>()
    private var capture: Thread? = null
    private var playback: Thread? = null

    fun start() {
        running = true
        capture = Thread({ guarded(::captureLoop) }, "call-capture").apply { start() }
        playback = Thread({ guarded(::playbackLoop) }, "call-playback").apply { start() }
    }

    /** Une panne pendant l'appel le termine, au lieu de laisser un appel muet. */
    private fun guarded(loop: () -> Unit) {
        runCatching(loop).onFailure { if (running) onFailure() }
    }

    fun stop() {
        running = false
        capture?.join(1_000)
        playback?.join(1_000)
        jitter.clear()
    }

    /** Un envoi reçu du contact, déjà déchiffré. */
    fun receive(group: ByteArray) {
        jitter.offerLast(group)
        // Trop de retard accumulé : on rattrape en jetant le plus ancien.
        while (jitter.size > MAX_PACKETS) jitter.pollFirst()
    }

    /* ====================================================================== *
     *  Micro → Opus
     * ====================================================================== */

    @SuppressLint("MissingPermission")
    private fun captureLoop() {
        val minBuffer = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // VOICE_COMMUNICATION : la source prévue pour les appels, celle sur
        // laquelle Android branche son annulation d'écho.
        val mic = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, FRAME_BYTES * 4)
        )
        val echo = if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(mic.audioSessionId) else null
        val noise = if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(mic.audioSessionId) else null
        echo?.enabled = true
        noise?.enabled = true

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        codec.configure(
            MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, RATE, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME_BYTES * 4)
            },
            null, null, MediaCodec.CONFIGURE_FLAG_ENCODE
        )

        val pcm = ByteArray(FRAME_BYTES)
        val info = MediaCodec.BufferInfo()
        val pending = ArrayList<ByteArray>(GROUP)
        var samples = 0L
        try {
            codec.start()
            mic.startRecording()
            if (mic.recordingState != AudioRecord.RECORDSTATE_RECORDING) error("Micro non démarré")
            while (running) {
                val read = mic.read(pcm, 0, pcm.size)
                // Erreur du micro (serveur audio redémarré…) : sans cette
                // sortie, la boucle tournerait à vide sur un cœur entier.
                if (read < 0) error("Micro indisponible : $read")
                if (read == 0) continue
                // Micro coupé : on continue d'envoyer, du silence — l'autre
                // côté garde son tampon réglé, et rien ne trahit la coupure.
                if (muted) pcm.fill(0, 0, read)
                val input = codec.dequeueInputBuffer(10_000)
                if (input >= 0) {
                    codec.getInputBuffer(input)!!.apply { clear(); put(pcm, 0, read) }
                    codec.queueInputBuffer(input, 0, read, samples * 1_000_000 / RATE, 0)
                    samples += read / 2
                }
                while (true) {
                    val index = codec.dequeueOutputBuffer(info, 0)
                    if (index == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (index < 0) continue
                    val buffer = codec.getOutputBuffer(index)!!
                    val packet = ByteArray(info.size).also { buffer.position(info.offset); buffer.get(it) }
                    codec.releaseOutputBuffer(index, false)
                    // L'en-tête de l'encodeur ne voyage pas : l'autre côté le reconstruit.
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 || packet.isEmpty()) continue
                    pending += packet
                    if (pending.size == GROUP) {
                        onPacket(CallProtocol.group(pending))
                        pending.clear()
                    }
                }
            }
        } finally {
            pcm.fill(0)
            runCatching { mic.stop() }
            echo?.release()
            noise?.release()
            mic.release()
            runCatching { codec.stop() }
            codec.release()
        }
    }

    /* ====================================================================== *
     *  Opus → écouteur
     * ====================================================================== */

    private fun playbackLoop() {
        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        codec.configure(decoderFormat(), null, null, 0)
        codec.start()
        var track: AudioTrack? = null
        val info = MediaCodec.BufferInfo()
        var ptsUs = 0L
        var buffering = true

        try {
            while (running) {
                // Démarrage, ou tampon vidé par un à-coup de Tor : on attend
                // d'avoir de l'avance plutôt que de hacher chaque mot.
                if (buffering) {
                    if (jitter.size < START_PACKETS) { Thread.sleep(20); continue }
                    buffering = false
                }
                val group = jitter.pollFirst(200, TimeUnit.MILLISECONDS)
                if (group == null) { buffering = true; continue }
                val packets = CallProtocol.ungroup(group) ?: continue

                for (packet in packets) {
                    val slot = codec.dequeueInputBuffer(10_000)
                    if (slot < 0) continue
                    codec.getInputBuffer(slot)!!.apply { clear(); put(packet) }
                    codec.queueInputBuffer(slot, 0, packet.size, ptsUs, 0)
                    ptsUs += FRAME_US
                }
                while (true) {
                    val index = codec.dequeueOutputBuffer(info, 5_000)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        track?.release()
                        track = audioTrack(codec.outputFormat)
                        continue
                    }
                    if (index < 0) break
                    val buffer = codec.getOutputBuffer(index)!!
                    val pcm = ByteArray(info.size).also { buffer.position(info.offset); buffer.get(it) }
                    codec.releaseOutputBuffer(index, false)
                    val out = track ?: audioTrack(codec.outputFormat).also { track = it }
                    // Bloquant : c'est l'écouteur qui donne le rythme.
                    if (pcm.isNotEmpty()) out.write(pcm, 0, pcm.size)
                    pcm.fill(0)
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            track?.let { runCatching { it.stop() }; it.release() }
        }
    }

    private fun audioTrack(format: MediaFormat): AudioTrack {
        val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val mask = if (format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 2) AudioFormat.CHANNEL_OUT_STEREO
        else AudioFormat.CHANNEL_OUT_MONO
        val min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // Écouteur par défaut, haut-parleur au choix (CallService).
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setChannelMask(mask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(min * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.play() }
    }

    /**
     * L'en-tête Opus ne voyage pas : il est toujours le même (mono, 48 kHz),
     * on le reconstruit. Voir RFC 7845 §5.1.
     */
    private fun decoderFormat(): MediaFormat {
        val head = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
            .put("OpusHead".toByteArray(Charsets.US_ASCII))
            .put(1)                     // version
            .put(1)                     // mono
            .putShort(PRE_SKIP.toShort())
            .putInt(RATE)
            .putShort(0)                // gain
            .put(0)                     // mappage simple
            .array()
        return MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, RATE, 1).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(head))
            setByteBuffer("csd-1", nativeLong(PRE_SKIP * 1_000_000_000L / RATE))
            setByteBuffer("csd-2", nativeLong(80_000_000L))
        }
    }

    private fun nativeLong(value: Long): ByteBuffer =
        ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(value).apply { flip() }

    companion object {
        private const val RATE = 48_000
        private const val BITRATE = 16_000
        /** 20 ms de son mono 16 bits. */
        private const val FRAME_BYTES = RATE / 50 * 2
        private const val FRAME_US = 20_000L
        private const val PRE_SKIP = 312
        /** Trames Opus par envoi : 60 ms. */
        private const val GROUP = 3
        /** 240 ms d'avance au démarrage. */
        private const val START_PACKETS = 4
        /** Au-delà de 720 ms de retard, on jette. */
        private const val MAX_PACKETS = 12
    }
}
