package com.whisper.ui.icons.outlined

import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.vector.ImageVector
import com.whisper.ui.icons.Icons
import com.whisper.ui.icons.materialIcon
import com.whisper.ui.icons.materialPath

/** Un visage souriant, en contour : le bouton du panneau d'emojis. */
public val Icons.Outlined.Smile: ImageVector
    get() {
        if (_smile != null) {
            return _smile!!
        }
        _smile = materialIcon(name = "Outlined.Smile") {
            // Le contour du visage.
            materialPath(pathFillType = PathFillType.EvenOdd) {
                moveTo(12.0f, 2.0f)
                arcTo(10.0f, 10.0f, 0.0f, true, true, 11.99f, 2.0f)
                close()
                moveTo(12.0f, 3.8f)
                arcTo(8.2f, 8.2f, 0.0f, true, true, 11.99f, 3.8f)
                close()
            }
            // Les yeux.
            materialPath {
                moveTo(8.8f, 8.3f)
                arcTo(1.4f, 1.4f, 0.0f, true, true, 8.79f, 8.3f)
                close()
                moveTo(15.2f, 8.3f)
                arcTo(1.4f, 1.4f, 0.0f, true, true, 15.19f, 8.3f)
                close()
            }
            // Le sourire : un croissant.
            materialPath {
                moveTo(7.2f, 13.2f)
                arcTo(4.9f, 4.9f, 0.0f, false, false, 16.8f, 13.2f)
                arcTo(6.4f, 6.4f, 0.0f, false, true, 7.2f, 13.2f)
                close()
            }
        }
        return _smile!!
    }

private var _smile: ImageVector? = null
