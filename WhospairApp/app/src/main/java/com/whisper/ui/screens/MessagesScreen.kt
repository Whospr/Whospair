package com.whisper.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import com.whisper.ui.icons.Icons
import com.whisper.ui.icons.outlined.PushPin
import com.whisper.ui.icons.outlined.ChatBubbleOutline
import com.whisper.ui.icons.outlined.Lock
import com.whisper.ui.icons.outlined.PersonAddAlt
import com.whisper.ui.icons.rounded.Mic
import com.whisper.ui.icons.rounded.Person
import com.whisper.ui.icons.rounded.Vibration
import com.whisper.voice.VoicePayload
import com.whisper.files.FilePayload
import com.whisper.files.FileNotice
import com.whisper.ui.icons.rounded.Share
import com.whisper.ui.icons.rounded.AttachFile
import com.whisper.call.CallEvent
import com.whisper.ui.call.callEventLabel
import com.whisper.ui.call.isMissed
import com.whisper.ui.icons.rounded.Call
import com.whisper.ui.icons.rounded.CallMissed
import com.whisper.ui.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.clickable
import androidx.compose.runtime.setValue
import com.whisper.ui.components.PhotoViewer
import com.whisper.ui.components.StatusMark
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.whisper.R
import com.whisper.models.ConversationItem
import com.whisper.models.MessageStatus
import com.whisper.theme.Ink
import com.whisper.ui.components.AvatarTones
import com.whisper.ui.components.LocalBottomBarSpace
import com.whisper.ui.components.OnlineDot
import com.whisper.ui.components.screenBackground
import com.whisper.ui.icons.outlined.Edit
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.sp
import com.whisper.ui.components.PrivateTextField
import com.whisper.ui.components.ContactActionsDialog
import com.whisper.ui.components.PrivacyExplainer
import com.whisper.ui.components.PrivacyIndicator
import com.whisper.viewmodels.ContactViewModel
import com.whisper.viewmodels.MessagesViewModel

/**
 * MessagesScreenContainer - Wrapper avec injection de dépendances
 */
@Composable
fun MessagesScreenContainer(
    paddingValues: PaddingValues,
    contactViewModel: ContactViewModel,
    onConversationClick: (String) -> Unit = {},
    onAddContact: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onNewChat: () -> Unit = {},
    viewModel: MessagesViewModel = hiltViewModel()
) {
    val state = viewModel.state.collectAsState()
    val searchQuery = remember { mutableStateOf("") }
    val torState by viewModel.torState.collectAsState()
    val tunnelled by viewModel.tunnelled.collectAsState()
    val stayReachable by viewModel.stayReachable.collectAsState()
    val update by viewModel.update.collectAsState()
    val showExplainer = remember { mutableStateOf(false) }
    val selected = remember { mutableStateOf<String?>(null) }

    if (showExplainer.value) {
        PrivacyExplainer(torState, tunnelled) { showExplainer.value = false }
    }

    MessagesScreen(
        state = state.value,
        paddingValues = paddingValues,
        indicator = { PrivacyIndicator(torState, tunnelled, reachable = stayReachable) { showExplainer.value = true } },
        searchQuery = searchQuery.value,
        onSearchChange = { query ->
            searchQuery.value = query
            viewModel.filterConversations(query)
        },
        onConversationClick = onConversationClick,
        onConversationLongPress = { selected.value = it },
        onAddContact = onAddContact,
        onNewChat = onNewChat,
        updateAvailable = update is com.whisper.update.UpdateManager.State.Available,
        onUpdateClick = onOpenSettings
    )

    // Relu dans l'état à chaque recomposition : un renommage s'y voit aussitôt.
    selected.value
        ?.let { key -> state.value.conversations.find { it.contact.publicKeyHex == key }?.contact }
        ?.let { contact ->
            val key = contact.publicKeyHex
            ContactActionsDialog(
                contact = contact,
                onDismiss = { selected.value = null },
                onRename = { contactViewModel.rename(key, it) },
                onBlock = { contactViewModel.block(key) },
                onUnblock = { contactViewModel.unblock(key) },
                onSetPrivate = { contactViewModel.setPrivate(key, it) },
                onSetPinned = { contactViewModel.setPinned(key, it) },
                onDelete = { contactViewModel.deleteWithConversation(key) },
                onConfirmTrust = { contactViewModel.confirmTrust(key) }
            )
        }
}

