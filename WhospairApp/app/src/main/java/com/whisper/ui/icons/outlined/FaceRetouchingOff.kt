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

public val Icons.Outlined.FaceRetouchingOff: ImageVector
    get() {
        if (_faceRetouchingOff != null) {
            return _faceRetouchingOff!!
        }
        _faceRetouchingOff = materialIcon(name = "Outlined.FaceRetouchingOff") {
            materialPath {
                moveTo(9.0f, 13.0f)
                moveToRelative(-1.25f, 0.0f)
                arcToRelative(1.25f, 1.25f, 0.0f, true, true, 2.5f, 0.0f)
                arcToRelative(1.25f, 1.25f, 0.0f, true, true, -2.5f, 0.0f)
            }
            materialPath {
                moveTo(17.5f, 10.0f)
                curveToRelative(0.75f, 0.0f, 1.47f, -0.09f, 2.17f, -0.24f)
                curveTo(19.88f, 10.47f, 20.0f, 11.22f, 20.0f, 12.0f)
                curveToRelative(0.0f, 1.22f, -0.28f, 2.37f, -0.77f, 3.4f)
                lineToRelative(1.49f, 1.49f)
                curveTo(21.53f, 15.44f, 22.0f, 13.78f, 22.0f, 12.0f)
                curveToRelative(0.0f, -5.52f, -4.48f, -10.0f, -10.0f, -10.0f)
                curveToRelative(-1.78f, 0.0f, -3.44f, 0.47f, -4.89f, 1.28f)
                lineToRelative(5.33f, 5.33f)
                curveTo(13.93f, 9.49f, 15.65f, 10.0f, 17.5f, 10.0f)
                close()
                moveTo(10.66f, 4.12f)
                curveTo(11.09f, 4.05f, 11.54f, 4.0f, 12.0f, 4.0f)
                curveToRelative(2.9f, 0.0f, 5.44f, 1.56f, 6.84f, 3.88f)
                curveTo(18.41f, 7.95f, 17.96f, 8.0f, 17.5f, 8.0f)
                curveTo(14.6f, 8.0f, 12.06f, 6.44f, 10.66f, 4.12f)
                close()
            }
            materialPath {
                moveTo(1.89f, 3.72f)
                lineToRelative(2.19f, 2.19f)
                curveTo(2.78f, 7.6f, 2.0f, 9.71f, 2.0f, 12.0f)
                curveToRelative(0.0f, 5.52f, 4.48f, 10.0f, 10.0f, 10.0f)
                curveToRelative(2.29f, 0.0f, 4.4f, -0.78f, 6.09f, -2.08f)
                lineToRelative(2.19f, 2.19f)
                lineToRelative(1.41f, -1.41f)
                lineTo(3.31f, 2.31f)
                lineTo(1.89f, 3.72f)
                close()
                moveTo(16.66f, 18.49f)
                curveTo(15.35f, 19.44f, 13.74f, 20.0f, 12.0f, 20.0f)
                curveToRelative(-4.41f, 0.0f, -8.0f, -3.59f, -8.0f, -8.0f)
                curveToRelative(0.0f, -0.05f, 0.01f, -0.1f, 0.0f, -0.14f)
                curveToRelative(1.39f, -0.52f, 2.63f, -1.35f, 3.64f, -2.39f)
                lineTo(16.66f, 18.49f)
                close()
                moveTo(6.23f, 8.06f)
                curveTo(5.7f, 8.61f, 5.09f, 9.09f, 4.42f, 9.47f)
                curveTo(4.68f, 8.7f, 5.05f, 7.99f, 5.51f, 7.34f)
                lineTo(6.23f, 8.06f)
                close()
            }
        }
        return _faceRetouchingOff!!
    }

private var _faceRetouchingOff: ImageVector? = null
