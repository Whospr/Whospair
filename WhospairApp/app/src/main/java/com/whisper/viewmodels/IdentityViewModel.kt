package com.whisper.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whisper.models.Identity
import com.whisper.services.IdentityService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * État du IdentityViewModel.
 *
 * On n'expose PAS l'objet Identity complet à l'UI : il contient la clé privée.
 * Seules les informations affichables remontent.
 */
data class IdentityViewState(
    val publicKeyHex: String? = null,
    val publicKeyAbbrev: String? = null,
    val createdAtFormatted: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val isInitialized: Boolean = false
)

/**
 * ViewModel de l'identité utilisateur.
 *
 * Délègue entièrement à IdentityService, qui persiste via StorageService
 * (EncryptedSharedPreferences). L'ancienne version écrivait la clé dans des
 * SharedPreferences en clair et dupliquait la logique du service.
 */
@HiltViewModel
class IdentityViewModel @Inject constructor(
    private val identityService: IdentityService
) : ViewModel() {

    private val _viewState = MutableStateFlow(IdentityViewState())
    val viewState: StateFlow<IdentityViewState> = _viewState.asStateFlow()

    init {
        initializeIdentity()
    }

    /**
     * Charge l'identité existante, ou la génère au premier lancement.
     * La génération de clés est bloquante (Keystore) : elle part sur Dispatchers.IO.
     */
    fun initializeIdentity() {
        viewModelScope.launch {
            _viewState.update { it.copy(isLoading = true, error = null) }
            try {
                val identity = withContext(Dispatchers.IO) {
                    identityService.getOrCreateIdentity()
                }

                if (!identityService.validateIdentity()) {
                    _viewState.update {
                        it.copy(
                            isLoading = false,
                            error = "Identité corrompue : la clé privée ne correspond pas " +
                                    "à la clé publique stockée."
                        )
                    }
                    return@launch
                }

                publish(identity)
            } catch (e: Exception) {
                _viewState.update {
                    it.copy(
                        isLoading = false,
                        error = "Erreur d'initialisation: ${e.message}"
                    )
                }
            }
        }
    }

    fun getPublicKeyHex(): String? = _viewState.value.publicKeyHex

    /**
     * Projette l'identité vers l'état d'UI, sans jamais y recopier la clé privée.
     */
    private fun publish(identity: Identity) {
        val info = identityService.getIdentityInfo()
        _viewState.update {
            it.copy(
                publicKeyHex = identity.publicKeyHex,
                publicKeyAbbrev = info?.publicKeyAbbrev ?: identity.publicKeyHex.take(8),
                createdAtFormatted = info?.createdAtFormatted,
                isLoading = false,
                isInitialized = true,
                error = null
            )
        }
    }
}
