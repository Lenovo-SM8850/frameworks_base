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

import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
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
import com.android.systemui.statusbar.quickactions.charging.ui.compose.ChargingPopup
import com.android.systemui.statusbar.quickactions.flashlight.ui.compose.FlashlightPopup
import com.android.systemui.statusbar.quickactions.livescore.ui.compose.LiveScorePopup
import com.android.systemui.statusbar.quickactions.media.ui.compose.LyricsCard
import com.android.systemui.statusbar.quickactions.media.ui.compose.MediaControlPopup
import com.android.systemui.statusbar.quickactions.popups.shared.DynamicIslandFeatureSettings.POPUP_COLOR_MODE_BLUR
import com.android.systemui.statusbar.quickactions.popups.ui.viewmodel.nextPopupChipAfterDismiss
import com.android.systemui.statusbar.quickactions.screenrecord.ui.compose.ScreenRecordPopup
import com.android.systemui.statusbar.quickactions.shared.model.PopupContentModel
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipId
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipModel
import com.android.systemui.statusbar.quickactions.stopwatch.ui.compose.StopwatchPopup
import com.android.systemui.statusbar.quickactions.timer.ui.compose.TimerPopup
import com.android.systemui.statusbar.quickactions.ui.compose.CosCardCloseMillis
import com.android.systemui.statusbar.quickactions.ui.compose.CosCardCorner
import com.android.systemui.statusbar.quickactions.ui.compose.CosCardEase
import com.android.systemui.statusbar.quickactions.ui.compose.CosCardGrow
import com.android.systemui.statusbar.quickactions.ui.compose.CosCardMove
import com.android.systemui.statusbar.quickactions.ui.compose.DynamicIslandCutoutEdge
import com.android.systemui.statusbar.quickactions.ui.compose.LocalDynamicIslandWidth
import com.android.systemui.statusbar.quickactions.ui.compose.LocalIslandContentProgress
import com.android.systemui.statusbar.quickactions.ui.compose.LocalIslandContentSpatialMotion
import com.android.systemui.statusbar.quickactions.ui.compose.PageDots
import com.android.systemui.statusbar.quickactions.ui.compose.islandBoundsInScreen
import com.android.systemui.statusbar.quickactions.ui.compose.rememberDynamicIslandSizeScale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.android.systemui.statusbar.quickactions.ui.compose.DynamicIslandEdge
import com.android.systemui.statusbar.quickactions.ui.compose.LocalHidePopup
import com.android.systemui.statusbar.quickactions.ui.compose.LocalIslandGlassAlpha
import com.android.systemui.statusbar.quickactions.ui.compose.LocalLaunchAfterPopupHidden
import com.android.systemui.statusbar.quickactions.ui.compose.LocalPopupCameraClearance
import com.android.systemui.statusbar.quickactions.ui.compose.LocalPopupContainer
import com.android.systemui.statusbar.quickactions.ui.compose.LocalPopupMarqueeEnabled
import com.android.systemui.statusbar.quickactions.ui.compose.PopupSurface
import com.android.systemui.statusbar.quickactions.ui.compose.SystemEventPopup
import com.android.systemui.statusbar.quickactions.ui.compose.rememberPopupColorMode
import com.android.systemui.statusbar.quickactions.ui.compose.rememberPopupLaunchAction

private val SwipeSlackMax = 96.dp
private val PopupMaxWidth = 400.dp
private val PopupPadding = 8.dp
private val PopupTopInset = 4.dp
private val SwipeFlingVelocity = 600.dp
private val SwipeFlingMinDistance = 16.dp
private val PageDragThreshold = 40.dp

// The stack tail holds only the peek edges; the indicator sits beside the front card.
private val StackTailHeight = 18.dp
private val PageIndicatorPadding = 6.dp
private val StackPeekDropFar = 18.dp
private val StackPeekDropNear = 9.dp
private const val StackPeekScaleFar = 0.95f
private const val StackPeekScaleNear = 0.975f
private val StackShape = RoundedCornerShape(32.dp)

// Material expressive default springs. Content staging is specific to the island.
// The default threshold ends the spring 1% short, a visible jump on a travel this long.
private val MorphSpring =
    spring<Float>(dampingRatio = 0.8f, stiffness = 380f, visibilityThreshold = 0.0005f)
private val EffectsSpring = spring<Float>(dampingRatio = 1f, stiffness = 1600f)

/**
 * Reuses the popup window as a shared container: it starts at the island's screen bounds and hands
 * those bounds back only after collapsing. The original chip stays laid out for touch and cutout
 * alignment, but is hidden while this window owns the shape.
 *
 * When [chips] holds more than one popup chip, swipe up for the next card or down for the previous
 * one, wrapping through [chips] and calling [onPageSelected] for the new front chip. Horizontal
 * swipes dismiss the front item when its onDismissed callback is available, or spring back otherwise.
 * Each drag locks to one axis after touch slop; taps on the card's controls keep their own actions.
 */
