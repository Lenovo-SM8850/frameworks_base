/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.systemui.statusbar.quickactions.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.axion.blur.AxBlurSurface
import com.android.axion.blur.AxBlurSurfaceDefaults
import com.android.systemui.statusbar.quickactions.popups.shared.DynamicIslandFeatureSettings.POPUP_COLOR_MODE_BLUR
import com.android.systemui.statusbar.quickactions.popups.shared.DynamicIslandFeatureSettings.POPUP_COLOR_MODE_SOLID_BLACK
import com.android.systemui.statusbar.quickactions.popups.shared.DynamicIslandFeatureSettings.observeDynamicIslandPopupColorMode
import com.android.systemui.statusbar.quickactions.popups.shared.DynamicIslandFeatureSettings.readDynamicIslandPopupColorMode

@Composable
fun rememberPopupColorMode(): Int {
    val context = LocalContext.current
    val initial = remember(context) { context.contentResolver.readDynamicIslandPopupColorMode() }
    val flow = remember(context) { observeDynamicIslandPopupColorMode(context) }
    val mode by flow.collectAsState(initial = initial)
    return mode
}

// The morph supplies one surface for the entire popup, including its lyrics.
internal val LocalPopupContainer = staticCompositionLocalOf { false }

@Composable
fun PopupSurface(
    shape: Shape,
    modifier: Modifier = Modifier,
    shadowElevation: Dp = 12.dp,
    colorMode: Int = rememberPopupColorMode(),
    cornerRadius: Dp = 32.dp,
    containerProgress: Float = 1f,
    content: @Composable () -> Unit,
) {
    if (LocalPopupContainer.current) {
        val contentColor =
            if (colorMode == POPUP_COLOR_MODE_SOLID_BLACK) Color.White
            else MaterialTheme.colorScheme.onSurface
        Box(modifier = modifier) {
            CompositionLocalProvider(LocalContentColor provides contentColor) { content() }
        }
        return
    }
    val surfaceContent: @Composable () -> Unit = {
        Box(modifier = if (containerProgress < 1f) Modifier.fillMaxSize() else Modifier) {
            content()
            if (containerProgress < 1f) {
                Box(
                    Modifier.matchParentSize()
                        .background(Color.Black.copy(alpha = 1f - containerProgress))
                )
            }
        }
    }
    when (colorMode) {
        POPUP_COLOR_MODE_BLUR -> {
            AxBlurSurface(
                modifier = modifier,
                shape = shape,
                cornerRadius = cornerRadius,
                highlight = AxBlurSurfaceDefaults.highlightBrush(),
            ) {
                // The blur surface follows the theme, so its content does too.
                CompositionLocalProvider(
                    LocalContentColor provides MaterialTheme.colorScheme.onSurface
                ) {
                    surfaceContent()
                }
            }
        }
        POPUP_COLOR_MODE_SOLID_BLACK -> {
            Surface(
                color = Color.Black,
                contentColor = Color.White,
                shape = shape,
                shadowElevation = shadowElevation,
                modifier = modifier,
            ) {
                surfaceContent()
            }
        }
        else -> {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = shape,
                shadowElevation = shadowElevation,
                modifier = modifier,
            ) {
                surfaceContent()
            }
        }
    }
}
