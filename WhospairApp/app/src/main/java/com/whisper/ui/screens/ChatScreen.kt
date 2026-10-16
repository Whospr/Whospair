package com.whisper.ui.screens

import com.whisper.ui.icons.rounded.DoneAll
import com.whisper.ui.icons.outlined.DeleteForever
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import com.whisper.ui.icons.outlined.ContentCopy
import com.whisper.ui.icons.outlined.VisibilityOff
import com.whisper.ui.icons.outlined.Visibility
import androidx.compose.ui.draw.clip
import com.whisper.ui.components.glass
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.whisper.theme.Ink
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import com.whisper.ui.icons.Icons
import androidx.compose.material3.TextButton
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.setValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.whisper.R
import com.whisper.ui.icons.outlined.DeleteOutline
import com.whisper.ui.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.combinedClickable
import com.whisper.files.FilePayload
import com.whisper.ui.components.ForwardDialog
import com.whisper.ui.components.ShareFileDialog
import com.whisper.files.TempFileProvider
import com.whisper.files.FileNotice
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.whisper.files.FileSource
import com.whisper.security.SessionLifecycle
import com.whisper.ui.components.SendFileDialog
import com.whisper.ui.components.FileViewer
import com.whisper.ui.components.FileUi
import com.whisper.ui.components.ChatWallpaper
import androidx.hilt.navigation.compose.hiltViewModel
import com.whisper.models.Message
import com.whisper.ui.components.DateDivider
import com.whisper.ui.components.MessageBubble
import com.whisper.ui.components.dateGroupLabel
import com.whisper.ui.components.MessageInput
import com.whisper.viewmodels.ChatViewModel
import android.Manifest
import android.widget.Toast
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.whisper.models.MessageStatus
import com.whisper.ui.components.VoiceUi
import com.whisper.util.rememberPermission
import com.whisper.voice.VoicePayload
import com.whisper.call.CallEvent
import com.whisper.ui.call.CallEventRow
import com.whisper.ui.components.formatMessageTime
import com.whisper.voice.VoicePlayer
import com.whisper.voice.VoiceRecorder
import kotlinx.coroutines.launch
import com.whisper.ui.components.QuoteUi
import com.whisper.util.ReplyPayload
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.whisper.ui.icons.automirrored.rounded.ArrowBack
import com.whisper.ui.icons.rounded.MoreVert
import com.whisper.ui.icons.rounded.Call
import com.whisper.ui.icons.rounded.Share
import com.whisper.ui.icons.rounded.Visibility
import com.whisper.ui.call.CallViewModel
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay


/**
 * ChatScreenContainer - Wrapper avec injection de dépendances
 */
