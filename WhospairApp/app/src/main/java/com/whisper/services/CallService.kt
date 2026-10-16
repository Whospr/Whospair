package com.whisper.services

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Routage audio Android pendant un appel : focus, mode communication,
 * haut-parleur. Piloté par CallManager (voix sur Tor, phase 11d).
 */
@Singleton
class CallService @Inject constructor(
    @ApplicationContext context: Context
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** Gardée pour relâcher le focus avec la MÊME requête. */
    private var focusRequest: AudioFocusRequest? = null
    private var previousMode = AudioManager.MODE_NORMAL
    private var active = false

    /** Met en pause la musique du téléphone et bascule en mode communication. */
    fun start() {
        if (active) return
        active = true
        previousMode = audioManager.mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .build()
        focusRequest = request
        audioManager.requestAudioFocus(request)
    }

    fun stop() {
        if (!active) return
        active = false
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = false
        audioManager.mode = previousMode
    }

    fun setSpeaker(on: Boolean) {
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = on
    }
}
