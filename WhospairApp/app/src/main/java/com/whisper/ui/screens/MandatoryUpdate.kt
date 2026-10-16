package com.whisper.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.whisper.R
import com.whisper.security.SessionLifecycle
import com.whisper.theme.Ink
import com.whisper.update.UpdateManager
import com.whisper.ui.components.screenBackground

/**
 * « Mise à jour obligatoire » (1.3.8) : 120 h, comptées par l'horloge
 * interne, après que ce téléphone a appris qu'une version plus récente
 * existe. Passé ce délai, l'app ne s'ouvre plus que sur cet écran : ses
 * contacts à jour refusent déjà les anciennes versions. Le téléchargement
 * et l'installation restent possibles d'ici, rien d'autre.
 */
@Composable
fun MandatoryUpdateGate(viewModel: SettingsViewModel = hiltViewModel(), content: @Composable () -> Unit) {
    val mandatory by viewModel.updateMandatory.collectAsState()
    if (!mandatory) return content()

    val context = LocalContext.current
    val update by viewModel.update.collectAsState()
    val retrying by viewModel.updateRetrying.collectAsState()
    val checking by viewModel.updateChecking.collectAsState()
    var systemTick by remember { mutableIntStateOf(0) }
    val systemScreen = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { systemTick++ }
    val canInstall = remember(systemTick) { context.packageManager.canRequestPackageInstalls() }
    val offered = when (val u = update) {
        is UpdateManager.State.Available -> u.manifest
        is UpdateManager.State.Failed -> u.manifest
        else -> null
    }

    Box(
        Modifier
            .fillMaxSize()
            .screenBackground()
            .systemBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(stringResource(R.string.update_mandatory_title), style = MaterialTheme.typography.headlineSmall, color = Ink.Chalk)
            Text(stringResource(R.string.update_mandatory_body), style = MaterialTheme.typography.bodyLarge, color = Ink.ChalkSoft)
            when (val u = update) {
                is UpdateManager.State.Downloading -> {
                    Text(stringResource(R.string.update_state_downloading, u.percent), color = Ink.Chalk)
                    LinearProgressIndicator(
                        progress = { u.percent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = Ink.Sage,
                        trackColor = Ink.Slate
                    )
                    if (retrying) {
                        Text(stringResource(R.string.update_diffuser_retry), style = MaterialTheme.typography.bodySmall, color = Ink.Ash)
                    }
                    TextButton(onClick = viewModel::cancelUpdate) { Text(stringResource(R.string.update_cancel), color = Ink.Brick) }
                }
                is UpdateManager.State.Installing -> Text(stringResource(R.string.update_state_installing), color = Ink.Chalk)
                is UpdateManager.State.Failed -> Text(stringResource(u.message), color = Ink.Brick)
                else -> Unit
            }
            when {
                offered != null && !canInstall -> {
                    Text(stringResource(R.string.update_permission_hint), style = MaterialTheme.typography.bodySmall, color = Ink.Ash)
                    Button(
                        onClick = {
                            SessionLifecycle.beforeSystemDialog(SessionLifecycle.FILE_PICKER_GRACE_MS)
                            systemScreen.launch(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    android.net.Uri.parse("package:" + context.packageName)
                                )
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Ink.Sage, contentColor = Ink.Night)
                    ) { Text(stringResource(R.string.update_permission_allow)) }
                }
                offered != null -> Button(
                    onClick = viewModel::downloadUpdate,
                    colors = ButtonDefaults.buttonColors(containerColor = Ink.Sage, contentColor = Ink.Night)
                ) {
                    Text(stringResource(if (update is UpdateManager.State.Failed) R.string.update_retry else R.string.update_download))
                }
                update is UpdateManager.State.Idle -> TextButton(onClick = viewModel::checkUpdate, enabled = !checking) {
                    Text(stringResource(if (checking) R.string.update_checking else R.string.update_check))
                }
            }
        }
    }
}
