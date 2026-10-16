package com.whisper.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.whisper.theme.Ink

/**
 * La photo d'un contact, en grand. Un appui n'importe où la ferme.
 *
 * Voir, pas garder : aucun bouton d'enregistrement ni de partage, pas d'appui
 * long, et la fenêtre refuse les captures d'écran comme le reste de l'app.
 */
@Composable
fun PhotoViewer(photo: ByteArray, name: String, onDismiss: () -> Unit) {
    val image = remember(photo.contentHashCode()) {
        runCatching { android.graphics.BitmapFactory.decodeByteArray(photo, 0, photo.size)?.asImageBitmap() }.getOrNull()
    }
    if (image == null) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            securePolicy = SecureFlagPolicy.SecureOn
        )
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Ink.Night.copy(alpha = 0.94f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(24.dp)
            ) {
                Image(
                    image,
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .widthIn(max = 360.dp)
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(20.dp))
                )
                Text(
                    name,
                    color = Ink.Chalk,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
