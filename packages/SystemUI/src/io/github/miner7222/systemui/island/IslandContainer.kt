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

import com.android.systemui.statusbar.quickactions.ui.compose.*

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.android.compose.animation.Easings
import com.android.systemui.statusbar.quickactions.popups.shared.DynamicIslandFeatureSettings.observeDynamicIslandColorOSStyle
import com.android.systemui.statusbar.quickactions.popups.shared.DynamicIslandFeatureSettings.readDynamicIslandColorOSStyle
import com.android.systemui.statusbar.quickactions.shared.model.PopupContentModel
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipId
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipModel
import com.android.systemui.statusbar.quickactions.ui.compose.DynamicIslandBaseHeight
import com.android.systemui.statusbar.quickactions.ui.compose.DynamicIslandCutoutEdge
import com.android.systemui.statusbar.quickactions.ui.compose.DynamicIslandCutoutSpec
import com.android.systemui.statusbar.quickactions.ui.compose.DynamicIslandDefaultCenterY
import com.android.systemui.statusbar.quickactions.ui.compose.LocalDynamicIslandWidth
import com.android.systemui.statusbar.quickactions.ui.compose.LocalIslandContentProgress
import com.android.systemui.statusbar.quickactions.ui.compose.LocalIslandContentSpatialMotion
import com.android.systemui.statusbar.quickactions.ui.compose.StatusBarDynamicIslandChip
import com.android.systemui.statusbar.quickactions.ui.compose.StatusBarPopup
import com.android.systemui.statusbar.quickactions.ui.compose.dynamicIslandStartEdgePx
import com.android.systemui.statusbar.quickactions.ui.compose.islandBoundsInScreen
import com.android.systemui.statusbar.quickactions.ui.compose.islandBoundsInWindow
import com.android.systemui.statusbar.quickactions.ui.compose.rememberDynamicIslandColorOSStyle
import com.android.systemui.statusbar.quickactions.ui.compose.rememberDynamicIslandCutoutSpec
import com.android.systemui.statusbar.quickactions.ui.compose.rememberDynamicIslandSideCutoutSpec
import com.android.systemui.statusbar.quickactions.ui.compose.rememberDynamicIslandSizeScale
import com.android.systemui.statusbar.quickactions.ui.compose.rememberDynamicIslandWidth
import com.android.systemui.statusbar.quickactions.ui.compose.resolveDynamicIslandAnchor
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.android.systemui.statusbar.quickactions.ui.compose.CosIslandShell
import com.android.systemui.statusbar.quickactions.ui.compose.DynamicIslandAnchor
import com.android.systemui.statusbar.quickactions.ui.compose.ProvideDynamicIslandPillTurns
import com.android.systemui.statusbar.quickactions.ui.compose.rotatedLayout

