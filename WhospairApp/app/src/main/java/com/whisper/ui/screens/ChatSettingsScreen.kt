package com.whisper.ui.screens

import com.whisper.ui.components.CardRow
import com.whisper.ui.components.GroupCard
import com.whisper.ui.components.glass
import com.whisper.ui.components.screenBackground
import com.whisper.ui.icons.outlined.Block
import com.whisper.ui.icons.outlined.DeleteOutline
import com.whisper.ui.icons.outlined.DeleteForever
import com.whisper.ui.icons.outlined.VerifiedUser
import androidx.compose.foundation.shape.CircleShape
import com.whisper.theme.Ink
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import com.whisper.ui.icons.Icons
import com.whisper.ui.icons.automirrored.rounded.ArrowBack
import com.whisper.ui.icons.outlined.Edit
import com.whisper.ui.icons.outlined.Lock
import com.whisper.ui.icons.outlined.VisibilityOff
import com.whisper.ui.icons.outlined.Tag
import com.whisper.ui.icons.outlined.WavingHand
import com.whisper.alerts.InAppAlerts
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.whisper.ui.components.PrivateTextField
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.style.TextOverflow
import com.whisper.ui.components.PhotoViewer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.whisper.R
import com.whisper.models.Contact
import com.whisper.repositories.ContactRepository
import com.whisper.ui.lock.PinPad
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Paramètres d'UNE conversation : nom local, confiance, blocage, suppression.
 * Tout ce qui s'y fait reste sur ce téléphone ; le contact n'en est pas averti.
 */
