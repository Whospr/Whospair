package com.whisper.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.heightIn
import com.whisper.ui.icons.rounded.Download
import com.whisper.ui.icons.rounded.Forward
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.os.Build
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.whisper.ui.icons.automirrored.rounded.OpenInNew
import com.whisper.ui.icons.outlined.Audiotrack
import com.whisper.ui.icons.rounded.Pause
import com.whisper.ui.icons.rounded.PlayArrow
import com.whisper.ui.icons.rounded.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import com.whisper.files.DocText
import com.whisper.files.MemFd
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.whisper.ui.icons.Icons
import com.whisper.ui.icons.outlined.InsertDriveFile
import com.whisper.ui.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.whisper.R
import com.whisper.files.FilePayload
import com.whisper.theme.Ink

/**
 * Avant l'envoi : le fichier, et les deux choix de l'envoyeur. Par défaut,
 * non enregistrable : c'est l'envoyeur qui ouvre la porte, pas l'inverse.
 */
@Composable
fun SendFileDialog(
    name: String,
    size: Long,
    onSend: (flags: Int) -> Unit,
    onCancel: () -> Unit
) {
    var savable by remember { mutableStateOf(false) }
    var viewOnce by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Ink.Raised,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(stringResource(R.string.file_send_title), color = Ink.Chalk) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.InsertDriveFile, null, tint = Ink.ChalkSoft, modifier = Modifier.size(28.dp))
                    Column {
                        Text(name, color = Ink.Chalk, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(FilePayload.sizeLabel(size), color = Ink.Ash, style = MaterialTheme.typography.bodySmall)
                    }
                }
                OptionRow(stringResource(R.string.file_allow_save), stringResource(R.string.file_allow_save_hint), savable) { savable = it }
                OptionRow(stringResource(R.string.file_view_once_option), stringResource(R.string.file_view_once_hint), viewOnce) { viewOnce = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                var flags = 0
                if (savable) flags = flags or FilePayload.FLAG_SAVABLE
                if (viewOnce) flags = flags or FilePayload.FLAG_VIEW_ONCE
                onSend(flags)
            }) { Text(stringResource(R.string.file_send), color = Ink.SageLight) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel), color = Ink.ChalkSoft) }
        }
    )
}

@Composable
private fun OptionRow(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink.Chalk, style = MaterialTheme.typography.bodyLarge)
            Text(hint, color = Ink.Ash, style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Ink.Sage, checkedThumbColor = Ink.Night)
        )
    }
}

/**
 * Un fichier reçu, ouvert dans Whisper : déchiffré en mémoire, jamais écrit
 * en clair, captures refusées. Photo, texte, PDF, vidéo, audio, .docx/.odt
 * (texte), .zip (liste, chaque entrée ouverte ici même).
 *
 * En haut : « Partager » si l'envoyeur l'a permis, « Ouvrir avec… » si
 * Whisper ne sait pas le lire (ou s'il est enregistrable). Chaque geste
 * vers l'extérieur prévient l'envoyeur (voir l'écran de conversation).
 */
