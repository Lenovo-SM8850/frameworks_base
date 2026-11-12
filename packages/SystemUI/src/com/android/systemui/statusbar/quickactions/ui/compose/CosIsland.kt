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
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.android.internal.policy.SystemBarUtils

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
    val IconSize = 20.dp
    val TextSize = 14.sp
    val TextColor = Color(0xE6FFFFFF)

    val ExtraWidth = 16.dp

    val SwitchWidth = 80.dp

    val StrokeWidth = 1.3.dp
    val StrokeColor = Color(0x44FFFFFF)
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
    holeHeight: Dp = hole,
    modifier: Modifier = Modifier,
    tall: Boolean = false,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val holePx = with(density) { hole.toPx() }
    val heightPx = with(density) { CosIsland.height(holeHeight, heightScale, tall).toPx() }
    val currentHeightPx by rememberUpdatedState(heightPx)
    val extraPx = with(density) { CosIsland.ExtraWidth.toPx() }
    val switchPx = with(density) { CosIsland.SwitchWidth.toPx() }
    val middlePx = with(density) { CosIsland.MiddleWidth.toPx() }
    val extraHeightPx = with(density) { CosIsland.ExtraHeight.toPx() }
    val reduceHeightPx = with(density) { CosIsland.ReduceHeight.toPx() }
    val maxWidthPx = with(density) { (CosIsland.maxWidth(startAnchored) * widthScale).roundToPx() }
    val blurPx = with(density) { CosIsland.ContentBlur.toPx() }

    val width = remember { Animatable(0f) }
    val height = remember { Animatable(0f) }
    val top = remember { Animatable(heightPx / 2) }
    val scale = remember { Animatable(0f) }
    val alpha = remember { Animatable(0f) }
    val blur = remember { Animatable(blurPx) }
    val press = remember { Animatable(1f) }

    var natural by remember { mutableStateOf(IntSize.Zero) }
    var shownKey by remember { mutableStateOf(key) }
    var busy by remember { mutableStateOf(true) }
    var shownContent by remember { mutableStateOf(content) }
    if (key == shownKey) {
        SideEffect { shownContent = content }
    }
    val body = if (key == shownKey) content else shownContent
    val currentKey by rememberUpdatedState(key)
    val currentVisible by rememberUpdatedState(visible)
    val currentOnGone by rememberUpdatedState(onGone)

    suspend fun nextNatural(): IntSize {
        withFrameNanos {}
        withFrameNanos {}
        return snapshotFlow { natural }.first { it.width > 0 }
    }

    // Out of the camera as a dot, past a middle width a little taller than the capsule, then wider
    // than the content as the content comes in, then settling to the content's width.
    suspend fun appear() = coroutineScope {
        val target = nextNatural().width.toFloat().coerceAtLeast(1f)
        launch { width.animateTo(holePx, tween(50, easing = CosIsland.Move)) }
        launch { height.animateTo(holePx, tween(50, easing = CosIsland.Move)) }
        top.animateTo((heightPx - holePx) / 2, tween(50, easing = CosIsland.Move))
        launch { width.animateTo(middlePx, tween(200, easing = CosIsland.Offset)) }
        launch { height.animateTo(currentHeightPx + extraHeightPx, tween(200, easing = CosIsland.Offset)) }
        top.animateTo(-extraHeightPx, tween(200, easing = CosIsland.Offset))
        scale.snapTo(middlePx / target)
        launch { width.animateTo(target + extraPx, tween(300, easing = CosIsland.Return)) }
        launch {
            height.animateTo(currentHeightPx - reduceHeightPx, tween(300, easing = CosIsland.Return))
        }
        launch { top.animateTo(reduceHeightPx, tween(300, easing = CosIsland.Return)) }
        launch { alpha.animateTo(1f, tween(300, easing = CosIsland.Return)) }
        launch { blur.animateTo(0f, tween(300, easing = CosIsland.Return)) }
        scale.animateTo((target + extraPx) / target, tween(300, easing = CosIsland.Return))
        launch { width.animateTo(target, tween(400, easing = CosIsland.Move)) }
        launch { height.animateTo(currentHeightPx, tween(400, easing = CosIsland.Move)) }
        launch { top.animateTo(0f, tween(400, easing = CosIsland.Move)) }
        scale.animateTo(1f, tween(400, easing = CosIsland.Move))
    }

    suspend fun disappear() = coroutineScope {
        val linear300 = tween<Float>(300, easing = LinearEasing)
        val linear200 = tween<Float>(200, easing = LinearEasing)
        launch { width.animateTo(holePx, linear300) }
        launch { height.animateTo(holePx, linear300) }
        launch { top.animateTo((heightPx - holePx) / 2, linear300) }
        launch { alpha.animateTo(0f, linear200) }
        launch { blur.animateTo(blurPx, linear200) }
        scale.animateTo(0.1f, linear300)
        launch { width.animateTo(0f, tween(50, easing = LinearEasing)) }
        launch { height.animateTo(0f, tween(50, easing = LinearEasing)) }
        top.animateTo(heightPx / 2, tween(50, easing = LinearEasing))
    }

    suspend fun switchTo(newKey: Any) = coroutineScope {
        val old = natural.width.toFloat().coerceAtLeast(1f)
        val close = tween<Float>(200, easing = LinearEasing)
        launch { width.animateTo(switchPx, close) }
        launch { height.animateTo(heightPx, close) }
        launch { blur.animateTo(blurPx, close) }
        launch { scale.animateTo((switchPx / old).coerceAtMost(1f), close) }
        alpha.animateTo(0f, close)
        shownKey = newKey
        val target = nextNatural().width.toFloat()
        scale.snapTo((switchPx / target).coerceAtMost(1f))
        launch { alpha.animateTo(1f, tween(200, easing = CosIsland.SwitchGrow)) }
        launch { blur.animateTo(0f, tween(200, easing = CosIsland.SwitchGrow)) }
        launch { scale.animateTo(1.1f, tween(200, easing = CosIsland.SwitchGrow)) }
        width.animateTo(target + extraPx, tween(200, easing = CosIsland.SwitchGrow))
        launch { scale.animateTo(1f, tween(500, easing = CosIsland.SwitchSettle)) }
        width.animateTo(target, tween(500, easing = CosIsland.SwitchSettle))
    }

    LaunchedEffect(Unit) {
        snapshotFlow { currentVisible to currentKey }
            .collectLatest { (shown, newKey) ->
                busy = true
                when {
                    !shown -> {
                        disappear()
                        currentOnGone()
                    }
                    newKey != shownKey && alpha.value > 0.5f -> switchTo(newKey)
                    else -> {
                        shownKey = newKey
                        if (alpha.value < 1f || width.value < 1f) appear()
                    }
                }
                busy = false
            }
    }

    // Follow the height scale live while the island is up.
    LaunchedEffect(Unit) {
        snapshotFlow { currentHeightPx to busy }
            .collectLatest { (h, isBusy) ->
                if (!isBusy && currentVisible && height.value > 0f) {
                    height.animateTo(h, tween(300, easing = CosIsland.Move))
                }
            }
    }

    LaunchedEffect(Unit) {
        snapshotFlow { natural.width to busy }
            .collectLatest { (w, isBusy) ->
                if (!isBusy && w > 0 && currentVisible) {
                    width.animateTo(w.toFloat(), tween(300, easing = CosIsland.Move))
                }
            }
    }

    val scope = rememberCoroutineScope()
    val shape = RoundedCornerShape(50)
    Box(
        modifier =
            modifier
                .graphicsLayer { translationY = top.value }
                .graphicsLayer {
                    scaleX = press.value
                    scaleY = press.value
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        scope.launch { CosIsland.press(press, true) }
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                        } while (event.changes.any { it.pressed })
                        scope.launch { CosIsland.press(press, false) }
                    }
                }
    ) {
        Layout(
            content = {
                Box(
                    // The content keeps itself clear of the rounded ends, so no padding is added here.
                    Modifier.wrapContentSize()
                        // The content lambda already shows the new chip while the old one
                        // closes, so its size has to be taken whatever the shown key is.
                        .onSizeChanged { natural = it }
                        .graphicsLayer {
                            scaleX = scale.value
                            scaleY = scale.value
                            this.alpha = alpha.value
                            val radius = blur.value
                            renderEffect =
                                if (radius > 0.5f) BlurEffect(radius, radius, TileMode.Decal)
                                else null
                        }
                ) {
                    body()
                }
            },
            modifier =
                Modifier.graphicsLayer {
                        this.shape = shape
                        clip = true
                    }
                    .drawWithContent {
                        drawRoundRect(
                            Color.Black,
                            cornerRadius = CornerRadius(size.height / 2f),
                        )
                        drawContent()
                        val stroke = CosIsland.StrokeWidth.toPx()
                        if (size.minDimension > stroke * 2) {
                            drawRoundRect(
                                CosIsland.StrokeColor,
                                topLeft = Offset(stroke / 2f, stroke / 2f),
                                size = Size(size.width - stroke, size.height - stroke),
                                cornerRadius = CornerRadius((size.height - stroke) / 2f),
                                style = Stroke(stroke),
                            )
                        }
                    },
        ) { measurables, _ ->
            val placeable =
                measurables.first().measure(Constraints(maxWidth = maxWidthPx))
            val w = width.value.roundToInt().coerceAtLeast(0)
            val h = height.value.roundToInt().coerceAtLeast(0)
            layout(w, h) {
                placeable.place((w - placeable.width) / 2, (h - placeable.height) / 2)
            }
        }
    }
}
