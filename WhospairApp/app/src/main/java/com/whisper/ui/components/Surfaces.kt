package com.whisper.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.whisper.theme.Appearance
import com.whisper.theme.ChatBackground
import com.whisper.theme.Ink

/**
 * Les matières de l'app (1.2.5) : des capsules qui flottent au-dessus du
 * contenu, des cartes qui regroupent, une lueur douce en haut de l'écran.
 * Du relief sans bruit : ombres courtes, bords à peine marqués.
 */

/**
 * Hauteur que la barre d'onglets flottante occupe en bas : les listes la
 * gardent libre en fin de défilement, mais passent dessous en défilant.
 */
val LocalBottomBarSpace = compositionLocalOf { 0.dp }

/**
 * Fond des écrans principaux : les couleurs du fond de conversation choisi
 * (sans son motif), éclairées par le haut.
 */
fun Modifier.screenBackground(): Modifier = drawBehind {
    val bg = ChatBackground.current
    drawRect(Brush.verticalGradient(listOf(bg.top, bg.bottom)))
    drawRect(
        Brush.radialGradient(
            colors = listOf(Ink.Glow.copy(alpha = if (Appearance.isDark) 0.55f else 0.7f), Color.Transparent),
            center = Offset(size.width / 2, -size.height * 0.05f),
            radius = size.maxDimension * 0.6f
        )
    )
}

/** Une capsule de verre dépoli posée au-dessus du contenu. */
fun Modifier.glass(shape: Shape, elevation: Dp = 14.dp): Modifier = this
    .shadow(elevation, shape, ambientColor = Ink.Shadow, spotColor = Ink.Shadow)
    .background(Ink.Glass, shape)
    .border(1.dp, Ink.GlassEdge, shape)
    .clip(shape)

/** Une carte qui regroupe des lignes, sans traits de séparation. */
@Composable
fun GroupCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .shadow(6.dp, shape, ambientColor = Ink.Shadow, spotColor = Ink.Shadow)
            .background(Ink.Card, shape)
            .border(1.dp, Ink.GlassEdge, shape)
            .clip(shape)
            .padding(vertical = 6.dp),
        content = content
    )
}

/** Titre de groupe, au-dessus ou dans une carte. */
@Composable
fun GroupLabel(text: String, color: Color = Ink.Ash, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = color,
        modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 4.dp)
    )
}

/** La tuile de couleur derrière une icône de réglage. */
@Composable
fun IconTile(icon: ImageVector, tone: Pair<Color, Color>, size: Dp = 36.dp) {
    Box(
        Modifier
            .size(size)
            .background(Brush.verticalGradient(listOf(tone.first, tone.second)), RoundedCornerShape(size * 0.3f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = Ink.OnTile, modifier = Modifier.size(size * 0.55f))
    }
}

/**
 * Une ligne de carte : tuile, titre, sous-titre facultatif, et à droite une
 * valeur ou un élément libre. [danger] la passe en brique.
 */
@Composable
fun CardRow(
    icon: ImageVector,
    tone: Pair<Color, Color>,
    title: String,
    subtitle: String? = null,
    danger: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        IconTile(icon, if (danger) Ink.TileBrick else tone)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (danger) Ink.Brick else Ink.Chalk,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Ink.Ash, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing()
    }
}

/**
 * Teinte d'avatar sans photo : tirée du nom, toujours la même pour un même
 * contact. Des tons sourds, lettre claire, lisibles le jour comme la nuit.
 */
object AvatarTones {
    private val tones = listOf(
        Triple(Color(0xFF4A6453), Color(0xFF26352C), Color(0xFFCFE3D4)),
        Triple(Color(0xFF4A5673), Color(0xFF272F42), Color(0xFFD3DCEE)),
        Triple(Color(0xFF6A5A3E), Color(0xFF3A3226), Color(0xFFEADBBE)),
        Triple(Color(0xFF6B4A4E), Color(0xFF3A282B), Color(0xFFF0D6D8)),
        Triple(Color(0xFF3F6260), Color(0xFF243837), Color(0xFFCDE6E3)),
        Triple(Color(0xFF5B4F6E), Color(0xFF302A3D), Color(0xFFE2D9EF))
    )

    fun of(name: String): Triple<Color, Color, Color> =
        tones[Math.floorMod(name.trim().lowercase().hashCode(), tones.size)]
}

/** Pastille ronde « joignable », entourée du fond. */
@Composable
fun OnlineDot(modifier: Modifier = Modifier, ring: Color = Ink.Night, size: Dp = 14.dp) {
    Box(
        modifier
            .size(size)
            .background(ring, CircleShape)
            .padding(size * 0.2f)
            .background(Ink.Sage, CircleShape)
    )
}
