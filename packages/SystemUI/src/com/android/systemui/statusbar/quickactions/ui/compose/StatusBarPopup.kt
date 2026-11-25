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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Region
import android.os.UserHandle
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.CubicBezierEasing
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.android.systemui.Dependency
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.res.R
import com.android.systemui.statusbar.quickactions.alarm.ui.compose.AlarmPopup
import com.android.systemui.statusbar.quickactions.charging.ui.compose.ChargingPopup
import com.android.systemui.statusbar.quickactions.flashlight.ui.compose.FlashlightPopup
import com.android.systemui.statusbar.quickactions.livescore.ui.compose.LiveScorePopup
import com.android.systemui.statusbar.quickactions.media.ui.compose.LyricsCard
import com.android.systemui.statusbar.quickactions.media.ui.compose.MediaControlPopup
import com.android.systemui.statusbar.quickactions.popups.ui.viewmodel.nextPopupChipAfterDismiss
import com.android.systemui.statusbar.quickactions.screenrecord.ui.compose.ScreenRecordPopup
import com.android.systemui.statusbar.quickactions.shared.model.PopupContentModel
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipModel
import com.android.systemui.statusbar.quickactions.stopwatch.ui.compose.StopwatchPopup
import com.android.systemui.statusbar.quickactions.timer.ui.compose.TimerPopup
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

// How long the stack takes to open as a list, and to close again.
private const val ListExpandMillis = 320

// Material expressive default springs. Content staging is specific to the island.
// The default threshold ends the spring 1% short, a visible jump on a travel this long.
private val MorphSpring =
    spring<Float>(dampingRatio = 0.8f, stiffness = 380f, visibilityThreshold = 0.0005f)
private val EffectsSpring = spring<Float>(dampingRatio = 1f, stiffness = 1600f)

internal val CosCardEase = CubicBezierEasing(0.33f, 0f, 0.67f, 1f)
internal val CosCardMove = CubicBezierEasing(0.6f, 0f, 0.1f, 1f)
internal val CosCardGrow = CubicBezierEasing(0.3f, 0f, 0.1f, 1f)

internal data class IslandSwipeThresholds(
    val fadeDistancePx: Float,
    val dismissDistancePx: Float,
    val flingVelocityPx: Float,
    val flingDistancePx: Float,
)