@Composable
fun IslandPopup(
    viewModel: QuickActionChipModel.PopupChip,
    isVisible: Boolean,
    chipBoundsInScreen: Rect? = null,
    sideEdge: DynamicIslandCutoutEdge? = null,
    cos: Boolean = false,
    chips: List<QuickActionChipModel.PopupChip> = listOf(viewModel),
    onPageSelected: (QuickActionChipModel.PopupChip) -> Unit = {},
    onMorphActiveChanged: (Boolean) -> Unit = {},
    onHidden: () -> Unit = {},
    appHandleBounds: List<android.graphics.Rect> = emptyList(),
) {
    val density = LocalDensity.current
    val collapsedWidth = LocalDynamicIslandWidth.current
    // The pill changes shape under the open card, so its bounds move during the entrance. Anchor
    // one opening to where the island was when it opened; otherwise the card jumps at the end.
    val latchedChip = remember(LocalConfiguration.current.orientation) { arrayOfNulls<Rect>(1) }
    if (!isVisible) {
        latchedChip[0] = null
    } else if (latchedChip[0] == null && chipBoundsInScreen != null) {
        latchedChip[0] = chipBoundsInScreen
    }
    val anchorChip = latchedChip[0] ?: chipBoundsInScreen
    val currentChip by rememberUpdatedState(viewModel)
    val currentChips by rememberUpdatedState(chips)
    val currentOnPageSelected by rememberUpdatedState(onPageSelected)
    val currentOnMorphActiveChanged by rememberUpdatedState(onMorphActiveChanged)
    val currentOnHidden by rememberUpdatedState(onHidden)
    val hidePopupAction = remember { { currentChip.hidePopup() } }
    val canPage = chips.size > 1
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
    val sideGapPx = with(density) { 4.dp.roundToPx() }
    val sideMarginPx = with(density) { 4.dp.roundToPx() }
    val edgePaddingPx = with(density) { PopupEdgePadding.roundToPx() }
    val view = LocalView.current
    // A status bar re-inflated around a style change detaches its island views without destroying
    // the composition; the popup window would survive it.
    DisposableEffect(view) {
        val listener =
            object : android.view.View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: android.view.View) {}

                override fun onViewDetachedFromWindow(v: android.view.View) {
                    currentChip.hidePopup()
                }
            }
        view.addOnAttachStateChangeListener(listener)
        onDispose { view.removeOnAttachStateChangeListener(listener) }
    }
    val cameraBottomPx =
        (view.rootWindowInsets?.displayCutout ?: view.display?.cutout)?.let { cutout ->
            cutout.boundingRectTop.takeUnless { it.isEmpty }?.bottom
        } ?: 0
    val topInsetPx = with(density) { PopupTopInset.roundToPx() }
    val appHandleBottomPx =
        if (sideEdge == null) {
            appHandleBounds.filterNot { it.isEmpty }.maxOfOrNull { it.bottom } ?: 0
        } else {
            0
        }
    // Top popups clear an app handle. A ColorOS card also opens below the camera; the other
    // styles run up behind it and push their content down by the camera clearance.
    val minimumTopPx =
        if (sideEdge == null) {
            val handleTopPx = if (appHandleBottomPx > 0) appHandleBottomPx + topInsetPx else 0
            if (cos) maxOf(handleTopPx, cameraBottomPx) else handleTopPx
        } else {
            topInsetPx
        }
    val popupTopPx =
        if (sideEdge == null) anchorChip?.top?.toInt()?.coerceAtLeast(minimumTopPx)
        else null
    // How far the popup's content has to be pushed down so it starts below the camera. Not for a
    // ColorOS card, which is placed below it.
    val cameraClearance =
        if (!cos && popupTopPx != null && cameraBottomPx > 0) {
            with(density) { (cameraBottomPx - popupTopPx).coerceAtLeast(0).toDp() }
        } else {
            0.dp
        }
    val contentPositionProvider =
        remember(
            yOffsetPx,
            sideEdge?.edge,
            anchorChip,
            sideGapPx,
            sideMarginPx,
            popupTopPx,
            minimumTopPx,
            edgePaddingPx,
            cameraBottomPx,
            cos,
        ) {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset {
                    val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
                    val chip = anchorChip
                    if (sideEdge != null && chip != null) {
                        // The anchor is the turned pill, whose parent-reported bounds are not
                        // reliable, so place against the pill's own on-screen bounds. The popup
                        // window is parented to the full-screen island window, so these are
                        // its coordinates.
                        val x =
                            when (sideEdge.edge) {
                                DynamicIslandEdge.RIGHT ->
                                    chip.left.roundToInt() - sideGapPx - popupContentSize.width
                                else -> chip.right.roundToInt() + sideGapPx
                            }.coerceIn(0, maxX)
                        val maxY =
                            (windowSize.height - popupContentSize.height - sideMarginPx)
                                .coerceAtLeast(sideMarginPx)
                        val y =
                            (chip.center.y.roundToInt() - popupContentSize.height / 2)
                                .coerceIn(sideMarginPx, maxY)
                        return IntOffset(x, y)
                    }
                    val centerX = chip?.center?.x?.roundToInt() ?: anchorBounds.center.x
                    val x = (centerX - popupContentSize.width / 2).coerceIn(0, maxX)
                    val y =
                        if (popupTopPx != null) {
                            // A ColorOS card opens fully below the camera, so its surface and
                            // tint are one piece. The others run up behind it and push their
                            // content down by the camera clearance instead.
                            (popupTopPx - edgePaddingPx).let {
                                if (cos) it.coerceAtLeast(cameraBottomPx) else it
                            }
                        } else {
                            (anchorBounds.top + yOffsetPx).coerceAtLeast(minimumTopPx)
                        }
                    return IntOffset(x, y)
                }
            }
        }
    // Direction the popup slides in from. Top camera: down from the status bar. Side camera:
    // out from the edge the pill sits on.
    val slideInPx = with(density) { 24.dp.toPx() }
    val slideOutPx = with(density) { 16.dp.toPx() }
    val enterOffset =
        when (sideEdge?.edge) {
            null,
            DynamicIslandEdge.TOP -> Offset(0f, -24f)
            DynamicIslandEdge.LEFT -> Offset(-slideInPx, 0f)
            DynamicIslandEdge.RIGHT -> Offset(slideInPx, 0f)
        }
    val exitOffset =
        when (sideEdge?.edge) {
            null,
            DynamicIslandEdge.TOP -> Offset(0f, -16f)
            DynamicIslandEdge.LEFT -> Offset(-slideOutPx, 0f)
            DynamicIslandEdge.RIGHT -> Offset(slideOutPx, 0f)
        }
    var popupAnchorBounds by remember { mutableStateOf(IntRect.Zero) }
    val positionProvider = remember {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                popupAnchorBounds = anchorBounds
                return IntOffset.Zero
            }
        }
    }
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = { currentChip.hidePopup() },
        properties =
            PopupProperties(
                flags =
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                        WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
                usePlatformDefaultWidth = false,
            ),
    ) {
        val popupView = LocalView.current
        var popupBoundsInScreen by remember { mutableStateOf<Rect?>(null) }
        val cardScaleX = remember { Animatable(1f) }
        val cardScaleY = remember { Animatable(1f) }
        // The card stays hidden until its bounds arrive, so it never draws once at full size.
        val cardAlpha = remember { Animatable(if (cos) 0f else 1f) }
        val cardTranslationY = remember { Animatable(0f) }
        val cardShiftX = remember { Animatable(0f) }
        val reveal = remember { Animatable(1f) }
        val veil = remember { Animatable(0f) }
        // The stack behind the setting-on card is not in its layer, so it follows the card's reveal
        // and fade instead of showing before the card or staying through the close.
        val cosStackAlpha = { if (cos) (reveal.value * cardAlpha.value).coerceIn(0f, 1f) else 1f }
        val cosCornerPx = with(density) { CosCardCorner.toPx() }
        val colorMode = rememberPopupColorMode()
        val glassCard = cos && viewModel.popupContent.let {
            it is PopupContentModel.Stopwatch || it is PopupContentModel.Alarm ||
                it is PopupContentModel.ScreenRecord
        }
        val skipMotionOnDismiss = colorMode == POPUP_COLOR_MODE_BLUR || glassCard
        val cosReady = cos && popupBoundsInScreen != null
        val progress = remember { Animatable(0f) }
        val contentAlpha = remember { Animatable(0f) }
        var closingContentAlpha by remember { mutableFloatStateOf(1f) }
        var dismissedBySwipe by remember { mutableStateOf(false) }
        val visibleContentAlpha = {
            if (dismissedBySwipe) 0f else minOf(contentAlpha.value, closingContentAlpha)
        }
        val surfaceProgress = remember { Animatable(0f) }
        val handoffAlpha = remember { Animatable(1f) }
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
        var swipeY by remember { mutableFloatStateOf(0f) }
        val revealingStack by remember { derivedStateOf { swipeX != 0f } }
        var pageDistancePx by remember { mutableFloatStateOf(1f) }
        var settlingPageFraction by remember { mutableStateOf<Float?>(null) }
        var settlingPageDirection by remember { mutableStateOf(1) }
        var swipeJob by remember { mutableStateOf<Job?>(null) }
        var displayedChipId by remember { mutableStateOf(viewModel.chipId) }
        val displayedChip = chips.firstOrNull { it.chipId == displayedChipId } ?: viewModel
        val frontIndex = chips.indexOfFirst { it.chipId == displayedChipId }
        val revealedChip =
            if (chips.size > 1 && frontIndex >= 0) chips[(frontIndex + 1).mod(chips.size)]
            else null
        val swipeScope = rememberCoroutineScope()
        val swipeDismissible = viewModel.onDismissed != null
        val fadeDistancePx = with(density) { maxOf(swipeSlack + PopupPadding, 64.dp).toPx() }
        val dismissThresholdPx = fadeDistancePx * 0.6f
        val dismissProgress by remember {
            derivedStateOf { popupDismissProgress(swipeX, dismissThresholdPx) }
        }
        val flingVelocityPx = with(density) { SwipeFlingVelocity.toPx() }
        val flingMinDistancePx = with(density) { SwipeFlingMinDistance.toPx() }
        val pageThresholdPx = with(density) { PageDragThreshold.toPx() }

        fun settleSwipe(target: Float, dismiss: Boolean) {
            swipeJob?.cancel()
            if (dismiss) isAnimating = true
            swipeJob =
                swipeScope.launch {
                    animate(
                        initialValue = swipeX,
                        targetValue =
                            if (dismiss) sign(target) * maxOf(abs(swipeX), abs(target)) else target,
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
                        val dismissedChip = currentChips.firstOrNull {
                            it.chipId == displayedChipId
                        } ?: currentChip
                        val nextChip = nextPopupChipAfterDismiss(currentChips, dismissedChip.chipId)
                        if (nextChip != null) {
                            displayedChipId = nextChip.chipId
                            swipeX = 0f
                            isAnimating = false
                            // Select the next card before removing this one, so the popup never
                            // sees an empty stack and runs its close animation.
                            currentOnPageSelected(nextChip)
                            dismissedChip.onDismissed?.invoke()
                        } else {
                            dismissedBySwipe = true
                            handoffAlpha.snapTo(0f)
                            dismissedChip.onDismissed?.invoke()
                            dismissedChip.hidePopup()
                        }
                    }
                }
        }

        fun settlePageSwipe(direction: Int = 0) {
            swipeJob?.cancel()
            val pages = currentChips
            val sourceId = displayedChipId
            val index = pages.indexOfFirst { it.chipId == sourceId }
            val nextChip =
                if (direction != 0 && pages.size > 1 && index >= 0) {
                    pages[(index + direction).mod(pages.size)]
                } else {
                    null
                }
            swipeJob =
                swipeScope.launch {
                    settlingPageDirection = if (direction != 0) direction else if (swipeY <= 0f) 1 else -1
                    val initial = (abs(swipeY) / pageDistancePx.coerceAtLeast(1f)).coerceIn(0f, 1f)
                    settlingPageFraction = initial
                    animate(
                        initialValue = initial,
                        targetValue = if (nextChip != null) 1f else 0f,
                        animationSpec = tween(220),
                    ) { value, _ -> settlingPageFraction = value }
                    if (nextChip != null && displayedChipId == sourceId &&
                        currentChips.any { it.chipId == nextChip.chipId }) {
                        displayedChipId = nextChip.chipId
                        currentOnPageSelected(nextChip)
                    }
                    swipeY = 0f
                    settlingPageFraction = null
                }
        }

        // An external selection or removal must not leave the old page mid-drag.
        LaunchedEffect(viewModel.chipId, chips.map { it.chipId }) {
            swipeJob?.cancel()
            settlingPageFraction = null
            displayedChipId = viewModel.chipId
            swipeX = 0f
            swipeY = 0f
        }

        // The setting-on motion does not wait for the first frame, so it must not restart on it.
        LaunchedEffect(isVisible, ready && !cos, cosReady) {
            if (cos) {
                isAnimating = true
                currentOnMorphActiveChanged(false)
                progress.snapTo(1f)
                contentAlpha.snapTo(1f)
                surfaceProgress.snapTo(1f)
                if (isVisible) {
                    swipeJob?.cancel()
                    settlingPageFraction = null
                    dismissedBySwipe = false
                    pendingLaunch = null
                    swipeX = 0f
                    swipeY = 0f
                }

                val chip = chipBoundsInScreen
                val popup = popupBoundsInScreen
                val fromX = if (chip != null && popup != null) chip.center.x - popup.center.x else 0f
                // The card grows from the top of the island and shrinks back into it, so its top
                // follows the island's top and never rises above the island.
                val fromY = if (chip != null && popup != null) chip.top - popup.top else 0f
                if (isVisible && popup != null) {
                    cardScaleX.snapTo(0.4f)
                    cardScaleY.snapTo(0.4f)
                    cardShiftX.snapTo(fromX)
                    cardTranslationY.snapTo(fromY)
                    reveal.snapTo(0f)
                    veil.snapTo(1f)
                    cardAlpha.snapTo(1f)
                    coroutineScope {
                        launch { cardShiftX.animateTo(0f, tween(300, easing = CosCardGrow)) }
                        launch { cardTranslationY.animateTo(0f, tween(300, easing = CosCardGrow)) }
                        launch { reveal.animateTo(1f, tween(300, easing = CosCardGrow)) }
                        launch {
                            veil.animateTo(0f, tween(200, delayMillis = 100, easing = CosCardEase))
                        }
                        for (scale in listOf(cardScaleX, cardScaleY)) {
                            launch {
                                scale.animateTo(1.03f, tween(300, easing = CosCardGrow))
                                scale.animateTo(1f, tween(300, easing = CosCardEase))
                            }
                        }
                    }
                } else if (!isVisible) {
                    if (skipMotionOnDismiss) {
                        // The blur is composited outside this layer and does not follow the
                        // translation or the reveal clip, so it just shrinks and fades in place.
                        coroutineScope {
                            launch { cardScaleX.animateTo(0.01f, tween(130)) }
                            launch { cardScaleY.animateTo(0.01f, tween(130)) }
                            cardAlpha.animateTo(0f, tween(120))
                        }
                    } else {
                        coroutineScope {
                            launch { cardAlpha.animateTo(0f, tween(50, delayMillis = CosCardCloseMillis - 50)) }
                            for (scale in listOf(cardScaleX, cardScaleY)) {
                                launch {
                                    scale.animateTo(0.4f, tween(CosCardCloseMillis, easing = CosCardEase))
                                }
                            }
                            launch {
                                cardShiftX.animateTo(fromX, tween(CosCardCloseMillis, easing = CosCardMove))
                            }
                            launch {
                                cardTranslationY.animateTo(fromY, tween(CosCardCloseMillis, easing = CosCardMove))
                            }
                            launch { reveal.animateTo(0f, tween(CosCardCloseMillis, easing = CosCardEase)) }
                            veil.animateTo(1f, tween(200, easing = CosCardEase))
                        }
                    }
                }
                if (isVisible) { isAnimating = false } else {
                    val launch = pendingLaunch
                    pendingLaunch = null
                    currentOnHidden()
                    launch?.invoke()
                }
                return@LaunchedEffect
            }

            if (!ready) return@LaunchedEffect
            swipeJob?.cancel()
            settlingPageFraction = null
            swipeY = 0f
            isAnimating = true
            if (isVisible) {
                contentAlpha.snapTo(visibleContentAlpha())
                dismissedBySwipe = false
                closingContentAlpha = 1f
                pendingLaunch = null
                currentOnMorphActiveChanged(true)
                handoffAlpha.snapTo(1f)
                withFrameNanos {}
                swipeJob?.cancel()
                swipeX = 0f
                swipeY = 0f
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
                if (dismissedBySwipe) {
                    handoffAlpha.snapTo(0f)
                    contentAlpha.snapTo(0f)
                    progress.snapTo(0f)
                    surfaceProgress.snapTo(0f)
                    currentOnMorphActiveChanged(false)
                    pendingLaunch = null
                    currentOnHidden()
                    return@LaunchedEffect
                }
                val startProgress = progress.value.coerceAtLeast(0.001f)
                val startAlpha = visibleContentAlpha()
                progress.animateTo(0f, MorphSpring) {
                    closingContentAlpha =
                        startAlpha * ((value / startProgress - 0.65f) / 0.35f).coerceIn(0f, 1f)
                }
                contentAlpha.snapTo(0f)
                surfaceProgress.snapTo(0f)
                currentOnMorphActiveChanged(false)
                // Restore the pill under an identical, effect-free source surface before fading
                // this window away. Overlapping antialiased edges must not become a dark rim.
                withFrameNanos {}
                withFrameNanos {}
                handoffAlpha.animateTo(0f, tween(100))
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
                    // The window covers the morph path, but only the card, the peeking stack and
                    // the swipe slack take touch.
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
                Box {
                    if (canPage && !revealingStack) {
                        if (chips.size > 2) {
                            PopupPeek(
                                scale = StackPeekScaleFar,
                                offsetY = StackPeekDropFar,
                                alpha = { visibleContentAlpha() * 0.55f * cosStackAlpha() },
                                progress = {
                                    if (dismissedBySwipe) 0f else progress.value.coerceIn(0f, 1f)
                                },
                            )
                        }
                        PopupPeek(
                            scale = StackPeekScaleNear,
                            offsetY = StackPeekDropNear,
                            alpha = { visibleContentAlpha() * 0.8f * cosStackAlpha() },
                            progress = {
                                if (dismissedBySwipe) 0f else progress.value.coerceIn(0f, 1f)
                            },
                        )
                    }
    if (!cos) {
                    PopupSurface(
                        surfaceKey = displayedChipId,
                        backgroundAlpha = 1f - dismissProgress,
                        shape = RoundedCornerShape(with(density) { radiusPx.toDp() }),
                        cornerRadius = with(density) { radiusPx.toDp() },
                        shadowElevation =
                            if (dismissedBySwipe) 0.dp
                            else 12.dp * progress.value.coerceIn(0f, 1f),
                        containerProgress =
                            if (dismissedBySwipe) 0f
                            else surfaceProgress.value * progress.value.coerceIn(0f, 1f),
                        blurFraction =
                            if (dismissedBySwipe) 0f else progress.value.coerceIn(0f, 1f),
                        modifier =
                            Modifier.fillMaxSize().graphicsLayer {
                                translationX = swipeX
                                alpha =
                                    (if (dismissedBySwipe) 0f else handoffAlpha.value) *
                                        (1f - dismissProgress)
                            },
                    ) {}
} else { Box {} }
                }
                Box(
                    modifier =
                        Modifier
                            // Keeps taps on blank parts of the card from closing the popup.
                            .pointerInput(Unit) { detectTapGestures {} }
                            .pointerInput(swipeDismissible, chips.size, isVisible) {
                                val paging = chips.size > 1
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    if (isAnimating || !isVisible) return@awaitEachGesture
                                    settlingPageFraction?.let {
                                        swipeY = -settlingPageDirection * it * pageDistancePx
                                    }
                                    settlingPageFraction = null
                                    val velocityTracker = VelocityTracker()
                                    var horizontal: Boolean? = null
                                    var overSlop = Offset.Zero
                                    val drag =
                                        awaitTouchSlopOrCancellation(down.id) { change, amount ->
                                            // Choose once; later diagonal motion cannot switch actions.
                                            if (horizontal == null) {
                                                horizontal = abs(amount.x) >= abs(amount.y)
                                            }
                                            if (horizontal == true || paging) {
                                                overSlop = amount
                                                change.consume()
                                            }
                                        } ?: return@awaitEachGesture

                                    val isHorizontal = horizontal == true
                                    swipeJob?.cancel()
                                    if (isHorizontal) {
                                        swipeY = 0f
                                        swipeX += overSlop.x
                                    } else {
                                        swipeX = 0f
                                        swipeY += overSlop.y
                                    }
                                    velocityTracker.addPosition(drag.uptimeMillis, drag.position)
                                    val onDrag: (PointerInputChange) -> Unit = { change ->
                                        velocityTracker.addPosition(
                                            change.uptimeMillis,
                                            change.position,
                                        )
                                        // Only the locked axis drives motion and consumes its drags.
                                        if (isHorizontal) {
                                            swipeX += change.positionChange().x
                                        } else {
                                            swipeY += change.positionChange().y
                                        }
                                        change.consume()
                                    }
                                    val completed =
                                        if (isHorizontal) horizontalDrag(drag.id, onDrag)
                                        else verticalDrag(drag.id, onDrag)
                                    if (!completed) {
                                        if (isHorizontal) settleSwipe(0f, dismiss = false)
                                        else settlePageSwipe()
                                        return@awaitEachGesture
                                    }

                                    val velocity = velocityTracker.calculateVelocity()
                                    val axisVelocity = if (isHorizontal) velocity.x else velocity.y
                                    val distance = if (isHorizontal) swipeX else swipeY
                                    val flung =
                                        abs(axisVelocity) > flingVelocityPx &&
                                            abs(distance) > flingMinDistancePx &&
                                            sign(axisVelocity) == sign(distance)
                                    if (isHorizontal) {
                                        val dismiss =
                                            swipeDismissible &&
                                                (abs(swipeX) >= dismissThresholdPx || flung)
                                        settleSwipe(
                                            if (dismiss) sign(swipeX) * fadeDistancePx else 0f,
                                            dismiss = dismiss,
                                        )
                                    } else {
                                        val direction =
                                            if (abs(swipeY) >= pageThresholdPx || flung) {
                                                if (swipeY < 0f) 1 else -1
                                            } else {
                                                0
                                            }
                                        settlePageSwipe(direction)
                                    }
                                }
                            }
                            // Only the setting-on motion reads the bounds, so the default path skips it.
                            .then(
                                if (cos) {
                                    Modifier.onGloballyPositioned {
                                        popupBoundsInScreen = it.boundsInScreen(popupView)
                                    }
                                } else {
                                    Modifier
                                }
                            )
                            .graphicsLayer {
                                if (cos) {
                                    this.scaleX = cardScaleX.value
                                    this.scaleY = cardScaleY.value
                                    this.alpha = cardAlpha.value * (1f - abs(swipeX) / fadeDistancePx).coerceIn(0f, 1f)
                                    this.translationX = cardShiftX.value + swipeX
                                    this.translationY = cardTranslationY.value
                                    this.transformOrigin = TransformOrigin(0.5f, 0f)
                                    this.clip = reveal.value < 1f
                                    if (this.clip) this.shape = CosRevealShape(reveal.value, cosCornerPx)
                                } else {
                                alpha =
                                    visibleContentAlpha() *
                                        (1f - dismissProgress)
                                scaleX = 0.92f + 0.08f * visibleContentAlpha()
                                scaleY = scaleX
                                translationX = swipeX
                                }
                            }
                            .drawWithContent {
                                if (cos) {
                                    drawContent()
                                    if (veil.value > 0f) {
                                        // The stack tail below the card is transparent; the veil
                                        // covers the card only.
                                        val tail = if (canPage) StackTailHeight.toPx() else 0f
                                        drawRect(
                                            Color.Black,
                                            size = Size(size.width, (size.height - tail).coerceAtLeast(0f)),
                                            alpha = veil.value,
                                        )
                                    }
                                } else {
                                // Clip the final-size controls to the growing container, without
                                // stretching text or artwork along independent axes.
                                val scale = 0.92f + 0.08f * visibleContentAlpha()
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
                            }
                ) {
                    CompositionLocalProvider(
                        LocalHidePopup provides hidePopupAction,
                        LocalPopupCameraClearance provides cameraClearance,
                        LocalLaunchAfterPopupHidden provides { action ->
                            pendingLaunch = action
                            currentChip.hidePopup()
                        },
                        LocalPopupMarqueeEnabled provides
                            (isVisible && !isAnimating && swipeX == 0f && swipeY == 0f),
                        LocalPopupContainer provides !cos,
                        // The window blur ignores the veil and the layer alpha, so it fades with them.
                        LocalIslandGlassAlpha provides {
                            if (cos) cardAlpha.value * (1f - veil.value)
                            else visibleContentAlpha() * (1f - dismissProgress)
                        },
                    ) {
                        PopupPages(
                            viewModel = displayedChip,
                            chips = chips,
                            swipeY = swipeY,
                            settlingFraction = settlingPageFraction,
                            settlingDirection = settlingPageDirection,
                            onPageDistanceChanged = { pageDistancePx = it },
                        )
                    }
                }
                Box(
                    modifier = Modifier.graphicsLayer {
                        // With two cards the dots leave together with the dismissed one.
                        alpha = visibleContentAlpha() * handoffAlpha.value * cosStackAlpha() *
                            (if (chips.size > 2) 1f else 1f - dismissProgress)
                    }
                ) {
                    if (canPage) {
                        PageDots(
                            count = chips.size,
                            index =
                                chips.indexOfFirst { it.chipId == displayedChipId }
                                    .coerceAtLeast(0),
                            color = MaterialTheme.colorScheme.onSurface,
                            onClick = {
                                val index = chips.indexOfFirst { it.chipId == displayedChipId }.coerceAtLeast(0)
                                onPageSelected(chips[(index + 1).mod(chips.size)])
                            },
                        )
                    }
                }
                Box {
                    if (revealingStack && revealedChip != null &&
                        revealedChip.popupContent != PopupContentModel.None
                    ) {
                        PopupSurface(
                            shape = StackShape,
                            modifier = Modifier.graphicsLayer { alpha = dismissProgress }
                                .clearAndSetSemantics {}.pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        awaitPointerEvent(PointerEventPass.Initial).changes.forEach {
                                            it.consume()
                                        }
                                    }
                                }
                            },
                        ) {
                            CompositionLocalProvider(
                                LocalPopupContainer provides true,
                                LocalPopupCameraClearance provides cameraClearance,
                                LocalPopupMarqueeEnabled provides false,
                            ) {
                                PopupBody(revealedChip)
                            }
                        }
                    }
                }
            },
        ) { measurables, constraints ->
            val padding = PopupPadding.roundToPx()
            val stackTail = if (canPage) StackTailHeight.roundToPx() else 0
            val indicator = measurables[2].measure(Constraints())
            val indicatorSpace = if (canPage) indicator.width + PageIndicatorPadding.roundToPx() else 0
            val width = constraints.maxWidth
            val contentWidth =
                minOf(PopupMaxWidth.roundToPx(), width - 2 * padding - 2 * indicatorSpace)
                    .coerceAtLeast(0)
            val chip =
                chipBoundsInScreen
                    ?: Rect(
                        (width - collapsedWidth.toPx()) / 2f,
                        0f,
                        (width + collapsedWidth.toPx()) / 2f,
                        32.dp.toPx(),
                    )
            val targetTop =
                if (sideEdge != null) 0f
                else {
                    (popupTopPx ?: (popupAnchorBounds.top + yOffsetPx))
                        .coerceAtLeast(minimumTopPx)
                        .toFloat()
                }
            val body =
                measurables[1].measure(
                    Constraints(
                        maxWidth = contentWidth,
                        maxHeight =
                            (constraints.maxHeight - targetTop.roundToInt() - padding)
                                .coerceAtLeast(0),
                    )
                )
            val revealedTail = if (chips.size > 2) StackTailHeight.roundToPx() else 0
            val revealedWidth =
                minOf(PopupMaxWidth.roundToPx(), width - 2 * padding -
                    if (chips.size > 2) 2 * indicatorSpace else 0).coerceAtLeast(0)
            val revealed = measurables[3].measure(
                Constraints(
                    maxWidth = revealedWidth,
                    maxHeight = (constraints.maxHeight - targetTop.roundToInt() - padding - revealedTail)
                        .coerceAtLeast(0),
                )
            )
            val revealedPosition = contentPositionProvider.calculatePosition(
                popupAnchorBounds,
                IntSize(width, constraints.maxHeight),
                layoutDirection,
                IntSize(revealed.width + 2 * padding, revealed.height + 2 * padding),
            )
            val cardHeight = (body.height - stackTail).coerceAtLeast(1)
            val targetOffset =
                contentPositionProvider.calculatePosition(
                    anchorBounds = popupAnchorBounds,
                    windowSize = IntSize(width, constraints.maxHeight),
                    layoutDirection = layoutDirection,
                    popupContentSize =
                        IntSize(body.width + 2 * padding, body.height + 2 * padding),
                )
            val x = (targetOffset.x + padding).toFloat()
            val y =
                if (sideEdge == null) {
                    maxOf((targetOffset.y + padding).toFloat(), minimumTopPx.toFloat())
                } else {
                    (targetOffset.y + padding).toFloat()
                }
            val target = Rect(x, y, x + body.width, y + cardHeight)
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
            val bodyY = (bounds.center.y - cardHeight / 2f).roundToInt()
            val frontIndicatorOffset =
                popupPageIndicatorOffset(
                    bounds,
                    IntSize(indicator.width, indicator.height),
                    width,
                    PageIndicatorPadding.roundToPx(),
                    preferStart = sideEdge?.edge == DynamicIslandEdge.RIGHT,
                )
            // While the front card is swiped away, move the dots to the card taking its place.
            val indicatorOffset =
                if (chips.size > 2 && dismissProgress > 0f && revealed.width > 0) {
                    val left = (revealedPosition.x + padding).toFloat()
                    val top = (revealedPosition.y + padding).toFloat()
                    val next =
                        popupPageIndicatorOffset(
                            Rect(left, top, left + revealed.width, top + revealed.height),
                            IntSize(indicator.width, indicator.height),
                            width,
                            PageIndicatorPadding.roundToPx(),
                            preferStart = sideEdge?.edge == DynamicIslandEdge.RIGHT,
                        )
                    IntOffset(
                        (frontIndicatorOffset.x + (next.x - frontIndicatorOffset.x) * dismissProgress)
                            .roundToInt(),
                        (frontIndicatorOffset.y + (next.y - frontIndicatorOffset.y) * dismissProgress)
                            .roundToInt(),
                    )
                } else {
                    frontIndicatorOffset
                }
            viewport =
                Rect(
                    bounds.left - bodyX,
                    bounds.top - bodyY,
                    bounds.right - bodyX,
                    bounds.bottom - bodyY + stackTail,
                )
            touchBounds =
                if (canPage) {
                    Rect(
                        minOf(bounds.left, indicatorOffset.x.toFloat()),
                        minOf(bounds.top, indicatorOffset.y.toFloat()),
                        maxOf(bounds.right, (indicatorOffset.x + indicator.width).toFloat()),
                        maxOf(bounds.bottom + stackTail, (indicatorOffset.y + indicator.height).toFloat()),
                    )
                } else {
                    Rect(bounds.left, bounds.top, bounds.right, bounds.bottom + stackTail)
                }
            val height =
                maxOf(
                    bodyY + body.height + padding,
                    touchBounds.bottom.roundToInt() + padding,
                    chip.bottom.roundToInt(),
                    if (revealed.height > 0) revealedPosition.y + padding + revealed.height else 0,
                )
            layout(width, height) {
                if (revealed.width > 0 && revealed.height > 0) {
                    revealed.place(revealedPosition.x + padding, revealedPosition.y + padding)
                }
                background.place(bounds.left.roundToInt(), bounds.top.roundToInt())
                body.place(bodyX, bodyY)
                indicator.place(
                    indicatorOffset.x,
                    indicatorOffset.y,
                )
            }
        }
    }
}

