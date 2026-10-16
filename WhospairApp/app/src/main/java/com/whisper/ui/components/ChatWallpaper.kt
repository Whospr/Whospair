package com.whisper.ui.components

import androidx.compose.foundation.Canvas
import com.whisper.ui.icons.Icons
import com.whisper.ui.icons.outlined.ChatBubbleOutline
import com.whisper.ui.icons.outlined.CloudOff
import com.whisper.ui.icons.outlined.HourglassEmpty
import com.whisper.ui.icons.outlined.Key
import com.whisper.ui.icons.outlined.Lock
import com.whisper.ui.icons.outlined.VisibilityOff
import com.whisper.ui.icons.rounded.GraphicEq
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import com.whisper.R
import com.whisper.theme.Appearance
import com.whisper.theme.ChatBackground
import kotlin.math.hypot

/**
 * Le fond de la conversation (phase 15) : un motif discret, à peine plus
 * clair que le fond, qui ne gêne jamais la lecture des bulles.
 *
 * Tout est dessiné ici, en traits : le logo de Whisper redessiné sans ses
 * couleurs, semé petit, retourné, pivoté ; autour, ce que l'app représente —
 * cadenas, clé, oignon (Tor), bulle, onde de voix, sablier (messages qui
 * s'effacent), œil barré, nuage barré (aucun serveur). Un seul logo au
 * centre, modeste, dans une clairière. Aucune image, aucune dépendance.
 */
@Composable
fun ChatWallpaper(modifier: Modifier = Modifier, background: ChatBackground = ChatBackground.current) {
    val icons = mapOf(
        Motif.LOCK to rememberVectorPainter(Icons.Outlined.Lock),
        Motif.KEY to rememberVectorPainter(Icons.Outlined.Key),
        Motif.BUBBLE to rememberVectorPainter(Icons.Outlined.ChatBubbleOutline),
        Motif.HOURGLASS to rememberVectorPainter(Icons.Outlined.HourglassEmpty),
        Motif.EYE_OFF to rememberVectorPainter(Icons.Outlined.VisibilityOff),
        Motif.WAVE to rememberVectorPainter(Icons.Rounded.GraphicEq),
        Motif.CLOUD_OFF to rememberVectorPainter(Icons.Outlined.CloudOff)
    )
    val dark = Appearance.isDark
    val top = background.top
    val bottom = background.bottom
    // Le motif : un voile clair la nuit, sombre le jour, jamais une couleur.
    val motif = if (dark) Color.White.copy(alpha = 0.045f) else Color.Black.copy(alpha = 0.055f)
    val centre = if (dark) Color.White.copy(alpha = 0.07f) else Color.Black.copy(alpha = 0.08f)
    val ink = ColorFilter.tint(motif)

    Canvas(modifier) {
        drawRect(Brush.verticalGradient(listOf(top, bottom)))
        if (!background.patterned) return@Canvas

        val tileW = TILE_W.dp.toPx()
        val tileH = TILE_H.dp.toPx()
        var row = 0
        var y = -tileH / 3
        while (y < size.height) {
            // Une rangée sur deux décalée d'une demi-tuile : pas de grille visible.
            var x = if (row % 2 == 0) -tileW / 4 else -tileW * 3 / 4
            while (x < size.width) {
                for (item in TILE) {
                    val s = item.size.dp.toPx()
                    val cx = x + item.x * tileW
                    val cy = y + item.y * tileH
                    rotate(item.rotation, pivot = Offset(cx, cy)) {
                        scale(if (item.mirrored) -1f else 1f, 1f, pivot = Offset(cx, cy)) {
                            when (item.motif) {
                                Motif.LOGO -> drawLogo(Offset(cx, cy), s, motif)
                                Motif.ONION -> drawOnion(Offset(cx, cy), s, motif)
                                else -> icons[item.motif]?.let { painter ->
                                    translate(cx - s / 2, cy - s / 2) {
                                        with(painter) { draw(Size(s, s), colorFilter = ink) }
                                    }
                                }
                            }
                        }
                    }
                }
                x += tileW
            }
            y += tileH
            row++
        }

        // Le logo du milieu, dans une clairière : présent, jamais envahissant.
        val center = Offset(size.width / 2, size.height / 2)
        drawCircle(lerp(top, bottom, 0.5f), radius = CLEARING.dp.toPx(), center = center)
        drawLogo(center, CENTER_LOGO.dp.toPx(), centre)
    }
}

private enum class Motif { LOGO, LOCK, KEY, BUBBLE, ONION, HOURGLASS, EYE_OFF, WAVE, CLOUD_OFF }

