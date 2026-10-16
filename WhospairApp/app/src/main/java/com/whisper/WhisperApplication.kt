package com.whisper

import android.app.Application
import com.whisper.reach.Reachability
import com.whisper.security.SessionLifecycle
import com.whisper.util.CrashReport
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Point d'entrée Hilt. Sans @HiltAndroidApp déclarée dans le manifeste, la
 * première activité @AndroidEntryPoint plante au lancement.
 *
 * Rien ne démarre ici : la base est verrouillée tant que le PIN n'a pas été
 * saisi. C'est l'écran de verrouillage qui lance la session.
 */
@HiltAndroidApp
class WhisperApplication : Application() {

    /** Paresseux : le processus d'arrière-plan (:reach) ne doit rien construire du graphe. */
    @Inject lateinit var sessionLifecycle: dagger.Lazy<SessionLifecycle>

    override fun onCreate() {
        super.onCreate()
        CrashReport.install(this)
        if (Reachability.isReachProcess()) return
        // Le diffuseur n'a ni PIN ni coffre : il doit vivre écran éteint.
        if (BuildConfig.IS_DIFFUSER) return
        sessionLifecycle.get().attach()
    }
}