@Composable
fun ChatSettingsScreen(
    contact: Contact,
    safetyNumber: String?,
    onBack: () -> Unit,
    onRename: (String) -> Unit,
    onConfirmTrust: () -> Unit,
    safetyCode: String?,
    onVerifyScanned: (String) -> Boolean,
    onWipeEnable: () -> Unit,
    onWipeDisable: () -> Unit,
    onWipeAll: () -> Unit,
    onNudge: ((Boolean) -> Unit) -> Unit,
    onBlock: () -> Unit,
    onUnblock: () -> Unit,
    onSetPrivate: (Boolean) -> Unit,
    onDelete: () -> Unit,
    readUnlocked: Boolean,
    checkingCode: Boolean,
    codeResult: Boolean?,
    onEnableReadLock: (CharArray) -> Unit,
    onUnlockReading: (CharArray) -> Unit,
    onDisableReadLock: (CharArray) -> Unit,
    onResetCode: () -> Unit
) {
    var name by remember(contact.publicKeyHex) { mutableStateOf(contact.displayName) }
    var askDelete by remember { mutableStateOf(false) }
    var verifying by remember { mutableStateOf(false) }
    var askWipe by remember { mutableStateOf(false) }
    var codeAction by remember { mutableStateOf<CodeAction?>(null) }
    val muted = Ink.Ash
    val accent = Ink.Sage
    val danger = Ink.Brick

    Column(
        Modifier
            .fillMaxSize()
            .screenBackground()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp)
    ) {
        Box(
            Modifier
                .padding(start = 12.dp, top = 12.dp)
                .size(46.dp)
                .glass(CircleShape, elevation = 6.dp)
                .clickable(onClickLabel = stringResource(R.string.back), onClick = onBack),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back), tint = Ink.Chalk)
        }

        // Le contact en grand ; la photo s'agrandit d'un appui.
        var viewing by remember { mutableStateOf(false) }
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(if (contact.photo != null) Modifier.clickable { viewing = true } else Modifier) {
                Avatar(contact.displayName, size = 108, photo = contact.photo)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                contact.displayLabel,
                color = Ink.Chalk,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            when {
                contact.isBlocked -> Tag(stringResource(R.string.contact_blocked), Ink.Brick, Ink.BrickDim)
                contact.isTrusted -> Text(
                    stringResource(R.string.settings_trusted),
                    color = Ink.SageLight,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .background(Ink.Sage.copy(alpha = 0.14f), RoundedCornerShape(50))
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                )
                else -> Tag(stringResource(R.string.contact_unconfirmed), Ink.Ochre, Ink.OchreDim)
            }
        }
        if (viewing) contact.photo?.let { PhotoViewer(it, contact.displayLabel) { viewing = false } }
        Spacer(Modifier.height(20.dp))

        // Faire signe : contacts confirmés seulement, comme les appels.
        if (contact.isTrusted && !contact.isBlocked) {
            GroupCard { NudgeSection(onNudge) }
            Spacer(Modifier.height(14.dp))
        }

        // Nom
        GroupCard {
            CardRow(Icons.Outlined.Edit, Ink.TileSlate, stringResource(R.string.settings_rename), stringResource(R.string.settings_rename_hint))
            Row(
                Modifier.padding(start = 16.dp, end = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(48.dp)
                        .background(Ink.Slate, RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    PrivateTextField(
                        value = name,
                        onValueChange = { name = it },
                        maxLength = ContactRepository.MAX_NAME_LENGTH,
                        placeholder = "User ${contact.publicKeyHex.take(8)}",
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                TextButton(
                    onClick = { onRename(name) },
                    enabled = name.trim() != contact.displayName
                ) { Text(stringResource(R.string.settings_save)) }
            }
        }
        Spacer(Modifier.height(14.dp))

        // Lecture et mode privé
        GroupCard {
            CardRow(
                Icons.Outlined.Lock, Ink.TileSage,
                stringResource(R.string.read_lock_title),
                stringResource(
                    when {
                        !contact.hasReadLock -> R.string.read_lock_off_hint
                        readUnlocked -> R.string.read_lock_open_hint
                        else -> R.string.read_lock_on_hint
                    }
                )
            )
            Row(Modifier.fillMaxWidth().padding(end = 8.dp), horizontalArrangement = Arrangement.End) {
                when {
                    !contact.hasReadLock -> TextButton(onClick = { codeAction = CodeAction.ENABLE }) {
                        Text(stringResource(R.string.read_lock_enable), color = accent)
                    }
                    !readUnlocked -> TextButton(onClick = { codeAction = CodeAction.UNLOCK }) {
                        Text(stringResource(R.string.read_lock_unlock), color = accent)
                    }
                    else -> TextButton(onClick = { codeAction = CodeAction.DISABLE }) {
                        Text(stringResource(R.string.read_lock_disable))
                    }
                }
            }
            if (!contact.isBlocked) {
                CardRow(
                    Icons.Outlined.VisibilityOff, Ink.TileMauve,
                    stringResource(R.string.private_mode_title),
                    stringResource(if (contact.isPrivate) R.string.private_mode_on_hint else R.string.private_mode_off_hint)
                )
                Row(Modifier.fillMaxWidth().padding(end = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { onSetPrivate(!contact.isPrivate) }) {
                        if (contact.isPrivate) Text(stringResource(R.string.private_mode_disable))
                        else Text(stringResource(R.string.private_mode_enable), color = accent)
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))

        // Confiance
        GroupCard {
            CardRow(Icons.Outlined.Tag, Ink.TileTeal, stringResource(R.string.settings_safety_number), stringResource(R.string.settings_safety_hint))
            safetyNumber?.let {
                Text(
                    it,
                    color = Ink.Chalk,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 68.dp, end = 16.dp, bottom = 6.dp)
                )
            }
            if (!contact.isTrusted) {
                Row(Modifier.fillMaxWidth().padding(end = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onConfirmTrust) {
                        Text(stringResource(R.string.settings_confirm_trust), color = accent)
                    }
                }
            }
            // Vérification en personne (1.3.7) : les deux codes scannés face à face.
            if (contact.isVerified) {
                CardRow(Icons.Outlined.VerifiedUser, Ink.TileSage, stringResource(R.string.verify_done))
            } else if (safetyCode != null) {
                CardRow(
                    Icons.Outlined.VerifiedUser, Ink.TileTeal,
                    stringResource(R.string.verify_title), stringResource(R.string.verify_hint),
                    onClick = { verifying = true }
                )
            }
        }
        Spacer(Modifier.height(14.dp))

        // Effacement à deux (1.3.7) : actif seulement si les deux y consentent.
        GroupCard {
            CardRow(
                Icons.Outlined.DeleteOutline, Ink.TileSlate,
                stringResource(R.string.wipe_title),
                stringResource(
                    when (contact.wipeState) {
                        Contact.WIPE_ASKED -> R.string.wipe_asked
                        Contact.WIPE_TO_ANSWER -> R.string.wipe_to_answer
                        Contact.WIPE_ON -> R.string.wipe_on
                        else -> R.string.wipe_off
                    }
                )
            )
            Row(Modifier.fillMaxWidth().padding(end = 8.dp), horizontalArrangement = Arrangement.End) {
                when (contact.wipeState) {
                    Contact.WIPE_ASKED -> TextButton(onClick = onWipeDisable) { Text(stringResource(R.string.wipe_cancel)) }
                    Contact.WIPE_TO_ANSWER -> {
                        TextButton(onClick = onWipeDisable) { Text(stringResource(R.string.wipe_refuse), color = danger) }
                        TextButton(onClick = onWipeEnable) { Text(stringResource(R.string.wipe_accept), color = accent) }
                    }
                    Contact.WIPE_ON -> TextButton(onClick = onWipeDisable) { Text(stringResource(R.string.wipe_disable)) }
                    else -> TextButton(onClick = onWipeEnable) { Text(stringResource(R.string.wipe_propose), color = accent) }
                }
            }
            if (contact.wipeState == Contact.WIPE_ON) {
                CardRow(Icons.Outlined.DeleteForever, Ink.TileBrick, stringResource(R.string.wipe_now), danger = true, onClick = { askWipe = true })
            }
        }
        Spacer(Modifier.height(14.dp))

        // Blocage et suppression
        GroupCard {
            if (contact.isBlocked) {
                CardRow(Icons.Outlined.Block, Ink.TileGrey, stringResource(R.string.chat_unblock), onClick = onUnblock)
            } else {
                CardRow(Icons.Outlined.Block, Ink.TileBrick, stringResource(R.string.chat_block), danger = true, onClick = onBlock)
            }
            CardRow(Icons.Outlined.DeleteOutline, Ink.TileBrick, stringResource(R.string.settings_delete), danger = true, onClick = { askDelete = true })
        }
    }

    codeAction?.let { action ->
        ReadCodeDialog(
            action = action,
            checking = checkingCode,
            result = codeResult,
            onSubmit = { code ->
                when (action) {
                    CodeAction.ENABLE -> onEnableReadLock(code)
                    CodeAction.UNLOCK -> onUnlockReading(code)
                    CodeAction.DISABLE -> onDisableReadLock(code)
                }
            },
            onReset = onResetCode,
            onDismiss = { codeAction = null }
        )
    }

    if (askWipe) {
        AlertDialog(
            onDismissRequest = { askWipe = false },
            text = { Text(stringResource(R.string.wipe_confirm)) },
            confirmButton = {
                TextButton(onClick = { askWipe = false; onWipeAll() }) {
                    Text(stringResource(R.string.wipe_action), color = danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { askWipe = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    if (verifying && safetyCode != null) {
        VerifyInPerson(safetyCode, onVerifyScanned, onClose = { verifying = false })
    }

    if (askDelete) {
        AlertDialog(
            onDismissRequest = { askDelete = false },
            text = { Text(stringResource(R.string.settings_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = { askDelete = false; onDelete() }) {
                    Text(stringResource(R.string.settings_delete_action), color = danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { askDelete = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

/** Une vibration chez le contact, s'il a l'app ouverte. Un signe toutes les 10 s au plus. */
@Composable
private fun NudgeSection(onNudge: ((Boolean) -> Unit) -> Unit) {
    var sending by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(result) {
        if (result != null) {
            delay(InAppAlerts.NUDGE_INTERVAL_MS)
            result = null
        }
    }
    CardRow(
        Icons.Outlined.WavingHand, Ink.TileOchre,
        stringResource(R.string.nudge_title),
        stringResource(
            when (result) {
                true -> R.string.nudge_sent
                false -> R.string.nudge_unreachable
                null -> R.string.nudge_hint
            }
        )
    )
    Row(Modifier.fillMaxWidth().padding(end = 8.dp), horizontalArrangement = Arrangement.End) {
        TextButton(
            onClick = {
                sending = true
                onNudge { ok -> sending = false; result = ok }
            },
            enabled = !sending && result == null
        ) { Text(stringResource(R.string.nudge_action), color = if (!sending && result == null) Ink.SageLight else Ink.Ash) }
    }
}

internal enum class CodeAction { ENABLE, UNLOCK, DISABLE }

/**
 * Saisie du code de lecture sur le clavier de l'app, validée au 4e chiffre.
 * Activer demande deux saisies identiques. La fenêtre se ferme au succès,
 * reste ouverte sur un refus.
 */
@Composable
internal fun ReadCodeDialog(
    action: CodeAction,
    checking: Boolean,
    result: Boolean?,
    onSubmit: (CharArray) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    var first by remember { mutableStateOf<CharArray?>(null) }
    var mismatch by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) { onReset() }
    LaunchedEffect(result) { if (result == true) onDismiss() }
    DisposableEffect(Unit) { onDispose { first?.fill('\u0000'); onReset() } }

    val confirming = action == CodeAction.ENABLE && first != null
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        PinPad(
            title = stringResource(
                when {
                    confirming -> R.string.read_code_confirm
                    action == CodeAction.ENABLE -> R.string.read_code_new
                    else -> R.string.read_code_enter
                }
            ),
            hint = when {
                mismatch -> stringResource(R.string.read_code_mismatch)
                result == false -> stringResource(R.string.read_code_wrong)
                else -> ""
            },
            hintIsError = mismatch || result == false,
            busy = checking,
            resetKey = Triple(confirming, attempt, result),
            autoLength = ContactRepository.READ_CODE_LENGTH,
            onSubmit = { code ->
                attempt++
                val earlier = first
                when {
                    action == CodeAction.ENABLE && earlier == null -> {
                        first = code
                        mismatch = false
                    }
                    action == CodeAction.ENABLE -> {
                        first = null
                        val same = earlier!!.contentEquals(code)
                        earlier.fill('\u0000')
                        if (same) onSubmit(code) else { code.fill('\u0000'); mismatch = true }
                    }
                    else -> onSubmit(code)
                }
            }
        )
    }
}

/**
 * Vérification en personne (1.3.7) : notre code de sécurité en QR, et le
 * scanner pour le sien. Identiques : personne ne s'est glissé entre nous.
 */
@Composable
private fun VerifyInPerson(code: String, onScanned: (String) -> Boolean, onClose: () -> Unit) {
    var scanning by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Boolean?>(null) }
    androidx.activity.compose.BackHandler { if (scanning) scanning = false else onClose() }
    if (scanning) {
        com.whisper.ui.contacts.QrScannerScreen(
            onScanned = { text -> result = onScanned(text); scanning = false },
            onCancel = { scanning = false }
        )
        return
    }
    val qr = remember(code) { com.whisper.util.QrCodeGenerator.generateImageBitmap(code) }
    Column(
        Modifier
            .fillMaxSize()
            .screenBackground()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Text(stringResource(R.string.verify_title), style = MaterialTheme.typography.headlineSmall, color = Ink.Chalk)
        Text(stringResource(R.string.verify_body), color = Ink.ChalkSoft, style = MaterialTheme.typography.bodyMedium)
        qr?.let {
            Box(Modifier.background(Ink.Chalk, RoundedCornerShape(24.dp)).padding(18.dp)) {
                androidx.compose.foundation.Image(bitmap = it, contentDescription = null, modifier = Modifier.size(240.dp))
            }
        }
        result?.let { ok ->
            Text(
                stringResource(if (ok) R.string.verify_ok else R.string.verify_fail),
                color = if (ok) Ink.SageLight else Ink.Brick,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        if (result != true) {
            TextButton(onClick = { scanning = true }) { Text(stringResource(R.string.verify_scan), color = Ink.Sage) }
        }
        TextButton(onClick = onClose) { Text(stringResource(R.string.indicator_dismiss)) }
    }
}
