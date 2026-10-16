package com.whisper.update

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.whisper.services.StorageService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * L'horloge interne de Whospair (1.3.8) : le temps écoulé compté par l'app,
 * pas la date du téléphone. Reculer la date n'y change rien.
 *
 * Même démarrage du téléphone : l'écart de SystemClock.elapsedRealtime, qui
 * avance app fermée et même tuée de force. Après un redémarrage : au moins le
 * temps depuis le démarrage, davantage si la date a avancé plus que ça.
 *
 * Sert aux « 5 jours de grâce » : une nouvelle version devient obligatoire
 * 120 h après qu'on l'a apprise, et une app à jour refuse les versions plus
 * anciennes 120 h après sa propre installation.
 */
@Singleton
class GraceClock @Inject constructor(
    @ApplicationContext private val context: Context,
    private val storage: StorageService
) {
    /** Millisecondes comptées depuis le tout premier appel ; jamais en arrière. */
    @Synchronized
    fun now(): Long {
        val elapsed = SystemClock.elapsedRealtime()
        val wall = System.currentTimeMillis()
        val boot = bootCount()
        val total = storage.getLong(KEY_TOTAL)
        val lastElapsed = storage.getLong(KEY_ELAPSED, -1L)
        val sameBoot = boot >= 0 && boot.toLong() == storage.getLong(KEY_BOOT, -1L)
        val delta = when {
            lastElapsed < 0 -> 0L
            sameBoot && elapsed >= lastElapsed -> elapsed - lastElapsed
            // BOOT_COUNT illisible : un elapsedRealtime qui recule dit le redémarrage.
            boot < 0 && elapsed >= lastElapsed -> elapsed - lastElapsed
            else -> maxOf(elapsed, wall - storage.getLong(KEY_WALL, wall))
        }
        val next = total + delta
        storage.saveLong(KEY_TOTAL, next)
        storage.saveLong(KEY_ELAPSED, elapsed)
        storage.saveLong(KEY_WALL, wall)
        storage.saveLong(KEY_BOOT, boot.toLong())
        return next
    }

    private fun bootCount(): Int =
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1) }.getOrDefault(-1)

    companion object {
        private const val KEY_TOTAL = "grace_clock_total"
        private const val KEY_ELAPSED = "grace_clock_elapsed"
        private const val KEY_WALL = "grace_clock_wall"
        private const val KEY_BOOT = "grace_clock_boot"
    }
}
