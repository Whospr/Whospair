package com.whisper.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import com.whisper.ui.icons.Icons
import com.whisper.ui.icons.outlined.ChatBubbleOutline
import com.whisper.ui.icons.outlined.Person
import com.whisper.ui.icons.outlined.PersonAddAlt
import com.whisper.ui.icons.outlined.Settings
import com.whisper.ui.icons.rounded.ChatBubble
import com.whisper.ui.icons.rounded.Person
import com.whisper.ui.icons.rounded.PersonAddAlt1
import com.whisper.ui.icons.rounded.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.sp
import com.whisper.ui.components.glass
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.whisper.R
import com.whisper.theme.Ink

/**
 * Routes de navigation
 */
sealed class NavigationRoute(val route: String) {
    object Home : NavigationRoute("home")
    object Contacts : NavigationRoute("contacts")
    object Calls : NavigationRoute("calls")
    object Discover : NavigationRoute("discover")
    object Settings : NavigationRoute("settings")
}

/**
 * Un onglet : l'icône en contour au repos, pleine quand il est actif.
 * C'est le seul endroit de l'app où une icône change de forme.
 */
data class NavigationItem(
    @StringRes val label: Int,
    val route: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector
)

/**
 * Quatre onglets. Appels reviendra avec la voix sur Tor : un onglet qui ne
 * mène nulle part n'a rien à faire dans la barre.
 */
val navigationItems = listOf(
    NavigationItem(R.string.nav_home, NavigationRoute.Home.route, Icons.Outlined.ChatBubbleOutline, Icons.Rounded.ChatBubble),
    NavigationItem(R.string.nav_contacts, NavigationRoute.Contacts.route, Icons.Outlined.Person, Icons.Rounded.Person),
    NavigationItem(R.string.nav_discover, NavigationRoute.Discover.route, Icons.Outlined.PersonAddAlt, Icons.Rounded.PersonAddAlt1),
    NavigationItem(R.string.nav_settings, NavigationRoute.Settings.route, Icons.Outlined.Settings, Icons.Rounded.Settings)
)

/**
 * La barre d'onglets flotte au-dessus du contenu : une capsule étroite,
 * centrée, l'onglet actif posé sur une pastille sauge. Les listes passent
 * dessous en défilant (voir LocalBottomBarSpace).
 */
@Composable
fun WhisperNavigationBar(
    currentRoute: String,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(33.dp)
    Row(
        modifier
            .navigationBarsPadding()
            .padding(horizontal = 30.dp, vertical = 14.dp)
            .widthIn(max = 380.dp)
            .fillMaxWidth()
            .height(66.dp)
            .glass(shape)
            .selectableGroup()
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        navigationItems.forEach { item ->
            val selected = currentRoute == item.route
            val pill = RoundedCornerShape(27.dp)
            Column(
                Modifier
                    .weight(1f)
                    .height(54.dp)
                    .clip(pill)
                    .background(if (selected) Ink.Sage.copy(alpha = 0.16f) else Color.Transparent, pill)
                    .selectable(selected = selected, role = Role.Tab, onClick = { onNavigate(item.route) }),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Le libellé est lu par le lecteur d'écran : l'icône se tait.
                Icon(
                    if (selected) item.selectedIcon else item.icon,
                    contentDescription = null,
                    tint = if (selected) Ink.SageLight else Ink.Ash,
                    modifier = Modifier.size(23.dp)
                )
                Text(
                    stringResource(item.label),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp),
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (selected) Ink.Chalk else Ink.Ash,
                    maxLines = 1
                )
            }
        }
    }
}