@Composable
internal fun islandSwipeThresholds(): IslandSwipeThresholds {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val slack = ((screenWidth - PopupMaxWidth - PopupPadding * 2) / 2).coerceIn(0.dp, SwipeSlackMax)
    return with(LocalDensity.current) {
        val fade = maxOf(slack + PopupPadding, 64.dp).toPx()
        IslandSwipeThresholds(
            fade,
            fade * 0.6f,
            SwipeFlingVelocity.toPx(),
            SwipeFlingMinDistance.toPx(),
        )
    }
}

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
fun StatusBarPopup(
    viewModel: QuickActionChipModel.PopupChip,
    isVisible: Boolean,
    chipBoundsInScreen: Rect? = null,
    sideEdge: DynamicIslandCutoutEdge? = null,
    cos: Boolean = false,
    chips: List<QuickActionChipModel.PopupChip> = listOf(viewModel),
    onPageSelected: (QuickActionChipModel.PopupChip) -> Unit = {},
    onCardUsed: (QuickActionChipModel.PopupChip) -> Unit = {},
    onMorphActiveChanged: (Boolean) -> Unit = {},
    onHidden: () -> Unit = {},
    appHandleBounds: List<android.graphics.Rect> = emptyList(),
) {
    if (cos) {
        CosIslandPopup(
            viewModel,
            isVisible,
            chipBoundsInScreen,
            chips,
            onCardUsed,
            onPageSelected,
            onMorphActiveChanged,
            onHidden,
            appHandleBounds,
        )
        return
    }
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
    // Both styles stack their cards; the page indicator opens them as a list, one card under
    // another, and closes the list again. A closed or reopened popup starts as the stack.
    var listExpanded by remember { mutableStateOf(false) }
    // 0 is the stack, 1 the list. The cards other than the front one slide out from under it.
    val expansion = remember { Animatable(0f) }
    // The list stays until a collapse has run to its end.
    val listShown by remember {
        derivedStateOf { currentChips.size > 1 && (listExpanded || expansion.value > 0f) }
    }
    // The peeks of the stack go once the list has fully opened.
    val stackPeeksShown by remember {
        derivedStateOf {
            currentChips.size > 1 && !(listExpanded && expansion.value >= 1f)
        }
    }
    val hasIndicator = chips.size > 1
    val canPage = hasIndicator && !listShown
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
    // Top popups clear an app handle. They run up behind the camera and push their content down
    // by the camera clearance.
    val minimumTopPx =
        if (sideEdge == null) {
            if (appHandleBottomPx > 0) appHandleBottomPx + topInsetPx else 0
        } else {
            topInsetPx
        }
    val popupTopPx =
        if (sideEdge == null) anchorChip?.top?.toInt()?.coerceAtLeast(minimumTopPx)
        else null
    // How far the popup's content has to be pushed down so it starts below the camera.
    val cameraClearance =
        if (popupTopPx != null && cameraBottomPx > 0) {
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
                            // Runs up behind the camera; the content is pushed down by the
                            // camera clearance instead.
                            popupTopPx - edgePaddingPx
                        } else {
                            (anchorBounds.top + yOffsetPx).coerceAtLeast(minimumTopPx)
                        }
                    return IntOffset(x, y)
                }
            }
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
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                        WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
                usePlatformDefaultWidth = false,
            ),
    ) {
        val popupView = LocalView.current
        IslandPopupSystemDialogs { currentChip.hidePopup() }
        // Height of the front card of the list, reported by the list when it is measured.
        val listFrontHeightPx = remember { IntArray(1) }
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
            if (chips.size > 1 && !listShown && frontIndex >= 0) chips[(frontIndex + 1).mod(chips.size)]
            else null
        // The list starts with the front card, so it stays where it is; the others follow it in the
        // order the stack shows them.
        val listChips =
            remember(chips, displayedChipId) {
                val front = chips.indexOfFirst { it.chipId == displayedChipId }.coerceAtLeast(0)
                chips.drop(front) + chips.take(front)
            }
        val swipeScope = rememberCoroutineScope()
        val swipeDismissible = viewModel.onDismissed != null
        val swipeThresholds = islandSwipeThresholds()
        val fadeDistancePx = swipeThresholds.fadeDistancePx
        val dismissThresholdPx = swipeThresholds.dismissDistancePx
        val dismissProgress by remember {
            derivedStateOf { popupDismissProgress(swipeX, dismissThresholdPx) }
        }
        val flingVelocityPx = swipeThresholds.flingVelocityPx
        val flingMinDistancePx = swipeThresholds.flingDistancePx
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

        // A card of the list left the screen. If it is the chip the popup is anchored to, select
        // another one first, so the popup never sees an empty list and runs its close animation.
        val dismissListCard: (QuickActionChipModel.PopupChip) -> Unit = { dismissed ->
            if (dismissed.chipId == currentChip.chipId) {
                nextPopupChipAfterDismiss(currentChips, dismissed.chipId)?.let {
                    currentOnPageSelected(it)
                }
            }
            dismissed.onDismissed?.invoke()
        }

        val currentVisible by rememberUpdatedState(isVisible)
        // The dots open the stack as a list and, while it is open, close it again. Their label
        // says what the next tap does; the tap that opens the list keeps the default one.
        val dotsClickLabel =
            if (listExpanded) stringResource(R.string.privacy_dialog_collapse_action) else null
        // Which side of the card the dots are on, as the layout placed them.
        var dotsRightOfCard by remember { mutableStateOf(sideEdge?.edge != DynamicIslandEdge.RIGHT) }
        // The indicator opens the stack as a list and, tapped again, closes it. The front card does
        // not move: the others slide down from under it, and back under it when it closes.
        val toggleList: () -> Unit = {
            if (currentChips.size > 1 && currentVisible && !isAnimating) {
                listExpanded = !listExpanded
                val target = if (listExpanded) 1f else 0f
                swipeScope.launch { expansion.animateTo(target, tween(ListExpandMillis, easing = CosCardGrow)) }
            }
        }

        // The list has nothing to show for a single card, and a new group of cards starts closed.
        LaunchedEffect(chips.size) {
            if (chips.size <= 1) {
                listExpanded = false
                expansion.snapTo(0f)
            }
        }
        // A reopened popup is the stack, also when the close that ended it never reached its end.
        LaunchedEffect(isVisible) {
            if (isVisible) {
                listExpanded = false
                expansion.snapTo(0f)
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

        LaunchedEffect(isVisible, ready) {
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
                    listExpanded = false
                    expansion.snapTo(0f)
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
                // Gone from view, so the list does not turn into the stack in it.
                listExpanded = false
                expansion.snapTo(0f)
                val launch = pendingLaunch
                pendingLaunch = null
                currentOnHidden()
                launch?.invoke()
            }
        }

        val currentAppHandles by rememberUpdatedState(appHandleBounds)
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
                        minOf(
                            touchBounds.bottom.roundToInt(),
                            islandPopupTouchableBottom(popupView),
                        ),
                    )
                    excludeIslandAppHandles(info.touchableRegion, popupView, currentAppHandles)
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
                    if (stackPeeksShown && !revealingStack) {
                        if (chips.size > 2) {
                            PopupPeek(
                                scale = StackPeekScaleFar,
                                offsetY = StackPeekDropFar,
                                alpha = {
                                    visibleContentAlpha() * 0.55f *
                                        (1f - expansion.value.coerceIn(0f, 1f))
                                },
                                progress = {
                                    if (dismissedBySwipe) 0f else progress.value.coerceIn(0f, 1f)
                                },
                            )
                        }
                        PopupPeek(
                            scale = StackPeekScaleNear,
                            offsetY = StackPeekDropNear,
                            alpha = {
                                visibleContentAlpha() * 0.8f *
                                    (1f - expansion.value.coerceIn(0f, 1f))
                            },
                            progress = {
                                if (dismissedBySwipe) 0f else progress.value.coerceIn(0f, 1f)
                            },
                        )
                    }
                    // The window blur of the glass ignores the layer alpha below, so it takes the
                    // same fade, and only that one: the content fades out early in a collapse while
                    // the surface still has to carry the glass down to the island.
                    val containerAlpha = {
                        (if (dismissedBySwipe) 0f else handoffAlpha.value) *
                            (1f - dismissProgress) *
                            (1f - (expansion.value * 4f).coerceIn(0f, 1f))
                    }
                    CompositionLocalProvider(
                        LocalIslandGlassAlpha provides containerAlpha
                    ) {
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
                                    // The front card of an open list has a surface of its own, so the
                                    // container makes way for it as the list opens.
                                    alpha = containerAlpha()
                                },
                        ) {}
                    }
                }
                Box(
                    modifier =
                        Modifier
                            // Keeps taps on blank parts of the card from closing the popup.
                            .pointerInput(Unit) { detectTapGestures {} }
                            .pointerInput(swipeDismissible, chips.size, isVisible, listShown) {
                                // The list handles the swipe of each card itself.
                                if (listShown) return@pointerInput
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
                            .graphicsLayer {
                                alpha =
                                    visibleContentAlpha() *
                                        (1f - dismissProgress)
                                scaleX = 0.92f + 0.08f * visibleContentAlpha()
                                scaleY = scaleX
                                translationX = swipeX
                            }
                            .drawWithContent {
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
                        // An open list gives every card a surface of its own.
                        LocalPopupContainer provides !listShown,
                        // The window blur ignores the layer alpha, so it fades with it.
                        LocalIslandGlassAlpha provides {
                            visibleContentAlpha() * (1f - dismissProgress)
                        },
                        LocalIslandCardBlurState provides
                            IslandCardBlurState(
                                alpha =
                                    (1f - abs(swipeX) / fadeDistancePx).coerceIn(0f, 1f) *
                                        (1f - dismissProgress),
                            ),
                    ) {
                        if (listShown) {
                            IslandCardList(
                                chips = listChips,
                                expansion = { expansion.value },
                                onFrontHeight = { listFrontHeightPx[0] = it },
                                interactive = { isVisible && !isAnimating },
                                fadeDistancePx = fadeDistancePx,
                                dismissThresholdPx = dismissThresholdPx,
                                flingVelocityPx = flingVelocityPx,
                                flingMinDistancePx = flingMinDistancePx,
                                onDismissed = dismissListCard,
                                onCardUsed = onCardUsed,
                            )
                        } else {
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
                }
                Box(
                    modifier = Modifier.graphicsLayer {
                        // With two cards the dots leave together with the dismissed one. They stay
                        // while the stack is open as a list, as the way back to the stack.
                        alpha = visibleContentAlpha() * handoffAlpha.value *
                            (if (chips.size > 2) 1f else 1f - dismissProgress)
                    }
                ) {
                    if (hasIndicator) {
                        PageDots(
                            count = chips.size,
                            index =
                                chips.indexOfFirst { it.chipId == displayedChipId }
                                    .coerceAtLeast(0),
                            color = MaterialTheme.colorScheme.onSurface,
                            cardOnLeft = dotsRightOfCard,
                            onClickLabel = dotsClickLabel,
                            onClick = toggleList,
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
                                IslandPopupContent(revealedChip, openMediaOnTap = true)
                            }
                        }
                    }
                }
            },
        ) { measurables, constraints ->
            val padding = PopupPadding.roundToPx()
            val stackTail = if (canPage) StackTailHeight.roundToPx() else 0
            val indicator = measurables[2].measure(Constraints())
            val indicatorGap = PageIndicatorPadding.roundToPx()
            // The dots stay beside the front card when the stack is open as a list.
            val indicatorSpace = if (hasIndicator) indicator.width + indicatorGap else 0
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
                            (constraints.maxHeight - targetTop.roundToInt() - padding -
                                    if (listShown) ListBottomMargin.roundToPx() else 0)
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
            // The list is taller than its front card, which is the card the popup is built around.
            val frontListHeight = if (listShown) listFrontHeightPx[0] else 0
            val cardHeight =
                (if (frontListHeight > 0) frontListHeight else body.height - stackTail)
                    .coerceAtLeast(1)
            // What the list holds below the front card.
            val listRest = (body.height - cardHeight - stackTail).coerceAtLeast(0)
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
                    indicatorGap,
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
                            indicatorGap,
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
            val rightOfCard = frontIndicatorOffset.x + indicator.width / 2f >= bounds.center.x
            if (dotsRightOfCard != rightOfCard) dotsRightOfCard = rightOfCard
            viewport =
                Rect(
                    bounds.left - bodyX,
                    bounds.top - bodyY,
                    bounds.right - bodyX,
                    // The normal style clips its content to the container; the cards of an open
                    // list are below it, so the clip makes room for them as the popup opens.
                    bounds.bottom - bodyY + stackTail +
                        if (listShown) listRest * fraction.coerceIn(0f, 1f) else 0f,
                )
            // The cards of a list below the front one take touch too.
            val cardsBottom = if (listShown) bodyY + body.height.toFloat() else 0f
            touchBounds =
                if (hasIndicator) {
                    Rect(
                        minOf(bounds.left, indicatorOffset.x.toFloat()),
                        minOf(bounds.top, indicatorOffset.y.toFloat()),
                        maxOf(bounds.right, (indicatorOffset.x + indicator.width).toFloat()),
                        maxOf(
                            bounds.bottom + stackTail,
                            cardsBottom,
                            (indicatorOffset.y + indicator.height).toFloat(),
                        ),
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

// Room kept below a list of cards that fills the screen.
private val ListBottomMargin = 16.dp

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
        // The ColorOS glass of a peek is composited at its full size whatever the layer alpha is, so
        // it follows the peek's fade and comes in only as the front card's glass does.
        CompositionLocalProvider(
            LocalIslandCardBlurState provides
                IslandCardBlurState(
                    alpha =
                        alpha().coerceIn(0f, 1f) *
                            ((progress() - 0.9f) / 0.1f).coerceIn(0f, 1f)
                )
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
                    Box { IslandPopupContent(page, openMediaOnTap = true) }
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

/** Renders the per-feature card for [viewModel]; the single popup and the card stack show these. */
@Composable
internal fun IslandPopupContent(
    viewModel: QuickActionChipModel.PopupChip,
    fillWidth: Boolean = false,
    openMediaOnTap: Boolean = false,
) {
    val cardModifier = if (fillWidth) Modifier.fillMaxWidth() else Modifier
    when (val popupContent = viewModel.popupContent) {
        is PopupContentModel.Media -> {
            val model = popupContent.model
            val useWaveform = popupContent.useWaveform
            val hasLyrics = !model.lyrics.isNullOrBlank() || !model.syncedLyrics.isNullOrBlank()
            // A tap anywhere on the card that a control did not consume opens the media app.
            val openApp = rememberPopupLaunchAction(model.openApp)
            val mediaClickModifier =
                if (openMediaOnTap) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = openApp != null,
                    ) {
                        openApp?.invoke()
                    }
                } else {
                    Modifier
                }
            if (hasLyrics && openMediaOnTap) {
                // One surface holds both cards, so the cards touch and the card keeps an inset
                // below. Outside a popup container, the lyrics get a surface of their own with the
                // media card, so the ColorOS cards read as one card too.
                val mediaWithLyrics: @Composable () -> Unit = {
                    Column(
                        modifier = mediaClickModifier.padding(bottom = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Top,
                    ) {
                        MediaControlPopup(
                            model = model,
                            useWaveform = useWaveform,
                            modifier =
                                if (LocalIslandCosStyle.current) Modifier.fillMaxWidth()
                                else Modifier,
                        )
                        CompositionLocalProvider(LocalPopupCameraClearance provides 0.dp) {
                            LyricsCard(model = model)
                        }
                    }
                }
                if (LocalPopupContainer.current) {
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
            } else if (hasLyrics) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MediaControlPopup(
                        model = model,
                        useWaveform = useWaveform,
                        modifier = cardModifier,
                    )
                    CompositionLocalProvider(LocalPopupCameraClearance provides 0.dp) {
                        LyricsCard(model = model, modifier = cardModifier)
                    }
                }
            } else if (openMediaOnTap) {
                Box(
                    modifier = mediaClickModifier,
                    propagateMinConstraints = LocalIslandCosStyle.current,
                ) {
                    MediaControlPopup(model = model, useWaveform = useWaveform)
                }
            } else {
                MediaControlPopup(model = model, useWaveform = useWaveform)
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
        is PopupContentModel.Alarm -> AlarmPopup(model = popupContent.model)
        is PopupContentModel.Timer -> TimerPopup(model = popupContent.model)
        is PopupContentModel.Charging -> ChargingPopup(model = popupContent.model)
        is PopupContentModel.SystemEvent -> SystemEventPopup(model = popupContent)
        PopupContentModel.None -> Unit
    }
}
internal fun popupPageFraction(swipe: Float, distance: Float, settling: Float?): Float =
    (settling ?: (abs(swipe) / distance.coerceAtLeast(1f))).coerceIn(0f, 1f)

internal fun popupDismissProgress(offset: Float, threshold: Float): Float {
    val distance = (abs(offset) / threshold.coerceAtLeast(1f)).coerceIn(0f, 1f)
    return ((distance - 0.4f) / 0.6f).coerceIn(0f, 1f)
}

internal fun islandPopupTouchableBottom(view: View): Int {
    val metrics = view.context.getSystemService(WindowManager::class.java).maximumWindowMetrics
    val insets =
        metrics.windowInsets.getInsets(
            WindowInsets.Type.systemGestures() or WindowInsets.Type.navigationBars()
        )
    val location = IntArray(2)
    view.getLocationOnScreen(location)
    return (metrics.bounds.bottom - insets.bottom - location[1]).coerceIn(0, view.height)
}

@Composable
internal fun IslandPopupSystemDialogs(onDismiss: () -> Unit) {
    val view = LocalView.current
    val dismiss by rememberUpdatedState(onDismiss)
    DisposableEffect(view) {
        val dispatcher = Dependency.get(BroadcastDispatcher::class.java)
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == Intent.ACTION_CLOSE_SYSTEM_DIALOGS) dismiss()
                }
            }
        dispatcher.registerReceiver(
            receiver,
            IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS),
            executor = view.context.mainExecutor,
            user = UserHandle.ALL,
        )
        onDispose { dispatcher.unregisterReceiver(receiver) }
    }
}

internal fun excludeIslandAppHandles(region: Region, view: View, bounds: List<android.graphics.Rect>) {
    val location = IntArray(2)
    view.getLocationOnScreen(location)
    bounds.forEach { handle ->
        val local = android.graphics.Rect(handle)
        local.offset(-location[0], -location[1])
        region.op(local, Region.Op.DIFFERENCE)
    }
}

@Composable
internal fun IslandPopupGestureInsets(appHandleBounds: List<android.graphics.Rect>) {
    val view = LocalView.current
    val handles by rememberUpdatedState(appHandleBounds)
    DisposableEffect(view) {
        val listener =
            ViewTreeObserver.OnComputeInternalInsetsListener { info ->
                // Home starts below the popup even when the window covers the whole screen.
                info.setTouchableInsets(ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION)
                info.touchableRegion.set(0, 0, view.width, islandPopupTouchableBottom(view))
                excludeIslandAppHandles(info.touchableRegion, view, handles)
            }
        view.viewTreeObserver.addOnComputeInternalInsetsListener(listener)
        onDispose { view.viewTreeObserver.removeOnComputeInternalInsetsListener(listener) }
    }
}
