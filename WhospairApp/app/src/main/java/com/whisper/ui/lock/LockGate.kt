package com.whisper.ui.lock

import com.whisper.theme.Ink
import com.whisper.util.DebugLog
import android.app.Activity
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whisper.R
import com.whisper.security.SessionLifecycle
import com.whisper.security.UnlockResult
import com.whisper.security.Vault
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class LockStage { CREATE, CONFIRM, UNLOCK, BUSY, OPEN, WIPED }

data class LockUiState(
    val stage: LockStage,
    val error: Int? = null,
    val attemptsLeft: Int? = null,
    /** Validation automatique à cette longueur ; 0 = bouton ✓ (création). */
    val autoLength: Int = 0
)

@HiltViewModel
class LockViewModel @Inject constructor(
    private val vault: Vault,
    private val session: SessionLifecycle
) : ViewModel() {

    private val _state = MutableStateFlow(
        if (vault.hasPin()) unlockStage() else LockUiState(LockStage.CREATE)
    )

    private fun unlockStage(error: Int? = null, attemptsLeft: Int? = null) =
        LockUiState(LockStage.UNLOCK, error, attemptsLeft, vault.pinLength())
    val state = _state.asStateFlow()

    private var firstEntry: CharArray? = null

    fun submit(pin: CharArray) {
        when (_state.value.stage) {
            LockStage.CREATE -> {
                if (!Vault.isAcceptable(pin)) {
                    _state.value = LockUiState(LockStage.CREATE, R.string.lock_hint_setup)
                    return
                }
                firstEntry = pin
                // La confirmation connaît déjà la longueur : elle valide seule.
                _state.value = LockUiState(LockStage.CONFIRM, autoLength = pin.size)
            }
            LockStage.CONFIRM -> {
                val first = firstEntry
                firstEntry = null
                if (first == null || !first.contentEquals(pin)) {
                    first?.fill('\u0000')
                    _state.value = LockUiState(LockStage.CREATE, R.string.lock_mismatch)
                    return
                }
                first.fill('\u0000')
                attempt(pin) { vault.setup(it); UnlockResult.Success }
            }
            LockStage.UNLOCK -> attempt(pin) { vault.unlock(it) }
            else -> Unit
        }
    }

    /**
     * Argon2 prend du temps : l'écran passe en attente, puis le PIN est effacé de la mémoire.
     *
     * Une erreur inattendue (ouverture de la base, démarrage de Tor) est
     * affichée au lieu de planter : un plantage ici se répéterait à chaque
     * lancement, sans que l'utilisateur puisse rien y faire.
     */
    private fun attempt(pin: CharArray, block: suspend (CharArray) -> UnlockResult) {
        _state.value = _state.value.copy(stage = LockStage.BUSY)
        viewModelScope.launch {
            _state.value = try {
                unlockState(try { block(pin) } finally { pin.fill('\u0000') })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLog.e(TAG, "Échec à l'ouverture du coffre", e)
                if (vault.hasPin()) unlockStage(R.string.lock_error)
                else LockUiState(LockStage.CREATE, R.string.lock_error)
            }
        }
    }

    private fun unlockState(result: UnlockResult): LockUiState =
        when (result) {
            UnlockResult.Success -> {
                session.onUnlocked()
                LockUiState(LockStage.OPEN)
            }
            is UnlockResult.WrongPin -> unlockStage(
                R.string.lock_wrong,
                result.attemptsLeft.takeIf { it <= 5 }
            )
            UnlockResult.Wiped -> LockUiState(LockStage.WIPED)
        }

    private companion object {
        const val TAG = "LockViewModel"
    }
}

/**
 * Rien de l'app n'est composé tant que le coffre n'est pas ouvert.
 */
@Composable
fun LockGate(viewModel: LockViewModel = hiltViewModel(), content: @Composable () -> Unit) {
    val state by viewModel.state.collectAsState()
    val activity = LocalContext.current as Activity

    when (state.stage) {
        LockStage.OPEN -> content()
        // Redémarrage à froid : l'app renaît comme une installation neuve,
        // et plus rien de l'ancienne session ne reste en mémoire.
        LockStage.WIPED -> LaunchedEffect(Unit) { restartApp(activity) }
        else -> PinPad(state, onSubmit = viewModel::submit)
    }
}

