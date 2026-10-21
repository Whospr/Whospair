package com.whisper.ui

import android.content.res.Configuration
import android.os.Bundle
import com.whisper.services.StorageService
import android.content.Context
import com.whisper.theme.AppLanguage
import com.whisper.theme.Appearance
import com.whisper.theme.ChatBackground
import javax.inject.Inject
import androidx.activity.ComponentActivity
import android.content.Intent
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.hilt.navigation.compose.hiltViewModel
import com.whisper.navigation.NavigationRoute
import com.whisper.navigation.WhisperNavigationBar
import com.whisper.theme.WhisperTheme
import com.whisper.ui.onboarding.OnboardingHost
import com.whisper.ui.lock.LockGate
import com.whisper.shield.ShieldedContent
import com.whisper.ui.contacts.DiscoverScreen
import com.whisper.ui.contacts.QrScannerScreen
import com.whisper.util.ContactKeyParser
import com.whisper.util.PendingContactLink
import com.whisper.viewmodels.DiscoverViewModel
import kotlinx.coroutines.flow.filterNotNull
import com.whisper.viewmodels.ContactViewModel
import com.whisper.viewmodels.IdentityViewModel
import com.whisper.ui.screens.ChatScreenContainer
import com.whisper.ui.screens.MessagesScreenContainer
import com.whisper.ui.screens.ContactsScreenContainer
import com.whisper.ui.screens.CallsScreen
import com.whisper.ui.screens.SettingsScreen
import com.whisper.ui.call.CallOverlay
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.whisper.ui.components.LocalBottomBarSpace
import androidx.compose.ui.input.pointer.pointerInput
import dagger.hilt.android.AndroidEntryPoint

