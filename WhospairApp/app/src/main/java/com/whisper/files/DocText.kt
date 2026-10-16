package com.whisper.files

import android.util.Xml
import android.webkit.MimeTypeMap
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * Lire dans Whisper ce qui ne s'affiche pas tout seul (phase 16) : le texte
 * d'un .docx ou d'un .odt, le contenu d'un .zip. Tout en mémoire.
 *
 * Garde-fous contre les archives piégées : au plus 2 000 entrées, 200 Mo
 * décompressés en tout par parcours, aucune entrée au-delà de 25 Mo.
 */
object DocText {

    private const val MAX_ENTRIES = 2_000
    /** Décompressé en tout, par parcours : au-delà, l'archive est tenue pour piégée. */
    private const val MAX_TOTAL = 200L * 1024 * 1024

    class ZipItem(val name: String, val size: Long)

    fun isDocument(name: String): Boolean = extension(name) in setOf("docx", "odt")

    fun isZip(name: String, mime: String): Boolean = mime == "application/zip" || extension(name) == "zip"

    /** Le texte d'un .docx ou .odt, paragraphe par paragraphe ; null si illisible. */
    fun extract(data: ByteArray, name: String): String? = runCatching {
        val odt = extension(name) == "odt"
        val xml = entry(data, if (odt) "content.xml" else "word/document.xml") ?: return null
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(ByteArrayInputStream(xml), "UTF-8")
        }
        val out = StringBuilder()
        var inText = false
        while (true) {
            when (parser.next()) {
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.START_TAG -> when (parser.name) {
                    "w:t" -> inText = true
                    "w:tab", "text:tab" -> out.append('\t')
                    "w:br", "text:line-break" -> out.append('\n')
                    "text:s" -> out.append(' ')
                    "text:p", "text:h" -> inText = true
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "w:t" -> inText = false
                    "w:p", "text:p", "text:h" -> { out.append('\n'); if (odt) inText = false }
                }
                XmlPullParser.TEXT -> if (inText) out.append(parser.text)
            }
        }
        xml.fill(0)
        out.toString().trim()
    }.getOrNull()

    /**
     * Parcourt l'archive en décompressant nous-mêmes chaque entrée, pour
     * compter : sauter une entrée la décompresse aussi, et une archive
     * piégée (25 Mo qui en font des Go) figeait l'app. Au-delà de
     * [MAX_TOTAL] octets décompressés en tout, on s'arrête.
     *
     * [keep] : l'entrée dont on veut les octets (null : aucune, on compte).
     */
    private fun walk(data: ByteArray, keep: String?, onEntry: (String, Long) -> Unit): ByteArray? {
        var total = 0L
        var kept: ByteArray? = null
        ZipInputStream(ByteArrayInputStream(data)).use { zip ->
            val buffer = ByteArray(64 * 1024)
            var seen = 0
            while (true) {
                val e = zip.nextEntry ?: break
                if (++seen > MAX_ENTRIES) break
                val wanted = !e.isDirectory && e.name == keep
                val out = if (wanted) ByteArrayOutputStream() else null
                var size = 0L
                while (true) {
                    val n = zip.read(buffer)
                    if (n < 0) break
                    size += n
                    total += n
                    if (total > MAX_TOTAL || (wanted && size > FilePayload.MAX_BYTES)) return kept
                    out?.write(buffer, 0, n)
                }
                if (!e.isDirectory) onEntry(e.name, size)
                if (out != null) {
                    kept = out.toByteArray()
                    break
                }
            }
        }
        return kept
    }

    /** Les entrées d'une archive (les dossiers exclus), avec leur vraie taille. À appeler hors du fil de l'écran. */
    fun list(data: ByteArray): List<ZipItem>? = runCatching {
        val items = mutableListOf<ZipItem>()
        walk(data, keep = null) { name, size -> items += ZipItem(name, size) }
        items
    }.getOrNull()

    /** Une entrée décompressée ; null si absente, trop grosse, ou au-delà du plafond total. */
    fun entry(data: ByteArray, name: String): ByteArray? =
        runCatching { walk(data, keep = name) { _, _ -> } }.getOrNull()

    /** Type deviné d'après l'extension (entrée d'archive). */
    fun mimeOf(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension(name)) ?: "application/octet-stream"

    private fun extension(name: String) = name.substringAfterLast('.', "").lowercase()
}
