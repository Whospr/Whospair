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

public val Icons.Outlined.WavingHand: ImageVector
    get() {
        if (_wavingHand != null) {
            return _wavingHand!!
        }
        _wavingHand = materialIcon(name = "Outlined.WavingHand") {
            materialPath {
                moveTo(7.03f, 4.95f)
                lineTo(3.49f, 8.49f)
                curveToRelative(-3.32f, 3.32f, -3.32f, 8.7f, 0.0f, 12.02f)
                reflectiveCurveToRelative(8.7f, 3.32f, 12.02f, 0.0f)
                lineToRelative(6.01f, -6.01f)
                curveToRelative(0.97f, -0.97f, 0.97f, -2.56f, 0.0f, -3.54f)
                curveToRelative(-0.12f, -0.12f, -0.25f, -0.23f, -0.39f, -0.32f)
                lineToRelative(0.39f, -0.39f)
                curveToRelative(0.97f, -0.97f, 0.97f, -2.56f, 0.0f, -3.54f)
                curveToRelative(-0.16f, -0.16f, -0.35f, -0.3f, -0.54f, -0.41f)
                curveToRelative(0.4f, -0.92f, 0.23f, -2.02f, -0.52f, -2.77f)
                curveToRelative(-0.87f, -0.87f, -2.22f, -0.96f, -3.2f, -0.28f)
                curveToRelative(-0.1f, -0.15f, -0.21f, -0.29f, -0.34f, -0.42f)
                curveToRelative(-0.97f, -0.97f, -2.56f, -0.97f, -3.54f, 0.0f)
                lineToRelative(-2.51f, 2.51f)
                curveToRelative(-0.09f, -0.14f, -0.2f, -0.27f, -0.32f, -0.39f)
                curveTo(9.58f, 3.98f, 8.0f, 3.98f, 7.03f, 4.95f)
                close()
                moveTo(8.44f, 6.37f)
                curveToRelative(0.2f, -0.2f, 0.51f, -0.2f, 0.71f, 0.0f)
                curveToRelative(0.2f, 0.2f, 0.2f, 0.51f, 0.0f, 0.71f)
                lineToRelative(-3.18f, 3.18f)
                curveToRelative(1.17f, 1.17f, 1.17f, 3.07f, 0.0f, 4.24f)
                lineToRelative(1.41f, 1.41f)
                curveToRelative(1.45f, -1.45f, 1.82f, -3.57f, 1.12f, -5.36f)
                lineToRelative(6.3f, -6.3f)
                curveToRelative(0.2f, -0.2f, 0.51f, -0.2f, 0.71f, 0.0f)
                reflectiveCurveToRelative(0.2f, 0.51f, 0.0f, 0.71f)
                lineToRelative(-4.6f, 4.6f)
                lineToRelative(1.41f, 1.41f)
                lineToRelative(6.01f, -6.01f)
                curveToRelative(0.2f, -0.2f, 0.51f, -0.2f, 0.71f, 0.0f)
                curveToRelative(0.2f, 0.2f, 0.2f, 0.51f, 0.0f, 0.71f)
                lineToRelative(-6.01f, 6.01f)
                lineToRelative(1.41f, 1.41f)
                lineToRelative(4.95f, -4.95f)
                curveToRelative(0.2f, -0.2f, 0.51f, -0.2f, 0.71f, 0.0f)
                curveToRelative(0.2f, 0.2f, 0.2f, 0.51f, 0.0f, 0.71f)
                lineToRelative(-5.66f, 5.66f)
                lineToRelative(1.41f, 1.41f)
                lineToRelative(3.54f, -3.54f)
                curveToRelative(0.2f, -0.2f, 0.51f, -0.2f, 0.71f, 0.0f)
                curveToRelative(0.2f, 0.2f, 0.2f, 0.51f, 0.0f, 0.71f)
                lineTo(14.1f, 19.1f)
                curveToRelative(-2.54f, 2.54f, -6.65f, 2.54f, -9.19f, 0.0f)
                reflectiveCurveToRelative(-2.54f, -6.65f, 0.0f, -9.19f)
                lineTo(8.44f, 6.37f)
                close()
                moveTo(23.0f, 17.0f)
                curveToRelative(0.0f, 3.31f, -2.69f, 6.0f, -6.0f, 6.0f)
                verticalLineToRelative(-1.5f)
                curveToRelative(2.48f, 0.0f, 4.5f, -2.02f, 4.5f, -4.5f)
                horizontalLineTo(23.0f)
                close()
                moveTo(1.0f, 7.0f)
                curveToRelative(0.0f, -3.31f, 2.69f, -6.0f, 6.0f, -6.0f)
                verticalLineToRelative(1.5f)
                curveTo(4.52f, 2.5f, 2.5f, 4.52f, 2.5f, 7.0f)
                horizontalLineTo(1.0f)
                close()
            }
        }
        return _wavingHand!!
    }

private var _wavingHand: ImageVector? = null
