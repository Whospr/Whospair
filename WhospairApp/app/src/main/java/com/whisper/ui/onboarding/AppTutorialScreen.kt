@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.whisper.ui.onboarding

import com.whisper.theme.Ink
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whisper.R
import kotlinx.coroutines.launch

private const val TUTORIAL_PAGE_COUNT = 5

/**
 * AppTutorialScreen - Parcours d'explication en 8 pages.
 *
 * Chaque page est scrollable individuellement : sur un petit écran en
 * français, le texte dépasse la hauteur disponible et serait tronqué sans ça.
 */
@Composable
fun AppTutorialScreen(
    onFinish: () -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { TUTORIAL_PAGE_COUNT })
    val scope = rememberCoroutineScope()
    val isLastPage = pagerState.currentPage == TUTORIAL_PAGE_COUNT - 1

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink.Night)
    ) {
        // Barre haute : compteur + passer
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(
                    R.string.tutorial_page_counter,
                    pagerState.currentPage + 1,
                    TUTORIAL_PAGE_COUNT
                ),
                color = Ink.Ash,
                fontSize = 13.sp
            )

            if (!isLastPage) {
                TextButton(onClick = onFinish) {
                    Text(
                        text = stringResource(R.string.tutorial_skip),
                        color = Ink.Ash,
                        fontSize = 14.sp
                    )
                }
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f)
        ) { page ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 28.dp),
                verticalArrangement = Arrangement.Center
            ) {
                when (page) {
                    // 1.3.7 : 8 pages ramenées à 5 (bienvenue et présence, contacts
                    // et confiance, messages et effacement, discrétion, PIN).
                    0 -> TutorialPage(
                        title = stringResource(R.string.tut_welcome_title),
                        body = stringResource(R.string.tut_welcome_body),
                        bullets = listOf(
                            stringResource(R.string.tut_welcome_f1),
                            stringResource(R.string.tut_welcome_f2),
                            stringResource(R.string.tut_welcome_f3)
                        )
                    )

                    // Trois niveaux : à confirmer, confirmé, vérifié (QR face à face).
                    1 -> TutorialPage(
                        title = stringResource(R.string.tut_add_title),
                        body = stringResource(R.string.tut_add_body),
                        bullets = listOf(
                            stringResource(R.string.tut_add_f1),
                            stringResource(R.string.tut_add_f2),
                            stringResource(R.string.tut_add_f3)
                        )
                    )

                    2 -> TutorialPage(
                        title = stringResource(R.string.tut_write_title),
                        body = stringResource(R.string.tut_write_body),
                        bullets = listOf(
                            stringResource(R.string.tut_write_f1),
                            stringResource(R.string.tut_write_f2),
                            stringResource(R.string.tut_write_f3)
                        ),
                        content = { TtlScale() }
                    )

                    3 -> TutorialPage(
                        title = stringResource(R.string.tutorial_discreet_title),
                        body = stringResource(R.string.tutorial_discreet_body),
                        bullets = listOf(
                            stringResource(R.string.tutorial_discreet_f1),
                            stringResource(R.string.tutorial_discreet_f2),
                            stringResource(R.string.tutorial_discreet_f3),
                            stringResource(R.string.tutorial_discreet_f4),
                            stringResource(R.string.tutorial_discreet_f5)
                        )
                    )

                    4 -> TutorialPage(
                        title = stringResource(R.string.tutorial_pin_title),
                        body = stringResource(R.string.tutorial_pin_body),
                        bullets = listOf(
                            stringResource(R.string.tutorial_pin_f1),
                            stringResource(R.string.tutorial_pin_f2),
                            stringResource(R.string.tutorial_pin_f3)
                        ),
                        note = stringResource(R.string.tutorial_pin_note)
                    )
                }
            }
        }

        // Pied : indicateurs + navigation
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(TUTORIAL_PAGE_COUNT) { index ->
                    val active = index == pagerState.currentPage
                    // La page courante s'étire en barre : plus lisible qu'un
                    // point simplement plus clair.
                    val width by animateDpAsState(
                        targetValue = if (active) 20.dp else 6.dp,
                        label = "dotWidth"
                    )
                    val color by animateColorAsState(
                        targetValue = if (active) Ink.Sage else Ink.Line,
                        label = "dotColor"
                    )
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .width(width)
                            .height(6.dp)
                            .background(color, CircleShape)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (pagerState.currentPage > 0) {
                    TextButton(
                        onClick = {
                            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = stringResource(R.string.tutorial_back),
                            color = Ink.Ash,
                            fontSize = 15.sp
                        )
                    }
                }

                Button(
                    onClick = {
                        if (isLastPage) onFinish()
                        else scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors()
                ) {
                    Text(
                        text = stringResource(
                            if (isLastPage) R.string.tutorial_start else R.string.tutorial_next
                        ),
                        color = Ink.Night,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

/**
 * Gabarit commun des pages du tutoriel.
 */
@Composable
private fun TutorialPage(
    title: String,
    body: String,
    bullets: List<String>? = null,
    numbered: List<String>? = null,
    note: String? = null,
    content: (@Composable () -> Unit)? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = title,
            color = Ink.Chalk,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 32.sp
        )

        Text(
            text = body,
            color = Ink.Ash,
            fontSize = 15.sp,
            lineHeight = 23.sp
        )

        content?.invoke()

        bullets?.let { items ->
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(top = 4.dp)
            ) {
                items.forEach { item ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .background(Ink.Sage, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Text(
                            text = item,
                            color = Ink.ChalkSoft,
                            fontSize = 15.sp
                        )
                    }
                }
            }
        }

        numbered?.let { steps ->
            Column(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.padding(top = 4.dp)
            ) {
                steps.forEachIndexed { index, step ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${index + 1}",
                                color = Ink.Sage,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = step,
                            color = Ink.ChalkSoft,
                            fontSize = 15.sp,
                            lineHeight = 21.sp
                        )
                    }
                }
            }
        }

        note?.let {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .background(Ink.Slate, RoundedCornerShape(16.dp))
                    .padding(14.dp)
            ) {
                Text(
                    text = it,
                    color = Ink.Ash,
                    fontSize = 13.sp,
                    lineHeight = 20.sp
                )
            }
        }
    }
}

/**
 * Échelle visuelle des durées de vie.
 *
 * Libellés courts non traduits (unités de temps universelles).
 * Borne haute = 8 h, qui est aussi la valeur par défaut : au-delà, un message
 * ne fait qu'allonger la fenêtre d'exposition sur l'appareil.
 */
@Composable
private fun TtlScale() {
    val steps = listOf("5m", "15m", "1h", "5h", "8h")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        steps.forEachIndexed { index, label ->
            // Dégradé d'opacité : plus la durée est longue, plus c'est pâle.
            val intensity = 1f - (index * 0.16f)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .background(
                        Ink.Sage.copy(alpha = 0.12f + intensity * 0.18f),
                        RoundedCornerShape(6.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    color = Ink.ChalkSoft,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