/**
 * MessagesScreen - Liste des conversations
 */
@Composable
fun MessagesScreen(
    state: com.whisper.viewmodels.MessagesViewState,
    paddingValues: PaddingValues,
    indicator: @Composable () -> Unit = {},
    searchQuery: String = "",
    onSearchChange: (String) -> Unit = {},
    onConversationClick: (String) -> Unit = {},
    onConversationLongPress: (String) -> Unit = {},
    onAddContact: () -> Unit = {},
    onNewChat: () -> Unit = {},
    updateAvailable: Boolean = false,
    onUpdateClick: () -> Unit = {}
) {
    val bottomSpace = LocalBottomBarSpace.current
    Box(Modifier.fillMaxSize().screenBackground().padding(paddingValues)) {
    Column(Modifier.fillMaxSize()) {
        ScreenTitle(stringResource(R.string.messages_title), trailing = indicator)
        // Une ligne sobre ; le détail et le bouton sont dans les Paramètres.
        if (updateAvailable) {
            Text(
                stringResource(R.string.update_banner),
                color = Ink.Chalk,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onUpdateClick)
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .background(Ink.Slate, RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }

        // Sans conversation, chercher n'a pas de sens : on montre quoi faire.
        val nothingYet = !state.isLoading && state.error == null && state.conversations.isEmpty()
        if (!nothingYet) SearchBar(searchQuery, onSearchChange)

        when {
            state.isLoading -> CenteredNote(stringResource(R.string.common_loading))
            state.error != null -> CenteredNote(state.error, color = Ink.Brick)
            nothingYet -> EmptyState(
                icon = { Icon(Icons.Outlined.ChatBubbleOutline, null, tint = Ink.LineStrong, modifier = Modifier.size(72.dp)) },
                title = stringResource(R.string.conversations_empty_title),
                body = stringResource(R.string.conversations_empty_body),
                action = stringResource(R.string.add_contact),
                onAction = onAddContact
            )
            state.filteredConversations.isEmpty() -> CenteredNote(stringResource(R.string.search_no_result))
            else -> LazyColumn(
                Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = bottomSpace + 84.dp)
            ) {
                items(state.filteredConversations, key = { it.contact.publicKeyHex }) { conversation ->
                    ConversationItemCard(
                        conversation = conversation,
                        onClick = { onConversationClick(conversation.contact.publicKeyHex) },
                        onLongPress = { onConversationLongPress(conversation.contact.publicKeyHex) }
                    )
                }
            }
        }
    }

    // Écrire à quelqu'un : on choisit parmi les contacts.
    if (state.conversations.isNotEmpty()) {
        val fab = RoundedCornerShape(20.dp)
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = bottomSpace + 14.dp)
                .size(58.dp)
                .shadow(12.dp, fab, ambientColor = Ink.Shadow, spotColor = Ink.Shadow)
                .background(Brush.verticalGradient(listOf(Ink.TileSage.first, Ink.TileSage.second)), fab)
                .clip(fab)
                .clickable(onClickLabel = stringResource(R.string.new_chat), onClick = onNewChat),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Outlined.Edit, stringResource(R.string.new_chat), tint = Ink.OnTile, modifier = Modifier.size(24.dp))
        }
    }
    }
}

/** Grand titre d'écran, avec un élément facultatif à droite. */
@Composable
fun ScreenTitle(text: String, trailing: @Composable () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 16.dp, top = 28.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.headlineMedium.copy(fontSize = 31.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
            color = Ink.Chalk,
            modifier = Modifier.semantics { heading() }
        )
        trailing()
    }
}

