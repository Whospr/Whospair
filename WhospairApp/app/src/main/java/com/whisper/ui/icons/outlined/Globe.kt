package com.whisper.ui.icons.outlined

import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.vector.ImageVector
import com.whisper.ui.icons.Icons
import com.whisper.ui.icons.materialIcon
import com.whisper.ui.icons.materialPath

/** Un globe, pour Langue. */
public val Icons.Outlined.Globe: ImageVector
    get() {
        if (_globe != null) {
            return _globe!!
        }
        _globe = materialIcon(name = "Outlined.Globe") {
            // Le contour : deux cercles, le plein entre les deux.
            materialPath(pathFillType = PathFillType.EvenOdd) {
                moveTo(12.0f, 2.0f)
                arcTo(10.0f, 10.0f, 0.0f, true, true, 11.99f, 2.0f)
                close()
                moveTo(12.0f, 3.6f)
                arcTo(8.4f, 8.4f, 0.0f, true, true, 11.99f, 3.6f)
                close()
            }
            // Le méridien : une ellipse creuse.
            materialPath(pathFillType = PathFillType.EvenOdd) {
                moveTo(12.0f, 2.0f)
                arcTo(4.4f, 10.0f, 0.0f, true, true, 11.99f, 2.0f)
                close()
                moveTo(12.0f, 3.6f)
                arcTo(2.9f, 8.4f, 0.0f, true, true, 11.99f, 3.6f)
                close()
            }
            // L'équateur et deux parallèles.
            materialPath {
                moveTo(2.6f, 11.2f)
                horizontalLineTo(21.4f)
                verticalLineTo(12.8f)
                horizontalLineTo(2.6f)
                close()
                moveTo(4.2f, 7.0f)
                horizontalLineTo(19.8f)
                verticalLineTo(8.4f)
                horizontalLineTo(4.2f)
                close()
                moveTo(4.2f, 15.6f)
                horizontalLineTo(19.8f)
                verticalLineTo(17.0f)
                horizontalLineTo(4.2f)
                close()
            }
        }
        return _globe!!
    }

private var _globe: ImageVector? = null
