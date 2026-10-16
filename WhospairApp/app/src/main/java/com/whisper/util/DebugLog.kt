package com.whisper.util

import android.util.Log
import com.whisper.BuildConfig

/**
 * Journaux de mise au point : muets en release, même sans R8 (qui les
 * retirait par proguard-rules.pro). Message construit seulement en debug.
 */
object DebugLog {
    fun d(tag: String, message: () -> String) {
        if (BuildConfig.DEBUG) Log.d(tag, message())
    }

    /**
     * Avertissements et erreurs : muets en release aussi (1.3.5). Sans R8,
     * les Log.w/Log.e laissaient dans logcat une trace d'activité (rejets,
     * appels, rangements) lisible par adb.
     */
    fun w(tag: String, message: String, error: Throwable? = null) {
        if (BuildConfig.DEBUG) Log.w(tag, message, error)
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        if (BuildConfig.DEBUG) Log.e(tag, message, error)
    }
}