@Composable
fun ChatScreenContainer(
    contactPublicKeyHex: String,
    paddingValues: PaddingValues,
    onBackClick: () -> Unit = {},
    viewModel: ChatViewModel = hiltViewModel()
) {
    LaunchedEffect(contactPublicKeyHex) {
        viewModel.initializeChat(contactPublicKeyHex)
    }

    val state = viewModel.state.collectAsState()
    var showSettings by remember { mutableStateOf(false) }
    // Le clavier ne suit pas vers les paramètres de la conversation, ni au retour.
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    LaunchedEffect(showSettings) {
        focus.clearFocus(force = true)
        keyboard?.hide()
    }

    // Vocaux : enregistreur et lecteur vivent avec l'écran, et s'arrêtent avec lui.
    val scope = rememberCoroutineScope()
    val recorder = remember { VoiceRecorder(scope) }
    val player = remember { VoicePlayer(scope) }
    DisposableEffect(Unit) { onDispose { recorder.cancel(); player.stop() } }
    val recording by recorder.recording.collectAsState()
    val elapsed by recorder.elapsedMs.collectAsState()
    val playing by player.playing.collectAsState()
    val progress by player.progress.collectAsState()
    val context = LocalContext.current
    val micDenied = stringResource(R.string.mic_denied)
    val mic = rememberPermission(Manifest.permission.RECORD_AUDIO) { granted ->
        if (granted) { player.stop(); recorder.start() }
        else Toast.makeText(context, micDenied, Toast.LENGTH_LONG).show()
    }
    // Appel : contact confirmé seulement, micro demandé au premier appel.
    val callManager = hiltViewModel<CallViewModel>().manager
    val notConfirmed = stringResource(R.string.call_not_confirmed)
    val callMicDenied = stringResource(R.string.call_mic_denied)
    val callMic = rememberPermission(Manifest.permission.RECORD_AUDIO) { granted ->
        val contact = state.value.contact
        if (granted && contact != null) { recorder.cancel(); player.stop(); callManager.call(contact) }
        else if (!granted) Toast.makeText(context, callMicDenied, Toast.LENGTH_LONG).show()
    }
    val startCall: () -> Unit = start@{
        val contact = state.value.contact ?: return@start
        if (!contact.isTrusted) {
            Toast.makeText(context, notConfirmed, Toast.LENGTH_LONG).show()
        } else if (callMic.granted) {
            recorder.cancel(); player.stop(); callManager.call(contact)
        } else callMic.request()
    }
    // Comme dans les paramètres : activer (masqué en quittant la conversation),
    // lire pour cette visite, ou désactiver — toujours avec le code.
    var headerCode by remember { mutableStateOf<CodeAction?>(null) }
    val maskClick: () -> Unit = mask@{
        val contact = state.value.contact ?: return@mask
        headerCode = when {
            !contact.hasReadLock -> CodeAction.ENABLE
            state.value.isMasked -> CodeAction.UNLOCK
            else -> CodeAction.DISABLE
        }
    }
    headerCode?.let { action ->
        ReadCodeDialog(
            action = action,
            checking = state.value.checkingCode,
            result = state.value.codeResult,
            onSubmit = { code ->
                when (action) {
                    CodeAction.ENABLE -> viewModel.enableReadLock(code)
                    CodeAction.UNLOCK -> viewModel.unlockReading(code)
                    CodeAction.DISABLE -> viewModel.disableReadLock(code)
                }
            },
            onReset = viewModel::resetCodeResult,
            onDismiss = { headerCode = null }
        )
    }
    val sendRecording: () -> Unit = {
        scope.launch { recorder.stop()?.let { viewModel.sendVoice(it.bytes, it.durationMs) } }
    }
    // Une minute atteinte : le vocal part de lui-même — seulement celui
    // que l'écran montre en cours.
    val full = recording && elapsed >= VoiceRecorder.MAX_MS
    LaunchedEffect(full) {
        if (full) sendRecording()
    }

    LaunchedEffect(state.value.isDeleted) {
        if (state.value.isDeleted) onBackClick()
    }

    // Fichiers (phase 16) : sélecteur de documents d'Android (AOSP), lu en
    // mémoire ; ouvert, déchiffré en mémoire aussi. Rien en clair sur le disque.
    var picked by remember { mutableStateOf<FileSource.Picked?>(null) }
    var opened by remember { mutableStateOf<Pair<FilePayload.Header, ByteArray>?>(null) }
    val fileTooBig = stringResource(R.string.file_too_big)
    val fileUnreadable = stringResource(R.string.file_unreadable)
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        SessionLifecycle.afterSystemDialog()
        if (uri != null) scope.launch {
            picked = try {
                FileSource.read(context, uri)
            } catch (e: FileSource.TooBig) {
                Toast.makeText(context, fileTooBig, Toast.LENGTH_LONG).show(); null
            } catch (e: Exception) {
                Toast.makeText(context, fileUnreadable, Toast.LENGTH_LONG).show(); null
            }
        }
    }
    DisposableEffect(Unit) { onDispose { picked?.data?.fill(0); opened?.second?.fill(0) } }

    // Partager (phase 16) : seulement ce que l'envoyeur a permis, et chaque
    // geste vers l'extérieur le prévient (FileNotice).
    var sharing by remember { mutableStateOf<Pair<FilePayload.Header, ByteArray>?>(null) }
    var forwarding by remember { mutableStateOf<Pair<FilePayload.Header, ByteArray>?>(null) }
    var saving by remember { mutableStateOf<Pair<FilePayload.Header, ByteArray>?>(null) }
    var targets by remember { mutableStateOf<List<com.whisper.models.Contact>>(emptyList()) }
    val outside = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        SessionLifecycle.afterSystemDialog()
    }
    val saveTo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        SessionLifecycle.afterSystemDialog()
        val file = saving
        saving = null
        if (uri != null && file != null) scope.launch {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(file.second) } != null }.getOrDefault(false)
            }
            file.second.fill(0)
            if (ok) viewModel.notifySender(file.first, FileNotice.Event.SAVED)
            else Toast.makeText(context, fileUnreadable, Toast.LENGTH_LONG).show()
        }
    }
    val chooserTitle = stringResource(R.string.file_open_with)
    // Hors de Whisper : un prêt en mémoire, retiré au retour dans l'app (TempFileProvider).
    val openElsewhere: (FilePayload.Header, ByteArray) -> Unit = { header, data ->
        val uri = TempFileProvider.offer(context, data, header.name, header.mime)
        val view = android.content.Intent(android.content.Intent.ACTION_VIEW)
            .setDataAndType(uri, header.mime)
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        view.clipData = android.content.ClipData.newRawUri(header.name, uri)
        SessionLifecycle.beforeSystemDialog(SessionLifecycle.FILE_PICKER_GRACE_MS)
        outside.launch(android.content.Intent.createChooser(view, chooserTitle).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION))
        viewModel.notifySender(header, FileNotice.Event.OPENED_ELSEWHERE)
    }
    val shareOutside: (FilePayload.Header, ByteArray) -> Unit = { header, data ->
        val uri = TempFileProvider.offer(context, data, header.name, header.mime)
        val send = android.content.Intent(android.content.Intent.ACTION_SEND)
            .setType(header.mime)
            .putExtra(android.content.Intent.EXTRA_STREAM, uri)
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = android.content.ClipData.newRawUri(header.name, uri)
        SessionLifecycle.beforeSystemDialog(SessionLifecycle.FILE_PICKER_GRACE_MS)
        outside.launch(android.content.Intent.createChooser(send, null).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION))
        viewModel.notifySender(header, FileNotice.Event.SHARED)
    }

    ChatScreen(
        state = state.value,
        paddingValues = paddingValues,
        draftKey = contactPublicKeyHex,
        header = {
            ChatHeader(
                contactName = state.value.contact?.displayLabel ?: "Contact",
                avatarName = state.value.contact?.displayName.orEmpty(),
                avatarPhoto = state.value.contact?.photo,
                isOnline = state.value.isPeerOnline,
                onBackClick = onBackClick,
                onCallClick = startCall.takeIf { state.value.contact?.isBlocked == false },
                onSettingsClick = { showSettings = true },
                onMaskClick = maskClick.takeIf { state.value.contact?.isBlocked == false },
                masked = state.value.contact?.takeIf { it.hasReadLock }?.let { state.value.isMasked }
            )
        },
        onSendMessage = { message ->
            viewModel.sendMessage(message)
        },
        onTTLChange = { ttl ->
            viewModel.setSelectedTTL(ttl)
        },
        onDismissError = viewModel::clearError,
        onConfirmTrust = viewModel::confirmTrust,
        onWipeAccept = viewModel::wipeEnable,
        onWipeRefuse = viewModel::wipeDisable,
        onBlock = viewModel::block,
        onUnblock = viewModel::unblock,
        onNudge = viewModel::nudge,
        onLeavePrivate = { viewModel.setPrivate(false) },
        onOpenSettings = { showSettings = true },
        onRetry = viewModel::retry,
        onAttach = {
            SessionLifecycle.beforeSystemDialog(SessionLifecycle.FILE_PICKER_GRACE_MS)
            pickFile.launch(arrayOf("*/*"))
        },
        onDeleteMessages = viewModel::deleteMessages,
        onDeleteForEveryone = viewModel::deleteForEveryone,
        onCancelFile = viewModel::cancelFile,
        onShareFile = { id ->
            scope.launch {
                val file = viewModel.openFile(id)
                when {
                    file == null -> Toast.makeText(context, fileUnreadable, Toast.LENGTH_LONG).show()
                    file.first.shareable -> { sharing?.second?.fill(0); sharing = file }
                    else -> file.second.fill(0)
                }
            }
        },
        onOpenFile = { id ->
            scope.launch {
                val file = viewModel.openFile(id)
                if (file == null) {
                    Toast.makeText(context, fileUnreadable, Toast.LENGTH_LONG).show()
                    return@launch
                }
                opened?.second?.fill(0)
                opened = file
                // Vue unique : effacé et annoncé à l'OUVERTURE. À la fermeture,
                // c'était trop tard : l'app tuée en arrière-plan ne fermait
                // jamais le lecteur, et le fichier restait rouvrable.
                if (file.first.viewOnce) {
                    viewModel.notifySender(file.first, FileNotice.Event.VIEWED)
                    viewModel.forgetFile(file.first.fileId)
                }
            }
        },
        voice = VoiceControls(
            playing = playing,
            progress = progress,
            onToggle = player::toggle,
            recording = recording,
            elapsedMs = elapsed,
            onRecord = { if (mic.granted) { player.stop(); recorder.start() } else mic.request() },
            onCancel = recorder::cancel,
            onSend = sendRecording
        )
    )

    picked?.let { file ->
        SendFileDialog(
            name = file.name,
            size = file.data.size.toLong(),
            onSend = { flags ->
                viewModel.sendFile(file.name, file.mime, file.data, flags)
                picked = null
            },
            onCancel = {
                file.data.fill(0)
                picked = null
            }
        )
    }
    opened?.let { (header, data) ->
        FileViewer(
            header, data,
            onDismiss = {
                data.fill(0)
                opened = null
            },
            onShare = { h, d -> sharing = h to d.copyOf() },
            onOpenElsewhere = openElsewhere
        )
    }
    sharing?.let { (header, data) ->
        ShareFileDialog(
            name = header.name,
            onForward = {
                sharing = null
                scope.launch {
                    targets = viewModel.forwardTargets()
                    forwarding = header to data
                }
            },
            onOutside = { sharing = null; shareOutside(header, data); data.fill(0) },
            onSave = {
                sharing = null
                saving = header to data
                SessionLifecycle.beforeSystemDialog(SessionLifecycle.FILE_PICKER_GRACE_MS)
                saveTo.launch(header.name)
            },
            onCancel = { sharing = null; data.fill(0) }
        )
    }
    forwarding?.let { (header, data) ->
        ForwardDialog(
            contacts = targets,
            onPick = { to ->
                forwarding = null
                viewModel.forwardFile(header, data, to.publicKeyHex)
                data.fill(0)
            },
            onCancel = { forwarding = null; data.fill(0) }
        )
    }

    val contact = state.value.contact
    if (showSettings && contact != null) {
        // Déclaré après celui de WhisperApp : Retour ferme d'abord les paramètres.
        BackHandler { showSettings = false }
        ChatSettingsScreen(
            contact = contact,
            safetyNumber = remember(contact.publicKeyHex, contact.signingPublicKeyHex) { viewModel.safetyNumber() },
            onBack = { showSettings = false },
            onRename = viewModel::rename,
            onConfirmTrust = viewModel::confirmTrust,
            safetyCode = remember(contact.publicKeyHex, contact.signingPublicKeyHex) { viewModel.safetyCode() },
            onVerifyScanned = viewModel::verifyScanned,
            onWipeEnable = viewModel::wipeEnable,
            onWipeDisable = viewModel::wipeDisable,
            onWipeAll = viewModel::wipeAll,
            onNudge = viewModel::nudge,
            onBlock = viewModel::block,
            onUnblock = viewModel::unblock,
            onSetPrivate = viewModel::setPrivate,
            onDelete = viewModel::deleteContact,
            readUnlocked = state.value.readUnlocked,
            checkingCode = state.value.checkingCode,
            codeResult = state.value.codeResult,
            onEnableReadLock = viewModel::enableReadLock,
            onUnlockReading = viewModel::unlockReading,
            onDisableReadLock = viewModel::disableReadLock,
            onResetCode = viewModel::resetCodeResult
        )
    }
}

