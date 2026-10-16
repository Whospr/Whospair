package com.whisper.theme

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.whisper.R

/**
 * Les fonds de conversation (1.2.5) : un seul choix, dans Réglages, le même
 * pour toutes les discussions. Chacun a son jour et sa nuit ; aucun n'est
 * une image — un dégradé doux et, sauf « Uni », le motif de ChatWallpaper.
 * Les écrans principaux reprennent ses couleurs, sans le motif : un seul
 * ton pour toute l'app.
 */
enum class ChatBackground(
    @StringRes val label: Int,
    private val nightTop: Long, private val nightBottom: Long,
    private val dayTop: Long, private val dayBottom: Long,
    val patterned: Boolean = true
) {
    ENCRE(R.string.bg_ink, 0xFF15171C, 0xFF15171C, 0xFFF1EFEA, 0xFFF1EFEA),
    SAUGE(R.string.bg_sage, 0xFF1D2A23, 0xFF131915, 0xFFDDEBDF, 0xFFC9DDCD),
    ARDOISE(R.string.bg_slate, 0xFF1C2232, 0xFF12151D, 0xFFDCE3EE, 0xFFC8D2E2),
    PRUNE(R.string.bg_plum, 0xFF261D2C, 0xFF15121A, 0xFFE9DFEC, 0xFFD8CADF),
    SABLE(R.string.bg_sand, 0xFF2A251B, 0xFF16140F, 0xFFF2E8D4, 0xFFE3D4B7),
    UNI(R.string.bg_plain, 0xFF15171C, 0xFF15171C, 0xFFF1EFEA, 0xFFF1EFEA, patterned = false);

    val top: Color get() = Color(if (Appearance.isDark) nightTop else dayTop)
    val bottom: Color get() = Color(if (Appearance.isDark) nightBottom else dayBottom)

    companion object {
        const val KEY = "chat_background"
        /** Le choix en cours, gardé chiffré (StorageService). */
        var current by mutableStateOf(ENCRE)
        fun load(stored: String) {
            current = entries.firstOrNull { it.name == stored } ?: ENCRE
        }
    }
}

