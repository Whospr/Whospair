package com.whisper.shield

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ShieldViewModel @Inject constructor(
    val shield: ScreenShield,
    private val vault: com.whisper.security.Vault
) : ViewModel() {

    val pinLength: Int get() = vault.pinLength()

    /**
     * « Désactiver le masquage avec le code PIN » (1.3.7, tous les modes en
     * 1.3.8) : mêmes règles que l'écran de verrouillage — 10 échecs ou le PIN
     * de contrainte effacent tout, et l'app repart de zéro.
     */
    fun disableWithPin(pin: CharArray, onResult: (com.whisper.security.UnlockResult) -> Unit) {
        viewModelScope.launch {
            val result = try { vault.verify(pin) } finally { pin.fill('\u0000') }
            when (result) {
                com.whisper.security.UnlockResult.Success -> shield.escapeWithPin()
                com.whisper.security.UnlockResult.Wiped -> android.os.Process.killProcess(android.os.Process.myPid())
                else -> Unit
            }
            onResult(result)
        }
    }
}

/**
 * L'app déverrouillée, sous son voile : noir total quand ScreenShield le
 * demande, et aucun toucher ne passe au travers.
 */
@Composable
fun ShieldedContent(viewModel: ShieldViewModel = hiltViewModel(), content: @Composable () -> Unit) {
    val shield = viewModel.shield
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> shield.start()
                Lifecycle.Event.ON_PAUSE -> shield.stop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            shield.stop()
        }
    }
    val hidden by shield.hidden.collectAsState()
    val pauseOver by shield.pauseOver.collectAsState()
    var askChoice by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        content()
        // Curieux, code PIN juste saisi : pause de 10 min ou arrêt complet.
        // Pause finie : la prolonger, ou arrêter. Fermer garde le masquage.
        if ((askChoice || pauseOver) && !hidden) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { askChoice = false; shield.keepShield() },
                containerColor = com.whisper.theme.Ink.Raised,
                title = {
                    Text(stringResource(if (askChoice) com.whisper.R.string.shield_choice_title else com.whisper.R.string.shield_pause_over_title))
                },
                text = { if (!askChoice) Text(stringResource(com.whisper.R.string.shield_pause_over_body)) },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = { askChoice = false; shield.pauseOnlooker() }) {
                        Text(stringResource(if (askChoice) com.whisper.R.string.shield_pause_10 else com.whisper.R.string.shield_keep_paused))
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { askChoice = false; shield.gazeMode = ScreenShield.GazeMode.OFF; shield.keepShield() }) {
                        Text(stringResource(com.whisper.R.string.shield_disable_all))
                    }
                }
            )
        }
        if (hidden) {
            // Dans sa propre fenêtre, ouverte en dernier : elle passe devant
            // les dialogues déjà affichés (fiche contact, Paramètres) et
            // prend le focus au clavier, qui se ferme.
            Dialog(
                onDismissRequest = {},
                properties = DialogProperties(
                    dismissOnBackPress = false,
                    dismissOnClickOutside = false,
                    usePlatformDefaultWidth = false,
                    decorFitsSystemWindows = false
                )
            ) {
                var askPin by remember { mutableStateOf(false) }
                var hint by remember { mutableStateOf<String?>(null) }
                var reset by remember { mutableIntStateOf(0) }
                var checking by remember { mutableStateOf(false) }
                val wrong = stringResource(com.whisper.R.string.lock_wrong)
                val context = androidx.compose.ui.platform.LocalContext.current
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) awaitPointerEvent().changes.forEach { it.consume() }
                            }
                        },
                    contentAlignment = Alignment.BottomCenter
                ) {
                    // Le code PIN, porte de sortie de chaque mode caméra (1.3.8) :
                    // visage plus reconnu, pièce sombre, écran à montrer. Aucun
                    // autre texte : l'écran noir n'explique rien à un curieux.
                    val mode = shield.gazeMode
                    if (mode != ScreenShield.GazeMode.OFF) {
                        if (askPin) {
                            com.whisper.ui.lock.PinPad(
                                title = stringResource(com.whisper.R.string.face_disable_pin),
                                hint = hint ?: "",
                                hintIsError = hint != null,
                                busy = checking,
                                resetKey = reset,
                                autoLength = viewModel.pinLength,
                                onSubmit = { pin ->
                                    // Pas de seconde saisie avant la réponse : le compteur d'essais
                                    // doit avancer à chaque PIN (voir Vault.attempts).
                                    if (checking) { pin.fill('\u0000'); return@PinPad }
                                    checking = true
                                    viewModel.disableWithPin(pin) { result ->
                                        checking = false
                                        if (result == com.whisper.security.UnlockResult.Success) {
                                            askPin = false
                                            if (mode == ScreenShield.GazeMode.ONLOOKER) {
                                                // Pause tout de suite : l'écran se rouvre et demande la suite.
                                                shield.pauseOnlooker()
                                                askChoice = true
                                            }
                                        }
                                        if (result is com.whisper.security.UnlockResult.WrongPin) {
                                            hint = if (result.attemptsLeft <= 5) {
                                                context.getString(com.whisper.R.string.lock_attempts_left, result.attemptsLeft)
                                            } else wrong
                                            reset++
                                        }
                                    }
                                }
                            )
                        } else {
                            androidx.compose.material3.TextButton(
                                onClick = { askPin = true },
                                modifier = Modifier.padding(bottom = 48.dp)
                            ) {
                                Text(stringResource(com.whisper.R.string.face_disable_pin), color = Color(0xFF8A8E97))
                            }
                        }
                    }
                }
            }
        }
    }
}
