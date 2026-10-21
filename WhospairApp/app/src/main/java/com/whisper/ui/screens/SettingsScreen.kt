package com.whisper.ui.screens

import com.whisper.ui.icons.outlined.Share
import com.whisper.theme.AppLanguage
import com.whisper.theme.Appearance
import com.whisper.ui.icons.outlined.Globe
import com.whisper.theme.ChatBackground
import com.whisper.theme.Ink
import com.whisper.theme.ThemeMode
import com.whisper.ui.components.CardRow
import com.whisper.ui.components.GroupCard
import com.whisper.ui.components.LocalBottomBarSpace
import com.whisper.ui.components.glass
import com.whisper.ui.components.screenBackground
import com.whisper.ui.icons.outlined.Image
import com.whisper.ui.icons.outlined.Moon
import com.whisper.ui.icons.automirrored.rounded.ArrowBack
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whisper.R
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import com.whisper.ui.icons.Icons
import com.whisper.ui.icons.outlined.Autorenew
import com.whisper.ui.icons.outlined.DeleteForever
import com.whisper.ui.icons.outlined.Hub
import com.whisper.ui.icons.outlined.Info
import com.whisper.ui.icons.outlined.Memory
import com.whisper.ui.icons.outlined.Numbers
import com.whisper.ui.icons.outlined.PrivacyTip
import com.whisper.ui.icons.outlined.Lock
import com.whisper.ui.icons.outlined.LockReset
import com.whisper.ui.icons.outlined.Password
import com.whisper.ui.icons.outlined.QrCode2
import com.whisper.ui.icons.outlined.Timer
import com.whisper.ui.icons.outlined.VisibilityOff
import com.whisper.ui.icons.outlined.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.whisper.repositories.ContactRepository
import com.whisper.repositories.MessageRepository
import com.whisper.security.Vault
import com.whisper.services.IdentityService
import com.whisper.services.MessageService
import com.whisper.services.StorageService
import com.whisper.services.ProfilePhotoService
import com.whisper.shield.ScreenShield
import com.whisper.util.rememberPermission
import com.whisper.ui.icons.outlined.FaceRetouchingOff
import com.whisper.security.SessionLifecycle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.whisper.ui.icons.outlined.AddAPhoto
import com.whisper.ui.icons.outlined.HideImage
import com.whisper.ui.icons.outlined.Visibility
import com.whisper.tor.TorRuntime
import com.whisper.reach.Reachability
import com.whisper.ui.icons.outlined.WifiTethering
import com.whisper.ui.icons.outlined.SystemUpdate
import androidx.compose.material3.LinearProgressIndicator
import com.whisper.update.UpdateManager
import androidx.core.app.NotificationManagerCompat
import com.whisper.ui.lock.PinPad
import com.whisper.ui.lock.restartApp
import com.whisper.viewmodels.IdentityViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import javax.inject.Inject

/**
 * Paramètres : sécurité, identité, réseau, informations.
 */