/**
 * ChatScreen - Écran de chat principal
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    state: com.whisper.viewmodels.ChatViewState,
    paddingValues: PaddingValues,
    header: @Composable () -> Unit,
    onSendMessage: (String) -> Unit = {},
    onTTLChange: (Int) -> Unit = {},
    onDismissError: () -> Unit = {},
    onConfirmTrust: () -> Unit = {},
    onWipeAccept: () -> Unit = {},
    onWipeRefuse: () -> Unit = {},
    onBlock: () -> Unit = {},
    onUnblock: () -> Unit = {},
    onNudge: (((Boolean) -> Unit) -> Unit)? = null,
    onLeavePrivate: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onRetry: (Message) -> Unit = {},
    onOpenFile: (String) -> Unit = {},
    onShareFile: (String) -> Unit = {},
    onAttach: () -> Unit = {},
    onDeleteMessages: (List<String>) -> Unit = {},
    onDeleteForEveryone: (List<String>) -> Unit = {},
    onCancelFile: (FilePayload.Header, List<String>) -> Unit = { _, _ -> },
    voice: VoiceControls = VoiceControls(),
    /** Le contact : son brouillon survit à la fermeture de la conversation. */
    draftKey: String? = null
) {
    val messageInput = remember { mutableStateOf(draftKey?.let { Drafts[it] }.orEmpty()) }
    val setInput: (String) -> Unit = { text ->
        messageInput.value = text
        if (draftKey != null) Drafts[draftKey] = text
    }
    val emojiOpen = remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    // Bulle dont on affiche le temps restant (un appui l'ouvre, un autre la ferme).
    var expanded by remember { mutableStateOf<String?>(null) }
    // Sélection : un appui long commence, chaque appui ajoute ou retire.
    var selection by remember { mutableStateOf(setOf<String>()) }
    // Appui long : une fenêtre d'actions (copier, supprimer, pour tous) ; la
    // confirmation s'y affiche aussi. « Sélectionner plusieurs » garde la barre.
    var showActions by remember { mutableStateOf(false) }
    var multi by remember { mutableStateOf(false) }
    var copying by remember { mutableStateOf<String?>(null) }
    val selecting = selection.isNotEmpty()
    LaunchedEffect(selecting) { if (!selecting) multi = false }
    // Réponse en préparation (glisser une bulle vers la droite).
    var replyTo by remember { mutableStateOf<ChatRow?>(null) }
    val scope = rememberCoroutineScope()
    BackHandler(enabled = selecting) { selection = emptySet() }

    // L'encre pâlit avec le temps : une horloge douce, pas à chaque image.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }

    // Les morceaux d'un vocal ne font qu'une bulle. Un fichier reçu n'apparaît
    // qu'une fois complet : son nom en dit déjà beaucoup, et l'envoyeur peut
    // encore annuler — le destinataire n'aura alors rien vu.
    val rows = remember(state.messages, state.decryptedMessages, state.selfPublicKeyHex) {
        chatRows(state.messages, state.decryptedMessages).filterNot { row ->
            val h = row.file
            val theirs = !row.anchor.fromPublicKeyHex.equals(state.selfPublicKeyHex, ignoreCase = true)
            // Leur fichier incomplet, ou leur annulation : rien à voir ici.
            (h != null && theirs && row.fileDone < h.count) ||
                (theirs && row.notice?.event == FileNotice.Event.CANCELLED)
        }
    }

    // Un seul message texte choisi, lisible : on peut le copier (en entier ou en partie).
    val copyable = selection.singleOrNull()
        ?.let { k -> rows.find { it.key == k } }
        ?.takeIf { !state.isMasked && !it.isVoice && it.file == null && it.call == null && it.notice == null }
        ?.let { r -> state.decryptedMessages[r.anchor.id]?.let { clear -> ReplyPayload.parse(clear)?.body ?: clear } }

    // Une ligne disparue (TTL, supprimée) quitte la sélection.
    LaunchedEffect(rows) {
        val keys = rows.mapTo(HashSet()) { it.key }
        if (!keys.containsAll(selection)) selection = selection.filterTo(HashSet()) { it in keys }
        // Le message cité a expiré avant l'envoi : plus rien à citer.
        if (replyTo?.key?.let { it !in keys } == true) replyTo = null
    }

    // Auto-scroll au dernier message
    LaunchedEffect(rows.size) {
        if (rows.isNotEmpty()) {
            listState.animateScrollToItem(rows.size - 1)
        }
    }

    // Le fond couvre tout l'écran : en-tête et saisie flottent dessus.
    Box(Modifier.fillMaxSize().background(Ink.Night)) {
    if (!state.isLoading) ChatWallpaper(Modifier.matchParentSize())
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .imePadding()
    ) {
        if (selecting && multi) {
            SelectionBar(
                count = selection.size,
                onClose = { selection = emptySet() },
                onDelete = { showActions = true }
            )
        } else header()

        // Venu seul avec notre lien : comme un numéro non enregistré.
        val contact = state.contact
        if (contact != null && contact.isBlocked) {
            ContactBanner(stringResource(R.string.chat_blocked_banner)) {
                TextButton(onClick = onUnblock) { Text(stringResource(R.string.chat_unblock), color = Ink.SageLight) }
            }
        } else if (state.isMasked) {
            ContactBanner(stringResource(R.string.read_lock_banner)) {
                TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.read_lock_unlock), color = Ink.SageLight) }
            }
        } else if (contact != null && !contact.isTrusted) {
            ContactBanner(stringResource(R.string.chat_unconfirmed_banner)) {
                TextButton(onClick = onBlock) { Text(stringResource(R.string.chat_block), color = Ink.Brick) }
                TextButton(onClick = onConfirmTrust) { Text(stringResource(R.string.chat_confirm), color = Ink.SageLight) }
            }
        } else if (contact != null && contact.wipeState == com.whisper.models.Contact.WIPE_TO_ANSWER) {
            // Il propose l'effacement à deux : rien n'est actif sans notre accord.
            ContactBanner(stringResource(R.string.wipe_banner, contact.displayLabel)) {
                TextButton(onClick = onWipeRefuse) { Text(stringResource(R.string.wipe_refuse), color = Ink.Brick) }
                TextButton(onClick = onWipeAccept) { Text(stringResource(R.string.wipe_accept), color = Ink.SageLight) }
            }
        } else if (state.isPeerOutdated) {
            ContactBanner(stringResource(R.string.chat_peer_outdated)) {}
        } else if (contact != null && contact.isPrivate) {
            ContactBanner(stringResource(R.string.private_mode_banner)) {
                TextButton(onClick = onLeavePrivate) { Text(stringResource(R.string.private_mode_disable), color = Ink.SageLight) }
            }
        }

        if (state.isLoading) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(stringResource(R.string.common_loading), color = Ink.Ash)
            }
        } else {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
            LazyColumn(
                // Un appui sur le fil referme le panneau d'emojis (les bulles gardent leurs appuis).
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { emojiOpen.value = false } },
                state = listState,
                verticalArrangement = Arrangement.Bottom,
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                // Un séparateur seulement quand le jour change.
                itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
                    val message = row.anchor
                    val day = dateGroupLabel(message.timestamp)
                    if (index == 0 || dateGroupLabel(rows[index - 1].anchor.timestamp) != day) {
                        DateDivider(day)
                    }
                    val clip = row.clip
                    val call = row.call
                    val own = message.fromPublicKeyHex == state.selfPublicKeyHex
                    val file = row.file?.let { header ->
                        val sending = own && row.fileDone < header.count
                        FileUi(
                            header, row.fileDone, state.isMasked,
                            onCancel = if (sending && !selecting) { { onCancelFile(header, row.ids) } } else null
                        )
                    }
                    val notice = row.notice
                    val selected = row.key in selection
                    val toggle = { selection = if (selected) selection - row.key else selection + row.key }
                    val longPress = {
                        if (multi) toggle() else { selection = setOf(row.key); showActions = true }
                    }
                    if (notice != null || (call != null && !state.isMasked)) Box(
                        Modifier
                            .fillMaxWidth()
                            .background(if (selected) Ink.SageDim else Color.Transparent)
                            .combinedClickable(onClick = { if (multi) toggle() }, onLongClick = longPress)
                    ) {
                        if (notice != null) NoticeRow(notice, own, state.contact?.displayLabel.orEmpty(), state.isMasked, formatMessageTime(message.timestamp))
                        else if (call != null) CallEventRow(call, formatMessageTime(message.timestamp))
                    } else {
                    val clear = state.decryptedMessages[message.id]
                    val reply = if (state.isMasked || row.isVoice || row.file != null) null else ReplyPayload.parse(clear)
                    val quote = reply?.let { r ->
                        // "o" : cité par l'auteur de la réponse lui-même.
                        val quotedMine = r.quotedByAuthor == own
                        QuoteUi(
                            author = if (quotedMine) stringResource(R.string.reply_you) else contact?.displayLabel.orEmpty(),
                            snippet = r.snippet,
                            onClick = rows.indexOfFirst { r.quotedId in it.ids }.takeIf { it >= 0 && !selecting }?.let { target ->
                                { scope.launch { listState.animateScrollToItem(target) } }
                            }
                        )
                    }
                    MessageBubble(
                        message = message,
                        plainText = if (state.isMasked) stringResource(R.string.read_lock_masked)
                        else clear?.let { reply?.body ?: it } ?: stringResource(R.string.message_unreadable),
                        quote = quote,
                        onReply = if (!selecting && !state.isMasked && (clear != null || row.isVoice || row.file != null) && contact?.isBlocked != true && message.status != MessageStatus.FAILED) {
                            { replyTo = row }
                        } else null,
                        isOwn = message.fromPublicKeyHex == state.selfPublicKeyHex,
                        now = now,
                        showExpiry = expanded == row.key,
                        voice = if (row.isVoice && !state.isMasked) VoiceUi(
                            ready = clip != null,
                            durationMs = row.durationMs,
                            playing = voice.playing == row.key,
                            progress = voice.progress,
                            onToggle = { if (clip != null) voice.onToggle(row.key, clip, row.durationMs) }
                        ) else null,
                        file = file,
                        onShare = if (!selecting && file != null && !own && file.complete && file.header.shareable && !state.isMasked) {
                            { onShareFile(file.header.fileId) }
                        } else null,
                        selected = selected,
                        onLongClick = longPress,
                        onClick = {
                            when {
                                multi -> toggle()
                                // Fichier reçu et complet : l'ouvrir. Sinon, le temps restant.
                                file != null && !own && file.complete && !state.isMasked -> onOpenFile(file.header.fileId)
                                else -> expanded = if (expanded == row.key) null else row.key
                            }
                        },
                        onRetry = { onRetry(message) }
                    )
                    }
                }
            }
            }
        }

        // L'erreur s'affiche AU-DESSUS de la saisie : elle remplaçait toute
        // la conversation, qui disparaissait au premier envoi raté.
        state.error?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = Ink.Chalk,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .background(Ink.BrickDim, RoundedCornerShape(12.dp))
                    .clickable(onClick = onDismissError)
                    .padding(12.dp)
            )
        }

        // Bloqué : rien ne peut partir, la saisie disparaît.
        if (contact?.isBlocked != true) MessageInput(
            message = messageInput.value,
            onMessageChange = setInput,
            showEmoji = emojiOpen,
            onSend = {
                if (messageInput.value.isNotBlank()) {
                    val quoted = replyTo
                    onSendMessage(
                        if (quoted == null) messageInput.value
                        else ReplyPayload.encode(
                            quotedId = quoted.anchor.id,
                            quotedByAuthor = quoted.anchor.fromPublicKeyHex == state.selfPublicKeyHex,
                            snippet = snippetOf(quoted, state.decryptedMessages),
                            body = messageInput.value
                        )
                    )
                    setInput("")
                    replyTo = null
                }
            },
            replyTo = replyTo?.let { r ->
                val mine = r.anchor.fromPublicKeyHex == state.selfPublicKeyHex
                (if (mine) stringResource(R.string.reply_you) else contact?.displayLabel.orEmpty()) to
                    snippetOf(r, state.decryptedMessages)
            },
            onCancelReply = { replyTo = null },
            selectedTTL = state.selectedTTLMinutes,
            onTTLChange = onTTLChange,
            ttlPresets = state.ttlPresets,
            recording = voice.recording,
            recordingMs = voice.elapsedMs,
            onRecord = voice.onRecord,
            onCancelRecording = voice.onCancel,
            onSendRecording = voice.onSend,
            onAttach = onAttach,
            // Faire signe : contacts confirmés seulement, comme dans les paramètres.
            onNudge = onNudge.takeIf { contact?.isTrusted == true }
        )
    }
    }

    copying?.let { text ->
        CopyDialog(text, onDismiss = { copying = null; if (!multi) selection = emptySet() })
    }

    if (showActions) {
        val chosen = rows.filter { it.key in selection }
        // Pour tous : seulement si tout ce qui est choisi vient de nous.
        val allMine = chosen.isNotEmpty() && state.contact?.isBlocked != true &&
            chosen.all { it.anchor.fromPublicKeyHex.equals(state.selfPublicKeyHex, ignoreCase = true) }
        // null : les actions ; false / true : confirmer « supprimer » / « pour tous ».
        var confirm by remember { mutableStateOf<Boolean?>(null) }
        val close = { showActions = false; if (!multi) selection = emptySet() }
        AlertDialog(
            onDismissRequest = close,
            containerColor = Ink.Raised,
            title = {
                Text(
                    if (confirm == null) stringResource(R.string.selection_count, chosen.size)
                    else stringResource(R.string.delete_selected_title, chosen.size),
                    color = Ink.Chalk
                )
            },
            text = {
                if (confirm == null) Column {
                    copyable?.let { text ->
                        ActionLine(Icons.Outlined.ContentCopy, stringResource(R.string.copy_action), Ink.Chalk) {
                            showActions = false
                            copying = text
                        }
                    }
                    if (!multi) ActionLine(Icons.Rounded.DoneAll, stringResource(R.string.select_more), Ink.Chalk) {
                        multi = true
                        showActions = false
                    }
                    ActionLine(Icons.Outlined.DeleteOutline, stringResource(R.string.delete_selected_action), Ink.Brick) { confirm = false }
                    if (allMine) ActionLine(Icons.Outlined.DeleteForever, stringResource(R.string.delete_everyone_action), Ink.Brick) { confirm = true }
                } else Text(
                    stringResource(if (confirm == true) R.string.delete_everyone_body else R.string.delete_selected_body),
                    color = Ink.ChalkSoft
                )
            },
            confirmButton = {
                val forEveryone = confirm
                if (forEveryone != null) TextButton(onClick = {
                    val ids = chosen.flatMap { it.ids }
                    showActions = false
                    selection = emptySet()
                    if (forEveryone) onDeleteForEveryone(ids) else onDeleteMessages(ids)
                }) {
                    Text(
                        stringResource(if (forEveryone) R.string.delete_everyone_action else R.string.delete_selected_action),
                        color = Ink.Brick
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { if (confirm != null) confirm = null else close() }) {
                    Text(stringResource(R.string.common_cancel), color = Ink.ChalkSoft)
                }
            }
        )
    }
}

