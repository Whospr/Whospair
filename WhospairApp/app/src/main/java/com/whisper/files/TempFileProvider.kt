package com.whisper.files

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.whisper.security.PinCrypto
import org.bouncycastle.util.encoders.Hex
import java.util.concurrent.ConcurrentHashMap

/**
 * « Ouvrir avec… » et « Partager » (phase 16) : un fichier déchiffré, prêté
 * à une autre app par une adresse content:// à usage court.
 *
 * Les octets restent en mémoire ; l'autre app reçoit un descripteur en
 * mémoire vive (MemFd), jamais un chemin sur le disque. Adresse au jeton
 * aléatoire, non exportée : seule l'app à qui Android a accordé la lecture
 * peut l'ouvrir. Au retour dans Whisper, tout est effacé et les accès
 * retirés (voir [revokeAll]).
 */
class TempFileProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? =
        if (isOwnApk(uri)) APK_MIME else entryOf(uri)?.mime

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val apk = if (isOwnApk(uri)) ownApkFile(context!!) else null
        val entry = if (apk == null) entryOf(uri) ?: return null else null
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(columns.map { c ->
                when (c) {
                    OpenableColumns.DISPLAY_NAME -> entry?.name ?: ownApkName()
                    OpenableColumns.SIZE -> entry?.data?.size?.toLong() ?: apk!!.length()
                    else -> null
                }
            })
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") throw SecurityException("Lecture seule")
        if (isOwnApk(uri)) return ParcelFileDescriptor.open(ownApkFile(context!!), ParcelFileDescriptor.MODE_READ_ONLY)
        val entry = entryOf(uri) ?: throw java.io.FileNotFoundException()
        return MemFd.readable(entry.data)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private fun entryOf(uri: Uri): Entry? = uri.pathSegments.firstOrNull()?.let { entries[it] }

    private fun isOwnApk(uri: Uri) = uri.pathSegments.firstOrNull() == APP_SEGMENT

    private class Entry(val data: ByteArray, val name: String, val mime: String)

    companion object {
        private val entries = ConcurrentHashMap<String, Entry>()

        const val APK_MIME = "application/vnd.android.package-archive"
        private const val APP_SEGMENT = "app"

        /**
         * « Partager Whospair » (1.3.2) : l'APK installé, prêté tel quel —
         * aucune copie, rien à effacer ensuite. Rien de secret dedans : il
         * n'est pas retiré au retour, un envoi long (Bluetooth) va au bout.
         */
        fun ownApk(context: Context): Uri =
            Uri.Builder().scheme("content").authority(authority(context))
                .appendPath(APP_SEGMENT).appendPath(ownApkName()).build()

        private fun ownApkFile(context: Context) = java.io.File(context.applicationInfo.sourceDir)

        private fun ownApkName() = "Whospair-${com.whisper.BuildConfig.VERSION_NAME}.apk"

        private fun authority(context: Context) = context.packageName + ".tempfiles"

        /** Prête une copie des octets ; l'adresse ne vaut que jusqu'à [revokeAll]. */
        fun offer(context: Context, data: ByteArray, name: String, mime: String): Uri {
            val token = Hex.toHexString(PinCrypto.randomBytes(16))
            val safe = safeName(name)
            entries[token] = Entry(data.copyOf(), safe, mime)
            return Uri.Builder().scheme("content").authority(authority(context))
                .appendPath(token).appendPath(safe).build()
        }

        /**
         * Le nom vient d'un contact distant : jamais de chemin. Une app qui
         * construit un chemin avec DISPLAY_NAME (« Dirty Stream ») écrirait
         * sinon « ../../x » hors de son dossier.
         */
        private fun safeName(name: String): String =
            name.substringAfterLast('/').substringAfterLast('\\')
                .filterNot { it.isISOControl() }
                .trim().trimStart('.')
                .take(120)
                .ifBlank { "fichier" }

        /** Retour dans Whisper : copies effacées, accès retirés. */
        fun revokeAll(context: Context) {
            if (entries.isEmpty()) return
            // Jeton par jeton : le prêt de l'APK (« app ») garde son accès.
            for (token in entries.keys) {
                entries.remove(token)?.data?.fill(0)
                runCatching {
                    context.revokeUriPermission(
                        Uri.parse("content://" + authority(context) + "/" + token),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
        }
    }
}