@Composable
fun SettingsScreen(
    identityViewModel: IdentityViewModel = hiltViewModel(),
    viewModel: SettingsViewModel = hiltViewModel(),
    paddingValues: PaddingValues,
    onOpenDiscover: () -> Unit = {}
) {
    val identityState by identityViewModel.viewState.collectAsState()
    val tor by viewModel.torState.collectAsState()
    val defaultTtl by viewModel.defaultTtl.collectAsState()
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }
    val activity = LocalContext.current as Activity
    val muted = Ink.Ash
    val photo by viewModel.photo.collectAsState()
    val photoVisibility by viewModel.photoVisibility.collectAsState()
    val photoError by viewModel.photoError.collectAsState()
    val gazeMode by viewModel.gazeMode.collectAsState()
    val gazeSchedule by viewModel.gazeSchedule.collectAsState()
    val stayReachable by viewModel.stayReachable.collectAsState()
    // Relu au retour de la fenêtre d'Android (batterie, notifications).
    var systemTick by remember { mutableStateOf(0) }
    val notifications = rememberPermission(android.Manifest.permission.POST_NOTIFICATIONS) {
        // Refusées ou non, l'option marche : les messages attendent le PIN.
        viewModel.setStayReachable(true)
        systemTick++
    }
    val systemScreen = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        SessionLifecycle.afterSystemDialog()
        systemTick++
    }
    // Le mode choisi attend la réponse à la demande de caméra.
    var pendingGaze by remember { mutableStateOf<ScreenShield.GazeMode?>(null) }
    // « Seulement mon visage » : explication d'abord, puis enregistrement du visage.
    var faceIntro by remember { mutableStateOf(false) }
    val enrolling by viewModel.enrolling.collectAsState()
    val enrolled by viewModel.enrolled.collectAsState()
    val camera = rememberPermission(android.Manifest.permission.CAMERA) { granted ->
        when (val wanted = pendingGaze?.takeIf { granted }) {
            null -> Unit
            ScreenShield.GazeMode.OWNER -> faceIntro = true
            else -> viewModel.setGazeMode(wanted)
        }
        pendingGaze = null
    }
    // Sélecteur de documents d'Android (AOSP) : PickVisualMedia peut passer,
    // sur Android 10-11, par un sélecteur fourni par un service tiers.
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        SessionLifecycle.afterSystemDialog()
        uri?.let(viewModel::setPhoto)
    }
    val update by viewModel.update.collectAsState()

    val language = remember { AppLanguage.current(activity) }
    // Les rubriques s'ouvrent en pages ; Retour ramène à la liste.
    var page by remember { mutableStateOf<SettingsPage?>(null) }
    BackHandler(enabled = page != null) { page = null }
    val bottomSpace = LocalBottomBarSpace.current
    val torLabel = when (val t = tor) {
        is TorRuntime.State.Ready -> stringResource(R.string.tor_state_ready)
        is TorRuntime.State.Starting ->
            if (t.reconnecting) stringResource(R.string.tor_state_reconnecting)
            else stringResource(R.string.tor_state_starting, t.progress)
        is TorRuntime.State.Failed -> stringResource(R.string.tor_state_failed)
        TorRuntime.State.Off -> stringResource(R.string.tor_state_off)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .screenBackground()
            .padding(paddingValues)
            .verticalScroll(rememberScrollState())
            .padding(bottom = bottomSpace + 16.dp)
    ) {
        when (page) {
            null -> {
                ProfileHeader(
                    photo = photo,
                    error = photoError,
                    fingerprint = identityState.publicKeyHex?.let { com.whisper.util.ContactKeyParser.fingerprint(it) },
                    onChoose = {
                        SessionLifecycle.beforeSystemDialog(SessionLifecycle.PHOTO_PICKER_GRACE_MS)
                        pickPhoto.launch(arrayOf("image/*"))
                    },
                    onRemove = viewModel::clearPhoto,
                    onQr = onOpenDiscover
                )
                Spacer(Modifier.height(20.dp))
                // Hors Play Store : l'app s'envoie elle-même.
                GroupCard {
                    CardRow(
                        Icons.Outlined.Share, Ink.TileSage,
                        stringResource(R.string.share_app),
                        stringResource(R.string.share_app_sub),
                        onClick = { com.whisper.files.ShareApp.launch(activity) }
                    )
                }
                Spacer(Modifier.height(14.dp))
                GroupCard {
                    CardRow(
                        Icons.Outlined.Globe, Ink.TileTeal,
                        stringResource(R.string.settings_language),
                        AppLanguage.nameOf(language) ?: stringResource(R.string.language_auto),
                        onClick = { dialog = SettingsDialog.LANGUAGE }
                    )
                    CardRow(
                        Icons.Outlined.Moon, Ink.TileGrey,
                        stringResource(R.string.settings_appearance),
                        stringResource(appearanceLabel(Appearance.mode)),
                        onClick = { dialog = SettingsDialog.APPEARANCE }
                    )
                    CardRow(
                        Icons.Outlined.Image, Ink.TileMauve,
                        stringResource(R.string.settings_background),
                        stringResource(ChatBackground.current.label),
                        onClick = { dialog = SettingsDialog.BACKGROUND }
                    ) { BackgroundSwatch(ChatBackground.current, 28.dp) }
                }
                Spacer(Modifier.height(14.dp))
                GroupCard {
                    CardRow(
                        Icons.Outlined.Lock, Ink.TileSage,
                        stringResource(R.string.settings_page_lock),
                        stringResource(R.string.settings_page_lock_sub),
                        onClick = { page = SettingsPage.LOCK }
                    ) { Chevron() }
                    CardRow(
                        Icons.Outlined.VisibilityOff, Ink.TileSlate,
                        stringResource(R.string.settings_page_discretion),
                        stringResource(R.string.settings_page_discretion_sub),
                        onClick = { page = SettingsPage.DISCRETION }
                    ) { Chevron() }
                    CardRow(
                        Icons.Outlined.Hub, Ink.TileOchre,
                        stringResource(R.string.settings_page_network),
                        torLabel + if (stayReachable) " · " + stringResource(R.string.reach_short) else "",
                        onClick = { page = SettingsPage.NETWORK }
                    ) {
                        if (tor is TorRuntime.State.Ready) {
                            Box(Modifier.size(9.dp).background(Ink.Sage, CircleShape))
                        }
                        Chevron()
                    }
                    CardRow(
                        Icons.Outlined.QrCode2, Ink.TileTeal,
                        stringResource(R.string.settings_section_identity),
                        stringResource(R.string.settings_page_identity_sub),
                        onClick = { page = SettingsPage.IDENTITY }
                    ) { Chevron() }
                    CardRow(
                        Icons.Outlined.SystemUpdate, Ink.TileMauve,
                        stringResource(R.string.update_title),
                        updateLabel(update),
                        onClick = { dialog = SettingsDialog.UPDATE }
                    )
                    CardRow(
                        Icons.Outlined.Info, Ink.TileGrey,
                        stringResource(R.string.settings_section_about),
                        stringResource(R.string.settings_page_about_sub),
                        onClick = { page = SettingsPage.ABOUT }
                    ) { Chevron() }
                }
                Spacer(Modifier.height(14.dp))
                GroupCard {
                    CardRow(
                        Icons.Outlined.DeleteForever, Ink.TileBrick,
                        stringResource(R.string.settings_wipe_now),
                        danger = true,
                        onClick = { dialog = SettingsDialog.WIPE }
                    )
                }
            }
            SettingsPage.LOCK -> SubPage(R.string.settings_page_lock, onBack = { page = null }) {
                CardRow(Icons.Outlined.Lock, Ink.TileSage, stringResource(R.string.settings_lock_now), onClick = { SessionLifecycle.lockNow() })
                CardRow(Icons.Outlined.Password, Ink.TileSlate, stringResource(R.string.settings_change_pin), onClick = { dialog = SettingsDialog.CHANGE_PIN })
                CardRow(Icons.Outlined.LockReset, Ink.TileOchre, stringResource(R.string.settings_duress_title), onClick = { dialog = SettingsDialog.DURESS })
            }
            SettingsPage.DISCRETION -> SubPage(R.string.settings_page_discretion, onBack = { page = null }) {
                CardRow(
                    Icons.Outlined.Timer, Ink.TileSage,
                    stringResource(R.string.settings_default_ttl),
                    stringResource(viewModel.ttlPresets.firstOrNull { it.minutes == defaultTtl }?.labelRes ?: R.string.ttl_8h),
                    onClick = { dialog = SettingsDialog.TTL }
                )
                CardRow(Icons.Outlined.VisibilityOff, Ink.TileSlate, stringResource(R.string.settings_mask_all), onClick = { dialog = SettingsDialog.MASK_ALL })
                CardRow(
                    Icons.Outlined.FaceRetouchingOff, Ink.TileMauve,
                    stringResource(R.string.settings_gaze_shield),
                    stringResource(gazeLabel(gazeMode)),
                    onClick = { dialog = SettingsDialog.GAZE_SHIELD }
                )
                CardRow(
                    Icons.Outlined.Visibility, Ink.TileTeal,
                    stringResource(R.string.profile_photo_visibility),
                    stringResource(visibilityLabel(photoVisibility)),
                    onClick = { dialog = SettingsDialog.PHOTO_VISIBILITY }
                )
            }
            SettingsPage.NETWORK -> SubPage(R.string.settings_page_network, onBack = { page = null }) {
                CardRow(Icons.Outlined.Hub, Ink.TileOchre, stringResource(R.string.settings_tor_state), torLabel, onClick = { dialog = SettingsDialog.RESTART_TOR })
                CardRow(
                    Icons.Outlined.WifiTethering, Ink.TileSage,
                    stringResource(R.string.settings_stay_reachable),
                    stringResource(if (stayReachable) R.string.settings_on else R.string.settings_off),
                    onClick = { dialog = SettingsDialog.STAY_REACHABLE }
                )
                CardRow(Icons.Outlined.VpnKey, Ink.TileSlate, stringResource(R.string.vpn_advice_title), onClick = { dialog = SettingsDialog.NETWORK })
                CardRow(Icons.Outlined.Memory, Ink.TileGrey, stringResource(R.string.memory_advice_title), onClick = { dialog = SettingsDialog.MEMORY })
            }
            SettingsPage.IDENTITY -> SubPage(R.string.settings_section_identity, onBack = { page = null }) {
                CardRow(Icons.Outlined.QrCode2, Ink.TileTeal, stringResource(R.string.settings_my_qr), onClick = onOpenDiscover)
                CardRow(Icons.Outlined.Autorenew, Ink.TileBrick, stringResource(R.string.settings_new_identity), danger = true, onClick = { dialog = SettingsDialog.NEW_IDENTITY })
            }
            SettingsPage.ABOUT -> SubPage(R.string.settings_section_about, onBack = { page = null }) {
                CardRow(Icons.Outlined.Info, Ink.TileGrey, stringResource(R.string.about_title), onClick = { dialog = SettingsDialog.ABOUT })
                CardRow(Icons.Outlined.PrivacyTip, Ink.TileSage, stringResource(R.string.privacy_title), onClick = { dialog = SettingsDialog.PRIVACY })
                CardRow(Icons.Outlined.Info, Ink.TileSlate, stringResource(R.string.licenses_title), onClick = { dialog = SettingsDialog.LICENSES })
                CardRow(Icons.Outlined.Numbers, Ink.TileSlate, stringResource(R.string.version_title), remember { versionName(activity) }, onClick = { dialog = SettingsDialog.VERSION })
            }
        }
    }

    if (faceIntro) {
        AlertDialog(
            onDismissRequest = { faceIntro = false },
            containerColor = Ink.Raised,
            title = { Text(stringResource(R.string.face_intro_title)) },
            text = {
                Text(
                    stringResource(R.string.face_intro_body),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            },
            confirmButton = {
                TextButton(onClick = { faceIntro = false; viewModel.enrollOwner() }) {
                    Text(stringResource(R.string.face_intro_ok))
                }
            },
            dismissButton = { TextButton(onClick = { faceIntro = false }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }
    if (enrolling) {
        AlertDialog(
            onDismissRequest = {},
            containerColor = Ink.Raised,
            title = { Text(stringResource(R.string.face_enroll_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.face_enroll_body))
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = Ink.Chalk, trackColor = Ink.Night)
                }
            },
            confirmButton = {}
        )
    }
    enrolled?.let { ok ->
        AlertDialog(
            onDismissRequest = viewModel::clearEnrolled,
            containerColor = Ink.Raised,
            text = { Text(stringResource(if (ok) R.string.face_enroll_ok else R.string.face_enroll_fail)) },
            confirmButton = {
                if (ok) TextButton(onClick = viewModel::clearEnrolled) { Text(stringResource(R.string.indicator_dismiss)) }
                else TextButton(onClick = { viewModel.clearEnrolled(); viewModel.enrollOwner() }) { Text(stringResource(R.string.update_retry)) }
            },
            dismissButton = if (ok) null else {
                { TextButton(onClick = viewModel::clearEnrolled) { Text(stringResource(R.string.common_cancel)) } }
            }
        )
    }

    val close = { dialog = null }
    when (dialog) {
        SettingsDialog.DURESS -> DuressDialog(viewModel, close)
        SettingsDialog.CHANGE_PIN -> ChangePinDialog(viewModel, close)
        SettingsDialog.MASK_ALL -> MaskAllDialog(viewModel, close)
        SettingsDialog.TTL -> AlertDialog(
            onDismissRequest = close,
            title = { Text(stringResource(R.string.settings_default_ttl)) },
            text = {
                Column {
                    Text(stringResource(R.string.settings_default_ttl_hint), style = MaterialTheme.typography.bodySmall)
                    viewModel.ttlPresets.forEach { preset ->
                        Row(
                            Modifier.fillMaxWidth().clickable { viewModel.setDefaultTtl(preset.minutes); close() },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = preset.minutes == defaultTtl, onClick = null)
                            Text(stringResource(preset.labelRes), Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = close) { Text(stringResource(R.string.common_cancel)) } }
        )
        SettingsDialog.WIPE -> ConfirmDialog(R.string.settings_wipe_confirm, R.string.settings_wipe_action, close) {
            viewModel.wipeNow { restartApp(activity) }
        }
        SettingsDialog.NEW_IDENTITY -> ConfirmDialog(R.string.settings_new_identity_confirm, R.string.settings_new_identity_action, close) {
            viewModel.newIdentity { restartApp(activity) }
        }
        SettingsDialog.RESTART_TOR -> ConfirmDialog(R.string.settings_restart_tor_confirm, R.string.settings_restart_tor_action, close, danger = false) {
            restartApp(activity)
        }
        SettingsDialog.GAZE_SHIELD -> AlertDialog(
            onDismissRequest = close,
            title = { Text(stringResource(R.string.settings_gaze_shield)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.settings_gaze_shield_body), style = MaterialTheme.typography.bodySmall)
                    ScreenShield.GazeMode.entries
                        .filter { it != ScreenShield.GazeMode.OWNER || viewModel.ownerAvailable }
                        .forEach { option ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable {
                                    // Regard détourné : le dialogue reste ouvert pour choisir manuel ou automatique.
                                    if (option != ScreenShield.GazeMode.LOOK_AWAY || !camera.granted) close()
                                    when {
                                        option == ScreenShield.GazeMode.OFF -> viewModel.setGazeMode(option)
                                        !camera.granted -> { pendingGaze = option; camera.request() }
                                        option == ScreenShield.GazeMode.OWNER -> faceIntro = true
                                        else -> viewModel.setGazeMode(option)
                                    }
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = option == gazeMode, onClick = null)
                            Column(Modifier.padding(start = 8.dp)) {
                                Text(stringResource(gazeLabel(option)))
                                if (option == ScreenShield.GazeMode.LOOK_AWAY && gazeMode == ScreenShield.GazeMode.LOOK_AWAY) {
                                    ScreenShield.Schedule.entries.forEach { choice ->
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .heightIn(min = 48.dp)
                                                .clickable { viewModel.setGazeSchedule(choice) },
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            RadioButton(selected = choice == gazeSchedule, onClick = null)
                                            Column(Modifier.padding(start = 8.dp)) {
                                                Text(stringResource(if (choice == ScreenShield.Schedule.AUTO) R.string.gaze_auto else R.string.gaze_manual))
                                                Text(
                                                    stringResource(if (choice == ScreenShield.Schedule.AUTO) R.string.gaze_auto_hint else R.string.gaze_manual_hint),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = Ink.Ash
                                                )
                                            }
                                        }
                                    }
                                }
                                if (option == ScreenShield.GazeMode.ONLOOKER) {
                                    Text(
                                        stringResource(R.string.settings_gaze_onlooker_hint),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Ink.Ash
                                    )
                                }
                                if (option == ScreenShield.GazeMode.OWNER) {
                                    Text(
                                        stringResource(R.string.settings_gaze_owner_hint),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Ink.Ash
                                    )
                                    if (gazeMode == ScreenShield.GazeMode.OWNER) {
                                        TextButton(onClick = { close(); faceIntro = true }) {
                                            Text(stringResource(R.string.face_reenroll))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = close) { Text(stringResource(R.string.common_cancel)) } }
        )
        SettingsDialog.PHOTO_VISIBILITY -> AlertDialog(
            onDismissRequest = close,
            containerColor = Ink.Raised,
            title = { Text(stringResource(R.string.profile_photo_visibility)) },
            text = {
                Column {
                    ProfilePhotoService.Visibility.entries.forEach { option ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable { viewModel.setPhotoVisibility(option); close() },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = option == photoVisibility, onClick = null)
                            Column(Modifier.padding(start = 8.dp)) {
                                Text(stringResource(visibilityLabel(option)))
                                if (option == ProfilePhotoService.Visibility.EVERYONE) {
                                    Text(
                                        stringResource(R.string.profile_photo_everyone_hint),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Ink.Ash
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = close) { Text(stringResource(R.string.common_cancel)) } }
        )
        SettingsDialog.STAY_REACHABLE -> {
            val notificationsOn = remember(systemTick) { NotificationManagerCompat.from(activity).areNotificationsEnabled() }
            val batteryFree = remember(systemTick) {
                activity.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(activity.packageName)
            }
            AlertDialog(
                onDismissRequest = close,
                containerColor = Ink.Raised,
                title = { Text(stringResource(R.string.settings_stay_reachable)) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.settings_stay_reachable_body))
                        Text(stringResource(R.string.settings_stay_reachable_costs), style = MaterialTheme.typography.bodySmall, color = Ink.Ash)
                        if (stayReachable && !notificationsOn) {
                            Text(stringResource(R.string.settings_reach_notifications_off), style = MaterialTheme.typography.bodySmall, color = Ink.Brick)
                            // La page d'Android plutôt que la demande : elle marche
                            // aussi après un refus, ou si l'app entière est coupée.
                            TextButton(onClick = {
                                SessionLifecycle.beforeSystemDialog()
                                systemScreen.launch(
                                    android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, activity.packageName)
                                )
                            }) { Text(stringResource(R.string.settings_reach_notifications_allow)) }
                        }
                        if (stayReachable && !batteryFree) {
                            Text(stringResource(R.string.settings_reach_battery_hint), style = MaterialTheme.typography.bodySmall, color = Ink.Ash)
                            TextButton(onClick = {
                                SessionLifecycle.beforeSystemDialog()
                                systemScreen.launch(
                                    android.content.Intent(
                                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                        android.net.Uri.parse("package:" + activity.packageName)
                                    )
                                )
                            }) { Text(stringResource(R.string.settings_reach_battery)) }
                        }
                        if (stayReachable) {
                            // Android exige une notification pour tout service en arrière-plan ;
                            // on peut la masquer dans ses réglages, le service continue.
                            // L'état « Joignable » s'affiche dans l'app, à côté de Tor.
                            Text(stringResource(R.string.settings_reach_hide_hint), style = MaterialTheme.typography.bodySmall, color = Ink.Ash)
                            TextButton(onClick = {
                                SessionLifecycle.beforeSystemDialog()
                                systemScreen.launch(
                                    android.content.Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, activity.packageName)
                                        .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, com.whisper.reach.ReachNotifications.CHANNEL_SERVICE)
                                )
                            }) { Text(stringResource(R.string.settings_reach_hide)) }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        when {
                            stayReachable -> viewModel.setStayReachable(false)
                            android.os.Build.VERSION.SDK_INT >= 33 && !notifications.granted -> notifications.request()
                            else -> viewModel.setStayReachable(true)
                        }
                    }) {
                        Text(
                            stringResource(if (stayReachable) R.string.settings_reach_disable else R.string.settings_reach_enable),
                            color = if (stayReachable) Ink.Brick else Color.Unspecified
                        )
                    }
                },
                dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.indicator_dismiss)) } }
            )
        }
        SettingsDialog.UPDATE -> {
            val relaying by viewModel.updateRelaying.collectAsState()
            val checking by viewModel.updateChecking.collectAsState()
            val retrying by viewModel.updateRetrying.collectAsState()
            val notes by viewModel.updateNotes.collectAsState()
            val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
            val canInstall = remember(systemTick) { activity.packageManager.canRequestPackageInstalls() }
            val allowInstall = {
                SessionLifecycle.beforeSystemDialog(SessionLifecycle.FILE_PICKER_GRACE_MS)
                systemScreen.launch(
                    android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        android.net.Uri.parse("package:" + activity.packageName)
                    )
                )
            }
            val offered = when (val u = update) {
                is UpdateManager.State.Available -> u.manifest
                is UpdateManager.State.Failed -> u.manifest
                else -> null
            }
            AlertDialog(
                onDismissRequest = close,
                containerColor = Ink.Raised,
                title = { Text(stringResource(R.string.update_title)) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        when (val u = update) {
                            is UpdateManager.State.Available -> {
                                Text(
                                    stringResource(R.string.update_available_body, u.manifest.versionName, com.whisper.files.FilePayload.sizeLabel(u.manifest.size))
                                )
                                // Notes signées par le développeur (1.3.6) : ce que contient la version.
                                notes?.takeIf { it.versionCode == u.manifest.versionCode }?.textFor(locale)?.let { text ->
                                    Text(stringResource(R.string.update_notes_title), style = MaterialTheme.typography.titleSmall)
                                    Text(text, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            is UpdateManager.State.Downloading -> {
                                Text(stringResource(R.string.update_state_downloading, u.percent))
                                LinearProgressIndicator(
                                    progress = { u.percent / 100f },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = Ink.Chalk,
                                    trackColor = Ink.Night
                                )
                                if (retrying) {
                                    Text(stringResource(R.string.update_diffuser_retry), style = MaterialTheme.typography.bodySmall, color = Ink.Ash)
                                }
                                // Bloqué ? Recontacter la source tout de suite (1.4.1).
                                TextButton(onClick = viewModel::retryUpdateNow) { Text(stringResource(R.string.update_retry_now)) }
                            }
                            is UpdateManager.State.Installing -> Text(stringResource(R.string.update_state_installing))
                            is UpdateManager.State.Failed -> Text(stringResource(u.message), color = Ink.Brick)
                            UpdateManager.State.Idle -> Text(stringResource(R.string.update_state_current) + " · " + versionName(activity))
                        }
                        if (offered != null && !canInstall) {
                            Text(stringResource(R.string.update_permission_hint), style = MaterialTheme.typography.bodySmall, color = Ink.Ash)
                        }
                        Text(stringResource(R.string.update_body), style = MaterialTheme.typography.bodySmall, color = Ink.Ash)
                        relaying?.let {
                            Text(stringResource(R.string.update_relaying, it.versionName), style = MaterialTheme.typography.bodySmall, color = Ink.Ash)
                        }
                        // Pas d'import de paquet dans l'app publique : il n'existe
                        // que dans l'app diffuseur. Ici, on interroge le diffuseur.
                        if (update is UpdateManager.State.Idle || update is UpdateManager.State.Failed) {
                            TextButton(onClick = viewModel::checkUpdate, enabled = !checking) {
                                Text(stringResource(if (checking) R.string.update_checking else R.string.update_check))
                            }
                        }
                    }
                },
                confirmButton = {
                    when {
                        update is UpdateManager.State.Downloading ->
                            TextButton(onClick = viewModel::cancelUpdate) { Text(stringResource(R.string.update_cancel), color = Ink.Brick) }
                        offered != null && !canInstall ->
                            TextButton(onClick = allowInstall) { Text(stringResource(R.string.update_permission_allow)) }
                        offered != null ->
                            TextButton(onClick = viewModel::downloadUpdate) {
                                Text(stringResource(if (update is UpdateManager.State.Failed) R.string.update_retry else R.string.update_download))
                            }
                    }
                },
                dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.indicator_dismiss)) } }
            )
        }
        SettingsDialog.LANGUAGE -> AlertDialog(
            onDismissRequest = close,
            containerColor = Ink.Raised,
            title = { Text(stringResource(R.string.settings_language)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    (listOf("" to stringResource(R.string.language_auto)) + AppLanguage.options).forEach { (tag, name) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable {
                                    close()
                                    if (tag != language) viewModel.setLanguage(activity, tag)
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = tag == language, onClick = null)
                            Text(name, Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = close) { Text(stringResource(R.string.common_cancel)) } }
        )
        SettingsDialog.APPEARANCE -> AlertDialog(
            onDismissRequest = close,
            containerColor = Ink.Raised,
            title = { Text(stringResource(R.string.settings_appearance)) },
            text = {
                Column {
                    ThemeMode.entries.forEach { option ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable { viewModel.setThemeMode(option); close() },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = option == Appearance.mode, onClick = null)
                            Column(Modifier.padding(start = 8.dp)) {
                                Text(stringResource(appearanceLabel(option)))
                                if (option == ThemeMode.AUTO) {
                                    Text(stringResource(R.string.appearance_auto_hint), style = MaterialTheme.typography.bodySmall, color = Ink.Ash)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = close) { Text(stringResource(R.string.common_cancel)) } }
        )
        SettingsDialog.BACKGROUND -> AlertDialog(
            onDismissRequest = close,
            containerColor = Ink.Raised,
            title = { Text(stringResource(R.string.settings_background)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(stringResource(R.string.settings_background_hint), style = MaterialTheme.typography.bodySmall, color = Ink.Ash)
                    // Trois par rangée : un aperçu du fond et son nom.
                    ChatBackground.entries.chunked(3).forEach { rowItems ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            rowItems.forEach { option ->
                                val chosen = option == ChatBackground.current
                                Column(
                                    Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(16.dp))
                                        .clickable { viewModel.setBackground(option) }
                                        .padding(4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .height(96.dp)
                                            .clip(RoundedCornerShape(14.dp))
                                            .border(
                                                if (chosen) 2.dp else 1.dp,
                                                if (chosen) Ink.Sage else Ink.Line,
                                                RoundedCornerShape(14.dp)
                                            )
                                    ) {
                                        com.whisper.ui.components.ChatWallpaper(Modifier.matchParentSize(), background = option)
                                    }
                                    Text(
                                        stringResource(option.label),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (chosen) Ink.SageLight else Ink.ChalkSoft,
                                        fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = close) { Text(stringResource(R.string.indicator_dismiss)) } }
        )
        SettingsDialog.MEMORY -> InfoDialog(R.string.memory_advice_title, listOf(R.string.memory_advice_body, R.string.memory_advice_how), close)
        SettingsDialog.NETWORK -> InfoDialog(R.string.vpn_advice_title, listOf(R.string.vpn_advice_body, R.string.vpn_advice_caveat), close)
        SettingsDialog.ABOUT -> InfoDialog(R.string.about_title, listOf(R.string.about_body), close)
        SettingsDialog.PRIVACY -> InfoDialog(R.string.privacy_title, listOf(R.string.privacy_body), close)
        SettingsDialog.LICENSES -> LicensesDialog(close)
        SettingsDialog.VERSION -> AlertDialog(
            onDismissRequest = close,
            title = { Text(stringResource(R.string.version_title) + " " + versionName(activity)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.version_fingerprint_hint))
                    Text(remember { signingFingerprint(activity) }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = close) { Text(stringResource(R.string.indicator_dismiss)) } }
        )
        null -> Unit
    }
}

private enum class SettingsPage { LOCK, DISCRETION, NETWORK, IDENTITY, ABOUT }

private fun appearanceLabel(mode: ThemeMode): Int = when (mode) {
    ThemeMode.AUTO -> R.string.appearance_auto
    ThemeMode.DAY -> R.string.appearance_day
    ThemeMode.NIGHT -> R.string.appearance_night
}

private enum class SettingsDialog {
    LANGUAGE, APPEARANCE, BACKGROUND, CHANGE_PIN, DURESS, TTL, PHOTO_VISIBILITY, GAZE_SHIELD, STAY_REACHABLE, MEMORY, MASK_ALL, WIPE, NEW_IDENTITY, RESTART_TOR, NETWORK, ABOUT, PRIVACY, LICENSES, VERSION, UPDATE
}

@Composable
private fun updateLabel(state: UpdateManager.State): String = when (state) {
    UpdateManager.State.Idle -> stringResource(R.string.update_state_current)
    is UpdateManager.State.Available -> stringResource(R.string.update_state_available, state.manifest.versionName)
    is UpdateManager.State.Downloading -> stringResource(R.string.update_state_downloading, state.percent)
    is UpdateManager.State.Installing -> stringResource(R.string.update_state_installing)
    is UpdateManager.State.Failed -> stringResource(R.string.update_state_failed)
}

private fun gazeLabel(mode: ScreenShield.GazeMode): Int = when (mode) {
    ScreenShield.GazeMode.OFF -> R.string.settings_off
    ScreenShield.GazeMode.LOOK_AWAY -> R.string.settings_gaze_look_away
    ScreenShield.GazeMode.ONLOOKER -> R.string.settings_gaze_onlooker
    ScreenShield.GazeMode.OWNER -> R.string.settings_gaze_owner
}

private fun visibilityLabel(v: ProfilePhotoService.Visibility): Int = when (v) {
    ProfilePhotoService.Visibility.EVERYONE -> R.string.profile_photo_everyone
    ProfilePhotoService.Visibility.CONFIRMED -> R.string.profile_photo_confirmed
    ProfilePhotoService.Visibility.HIDDEN -> R.string.profile_photo_hidden
}

/**
 * En haut des réglages : notre photo en grand (un appui la change), notre
 * empreinte, et le QR à portée de pouce.
 */
@Composable
private fun ProfileHeader(
    photo: ByteArray?,
    error: Boolean,
    fingerprint: String?,
    onChoose: () -> Unit,
    onRemove: () -> Unit,
    onQr: () -> Unit
) {
    Box(Modifier.fillMaxWidth().padding(top = 12.dp, end = 12.dp)) {
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .size(46.dp)
                .glass(CircleShape, elevation = 6.dp)
                .clickable(onClickLabel = stringResource(R.string.settings_my_qr), onClick = onQr),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Outlined.QrCode2, stringResource(R.string.settings_my_qr), tint = Ink.Chalk, modifier = Modifier.size(22.dp))
        }
        Column(
            Modifier.fillMaxWidth().padding(top = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                Modifier.clickable(onClickLabel = stringResource(R.string.profile_photo_change), onClick = onChoose)
            ) {
                Avatar("", size = 96, photo = photo)
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(32.dp)
                        .background(Ink.Night, CircleShape)
                        .padding(3.dp)
                        .background(Ink.Sage, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.AddAPhoto, null, tint = Ink.Night, modifier = Modifier.size(15.dp))
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.profile_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Ink.Chalk,
                modifier = Modifier.semantics { heading() }
            )
            fingerprint?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Ink.Ash) }
            if (error) {
                Text(stringResource(R.string.profile_photo_error), color = Ink.Brick, style = MaterialTheme.typography.bodySmall)
            }
            if (photo != null) {
                TextButton(onClick = onRemove) {
                    Icon(Icons.Outlined.HideImage, null, tint = Ink.Brick, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.profile_photo_remove), color = Ink.Brick, modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

/** Une rubrique ouverte : retour, titre, et ses lignes dans une carte. */
@Composable
private fun SubPage(title: Int, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier
                .size(46.dp)
                .glass(CircleShape, elevation = 6.dp)
                .clickable(onClickLabel = stringResource(R.string.back), onClick = onBack),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back), tint = Ink.Chalk)
        }
        Text(
            stringResource(title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Ink.Chalk,
            modifier = Modifier.semantics { heading() }
        )
    }
    GroupCard(content = content)
}

@Composable
private fun Chevron() {
    Icon(
        Icons.AutoMirrored.Rounded.ArrowBack,
        null,
        tint = Ink.LineStrong,
        modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = 180f }
    )
}

/** Petit aperçu rond d'un fond, à droite de la ligne. */
@Composable
private fun BackgroundSwatch(background: ChatBackground, size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(Brush.verticalGradient(listOf(background.top, background.bottom)))
            .border(1.dp, Ink.Line, CircleShape)
    )
}

