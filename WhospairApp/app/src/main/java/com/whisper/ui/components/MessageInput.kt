package com.whisper.ui.components

import com.whisper.ui.icons.outlined.Smile
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import com.whisper.ui.icons.Icons
import com.whisper.ui.icons.automirrored.rounded.Send
import com.whisper.ui.icons.outlined.Timer
import com.whisper.ui.icons.outlined.DeleteOutline
import com.whisper.ui.icons.rounded.Mic
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import com.whisper.ui.icons.rounded.Vibration
import com.whisper.ui.icons.rounded.AttachFile
import com.whisper.ui.icons.rounded.Add
import com.whisper.ui.icons.rounded.Close
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.runtime.MutableState
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.whisper.alerts.InAppAlerts
import kotlinx.coroutines.delay
import androidx.compose.ui.draw.alpha
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whisper.R
import com.whisper.services.TTLPreset
import com.whisper.theme.Ink

/**
 * La saisie : durée de vie à gauche, texte au milieu, envoyer à droite.
 * Rien ne bloque la saisie pendant un envoi : la bulle part avec son horloge.
 */
@Composable
fun MessageInput(
    message: String,
    onMessageChange: (String) -> Unit,
    onSend: () -> Unit,
    selectedTTL: Int = com.whisper.services.MessageService.DEFAULT_TTL_MINUTES,
    onTTLChange: (Int) -> Unit = {},
    ttlPresets: List<TTLPreset> = emptyList(),
    recording: Boolean = false,
    recordingMs: Long = 0,
    onRecord: () -> Unit = {},
    onCancelRecording: () -> Unit = {},
    onSendRecording: () -> Unit = {},
    /** Faire signe (contact confirmé) ; null : le bouton n'a que la durée de vie. */
    onNudge: (((Boolean) -> Unit) -> Unit)? = null,
    /** Joindre un fichier (phase 16). */
    onAttach: (() -> Unit)? = null,
    /** Réponse en cours (1.3.1) : à qui, et quelques mots du message cité ; null sinon. */
    replyTo: Pair<String, String>? = null,
    onCancelReply: () -> Unit = {},
    /** Panneau d'emojis ouvert : tenu par l'écran, qu'un appui sur le fil referme. */
    showEmoji: MutableState<Boolean> = remember { mutableStateOf(false) }
) {
    val showTTLPicker = remember { mutableStateOf(false) }
    val showActions = remember { mutableStateOf(false) }
    // Panneau d'emojis : à la place du clavier, jamais les deux à la fois.
    // Retour le ferme lui seul, pas la conversation.
    BackHandler(enabled = showEmoji.value) { showEmoji.value = false }
    val focusManager = LocalFocusManager.current
    val canSend = message.isNotBlank()
    val inputFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // Signe : en cours, puis son issue affichée 10 s (un signe toutes les 10 s au plus).
    var nudging by remember { mutableStateOf(false) }
    var nudgeResult by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(nudgeResult) {
        if (nudgeResult != null) {
            delay(InAppAlerts.NUDGE_INTERVAL_MS)
            nudgeResult = null
        }
    }

    // Rien ne barre l'écran : une capsule flotte sur le fond de conversation.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 10.dp, bottom = 12.dp)
    ) {

        nudgeResult?.let { sent ->
            Text(
                stringResource(if (sent) R.string.nudge_sent else R.string.nudge_unreachable),
                style = MaterialTheme.typography.bodySmall,
                color = if (sent) Ink.SageLight else Ink.Ash,
                modifier = Modifier
                    .padding(bottom = 6.dp)
                    .glass(RoundedCornerShape(50), elevation = 2.dp)
                    .padding(horizontal = 12.dp, vertical = 5.dp)
            )
        }

        // Le « + » ouvre ce panneau : durée de vie, faire signe, fichier.
        if (showActions.value && !recording) Column(
            Modifier
                .padding(bottom = 8.dp)
                .glass(RoundedCornerShape(24.dp))
                .padding(bottom = 10.dp)
        ) {
            ActionsPanel(
                ttlLabel = shortDuration(selectedTTL),
                ttlOpen = showTTLPicker.value,
                onTtl = { showTTLPicker.value = !showTTLPicker.value },
                nudgeTint = when (nudgeResult) {
                    true -> Ink.Sage
                    false -> Ink.Brick
                    null -> if (nudging) Ink.Ash else Ink.ChalkSoft
                },
                onNudge = onNudge?.let { nudge ->
                    {
                        if (!nudging && nudgeResult == null) {
                            nudging = true
                            nudge { ok ->
                                nudging = false
                                nudgeResult = ok
                            }
                        }
                        showActions.value = false
                        showTTLPicker.value = false
                    }
                },
                onFile = onAttach?.let { attach ->
                    {
                        showActions.value = false
                        showTTLPicker.value = false
                        attach()
                    }
                }
            )
            if (showTTLPicker.value && ttlPresets.isNotEmpty()) {
                TTLPickerRow(
                    presets = ttlPresets,
                    selected = selectedTTL,
                    onSelect = { ttl ->
                        onTTLChange(ttl)
                        showTTLPicker.value = false
                        showActions.value = false
                    }
                )
            }
        }

        if (showEmoji.value && !recording) {
            EmojiPanel(
                onPick = { onMessageChange(message + it) },
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .glass(RoundedCornerShape(24.dp))
                    .padding(vertical = 6.dp)
            )
        }

        // Réponse à un message : la citation au-dessus de la saisie, une croix pour renoncer.
        if (replyTo != null && !recording) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
                    .glass(RoundedCornerShape(18.dp), elevation = 4.dp)
                    .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(width = 3.dp, height = 34.dp).background(Ink.Sage, RoundedCornerShape(2.dp)))
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(replyTo.first, style = MaterialTheme.typography.labelMedium, color = Ink.SageLight, maxLines = 1)
                    Text(replyTo.second, style = MaterialTheme.typography.bodySmall, color = Ink.ChalkSoft, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                Box(
                    Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onCancelReply),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Rounded.Close, stringResource(R.string.reply_cancel), tint = Ink.Ash, modifier = Modifier.size(20.dp)) }
            }
        }

        // Pas de « return » anticipé ici : dans un bloc Compose en ligne, il
        // désaligne la pile interne de Compose et fait planter au redessin.
        if (recording) {
            RecordingRow(recordingMs, onCancelRecording, onSendRecording)
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 58.dp)
                    .glass(RoundedCornerShape(29.dp))
                    .padding(5.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // « + » : durée de vie, faire signe, fichier. La durée choisie se lit
                // dans le panneau (« Durée · 8 h »).
                val ttlLabel = shortDuration(selectedTTL)
                val moreDescription = stringResource(R.string.input_more, ttlLabel)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(if (showActions.value) Ink.SageDim else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable {
                                showActions.value = !showActions.value
                                showTTLPicker.value = false
                            }
                            .semantics { contentDescription = moreDescription },
                        contentAlignment = Alignment.Center
                    ) {
                        if (showActions.value) {
                            Icon(Icons.Rounded.Close, null, tint = Ink.SageLight, modifier = Modifier.size(24.dp))
                        } else {
                            // Le + et, dessous, la durée de vie en vigueur : toujours visible.
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Rounded.Add, null, tint = Ink.ChalkSoft, modifier = Modifier.size(18.dp))
                                Text(
                                    ttlLabel,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 11.sp),
                                    color = Ink.Ash,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                // Emojis : le panneau remplace le clavier ; un second appui rend le clavier.
                val emojiDescription = stringResource(R.string.input_emoji)
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(if (showEmoji.value) Ink.SageDim else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable {
                            if (showEmoji.value) {
                                showEmoji.value = false
                                inputFocus.requestFocus()
                                keyboard?.show()
                            } else {
                                // Le champ lâche le focus : le toucher ensuite referme le panneau.
                                focusManager.clearFocus()
                                keyboard?.hide()
                                showEmoji.value = true
                                showActions.value = false
                            }
                        }
                        .semantics { contentDescription = emojiDescription },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.Smile, null, tint = if (showEmoji.value) Ink.SageLight else Ink.ChalkSoft, modifier = Modifier.size(22.dp))
                }

                // Toute la zone de saisie se touche, pas seulement la ligne du texte.
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            showEmoji.value = false
                            inputFocus.requestFocus()
                            keyboard?.show()
                        }
                        .padding(horizontal = 6.dp, vertical = 12.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    PrivateTextField(
                        value = message,
                        onValueChange = onMessageChange,
                        placeholder = stringResource(R.string.message_placeholder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(inputFocus)
                            .onFocusChanged { if (it.isFocused) showEmoji.value = false },
                        singleLine = false,
                        maxLines = 5
                    )
                }

                // Champ vide : le micro. Du texte : envoyer.
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .shadow(6.dp, CircleShape, ambientColor = Ink.Sage, spotColor = Ink.Sage)
                        .background(Brush.verticalGradient(listOf(Ink.TileSage.first, Ink.TileSage.second)), CircleShape)
                        .clip(CircleShape)
                        .clickable(onClick = if (canSend) onSend else onRecord),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (canSend) Icons.AutoMirrored.Rounded.Send else Icons.Rounded.Mic,
                        contentDescription = stringResource(if (canSend) R.string.send else R.string.voice_record),
                        tint = Ink.OnTile,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

/**
 * Les gestes du « + », côte à côte : une icône et un verbe chacun. Faire
 * signe n'apparaît que pour un contact confirmé.
 */
@Composable
private fun ActionsPanel(
    ttlLabel: String,
    ttlOpen: Boolean,
    onTtl: () -> Unit,
    nudgeTint: androidx.compose.ui.graphics.Color,
    onNudge: (() -> Unit)?,
    onFile: (() -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ActionChip(Icons.Outlined.Timer, stringResource(R.string.input_ttl, ttlLabel), if (ttlOpen) Ink.SageLight else Ink.ChalkSoft, ttlOpen, onTtl)
        if (onNudge != null) ActionChip(Icons.Rounded.Vibration, stringResource(R.string.nudge_action), nudgeTint, false, onNudge)
        if (onFile != null) ActionChip(Icons.Rounded.AttachFile, stringResource(R.string.input_file), Ink.ChalkSoft, false, onFile)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ActionChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: androidx.compose.ui.graphics.Color,
    active: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(if (active) Ink.SageDim else Ink.Slate)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 1)
    }
}

