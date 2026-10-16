package com.whisper.ui

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.whisper.security.SessionLifecycle

/**
 * Tuile « Verrouiller Whospair » des réglages rapides (1.3.7) : le même effet
 * que « Verrouiller maintenant » des Réglages, depuis n'importe quel écran.
 * Le processus de l'app est tué — rien de déchiffré ne reste en mémoire — ;
 * « Rester joignable » continue en arrière-plan s'il est actif.
 */
class LockTileService : TileService() {

    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        SessionLifecycle.lockNow()
    }
}