@Composable
private fun InfoDialog(title: Int, paragraphs: List<Int>, onDone: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                paragraphs.forEach { Text(stringResource(it)) }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.indicator_dismiss)) } }
    )
}

/** Mentions de licence des bibliothèques embarquées (Tor, SQLCipher…), exigées par leurs licences. */
@Composable
private fun LicensesDialog(onDone: () -> Unit) {
    val context = LocalContext.current
    val text = remember {
        runCatching { context.assets.open("licenses/third-party.txt").bufferedReader().use { it.readText() } }.getOrDefault("")
    }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(R.string.licenses_title)) },
        text = {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.verticalScroll(rememberScrollState())
            )
        },
        confirmButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.indicator_dismiss)) } }
    )
}

@Composable
private fun ConfirmDialog(body: Int, action: Int, onDismiss: () -> Unit, danger: Boolean = true, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(stringResource(body)) },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm() }) {
                Text(stringResource(action), color = if (danger) Ink.Brick else Color.Unspecified)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}

/** Plein écran, clavier de l'app : aucun PIN ne passe par le clavier système. */
@Composable
private fun PadDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) { content() }
}

@Composable
private fun ResultDialog(message: Int, onDone: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDone,
        text = { Text(stringResource(message)) },
        confirmButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.indicator_dismiss)) } }
    )
}

