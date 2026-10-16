package com.whisper.ui.components

import com.whisper.ui.icons.rounded.Forward
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import com.whisper.ui.icons.Icons
import com.whisper.ui.icons.outlined.ErrorOutline
import com.whisper.ui.icons.outlined.Schedule
import com.whisper.ui.icons.rounded.Check
import com.whisper.ui.icons.rounded.DoneAll
import com.whisper.ui.icons.rounded.Pause
import com.whisper.ui.icons.rounded.PlayArrow
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.whisper.ui.icons.rounded.Close
import com.whisper.ui.icons.rounded.Share
import com.whisper.ui.icons.outlined.Lock
import com.whisper.ui.icons.outlined.Image
import com.whisper.ui.icons.outlined.Movie
import com.whisper.ui.icons.outlined.Audiotrack
import com.whisper.ui.icons.outlined.PictureAsPdf
import com.whisper.ui.icons.outlined.InsertDriveFile
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.whisper.R
import com.whisper.models.Message
import com.whisper.models.MessageStatus
import com.whisper.theme.Ink

/**
 * Une bulle. L'encre pâlit à mesure que le message approche de sa fin :
 * l'éphémère se voit sans chronomètre rouge sous chaque ligne. Un appui
 * affiche le temps restant exact.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: Message,
    plainText: String,
    isOwn: Boolean,
    now: Long,
    showExpiry: Boolean = false,
    voice: VoiceUi? = null,
    file: FileUi? = null,
    /** Partager ce fichier (enregistrable, reçu, complet) ; null : pas d'icône. */
    onShare: (() -> Unit)? = null,
    /** Sélection (supprimer soi-même) : l'appui long sélectionne, la bulle sélectionnée est surlignée. */
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit = {},
    onRetry: () -> Unit = {},
    /** Réponse (1.3.1) : la citation affichée en haut de la bulle ; null sinon. */
    quote: QuoteUi? = null,
    /** Glisser la bulle vers la droite pour y répondre ; null : pas de réponse possible. */
    onReply: (() -> Unit)? = null
) {
    val ink = inkStrength(message, now)
    val failed = isOwn && message.status == MessageStatus.FAILED
    val maxWidth = (LocalConfiguration.current.screenWidthDp * 0.78f).dp

    // Glisser vers la droite pour répondre : au-delà du seuil, une vibration,
    // et au relâcher la réponse s'ouvre. La bulle revient toujours à sa place.
    val trigger = with(LocalDensity.current) { 64.dp.toPx() }
    val drag = remember { Animatable(0f) }
    val dragScope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var armed by remember { mutableStateOf(false) }
    val swipe = if (onReply == null) Modifier else Modifier.pointerInput(onReply) {
        detectHorizontalDragGestures(
            onDragEnd = {
                if (drag.value >= trigger) onReply()
                armed = false
                dragScope.launch { drag.animateTo(0f) }
            },
            onDragCancel = {
                armed = false
                dragScope.launch { drag.animateTo(0f) }
            },
            onHorizontalDrag = { change, amount ->
                change.consume()
                val next = (drag.value + amount).coerceIn(0f, trigger * 1.4f)
                dragScope.launch { drag.snapTo(next) }
                if (!armed && next >= trigger) {
                    armed = true
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            }
        )
    }

    Box(Modifier.fillMaxWidth().then(swipe)) {
    if (drag.value > 0f) {
        Icon(
            Icons.Rounded.Forward,
            null,
            tint = Ink.SageLight.copy(alpha = (drag.value / trigger).coerceIn(0f, 1f)),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 16.dp)
                .size(22.dp)
                .graphicsLayer { scaleX = -1f }
        )
    }
    Column(
        modifier = Modifier
            .graphicsLayer { translationX = drag.value }
            .fillMaxWidth()
            .background(if (selected) Ink.SageDim else androidx.compose.ui.graphics.Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 3.dp),
        horizontalAlignment = if (isOwn) Alignment.End else Alignment.Start
    ) {
        // La pointe en bas, du côté de celui qui parle.
        val shape = RoundedCornerShape(
            topStart = 20.dp,
            topEnd = 20.dp,
            bottomStart = if (isOwn) 20.dp else 6.dp,
            bottomEnd = if (isOwn) 6.dp else 20.dp
        )
        // Fichier enregistrable reçu : l'icône de partage, à côté de la bulle.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(
                modifier = Modifier
                    .widthIn(max = maxWidth)
                    .shadow(2.dp, shape, ambientColor = Ink.Shadow, spotColor = Ink.Shadow)
                    .background(
                        Brush.verticalGradient(
                            if (isOwn) listOf(Ink.BubbleOwnTop, Ink.BubbleOwn) else listOf(Ink.BubbleOtherTop, Ink.BubbleOther)
                        ),
                        shape
                    )
                    .then(if (failed) Modifier.border(1.dp, Ink.BrickDim, shape) else Modifier)
                    .clip(shape)
                    .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                    .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 8.dp),
                horizontalAlignment = Alignment.End
            ) {
                if (quote != null) QuoteBlock(quote, isOwn, Modifier.align(Alignment.Start))
                if (file != null) {
                    FileRow(file, isOwn, ink, Modifier.align(Alignment.Start))
                } else if (voice != null) {
                    VoiceRow(voice, ink, Modifier.align(Alignment.Start))
                } else {
                    Text(
                        text = plainText,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Ink.Chalk.copy(alpha = ink),
                        modifier = Modifier.align(Alignment.Start)
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    Text(
                        text = formatMessageTime(message.timestamp),
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink.Ash.copy(alpha = ink)
                    )
                    // « Jamais » : une petite marque, pour que chacun voie que ce message reste.
                    if (com.whisper.services.MessageService.isNever(message.timestamp, message.expiresAt)) {
                        Text("∞", style = MaterialTheme.typography.bodySmall, color = Ink.Ash.copy(alpha = ink))
                    }
                    if (isOwn) StatusMark(message.status, ink)
                }
            }
            if (onShare != null) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Ink.Slate)
                        .clickable(onClick = onShare),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Share, stringResource(R.string.file_share), tint = Ink.ChalkSoft, modifier = Modifier.size(18.dp))
                }
            }
        }

        if (showExpiry) {
            val left = expiryLabel(message.getRemainingTtlMs())
            if (left.isNotEmpty()) {
                Text(
                    stringResource(R.string.message_expires_in, left),
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink.Ash,
                    modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp)
                )
            }
        }

        // Sous la bulle, pas dedans : on le voit sans avoir à le chercher.
        if (failed) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Icon(Icons.Outlined.ErrorOutline, null, tint = Ink.Brick, modifier = Modifier.size(15.dp))
                Text(
                    stringResource(R.string.message_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink.Brick
                )
                Box(
                    Modifier
                        .height(32.dp)
                        .border(1.dp, Ink.Line, RoundedCornerShape(16.dp))
                        .clickable(onClick = onRetry)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.message_retry),
                        style = MaterialTheme.typography.labelMedium,
                        color = Ink.SageLight
                    )
                }
            }
        }
    }
    }
}

