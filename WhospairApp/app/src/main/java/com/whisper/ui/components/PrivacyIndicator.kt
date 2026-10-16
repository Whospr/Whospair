package com.whisper.ui.components

import com.whisper.theme.Ink
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.whisper.R
import com.whisper.tor.TorRuntime

/**
 * L'indicateur d'état, en haut de l'écran.
 *
 * ### Parti pris
 *
 * Trois mots et deux points, pas davantage. La tentation, sur une app de ce
 * genre, est d'afficher un cadenas et d'écrire « sécurisé » : c'est rassurant,
 * et c'est faux — aucune app ne peut promettre ça. L'indicateur ne dit donc que
 * des choses vérifiables : Tor est prêt ou non, le trafic passe par un
 * tunnel ou non.
 *
 * Il est aussi volontairement terne. Un bandeau vert vif se remarque
 * par-dessus l'épaule et par-dessus une épaule, c'est déjà une information
 * donnée. Les couleurs restent proches du fond ; l'œil de l'utilisateur les
 * trouve parce qu'il sait où regarder, pas celui d'un voisin.
 *
 * Le point de Tor respire lentement pendant l'amorçage. C'est le
 * seul mouvement de l'écran : une reconnexion se voit sans qu'un texte la
 * décrive.
 */
@Composable
fun PrivacyIndicator(
    torState: TorRuntime.State,
    tunnelled: Boolean,
    modifier: Modifier = Modifier,
    /** « Rester joignable » actif : dit ici, plutôt que par une notification permanente. */
    reachable: Boolean = false,
    onClick: () -> Unit = {}
) {
    // Une pastille cerclée, à hauteur de doigt : on la touche pour
    // comprendre, elle ne crie rien.
    Row(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Ink.Chalk.copy(alpha = 0.05f))
            .border(1.dp, Ink.Line, RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TorDot(torState)

        Text(
            text = stringResource(torState.labelRes()),
            style = MaterialTheme.typography.bodyMedium,
            color = Ink.ChalkSoft
        )

        if (reachable) {
            Box(Modifier.size(3.dp).background(Ink.Ash, CircleShape))
            Text(
                text = stringResource(R.string.reach_service_text),
                style = MaterialTheme.typography.bodyMedium,
                color = Ink.Sage
            )
        }

        if (tunnelled) {
            TunnelMark()
        }
    }
}

@Composable
private fun TorDot(state: TorRuntime.State) {
    val scheme = MaterialTheme.colorScheme

    val target = when (state) {
        is TorRuntime.State.Ready -> Ink.Sage      // vert éteint, pas un feu vert
        is TorRuntime.State.Starting -> Ink.Ochre
        is TorRuntime.State.Failed -> Ink.Brick
        TorRuntime.State.Off -> scheme.onSurface.copy(alpha = 0.25f)
    }

    val color by animateColorAsState(targetValue = target, label = "tor-dot")

    // Le battement ne concerne que l'amorçage : un point fixe signifie
    // « c'est stable », dans un sens comme dans l'autre.
    val breathing = state is TorRuntime.State.Starting
    val transition = rememberInfiniteTransition(label = "relay-breath")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100),
            repeatMode = RepeatMode.Reverse
        ),
        label = "relay-breath-alpha"
    )

    Box(
        modifier = Modifier
            .size(8.dp)
            .alpha(if (breathing) pulse else 1f)
            .background(color = color, shape = CircleShape)
    )
}

/**
 * La marque du tunnel : un anneau creux, pas un bouclier.
 *
 * Un bouclier dirait « vous êtes protégé ». L'app ne constate qu'une chose —
 * le trafic sort par une interface VPN — et n'a aucun moyen de savoir ce que
 * ce tunnel vaut. La forme reste donc neutre, et le texte d'explication au
 * clic dit exactement ça.
 */
@Composable
private fun TunnelMark() {
    Box(
        modifier = Modifier
            .size(7.dp)
            .border(
                width = 1.2.dp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                shape = CircleShape
            )
    )
}

/**
 * Ce que l'indicateur veut dire, au clic. Des faits, pas de promesse.
 */
@Composable
fun PrivacyExplainer(torState: TorRuntime.State, tunnelled: Boolean, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.indicator_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(
                    if (torState is TorRuntime.State.Ready) R.string.indicator_channel_live
                    else R.string.indicator_channel_none
                ))
                Text(stringResource(
                    if (tunnelled) R.string.indicator_tunnel_on else R.string.indicator_tunnel_off
                ))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.indicator_dismiss)) }
        }
    )
}

private fun TorRuntime.State.labelRes(): Int = when (this) {
    is TorRuntime.State.Ready -> R.string.tor_state_live
    is TorRuntime.State.Starting -> R.string.tor_state_connecting
    is TorRuntime.State.Failed, TorRuntime.State.Off -> R.string.tor_state_offline
}
