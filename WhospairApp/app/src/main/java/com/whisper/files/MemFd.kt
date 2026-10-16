package com.whisper.files

import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.io.FileOutputStream
import kotlin.concurrent.thread

/**
 * Des octets déchiffrés, offerts comme un fichier… qui n'existe qu'en
 * mémoire vive (phase 16). Rien n'est écrit sur le disque.
 *
 * Android 11+ : memfd, relisible et positionnable (un lecteur PDF ou vidéo
 * saute d'un endroit à l'autre). Le descripteur remis à une autre app lui
 * appartient : il survit même quand Whisper se ferme en arrière-plan.
 * Android 10 : un tube, lisible une fois du début à la fin.
 */
object MemFd {

    /** Positionnable ; null avant Android 11. */
    fun seekable(data: ByteArray): ParcelFileDescriptor? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val fd = Os.memfd_create("whisper", 0)
        return try {
            var written = 0
            while (written < data.size) written += Os.write(fd, data, written, data.size - written)
            Os.lseek(fd, 0, OsConstants.SEEK_SET)
            ParcelFileDescriptor.dup(fd)
        } finally {
            Os.close(fd)
        }
    }

    /** Positionnable si possible, sinon un tube rempli par un fil à part. */
    fun readable(data: ByteArray): ParcelFileDescriptor {
        seekable(data)?.let { return it }
        val (read, write) = ParcelFileDescriptor.createPipe()
        val copy = data.copyOf()
        thread(name = "whisper-pipe") {
            runCatching { FileOutputStream(write.fileDescriptor).use { it.write(copy) } }
            copy.fill(0)
            runCatching { write.close() }
        }
        return read
    }
}
