package com.whisper.reach

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import com.whisper.R
import com.whisper.ui.MainActivity

/**
 * Les seules notifications de Whisper, toutes émises app fermée et toutes
 * anonymes : ni nom, ni photo, ni contenu. Même l'écran verrouillé d'un
 * téléphone posé sur la table ne dit pas QUI écrit.
 *
 * Toucher ouvre l'app, donc l'écran du PIN.
 */
object ReachNotifications {

    const val SERVICE_ID = 1301
    private const val ALERT_ID = 1302
    private const val CALL_ID = 1303

    const val CHANNEL_SERVICE = "reach_service"
    private const val CHANNEL_ALERT = "reach_alert"
    private const val CHANNEL_CALL = "reach_call"

    /** Sonnerie d'un appel : le contact abandonne au bout d'une minute. */
    private const val CALL_TIMEOUT_MS = 60_000L

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, context.getString(R.string.reach_channel_service), NotificationManager.IMPORTANCE_MIN)
                .apply { setShowBadge(false) }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, context.getString(R.string.reach_channel_alert), NotificationManager.IMPORTANCE_HIGH)
                .apply { setShowBadge(false) }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_CALL, context.getString(R.string.reach_channel_call), NotificationManager.IMPORTANCE_HIGH).apply {
                setShowBadge(false)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 600, 900)
            }
        )
    }

    /** Obligatoire pour un service de premier plan. Discrète, sans son. */
    fun service(context: Context): Notification =
        Notification.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.reach_service_title))
            .setContentText(context.getString(R.string.reach_service_text))
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .build()

    /** Message ou signe : une seule notification, remplacée à chaque fois. */
    fun alert(context: Context) {
        notify(
            context, ALERT_ID,
            Notification.Builder(context, CHANNEL_ALERT)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.reach_alert_title))
                .setContentIntent(openApp(context))
                .setAutoCancel(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .build()
        )
    }

    /**
     * Appel d'un contact confirmé : plein écran si le téléphone le permet,
     * sonnerie en boucle. Décrocher se fait après le PIN, dans l'app — le
     * contact continue d'appeler pendant ce temps.
     */
    fun call(context: Context) {
        val open = openApp(context)
        notify(
            context, CALL_ID,
            Notification.Builder(context, CHANNEL_CALL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.reach_call_title))
                .setContentText(context.getString(R.string.reach_call_text))
                .setCategory(Notification.CATEGORY_CALL)
                .setContentIntent(open)
                .setFullScreenIntent(open, true)
                .setAutoCancel(true)
                .setTimeoutAfter(CALL_TIMEOUT_MS)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .build()
                .apply { flags = flags or Notification.FLAG_INSISTENT }
        )
    }

    fun cancelCall(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(CALL_ID)
    }

    /** Au déverrouillage : l'app ouverte a pris le relais. */
    fun cancelAlerts(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(ALERT_ID)
        manager.cancel(CALL_ID)
    }

    private fun notify(context: Context, id: Int, notification: Notification) {
        // Notifications refusées (Android 13+) : on n'insiste pas.
        runCatching { context.getSystemService(NotificationManager::class.java).notify(id, notification) }
    }

    private fun openApp(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
}
