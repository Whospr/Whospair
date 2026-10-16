package com.whisper.util

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.whisper.security.SessionLifecycle

/**
 * Une permission, demandée au moment de l'usage — jamais au démarrage.
 * En attente : le micro des messages vocaux et de la voix sur Tor (11c, 11d).
 */
class Permission(val granted: Boolean, val request: () -> Unit)

@Composable
fun rememberPermission(permission: String, onResult: (Boolean) -> Unit = {}): Permission {
    val context = LocalContext.current
    var granted by remember(permission) {
        mutableStateOf(ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        SessionLifecycle.afterSystemDialog()
        granted = it
        onResult(it)
    }
    return Permission(granted) {
        // La fenêtre d'Android met l'app en arrière-plan : sans ce drapeau,
        // le verrouillage la tuait avant même la réponse.
        SessionLifecycle.beforeSystemDialog()
        launcher.launch(permission)
    }
}
