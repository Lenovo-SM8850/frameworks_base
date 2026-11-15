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

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalConfiguration

import io.github.miner7222.systemui.island.CosIslandShell as IslandShell

internal object CosIsland {
    val MinHeight = 36.dp
    @Composable
    fun maxWidth(startAnchored: Boolean): Dp =
        if (tablet()) 296.dp else if (startAnchored) 168.dp else 202.dp
    val MinWidth: Dp
        @Composable get() = if (tablet()) 164.dp else 100.dp

    @Composable
    private fun tablet(): Boolean {
        val config = LocalConfiguration.current
        return minOf(config.screenWidthDp, config.screenHeightDp) >= 600
    }

    val ContentPadding = 5.dp
    val TextMargin = 4.dp
    val TextGoneMargin = 5.dp
    val NormalIconMargin = 3.dp
    val IconListGap = 14.dp
    val IconSize = 20.dp
    val TextSize = 14.sp
    val TextColor = Color(0xE6FFFFFF)

    val ExtraWidth = 16.dp

    val SwitchWidth = 80.dp

    val StrokeWidth = 1.3.dp
    val StrokeColor = Color(0x40959595)
    val ContentBlur = 9.dp

    val Move = CubicBezierEasing(0.3f, 0f, 0.1f, 1f)
    val SwitchGrow = CubicBezierEasing(0.17f, 0.23f, 0.83f, 0.95f)
    /** The capsule coming out of the camera, past its middle width. */
    val Offset = CubicBezierEasing(0.3f, 0f, 0.83f, 0.88f)
    /** The capsule overshooting its width as its content comes in. */
    val Return = CubicBezierEasing(0.17f, 0.12f, 0.67f, 1f)
    /** Width the capsule passes on its way out, and how much taller and then lower it is. */
    val MiddleWidth = 80.dp
    val ExtraHeight = 3.dp
    val ReduceHeight = 1.dp
    val SwitchSettle = CubicBezierEasing(0.17f, 0.56f, 0.1f, 1f)
    private val PressDown = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
    private val PressUp = CubicBezierEasing(0f, 0f, 0.2f, 1f)

    /**
     * Calls get a taller, wider pill: the proximity sensor sits by the camera, so the bigger pill
     * lets a tap land away from it instead of blanking the screen mid-call.
     */
    val CallExtraHeight = 10.dp
    val CallMinWidth = 150.dp

    /** Island height around a camera [hole], scaled by the user's height [scale] but never
     *  shorter than the hole itself, and taller for a call when [tall]. */
    fun height(hole: Dp, scale: Float = 1f, tall: Boolean = false): Dp =
        maxOf(hole, maxOf(MinHeight, hole + 4.dp) * scale) +
            if (tall) CallExtraHeight else 0.dp

    internal suspend fun press(scale: Animatable<Float, *>, down: Boolean) {
        if (down) scale.animateTo(0.98f, tween(200, easing = PressDown))
        else scale.animateTo(1f, tween(340, easing = PressUp))
    }
}

@Composable
internal fun CosIslandShell(
    key: Any,
    visible: Boolean,
    hole: Dp,
    onGone: () -> Unit,
    heightScale: Float = 1f,
    widthScale: Float = 1f,
    startAnchored: Boolean = false,
    multiple: Boolean = false,
    strokeColor: Color? = null,
    holeHeight: Dp = hole,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) =
    IslandShell(
        key = key,
        visible = visible,
        hole = hole,
        onGone = onGone,
        heightScale = heightScale,
        widthScale = widthScale,
        startAnchored = startAnchored,
        multiple = multiple,
        strokeColor = strokeColor,
        holeHeight = holeHeight,
        modifier = modifier,
        content = content,
    )
