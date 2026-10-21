package com.whisper.ui.contacts

import com.whisper.ui.components.screenBackground
import com.whisper.theme.Ink
import com.whisper.ui.components.PrivateTextField
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import com.whisper.ui.icons.outlined.ContentCopy
import com.whisper.ui.icons.outlined.QrCodeScanner
import com.whisper.ui.icons.outlined.Share
import com.whisper.ui.icons.rounded.Check
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import com.whisper.ui.icons.Icons
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.whisper.R
import com.whisper.util.ContactKeyParser
import com.whisper.util.QrCodeGenerator
import com.whisper.viewmodels.AddContactOutcome
import com.whisper.viewmodels.DiscoverViewModel

/**
 * DiscoverScreen - Ajouter un contact.
 *
 * Trois voies vers le même dépôt : scanner un QR, coller une clé, ou montrer
 * son propre QR. L'ancienne version affichait trois suggestions codées en dur
 * (« Julian Petit — 3 amis en commun ») qui n'étaient reliées à rien : dans une
 * app sans serveur, il n'existe aucune source possible pour des suggestions.
 */
@Composable
fun DiscoverScreen(
    paddingValues: PaddingValues,
    onOpenScanner: () -> Unit,
    viewModel: DiscoverViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .screenBackground()
            .padding(paddingValues)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = com.whisper.ui.components.LocalBottomBarSpace.current)
    ) {
        Text(
            text = stringResource(R.string.discover_title),
            color = Ink.Chalk,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier
                .padding(top = 24.dp, bottom = 10.dp)
                .semantics { heading() }
        )
        Text(
            text = stringResource(R.string.discover_subtitle),
            color = Ink.ChalkSoft,
            style = MaterialTheme.typography.bodyLarge
        )

        // Le code d'abord : c'est ce qu'on montre, en face à face.
        if (state.myPublicKeyHex.isNotBlank()) {
            MyCodePanel(state.myPublicKeyHex, viewModel.myQrPayload())
        }

        Button(
            onClick = onOpenScanner,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp)
                .height(54.dp)
        ) {
            Icon(Icons.Outlined.QrCodeScanner, null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.scan_code), style = MaterialTheme.typography.titleMedium)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = {
                    val send = Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(
                            Intent.EXTRA_TEXT,
                            context.getString(R.string.invite_message, viewModel.myShareableLink())
                        )
                    context.startActivity(Intent.createChooser(send, null))
                },
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp),
                border = BorderStroke(1.dp, Ink.LineStrong)
            ) {
                Icon(Icons.Outlined.Share, null, tint = Ink.ChalkSoft, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.share_link), color = Ink.Chalk, style = MaterialTheme.typography.bodyMedium)
            }
            OutlinedButton(
                onClick = {
                    clipboard.setText(AnnotatedString(viewModel.myShareableLink()))
                    copied = true
                },
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp),
                border = BorderStroke(1.dp, Ink.LineStrong)
            ) {
                Icon(
                    if (copied) Icons.Rounded.Check else Icons.Outlined.ContentCopy,
                    null,
                    tint = if (copied) Ink.Sage else Ink.ChalkSoft,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(if (copied) R.string.link_copied else R.string.copy_link),
                    color = Ink.Chalk,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        // Il n'a pas encore l'app : elle part d'ici, sans Play Store.
        OutlinedButton(
            onClick = { com.whisper.files.ShareApp.launch(context) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .height(50.dp),
            border = BorderStroke(1.dp, Ink.LineStrong)
        ) {
            Icon(Icons.Outlined.Share, null, tint = Ink.ChalkSoft, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.share_app), color = Ink.Chalk, style = MaterialTheme.typography.bodyMedium)
        }

        // --- Saisie manuelle : un lien reçu par un autre canal ---
        Text(
            text = stringResource(R.string.discover_manual_title),
            color = Ink.ChalkSoft,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 32.dp, bottom = 10.dp)
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Ink.Slate, RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            PrivateTextField(
                value = state.manualInput,
                onValueChange = viewModel::onManualInputChange,
                placeholder = stringResource(R.string.discover_manual_hint),
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                maxLines = 4,
                textSizeSp = 14f
            )
        }

        if (state.manualInput.isNotBlank()) {
            Button(
                onClick = { viewModel.addFromManualInput() },
                enabled = !state.isAdding,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .height(50.dp)
            ) {
                Text(stringResource(R.string.discover_add), style = MaterialTheme.typography.titleSmall)
            }
        }

        state.outcome?.let { outcome ->
            OutcomeBanner(
                outcome = outcome,
                onUnblock = { key -> viewModel.unblock(key) },
                onDismiss = viewModel::clearOutcome
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

/**
 * Le QR personnel, sur fond clair : sombre sur sombre, il ne se scanne pas
 * de façon fiable. L'empreinte courte permet une vérification orale —
 * comparer 64 caractères au téléphone n'est pas réaliste.
 */
@Composable
private fun MyCodePanel(publicKeyHex: String, qrPayload: String) {
    val qr = remember(qrPayload) { QrCodeGenerator.generateImageBitmap(qrPayload) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        qr?.let {
            Box(
                modifier = Modifier
                    .background(Ink.Chalk, RoundedCornerShape(24.dp))
                    .padding(18.dp)
            ) {
                Image(
                    bitmap = it,
                    contentDescription = stringResource(R.string.discover_mycode_title),
                    modifier = Modifier.size(220.dp)
                )
            }
        }
        Text(
            text = stringResource(R.string.discover_code_caption),
            color = Ink.Ash,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 14.dp)
        )
        Text(
            text = stringResource(R.string.discover_fingerprint),
            color = Ink.Ash,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 14.dp, bottom = 2.dp)
        )
        Text(
            text = ContactKeyParser.fingerprint(publicKeyHex),
            color = Ink.SageLight,
            style = MaterialTheme.typography.titleMedium
        )
    }
}

/**
 * Retour après tentative d'ajout.
 *
 * Chaque cas a son message : « échec » sans raison ne dit pas à l'utilisateur
 * s'il a scanné le mauvais code, son propre code, ou un contact déjà connu.
 */
@Composable
private fun OutcomeBanner(
    outcome: AddContactOutcome,
    onUnblock: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val (accent, message) = when (outcome) {
        is AddContactOutcome.Added ->
            Ink.Sage to stringResource(R.string.add_ok, outcome.contact.displayLabel)
        is AddContactOutcome.AlreadyKnown ->
            Ink.Ochre to stringResource(R.string.add_already, outcome.contact.displayLabel)
        is AddContactOutcome.WasBlocked ->
            Ink.Ochre to stringResource(R.string.add_blocked, outcome.contact.displayLabel)
        is AddContactOutcome.Unblocked ->
            Ink.Sage to stringResource(R.string.add_unblocked, outcome.contact.displayLabel)
        AddContactOutcome.SelfKey ->
            Ink.Ochre to stringResource(R.string.add_self)
        AddContactOutcome.MalformedKey ->
            Ink.Brick to stringResource(R.string.add_malformed)
        AddContactOutcome.NotAWhisperKey ->
            Ink.Brick to stringResource(R.string.add_not_whisper)
        is AddContactOutcome.Failed ->
            Ink.Brick to (outcome.reason ?: stringResource(R.string.common_error))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp)
            .background(Ink.Slate, RoundedCornerShape(16.dp))
            .padding(top = 12.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(Modifier.size(8.dp).background(accent, CircleShape))
            Text(text = message, color = Ink.Chalk, style = MaterialTheme.typography.bodyMedium)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End
        ) {
            if (outcome is AddContactOutcome.WasBlocked) {
                TextButton(onClick = { onUnblock(outcome.contact.publicKeyHex) }) {
                    Text(
                        text = stringResource(R.string.add_unblock),
                        color = Ink.Sage,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.common_dismiss),
                    color = Ink.Ash,
                    fontSize = 13.sp
                )
            }
        }
    }
}
