package com.whisper.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.whisper.ui.icons.Icons
import com.whisper.ui.icons.outlined.PushPin
import com.whisper.ui.icons.outlined.Block
import com.whisper.ui.icons.outlined.DeleteOutline
import com.whisper.ui.icons.outlined.VerifiedUser
import com.whisper.ui.icons.outlined.Visibility
import com.whisper.ui.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.whisper.R
import com.whisper.models.Contact
import com.whisper.repositories.ContactRepository
import com.whisper.theme.Ink

/**
 * Appui long sur un contact, dans la liste des conversations comme dans le
 * carnet : renommer, confirmer, bloquer, supprimer avec la conversation. Les
 * mêmes gestes que les paramètres de la conversation, sans avoir à l'ouvrir.
 */
@Composable
fun ContactActionsDialog(
    contact: Contact,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onBlock: () -> Unit,
    onUnblock: () -> Unit,
    onDelete: () -> Unit,
    onConfirmTrust: (() -> Unit)? = null,
    onSetPrivate: ((Boolean) -> Unit)? = null,
    onSetPinned: ((Boolean) -> Unit)? = null
) {
    var name by remember(contact.publicKeyHex) { mutableStateOf(contact.displayName) }
    var askDelete by remember { mutableStateOf(false) }

    if (askDelete) {
        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = Ink.Raised,
            text = { Text(stringResource(R.string.settings_delete_confirm), color = Ink.ChalkSoft) },
            confirmButton = {
                TextButton(onClick = { onDelete(); onDismiss() }) {
                    Text(stringResource(R.string.settings_delete_action), color = Ink.Brick)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel), color = Ink.ChalkSoft) }
            }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink.Raised,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                com.whisper.ui.screens.Avatar(contact.displayName, size = 44, photo = contact.photo)
                Column {
                    Text(contact.displayLabel, style = MaterialTheme.typography.titleLarge, color = Ink.Chalk)
                    when {
                        contact.isBlocked -> Text(stringResource(R.string.contact_blocked), style = MaterialTheme.typography.bodySmall, color = Ink.Brick)
                        !contact.isTrusted -> Text(stringResource(R.string.contact_unconfirmed), style = MaterialTheme.typography.bodySmall, color = Ink.Ochre)
                        contact.isPrivate -> Text(stringResource(R.string.private_mode_title), style = MaterialTheme.typography.bodySmall, color = Ink.Ash)
                    }
                }
            }
        },
        text = {
            Column {
                val changed = name.trim() != contact.displayName
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_rename), style = MaterialTheme.typography.bodySmall, color = Ink.ChalkSoft)
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp)
                                .height(52.dp)
                                .background(Ink.Bar, RoundedCornerShape(12.dp))
                                .border(1.dp, Ink.LineStrong, RoundedCornerShape(12.dp))
                                .padding(horizontal = 14.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            PrivateTextField(
                                value = name,
                                onValueChange = { name = it },
                    maxLength = ContactRepository.MAX_NAME_LENGTH,
                                placeholder = contact.displayLabel,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    TextButton(onClick = { onRename(name); onDismiss() }, enabled = changed) {
                        Text(stringResource(R.string.settings_save), color = if (changed) Ink.SageLight else Ink.Ash)
                    }
                }
                Text(
                    stringResource(R.string.settings_rename_hint),
                    color = Ink.Ash,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp)
                )

                HorizontalDivider(color = Ink.Line, modifier = Modifier.padding(vertical = 14.dp))

                if (!contact.isTrusted && onConfirmTrust != null) {
                    ActionRow(Icons.Outlined.VerifiedUser, stringResource(R.string.confirm_identity), Ink.SageLight, Ink.SageLight) {
                        onConfirmTrust(); onDismiss()
                    }
                }
                // Épingler (1.3.8) : la conversation reste en haut de la liste.
                if (onSetPinned != null) {
                    ActionRow(
                        Icons.Outlined.PushPin,
                        stringResource(if (contact.isPinned) R.string.conv_unpin else R.string.conv_pin),
                        Ink.SageLight, Ink.Chalk
                    ) { onSetPinned(!contact.isPinned); onDismiss() }
                }
                if (!contact.isBlocked && onSetPrivate != null) {
                    if (contact.isPrivate) {
                        ActionRow(Icons.Outlined.Visibility, stringResource(R.string.private_mode_disable), Ink.ChalkSoft, Ink.Chalk) {
                            onSetPrivate(false); onDismiss()
                        }
                    } else {
                        ActionRow(Icons.Outlined.VisibilityOff, stringResource(R.string.private_mode_enable), Ink.SageLight, Ink.Chalk) {
                            onSetPrivate(true); onDismiss()
                        }
                    }
                }
                if (contact.isBlocked) {
                    ActionRow(Icons.Outlined.Block, stringResource(R.string.chat_unblock), Ink.ChalkSoft, Ink.Chalk) {
                        onUnblock(); onDismiss()
                    }
                } else {
                    ActionRow(Icons.Outlined.Block, stringResource(R.string.chat_block), Ink.Brick, Ink.Chalk) {
                        onBlock(); onDismiss()
                    }
                }
                ActionRow(Icons.Outlined.DeleteOutline, stringResource(R.string.delete_contact_and_chat), Ink.Brick, Ink.Brick) {
                    askDelete = true
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close), color = Ink.ChalkSoft) }
        }
    )
}

/** Une action : icône à gauche, verbe à droite, toute la ligne se touche. */
@Composable
private fun ActionRow(icon: ImageVector, label: String, iconTint: Color, textColor: Color, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(icon, null, tint = iconTint, modifier = Modifier.size(22.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = textColor)
    }
}