/**
 * En-tête du chat : le nom, « joignable » tant qu'un flux Tor est ouvert,
 * et Appeler (absent pour un contact bloqué).
 */
@Composable
fun ChatHeader(
    contactName: String,
    avatarName: String,
    avatarPhoto: ByteArray? = null,
    isOnline: Boolean,
    onBackClick: () -> Unit,
    onCallClick: (() -> Unit)?,
    onSettingsClick: () -> Unit,
    /** Le masquage de la lecture, sans passer par les paramètres ; null : pas de bouton. */
    onMaskClick: (() -> Unit)? = null,
    /** null : pas de masquage ; true : masqué ; false : masquage actif, lisible pour cette visite. */
    masked: Boolean? = null
) {
    // Une seule capsule qui flotte sur le fond : retour, le contact (un appui
    // ouvre sa fiche), puis appeler et plus — chacun à sa place, sans séparation.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .height(60.dp)
            .glass(RoundedCornerShape(30.dp))
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBackClick) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back), tint = Ink.Chalk)
        }
        Row(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(24.dp))
                .clickable(onClickLabel = stringResource(R.string.chat_settings), onClick = onSettingsClick)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Avatar(avatarName, size = 42, photo = avatarPhoto)
            Column(Modifier.weight(1f)) {
                Text(
                    contactName,
                    color = Ink.Chalk,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // Pas de « hors ligne » : sans flux, on ne sait pas — et on ne l'affirme pas.
                if (isOnline) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(7.dp).background(Ink.Sage, CircleShape))
                        Text(
                            stringResource(R.string.presence_reachable),
                            color = Ink.SageLight,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
        if (onMaskClick != null) {
            IconButton(onClick = onMaskClick) {
                // Les mêmes gestes que les paramètres : activer, lire, désactiver.
                Icon(
                    if (masked == null) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                    stringResource(
                        when (masked) {
                            null -> R.string.read_lock_enable
                            true -> R.string.read_lock_unlock
                            false -> R.string.read_lock_disable
                        }
                    ),
                    tint = when (masked) {
                        null -> Ink.Chalk
                        true -> Ink.Ochre
                        false -> Ink.SageLight
                    },
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        if (onCallClick != null) {
            IconButton(onClick = onCallClick) {
                Icon(Icons.Rounded.Call, stringResource(R.string.call_action), tint = Ink.Chalk, modifier = Modifier.size(22.dp))
            }
        }
        IconButton(onClick = onSettingsClick) {
            Icon(Icons.Rounded.MoreVert, stringResource(R.string.chat_settings), tint = Ink.Chalk)
        }
    }
}

/** Bandeau sous l'en-tête : un constat, et les gestes qui y répondent. */
@Composable
private fun ContactBanner(text: String, actions: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .glass(RoundedCornerShape(20.dp), elevation = 8.dp)
            .padding(start = 16.dp, end = 8.dp, top = 12.dp)
    ) {
        Text(text, color = Ink.ChalkSoft, style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.align(Alignment.End)) { actions() }
    }
}

/** Ce que l'écran de conversation reçoit pour enregistrer et lire les vocaux. */
class VoiceControls(
    val playing: String? = null,
    val progress: Float = 0f,
    val onToggle: (key: String, clip: ByteArray, durationMs: Int) -> Unit = { _, _, _ -> },
    val recording: Boolean = false,
    val elapsedMs: Long = 0,
    val onRecord: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onSend: () -> Unit = {}
)

/**
 * Une ligne de la conversation : un message texte, ou un vocal qui regroupe
 * ses morceaux. [anchor] porte la date, l'expéditeur et l'état affichés.
 */
private class ChatRow(
    val key: String,
    val anchor: Message,
    /** Les messages de la ligne (un vocal ou un fichier en a plusieurs) : ce qu'on supprime. */
    val ids: List<String> = listOf(anchor.id),
    val isVoice: Boolean = false,
    /** Trace d'un appel : une ligne centrée, pas une bulle. */
    val call: CallEvent? = null,
    val durationMs: Int = 0,
    /** L'audio recollé ; null tant qu'un morceau manque. */
    val clip: ByteArray? = null,
    /** Fichier (phase 16) : son en-tête, et combien de morceaux sont arrivés (reçu) ou accusés (envoyé). */
    val file: FilePayload.Header? = null,
    val fileDone: Int = 0,
    /** Avis sur un fichier (partagé, enregistré, vu…) : une ligne centrée. */
    val notice: FileNotice.Notice? = null
)

/** Quelques mots pour citer une ligne : le texte, ou ce qu'elle est. */
private fun snippetOf(row: ChatRow, clear: Map<String, String>): String {
    row.file?.let { return "📎 " + it.name }
    if (row.isVoice) {
        val s = row.durationMs / 1000
        return "🎤 %d:%02d".format(s / 60, s % 60)
    }
    return ReplyPayload.body(clear[row.anchor.id].orEmpty()).replace('\n', ' ').trim().take(ReplyPayload.SNIPPET)
}

private fun chatRows(messages: List<Message>, clear: Map<String, String>): List<ChatRow> {
    val order = mutableListOf<String>()
    val texts = mutableMapOf<String, Message>()
    val voices = linkedMapOf<String, MutableList<Pair<Message, VoicePayload.Part>>>()
    val files = linkedMapOf<String, MutableList<Pair<Message, FilePayload.Header>>>()
    for (message in messages) {
        val fileHeader = clear[message.id]?.let(FilePayload::header)
        if (fileHeader != null) {
            // Même règle que les vocaux : un pair ne glisse rien dans le fichier de l'autre.
            val key = message.fromPublicKeyHex + ":f:" + fileHeader.fileId
            if (key !in files) order += key
            files.getOrPut(key) { mutableListOf() } += message to fileHeader
            continue
        }
        val part = clear[message.id]?.let(VoicePayload::parse)
        if (part == null) {
            order += message.id
            texts[message.id] = message
        } else {
            // Clé : expéditeur + identifiant, pour qu'un pair ne puisse pas
            // glisser un morceau dans un vocal de l'autre.
            val key = message.fromPublicKeyHex + ":" + part.voiceId
            if (key !in voices) order += key
            voices.getOrPut(key) { mutableListOf() } += message to part
        }
    }
    return order.map { key ->
        files[key]?.let { parts -> return@map fileRow(key, parts) }
        texts[key]?.let { ChatRow(it.id, it, call = CallEvent.parse(clear[it.id]), notice = FileNotice.parse(clear[it.id])) } ?: run {
            val parts = voices.getValue(key)
            val first = parts.first()
            val statuses = parts.map { it.first.status }
            val anchor = when {
                MessageStatus.FAILED in statuses -> parts.first { it.first.status == MessageStatus.FAILED }.first
                MessageStatus.PENDING in statuses -> first.first.withStatus(MessageStatus.PENDING)
                // Reçu seulement quand tous les morceaux le sont.
                MessageStatus.SENT in statuses -> first.first.withStatus(MessageStatus.SENT)
                else -> first.first
            }
            ChatRow(
                key = key,
                anchor = anchor,
                ids = parts.map { it.first.id },
                isVoice = true,
                durationMs = first.second.durationMs,
                clip = VoicePayload.assemble(parts.map { it.second })
            )
        }
    }
}

/**
 * La bulle d'un fichier : un seul état pour tous ses morceaux. Reçu :
 * combien sont arrivés. Envoyé : combien sont accusés par le contact.
 */
private fun fileRow(key: String, parts: List<Pair<Message, FilePayload.Header>>): ChatRow {
    val first = parts.first()
    val statuses = parts.map { it.first.status }
    val anchor = when {
        MessageStatus.FAILED in statuses -> parts.first { it.first.status == MessageStatus.FAILED }.first
        MessageStatus.PENDING in statuses -> first.first.withStatus(MessageStatus.PENDING)
        MessageStatus.SENT in statuses -> first.first.withStatus(MessageStatus.SENT)
        else -> first.first
    }
    val done = parts.distinctBy { it.second.index }.count { (m, _) ->
        m.status == MessageStatus.DELIVERED || m.status == MessageStatus.READ
    }
    return ChatRow(key = key, anchor = anchor, ids = parts.map { it.first.id }, file = first.second, fileDone = done)
}

/**
 * Ce qui est arrivé à un fichier, dit aux deux : chez l'envoyeur, « Marie a
 * partagé « photo.jpg » » ; chez le destinataire, qu'il l'a fait et que
 * l'envoyeur le sait. Jamais à qui un fichier a été transféré.
 */
@Composable
private fun NoticeRow(notice: FileNotice.Notice, own: Boolean, contactName: String, masked: Boolean, time: String) {
    val name = if (masked) stringResource(R.string.file_generic) else notice.name
    val text = if (own) {
        stringResource(
            when (notice.event) {
                FileNotice.Event.FORWARDED -> R.string.notice_you_forwarded
                FileNotice.Event.SHARED -> R.string.notice_you_shared
                FileNotice.Event.SAVED -> R.string.notice_you_saved
                FileNotice.Event.OPENED_ELSEWHERE -> R.string.notice_you_opened
                FileNotice.Event.VIEWED -> R.string.notice_you_viewed
                FileNotice.Event.CANCELLED -> R.string.notice_you_cancelled
            },
            name
        )
    } else {
        stringResource(
            when (notice.event) {
                FileNotice.Event.FORWARDED -> R.string.notice_forwarded
                FileNotice.Event.SHARED -> R.string.notice_shared
                FileNotice.Event.SAVED -> R.string.notice_saved
                FileNotice.Event.OPENED_ELSEWHERE -> R.string.notice_opened
                FileNotice.Event.VIEWED -> R.string.notice_viewed
                FileNotice.Event.CANCELLED -> R.string.notice_cancelled
            },
            contactName, name
        )
    }
    // Chez l'envoyeur, un fichier qui a quitté la conversation se voit : couleur d'alerte.
    val leaves = notice.event != FileNotice.Event.VIEWED && notice.event != FileNotice.Event.CANCELLED
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        Row(
            Modifier
                .background(if (!own && leaves) Ink.OchreDim else Ink.Slate, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                when (notice.event) {
                    FileNotice.Event.VIEWED -> Icons.Rounded.Visibility
                    FileNotice.Event.CANCELLED -> Icons.Rounded.Close
                    else -> Icons.Rounded.Share
                },
                null,
                tint = if (!own && leaves) Ink.Ochre else Ink.Ash,
                modifier = Modifier.size(16.dp)
            )
            Text(text, color = Ink.ChalkSoft, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f, fill = false))
            Text(time, color = Ink.Ash, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** À la place de l'en-tête pendant une sélection : combien, et supprimer. */
@Composable
private fun SelectionBar(count: Int, onClose: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .height(56.dp)
            .glass(RoundedCornerShape(28.dp))
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.Rounded.Close, stringResource(R.string.selection_cancel), tint = Ink.Chalk)
        }
        Text(
            stringResource(R.string.selection_count, count),
            color = Ink.Chalk,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f).padding(start = 8.dp)
        )
        TextButton(onClick = onDelete) {
            Icon(Icons.Outlined.DeleteOutline, null, tint = Ink.Brick, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.delete_selected_action), color = Ink.Brick, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Une action de la fenêtre d'appui long : icône et verbe. */
@Composable
private fun ActionLine(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
        Text(label, color = color, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * Copier (1.3.2) : le message dans une fenêtre, où l'on choisit au doigt la
 * partie voulue — ou « Tout copier ». Le presse-papiers est marqué sensible :
 * Android n'en montre pas l'aperçu (Android 13 et plus).
 */
@Composable
private fun CopyDialog(text: String, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val copied = stringResource(R.string.copy_done)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink.Raised,
        title = { Text(stringResource(R.string.copy_title), color = Ink.Chalk) },
        text = {
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(
                    text,
                    color = Ink.Chalk,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val clip = android.content.ClipData.newPlainText("", text)
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    clip.description.extras = android.os.PersistableBundle().apply {
                        putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
                    }
                }
                context.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(clip)
                android.widget.Toast.makeText(context, copied, android.widget.Toast.LENGTH_SHORT).show()
                onDismiss()
            }) { Text(stringResource(R.string.copy_all), color = Ink.SageLight) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close), color = Ink.ChalkSoft) }
        }
    )
}

/**
 * Brouillons (1.3.1) : le texte commencé, par contact, en mémoire seulement.
 * Rien sur le disque : ils meurent avec l'app (tuée dès l'arrière-plan).
 */
private object Drafts {
    private val byPeer = HashMap<String, String>()
    operator fun get(peer: String): String = byPeer[peer].orEmpty()
    operator fun set(peer: String, text: String) {
        if (text.isEmpty()) byPeer.remove(peer) else byPeer[peer] = text
    }
}