/** Ce qu'une réponse cite : qui, quelques mots, et comment revenir à l'original. */
class QuoteUi(val author: String, val snippet: String, val onClick: (() -> Unit)?)

/** La citation, en haut de la bulle : un trait sauge, l'auteur, l'extrait. */
@Composable
private fun QuoteBlock(quote: QuoteUi, isOwn: Boolean, modifier: Modifier) {
    Row(
        modifier
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Ink.Chalk.copy(alpha = if (isOwn) 0.06f else 0.05f))
            .then(if (quote.onClick != null) Modifier.clickable(onClick = quote.onClick) else Modifier)
            .padding(end = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(width = 3.dp, height = 36.dp).background(Ink.Sage))
        Column(Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp)) {
            Text(quote.author, style = MaterialTheme.typography.labelMedium, color = Ink.SageLight, maxLines = 1)
            Text(
                quote.snippet,
                style = MaterialTheme.typography.bodySmall,
                color = Ink.ChalkSoft,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
    }
}

/** Ce qu'une bulle de fichier doit savoir (phase 16). */
class FileUi(
    val header: com.whisper.files.FilePayload.Header,
    /** Morceaux arrivés (reçu) ou accusés (envoyé). */
    val done: Int,
    /** Conversation masquée : ni nom ni type. */
    val masked: Boolean,
    /** Annuler l'envoi (le nôtre, pas encore complet) ; null : pas de bouton. */
    val onCancel: (() -> Unit)? = null
) {
    val complete: Boolean get() = done >= header.count
}

