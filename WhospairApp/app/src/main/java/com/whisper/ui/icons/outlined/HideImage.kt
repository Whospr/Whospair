/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.whisper.ui.icons.outlined

import com.whisper.ui.icons.Icons
import com.whisper.ui.icons.materialIcon
import com.whisper.ui.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

public val Icons.Outlined.HideImage: ImageVector
    get() {
        if (_hideImage != null) {
            return _hideImage!!
        }
        _hideImage = materialIcon(name = "Outlined.HideImage") {
            materialPath {
                moveTo(19.0f, 5.0f)
                verticalLineToRelative(11.17f)
                lineToRelative(2.0f, 2.0f)
                verticalLineTo(5.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                horizontalLineTo(5.83f)
                lineToRelative(2.0f, 2.0f)
                horizontalLineTo(19.0f)
                close()
            }
            materialPath {
                moveTo(2.81f, 2.81f)
                lineTo(1.39f, 4.22f)
                lineTo(3.0f, 5.83f)
                verticalLineTo(19.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(13.17f)
                lineToRelative(1.61f, 1.61f)
                lineToRelative(1.41f, -1.41f)
                lineTo(2.81f, 2.81f)
                close()
                moveTo(5.0f, 19.0f)
                verticalLineTo(7.83f)
                lineToRelative(7.07f, 7.07f)
                lineTo(11.25f, 16.0f)
                lineTo(9.0f, 13.0f)
                lineToRelative(-3.0f, 4.0f)
                horizontalLineToRelative(8.17f)
                lineToRelative(2.0f, 2.0f)
                horizontalLineTo(5.0f)
                close()
            }
        }
        return _hideImage!!
    }

private var _hideImage: ImageVector? = null