@Composable
fun FileViewer(
    header: FilePayload.Header,
    data: ByteArray,
    onDismiss: () -> Unit,
    onShare: ((FilePayload.Header, ByteArray) -> Unit)? = null,
    onOpenElsewhere: ((FilePayload.Header, ByteArray) -> Unit)? = null
) {
    // Une entrée d'archive ouverte par-dessus, avec les mêmes règles.
    var inner by remember { mutableStateOf<Pair<FilePayload.Header, ByteArray>?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn)
    ) {
        Column(Modifier.fillMaxSize().background(Ink.Night)) {
            Row(
                Modifier.fillMaxWidth().height(64.dp).background(Ink.Bar).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, stringResource(R.string.close), tint = Ink.Chalk) }
                Column(Modifier.weight(1f)) {
                    Text(header.name, color = Ink.Chalk, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        FilePayload.sizeLabel(data.size.toLong()) + if (header.viewOnce) " · " + stringResource(R.string.file_view_once) else "",
                        color = Ink.Ash,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (onShare != null && header.shareable) {
                    IconButton(onClick = { onShare(header, data) }) {
                        Icon(Icons.Rounded.Share, stringResource(R.string.file_share), tint = Ink.ChalkSoft)
                    }
                }
                if (onOpenElsewhere != null && (header.shareable || !viewableInApp(header))) {
                    IconButton(onClick = { onOpenElsewhere(header, data) }) {
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, stringResource(R.string.file_open_with), tint = Ink.ChalkSoft)
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val pdf = header.mime == "application/pdf" || header.name.lowercase().endsWith(".pdf")
                when {
                    header.mime.startsWith("image/") -> ImageContent(data)
                    header.mime.startsWith("video/") -> MediaContent(data, video = true)
                    header.mime.startsWith("audio/") -> MediaContent(data, video = false)
                    pdf -> PdfContent(data)
                    DocText.isDocument(header.name) -> DocumentContent(data, header.name)
                    DocText.isZip(header.name, header.mime) -> ZipContent(data) { name, bytes ->
                        inner = FilePayload.Header(header.fileId, 0, 1, bytes.size.toLong(), header.flags, DocText.mimeOf(name), name.substringAfterLast('/')) to bytes
                    }
                    isText(header) -> TextContent(data)
                    else -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                        Icon(Icons.Outlined.InsertDriveFile, null, tint = Ink.Ash, modifier = Modifier.size(48.dp))
                        Text(stringResource(R.string.file_open_elsewhere_hint), color = Ink.Ash, modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }
        }
    }

    inner?.let { (h, bytes) ->
        FileViewer(h, bytes, onDismiss = { bytes.fill(0); inner = null }, onShare = onShare, onOpenElsewhere = onOpenElsewhere)
    }
}

/** Ce que Whisper sait afficher lui-même ; le reste passe par « Ouvrir avec… ». */
fun viewableInApp(header: FilePayload.Header): Boolean {
    val pdf = header.mime == "application/pdf" || header.name.lowercase().endsWith(".pdf")
    return header.mime.startsWith("image/") || header.mime.startsWith("video/") || header.mime.startsWith("audio/") ||
        (pdf && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ||
        DocText.isDocument(header.name) || DocText.isZip(header.name, header.mime) || isText(header)
}

private fun isText(header: FilePayload.Header): Boolean =
    header.mime.startsWith("text/") ||
        header.mime in setOf("application/json", "application/xml", "application/csv") ||
        header.name.substringAfterLast('.', "").lowercase() in setOf("txt", "csv", "json", "md", "log", "xml")

@Composable
private fun ImageContent(data: ByteArray) {
    val image = remember(data) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2048) sample *= 2
            BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        }.getOrNull()
    }
    if (image != null) {
        Image(image, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
    } else {
        Text(stringResource(R.string.file_unreadable), color = Ink.Ash)
    }
}

@Composable
private fun TextContent(data: ByteArray) {
    // Au-delà de 2 Mo de texte, le début suffit : l'écran n'en montrerait pas plus.
    val text = remember(data) { String(data, 0, minOf(data.size, 2 * 1024 * 1024), Charsets.UTF_8) }
    PlainText(text)
}

@Composable
private fun PlainText(text: String) {
    Text(
        text,
        color = Ink.ChalkSoft,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
    )
}

/** .docx / .odt : le texte seul, lisible ; la mise en page fine est perdue. */
@Composable
private fun DocumentContent(data: ByteArray, name: String) {
    val text by produceState<String?>(null, data) { value = withContext(Dispatchers.Default) { DocText.extract(data, name) } }
    when (val t = text) {
        null -> CircularProgressIndicator(color = Ink.Sage)
        "" -> Text(stringResource(R.string.file_unreadable), color = Ink.Ash)
        else -> Text(
            t,
            color = Ink.Chalk,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)
        )
    }
}

/** Une archive : ses entrées ; un appui en ouvre une, ici même. */
@Composable
private fun ZipContent(data: ByteArray, onOpen: (String, ByteArray) -> Unit) {
    // Hors du fil de l'écran : une archive piégée ne fige plus rien.
    val loaded by produceState<List<DocText.ZipItem>?>(null, data) {
        value = withContext(Dispatchers.Default) { DocText.list(data) } ?: emptyList()
    }
    val items = loaded
    if (items == null) {
        CircularProgressIndicator(color = Ink.Sage)
        return
    }
    if (items.isEmpty()) {
        Text(stringResource(R.string.file_unreadable), color = Ink.Ash)
        return
    }
    val scope = rememberCoroutineScope()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(items) { item ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        scope.launch {
                            withContext(Dispatchers.Default) { DocText.entry(data, item.name) }?.let { onOpen(item.name, it) }
                        }
                    }
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(Icons.Outlined.InsertDriveFile, null, tint = Ink.Ash, modifier = Modifier.size(20.dp))
                Text(item.name, color = Ink.Chalk, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (item.size >= 0) Text(FilePayload.sizeLabel(item.size), color = Ink.Ash, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * PDF : pages rendues une à une depuis un descripteur en mémoire vive
 * (Android 11+). Avant, « Ouvrir avec… ».
 */
@Composable
private fun PdfContent(data: ByteArray) {
    val renderer = remember(data) { MemFd.seekable(data)?.let { fd -> runCatching { PdfRenderer(fd) }.getOrNull() } }
    DisposableEffect(renderer) { onDispose { runCatching { renderer?.close() } } }
    if (renderer == null) {
        Text(stringResource(R.string.file_open_elsewhere_hint), color = Ink.Ash, modifier = Modifier.padding(32.dp))
        return
    }
    val widthPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.toPx() }.toInt().coerceAtMost(1440)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(renderer.pageCount) { index ->
            val page by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, index) {
                value = withContext(Dispatchers.Default) {
                    runCatching {
                        synchronized(renderer) {
                            renderer.openPage(index).use { p ->
                                val bitmap = Bitmap.createBitmap(widthPx, maxOf(1, widthPx * p.height / p.width), Bitmap.Config.ARGB_8888)
                                bitmap.eraseColor(android.graphics.Color.WHITE)
                                p.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                bitmap.asImageBitmap()
                            }
                        }
                    }.getOrNull()
                }
            }
            page?.let { Image(it, contentDescription = null, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth()) }
                ?: Box(Modifier.fillMaxWidth().aspectRatio(0.707f).background(Ink.Slate))
        }
    }
}