/** Icône du type, nom, taille, et la progression tant que tout n'est pas là. */
@Composable
private fun FileRow(file: FileUi, isOwn: Boolean, ink: Float, modifier: Modifier) {
    val h = file.header
    val icon = when {
        file.masked -> Icons.Outlined.Lock
        h.mime.startsWith("image/") -> Icons.Outlined.Image
        h.mime.startsWith("video/") -> Icons.Outlined.Movie
        h.mime.startsWith("audio/") -> Icons.Outlined.Audiotrack
        h.mime == "application/pdf" -> Icons.Outlined.PictureAsPdf
        else -> Icons.Outlined.InsertDriveFile
    }
    Row(
        modifier = modifier.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier.size(42.dp).background(Ink.Line.copy(alpha = ink), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = Ink.ChalkSoft.copy(alpha = ink), modifier = Modifier.size(22.dp))
        }
        Column(Modifier.widthIn(max = 220.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                if (file.masked) stringResource(R.string.read_lock_masked) else h.name,
                style = MaterialTheme.typography.bodyLarge,
                color = Ink.Chalk.copy(alpha = ink),
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            val details = buildList {
                add(com.whisper.files.FilePayload.sizeLabel(h.size))
                if (h.viewOnce) add(stringResource(R.string.file_view_once))
                if (!file.complete) {
                    val percent = file.done * 100 / h.count
                    add(stringResource(if (isOwn) R.string.file_sending else R.string.file_receiving, percent))
                }
            }.joinToString(" · ")
            Text(details, style = MaterialTheme.typography.bodySmall, color = Ink.Ash.copy(alpha = ink))
            file.onCancel?.let { cancel ->
                Row(
                    Modifier
                        .padding(top = 4.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.dp, Ink.Line, RoundedCornerShape(14.dp))
                        .clickable(onClick = cancel)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(Icons.Rounded.Close, null, tint = Ink.Brick, modifier = Modifier.size(14.dp))
                    Text(stringResource(R.string.file_cancel), style = MaterialTheme.typography.labelMedium, color = Ink.Brick)
                }
            }
            if (!file.complete) {
                Box(
                    Modifier
                        .padding(top = 2.dp)
                        .width(180.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Ink.LineStrong)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(file.done.toFloat() / h.count)
                            .height(3.dp)
                            .background(Ink.Sage.copy(alpha = ink))
                    )
                }
            }
        }
    }
}

/** Ce qu'une bulle vocale doit savoir pour s'afficher et se lire. */
class VoiceUi(
    /** Tous les morceaux sont là : le vocal peut se lire. */
    val ready: Boolean,
    val durationMs: Int,
    val playing: Boolean,
    val progress: Float,
    val onToggle: () -> Unit
)

/** Lecture ou pause, la piste qui avance, la durée. */
@Composable
private fun VoiceRow(voice: VoiceUi, ink: Float, modifier: Modifier) {
    Row(
        modifier = modifier.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (voice.ready) Ink.Sage.copy(alpha = ink) else Ink.Line)
                .clickable(enabled = voice.ready, onClick = voice.onToggle),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (voice.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = stringResource(if (voice.playing) R.string.voice_pause else R.string.voice_play),
                tint = if (voice.ready) Ink.Night else Ink.Ash,
                modifier = Modifier.size(24.dp)
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                Modifier
                    .width(140.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Ink.LineStrong)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(if (voice.playing) voice.progress else 0f)
                        .height(4.dp)
                        .background(Ink.Sage.copy(alpha = ink))
                )
            }
            Text(
                if (voice.ready) com.whisper.voice.VoicePayload.durationLabel(voice.durationMs)
                else stringResource(R.string.voice_receiving),
                style = MaterialTheme.typography.bodySmall,
                color = Ink.ChalkSoft.copy(alpha = ink)
            )
        }
    }
}