/**
 * PIN de contrainte : le PIN actuel, puis le PIN de contrainte, de même
 * longueur — les deux valident seuls au bon nombre de chiffres.
 */
@Composable
private fun DuressDialog(viewModel: SettingsViewModel, onDone: () -> Unit) {
    var current by remember { mutableStateOf<CharArray?>(null) }
    val result by viewModel.result.collectAsState()
    val pending by viewModel.pending.collectAsState()

    DisposableEffect(Unit) { onDispose { current?.fill('\u0000'); viewModel.reset() } }

    if (result == R.string.settings_duress_saved) {
        ResultDialog(R.string.settings_duress_saved, onDone)
        return
    }
    PadDialog(onDone) {
        val step = current
        if (step == null) {
            PinPad(
                title = stringResource(R.string.settings_duress_current),
                hint = stringResource(result ?: R.string.settings_duress_body),
                hintIsError = result != null,
                busy = pending,
                resetKey = result ?: Unit,
                autoLength = viewModel.pinLength,
                onSubmit = { current = it }
            )
        } else {
            PinPad(
                title = stringResource(R.string.settings_duress_new),
                hint = stringResource(R.string.settings_duress_same_length),
                hintIsError = false,
                busy = pending,
                resetKey = step,
                autoLength = viewModel.pinLength,
                onSubmit = { duress ->
                    current = null
                    viewModel.setDuress(step, duress)
                }
            )
        }
    }
}

