package com.whisper.files

import android.content.ClipData
import android.content.Context
import android.content.Intent
import com.whisper.R

/**
 * « Partager Whospair » (1.3.2) : hors Play Store, l'app s'envoie elle-même.
 * Le menu de partage d'Android reçoit l'APK installé (signé, donc les mises à
 * jour par onion marchent chez celui qui l'installe).
 */
object ShareApp {
    fun launch(context: Context) {
        val uri = TempFileProvider.ownApk(context)
        val send = Intent(Intent.ACTION_SEND)
            .setType(TempFileProvider.APK_MIME)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(null, uri)
        // Pas de délai de grâce (1.3.4) : l'APK installé n'a pas besoin du coffre,
        // le fournisseur le sert même app verrouillée. Avant, l'app restait
        // ouverte 2 min pendant qu'on était dans l'autre app.
        context.startActivity(
            Intent.createChooser(send, context.getString(R.string.share_app))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }
}
