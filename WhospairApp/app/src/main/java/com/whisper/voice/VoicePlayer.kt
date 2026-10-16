package com.whisper.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Lit un vocal produit par VoiceRecorder : décodeur Opus d'Android, puis
 * haut-parleur. Rien n'est écrit sur le disque, et la capture du son par
 * d'autres apps est refusée (allowAudioPlaybackCapture=false).
 *
 * Un seul vocal à la fois : en lancer un autre arrête le premier.
 */
class VoicePlayer(private val scope: CoroutineScope) {

    /** Clé du vocal en cours de lecture, null au repos. */
    private val _playing = MutableStateFlow<String?>(null)
    val playing: StateFlow<String?> = _playing.asStateFlow()

    /** Avancement de la lecture, de 0 à 1. */
    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private var job: Job? = null

    fun toggle(key: String, clip: ByteArray, durationMs: Int) {
        if (_playing.value == key) {
            stop()
            return
        }
        stop()
        _playing.value = key
        _progress.value = 0f
        job = scope.launch(Dispatchers.IO) {
            runCatching { play(clip, durationMs) }
            if (_playing.value == key) {
                _playing.value = null
                _progress.value = 0f
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _playing.value = null
        _progress.value = 0f
    }

    private suspend fun play(clip: ByteArray, durationMs: Int) {
        val input = DataInputStream(ByteArrayInputStream(clip))
        val header = ByteArray(input.readUnsignedShort()).also { input.readFully(it) }
        val packets = buildList {
            while (input.available() >= 2) {
                val size = input.readUnsignedShort()
                if (size == 0 || size > input.available()) break
                add(ByteArray(size).also { input.readFully(it) })
            }
        }
        if (packets.isEmpty()) return

        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        codec.configure(decoderFormat(header), null, null, 0)
        codec.start()

        var track: AudioTrack? = null
        val info = MediaCodec.BufferInfo()
        var next = 0
        var ptsUs = 0L
        var inputDone = false
        val totalUs = maxOf(durationMs, 1) * 1000L
        var idle = 0

        try {
            while (currentCoroutineContext().isActive) {
                if (!inputDone) {
                    val slot = codec.dequeueInputBuffer(10_000)
                    if (slot >= 0) {
                        if (next < packets.size) {
                            val packet = packets[next++]
                            codec.getInputBuffer(slot)!!.apply { clear(); put(packet) }
                            codec.queueInputBuffer(slot, 0, packet.size, ptsUs, 0)
                            ptsUs += FRAME_US
                        } else {
                            codec.queueInputBuffer(slot, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track?.release()
                    track = audioTrack(codec.outputFormat)
                    continue
                }
                if (index < 0) {
                    // Décodeur muet après la fin des paquets : on n'attend pas indéfiniment.
                    if (inputDone && ++idle > 200) break
                    continue
                }
                idle = 0
                val buffer = codec.getOutputBuffer(index)!!
                val pcm = ByteArray(info.size).also { buffer.position(info.offset); buffer.get(it) }
                codec.releaseOutputBuffer(index, false)
                val out = track ?: audioTrack(codec.outputFormat).also { track = it }
                if (pcm.isNotEmpty()) out.write(pcm, 0, pcm.size)
                pcm.fill(0)
                _progress.value = (info.presentationTimeUs.toFloat() / totalUs).coerceIn(0f, 1f)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            track?.let { runCatching { it.stop() }; it.release() }
        }
    }

    private fun audioTrack(format: MediaFormat): AudioTrack {
        val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val mask = if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
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
     * L'encodeur d'Android rend son en-tête sous l'une de deux formes : l'en-tête
     * Opus standard (« OpusHead »), ou un paquet balisé (« AOPUSHDR »…) qui
     * contient aussi le délai initial et la pré-lecture. Le décodeur veut les
     * trois séparément : csd-0, csd-1 et csd-2.
     */
    private fun decoderFormat(header: ByteArray): MediaFormat {
        var head = header
        var delayNs = -1L
        var prerollNs = 80_000_000L
        if (String(header, 0, minOf(8, header.size), Charsets.US_ASCII) == "AOPUSHDR") {
            val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            while (b.remaining() >= 16) {
                val tag = ByteArray(8).also { b.get(it) }.toString(Charsets.US_ASCII)
                val len = b.long.toInt()
                if (len < 0 || len > b.remaining()) break
                val body = ByteArray(len).also { b.get(it) }
                when (tag) {
                    "AOPUSHDR" -> head = body
                    "AOPUSDLY" -> delayNs = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN).long
                    "AOPUSPRL" -> prerollNs = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN).long
                }
            }
        }
        if (delayNs < 0 && head.size >= 12) {
            // Pré-saut de l'en-tête Opus (octets 10-11), en échantillons à 48 kHz.
            val preSkip = (head[10].toInt() and 0xFF) or ((head[11].toInt() and 0xFF) shl 8)
            delayNs = preSkip * 1_000_000_000L / 48_000
        }
        return MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, VoiceRecorder.RATE, 1).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(head))
            setByteBuffer("csd-1", nativeLong(maxOf(delayNs, 0)))
            setByteBuffer("csd-2", nativeLong(prerollNs))
        }
    }

    private fun nativeLong(value: Long): ByteBuffer =
        ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(value).apply { flip() }

    companion object {
        /** L'encodeur d'Android produit des trames de 20 ms. */
        private const val FRAME_US = 20_000L
    }
}
