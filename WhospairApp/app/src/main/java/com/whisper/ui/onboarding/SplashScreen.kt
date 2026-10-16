package com.whisper.ui.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.whisper.R

/**
 * SplashScreen - Écran d'attente pendant la résolution de l'identité.
 *
 * Le logo de l'auteur (1.3.8), sur son propre fond (#181818) pour qu'aucun
 * bord ne se voie, avec « PROTECT BY DESIGN » centré dessous, dessiné dans
 * l'image en Montserrat (licence libre OFL ; l'ancienne police était sous
 * licence). Aucune police ajoutée à l'app. Immobile : l'attente est courte.
 */
@Composable
fun SplashScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF181818)),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(R.drawable.whisper_home),
            contentDescription = stringResource(R.string.app_name),
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
        )
    }
}
