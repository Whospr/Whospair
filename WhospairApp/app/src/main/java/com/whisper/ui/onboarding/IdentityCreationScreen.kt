package com.whisper.ui.onboarding

import com.whisper.theme.Ink
import com.whisper.ui.icons.Icons
import com.whisper.ui.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whisper.R

/**
 * IdentityCreationScreen - Dernière étape du premier lancement.
 *
 * Trois états : génération en cours, identité prête, échec.
 * La génération démarre automatiquement — il n'y a rien à décider pour
 * l'utilisateur, lui demander de cliquer sur « créer » serait une étape vide.
 */
@Composable
fun IdentityCreationScreen(
    publicKeyHex: String?,
    isCreating: Boolean,
    error: String?,
    onCreate: () -> Unit,
    onContinue: () -> Unit
) {
    LaunchedEffect(Unit) {
        if (publicKeyHex == null && !isCreating) onCreate()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink.Night),
        contentAlignment = Alignment.Center
    ) {
        when {
            error != null && publicKeyHex == null -> ErrorState(error = error, onRetry = onCreate)
            publicKeyHex != null -> ReadyState(onContinue = onContinue)
            else -> CreatingState()
        }
    }
}

@Composable
private fun CreatingState() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier.padding(32.dp)
    ) {
        CircularProgressIndicator(
            color = Ink.Sage,
            strokeWidth = 2.dp,
            modifier = Modifier.size(36.dp)
        )
        Text(
            text = stringResource(R.string.identity_creating_title),
            color = Ink.Chalk,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Text(
            text = stringResource(R.string.identity_creating_body),
            color = Ink.Ash,
            fontSize = 14.sp,
            lineHeight = 21.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ReadyState(onContinue: () -> Unit) {
    // Pas de QR ici : l'adresse onion, qui en fait partie, naît au premier
    // déverrouillage, scellée sous le PIN. Le QR complet est dans Découvrir.

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(
            text = stringResource(R.string.identity_ready_title),
            color = Ink.Chalk,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = stringResource(R.string.identity_ready_body),
            color = Ink.ChalkSoft,
            fontSize = 16.sp,
            lineHeight = 24.sp,
            textAlign = TextAlign.Center
        )

        // Avertissement d'irréversibilité : une icône brique, pas une barre.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Ink.Slate, RoundedCornerShape(16.dp))
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Outlined.ErrorOutline, null, tint = Ink.Brick, modifier = Modifier.size(22.dp))
            Text(
                text = stringResource(R.string.identity_warning),
                color = Ink.ChalkSoft,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Button(
            onClick = onContinue,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            colors = ButtonDefaults.buttonColors()
        ) {
            Text(
                text = stringResource(R.string.identity_continue),
                color = Ink.Night,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun ErrorState(
    error: String,
    onRetry: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.padding(32.dp)
    ) {
        Text(
            text = stringResource(R.string.identity_error_title),
            color = Ink.Chalk,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Text(
            text = error,
            color = Ink.Brick,
            fontSize = 14.sp,
            lineHeight = 21.sp,
            textAlign = TextAlign.Center
        )
        Button(
            onClick = onRetry,
            modifier = Modifier.height(46.dp),
            colors = ButtonDefaults.buttonColors()
        ) {
            Text(
                text = stringResource(R.string.identity_retry),
                color = Ink.Night,
                fontSize = 15.sp
            )
        }
    }
}
