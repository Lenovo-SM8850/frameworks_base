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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
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
import com.android.systemui.statusbar.quickactions.popups.shared.DynamicIslandFeatureSettings.POPUP_COLOR_MODE_BLUR
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/** Room beside the popup card that lets a swiped card slide before the window clips it. */
private val SwipeSlackMax = 96.dp
private val PopupMaxWidth = 400.dp
private val PopupPadding = 8.dp
private val SwipeFlingVelocity = 600.dp
private val SwipeFlingMinDistance = 16.dp

/**
 * Displays a popup in the status bar area. The offset is calculated to draw the popup below the
 * status bar. When [chipBoundsInScreen] is provided, the popup grows out of the chip's on-screen
 * position and aspect ratio with an independent-axis squish animation, rather than a generic
 * uniform scale-in. Swiping the card sideways dismisses the item it shows, see
 * [QuickActionChipModel.PopupChip.onDismissed].
 */
@Composable
fun StatusBarPopup(
    viewModel: QuickActionChipModel.PopupChip,
    isVisible: Boolean,
    chipBoundsInScreen: Rect? = null,
) {
    val density = Density(LocalContext.current)
    val currentChip by rememberUpdatedState(viewModel)
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
    val positionProvider =
        remember(yOffsetPx) {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset {
                    val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
                    val x =
                        (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(0, maxX)
                    return IntOffset(x, anchorBounds.top + yOffsetPx)
                }
            }
        }
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = { viewModel.hidePopup() },
        properties =
            PopupProperties(
                focusable = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
            ),
    ) {
        val popupView = LocalView.current
        var popupBoundsInScreen by remember { mutableStateOf<Rect?>(null) }

        val transformOrigin by remember {
            derivedStateOf {
                val chip = chipBoundsInScreen
                val popup = popupBoundsInScreen
                if (chip == null || popup == null || popup.width <= 0f) {
                    TransformOrigin(0.5f, 0f)
                } else {
                    val pivotX =
                        ((chip.center.x - popup.left) / popup.width).coerceIn(0.05f, 0.95f)
                    val pivotY =
                        if (popup.height > 0f) {
                            ((chip.center.y - popup.top) / popup.height).coerceIn(0f, 0.3f)
                        } else {
                            0f
                        }
                    TransformOrigin(pivotX, pivotY)
                }
            }
        }

        val initialScaleFromChip by remember {
            derivedStateOf {
                val chip = chipBoundsInScreen
                val popup = popupBoundsInScreen
                if (chip == null || popup == null || popup.width <= 0f || popup.height <= 0f) {
                    Offset(0.4f, 0.4f)
                } else {
                    Offset(
                        x = (chip.width / popup.width).coerceIn(0.2f, 1f),
                        y = (chip.height / popup.height).coerceIn(0.15f, 1f),
                    )
                }
            }
        }

        val scaleX = remember { Animatable(initialScaleFromChip.x) }
        val scaleY = remember { Animatable(initialScaleFromChip.y) }
        val alpha = remember { Animatable(0f) }
        val translationY = remember { Animatable(-24f) }
        val colorMode = rememberPopupColorMode()
        val skipMotionOnDismiss = colorMode == POPUP_COLOR_MODE_BLUR

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
                                spring(
                                    dampingRatio = 0.75f,
                                    stiffness = Spring.StiffnessMediumLow,
                                )
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

        LaunchedEffect(isVisible) {
            if (isVisible) {
                swipeJob?.cancel()
                swipeX = 0f
                scaleX.snapTo(initialScaleFromChip.x)
                scaleY.snapTo(initialScaleFromChip.y)
                alpha.snapTo(0f)
                translationY.snapTo(-24f)
                coroutineScope {
                    launch {
                        scaleX.animateTo(
                            targetValue = 1f,
                            animationSpec =
                                spring(
                                    dampingRatio = 0.6f,
                                    stiffness = Spring.StiffnessLow,
                                ),
                        )
                    }
                    launch {
                        scaleY.animateTo(
                            targetValue = 1f,
                            animationSpec =
                                spring(
                                    dampingRatio = 0.65f,
                                    stiffness = Spring.StiffnessLow,
                                ),
                        )
                    }
                    launch {
                        translationY.animateTo(
                            targetValue = 0f,
                            animationSpec =
                                spring(
                                    dampingRatio = 0.7f,
                                    stiffness = Spring.StiffnessMediumLow,
                                ),
                        )
                    }
                    launch { alpha.animateTo(1f, animationSpec = tween(180)) }
                }
            } else {
                if (skipMotionOnDismiss) {
                    coroutineScope {
                        launch {
                            scaleX.animateTo(
                                targetValue = 0.01f,
                                animationSpec = tween(130),
                            )
                        }
                        launch {
                            scaleY.animateTo(
                                targetValue = 0.01f,
                                animationSpec = tween(130),
                            )
                        }
                        launch { alpha.animateTo(0f, animationSpec = tween(120)) }
                    }
                } else {
                coroutineScope {
                    launch {
                        scaleX.animateTo(
                            targetValue = initialScaleFromChip.x,
                            animationSpec =
                                spring(
                                    dampingRatio = 0.8f,
                                    stiffness = Spring.StiffnessMediumLow,
                                ),
                        )
                    }
                    launch {
                        scaleY.animateTo(
                            targetValue = initialScaleFromChip.y,
                            animationSpec =
                                spring(
                                    dampingRatio = 0.8f,
                                    stiffness = Spring.StiffnessMediumLow,
                                ),
                        )
                    }
                    launch {
                        translationY.animateTo(
                            targetValue = -16f,
                            animationSpec = spring(stiffness = Spring.StiffnessMedium),
                        )
                    }
                    launch { alpha.animateTo(0f, animationSpec = tween(160)) }
                    }
                }
            }
        }

        DisposableEffect(popupView) {
            val listener =
                ViewTreeObserver.OnComputeInternalInsetsListener { info ->
                    info.setTouchableInsets(
                        ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_FRAME
                    )
                }
            popupView.viewTreeObserver.addOnComputeInternalInsetsListener(listener)
            onDispose {
                popupView.viewTreeObserver.removeOnComputeInternalInsetsListener(listener)
            }
        }

        // A tap on the room beside the card counts as a tap outside of it.
        Box(modifier = Modifier.pointerInput(Unit) { detectTapGestures { hidePopupAction() } }) {
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(animationSpec = tween(60)),
                exit = fadeOut(animationSpec = tween(160)),
            ) {
                Box(
                    modifier =
                        Modifier.padding(
                                horizontal = PopupPadding + swipeSlack,
                                vertical = PopupPadding,
                            )
                            .wrapContentSize()
                            .onGloballyPositioned { coordinates ->
                                popupBoundsInScreen = coordinates.boundsInScreen(popupView)
                            }
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
                                this.scaleX = scaleX.value
                                this.scaleY = scaleY.value
                                this.alpha =
                                    alpha.value *
                                        (1f - abs(swipeX) / fadeDistancePx).coerceIn(0f, 1f)
                                this.translationX = swipeX
                                this.translationY = translationY.value
                                this.transformOrigin = transformOrigin
                            }
                ) {
                    CompositionLocalProvider(LocalHidePopup provides hidePopupAction) {
                        PopupBody(viewModel)
                    }
                }
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
            val hasLyrics =
                !model.lyrics.isNullOrBlank() || !model.syncedLyrics.isNullOrBlank()
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
    }}

private fun LayoutCoordinates.boundsInScreen(view: android.view.View): Rect {
    val location = IntArray(2)
    view.getLocationOnScreen(location)
    return boundsInRoot().translate(Offset(location[0].toFloat(), location[1].toFloat()))
}
