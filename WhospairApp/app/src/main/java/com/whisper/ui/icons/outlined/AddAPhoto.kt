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

public val Icons.Outlined.AddAPhoto: ImageVector
    get() {
        if (_addAPhoto != null) {
            return _addAPhoto!!
        }
        _addAPhoto = materialIcon(name = "Outlined.AddAPhoto") {
            materialPath {
                moveTo(21.0f, 6.0f)
                horizontalLineToRelative(-3.17f)
                lineTo(16.0f, 4.0f)
                horizontalLineToRelative(-6.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(5.12f)
                lineToRelative(1.83f, 2.0f)
                lineTo(21.0f, 8.0f)
                verticalLineToRelative(12.0f)
                lineTo(5.0f, 20.0f)
                verticalLineToRelative(-9.0f)
                lineTo(3.0f, 11.0f)
                verticalLineToRelative(9.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(16.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                lineTo(23.0f, 8.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                close()
                moveTo(8.0f, 14.0f)
                curveToRelative(0.0f, 2.76f, 2.24f, 5.0f, 5.0f, 5.0f)
                reflectiveCurveToRelative(5.0f, -2.24f, 5.0f, -5.0f)
                reflectiveCurveToRelative(-2.24f, -5.0f, -5.0f, -5.0f)
                reflectiveCurveToRelative(-5.0f, 2.24f, -5.0f, 5.0f)
                close()
                moveTo(13.0f, 11.0f)
                curveToRelative(1.65f, 0.0f, 3.0f, 1.35f, 3.0f, 3.0f)
                reflectiveCurveToRelative(-1.35f, 3.0f, -3.0f, 3.0f)
                reflectiveCurveToRelative(-3.0f, -1.35f, -3.0f, -3.0f)
                reflectiveCurveToRelative(1.35f, -3.0f, 3.0f, -3.0f)
                close()
                moveTo(5.0f, 6.0f)
                horizontalLineToRelative(3.0f)
                lineTo(8.0f, 4.0f)
                lineTo(5.0f, 4.0f)
                lineTo(5.0f, 1.0f)
                lineTo(3.0f, 1.0f)
                verticalLineToRelative(3.0f)
                lineTo(0.0f, 4.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(3.0f)
                verticalLineToRelative(3.0f)
                horizontalLineToRelative(2.0f)
                close()
            }
        }
        return _addAPhoto!!
    }

private var _addAPhoto: ImageVector? = null