@Composable
fun SearchBar(
    query: String = "",
    onQueryChange: (String) -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .height(46.dp)
            .background(Ink.Slate, RoundedCornerShape(23.dp))
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(Icons.Rounded.Search, contentDescription = null, tint = Ink.Ash, modifier = Modifier.size(20.dp))
        PrivateTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = stringResource(R.string.search_contact),
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * Une conversation. Appui : ouvrir. Appui long : renommer, bloquer, supprimer.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConversationItemCard(
    conversation: ConversationItem,
    onClick: () -> Unit = {},
    onLongPress: () -> Unit = {}
) {
    val contact = conversation.contact
    val unread = conversation.unreadCount > 0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (unread) Ink.Chalk.copy(alpha = 0.035f) else androidx.compose.ui.graphics.Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(vertical = 10.dp, horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // La photo s'agrandit d'un appui ; sans photo, l'appui ouvre la conversation.
        var viewing by remember { mutableStateOf(false) }
        Box(
            if (contact.photo != null) Modifier.clickable { viewing = true } else Modifier
        ) {
            Avatar(contact.displayName, online = conversation.isOnline, size = 56, photo = contact.photo)
        }
        if (viewing) contact.photo?.let { PhotoViewer(it, contact.displayLabel) { viewing = false } }

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    contact.displayLabel,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = Ink.Chalk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                conversation.lastMessage?.let {
                    Text(
                        shortTime(it.timestamp),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (unread) Ink.SageLight else Ink.Ash,
                        fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (contact.isPinned) {
                    Icon(Icons.Outlined.PushPin, stringResource(R.string.conv_pinned), tint = Ink.Ash, modifier = Modifier.size(14.dp))
                }
                if (contact.isBlocked) Tag(stringResource(R.string.contact_blocked), Ink.Brick, Ink.BrickDim)
                else if (!contact.isTrusted) Tag(stringResource(R.string.contact_unconfirmed), Ink.Ochre, Ink.OchreDim)
                else if (contact.isVerified) Tag(stringResource(R.string.contact_verified), Ink.SageLight, Ink.SageDim)
                // Dernier message envoyé par nous : même horloge / coches que dans la conversation.
                conversation.lastMessage
                    ?.takeIf { it.toPublicKeyHex == contact.publicKeyHex && !conversation.nudged && !contact.hasReadLock }
                    ?.let { StatusMark(it.status) }
                PreviewLine(conversation, Modifier.weight(1f))
                if (unread) {
                    Box(
                        Modifier
                            .defaultMinSize(minWidth = 22.dp, minHeight = 22.dp)
                            .background(Ink.Sage, CircleShape)
                            .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            conversation.unreadCount.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = Ink.Night
                        )
                    }
                }
            }
        }
    }
}

/**
 * L'aperçu : jamais le texte d'une conversation masquée. Un signe reçu passe
 * devant, même masquée — il ne dit rien de ce qui a été écrit.
 */
