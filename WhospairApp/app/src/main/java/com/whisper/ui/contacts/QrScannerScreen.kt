package com.whisper.ui.contacts

import com.whisper.theme.Ink
import com.whisper.security.SessionLifecycle
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.whisper.R

/**
 * QrScannerScreen - Lecture du QR d'un contact.
 *
 * Gère la permission caméra, le cycle de vie de la caméra, et la
 * déduplication des lectures.
 */
@Composable
fun QrScannerScreen(
    onScanned: (String) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }
    var permissionRequested by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        SessionLifecycle.afterSystemDialog()
        hasPermission = granted
        permissionRequested = true
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            SessionLifecycle.beforeSystemDialog()
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink.Night)
    ) {
        if (hasPermission) {
            CameraPreview(onScanned = onScanned)
            ScannerOverlay(onCancel = onCancel)
        } else {
            PermissionRationale(
                denied = permissionRequested,
                onRequest = {
                    SessionLifecycle.beforeSystemDialog()
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                },
                onCancel = onCancel
            )
        }
    }
}

/**
 * Aperçu caméra ZXing, branché sur le cycle de vie Compose.
 */
@Composable
private fun CameraPreview(onScanned: (String) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    // La caméra frontale de l'écran masqué cède la place le temps du scan.
    DisposableEffect(Unit) {
        com.whisper.shield.ScreenShield.cameraClaimed.value = true
        onDispose { com.whisper.shield.ScreenShield.cameraClaimed.value = false }
    }

    // Un QR reste dans le champ plusieurs dizaines de frames : sans ce garde,
    // decodeContinuous rappellerait le callback en rafale et déclencherait
    // autant de tentatives d'ajout.
    var alreadyHandled by remember { mutableStateOf(false) }

    // La vue est conservée pour pouvoir la piloter depuis le DisposableEffect.
    // Sans cette référence, rien ne pouvait mettre la caméra en pause quand
    // l'app passait en arrière-plan : elle restait active écran éteint.
    var barcodeView by remember { mutableStateOf<DecoratedBarcodeView?>(null) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            DecoratedBarcodeView(ctx).also { barcodeView = it }.apply {
                // Le viseur et le texte d'aide de ZXing jurent avec le reste :
                // l'overlay Compose s'en charge.
                setStatusText("")
                decodeContinuous(object : BarcodeCallback {
                    override fun barcodeResult(result: BarcodeResult?) {
                        val text = result?.text ?: return
                        if (alreadyHandled) return
                        alreadyHandled = true
                        onScanned(text)
                    }
                })
                resume()
            }
        },
        onRelease = { view ->
            // Sans cet arrêt explicite, la caméra reste allouée après la
            // sortie de l'écran et l'app garde l'indicateur d'usage actif.
            view.pause()
            barcodeView = null
        }
    )

    // L'observateur est enregistré UNE fois et retiré à la sortie.
    // Le placer dans update{} l'ajouterait à chaque recomposition, et la
    // caméra recevrait autant de resume() qu'il y a eu de passages.
    DisposableEffect(lifecycleOwner, barcodeView) {
        val view = barcodeView
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    // Réarme la lecture au retour : sinon, revenir sur
                    // l'écran après un aller-retour laisserait le scanner
                    // définitivement sourd.
                    alreadyHandled = false
                    view?.resume()
                }
                // Le point qui compte pour une app de confidentialité :
                // la caméra doit s'éteindre dès que l'app quitte le premier
                // plan, pas seulement à la fermeture de l'écran.
                Lifecycle.Event.ON_PAUSE -> view?.pause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            view?.pause()
        }
    }
}

/**
 * Viseur et bouton d'annulation par-dessus la caméra.
 */
@Composable
private fun ScannerOverlay(onCancel: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = stringResource(R.string.scan_instruction),
            color = Ink.Chalk,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(top = 48.dp, start = 32.dp, end = 32.dp)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp)
        )

        Box(
            modifier = Modifier
                .size(240.dp)
                .border(2.dp, Ink.Sage, RoundedCornerShape(16.dp))
        )

        Button(
            onClick = onCancel,
            modifier = Modifier
                .padding(bottom = 48.dp)
                .height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Ink.Relief)
        ) {
            Text(
                text = stringResource(R.string.common_cancel),
                color = Ink.Chalk,
                fontSize = 15.sp
            )
        }
    }
}

/**
 * Écran affiché tant que la caméra n'est pas autorisée.
 *
 * Le texte change après un refus : redemander la même chose à l'identique
 * ne dit pas à l'utilisateur que le système ne reposera plus la question et
 * qu'il faut passer par les réglages.
 */
@Composable
private fun PermissionRationale(
    denied: Boolean,
    onRequest: () -> Unit,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.scan_permission_title),
            color = Ink.Chalk,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )

        Text(
            text = stringResource(
                if (denied) R.string.scan_permission_denied
                else R.string.scan_permission_body
            ),
            color = Ink.Ash,
            fontSize = 14.sp,
            lineHeight = 21.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp, bottom = 28.dp)
        )

        if (!denied) {
            Button(
                onClick = onRequest,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors()
            ) {
                Text(
                    text = stringResource(R.string.scan_permission_grant),
                    color = Ink.Night,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Button(
            onClick = onCancel,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Ink.Relief)
        ) {
            Text(
                text = stringResource(R.string.scan_use_manual),
                color = Ink.Chalk,
                fontSize = 15.sp
            )
        }
    }
}