/**
 * Changer le PIN : l'actuel, le nouveau (✓, sa longueur est libre), puis
 * sa confirmation, qui valide seule à la même longueur.
 */
@Composable
private fun ChangePinDialog(viewModel: SettingsViewModel, onDone: () -> Unit) {
    var current by remember { mutableStateOf<CharArray?>(null) }
    var fresh by remember { mutableStateOf<CharArray?>(null) }
    var mismatch by remember { mutableStateOf(false) }
    val result by viewModel.result.collectAsState()
    val pending by viewModel.pending.collectAsState()

    DisposableEffect(Unit) {
        onDispose { current?.fill('\u0000'); fresh?.fill('\u0000'); viewModel.reset() }
    }

    if (result == R.string.settings_pin_changed || result == R.string.settings_pin_changed_duress_removed) {
        ResultDialog(result!!, onDone)
        return
    }
    PadDialog(onDone) {
        val cur = current
        val next = fresh
        when {
            cur == null -> PinPad(
                title = stringResource(R.string.settings_duress_current),
                hint = result?.let { stringResource(it) } ?: "",
                hintIsError = result != null,
                busy = pending,
                resetKey = result ?: Unit,
                autoLength = viewModel.pinLength,
                onSubmit = { current = it; viewModel.reset() }
            )
            next == null -> PinPad(
                title = stringResource(R.string.settings_new_pin),
                hint = stringResource(if (mismatch) R.string.lock_mismatch else R.string.lock_hint_setup),
                hintIsError = mismatch,
                busy = pending,
                resetKey = cur to mismatch,
                onSubmit = { if (Vault.isAcceptable(it)) { fresh = it; mismatch = false } else it.fill('\u0000') }
            )
            else -> PinPad(
                title = stringResource(R.string.lock_title_confirm),
                hint = "",
                hintIsError = false,
                busy = pending,
                resetKey = next,
                autoLength = next.size,
                onSubmit = { again ->
                    fresh = null
                    if (again.contentEquals(next)) {
                        again.fill('\u0000')
                        current = null
                        viewModel.changePin(cur, next)
                    } else {
                        again.fill('\u0000'); next.fill('\u0000')
                        mismatch = true
                    }
                }
            )
        }
    }
}

