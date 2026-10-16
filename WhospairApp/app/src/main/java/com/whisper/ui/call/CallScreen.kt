package com.whisper.ui.call

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import com.whisper.ui.icons.Icons
import com.whisper.ui.icons.automirrored.rounded.VolumeUp
import com.whisper.ui.icons.rounded.Call
import com.whisper.ui.icons.rounded.CallEnd
import com.whisper.ui.icons.rounded.CallMissed
import com.whisper.ui.icons.rounded.Hearing
import com.whisper.ui.icons.rounded.Mic
import com.whisper.ui.icons.rounded.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.whisper.R
import com.whisper.call.CallEvent
import com.whisper.call.CallManager
import com.whisper.call.CallPeer
import com.whisper.call.CallState
import com.whisper.call.EndReason
import com.whisper.theme.Ink
import com.whisper.ui.screens.Avatar
import com.whisper.util.rememberPermission
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import javax.inject.Inject

@HiltViewModel
class CallViewModel @Inject constructor(val manager: CallManager) : ViewModel()

/**
 * Posé par-dessus toute l'app dès qu'un appel existe. Rien au repos.
 * Retour ne ferme pas l'écran : on raccroche d'un geste explicite.
 */
@Composable
fun CallOverlay(viewModel: CallViewModel = hiltViewModel()) {
    val manager = viewModel.manager
    val state by manager.state.collectAsState()
    // Pas de return en cours de composition (cf. le plantage du micro) : une branche.
    val peer = when (val s = state) {
        is CallState.Outgoing -> s.peer
        is CallState.Incoming -> s.peer
        is CallState.Active -> s.peer
        is CallState.Ended -> s.peer
        CallState.Idle -> null
    }
    // Un appel qui arrive pendant la saisie d'un message : le clavier part.
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    LaunchedEffect(peer != null) {
        if (peer != null) {
            focus.clearFocus(force = true)
            keyboard?.hide()
        }
    }
    if (peer != null) CallContent(state, peer, manager)
}

@Composable
private fun CallContent(state: CallState, peer: CallPeer, manager: CallManager) {
    BackHandler { }

    val context = LocalContext.current
    val micDenied = stringResource(R.string.call_mic_denied)
    val mic = rememberPermission(Manifest.permission.RECORD_AUDIO) { granted ->
        if (granted) manager.accept()
        else {
            Toast.makeText(context, micDenied, Toast.LENGTH_LONG).show()
            manager.hangUp()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Ink.Night)
            .clickable(enabled = false) { }
            .systemBarsPadding()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(48.dp))
        Avatar(peer.avatarName, size = 96, photo = peer.photo)
        Spacer(Modifier.height(20.dp))
        Text(peer.label, color = Ink.Chalk, style = MaterialTheme.typography.headlineSmall, maxLines = 1)
        Spacer(Modifier.height(8.dp))
        Text(statusText(state), color = statusColor(state), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)

        if (state is CallState.Active) {
            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(R.string.call_delay_hint),
                color = Ink.Ash,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
        }

        Spacer(Modifier.weight(1f))

        when (val s = state) {
            is CallState.Incoming -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                RoundAction(Icons.Rounded.CallEnd, stringResource(R.string.call_decline), Ink.BrickDim, Ink.Brick) { manager.hangUp() }
                RoundAction(Icons.Rounded.Call, stringResource(R.string.call_answer), Ink.Sage, Ink.Night) {
                    if (mic.granted) manager.accept() else mic.request()
                }
            }
            is CallState.Outgoing -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                RoundAction(Icons.Rounded.CallEnd, stringResource(R.string.call_cancel), Ink.BrickDim, Ink.Brick) { manager.hangUp() }
            }
            is CallState.Active -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                RoundAction(
                    if (s.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                    stringResource(if (s.muted) R.string.call_unmute else R.string.call_mute),
                    if (s.muted) Ink.Chalk else Ink.Relief,
                    if (s.muted) Ink.Night else Ink.Chalk
                ) { manager.setMuted(!s.muted) }
                RoundAction(Icons.Rounded.CallEnd, stringResource(R.string.call_hang_up), Ink.BrickDim, Ink.Brick) { manager.hangUp() }
                RoundAction(
                    if (s.speaker) Icons.Rounded.Hearing else Icons.AutoMirrored.Rounded.VolumeUp,
                    stringResource(if (s.speaker) R.string.call_earpiece else R.string.call_speaker),
                    Ink.Relief,
                    Ink.Chalk
                ) { manager.setSpeaker(!s.speaker) }
            }
            is CallState.Ended, CallState.Idle -> Unit
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun statusText(state: CallState): String = when (state) {
    is CallState.Outgoing -> stringResource(if (state.ringing) R.string.call_ringing else R.string.call_connecting)
    is CallState.Incoming -> stringResource(R.string.call_incoming)
    is CallState.Active -> {
        val elapsed by produceState(0L, state.startedAt) {
            while (true) {
                value = (System.currentTimeMillis() - state.startedAt) / 1000
                delay(1_000)
            }
        }
        "%d:%02d".format(elapsed / 60, elapsed % 60)
    }
    is CallState.Ended -> stringResource(
        when (state.reason) {
            EndReason.HUNG_UP -> R.string.call_ended
            EndReason.DECLINED -> R.string.call_declined
            EndReason.BUSY -> R.string.call_busy
            EndReason.NO_ANSWER -> R.string.call_no_answer
            EndReason.UNREACHABLE -> R.string.call_unreachable
            EndReason.LOST -> R.string.call_lost
        }
    )
    CallState.Idle -> ""
}