/** Phone-only centered dynamic island that pages through active popup chips. */
@Composable
fun IslandContainer(
    chips: List<QuickActionChipModel.PopupChip>,
    onMediaControlPopupVisibilityChanged: (Boolean) -> Unit,
    onIslandBoundsChanged: (android.graphics.Rect) -> Unit = {},
    onIslandActiveChanged: (Boolean) -> Unit = {},
    onIslandExpandedChanged: (Boolean) -> Unit = {},
    appHandleBounds: List<android.graphics.Rect> = emptyList(),
    modifier: Modifier = Modifier,
    sideEdge: DynamicIslandCutoutEdge? = null,
) {
    val view = LocalView.current
    val cutoutSpec =
        if (sideEdge != null) {
            rememberDynamicIslandSideCutoutSpec(sideEdge)
        } else {
            rememberDynamicIslandCutoutSpec()
        }
    // The ColorOS island is drawn on a top camera only; a side-docked pill keeps our own look.
    val cos = sideEdge == null && rememberDynamicIslandColorOSStyle()
    val collapsedWidth = rememberDynamicIslandWidth(cutoutSpec)
    var selectedChipId by remember { mutableStateOf<QuickActionChipId?>(null) }
    var popupAnchorChip by remember { mutableStateOf<QuickActionChipModel.PopupChip?>(null) }
    var popupVisible by remember { mutableStateOf(false) }
    var popupOwnsIsland by remember { mutableStateOf(false) }
    var popupHidesContent by remember { mutableStateOf(false) }
    var knownChipIds by remember { mutableStateOf<List<QuickActionChipId>>(emptyList()) }
    var anchorBounds by remember { mutableStateOf<Rect?>(null) }
    // Where the island was placed before it is shifted onto the camera; NaN until first placed.
    var naturalCenterX by remember { mutableFloatStateOf(Float.NaN) }
    val cameraCenterX = if (sideEdge == null) cutoutSpec.cutoutCenterX else null
    var retainedChip by remember { mutableStateOf<QuickActionChipModel.PopupChip?>(null) }
    val presence = remember { IslandPresenceMotion() }
    val popupContentAlpha = remember { Animatable(1f) }
    val spatialMotionEnabled = rememberIslandSpatialMotionEnabled()
    // The pill returns at full opacity; only its contents reveal after the popup morph.
    // Keep this separate from presence so returning from a popup never reruns the circle sequence.
    LaunchedEffect(popupHidesContent, spatialMotionEnabled) {
        if (popupHidesContent) {
            popupContentAlpha.snapTo(0f)
        } else {
            val durationScale = currentCoroutineContext()[MotionDurationScale]?.scaleFactor ?: 1f
            if (!spatialMotionEnabled || durationScale == 0f) {
                withContext(IslandFadeDurationScale) {
                    popupContentAlpha.animateTo(1f, tween(IslandReducedMotionFadeMillis))
                }
            } else {
                popupContentAlpha.animateTo(
                    1f,
                    tween(IslandContentFadeMillis, easing = Easings.EmphasizedDecelerate),
                )
            }
        }
    }
    val contentProgress = remember {
        { presence.contentAlpha.value * popupContentAlpha.value }
    }
    val layoutDirection = LocalLayoutDirection.current
    val presenceOrigin = remember(cutoutSpec.anchor, layoutDirection) {
        if (cutoutSpec.anchor == DynamicIslandAnchor.START) {
            TransformOrigin(if (layoutDirection == LayoutDirection.Ltr) 0f else 1f, 0.5f)
        } else {
            TransformOrigin.Center
        }
    }

    LaunchedEffect(chips) {
        val currentChipIds = chips.map { it.chipId }
        val newestChipId =
            if (knownChipIds.isEmpty()) {
                null
            } else {
                currentChipIds.lastOrNull { it !in knownChipIds }
            }
        selectedChipId =
            when {
                newestChipId != null -> newestChipId
                chips.any { it.chipId == selectedChipId } -> selectedChipId
                else -> chips.firstOrNull()?.chipId
            }
        knownChipIds = currentChipIds
    }

    val selectedIndex = chips.indexOfFirst { it.chipId == selectedChipId }.coerceAtLeast(0)
    val selectedChip = chips.getOrNull(selectedIndex)
    val shownChip = chips.firstOrNull { it.isPopupShown }

    val stackChips = remember(chips) { islandCardStack(chips) }
    // Chosen when the popup opens and kept until it closes, so dismissing cards never swaps the
    // stack for the single card while it is open.
    var stacked by remember { mutableStateOf(false) }

    LaunchedEffect(shownChip) {
        if (shownChip != null) {
            if (popupAnchorChip == null) {
                stacked =
                    cos &&
                        stackChips.size >= ISLAND_STACK_MIN_CARDS &&
                        stackChips.any { it.chipId == shownChip.chipId }
            }
            selectedChipId = shownChip.chipId
            popupAnchorChip = shownChip
            popupVisible = true
        } else if (popupAnchorChip != null) {
            popupVisible = false
            delay(if (cos) CosCardCloseMillis + 20L else 220L)
            popupAnchorChip = null
            stacked = false
        }
    }

    LaunchedEffect(chips) {
        onMediaControlPopupVisibilityChanged(
            chips.any { it.chipId == QuickActionChipId.MediaControl && it.isPopupShown }
        )
    }

    val hasChip = selectedChip != null || popupAnchorChip != null
    val renderedChip = selectedChip ?: retainedChip
    LaunchedEffect(selectedChip, chips.size) {
        if (selectedChip != null) {
            retainedChip = selectedChip
        }
    }
    // Only an empty/non-empty transition runs the circle sequence. Paging and popup size
    // changes keep their existing animations, and interrupted exits retarget the same springs.
    LaunchedEffect(hasChip, spatialMotionEnabled) {
        presence.animatePresence(hasChip, spatialMotionEnabled)
        if (!hasChip) retainedChip = null
    }
    val isIslandActive = hasChip || retainedChip != null
    LaunchedEffect(isIslandActive) { onIslandActiveChanged(isIslandActive) }

    val isExpanded = shownChip != null || popupOwnsIsland
    LaunchedEffect(isExpanded) { onIslandExpandedChanged(isExpanded) }

    LaunchedEffect(renderedChip) {
        if (renderedChip == null) {
            onIslandBoundsChanged(android.graphics.Rect())
        }
    }

    fun selectChip(newChip: QuickActionChipModel.PopupChip) {
        selectedChipId = newChip.chipId
        if (popupVisible) {
            popupAnchorChip = newChip
            newChip.showPopup()
        }
    }

    fun selectRelative(direction: Int) {
        if (chips.size <= 1) return
        selectChip(chips[(selectedIndex + direction).mod(chips.size)])
    }

    // Popups without rich content cannot be stacked or paged through.
    // The status bar view is shorter than the island, so its height has to be required.
    val islandHeight =
        maxOf(
            DynamicIslandBaseHeight * rememberDynamicIslandSizeScale().second,
            cutoutSpec.minimumHeight,
        )

    Box(
        modifier =
            modifier
                .padding(
                    start =
                        if (sideEdge != null || cutoutSpec.anchor == DynamicIslandAnchor.START) {
                            0.dp
                        } else {
                            8.dp
                        },
                    end = if (sideEdge != null) 0.dp else 8.dp,
                )
                .then(
                    if (cameraCenterX != null) {
                        Modifier.onPlaced { naturalCenterX = it.islandBoundsInWindow().center.x }
                            .graphicsLayer {
                                val natural = naturalCenterX
                                translationX =
                                    if (natural.isNaN()) 0f else cameraCenterX - natural
                            }
                    } else {
                        Modifier.offset(x = cutoutSpec.horizontalOffset)
                    }
                )
                .onGloballyPositioned { coordinates ->
                    if (renderedChip != null) {
                        val b = coordinates.islandBoundsInWindow()
                        onIslandBoundsChanged(
                            android.graphics.Rect(
                                kotlin.math.floor(b.left).toInt(),
                                kotlin.math.floor(b.top).toInt(),
                                kotlin.math.ceil(b.right).toInt(),
                                kotlin.math.ceil(b.bottom).toInt(),
                            )
                        )
                    } else {
                        onIslandBoundsChanged(android.graphics.Rect())
                    }
                }
                // Docked to a side camera: turn the whole pill with its edge. The reported bounds
                // above are of the turned pill.
                .rotatedLayout(sideEdge?.quarterTurns ?: 0)
                .requiredHeightIn(min = islandHeight),
        contentAlignment = Alignment.Center,
    ) {
        if (cos) {
            // The shell outlives the selected chip so it can shrink back into the camera hole.
            val leaving = remember { arrayOfNulls<QuickActionChipModel.PopupChip>(1) }
            var present by remember { mutableStateOf(false) }
            if (selectedChip != null) {
                leaving[0] = selectedChip
                SideEffect { present = true }
            }
            val chip = selectedChip ?: leaving[0]
            if (chip != null && (selectedChip != null || present)) {
                val slopPx = with(LocalDensity.current) { 30.dp.toPx() }
                var dragged by remember { mutableFloatStateOf(0f) }
                val currentChip by rememberUpdatedState(chip)
                val currentSelectRelative by
                    rememberUpdatedState { direction: Int -> selectRelative(direction) }
                CosIslandShell(
                    key = chip.chipId,
                    visible = selectedChip != null && !popupVisible,
                    hole = cutoutSpec.holeSize,
                    moons = chips.size > 1,
                    tall = chip.isCallChip(),
                    onGone = {
                        present = false
                        leaving[0] = null
                    },
                    modifier =
                        Modifier.swipeUpToHide(chip.chipId, slopPx) { currentChip.hide() }
                            .pointerInput(chips.size, chip.chipId) {
                                detectHorizontalDragGestures(
                                    onDragStart = { dragged = 0f },
                                    onDragEnd = {
                                        when {
                                            chips.size <= 1 && abs(dragged) >= slopPx ->
                                                currentChip.hide()
                                            dragged <= -slopPx -> currentSelectRelative(1)
                                            dragged >= slopPx -> currentSelectRelative(-1)
                                        }
                                        dragged = 0f
                                    },
                                    onDragCancel = { dragged = 0f },
                                    onHorizontalDrag = { change, amount ->
                                        dragged += amount
                                        if (chips.size > 1) change.consume()
                                    },
                                )
                            },
                ) {
                    StatusBarDynamicIslandChip(
                        viewModel = chip,
                        pageCount = 1,
                        cutoutSpec = cutoutSpec,
                        onChipBoundsChanged = { bounds -> anchorBounds = bounds },
                        onTap = { if (chip.isPopupShown) chip.hidePopup() else chip.showPopup() },
                        cos = true,
                    )
                }
            }
        } else if (renderedChip != null) Box(
            modifier =
                Modifier.onGloballyPositioned { coordinates ->
                        // Content slides never move the surface used as the popup source.
                        anchorBounds = coordinates.islandBoundsInScreen(view)
                    }
                    .graphicsLayer {
                        alpha = if (popupOwnsIsland) 0f else presence.surfaceAlpha.value
                        scaleX = presence.circleScale.value
                        scaleY = presence.circleScale.value
                        transformOrigin = presenceOrigin
                        // Clip the surface and every child layer to the same circle/pill outline.
                        // Keep measurement unchanged so the popup always receives the full pill bounds.
                        shape = IslandPresenceShape(presence.widthFraction.value, cutoutSpec.anchor)
                        clip = true
                    }
                    .background(Color.Black)
        ) {
            val contentSlidePx = with(LocalDensity.current) { 6.dp.roundToPx() }
            CompositionLocalProvider(
                LocalDynamicIslandWidth provides collapsedWidth,
                LocalIslandContentProgress provides contentProgress,
                LocalIslandContentSpatialMotion provides spatialMotionEnabled,
            ) {
                AnimatedContent(
                    targetState = renderedChip,
                    contentKey = { it.chipId },
                    transitionSpec = {
                        val enter = fadeIn(
                            tween(
                                if (spatialMotionEnabled) IslandContentFadeMillis
                                else IslandReducedMotionFadeMillis,
                                easing = Easings.EmphasizedDecelerate,
                            )
                        )
                        val exit = fadeOut(
                            tween(
                                if (spatialMotionEnabled) IslandContentExitMillis
                                else IslandReducedMotionFadeMillis,
                                easing = Easings.EmphasizedAccelerate,
                            )
                        )
                        if (spatialMotionEnabled) {
                            (enter + slideInVertically(
                                tween(IslandContentFadeMillis, easing = Easings.EmphasizedDecelerate),
                                initialOffsetY = { contentSlidePx },
                            )) togetherWith
                                (exit + slideOutVertically(
                                    tween(IslandContentExitMillis, easing = Easings.EmphasizedAccelerate),
                                    targetOffsetY = { -contentSlidePx },
                                ))
                        } else {
                            enter togetherWith exit
                        }
                    },
                    label = "dynamic_island_chip",
                ) { chip ->
                    val currentChip by rememberUpdatedState(chip)
                    val currentSelectRelative by rememberUpdatedState { direction: Int -> selectRelative(direction) }
                    var horizontalDragPx by remember(chip.chipId, chips.size) { mutableFloatStateOf(0f) }
                    val thresholdPx = with(LocalDensity.current) { 36.dp.toPx() }
                    ProvideDynamicIslandPillTurns(sideEdge?.quarterTurns ?: 0) {
                        StatusBarDynamicIslandChip(
                            viewModel = chip,
                            pageCount = chips.size,
                            modifier = Modifier.swipeUpToHide(chip.chipId, thresholdPx) { currentChip.hide() }
                                .pointerInput(chips.size, chip.chipId) {
                                    detectHorizontalDragGestures(
                                        onDragEnd = {
                                            when {
                                                chips.size <= 1 && abs(horizontalDragPx) >= thresholdPx -> currentChip.hide()
                                                horizontalDragPx <= -thresholdPx -> currentSelectRelative(1)
                                                horizontalDragPx >= thresholdPx -> currentSelectRelative(-1)
                                            }
                                            horizontalDragPx = 0f
                                        },
                                        onDragCancel = { horizontalDragPx = 0f },
                                        onHorizontalDrag = { change, delta ->
                                            horizontalDragPx += delta
                                            change.consume()
                                        },
                                    )
                                },
                            cutoutSpec = cutoutSpec,
                            onTap = {
                                if (selectedChip != null && presence.contentAlpha.value >= 1f) {
                                    if (chip.isPopupShown) chip.hidePopup() else chip.showPopup()
                                }
                            },
                        )
                    }
                }
            }
        }

        popupAnchorChip?.takeIf { cos || selectedChip != null }?.let { anchoredChip ->
            StatusBarPopup(
                viewModel = anchoredChip,
                isVisible = popupVisible,
                chipBoundsInScreen = anchorBounds,
                anchorCenterX = cameraCenterX,
                sideEdge = sideEdge,
                cos = cos,
                stackedChips = if (stacked) stackChips else null,
            )
        }
    }
}
private const val TABLET_MIN_WIDTH_DP = 600
private val AppHandleGap = 8.dp