/** Position dans la tuile (fractions), taille en dp, rotation en degrés. */
private class Placed(
    val motif: Motif,
    val x: Float,
    val y: Float,
    val size: Int,
    val rotation: Float,
    val mirrored: Boolean = false
)

// Tailles doublées le 29/09 (trop discret au premier essai), tuile aussi : même densité.
private val TILE = listOf(
    Placed(Motif.LOGO, 0.12f, 0.10f, 52, 18f),
    Placed(Motif.LOCK, 0.55f, 0.07f, 34, -12f),
    Placed(Motif.BUBBLE, 0.86f, 0.22f, 38, 8f),
    Placed(Motif.ONION, 0.30f, 0.35f, 38, -6f),
    Placed(Motif.HOURGLASS, 0.72f, 0.45f, 32, 22f),
    Placed(Motif.LOGO, 0.08f, 0.62f, 44, 180f),
    Placed(Motif.KEY, 0.46f, 0.63f, 36, 40f),
    Placed(Motif.WAVE, 0.86f, 0.72f, 38, 0f),
    Placed(Motif.EYE_OFF, 0.22f, 0.87f, 34, -18f),
    Placed(Motif.CLOUD_OFF, 0.62f, 0.89f, 36, 10f),
    Placed(Motif.LOGO, 0.93f, 0.97f, 40, -90f, mirrored = true)
)

private const val TILE_W = 400
private const val TILE_H = 480
/** Une fois et demie seulement : au centre, il doit rester modeste. */
private const val CENTER_LOGO = 126
private const val CLEARING = 96

/**
 * Le logo de Whisper en traits : six nœuds en zigzag reliés par des doubles
 * liens, un hexagone au cœur. Repère 44 × 96, centré sur [center], haut de [height].
 */
private fun DrawScope.drawLogo(center: Offset, height: Float, color: Color) {
    val k = height / 96f
    fun p(x: Float, y: Float) = Offset(center.x + (x - 22f) * k, center.y + (y - 48f) * k)
    val stroke = Stroke(width = 1.6f * k)

    val nodes = listOf(
        p(23.2f, 6.8f) to 5.5f, p(9.2f, 21.6f) to 6.5f, p(8f, 49.2f) to 6.5f,
        p(36f, 48f) to 6.5f, p(34f, 74.4f) to 6.5f, p(20.8f, 89.6f) to 5.5f
    )
    fun link(a: Int, b: Int, double: Boolean = true) {
        val (pa, ra) = nodes[a]
        val (pb, rb) = nodes[b]
        val dx = pb.x - pa.x
        val dy = pb.y - pa.y
        val len = hypot(dx, dy)
        val ux = dx / len
        val uy = dy / len
        val start = Offset(pa.x + ux * ra * k, pa.y + uy * ra * k)
        val end = Offset(pb.x - ux * rb * k, pb.y - uy * rb * k)
        val offsets = if (double) listOf(-1.7f, 1.7f) else listOf(0f)
        for (o in offsets) {
            val n = Offset(-uy * o * k, ux * o * k)
            drawLine(color, start + n, end + n, strokeWidth = 1.4f * k)
        }
    }
    link(0, 1); link(1, 3); link(2, 4); link(4, 5)
    link(2, 3, double = false)
    for ((c, r) in nodes) drawCircle(color, radius = r * k, center = c, style = stroke)

    // Hexagone du cœur, plein.
    val h = p(22.4f, 48.6f)
    val r = 3.8f * k
    val hex = Path().apply {
        for (i in 0 until 6) {
            val a = Math.toRadians(60.0 * i + 30.0)
            val pt = Offset(h.x + r * kotlin.math.cos(a).toFloat(), h.y + r * kotlin.math.sin(a).toFloat())
            if (i == 0) moveTo(pt.x, pt.y) else lineTo(pt.x, pt.y)
        }
        close()
    }
    drawPath(hex, color)
}

/** L'oignon de Tor : trois pelures et une tige, dans un carré de côté [size]. */
private fun DrawScope.drawOnion(center: Offset, size: Float, color: Color) {
    val stroke = Stroke(width = size * 0.075f)
    val body = Offset(center.x, center.y + size * 0.1f)
    val r = size * 0.38f
    drawCircle(color, radius = r, center = body, style = stroke)
    drawOval(color, Offset(body.x - r * 0.62f, body.y - r), Size(r * 1.24f, r * 2f), style = stroke)
    drawOval(color, Offset(body.x - r * 0.24f, body.y - r), Size(r * 0.48f, r * 2f), style = stroke)
    drawLine(color, Offset(body.x, body.y - r), Offset(body.x + size * 0.08f, center.y - size * 0.46f), strokeWidth = stroke.width)
}