/** Horloge : pas encore parti. Coche : parti sur le flux Tor. Double coche : rangé chez le contact. */
@Composable
fun StatusMark(status: MessageStatus, ink: Float = 1f) {
    when (status) {
        MessageStatus.PENDING -> Icon(
            Icons.Outlined.Schedule,
            contentDescription = stringResource(R.string.message_pending),
            tint = Ink.Ash,
            modifier = Modifier.size(14.dp)
        )
        MessageStatus.DELIVERED, MessageStatus.READ -> Icon(
            Icons.Rounded.DoneAll,
            contentDescription = stringResource(R.string.message_delivered),
            tint = Ink.Sage.copy(alpha = ink),
            modifier = Modifier.size(15.dp)
        )
        MessageStatus.SENT -> Icon(
            Icons.Rounded.Check,
            contentDescription = stringResource(R.string.message_sent),
            tint = Ink.Sage.copy(alpha = ink),
            modifier = Modifier.size(15.dp)
        )
        else -> Unit
    }
}

/**
 * Force de l'encre, de 1 à 0,4 : pleine pendant la première moitié de la
 * vie du message, puis elle pâlit jusqu'à l'effacement.
 */
private fun inkStrength(message: Message, now: Long): Float {
    val total = (message.expiresAt - message.timestamp).coerceAtLeast(1L)
    val left = (message.expiresAt - now).coerceIn(0L, total).toFloat() / total
    return if (left >= 0.5f) 1f else 0.4f + 1.2f * left
}

/**
 * DateDivider - Le jour, discret, au-dessus du premier message de ce jour.
 */
@Composable
fun DateDivider(dateLabel: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = dateLabel,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            color = Ink.ChalkSoft,
            modifier = Modifier
                .glass(androidx.compose.foundation.shape.RoundedCornerShape(50), elevation = 2.dp)
                .padding(horizontal = 13.dp, vertical = 5.dp)
        )
    }
}

fun formatMessageTime(timestamp: Long): String {
    val time = java.time.Instant.ofEpochMilli(timestamp)
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalTime()
    return "%02d:%02d".format(time.hour, time.minute)
}

@Composable
fun dateGroupLabel(timestamp: Long): String {
    val date = java.time.Instant.ofEpochMilli(timestamp)
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalDate()
    val today = java.time.LocalDate.now()

    return when {
        date == today -> stringResource(R.string.chat_today)
        date == today.minusDays(1) -> stringResource(R.string.chat_yesterday)
        date.year == today.year -> date.format(java.time.format.DateTimeFormatter.ofPattern("d MMM"))
        else -> date.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy"))
    }
}

/** Temps restant : « 42 min », « 3 h », « 5 j ». Vide si expiré. */
@Composable
fun expiryLabel(remainingMs: Long): String {
    val minutes = remainingMs / 60_000
    return when {
        remainingMs <= 0 -> ""
        minutes > 10L * 365 * 24 * 60 -> stringResource(R.string.ttl_never)
        minutes < 60 -> "${maxOf(minutes, 1)} min"
        minutes < 48 * 60 -> "${minutes / 60} h"
        else -> stringResource(R.string.duration_days, (minutes / (24 * 60)).toInt())
    }
}

/** Durée courte d'un réglage : « 15 min », « 8 h », « 7 j ». */
@Composable
fun shortDuration(minutes: Int): String = when {
    minutes >= com.whisper.services.MessageService.NEVER_TTL_MINUTES -> "∞"
    minutes < 60 -> "$minutes min"
    minutes < 24 * 60 -> "${minutes / 60} h"
    else -> stringResource(R.string.duration_days, minutes / (24 * 60))
}