private class IslandPresenceShape(
    private val widthFraction: Float,
    private val anchor: DynamicIslandAnchor,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val diameter = size.height
        val width =
            diameter + (size.width - diameter).coerceAtLeast(0f) * widthFraction.coerceAtLeast(0f)
        val left =
            if (anchor == DynamicIslandAnchor.START) {
                if (layoutDirection == LayoutDirection.Ltr) 0f else size.width - width
            } else {
                (size.width - width) / 2f
            }
        return Outline.Rounded(
            RoundRect(Rect(left, 0f, left + width, diameter), CornerRadius(diameter / 2f))
        )
    }
}

// M3 Expressive default spatial spring for the pill (~300-350ms to settle). The smaller
// circle uses custom higher stiffness for a ~200ms emergence, with the same slight overshoot.
private val IslandPillSpatialSpring = spring<Float>(dampingRatio = 0.8f, stiffness = 380f)
private val IslandCircleSpatialSpring = spring<Float>(dampingRatio = 0.8f, stiffness = 1600f)
private const val IslandCircleFadeMillis = 200
private const val IslandContentFadeMillis = 230
private const val IslandContentExitMillis = 150
private const val IslandReducedMotionFadeMillis = 100
private const val IslandHiddenCircleScale = 0f

// A short opacity-only fallback remains a fade even when the system animator scale is zero.
private val IslandFadeDurationScale = object : MotionDurationScale {
    override val scaleFactor = 1f
}