/** Un code à 4 chiffres pour toutes les conversations encore lisibles. */
@Composable
private fun MaskAllDialog(viewModel: SettingsViewModel, onDone: () -> Unit) {
    var first by remember { mutableStateOf<CharArray?>(null) }
    var mismatch by remember { mutableStateOf(false) }
    val result by viewModel.result.collectAsState()
    val pending by viewModel.pending.collectAsState()

    DisposableEffect(Unit) { onDispose { first?.fill('\u0000'); viewModel.reset() } }

    if (result == R.string.settings_mask_all_done) {
        ResultDialog(R.string.settings_mask_all_done, onDone)
        return
    }
    PadDialog(onDone) {
        val code = first
        PinPad(
            title = stringResource(if (code == null) R.string.read_code_new else R.string.read_code_confirm),
            hint = stringResource(if (mismatch) R.string.read_code_mismatch else R.string.settings_mask_all_hint),
            hintIsError = mismatch,
            busy = pending,
            resetKey = (code != null) to mismatch,
            autoLength = ContactRepository.READ_CODE_LENGTH,
            onSubmit = { entry ->
                if (code == null) {
                    first = entry; mismatch = false
                } else {
                    first = null
                    if (entry.contentEquals(code)) {
                        code.fill('\u0000')
                        viewModel.maskAll(entry)
                    } else {
                        entry.fill('\u0000'); code.fill('\u0000')
                        mismatch = true
                    }
                }
            }
        )
    }
}