/**
 * MainActivity - Point d'entrée principal de l'app
 * Gère la navigation et l'état global
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var storage: StorageService

    // Android 10 à 12 : la langue choisie dans Whospair (13+ : le système s'en charge).
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Jour ou nuit : le choix gardé, et l'état du téléphone pour « automatique ».
        // Android recrée l'activité quand le téléphone change de mode.
        Appearance.load(storage.getString(Appearance.KEY))
        Appearance.systemDark = (resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        ChatBackground.load(storage.getString(ChatBackground.KEY))

        // Interdit les captures d'écran ET retire le contenu de la vignette
        // du multitâche, qu'Android écrit sur le disque en clair.
        // Sans ça, le dernier écran consulté reste récupérable sur le support.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        // Un lien whisper://add_contact?key=… peut lancer l'app ou arriver
        // alors qu'elle tourne déjà : les deux chemins passent par ici.
        handleDeepLink(intent)

        setContent {
            WhisperTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // OnboardingHost garde la main tant que le premier lancement
                    // n'est pas terminé, puis cède la place à l'app.
                    // WhisperApp() n'est donc jamais composée sans identité valide,
                    // ce qui évite tous les états « clé publique vide » en aval.
                    OnboardingHost(
                        onExitApp = { finish() }
                    ) {
                        // Après l'onboarding (identité créée), avant l'app :
                        // rien ne s'ouvre sans le PIN.
                        // Déverrouillée, l'app vit sous le voile de l'écran masqué.
                        LockGate { ShieldedContent { com.whisper.ui.screens.MandatoryUpdateGate { WhisperApp() } } }
                    }
                }
            }
        }
    }

    /**
     * launchMode="singleTask" : un nouveau lien n'appelle PAS onCreate(),
     * il arrive ici. Sans cette surcharge, seul le tout premier lien serait
     * traité et les suivants seraient silencieusement ignorés.
     */
    /**
     * Retour dans Whisper : les fichiers prêtés à une autre app (« Ouvrir
     * avec… », « Partager ») sont effacés, et leurs accès retirés.
     */
    override fun onResume() {
        super.onResume()
        com.whisper.files.TempFileProvider.revokeAll(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    /**
     * Extrait une clé publique d'un lien entrant.
     *
     * Mémorisée seulement : l'ajout attend le déverrouillage (WhisperApp).
     */
    private fun handleDeepLink(intent: Intent?) {
        val data = intent?.data?.toString() ?: return
        val parsed = ContactKeyParser.parse(data)
        if (parsed is ContactKeyParser.Result.Valid) PendingContactLink.submit(parsed, scanned = false)
    }
}

/**
 * Composant principal de l'app
 */
@Composable
fun WhisperApp(
    identityViewModel: IdentityViewModel = hiltViewModel(),
    contactViewModel: ContactViewModel = hiltViewModel()
) {
    // État de navigation
    var currentRoute by remember { mutableStateOf(NavigationRoute.Home.route) }

    // Conversation ouverte par-dessus les onglets, comme le scanner.
    var openChat by remember { mutableStateOf<String?>(null) }

    // Le scanner QR se pose PAR-DESSUS l'app plutôt que d'être un onglet :
    // c'est une action ponctuelle, pas une destination.
    var showScanner by remember { mutableStateOf(false) }

    // Lien touché (avant ou après le PIN) ou QR scanné : un seul lecteur, ici.
    // On montre « Ajouter un contact », qui ajoute et affiche le résultat.
    val discoverViewModel: DiscoverViewModel = hiltViewModel()
    LaunchedEffect(Unit) {
        PendingContactLink.pending.filterNotNull().collect {
            PendingContactLink.consume()
            showScanner = false
            openChat = null
            currentRoute = NavigationRoute.Discover.route
            discoverViewModel.addPending(it)
        }
    }

    // Retour système : ferme ce qui est posé par-dessus, puis revient à
    // l'accueil. Sans ça, Retour fermait l'activité — et l'app se tue en
    // arrière-plan, donc tout était perdu.
    BackHandler(enabled = showScanner || openChat != null || currentRoute != NavigationRoute.Home.route) {
        when {
            showScanner -> showScanner = false
            openChat != null -> openChat = null
            else -> currentRoute = NavigationRoute.Home.route
        }
    }

    // Le clavier ne suit pas d'un écran à l'autre : sans ça, il restait
    // ouvert sur les onglets après une conversation.
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    LaunchedEffect(currentRoute, openChat, showScanner) {
        focus.clearFocus(force = true)
        keyboard?.hide()
    }

    // État de l'identité
    val identityState by identityViewModel.viewState.collectAsState()

    // Initialise l'identité au démarrage
    LaunchedEffect(Unit) {
        if (!identityState.isInitialized) {
            identityViewModel.initializeIdentity()
        }
    }

    // La barre d'onglets flotte : les écrans passent dessous et gardent
    // cet espace libre en fin de liste.
    val paddingValues = PaddingValues()
    CompositionLocalProvider(LocalBottomBarSpace provides 94.dp) {
    Box(Modifier.fillMaxSize()) {
        // Affiche le contenu selon la route
        when (currentRoute) {
            NavigationRoute.Home.route -> {
                MessagesScreenContainer(
                    paddingValues = paddingValues,
                    contactViewModel = contactViewModel,
                    onConversationClick = { openChat = it },
                    onAddContact = { currentRoute = NavigationRoute.Discover.route },
                    onOpenSettings = { currentRoute = NavigationRoute.Settings.route },
                    onNewChat = { currentRoute = NavigationRoute.Contacts.route }
                )
            }
            NavigationRoute.Contacts.route -> {
                ContactsScreenContainer(
                    contactViewModel = contactViewModel,
                    paddingValues = paddingValues,
                    onContactClick = { openChat = it },
                    onAddContact = { currentRoute = NavigationRoute.Discover.route },
                    onOpenScanner = { showScanner = true }
                )
            }
            NavigationRoute.Calls.route -> {
                CallsScreen(paddingValues = paddingValues)
            }
            NavigationRoute.Discover.route -> {
                DiscoverScreen(
                    paddingValues = paddingValues,
                    viewModel = discoverViewModel,
                    onOpenScanner = { showScanner = true }
                )
            }
            NavigationRoute.Settings.route -> {
                SettingsScreen(
                    identityViewModel = identityViewModel,
                    paddingValues = paddingValues,
                    onOpenDiscover = { currentRoute = NavigationRoute.Discover.route }
                )
            }
        }

        WhisperNavigationBar(
            currentRoute = currentRoute,
            onNavigate = { newRoute -> currentRoute = newRoute },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
    }

    openChat?.let { peer ->
        // Un écran neuf par contact : le bandeau peut sauter de X à Y, et un
        // vocal ou un brouillon commencé pour X ne doit jamais partir vers Y.
        key(peer) {
            Box(Modifier.fillMaxSize().swallowTouches()) {
                ChatScreenContainer(
                    contactPublicKeyHex = peer,
                    paddingValues = PaddingValues(),
                    onBackClick = { openChat = null }
                )
            }
        }
    }

    if (showScanner) {
        Box(Modifier.fillMaxSize().swallowTouches()) {
            QrScannerScreen(
                onScanned = { scanned ->
                    // Le résultat repasse par le même relais que les liens
                    // profonds : un seul chemin d'ajout, donc un seul endroit
                    // où les garde-fous s'appliquent.
                    val parsed = ContactKeyParser.parse(scanned)
                    if (parsed is ContactKeyParser.Result.Valid) PendingContactLink.submit(parsed, scanned = true)
                    else showScanner = false
                },
                onCancel = { showScanner = false }
            )
        }
    }

    // Message ou signe d'un contact : bandeau en haut, jamais de notification système.
    com.whisper.alerts.AlertBanner(openConversation = openChat, onOpen = { openChat = it; showScanner = false })

    // En dernier : un appel passe devant tout le reste.
    CallOverlay()
}

/**
 * Écran posé par-dessus les onglets : il garde toutes les touches. Sans ça,
 * un appui dans une zone vide de la conversation traversait jusqu'à l'onglet
 * du dessous (« Ajouter un contact »), et Retour ramenait sur la page scan.
 * Recevoir suffit : l'écran touché, ceux d'en dessous ne le sont plus. Rien
 * n'est consommé, sinon les appuis sur les boutons de l'écran seraient annulés.
 */
private fun Modifier.swallowTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent()
    }
}
