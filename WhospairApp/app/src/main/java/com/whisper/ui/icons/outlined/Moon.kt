package com.whisper.ui.icons.outlined

import androidx.compose.ui.graphics.vector.ImageVector
import com.whisper.ui.icons.Icons
import com.whisper.ui.icons.materialIcon
import com.whisper.ui.icons.materialPath

/** Un croissant de lune, pour Apparence (jour / nuit). */
public val Icons.Outlined.Moon: ImageVector
    get() {
        if (_moon != null) {
            return _moon!!
        }
        _moon = materialIcon(name = "Outlined.Moon") {
            materialPath {
                moveTo(20.0f, 14.5f)
                arcTo(8.0f, 8.0f, 0.0f, false, true, 9.5f, 4.0f)
                arcTo(8.0f, 8.0f, 0.0f, true, false, 20.0f, 14.5f)
                close()
            }
        }
        return _moon!!
    }

private var _moon: ImageVector? = null
