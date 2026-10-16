package com.whisper.reach

import android.content.Context
import java.io.File

/**
 * Les messages reçus app fermée, en attente du PIN : un fichier par
 * message, scellé (InboxSeal). Le nom n'est qu'un numéro d'ordre (le
 * compteur anti-rejeu exige l'ordre d'arrivée) ; aucune heure, ni dans le
 * nom ni dans la date du fichier.
 *
 * Écrit par le processus d'arrière-plan, vidé par l'app au déverrouillage
 * — jamais les deux à la fois : l'app tue l'arrière-plan avant de vider.
 */
class Inbox(context: Context) {

    private val dir = dirOf(context)
    /** Numéro suivant, repris du plus grand déjà rangé (l'arrière-plan peut redémarrer). */
    private var next = -1L

    /** Octets et fichiers rangés, relus une fois puis tenus à jour (une liste de 20 000 fichiers par message coûterait cher). */
    private var used = -1L
    private var count = 0

    /**
     * Plafonds : 1 Go, jamais plus de 20 % de ce que la boîte et l'espace
     * libre font ensemble (phase 16). Large exprès : le dernier fichier d'une
     * source, envoyé app fermée, doit pouvoir passer. Tout est effacé dès
     * que l'app a vidé la boîte.
     */
    @Synchronized
    fun hasRoom(incoming: Int = 0): Boolean {
        if (used < 0) {
            val files = pending()
            used = files.sumOf { it.length() }
            count = files.size
        }
        val free = runCatching { dir.parentFile?.usableSpace ?: 0L }.getOrDefault(0L)
        val cap = minOf(MAX_BYTES, (used + free) / 5)
        return count < MAX_FILES && used + incoming <= cap
    }

    /** Écriture complète ou rien : le fichier temporaire est renommé à la fin. */
    @Synchronized
    fun put(sealed: ByteArray): Boolean = runCatching {
        dir.mkdirs()
        if (next < 0) next = pending().lastOrNull()?.name?.removeSuffix(SUFFIX)?.toLongOrNull()?.plus(1) ?: 0
        val name = "%012d".format(next++)
        val tmp = File(dir, "$name.tmp")
        tmp.writeBytes(sealed)
        val file = File(dir, "$name$SUFFIX")
        tmp.renameTo(file).also {
            if (it) {
                file.setLastModified(0)
                if (used >= 0) { used += sealed.size; count++ }
            }
        }
    }.getOrDefault(false)

    /** Écritures interrompues (processus tué en plein milieu) : jamais complètes. */
    fun discardPartial() {
        dir.listFiles { f -> f.name.endsWith(".tmp") }?.forEach { it.delete() }
    }

    /** Dans l'ordre d'arrivée. */
    fun pending(): List<File> =
        dir.listFiles { f -> f.name.endsWith(SUFFIX) }?.sortedBy { it.name }.orEmpty()

    companion object {
        private const val DIR = "inbox"
        private const val SUFFIX = ".box"
        /** 1 Go de morceaux de 48 Ko, avec marge pour les messages. */
        private const val MAX_FILES = 40_000
        private const val MAX_BYTES = 1024L * 1024 * 1024

        private fun dirOf(context: Context) = File(context.noBackupFilesDir, DIR)

        /** Effacement d'urgence, ou identité remplacée. */
        fun deleteAll(context: Context) {
            dirOf(context).deleteRecursively()
        }
    }
}
