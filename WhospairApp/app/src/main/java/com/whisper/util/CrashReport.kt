package com.whisper.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ContentValues
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rapport de plantage — VERSION DEBUG UNIQUEMENT.
 *
 * Écrit dans Download/Whisper/ la cause des dernières fermetures : exception
 * Java, plantage natif (Tor, SQLCipher), ou arrêt volontaire du processus.
 * Sert à diagnostiquer sans ordinateur ; ne tourne jamais en release, où
 * un fichier en clair dans le stockage partagé serait une fuite.
 */
object CrashReport {

    private const val JAVA_CRASH_FILE = "last_java_crash.txt"
    const val TOR_LOG_FILE = "tor-debug.log"
    private const val LAST_REPORTED_FILE = "last_crash_reported.txt"

    private val CRASH_REASONS = listOf(
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_ANR
    )

    fun isEnabled(context: Context): Boolean =
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    fun install(context: Context) {
        if (!isEnabled(context)) return
        val app = context.applicationContext

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }
                File(app.filesDir, JAVA_CRASH_FILE).writeText("Fil : ${thread.name}\n$trace")
            }
            previous?.uncaughtException(thread, error)
        }

        Thread { runCatching { export(app) } }.start()
    }

    /**
     * N'écrit un rapport qu'après un VRAI plantage (exception, plantage
     * natif, app figée), et une seule fois par plantage. Les fermetures
     * normales — l'app tuée en arrière-plan, exprès — ne laissent rien :
     * un fichier par lancement dans le stockage partagé, c'était l'heure de
     * chaque utilisation, lisible par toute app ayant accès aux fichiers.
     */
    private fun export(context: Context) {
        val javaCrash = File(context.filesDir, JAVA_CRASH_FILE)
        val torLog = File(context.filesDir, TOR_LOG_FILE)
        val marker = File(context.filesDir, LAST_REPORTED_FILE)
        val lastReported = marker.takeIf { it.exists() }?.readText()?.toLongOrNull() ?: 0L

        val crash = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(context.packageName, 0, 5)
                .firstOrNull { it.reason in CRASH_REASONS && it.timestamp > lastReported }
        } else null

        if (crash == null && !javaCrash.exists()) {
            // Rien à signaler : le journal de Tor de la session précédente part aussi.
            torLog.delete()
            return
        }

        val report = StringBuilder()
        if (crash != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            report.appendLine("=== Plantage : ${date(crash.timestamp)}")
            report.appendLine("Raison : ${reason(crash.reason)} (${crash.reason}), statut ${crash.status}")
            report.appendLine("Description : ${crash.description}")
            if (crash.reason != ApplicationExitInfo.REASON_CRASH) {
                crash.traceInputStream?.use { save(context, "whospair-trace.bin", it.readBytes()) }
            }
            marker.writeText(crash.timestamp.toString())
        }

        javaCrash.takeIf { it.exists() }?.let {
            report.appendLine("\n=== Dernière exception Java\n${it.readText()}")
            it.delete()
        }

        torLog.takeIf { it.exists() }?.let {
            save(context, "whospair-tor.txt", it.readBytes())
            it.delete()
        }

        save(context, "whospair-crash.txt", report.toString().toByteArray())
    }

    private fun save(context: Context, name: String, bytes: ByteArray) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Whospair")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
    }

    private fun date(ms: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(ms))

    private fun reason(code: Int) = when (code) {
        ApplicationExitInfo.REASON_CRASH -> "exception Java"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "plantage natif"
        ApplicationExitInfo.REASON_EXIT_SELF -> "arrêt par l'app elle-même (exit)"
        ApplicationExitInfo.REASON_SIGNALED -> "tuée par un signal (killProcess ?)"
        ApplicationExitInfo.REASON_ANR -> "ANR (app figée)"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "mémoire insuffisante"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "fermée par l'utilisateur"
        ApplicationExitInfo.REASON_OTHER -> "autre"
        else -> "code $code"
    }
}