@Composable
private fun PreviewLine(conversation: ConversationItem, modifier: Modifier) {
    val muted = MaterialTheme.typography.bodyMedium
    when {
        conversation.nudged -> Row(
            modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(Icons.Rounded.Vibration, null, tint = Ink.Sage, modifier = Modifier.size(15.dp))
            Text(
                stringResource(R.string.nudge_received, conversation.contact.displayLabel),
                style = muted,
                color = Ink.SageLight,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        conversation.contact.hasReadLock -> Row(
            modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(Icons.Outlined.Lock, null, tint = Ink.Ash, modifier = Modifier.size(15.dp))
            Text(stringResource(R.string.read_lock_preview), style = muted, fontStyle = FontStyle.Italic, color = Ink.Ash)
        }
        CallEvent.isCall(conversation.lastMessagePreview) -> {
            val event = CallEvent.parse(conversation.lastMessagePreview)
            Row(
                modifier,
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    if (event?.answered == true) Icons.Rounded.Call else Icons.Rounded.CallMissed,
                    null,
                    tint = if (event?.isMissed() == true) Ink.Brick else Ink.Ash,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    event?.let { callEventLabel(it) } ?: stringResource(R.string.call_action),
                    style = muted,
                    color = Ink.Ash
                )
            }
        }
        VoicePayload.isVoice(conversation.lastMessagePreview) -> Row(
            modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(Icons.Rounded.Mic, null, tint = Ink.Ash, modifier = Modifier.size(16.dp))
            Text(
                stringResource(
                    R.string.voice_preview,
                    VoicePayload.durationLabel(VoicePayload.durationMs(conversation.lastMessagePreview!!) ?: 0)
                ),
                style = muted,
                color = if (conversation.unreadCount > 0) Ink.ChalkSoft else Ink.Ash
            )
        }
        FileNotice.isNotice(conversation.lastMessagePreview) -> Row(
            modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(Icons.Rounded.Share, null, tint = Ink.Ochre, modifier = Modifier.size(15.dp))
            Text(stringResource(R.string.notice_preview), style = muted, color = Ink.Ash, maxLines = 1)
        }
        FilePayload.isFile(conversation.lastMessagePreview) -> Row(
            modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(Icons.Rounded.AttachFile, null, tint = Ink.Ash, modifier = Modifier.size(16.dp))
            Text(
                // Sans en-tête (le marqueur seul) : un fichier arrive, son nom n'est pas encore montré.
                FilePayload.header(conversation.lastMessagePreview!!)?.name ?: stringResource(R.string.file_incoming),
                style = muted,
                color = if (conversation.unreadCount > 0) Ink.ChalkSoft else Ink.Ash,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        else -> {
            val message = conversation.lastMessage
            val text = when {
                message == null -> stringResource(R.string.preview_none)
                conversation.lastMessagePreview != null -> conversation.lastMessagePreview
                message.status == MessageStatus.DELETED -> stringResource(R.string.preview_none)
                else -> stringResource(R.string.preview_encrypted)
            }
            Text(
                text,
                style = muted,
                color = if (conversation.unreadCount > 0) Ink.ChalkSoft else Ink.Ash,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = modifier
            )
        }
    }
}

/**
 * La photo du contact s'il en a envoyé une ; sinon l'initiale sur un fond
 * ton sur ton. Sans nom choisi, une silhouette : le « U » de « User 3fa2… »
 * ne dirait rien. La pastille dit « joignable ».
 */
@Composable
fun Avatar(name: String, online: Boolean = false, size: Int = 48, photo: ByteArray? = null) {
    // Clé sur le contenu : Room rend de nouveaux tableaux à chaque émission.
    val image = remember(photo?.contentHashCode()) {
        photo?.let { runCatching { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() }
    }
    Box(Modifier.size(size.dp)) {
        if (image != null) {
            Image(
                image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(size.dp)
                    .clip(CircleShape)
            )
        } else {
            val initial = name.trim().firstOrNull()?.uppercase()
            val tone = AvatarTones.of(name)
            Box(
                Modifier
                    .size(size.dp)
                    .shadow((size / 12).dp, CircleShape, ambientColor = Ink.Shadow, spotColor = Ink.Shadow)
                    .background(
                        if (initial == null) Brush.verticalGradient(listOf(Ink.Relief, Ink.Relief))
                        else Brush.linearGradient(listOf(tone.first, tone.second)),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (initial == null) {
                    Icon(Icons.Rounded.Person, null, tint = Ink.Ash, modifier = Modifier.size((size * 0.58f).dp))
                } else {
                    Text(
                        initial,
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = (size * 0.4f).sp, fontWeight = FontWeight.Bold),
                        color = tone.third
                    )
                }
            }
        }
        if (online) {
            OnlineDot(Modifier.align(Alignment.BottomEnd), size = (size / 4 + 1).dp)
        }
    }
}

/** Petite étiquette d'état : non confirmé, bloqué. */
@Composable
fun Tag(text: String, color: androidx.compose.ui.graphics.Color, border: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .border(1.dp, border, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 1.dp)
    )
}

@Composable
fun CenteredNote(text: String, color: androidx.compose.ui.graphics.Color = Ink.Ash) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = color, textAlign = TextAlign.Center)
    }
}

/** Écran vide : une icône pâle, ce qui se passe, et le geste pour avancer. */
@Composable
fun EmptyState(
    icon: @Composable () -> Unit,
    title: String,
    body: String,
    action: String? = null,
    onAction: () -> Unit = {}
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp)
            .padding(bottom = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        icon()
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = Ink.Chalk, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(body, style = MaterialTheme.typography.bodyLarge, color = Ink.ChalkSoft, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(24.dp))
            Button(onClick = onAction, modifier = Modifier.height(52.dp)) {
                Icon(Icons.Outlined.PersonAddAlt, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(10.dp))
                Text(action, style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

/** Heure du jour, « Hier », ou la date courte. */
@Composable
private fun shortTime(timestamp: Long): String {
    val zoned = java.time.Instant.ofEpochMilli(timestamp).atZone(java.time.ZoneId.systemDefault())
    val date = zoned.toLocalDate()
    val today = java.time.LocalDate.now()
    return when {
        date == today -> "%02d:%02d".format(zoned.hour, zoned.minute)
        date == today.minusDays(1) -> stringResource(R.string.chat_yesterday)
        date.year == today.year -> date.format(java.time.format.DateTimeFormatter.ofPattern("d MMM"))
        else -> date.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy"))
    }
}