private val PopupEdgePadding = 8.dp

internal fun popupPageIndicatorOffset(
    card: Rect,
    indicator: IntSize,
    windowWidth: Int,
    gap: Int,
    preferStart: Boolean,
): IntOffset {
    val start = (card.left - gap - indicator.width).roundToInt()
    val end = (card.right + gap).roundToInt()
    val x =
        when {
            preferStart && start >= 0 -> start
            end + indicator.width <= windowWidth -> end
            else -> start
        }.coerceIn(0, (windowWidth - indicator.width).coerceAtLeast(0))
    return IntOffset(x, (card.center.y - indicator.height / 2f).roundToInt().coerceAtLeast(0))
}

@Composable
private fun BoxScope.PopupPeek(
    scale: Float,
    offsetY: Dp,
    alpha: () -> Float,
    progress: () -> Float,
) {
    Box(
        modifier =
            Modifier.matchParentSize().graphicsLayer {
                val currentAlpha = alpha().coerceIn(0f, 1f)
                this.alpha = currentAlpha
                scaleX = scale
                scaleY = scale
                translationY = offsetY.toPx()
            }
    ) {
        PopupSurface(
            shape = StackShape,
            shadowElevation = 4.dp * progress(),
            containerProgress = progress(),
            blurFraction = progress(),
            modifier = Modifier.fillMaxSize(),
        ) {}
    }
}

