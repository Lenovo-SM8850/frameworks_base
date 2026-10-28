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

import android.view.ViewTreeObserver
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.android.systemui.res.R
import com.android.systemui.statusbar.quickactions.alarm.ui.compose.AlarmPopup
import com.android.systemui.statusbar.quickactions.flashlight.ui.compose.FlashlightPopup
import com.android.systemui.statusbar.quickactions.livescore.ui.compose.LiveScorePopup
import com.android.systemui.statusbar.quickactions.media.ui.compose.LyricsCard
import com.android.systemui.statusbar.quickactions.media.ui.compose.MediaControlPopup
import com.android.systemui.statusbar.quickactions.screenrecord.ui.compose.ScreenRecordPopup
import com.android.systemui.statusbar.quickactions.shared.model.PopupContentModel
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipModel
import com.android.systemui.statusbar.quickactions.stopwatch.ui.compose.StopwatchPopup
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val SwipeSlackMax = 96.dp
private val PopupMaxWidth = 400.dp
private val PopupPadding = 8.dp
private val SwipeFlingVelocity = 600.dp
private val SwipeFlingMinDistance = 16.dp

// Material expressive default springs. Content staging is specific to the island.
private val MorphSpring = spring<Float>(dampingRatio = 0.8f, stiffness = 380f)
private val EffectsSpring = spring<Float>(dampingRatio = 1f, stiffness = 1600f)
// Matches the morph stiffness so closing content fades out as the surface shrinks.
private val CollapseContentSpring = spring<Float>(dampingRatio = 1f, stiffness = 380f)

/**
 * Reuses the popup window as a shared container: it starts at the island's screen bounds and hands
 * those bounds back only after collapsing. The original chip stays laid out for touch and cutout
 * alignment, but is hidden while this window owns the shape.
 */