private fun versionName(context: Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"

/**
 * Empreinte SHA-256 du certificat qui a signé l'APK. Elle ne change pas
 * d'une version à l'autre : si elle diffère de celle publiée par l'auteur,
 * l'APK n'est pas le sien.
 */
private fun signingFingerprint(context: Context): String = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    val cert = info.signingInfo!!.apkContentsSigners.first().toByteArray()
    MessageDigest.getInstance("SHA-256").digest(cert)
        .joinToString(":") { "%02X".format(it) }
}.getOrDefault("?")

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val vault: Vault,
    private val storage: StorageService,
    private val identityService: IdentityService,
    private val contactRepository: ContactRepository,
    private val messageRepository: MessageRepository,
    messageService: MessageService,
    tor: TorRuntime,
    private val photos: ProfilePhotoService,
    private val shield: ScreenShield,
    private val reachability: Reachability,
    private val updates: UpdateManager
) : ViewModel() {

    val update = updates.state
    val updateRelaying = updates.relaying
    val updateChecking = updates.checking
    val updateRetrying = updates.retrying
    val updateMandatory = updates.mandatory
    val updateNotes = updates.notes

    fun downloadUpdate() = updates.download()
    fun cancelUpdate() = updates.cancel()
    fun retryUpdateNow() = updates.retryNow()
    fun checkUpdate() = updates.checkNow()

    val stayReachable = reachability.enabled

    /** Langue de l'app ; "" = celle du téléphone. L'écran se recrée dans la nouvelle langue. */
    fun setLanguage(activity: Activity, tag: String) = AppLanguage.set(activity, storage, tag)

    /** Jour, nuit ou automatique : effet immédiat, gardé chiffré. */
    fun setThemeMode(mode: ThemeMode) {
        Appearance.mode = mode
        storage.saveString(Appearance.KEY, mode.name)
    }

    /** Le fond de toutes les conversations, et la teinte des écrans principaux. */
    fun setBackground(background: ChatBackground) {
        ChatBackground.current = background
        storage.saveString(ChatBackground.KEY, background.name)
    }

    fun setStayReachable(on: Boolean) = reachability.setEnabled(on)


    val gazeMode = shield.mode

    fun setGazeMode(mode: ScreenShield.GazeMode) {
        shield.gazeMode = mode
    }

    /** Regard détourné : manuel ou automatique (1.3.8). */
    private val _gazeSchedule = MutableStateFlow(shield.schedule)
    val gazeSchedule = _gazeSchedule.asStateFlow()

    fun setGazeSchedule(schedule: ScreenShield.Schedule) {
        shield.schedule = schedule
        _gazeSchedule.value = schedule
    }

    /* « Seulement mon visage » (1.3.7) */
    val ownerAvailable: Boolean get() = shield.ownerAvailable
    private val _enrolling = MutableStateFlow(false)
    val enrolling = _enrolling.asStateFlow()
    private val _enrolled = MutableStateFlow<Boolean?>(null)
    /** Résultat du dernier enregistrement ; null = rien à afficher. */
    val enrolled = _enrolled.asStateFlow()

    fun enrollOwner() {
        if (_enrolling.value) return
        _enrolling.value = true
        viewModelScope.launch {
            val ok = shield.enrollOwner()
            if (ok) setGazeMode(ScreenShield.GazeMode.OWNER)
            _enrolling.value = false
            _enrolled.value = ok
        }
    }

    fun clearEnrolled() { _enrolled.value = null }

    private val _photo = MutableStateFlow(photos.own())
    val photo = _photo.asStateFlow()
    private val _photoVisibility = MutableStateFlow(photos.visibility)
    val photoVisibility = _photoVisibility.asStateFlow()
    private val _photoError = MutableStateFlow(false)
    val photoError = _photoError.asStateFlow()

    fun setPhoto(uri: android.net.Uri) {
        viewModelScope.launch {
            val jpeg = photos.setFromUri(uri)
            _photoError.value = jpeg == null
            if (jpeg != null) _photo.value = jpeg
        }
    }

    fun clearPhoto() {
        photos.clear()
        _photoError.value = false
        _photo.value = null
    }

    fun setPhotoVisibility(value: ProfilePhotoService.Visibility) {
        photos.setVisibility(value)
        _photoVisibility.value = value
    }

    val torState = tor.state
    val ttlPresets = messageService.getTTLPresets()
    val pinLength: Int get() = vault.pinLength()

    private val _defaultTtl = MutableStateFlow(
        storage.getInt(StorageService.KEY_DEFAULT_TTL, MessageService.DEFAULT_TTL_MINUTES)
    )
    val defaultTtl = _defaultTtl.asStateFlow()

    /** Texte à afficher après une opération : succès ou raison du refus. */
    private val _result = MutableStateFlow<Int?>(null)
    val result = _result.asStateFlow()

    /** Argon2 en cours : l'écran attend. */
    private val _pending = MutableStateFlow(false)
    val pending = _pending.asStateFlow()

    fun setDefaultTtl(minutes: Int) {
        storage.saveInt(StorageService.KEY_DEFAULT_TTL, minutes)
        _defaultTtl.value = minutes
    }

    fun setDuress(current: CharArray, duress: CharArray) = perform(current, duress) {
        if (vault.setDuressPin(current, duress)) R.string.settings_duress_saved else R.string.settings_duress_failed
    }

    fun changePin(current: CharArray, fresh: CharArray) = perform(current, fresh) {
        when (vault.changePin(current, fresh)) {
            Vault.ChangeResult.CHANGED -> R.string.settings_pin_changed
            Vault.ChangeResult.CHANGED_DURESS_REMOVED -> R.string.settings_pin_changed_duress_removed
            Vault.ChangeResult.WRONG_CURRENT -> R.string.lock_wrong
            Vault.ChangeResult.REJECTED -> R.string.settings_pin_rejected
        }
    }

    fun maskAll(code: CharArray) = perform(code) {
        contactRepository.setReadLockWhereMissing(code)
        R.string.settings_mask_all_done
    }

    /** Le processus meurt juste après : rien de l'ancienne session ne reste. */
    fun wipeNow(then: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { vault.wipe() }
            then()
        }
    }

    /** Messages et contacts d'abord : ils appartiennent à l'ancienne identité. */
    fun newIdentity(then: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                messageRepository.deleteAll()
                contactRepository.deleteAll()
                // La photo relierait l'ancienne identité à la nouvelle.
                photos.clear()
                identityService.replaceIdentity()
            }
            then()
        }
    }

    fun reset() { _result.value = null }

    /** Les PIN et codes passés sont effacés, quoi qu'il arrive. */
    private fun perform(vararg secrets: CharArray, block: suspend () -> Int) {
        _result.value = null
        _pending.value = true
        viewModelScope.launch {
            _result.value = try {
                block()
            } catch (e: Exception) {
                R.string.common_error
            } finally {
                secrets.forEach { it.fill('\u0000') }
                _pending.value = false
            }
        }
    }
}
