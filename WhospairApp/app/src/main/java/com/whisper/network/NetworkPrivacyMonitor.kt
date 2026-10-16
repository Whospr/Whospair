package com.whisper.network

import android.content.Context
import com.whisper.util.DebugLog
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Le trafic sort-il par un tunnel ?
 *
 * ### Pourquoi c'est là
 *
 * Whisper chiffre le contenu de bout en bout ; il ne cache pas l'enveloppe IP.
 * Le fournisseur d'accès, et qui le contraint, voit qu'un appareil parle au
 * relais et à d'autres adresses. Il ne peut rien lire, mais il sait quand, et
 * combien. Pour le public visé, cette métadonnée-là est parfois la seule qui
 * compte : être vu en train d'utiliser un outil de ce genre suffit déjà.
 *
 * Un VPN déplace le problème sans le supprimer — l'opérateur du tunnel voit ce
 * que voyait le fournisseur. Mais il le déplace hors de la juridiction qui
 * surveille, ce qui, en pratique, change tout. D'où l'indicateur : pas une
 * fonctionnalité, un rappel.
 *
 * ### Ce qu'on affiche, et ce qu'on n'affiche pas
 *
 * On sait seulement qu'une interface VPN porte le trafic. On ne sait ni quel
 * fournisseur, ni si le tunnel fuit, ni s'il est digne de confiance. L'UI reste
 * donc sobre : un point, un libellé. Écrire « protégé » serait un mensonge
 * confortable ; l'app ne promet que ce qu'elle constate.
 *
 * ### Détection
 *
 * [NetworkCapabilities.TRANSPORT_VPN] décrit le transport réellement utilisé,
 * là où l'ancienne inspection des interfaces (`tun0`, `ppp0`) donnait des faux
 * positifs sur les appareils rootés et manquait les VPN passant par des noms
 * d'interface non standards.
 *
 * NET_CAPABILITY_NOT_VPN est volontairement retiré de la requête : sans ça, le
 * système filtre les réseaux VPN de la réponse, et on ne verrait jamais celui
 * qu'on cherche précisément à détecter.
 */
@Singleton
class NetworkPrivacyMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val _tunnelled = MutableStateFlow(false)

    /** true si au moins un réseau actif transporte le trafic par un VPN. */
    val tunnelled: StateFlow<Boolean> = _tunnelled.asStateFlow()

    private val vpnNetworks = mutableSetOf<Network>()

    private val callback = object : ConnectivityManager.NetworkCallback() {

        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: NetworkCapabilities
        ) {
            val isVpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            synchronized(vpnNetworks) {
                if (isVpn) vpnNetworks.add(network) else vpnNetworks.remove(network)
                _tunnelled.value = vpnNetworks.isNotEmpty()
            }
        }

        override fun onLost(network: Network) {
            synchronized(vpnNetworks) {
                vpnNetworks.remove(network)
                _tunnelled.value = vpnNetworks.isNotEmpty()
            }
        }
    }

    fun start() {
        val manager = connectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        try {
            manager.registerNetworkCallback(request, callback)
            refreshFromActiveNetwork(manager)
        } catch (e: SecurityException) {
            // Certains constructeurs restreignent l'enregistrement. On perd
            // l'indicateur, pas l'app : il ne conditionne aucune fonction.
            DebugLog.w(TAG, "Surveillance réseau indisponible")
        }
    }

    fun stop() {
        try {
            connectivityManager?.unregisterNetworkCallback(callback)
        } catch (e: IllegalArgumentException) {
            // Déjà désenregistré.
        }
        synchronized(vpnNetworks) {
            vpnNetworks.clear()
            _tunnelled.value = false
        }
    }

    /**
     * Premier état.
     *
     * Le callback n'est appelé qu'au prochain changement : sans cette lecture
     * initiale, un VPN déjà actif au lancement resterait invisible jusqu'à ce
     * que l'utilisateur change de réseau.
     */
    private fun refreshFromActiveNetwork(manager: ConnectivityManager) {
        val active = manager.activeNetwork ?: return
        val capabilities = manager.getNetworkCapabilities(active) ?: return
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            synchronized(vpnNetworks) {
                vpnNetworks.add(active)
                _tunnelled.value = true
            }
        }
    }

    companion object {
        private const val TAG = "NetworkPrivacy"
    }
}
