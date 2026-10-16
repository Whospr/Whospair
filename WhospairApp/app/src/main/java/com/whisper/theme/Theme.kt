package com.whisper.theme

import android.app.Activity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/**
 * « Encre de nuit » : une app pour dire des choses sensibles à voix basse.
 *
 * Sombre mais pas noire — un graphite bleuté —, un seul accent (la sauge,
 * pour « joignable » et les actions), une brique sourde pour ce qui détruit.
 * Rien de vif : l'écran ne doit pas attirer l'œil d'un voisin.
 *
 * 1.2.5 : un jour « papier » s'ajoute (Réglages › Apparence : automatique,
 * jour ou nuit). Les noms restent ceux de la nuit — `Night` est le fond,
 * `Chalk` le texte — et chaque jeton change de valeur avec le mode. Les
 * lectures passent par un état Compose : changer de mode redessine tout.
 */
enum class ThemeMode { AUTO, DAY, NIGHT }

object Appearance {
    /** Choix de l'utilisateur, gardé chiffré (StorageService). */
    var mode by mutableStateOf(ThemeMode.AUTO)
    /** Le téléphone est-il en mode sombre ? Mis à jour par l'activité. */
    var systemDark by mutableStateOf(true)

    val isDark: Boolean
        get() = when (mode) {
            ThemeMode.AUTO -> systemDark
            ThemeMode.DAY -> false
            ThemeMode.NIGHT -> true
        }

    const val KEY = "theme_mode"

    fun load(stored: String) {
        mode = ThemeMode.entries.firstOrNull { it.name == stored } ?: ThemeMode.AUTO
    }
}

@Immutable
class InkPalette(
    val night: Color, val watermark: Color, val watermarkStrong: Color,
    val bar: Color, val slate: Color, val relief: Color, val raised: Color,
    val line: Color, val lineStrong: Color,
    val chalk: Color, val chalkSoft: Color, val ash: Color,
    val sage: Color, val sageLight: Color, val sageDim: Color, val moss: Color,
    val brick: Color, val brickDim: Color, val ochre: Color, val ochreDim: Color,
    val card: Color, val glass: Color, val glassEdge: Color, val glow: Color,
    val bubbleOwnTop: Color, val bubbleOwn: Color,
    val bubbleOtherTop: Color, val bubbleOther: Color,
    val shadow: Color
)

private val NightInk = InkPalette(
    night = Color(0xFF15171C),
    watermark = Color(0xFF1E2127), watermarkStrong = Color(0xFF23262D),
    bar = Color(0xFF1A1C22), slate = Color(0xFF1D2027), relief = Color(0xFF272B34),
    raised = Color(0xFF23262E), line = Color(0xFF2E323C), lineStrong = Color(0xFF3A3F4A),
    chalk = Color(0xFFE4E1DA), chalkSoft = Color(0xFFB9B7B1), ash = Color(0xFF8A8E97),
    sage = Color(0xFF7FA88A), sageLight = Color(0xFFA9CBB2), sageDim = Color(0xFF26352C),
    moss = Color(0xFF1F2A26),
    brick = Color(0xFFD98A90), brickDim = Color(0xFF5B3438),
    ochre = Color(0xFFD2B27A), ochreDim = Color(0xFF5A4D35),
    card = Color(0xFF1C1F25), glass = Color(0xEB262A33), glassEdge = Color(0x12FFFFFF),
    glow = Color(0xFF20252E),
    bubbleOwnTop = Color(0xFF353B49), bubbleOwn = Color(0xFF292D37),
    bubbleOtherTop = Color(0xFF24322C), bubbleOther = Color(0xFF1F2A26),
    shadow = Color(0xFF000000)
)

private val DayInk = InkPalette(
    night = Color(0xFFF1EFEA),
    watermark = Color(0xFFE2DED5), watermarkStrong = Color(0xFFD8D3C9),
    bar = Color(0xFFF7F6F2), slate = Color(0xFFE6E3DC), relief = Color(0xFFE0DCD4),
    raised = Color(0xFFFFFFFF), line = Color(0xFFDDD9D1), lineStrong = Color(0xFFC6C1B7),
    chalk = Color(0xFF1C1E23), chalkSoft = Color(0xFF474B54), ash = Color(0xFF666A73),
    sage = Color(0xFF4E7D5E), sageLight = Color(0xFF3E6A4D), sageDim = Color(0xFFD5E5D9),
    moss = Color(0xFFDCEADF),
    brick = Color(0xFFA8474F), brickDim = Color(0xFFF3DADC),
    ochre = Color(0xFF8A6A2E), ochreDim = Color(0xFFF1E4C9),
    card = Color(0xFFFFFFFF), glass = Color(0xF2FFFFFF), glassEdge = Color(0x0F000000),
    glow = Color(0xFFFFFFFF),
    bubbleOwnTop = Color(0xFFFFFFFF), bubbleOwn = Color(0xFFF8F7F3),
    bubbleOtherTop = Color(0xFFE3EEE6), bubbleOther = Color(0xFFD6E6DA),
    shadow = Color(0x55403C30)
)

object Ink {
    private val p: InkPalette get() = if (Appearance.isDark) NightInk else DayInk

