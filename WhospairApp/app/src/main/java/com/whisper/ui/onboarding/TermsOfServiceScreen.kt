package com.whisper.ui.onboarding

import com.whisper.theme.Ink
import com.whisper.ui.icons.Icons
import com.whisper.ui.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whisper.R

private data class TermsSection(val titleRes: Int, val bodyRes: Int)

private val termsSections = listOf(
    TermsSection(R.string.tos_s1_title, R.string.tos_s1_body),
    TermsSection(R.string.tos_s2_title, R.string.tos_s2_body),
    TermsSection(R.string.tos_s3_title, R.string.tos_s3_body),
    TermsSection(R.string.tos_s4_title, R.string.tos_s4_body),
    TermsSection(R.string.tos_s5_title, R.string.tos_s5_body),
    TermsSection(R.string.tos_s6_title, R.string.tos_s6_body),
    TermsSection(R.string.tos_s7_title, R.string.tos_s7_body),
    TermsSection(R.string.tos_s8_title, R.string.tos_s8_body),
    TermsSection(R.string.tos_s9_title, R.string.tos_s9_body),
    TermsSection(R.string.tos_s10_title, R.string.tos_s10_body)
)

/**
 * TermsOfServiceScreen - Conditions obligatoires au premier lancement.
 *
 * La case à cocher ne s'active qu'une fois la liste parcourue jusqu'en bas.
 * Cocher sans avoir lu reste possible partout ailleurs ; ici le point 6
 * (aucune récupération d'identité) est irréversible, donc le défilement est
 * un prérequis assumé, pas une friction gratuite.
 */
@Composable
fun TermsOfServiceScreen(
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    var accepted by remember { mutableStateOf(false) }
    var showDeclineDialog by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val reachedBottom by remember {
        derivedStateOf {
            val layout = listState.layoutInfo
            val last = layout.visibleItemsInfo.lastOrNull()
            last != null && last.index >= layout.totalItemsCount - 1
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink.Night)
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 24.dp, end = 24.dp, top = 48.dp, bottom = 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.tos_title),
                    color = Ink.Chalk,
                    style = MaterialTheme.typography.headlineMedium
                )
            }

            item {
                // Avertissement : une icône et une phrase, sur un fond à peine
                // relevé. Pas de bloc orange saturé, qu'on finit par ne plus voir.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Ink.Slate, RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Outlined.WarningAmber, null, tint = Ink.Ochre, modifier = Modifier.size(22.dp))
                    Text(
                        text = stringResource(R.string.tos_warning),
                        color = Ink.ChalkSoft,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            items(termsSections) { section ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(section.titleRes),
                        color = Ink.Chalk,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = stringResource(section.bodyRes),
                        color = Ink.ChalkSoft,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        // Pied fixe : l'utilisateur ne perd jamais les boutons de vue.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Ink.Night)
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = reachedBottom) { accepted = !accepted }
            ) {
                Checkbox(
                    checked = accepted,
                    onCheckedChange = { accepted = it },
                    enabled = reachedBottom,
                    colors = CheckboxDefaults.colors(
                        checkedColor = Ink.Sage,
                        uncheckedColor = Ink.LineStrong,
                        disabledUncheckedColor = Ink.Line
                    )
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.tos_checkbox),
                    color = if (reachedBottom) Ink.ChalkSoft else Ink.LineStrong,
                    fontSize = 14.sp
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextButton(
                    onClick = { showDeclineDialog = true },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = stringResource(R.string.tos_decline),
                        color = Ink.Ash,
                        fontSize = 15.sp
                    )
                }

                Button(
                    onClick = onAccept,
                    enabled = accepted,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Ink.Sage,
                        disabledContainerColor = Ink.Slate
                    )
                ) {
                    Text(
                        text = stringResource(R.string.tos_accept),
                        color = if (accepted) Ink.Night else Ink.Ash,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }

    if (showDeclineDialog) {
        AlertDialog(
            onDismissRequest = { showDeclineDialog = false },
            containerColor = Ink.Bar,
            title = {
                Text(
                    text = stringResource(R.string.tos_decline_dialog_title),
                    color = Ink.Chalk,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.tos_decline_dialog_body),
                    color = Ink.Ash,
                    fontSize = 14.sp,
                    lineHeight = 21.sp
                )
            },
            confirmButton = {
                TextButton(onClick = onDecline) {
                    Text(
                        text = stringResource(R.string.tos_decline_confirm),
                        color = Ink.Brick
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeclineDialog = false }) {
                    Text(
                        text = stringResource(R.string.tos_decline_cancel),
                        color = Ink.Ash
                    )
                }
            }
        )
    }
}
