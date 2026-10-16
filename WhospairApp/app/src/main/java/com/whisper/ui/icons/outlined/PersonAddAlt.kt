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

public val Icons.Outlined.PersonAddAlt: ImageVector
    get() {
        if (_personAddAlt != null) {
            return _personAddAlt!!
        }
        _personAddAlt = materialIcon(name = "Outlined.PersonAddAlt") {
            materialPath {
                moveTo(20.0f, 9.0f)
                verticalLineTo(6.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(3.0f)
                horizontalLineToRelative(-3.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(3.0f)
                verticalLineToRelative(3.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(-3.0f)
                horizontalLineToRelative(3.0f)
                verticalLineTo(9.0f)
                horizontalLineTo(20.0f)
                close()
                moveTo(9.0f, 12.0f)
                curveToRelative(2.21f, 0.0f, 4.0f, -1.79f, 4.0f, -4.0f)
                curveToRelative(0.0f, -2.21f, -1.79f, -4.0f, -4.0f, -4.0f)
                reflectiveCurveTo(5.0f, 5.79f, 5.0f, 8.0f)
                curveTo(5.0f, 10.21f, 6.79f, 12.0f, 9.0f, 12.0f)
                close()
                moveTo(9.0f, 6.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, 0.9f, 2.0f, 2.0f)
                curveToRelative(0.0f, 1.1f, -0.9f, 2.0f, -2.0f, 2.0f)
                reflectiveCurveTo(7.0f, 9.1f, 7.0f, 8.0f)
                curveTo(7.0f, 6.9f, 7.9f, 6.0f, 9.0f, 6.0f)
                close()
                moveTo(15.39f, 14.56f)
                curveTo(13.71f, 13.7f, 11.53f, 13.0f, 9.0f, 13.0f)
                curveToRelative(-2.53f, 0.0f, -4.71f, 0.7f, -6.39f, 1.56f)
                curveTo(1.61f, 15.07f, 1.0f, 16.1f, 1.0f, 17.22f)
                verticalLineTo(20.0f)
                horizontalLineToRelative(16.0f)
                verticalLineToRelative(-2.78f)
                curveTo(17.0f, 16.1f, 16.39f, 15.07f, 15.39f, 14.56f)
                close()
                moveTo(15.0f, 18.0f)
                horizontalLineTo(3.0f)
                verticalLineToRelative(-0.78f)
                curveToRelative(0.0f, -0.38f, 0.2f, -0.72f, 0.52f, -0.88f)
                curveTo(4.71f, 15.73f, 6.63f, 15.0f, 9.0f, 15.0f)
                curveToRelative(2.37f, 0.0f, 4.29f, 0.73f, 5.48f, 1.34f)
                curveTo(14.8f, 16.5f, 15.0f, 16.84f, 15.0f, 17.22f)
                verticalLineTo(18.0f)
                close()
            }
        }
        return _personAddAlt!!
    }

private var _personAddAlt: ImageVector? = null
