package com.whisper.alerts

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.whisper.R
import com.whisper.repositories.ContactRepository
import com.whisper.theme.Ink
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import javax.inject.Inject

@HiltViewModel
class AlertsViewModel @Inject constructor(
    val alerts: InAppAlerts,
    private val contacts: ContactRepository
) : ViewModel() {
    suspend fun nameOf(contactPublicKey: String): String? =
        runCatching { contacts.getContact(contactPublicKey)?.displayLabel }.getOrNull()
}

/**
 * Le bandeau d'alerte, en haut de l'écran, quelques secondes : « <nom> vous
 * bipe », app ouverte (choix de l'auteur, 29/09). Ni photo ni contenu. App
 * fermée, la notification reste anonyme : « Whisper vous bipe ».
 * Toucher ouvre la conversation : c'est l'utilisateur qui choisit de voir.
 */
@Composable
fun AlertBanner(
    openConversation: String?,
    onOpen: (String) -> Unit,
    viewModel: AlertsViewModel = hiltViewModel()
) {
    val alerts = viewModel.alerts
    LaunchedEffect(openConversation) { alerts.openConversation = openConversation }
    val alert by alerts.current.collectAsState()

    LaunchedEffect(alert) {
        val shown = alert ?: return@LaunchedEffect
        delay(SHOWN_MS)
        alerts.dismiss(shown)
    }

    // Garde le dernier bandeau pendant qu'il s'efface.
    val last = remember { mutableListOf<InAppAlerts.Alert>() }
    alert?.let { last.clear(); last += it }
    val shown = alert ?: last.firstOrNull()

    Box(Modifier.fillMaxWidth().statusBarsPadding(), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(
            visible = alert != null,
            enter = slideInVertically { -it },
            exit = slideOutVertically { -it } + fadeOut()
        ) {
            shown?.let { current ->
                val name by produceState<String?>(null, current.contactPublicKey) {
                    value = viewModel.nameOf(current.contactPublicKey)
                }
                Row(
                    Modifier
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .fillMaxWidth()
                        .background(Ink.Raised, RoundedCornerShape(16.dp))
                        .border(1.dp, Ink.LineStrong, RoundedCornerShape(16.dp))
                        .clickable {
                            alerts.dismiss(current)
                            onOpen(current.contactPublicKey)
                        }
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        name?.let { stringResource(R.string.alert_bip, it) } ?: stringResource(R.string.alert_someone),
                        color = Ink.Chalk,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Text(stringResource(R.string.alert_open), color = Ink.SageLight, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

private const val SHOWN_MS = 4_000L