/** Les octets, lus par le lecteur d'Android sans passer par un fichier. */
private class BytesDataSource(private val data: ByteArray) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= data.size) return -1
        val n = minOf(size.toLong(), data.size - position).toInt()
        System.arraycopy(data, position.toInt(), buffer, offset, n)
        return n
    }
    override fun getSize(): Long = data.size.toLong()
    override fun close() {}
}

/** Vidéo ou audio : lecture, pause, et la barre pour avancer. */
@Composable
private fun MediaContent(data: ByteArray, video: Boolean) {
    val player = remember(data) { MediaPlayer() }
    var ready by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableStateOf(0) }
    var duration by remember { mutableStateOf(0) }
    var ratio by remember { mutableStateOf(16f / 9f) }
    DisposableEffect(player) {
        runCatching {
            player.setDataSource(BytesDataSource(data))
            player.setOnPreparedListener { ready = true; duration = it.duration }
            player.setOnCompletionListener { playing = false; position = duration }
            player.setOnVideoSizeChangedListener { _, w, h -> if (w > 0 && h > 0) ratio = w.toFloat() / h }
            player.setOnErrorListener { _, _, _ -> failed = true; true }
            player.prepareAsync()
        }.onFailure { failed = true }
        onDispose { runCatching { player.release() } }
    }
    LaunchedEffect(playing) {
        while (playing) {
            position = runCatching { player.currentPosition }.getOrDefault(position)
            delay(250)
        }
    }
    if (failed) {
        Text(stringResource(R.string.file_open_elsewhere_hint), color = Ink.Ash, modifier = Modifier.padding(32.dp))
        return
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        if (video) {
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                                runCatching { player.setSurface(Surface(st)) }
                            }
                            override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {}
                            override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                                runCatching { player.setSurface(null) }
                                return true
                            }
                            override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().aspectRatio(ratio)
            )
        } else {
            Icon(Icons.Outlined.Audiotrack, null, tint = Ink.Ash, modifier = Modifier.size(72.dp))
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(
                enabled = ready,
                onClick = {
                    if (playing) player.pause() else player.start()
                    playing = !playing
                }
            ) {
                Icon(
                    if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    stringResource(if (playing) R.string.media_pause else R.string.media_play),
                    tint = Ink.SageLight
                )
            }
            Slider(
                value = if (duration > 0) position.toFloat() / duration else 0f,
                onValueChange = { f ->
                    position = (f * duration).toInt()
                    runCatching { player.seekTo(position) }
                },
                enabled = ready,
                colors = SliderDefaults.colors(thumbColor = Ink.Sage, activeTrackColor = Ink.Sage, inactiveTrackColor = Ink.LineStrong),
                modifier = Modifier.weight(1f)
            )
            Text(
                com.whisper.voice.VoicePayload.durationLabel(position) + " / " + com.whisper.voice.VoicePayload.durationLabel(duration),
                color = Ink.Ash,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/**
 * Partager un fichier que l'envoyeur a permis d'enregistrer : à un autre
 * contact (dans Whisper), à une autre app, ou sur le téléphone. L'envoyeur
 * est prévenu de chaque geste — c'est dit ici, avant, pas après.
 */
@Composable
fun ShareFileDialog(
    name: String,
    onForward: () -> Unit,
    onOutside: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Ink.Raised,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(name, color = Ink.Chalk, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ShareRow(Icons.Rounded.Forward, stringResource(R.string.share_forward), onForward)
                ShareRow(Icons.Rounded.Share, stringResource(R.string.share_outside), onOutside)
                ShareRow(Icons.Rounded.Download, stringResource(R.string.share_save), onSave)
                Text(
                    stringResource(R.string.share_sender_told),
                    color = Ink.Ash,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel), color = Ink.ChalkSoft) }
        }
    )
}

@Composable
private fun ShareRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(icon, null, tint = Ink.ChalkSoft, modifier = Modifier.size(22.dp))
        Text(label, color = Ink.Chalk, style = MaterialTheme.typography.bodyLarge)
    }
}

/** À qui transférer : les contacts confirmés, non bloqués. */
@Composable
fun ForwardDialog(
    contacts: List<com.whisper.models.Contact>,
    onPick: (com.whisper.models.Contact) -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Ink.Raised,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(stringResource(R.string.forward_title), color = Ink.Chalk) },
        text = {
            if (contacts.isEmpty()) {
                Text(stringResource(R.string.forward_none), color = Ink.Ash)
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    items(contacts) { contact ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(contact) }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            com.whisper.ui.screens.Avatar(contact.displayName, size = 36, photo = contact.photo)
                            Text(contact.displayLabel, color = Ink.Chalk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel), color = Ink.ChalkSoft) }
        }
    )
}