/** One surface follows the same paging fraction as the cards' size, motion and opacity. */
@Composable
private fun PopupPages(
    viewModel: QuickActionChipModel.PopupChip,
    chips: List<QuickActionChipModel.PopupChip>,
    swipeY: Float,
    settlingFraction: Float?,
    settlingDirection: Int,
    onPageDistanceChanged: (Float) -> Unit,
) {
    val index = chips.indexOfFirst { it.chipId == viewModel.chipId }
    val direction = if (settlingFraction != null) settlingDirection else if (swipeY <= 0f) 1 else -1
    val neighbor =
        if (chips.size > 1 && index >= 0) {
            chips[(index + direction).mod(chips.size)]
        } else {
            null
        }
    val pages = chips.ifEmpty { listOf(viewModel) }
    Layout(
        content = {
            pages.forEach { page ->
                key(page.chipId) {
                    Box { PopupBody(page) }
                }
            }
        }
    ) { measurables, constraints ->
        val tail = if (chips.size > 1) StackTailHeight.roundToPx() else 0
        val pageConstraints = constraints.copy(
            minHeight = 0,
            maxHeight = (constraints.maxHeight - tail).coerceAtLeast(0),
        )
        val cards = measurables.map { it.measure(pageConstraints) }
        val frontIndex = pages.indexOfFirst { it.chipId == viewModel.chipId }.coerceAtLeast(0)
        val front = cards[frontIndex]
        val incoming = neighbor?.let { page ->
            cards.getOrNull(pages.indexOfFirst { it.chipId == page.chipId })
        }
        val distance =
            (maxOf(front.height, incoming?.height ?: front.height) + PopupPadding.roundToPx())
                .coerceAtLeast(1)
        onPageDistanceChanged(distance.toFloat())
        val fraction = popupPageFraction(swipeY, distance.toFloat(), settlingFraction)
        val width =
            (front.width + ((incoming?.width ?: front.width) - front.width) * fraction)
                .roundToInt()
        val height =
            (front.height + ((incoming?.height ?: front.height) - front.height) * fraction)
                .roundToInt()
        val travel = minOf(24.dp.toPx(), height / 8f)
        layout(width, height + tail) {
            incoming?.placeWithLayer((width - incoming.width) / 2, (height - incoming.height) / 2) {
                alpha = fraction
                translationY = direction * travel * (1f - fraction)
                val availableHeight = (height - 2f * abs(translationY)).coerceAtLeast(0f)
                scaleX =
                    minOf(
                        1f,
                        width.toFloat() / incoming.width.coerceAtLeast(1),
                        availableHeight / incoming.height.coerceAtLeast(1),
                    )
                scaleY = scaleX
            }
            front.placeWithLayer((width - front.width) / 2, (height - front.height) / 2) {
                alpha = 1f - fraction
                translationY = -direction * travel * fraction
                val availableHeight = (height - 2f * abs(translationY)).coerceAtLeast(0f)
                scaleX =
                    minOf(
                        1f,
                        width.toFloat() / front.width.coerceAtLeast(1),
                        availableHeight / front.height.coerceAtLeast(1),
                    )
                scaleY = scaleX
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
            // A tap anywhere on the card that a control did not consume opens the media app.
            val openApp = rememberPopupLaunchAction(model.openApp)
            val mediaClickModifier =
                Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = openApp != null,
                ) {
                    openApp?.invoke()
                }
            if (hasLyrics) {
                // One surface holds both cards in a container, so the cards touch and the card keeps
                // an inset below. Without one they are separate surfaces with a gap between.
                // The ColorOS cards have a surface each; give the lyrics the surface of the media
                // card there too, like the morph container does, so they read as one card.
                val sharedSurface = LocalPopupContainer.current
                val mediaWithLyrics: @Composable () -> Unit = {
                    Column(
                        modifier = mediaClickModifier.padding(bottom = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Top,
                    ) {
                        MediaControlPopup(model = model, useWaveform = useWaveform)
                        CompositionLocalProvider(LocalPopupCameraClearance provides 0.dp) {
                            LyricsCard(model = model)
                        }
                    }
                }
                if (sharedSurface) {
                    mediaWithLyrics()
                } else {
                    PopupSurface(
                        shape = RoundedCornerShape(32.dp),
                        modifier = Modifier.widthIn(min = 320.dp, max = 400.dp),
                    ) {
                        CompositionLocalProvider(LocalPopupContainer provides true) {
                            mediaWithLyrics()
                        }
                    }
                }
            } else {
                Box(modifier = mediaClickModifier) {
                    MediaControlPopup(model = model, useWaveform = useWaveform)
                }
            }
        }
        is PopupContentModel.ScreenRecord -> ScreenRecordPopup(model = popupContent.model)
        is PopupContentModel.LiveScore -> LiveScorePopup(model = popupContent.model)
        is PopupContentModel.Flashlight ->
            FlashlightPopup(
                model = popupContent.model,
                hapticsViewModelFactory = popupContent.hapticsViewModelFactory,
            )
        is PopupContentModel.Stopwatch -> StopwatchPopup(model = popupContent.model)
        is PopupContentModel.Timer -> TimerPopup(model = popupContent.model)
        is PopupContentModel.Alarm -> AlarmPopup(model = popupContent.model)
        is PopupContentModel.Charging -> ChargingPopup(model = popupContent.model)
        is PopupContentModel.SystemEvent -> SystemEventPopup(model = popupContent)
        PopupContentModel.None -> Unit
    }
}
/** Reveals the card from a small rounded rectangle in its middle out to its full size. */
private class CosRevealShape(private val fraction: Float, private val cornerPx: Float) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val start = size.width * 0.12f
        val w = start + (size.width - start) * fraction
        val h = start + (size.height - start) * fraction
        val left = (size.width - w) / 2f
        // Kept to the top of the card, where the island is.
        val top = 0f
        val r = minOf(cornerPx, w / 2f, h / 2f)
        return Outline.Rounded(RoundRect(left, top, left + w, top + h, CornerRadius(r)))
    }
}

private fun LayoutCoordinates.boundsInScreen(view: android.view.View): Rect {
    val location = IntArray(2)
    view.getLocationOnScreen(location)
    return boundsInRoot().translate(Offset(location[0].toFloat(), location[1].toFloat()))
}

internal fun popupPageFraction(swipe: Float, distance: Float, settling: Float?): Float =
    (settling ?: (abs(swipe) / distance.coerceAtLeast(1f))).coerceIn(0f, 1f)

internal fun popupDismissProgress(offset: Float, threshold: Float): Float {
    val distance = (abs(offset) / threshold.coerceAtLeast(1f)).coerceIn(0f, 1f)
    return ((distance - 0.4f) / 0.6f).coerceIn(0f, 1f)
}