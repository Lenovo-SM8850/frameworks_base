/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

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

package io.github.miner7222.systemui.island

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.MutatorMutex
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.android.internal.policy.SystemBarUtils
import com.android.systemui.Dependency
import com.android.systemui.plugins.DarkIconDispatcher
import com.android.systemui.res.R

import com.android.systemui.statusbar.quickactions.ui.compose.CosIsland

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
    tall: Boolean = false,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
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

    val leftMoon = painterResource(R.drawable.island_left_moon)
    val rightMoon = painterResource(R.drawable.island_right_moon)
    val moonAlpha = remember { Animatable(0f) }
    LaunchedEffect(multiple, visible) {
        moonAlpha.animateTo(if (multiple && visible) 1f else 0f, tween(300, easing = LinearEasing))
    }
    var darkIntensity by remember { mutableFloatStateOf(0f) }
    var shellBounds by remember { mutableStateOf(android.graphics.Rect()) }
    var darkAreas by remember { mutableStateOf(ArrayList<android.graphics.Rect>()) }
    DisposableEffect(Unit) {
        val dispatcher = Dependency.get(DarkIconDispatcher::class.java)
        val receiver =
            object : DarkIconDispatcher.DarkReceiver {
                override fun onDarkChanged(
                    areas: ArrayList<android.graphics.Rect>,
                    intensity: Float,
                    tint: Int,
                ) {
                    darkAreas = ArrayList(areas)
                    darkIntensity = intensity
                }
            }
        dispatcher.addDarkReceiver(receiver)
        onDispose { dispatcher.removeDarkReceiver(receiver) }
    }
    val sampledIntensity =
        if (DarkIconDispatcher.isInAreas(darkAreas, shellBounds)) darkIntensity else 0f
    val moonTint = if (sampledIntensity < 0.15f) Color(0xE64A4A4A) else Color(0xFF141414)

    val width = remember { Animatable(0f) }
    val height = remember { Animatable(0f) }
    val top = remember { Animatable(heightPx / 2) }
    val scale = remember { Animatable(0f) }
    val alpha = remember { Animatable(0f) }
    val blur = remember { Animatable(blurPx) }
    val press = remember { Animatable(1f) }
    val motionMutex = remember { MutatorMutex() }

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

    // Each phase waits for all bounds so interrupted animations cannot overtake one another.
    suspend fun appear() = coroutineScope {
        alpha.snapTo(0f)
        blur.snapTo(blurPx)
        scale.snapTo(0f)
        val target = nextNatural().width.toFloat().coerceAtLeast(1f)
        coroutineScope {
            val motion = tween<Float>(50, easing = CosIsland.Move)
            launch { width.animateTo(holePx, motion) }
            launch { height.animateTo(holePx, motion) }
            top.animateTo((currentHeightPx - holePx) / 2, motion)
        }
        coroutineScope {
            val motion = tween<Float>(200, easing = CosIsland.Offset)
            launch { width.animateTo(middlePx, motion) }
            launch { height.animateTo(currentHeightPx + extraHeightPx, motion) }
            top.animateTo(-extraHeightPx, motion)
        }
        scale.snapTo(middlePx / target)
        coroutineScope {
            val motion = tween<Float>(300, easing = CosIsland.Return)
            launch { width.animateTo(target + extraPx, motion) }
            launch { height.animateTo(currentHeightPx - reduceHeightPx, motion) }
            launch { top.animateTo(reduceHeightPx, motion) }
            launch { alpha.animateTo(1f, motion) }
            launch { blur.animateTo(0f, motion) }
            scale.animateTo((target + extraPx) / target, motion)
        }
        coroutineScope {
            val motion = tween<Float>(400, easing = CosIsland.Move)
            launch { width.animateTo(target, motion) }
            launch { height.animateTo(currentHeightPx, motion) }
            launch { top.animateTo(0f, motion) }
            scale.animateTo(1f, motion)
        }
    }

    suspend fun disappear() = coroutineScope {
        coroutineScope {
            val linear300 = tween<Float>(300, easing = LinearEasing)
            val linear200 = tween<Float>(200, easing = LinearEasing)
            launch { width.animateTo(holePx, linear300) }
            launch { height.animateTo(holePx, linear300) }
            launch { top.animateTo((heightPx - holePx) / 2, linear300) }
            launch { alpha.animateTo(0f, linear200) }
            launch { blur.animateTo(blurPx, linear200) }
            scale.animateTo(0.1f, linear300)
        }
        coroutineScope {
            val motion = tween<Float>(50, easing = LinearEasing)
            launch { width.animateTo(0f, motion) }
            launch { height.animateTo(0f, motion) }
            top.animateTo(heightPx / 2, motion)
        }
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

    val animateShell by rememberUpdatedState<suspend (Boolean, Any) -> Unit> { shown, newKey ->
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
    }
    val settleShell by rememberUpdatedState<suspend () -> Unit> {
        shownKey = currentKey
        shownContent = content
        alpha.snapTo(if (currentVisible) 1f else 0f)
        blur.snapTo(if (currentVisible) 0f else blurPx)
        scale.snapTo(if (currentVisible) 1f else 0f)
        width.snapTo(if (currentVisible) natural.width.toFloat() else 0f)
        height.snapTo(if (currentVisible) heightPx else 0f)
        top.snapTo(if (currentVisible) 0f else heightPx / 2)
    }
    LaunchedEffect(configuration.windowConfiguration.rotation, heightPx, holePx, maxWidthPx, blurPx) {
        try {
            snapshotFlow { currentVisible to currentKey }
                .collectLatest { (shown, newKey) ->
                    // Idle size updates must not cancel a partially revealed shell.
                    motionMutex.mutate(MutatePriority.UserInput) {
                        busy = true
                        try {
                            animateShell(shown, newKey)
                        } finally {
                            busy = false
                        }
                    }
                }
        } finally {
            // Settle only when the effect ends, preserving ordinary transition interruptions.
            withContext(NonCancellable) {
                motionMutex.mutate(MutatePriority.UserInput) { settleShell() }
            }
        }
    }

    // Follow the height scale live while the island is up.
    LaunchedEffect(Unit) {
        snapshotFlow { currentHeightPx to busy }
            .collectLatest { (h, isBusy) ->
                if (!isBusy && currentVisible && height.value > 0f) {
                    motionMutex.mutate {
                        if (!busy && currentVisible) {
                            height.animateTo(h, tween(300, easing = CosIsland.Move))
                        }
                    }
                }
            }
    }

    LaunchedEffect(Unit) {
        snapshotFlow { natural.width to busy }
            .collectLatest { (w, isBusy) ->
                if (!isBusy && !busy && w > 0 && currentVisible) {
                    motionMutex.mutate {
                        if (!busy && currentVisible) coroutineScope {
                            launch {
                                width.animateTo(w.toFloat(), tween(300, easing = CosIsland.Move))
                            }
                            height.animateTo(currentHeightPx, tween(300, easing = CosIsland.Move))
                        }
                    }
                }
            }
    }

    val scope = rememberCoroutineScope()
    val shape = RoundedCornerShape(50)
    Box(
        modifier =
            modifier
                .graphicsLayer { translationY = top.value }
                .onGloballyPositioned {
                    val r = it.boundsInWindow()
                    shellBounds =
                        android.graphics.Rect(
                            r.left.roundToInt(),
                            r.top.roundToInt(),
                            r.right.roundToInt(),
                            r.bottom.roundToInt(),
                        )
                }
                .drawWithContent {
                    drawContent()
                    val w = 4.dp.toPx()
                    val h = 18.dp.toPx()
                    val a = moonAlpha.value
                    val inset = if (currentVisible) 0f else (1f - a) * 6.dp.toPx()
                    translate(inset, (size.height - h) / 2f) {
                        with(leftMoon) {
                            draw(Size(w, h), alpha = a, colorFilter = ColorFilter.tint(moonTint))
                        }
                    }
                    translate(size.width - w - inset, (size.height - h) / 2f) {
                        with(rightMoon) {
                            draw(Size(w, h), alpha = a, colorFilter = ColorFilter.tint(moonTint))
                        }
                    }
                }
                .padding(horizontal = 6.dp)
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
                        val stroke =
                            if (sampledIntensity <= 0.13f) CosIsland.StrokeWidth.toPx() else 0f
                        if (stroke > 0f && size.minDimension > stroke * 2) {
                            drawRoundRect(
                                (strokeColor ?: CosIsland.StrokeColor).copy(alpha = 64f / 255f),
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