/**
 * Pendant l'enregistrement : jeter à gauche, le temps au milieu, envoyer à droite.
 * Le point respire comme celui de Tor : on voit que ça tourne.
 */
@Composable
private fun RecordingRow(elapsedMs: Long, onCancel: () -> Unit, onSend: () -> Unit) {
    val pulse by rememberInfiniteTransition(label = "rec").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "rec-alpha"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(29.dp))
            .padding(5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable(onClick = onCancel),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.voice_cancel), tint = Ink.Brick, modifier = Modifier.size(24.dp))
        }
        Row(
            Modifier
                .weight(1f)
                .height(48.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(Modifier.size(10.dp).alpha(pulse).background(Ink.Brick, CircleShape))
            Text(
                com.whisper.voice.VoicePayload.durationLabel(elapsedMs.toInt()),
                style = MaterialTheme.typography.bodyLarge,
                color = Ink.Chalk
            )
            Text(
                stringResource(R.string.voice_recording, com.whisper.voice.VoicePayload.durationLabel(com.whisper.voice.VoiceRecorder.MAX_MS)),
                style = MaterialTheme.typography.bodySmall,
                color = Ink.Ash
            )
        }
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(Brush.verticalGradient(listOf(Ink.TileSage.first, Ink.TileSage.second)), CircleShape)
                .clip(CircleShape)
                .clickable(onClick = onSend),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.AutoMirrored.Rounded.Send,
                contentDescription = stringResource(R.string.voice_send),
                tint = Ink.OnTile,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/**
 * Les durées proposées, en pastilles. La choisie est cerclée de sauge.
 */
@Composable
fun TTLPickerRow(
    presets: List<TTLPreset>,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = 12.dp, end = 12.dp, top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        presets.forEach { preset ->
            val active = preset.minutes == selected
            Box(
                modifier = Modifier
                    .height(40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (active) Ink.SageDim else Ink.Slate)
                    .border(1.dp, if (active) Ink.Sage else Ink.Slate, RoundedCornerShape(20.dp))
                    .clickable { onSelect(preset.minutes) }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(preset.labelRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (active) Ink.SageLight else Ink.ChalkSoft
                )
            }
        }
    }
}