private class IslandPresenceMotion {
    val surfaceAlpha = Animatable(0f)
    val circleScale = Animatable(IslandHiddenCircleScale)
    val widthFraction = Animatable(0f)
    val contentAlpha = Animatable(0f)

    suspend fun animatePresence(visible: Boolean, spatialMotionEnabled: Boolean) {
        if (!visible && surfaceAlpha.value == 0f) {
            // A transition can be cancelled before its first visible frame.
            widthFraction.snapTo(0f)
            circleScale.snapTo(IslandHiddenCircleScale)
            contentAlpha.snapTo(0f)
            return
        }
        val durationScale = currentCoroutineContext()[MotionDurationScale]?.scaleFactor ?: 1f
        if (!spatialMotionEnabled || durationScale == 0f) {
            widthFraction.snapTo(1f)
            circleScale.snapTo(1f)
            contentAlpha.snapTo(1f)
            withContext(IslandFadeDurationScale) {
                surfaceAlpha.animateTo(
                    if (visible) 1f else 0f,
                    tween(IslandReducedMotionFadeMillis),
                )
            }
            if (!visible) {
                widthFraction.snapTo(0f)
                circleScale.snapTo(IslandHiddenCircleScale)
                contentAlpha.snapTo(0f)
            }
            return
        }

        if (visible) {
            coroutineScope {
                launch { circleScale.animateTo(1f, IslandCircleSpatialSpring) }
                launch { surfaceAlpha.animateTo(1f, tween(IslandCircleFadeMillis)) }
            }
            widthFraction.animateTo(1f, IslandPillSpatialSpring)
            contentAlpha.animateTo(
                1f,
                tween(IslandContentFadeMillis, easing = Easings.EmphasizedDecelerate),
            )
        } else {
            contentAlpha.animateTo(
                0f,
                tween(IslandContentExitMillis, easing = Easings.EmphasizedAccelerate),
            )
            widthFraction.animateTo(0f, IslandPillSpatialSpring)
            coroutineScope {
                launch {
                    circleScale.animateTo(IslandHiddenCircleScale, IslandCircleSpatialSpring)
                }
                launch { surfaceAlpha.animateTo(0f, tween(IslandCircleFadeMillis)) }
            }
        }
    }
}

@Composable
private fun rememberIslandSpatialMotionEnabled(): Boolean {
    val context = LocalContext.current
    fun readEnabled() =
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) > 0f
    var enabled by remember(context) { mutableStateOf(readEnabled()) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                enabled = readEnabled()
            }
        }
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        enabled = readEnabled()
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return enabled
}

private fun Modifier.swipeUpToHide(key: Any, slopPx: Float, onHide: () -> Unit): Modifier =
    pointerInput(key) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var change = down
            while (change.pressed) {
                change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                val delta = change.position - down.position
                if (-delta.y >= slopPx && -delta.y > abs(delta.x)) {
                    change.consume()
                    onHide()
                    break
                }
            }
        }
    }
