package com.whisper.ui.icons.outlined

import com.whisper.ui.icons.Icons
import com.whisper.ui.icons.materialIcon
import com.whisper.ui.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

/** Punaise (épingler une conversation, 1.3.8), dessinée en traits droits. */
public val Icons.Outlined.PushPin: ImageVector
    get() {
        if (_pushPin != null) {
            return _pushPin!!
        }
        _pushPin = materialIcon(name = "Outlined.PushPin") {
            materialPath {
                moveTo(16.0f, 9.0f)
                verticalLineTo(4.0f)
                horizontalLineTo(17.0f)
                verticalLineTo(2.0f)
                horizontalLineTo(7.0f)
                verticalLineTo(4.0f)
                horizontalLineTo(8.0f)
                verticalLineTo(9.0f)
                lineTo(5.0f, 12.0f)
                verticalLineTo(14.0f)
                horizontalLineTo(11.0f)
                verticalLineTo(21.0f)
                lineTo(12.0f, 22.0f)
                lineTo(13.0f, 21.0f)
                verticalLineTo(14.0f)
                horizontalLineTo(19.0f)
                verticalLineTo(12.0f)
                close()
                // Évidement (sens inverse) : la punaise reste au trait.
                moveTo(8.8f, 12.0f)
                lineTo(10.0f, 9.0f)
                verticalLineTo(4.0f)
                horizontalLineTo(14.0f)
                verticalLineTo(9.0f)
                lineTo(15.2f, 12.0f)
                close()
            }
        }
        return _pushPin!!
    }

private var _pushPin: ImageVector? = null
