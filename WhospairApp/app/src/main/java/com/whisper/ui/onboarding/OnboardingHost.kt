package com.whisper.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import com.whisper.viewmodels.OnboardingStep
import com.whisper.viewmodels.OnboardingViewModel

/**
 * OnboardingHost - Porte d'entrée de l'application.
 *
 * Rend le parcours de premier lancement tant qu'il n'est pas terminé, puis
 * cède la place à [content] (l'app réelle).
 *
 * Pas de NavController ici : le parcours est linéaire et son étape est
 * dérivée de l'état persisté. Un graphe de navigation ajouterait une pile
 * arrière dont on ne veut pas — revenir en arrière depuis la création
 * d'identité vers les conditions n'a aucun sens.
 */
@Composable
fun OnboardingHost(
    onExitApp: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
    content: @Composable () -> Unit
) {
    val state by viewModel.viewState.collectAsState()

    // Note : aucune transition automatique après la création d'identité.
    // L'utilisateur reste sur l'écran pour noter sa clé ; c'est le bouton
    // Continuer qui déclenche finishOnboarding().

    AnimatedContent(
        targetState = state.step,
        transitionSpec = {
            fadeIn(tween(220)) togetherWith fadeOut(tween(160))
        },
        label = "onboardingStep"
    ) { step ->
        when (step) {
            OnboardingStep.CHECKING -> SplashScreen()

            OnboardingStep.TERMS -> TermsOfServiceScreen(
                onAccept = viewModel::acceptTerms,
                onDecline = onExitApp
            )

            OnboardingStep.TUTORIAL -> AppTutorialScreen(
                onFinish = viewModel::completeTutorial
            )

            OnboardingStep.IDENTITY -> IdentityCreationScreen(
                publicKeyHex = state.publicKeyHex,
                isCreating = state.isCreatingIdentity,
                error = state.error,
                onCreate = viewModel::createIdentity,
                onContinue = viewModel::finishOnboarding
            )

            OnboardingStep.READY -> content()
        }
    }
}