@Composable
fun StatusBarPopup(
    viewModel: QuickActionChipModel.PopupChip,
    isVisible: Boolean,
    chipBoundsInScreen: Rect? = null,
    onMorphActiveChanged: (Boolean) -> Unit = {},
    onHidden: () -> Unit = {},
) {
    val density = LocalDensity.current
    val currentChip by rememberUpdatedState(viewModel)
    val currentOnMorphActiveChanged by rememberUpdatedState(onMorphActiveChanged)
    val currentOnHidden by rememberUpdatedState(onHidden)
    val hidePopupAction = remember { { currentChip.hidePopup() } }
    val swipeSlack =
        ((LocalConfiguration.current.screenWidthDp - (PopupMaxWidth + PopupPadding * 2).value) / 2f)
            .coerceIn(0f, SwipeSlackMax.value)
            .dp
    val yOffsetPx =
        with(density) {
            LocalContext.current.resources
                .getDimensionPixelSize(R.dimen.status_bar_height)
                .plus(4.dp.roundToPx())
        }
    val positionProvider = remember {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ) = IntOffset.Zero
        }
    }
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = { currentChip.hidePopup() },
        properties =
            PopupProperties(
                focusable = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
                clippingEnabled = false,
                usePlatformDefaultWidth = false,
            ),
    ) {
        val popupView = LocalView.current
        val progress = remember { Animatable(0f) }
        val contentAlpha = remember { Animatable(0f) }
        val surfaceProgress = remember { Animatable(0f) }
        var pendingLaunch by remember { mutableStateOf<(() -> Unit)?>(null) }
        var isAnimating by remember { mutableStateOf(true) }
        var ready by remember { mutableStateOf(false) }
        var viewport by remember { mutableStateOf(Rect.Zero) }
        var radiusPx by remember { mutableFloatStateOf(0f) }
        var touchBounds by remember { mutableStateOf(Rect.Zero) }

        // Wait for the first source-sized frame before replacing the chip in the status bar window.
        LaunchedEffect(Unit) {
            withFrameNanos {}
            ready = true
        }
        DisposableEffect(Unit) { onDispose { currentOnMorphActiveChanged(false) } }
        var swipeX by remember { mutableFloatStateOf(0f) }
        var swipeJob by remember { mutableStateOf<Job?>(null) }
        val swipeScope = rememberCoroutineScope()
        val swipeDismissible = viewModel.onDismissed != null
        val fadeDistancePx = with(density) { maxOf(swipeSlack + PopupPadding, 64.dp).toPx() }
        val dismissThresholdPx = fadeDistancePx * 0.6f
        val flingVelocityPx = with(density) { SwipeFlingVelocity.toPx() }
        val flingMinDistancePx = with(density) { SwipeFlingMinDistance.toPx() }

        fun settleSwipe(target: Float, dismiss: Boolean) {
            swipeJob?.cancel()
            swipeJob =
                swipeScope.launch {
                    animate(
                        initialValue = swipeX,
                        targetValue = target,
                        animationSpec =
                            if (dismiss) {
                                tween(160)
                            } else {
                                spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)
                            },
                    ) { value, _ ->
                        swipeX = value
                    }
                    if (dismiss) {
                        currentChip.onDismissed?.invoke()
                        currentChip.hidePopup()
                    }
                }
        }

        LaunchedEffect(isVisible, ready) {
            if (!ready) return@LaunchedEffect
            isAnimating = true
            if (isVisible) {
                pendingLaunch = null
                currentOnMorphActiveChanged(true)
                withFrameNanos {}
                swipeJob?.cancel()
                swipeX = 0f
                coroutineScope {
                    launch { progress.animateTo(1f, MorphSpring) }
                    launch {
                        snapshotFlow { progress.value }.first { it >= 0.25f }
                        surfaceProgress.animateTo(1f, EffectsSpring)
                    }
                    launch {
                        snapshotFlow { progress.value }.first { it >= 0.65f }
                        contentAlpha.animateTo(1f, EffectsSpring)
                    }
                }
                isAnimating = false
            } else {
                // Fade and scale the controls with the shrinking container so both collapse
                // together; the shrinking shape clips whatever is still visible.
                coroutineScope {
                    launch { contentAlpha.animateTo(0f, CollapseContentSpring) }
                    launch { surfaceProgress.animateTo(0f, EffectsSpring) }
                    launch { progress.animateTo(0f, MorphSpring) }
                }
                currentOnMorphActiveChanged(false)
                // Let the original chip draw under the source-sized popup before removing it.
                withFrameNanos {}
                withFrameNanos {}
                val launch = pendingLaunch
                pendingLaunch = null
                currentOnHidden()
                launch?.invoke()
            }
        }

        DisposableEffect(popupView) {
            val listener =
                ViewTreeObserver.OnComputeInternalInsetsListener { info ->
                    info.setTouchableInsets(
                        ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION
                    )
                    // The window covers the morph path, but only the card and swipe slack take
                    // touch.
                    info.touchableRegion.set(
                        (touchBounds.left - with(density) { swipeSlack.toPx() }).roundToInt(),
                        touchBounds.top.roundToInt(),
                        (touchBounds.right + with(density) { swipeSlack.toPx() }).roundToInt(),
                        touchBounds.bottom.roundToInt(),
                    )
                }
            popupView.viewTreeObserver.addOnComputeInternalInsetsListener(listener)
            onDispose { popupView.viewTreeObserver.removeOnComputeInternalInsetsListener(listener) }
        }

        Layout(
            modifier =
                Modifier.fillMaxWidth().pointerInput(Unit) {
                    detectTapGestures { hidePopupAction() }
                },
            content = {
                PopupSurface(
                    shape = RoundedCornerShape(with(density) { radiusPx.toDp() }),
                    cornerRadius = with(density) { radiusPx.toDp() },
                    shadowElevation = 12.dp * progress.value.coerceIn(0f, 1f),
                    containerProgress = surfaceProgress.value,
                    modifier =
                        Modifier.graphicsLayer {
                            translationX = swipeX
                            alpha = (1f - abs(swipeX) / fadeDistancePx).coerceIn(0f, 1f)
                        },
                ) {}
                Box(
                    modifier =
                        Modifier
                            // Keeps taps on blank parts of the card from closing the popup.
                            .pointerInput(Unit) { detectTapGestures {} }
                            .pointerInput(swipeDismissible) {
                                if (!swipeDismissible) return@pointerInput
                                val velocityTracker = VelocityTracker()
                                detectHorizontalDragGestures(
                                    onDragStart = {
                                        swipeJob?.cancel()
                                        velocityTracker.resetTracking()
                                    },
                                    onDragEnd = {
                                        val velocity = velocityTracker.calculateVelocity().x
                                        val flung =
                                            abs(velocity) > flingVelocityPx &&
                                                abs(swipeX) > flingMinDistancePx &&
                                                sign(velocity) == sign(swipeX)
                                        if (abs(swipeX) >= dismissThresholdPx || flung) {
                                            settleSwipe(
                                                sign(swipeX) * fadeDistancePx,
                                                dismiss = true,
                                            )
                                        } else {
                                            settleSwipe(0f, dismiss = false)
                                        }
                                    },
                                    onDragCancel = { settleSwipe(0f, dismiss = false) },
                                ) { change, dragAmount ->
                                    velocityTracker.addPosition(
                                        change.uptimeMillis,
                                        change.position,
                                    )
                                    change.consume()
                                    swipeX += dragAmount
                                }
                            }
                            .graphicsLayer {
                                alpha =
                                    contentAlpha.value *
                                        (1f - abs(swipeX) / fadeDistancePx).coerceIn(0f, 1f)
                                scaleX = 0.92f + 0.08f * contentAlpha.value
                                scaleY = scaleX
                                translationX = swipeX
                            }
                            .drawWithContent {
                                // Clip the final-size controls to the growing container, without
                                // stretching text or artwork along independent axes.
                                val scale = 0.92f + 0.08f * contentAlpha.value
                                val clip =
                                    Rect(
                                        (viewport.left - size.width / 2f) / scale + size.width / 2f,
                                        (viewport.top - size.height / 2f) / scale +
                                            size.height / 2f,
                                        (viewport.right - size.width / 2f) / scale +
                                            size.width / 2f,
                                        (viewport.bottom - size.height / 2f) / scale +
                                            size.height / 2f,
                                    )
                                val path =
                                    Path().apply {
                                        addRoundRect(
                                            RoundRect(clip, CornerRadius(radiusPx / scale))
                                        )
                                    }
                                clipPath(path) { this@drawWithContent.drawContent() }
                            }
                ) {
                    CompositionLocalProvider(
                        LocalHidePopup provides hidePopupAction,
                        LocalLaunchAfterPopupHidden provides { action ->
                            pendingLaunch = action
                            currentChip.hidePopup()
                        },
                        LocalPopupMarqueeEnabled provides (isVisible && !isAnimating && swipeX == 0f),
                        LocalPopupContainer provides true,
                    ) {
                        PopupBody(viewModel)
                    }
                }
            },
        ) { measurables, constraints ->
            val padding = PopupPadding.roundToPx()
            val width = constraints.maxWidth
            val contentWidth = minOf(PopupMaxWidth.roundToPx(), width - 2 * padding)
            val chip =
                chipBoundsInScreen
                    ?: Rect(
                        (width - 108.dp.toPx()) / 2f,
                        0f,
                        (width + 108.dp.toPx()) / 2f,
                        32.dp.toPx(),
                    )
            val targetTop = chip.top + yOffsetPx + padding
            val body =
                measurables[1].measure(
                    Constraints(
                        maxWidth = contentWidth,
                        maxHeight =
                            (constraints.maxHeight - targetTop.roundToInt() - padding)
                                .coerceAtLeast(0),
                    )
                )
            val x =
                (chip.center.x - body.width / 2f).coerceIn(
                    padding.toFloat(),
                    (width - padding - body.width).toFloat(),
                )
            val target = Rect(x, targetTop, x + body.width, targetTop + body.height)
            // Keep the spring's small spatial overshoot, but never collapse below the source size.
            val fraction = progress.value.coerceAtLeast(0f)
            val bounds = androidx.compose.ui.geometry.lerp(chip, target, fraction)
            val corner = chip.height / 2f + (32.dp.toPx() - chip.height / 2f) * fraction
            radiusPx = corner.coerceAtLeast(0f)
            val background =
                measurables[0].measure(
                    Constraints.fixed(
                        bounds.width.roundToInt().coerceAtLeast(1),
                        bounds.height.roundToInt().coerceAtLeast(1),
                    )
                )
            val bodyX = (bounds.center.x - body.width / 2f).roundToInt()
            val bodyY = (bounds.center.y - body.height / 2f).roundToInt()
            viewport = bounds.translate(-bodyX.toFloat(), -bodyY.toFloat())
            touchBounds = bounds
            layout(width, maxOf(target.bottom.roundToInt() + padding, chip.bottom.roundToInt())) {
                background.place(bounds.left.roundToInt(), bounds.top.roundToInt())
                body.place(bodyX, bodyY)
            }
        }
    }
}

@Composable
private fun PopupBody(viewModel: QuickActionChipModel.PopupChip) {
    when (val popupContent = viewModel.popupContent) {
        is PopupContentModel.Media -> {
            val model = popupContent.model
            val useWaveform = popupContent.useWaveform
            val hasLyrics = !model.lyrics.isNullOrBlank() || !model.syncedLyrics.isNullOrBlank()
            if (hasLyrics) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MediaControlPopup(model = model, useWaveform = useWaveform)
                    LyricsCard(model = model)
                }
            } else {
                MediaControlPopup(model = model, useWaveform = useWaveform)
            }
        }
        is PopupContentModel.ScreenRecord -> ScreenRecordPopup(model = popupContent.model)
        is PopupContentModel.LiveScore -> LiveScorePopup(model = popupContent.model)
        is PopupContentModel.Flashlight -> FlashlightPopup(model = popupContent.model)
        is PopupContentModel.Stopwatch -> StopwatchPopup(model = popupContent.model)
        is PopupContentModel.Alarm -> AlarmPopup(model = popupContent.model)
        PopupContentModel.None -> Unit
    }
}
