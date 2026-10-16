package com.whisper.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whisper.services.IdentityService
import com.whisper.services.StorageService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import com.whisper.util.DebugLog

/**
 * Étape courante du parcours de premier lancement.
 *
 * L'étape est DÉRIVÉE de l'état persisté, jamais devinée : si l'utilisateur
 * ferme l'app au milieu du tutoriel, il reprend là où il en était plutôt que
 * de tout recommencer.
 */
enum class OnboardingStep {
    CHECKING,   // Lecture du stockage, rien à afficher encore
    TERMS,      // Conditions non acceptées
    TUTORIAL,   // Conditions acceptées, tutoriel pas terminé
    IDENTITY,   // Tutoriel vu, identité pas encore créée
    READY       // Tout est fait -> écran d'accueil
}

/**
 * État du parcours d'onboarding.
 *
 * publicKeyHex est la seule donnée d'identité exposée à l'UI.
 * La clé privée n'a rien à faire dans un état observable.
 */
data class OnboardingViewState(
    val step: OnboardingStep = OnboardingStep.CHECKING,
    val isCreatingIdentity: Boolean = false,
    val publicKeyHex: String? = null,
    val error: String? = null
)

/**
 * OnboardingViewModel - Pilote le premier lancement.
 *
 * Parcours : Splash → Conditions → Tutoriel → Création d'identité → Accueil.
 * Une identité déjà présente court-circuite tout.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val identityService: IdentityService,
    private val storageService: StorageService
) : ViewModel() {

    private val _viewState = MutableStateFlow(OnboardingViewState())
    val viewState: StateFlow<OnboardingViewState> = _viewState.asStateFlow()

    init {
        resolveStartingStep()
    }

    /**
     * Détermine où reprendre le parcours.
     *
     * L'ordre des tests compte : une identité valide l'emporte sur tout le
     * reste, y compris sur des drapeaux de conditions ou de tutoriel absents
     * (cas d'une mise à jour depuis une version antérieure).
     */
    private fun resolveStartingStep() {
        viewModelScope.launch {
            try {
                coroutineScope {
                // Le délai court EN PARALLÈLE de la lecture, pas après :
                // enchaîner les deux donnerait IO + 600 ms au lieu du
                // max(IO, 600 ms) recherché.
                val minimumSplash = async { delay(SPLASH_MIN_DURATION_MS) }

                val stored = withContext(Dispatchers.IO) {
                    // Scellée = elle existe, mais ses clés privées attendent le
                    // PIN : on ne peut pas la valider ici, et surtout pas la
                    // prendre pour absente (on en recréerait une par-dessus).
                    val identityOk = identityService.isIdentityInitialized() &&
                            (identityService.isSealed() || identityService.validateIdentity())
                    Triple(
                        identityOk,
                        storageService.getBoolean(StorageService.KEY_TERMS_ACCEPTED),
                        storageService.getBoolean(StorageService.KEY_TUTORIAL_COMPLETED)
                    )
                }
                minimumSplash.await()

                val (hasIdentity, termsAccepted, tutorialDone) = stored

                val step = when {
                    hasIdentity -> OnboardingStep.READY
                    !termsAccepted -> OnboardingStep.TERMS
                    !tutorialDone -> OnboardingStep.TUTORIAL
                    else -> OnboardingStep.IDENTITY
                }

                _viewState.update {
                    it.copy(
                        step = step,
                        publicKeyHex = if (hasIdentity) identityService.getPublicKeyHex() else null
                    )
                }
                DebugLog.d(TAG) { "Étape de départ: $step" }
                }
            } catch (e: Exception) {
                DebugLog.e(TAG, "Erreur résolution de l'étape", e)
                // En cas de doute, repartir du début est le comportement sûr :
                // mieux vaut refaire le parcours que démarrer sans identité.
                _viewState.update {
                    it.copy(step = OnboardingStep.TERMS, error = e.message)
                }
            }
        }
    }

    /**
     * Conditions acceptées. Persiste AVANT d'avancer, pour que fermer l'app
     * juste après ne réaffiche pas les conditions.
     */
    fun acceptTerms() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                storageService.saveBoolean(StorageService.KEY_TERMS_ACCEPTED, true)
            }
            _viewState.update { it.copy(step = OnboardingStep.TUTORIAL) }
        }
    }

    /**
     * Tutoriel terminé ou passé.
     */
    fun completeTutorial() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                storageService.saveBoolean(StorageService.KEY_TUTORIAL_COMPLETED, true)
            }
            _viewState.update { it.copy(step = OnboardingStep.IDENTITY) }
        }
    }

    /**
     * Crée l'identité locale (paire X25519 + clé de signature Keystore).
     *
     * Opération bloquante : elle touche le Keystore matériel, donc Dispatchers.IO.
     * Elle est aussi idempotente côté IdentityService, un double appui sur le
     * bouton ne génère pas deux identités.
     */
    fun createIdentity() {
        if (_viewState.value.isCreatingIdentity) return

        viewModelScope.launch {
            _viewState.update { it.copy(isCreatingIdentity = true, error = null) }
            try {
                val identity = withContext(Dispatchers.IO) {
                    identityService.getOrCreateIdentity()
                }

                // Contrôle de cohérence : la clé publique doit bien dériver de
                // la privée. Une identité corrompue détectée maintenant coûte
                // moins cher qu'à la première tentative de déchiffrement.
                val valid = withContext(Dispatchers.IO) { identityService.validateIdentity() }
                if (!valid) {
                    throw IllegalStateException(
                        "Identité incohérente : la clé publique ne dérive pas de la clé privée"
                    )
                }

                _viewState.update {
                    it.copy(
                        isCreatingIdentity = false,
                        publicKeyHex = identity.publicKeyHex
                    )
                }
                DebugLog.d(TAG) { "Identité créée: ${identity.publicKeyHex.take(8)}…" }
            } catch (e: Exception) {
                DebugLog.e(TAG, "Erreur création d'identité", e)
                _viewState.update {
                    it.copy(isCreatingIdentity = false, error = e.message)
                }
            }
        }
    }

    /**
     * Sort de l'onboarding vers l'écran d'accueil.
     * Refuse d'avancer tant qu'aucune identité n'existe.
     */
    fun finishOnboarding() {
        if (_viewState.value.publicKeyHex == null) {
            DebugLog.w(TAG, "finishOnboarding() sans identité — ignoré")
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                storageService.saveBoolean(StorageService.KEY_FIRST_LAUNCH, false)
            }
            _viewState.update { it.copy(step = OnboardingStep.READY) }
        }
    }

    companion object {
        private const val TAG = "OnboardingViewModel"
        private const val SPLASH_MIN_DURATION_MS = 600L
    }
}