private fun statusColor(state: CallState): Color = when (state) {
    is CallState.Active -> Ink.Sage
    is CallState.Ended -> if (state.reason == EndReason.HUNG_UP) Ink.ChalkSoft else Ink.Ochre
    else -> Ink.ChalkSoft
}

/** Un rond et son verbe dessous : le libellé dit ce que fait le geste. */
@Composable
private fun RoundAction(icon: ImageVector, label: String, background: Color, tint: Color, onClick: () -> Unit) {
    Column(
        Modifier
            .width(96.dp)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(64.dp)
                .background(background, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(label, color = Ink.ChalkSoft, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
    }
}

/** Le libellé d'une trace d'appel, dans la conversation et dans la liste. */
@Composable
fun callEventLabel(event: CallEvent): String {
    val duration = "%d:%02d".format(event.seconds / 60, event.seconds % 60)
    return when {
        event.answered && event.outgoing -> stringResource(R.string.call_log_outgoing, duration)
        event.answered -> stringResource(R.string.call_log_incoming, duration)
        !event.outgoing && event.reason == EndReason.DECLINED -> stringResource(R.string.call_log_refused)
        !event.outgoing -> stringResource(R.string.call_log_missed)
        event.reason == EndReason.DECLINED -> stringResource(R.string.call_log_refused)
        event.reason == EndReason.NO_ANSWER -> stringResource(R.string.call_log_no_answer)
        event.reason == EndReason.BUSY -> stringResource(R.string.call_log_busy)
        event.reason == EndReason.HUNG_UP -> stringResource(R.string.call_log_cancelled)
        else -> stringResource(R.string.call_log_unreachable)
    }
}

/** Manqué ou refusé à l'arrivée : la seule trace qui mérite la couleur d'alerte. */
fun CallEvent.isMissed(): Boolean = !outgoing && !answered

/** Trace d'appel au fil de la conversation : une ligne centrée, pas une bulle. */
@Composable
fun CallEventRow(event: CallEvent, time: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            Modifier
                .background(Ink.Slate, androidx.compose.foundation.shape.RoundedCornerShape(50))
                .padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val tint = if (event.isMissed()) Ink.Brick else Ink.Sage
            Icon(
                if (event.answered) Icons.Rounded.Call else Icons.Rounded.CallMissed,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(16.dp)
            )
            Text(callEventLabel(event), color = Ink.ChalkSoft, style = MaterialTheme.typography.bodySmall)
            Text(time, color = Ink.Ash, style = MaterialTheme.typography.labelSmall)
        }
    }
}
