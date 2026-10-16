package com.whisper.ui.screens

import com.whisper.ui.icons.rounded.Call
import com.whisper.ui.icons.outlined.WavingHand
import com.whisper.ui.icons.outlined.VerifiedUser
import com.whisper.ui.icons.outlined.Share
import com.whisper.theme.Ink
import com.whisper.ui.components.CardRow
import com.whisper.ui.components.GroupCard
import com.whisper.ui.components.GroupLabel
import com.whisper.ui.components.LocalBottomBarSpace
import com.whisper.ui.components.screenBackground
import com.whisper.ui.icons.outlined.QrCode2
import com.whisper.ui.icons.outlined.QrCodeScanner
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.whisper.viewmodels.ContactViewModel
import com.whisper.models.Contact
import com.whisper.util.ContactKeyParser
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.whisper.viewmodels.IdentityViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import com.whisper.security.Vault
import com.whisper.R
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.ViewModel
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.clickable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.whisper.ui.lock.PinPad
import com.whisper.ui.icons.Icons
import com.whisper.ui.icons.outlined.Person
import androidx.compose.material3.Icon
import com.whisper.ui.components.ContactActionsDialog
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable

/**
 * ContactsScreenContainer
 */
@Composable
fun ContactsScreenContainer(
    contactViewModel: ContactViewModel,
    paddingValues: PaddingValues,
    onContactClick: (String) -> Unit = {},
    onAddContact: () -> Unit = {},
    onOpenScanner: () -> Unit = {}
) {
    val contactState by contactViewModel.viewState.collectAsState()

    var selected by remember { mutableStateOf<String?>(null) }

    // Appeler et faire signe sans ouvrir la conversation (contacts confirmés).
    val context = androidx.compose.ui.platform.LocalContext.current
    val callManager = androidx.hilt.navigation.compose.hiltViewModel<com.whisper.ui.call.CallViewModel>().manager
    var calling by remember { mutableStateOf<Contact?>(null) }
    val callMicDenied = stringResource(R.string.call_mic_denied)
    val callMic = com.whisper.util.rememberPermission(android.Manifest.permission.RECORD_AUDIO) { granted ->
        val contact = calling
        if (granted && contact != null) callManager.call(contact)
        else if (!granted) android.widget.Toast.makeText(context, callMicDenied, android.widget.Toast.LENGTH_LONG).show()
    }
    val call: (Contact) -> Unit = { contact ->
        calling = contact
        if (callMic.granted) callManager.call(contact) else callMic.request()
    }
    // Un signe toutes les 10 s au plus, comme dans la conversation.
    val lastNudge = remember { mutableMapOf<String, Long>() }
    val nudgeSent = stringResource(R.string.nudge_sent)
    val nudgeUnreachable = stringResource(R.string.nudge_unreachable)
    val nudge: (Contact) -> Unit = nudge@{ contact ->
        val now = System.currentTimeMillis()
        if (now - (lastNudge[contact.publicKeyHex] ?: 0L) < com.whisper.alerts.InAppAlerts.NUDGE_INTERVAL_MS) return@nudge
        lastNudge[contact.publicKeyHex] = now
        contactViewModel.nudge(contact.publicKeyHex) { sent ->
            android.widget.Toast.makeText(context, if (sent) nudgeSent else nudgeUnreachable, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    ContactsScreen(
        contacts = contactState.contacts,
        isLoading = contactState.isLoading,
        error = contactState.error,
        paddingValues = paddingValues,
        onContactClick = onContactClick,
        onContactLongPress = { selected = it },
        onAddContact = onAddContact,
        onOpenScanner = onOpenScanner,
        onCall = call,
        onNudge = nudge
    )

    // Relu dans l'état à chaque recomposition : un renommage s'y voit aussitôt.
    selected
        ?.let { key -> contactState.contacts.find { it.publicKeyHex == key } }
        ?.let { contact ->
            val key = contact.publicKeyHex
            ContactActionsDialog(
                contact = contact,
                onDismiss = { selected = null },
                onRename = { contactViewModel.rename(key, it) },
                onBlock = { contactViewModel.block(key) },
                onUnblock = { contactViewModel.unblock(key) },
                onSetPrivate = { contactViewModel.setPrivate(key, it) },
                onDelete = { contactViewModel.deleteWithConversation(key) },
                onConfirmTrust = { contactViewModel.confirmTrust(key) }
            )
        }
}

/**
 * ContactsScreen - Le carnet. Un appui ouvre la conversation ; un appui
 * long propose de renommer, bloquer ou supprimer.
 */
@Composable
fun ContactsScreen(
    contacts: List<Contact>,
    isLoading: Boolean,
    error: String?,
    paddingValues: PaddingValues,
    onContactClick: (String) -> Unit = {},
    onContactLongPress: (String) -> Unit = {},
    onAddContact: () -> Unit = {},
    onOpenScanner: () -> Unit = {},
    onCall: (Contact) -> Unit = {},
    onNudge: (Contact) -> Unit = {}
) {
    val bottomSpace = LocalBottomBarSpace.current
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .screenBackground()
            .padding(paddingValues)
    ) {
        ScreenTitle(stringResource(R.string.contacts_title))

        when {
            isLoading -> CenteredNote(stringResource(R.string.common_loading))
            error != null -> CenteredNote(error, color = Ink.Brick)
            contacts.isEmpty() -> EmptyState(
                icon = { Icon(Icons.Outlined.Person, null, tint = Ink.LineStrong, modifier = Modifier.size(72.dp)) },
                title = stringResource(R.string.contacts_empty),
                body = stringResource(R.string.conversations_empty_body),
                action = stringResource(R.string.add_contact),
                onAction = onAddContact
            )
            else -> {
                val sorted = contacts.sortedBy { it.displayLabel.lowercase() }
                val groups = listOf(
                    R.string.contacts_group_confirmed to sorted.filter { it.isTrusted && !it.isBlocked },
                    R.string.contacts_group_unconfirmed to sorted.filter { !it.isTrusted && !it.isBlocked },
                    R.string.contacts_group_blocked to sorted.filter { it.isBlocked }
                ).filter { it.second.isNotEmpty() }
                LazyColumn(contentPadding = PaddingValues(top = 4.dp, bottom = bottomSpace + 16.dp)) {
                    item {
                        GroupCard {
                            CardRow(Icons.Outlined.QrCodeScanner, Ink.TileSage, stringResource(R.string.contacts_scan), onClick = onOpenScanner)
                            CardRow(Icons.Outlined.QrCode2, Ink.TileTeal, stringResource(R.string.contacts_show_qr), onClick = onAddContact)
                            CardRow(Icons.Outlined.Share, Ink.TileMauve, stringResource(R.string.share_app), onClick = { com.whisper.files.ShareApp.launch(context) })
                        }
                        Spacer(Modifier.height(14.dp))
                    }
                    item {
                        GroupCard {
                            groups.forEachIndexed { i, (label, list) ->
                                GroupLabel(
                                    stringResource(label),
                                    color = when (label) {
                                        R.string.contacts_group_confirmed -> Ink.SageLight
                                        R.string.contacts_group_unconfirmed -> Ink.Ochre
                                        else -> Ink.Brick
                                    },
                                    modifier = Modifier.padding(top = if (i == 0) 4.dp else 10.dp)
                                )
                                list.forEach { contact ->
                                    val reachable = contact.isTrusted && !contact.isBlocked
                                    ContactRow(
                                        contact,
                                        onClick = { onContactClick(contact.publicKeyHex) },
                                        onLongClick = { onContactLongPress(contact.publicKeyHex) },
                                        onNudge = if (reachable) { { onNudge(contact) } } else null,
                                        onCall = if (reachable) { { onCall(contact) } } else null
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Nom et empreinte courte ; le groupe dit déjà l'état. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContactRow(
    contact: Contact,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onNudge: (() -> Unit)? = null,
    onCall: (() -> Unit)? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Avatar(contact.displayName, size = 46, photo = contact.photo)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    contact.displayLabel,
                    color = Ink.Chalk,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                // Vérifié en personne (1.3.7) : codes de sécurité scannés face à face.
                if (contact.isVerified) {
                    Icon(
                        Icons.Outlined.VerifiedUser,
                        stringResource(R.string.contact_verified),
                        tint = Ink.SageLight,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Text(
                ContactKeyParser.fingerprint(contact.publicKeyHex),
                color = Ink.Ash,
                style = MaterialTheme.typography.bodySmall
            )
        }
        // Le signe d'abord, l'appel au coin.
        if (onNudge != null) {
            androidx.compose.material3.IconButton(onClick = onNudge) {
                Icon(Icons.Outlined.WavingHand, stringResource(R.string.nudge_action), tint = Ink.ChalkSoft, modifier = Modifier.size(22.dp))
            }
        }
        if (onCall != null) {
            androidx.compose.material3.IconButton(onClick = onCall) {
                Icon(Icons.Rounded.Call, stringResource(R.string.call_action), tint = Ink.SageLight, modifier = Modifier.size(22.dp))
            }
        }
    }
}

/**
 * CallsScreen - Écran appels (stub)
 */
@Composable
fun CallsScreen(paddingValues: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Calls",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text("Coming soon", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// DiscoverScreen a été retiré d'ici : la version complète de la Phase 2 vit dans
// ui/screens/DiscoverAndPinScreens.kt. Deux fonctions top-level de même nom dans
// le package com.whisper.ui.screens = erreur de redéclaration.