    /** Fond. */
    val Night get() = p.night
    /** Motif du fond de conversation : on le devine, on ne le lit pas. */
    val Watermark get() = p.watermark
    val WatermarkStrong get() = p.watermarkStrong
    /** Barres du haut et du bas. */
    val Bar get() = p.bar
    /** Champs de saisie, rangée sélectionnée. */
    val Slate get() = p.slate
    /** Avatars neutres, boutons secondaires. */
    val Relief get() = p.relief
    /** Dialogues. */
    val Raised get() = p.raised
    /** Bordures et séparateurs. */
    val Line get() = p.line
    val LineStrong get() = p.lineStrong

    /** Texte. */
    val Chalk get() = p.chalk
    /** Texte secondaire lisible. */
    val ChalkSoft get() = p.chalkSoft
    /** Texte discret : heures, aides. */
    val Ash get() = p.ash

    /** Le seul accent. */
    val Sage get() = p.sage
    val SageLight get() = p.sageLight
    /** Fond de l'onglet actif. */
    val SageDim get() = p.sageDim
    /** Bulles reçues. */
    val Moss get() = p.moss

    /** Échec, blocage, suppression. */
    val Brick get() = p.brick
    val BrickDim get() = p.brickDim
    /** Avertissement : contact non confirmé, Tor en connexion. */
    val Ochre get() = p.ochre
    val OchreDim get() = p.ochreDim

    /** Cartes des listes groupées (réglages, fiche contact). */
    val Card get() = p.card
    /** Capsules qui flottent au-dessus du contenu : onglets, en-tête, saisie. */
    val Glass get() = p.glass
    val GlassEdge get() = p.glassEdge
    /** Lueur douce en haut des écrans. */
    val Glow get() = p.glow
    /** Bulles : un léger dégradé, du haut vers le bas. */
    val BubbleOwnTop get() = p.bubbleOwnTop
    val BubbleOwn get() = p.bubbleOwn
    val BubbleOtherTop get() = p.bubbleOtherTop
    val BubbleOther get() = p.bubbleOther
    /** Ombre portée des éléments flottants. */
    val Shadow get() = p.shadow

    /** Icône posée sur une tuile de couleur : toujours sombre. */
    val OnTile = Color(0xFF15171C)

    /**
     * Tuiles des réglages et teintes d'avatar : des couleurs sourdes, les
     * mêmes le jour et la nuit. Chaque paire va du haut vers le bas.
     */
    val TileSage = Color(0xFF93BB9D) to Color(0xFF6E977A)
    val TileSlate = Color(0xFFA6B7D8) to Color(0xFF7D90B6)
    val TileOchre = Color(0xFFE0C592) to Color(0xFFBC9B62)
    val TileTeal = Color(0xFF8EC0BC) to Color(0xFF62948F)
    val TileMauve = Color(0xFFB8A9D4) to Color(0xFF8E7FAE)
    val TileGrey = Color(0xFFC9CDD6) to Color(0xFF9199A8)
    val TileBrick = Color(0xFFE3A1A6) to Color(0xFFB96C73)
}

private fun schemeOf(dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = Ink.Sage,
        onPrimary = Ink.Night,
        primaryContainer = Ink.SageDim,
        onPrimaryContainer = Ink.SageLight,
        secondary = Ink.Sage,
        onSecondary = Ink.Night,
        secondaryContainer = Ink.SageDim,
        onSecondaryContainer = Ink.SageLight,
        tertiary = Ink.Ochre,
        onTertiary = Ink.Night,
        background = Ink.Night,
        onBackground = Ink.Chalk,
        surface = Ink.Night,
        onSurface = Ink.Chalk,
        surfaceVariant = Ink.Slate,
        onSurfaceVariant = Ink.ChalkSoft,
        surfaceTint = Color.Transparent,
        surfaceContainerLowest = Ink.Night,
        surfaceContainerLow = Ink.Bar,
        surfaceContainer = Ink.Slate,
        surfaceContainerHigh = Ink.Raised,
        surfaceContainerHighest = Ink.Relief,
        inverseSurface = Ink.Chalk,
        inverseOnSurface = Ink.Night,
        outline = Ink.LineStrong,
        outlineVariant = Ink.Line,
        error = Ink.Brick,
        onError = Ink.Night,
        errorContainer = Ink.BrickDim,
        onErrorContainer = Ink.Chalk,
        scrim = Color(0xFF08090C)
    )
}

private val InkShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/**
 * Thème Whisper - Material Design 3, jour ou nuit selon Réglages › Apparence.
 * Les barres système prennent la couleur du fond, icônes claires ou sombres.
 */
@Composable
fun WhisperTheme(content: @Composable () -> Unit) {
    val dark = Appearance.isDark
    val scheme = remember(dark) { schemeOf(dark) }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            // Les barres prennent les couleurs du fond choisi (haut et bas).
            val bg = ChatBackground.current
            window.statusBarColor = bg.top.toArgb()
            window.navigationBarColor = bg.bottom.toArgb()
            window.decorView.setBackgroundColor(Ink.Night.toArgb())
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = WhisperTypography,
        shapes = InkShapes,
        content = content
    )
}