/**
 * Tue le processus et relance l'app : tout ce qui vivait en mémoire (clés,
 * base ouverte, Tor) disparaît. Le PIN sera redemandé.
 */
fun restartApp(activity: Activity) {
    activity.startActivity(
        activity.packageManager.getLaunchIntentForPackage(activity.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    )
    android.os.Process.killProcess(android.os.Process.myPid())
}

@Composable
private fun PinPad(state: LockUiState, onSubmit: (CharArray) -> Unit) {
    val hint = when {
        state.attemptsLeft != null -> stringResource(R.string.lock_attempts_left, state.attemptsLeft)
        state.error != null -> stringResource(state.error)
        state.stage == LockStage.CREATE -> stringResource(R.string.lock_hint_setup)
        else -> ""
    }
    PinPad(
        title = stringResource(
            when (state.stage) {
                LockStage.CREATE -> R.string.lock_title_setup
                LockStage.CONFIRM -> R.string.lock_title_confirm
                LockStage.BUSY -> R.string.lock_busy
                else -> R.string.lock_title_unlock
            }
        ),
        hint = hint,
        hintIsError = state.error != null,
        busy = state.stage == LockStage.BUSY,
        resetKey = state,
        autoLength = state.autoLength,
        onSubmit = onSubmit
    )
}

/**
 * Clavier du PIN, dessiné par l'app : le clavier système
 * ne voit jamais un chiffre.
 *
 * Les chiffres vivent dans un CharArray effaçable, pas dans une String : une
 * String par frappe, c'est autant de copies du PIN que le ramasse-miettes
 * libère quand il veut, sans jamais les écraser.
 *
 * Grandes touches, en bas de l'écran : à portée du pouce sur un grand
 * téléphone tenu d'une main.
 *
 * @param resetKey tout changement vide la saisie (étape suivante, PIN refusé).
 * @param autoLength validation dès ce nombre de chiffres, sans ✓. 0 = ✓.
 */
@Composable
fun PinPad(
    title: String,
    hint: String,
    hintIsError: Boolean,
    busy: Boolean,
    resetKey: Any,
    autoLength: Int = 0,
    onSubmit: (CharArray) -> Unit
) {
    val buffer = remember { CharArray(MAX_PIN) }
    var length by remember { mutableIntStateOf(0) }
    LaunchedEffect(resetKey) { buffer.fill('\u0000'); length = 0 }
    DisposableEffect(Unit) { onDispose { buffer.fill('\u0000') } }

    fun submit() {
        // Le destinataire reçoit sa copie et l'efface lui-même.
        onSubmit(buffer.copyOf(length))
        buffer.fill('\u0000')
        length = 0
    }

    Column(
        Modifier.fillMaxSize().background(Ink.Night).padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(title, color = Ink.Chalk, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                hint,
                color = if (hintIsError) Ink.Brick else Ink.Ash,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(28.dp))
            // Un point par chiffre saisi, sans longueur annoncée : afficher la
            // taille attendue dirait à un observateur combien il lui en reste.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.height(12.dp)) {
                repeat(length) { Box(Modifier.size(10.dp).background(Ink.ChalkSoft, CircleShape)) }
            }
        }

        val rows = listOf("123", "456", "789", "⌫0✓")
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                row.forEach { key ->
                    // Longueur connue : pas de ✓, la dernière frappe valide.
                    val hidden = key == '✓' && autoLength > 0
                    Box(
                        Modifier
                            .size(84.dp)
                            .background(if (hidden) Color.Transparent else Ink.Slate, CircleShape)
                            .clickable(enabled = !busy && !hidden) {
                                when (key) {
                                    '⌫' -> if (length > 0) buffer[--length] = '\u0000'
                                    '✓' -> if (length > 0) submit()
                                    else -> if (length < MAX_PIN) {
                                        buffer[length++] = key
                                        if (autoLength > 0 && length == autoLength) submit()
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (!hidden) Text(key.toString(), color = Ink.Chalk, fontSize = 28.sp)
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

private const val MAX_PIN = 16
