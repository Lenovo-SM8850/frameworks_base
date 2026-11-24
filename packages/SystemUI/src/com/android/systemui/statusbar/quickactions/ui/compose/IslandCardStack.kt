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

import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import com.android.axion.blur.AxBlurRegionCorners
import com.android.axion.blur.AxBlurRegionFrame
import com.android.axion.blur.AxBlurSurface
import com.android.axion.blur.CornerRadii
import com.android.axion.blur.rememberAxBlurRegion
import com.android.compose.theme.PlatformTheme
import com.android.internal.graphics.drawable.BackgroundBlurDrawable
import com.android.systemui.common.shared.model.Icon as UiIcon
import com.android.systemui.qs.shared.style.liquidGlassCosStyle
import com.android.systemui.qs.shared.style.liquidGlassEnabled
import com.android.systemui.qs.shared.style.liquidGlassRim
import com.android.systemui.res.R
import com.android.systemui.statusbar.quickactions.popups.shared.DynamicIslandFeatureSettings.POPUP_COLOR_MODE_BLUR
import com.android.systemui.statusbar.quickactions.popups.shared.DynamicIslandFeatureSettings.POPUP_COLOR_MODE_SOLID_BLACK
import com.android.systemui.statusbar.quickactions.shared.model.PopupContentModel
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipId
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipModel
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The island opens every live activity as a stack once at least this many are running. */
internal const val ISLAND_STACK_MIN_CARDS = 2

/**
 * Live activities shown as stacked cards: newest kinds on top, the player last, as on ColorOS.
 * Transient system events keep the single card popup; the live updates of apps join the stack.
 */
internal fun islandCardStack(
    chips: List<QuickActionChipModel.PopupChip>
): List<QuickActionChipModel.PopupChip> {
    val live =
        chips.filter { chip ->
            val id = chip.chipId
            val transientEvent =
                id is QuickActionChipId.SystemEvent && !id.eventId.startsWith("live:")
            !transientEvent && chip.popupContent != PopupContentModel.None
        }
    val (media, others) = live.partition { it.chipId == QuickActionChipId.MediaControl }
    return others.asReversed() + media
}

/**
 * Every live activity as its own card under the island. The top card comes out of the island with
 * the popup; the others slide out from under it one after another, and slide back on close. Swipe
 * a card sideways to put that activity away.
 */
@Composable
internal fun IslandCardStack(
    chips: List<QuickActionChipModel.PopupChip>,
    isVisible: Boolean,
    onTopCardHeight: (Float) -> Unit,
) {
    val config = LocalConfiguration.current
    val cardWidth = min(config.screenWidthDp.dp - StackSideMargin * 2, StackMaxWidth)
    val maxHeight = max(config.screenHeightDp.dp - StackVerticalClearance, StackMinHeight)

    val liveIds = chips.map { it.chipId }
    // Last model of every card, so a card that just ended can still animate away.
    val models = remember { HashMap<QuickActionChipId, QuickActionChipModel.PopupChip>() }
    chips.forEach { models[it.chipId] = it }
    val openedWith = remember { liveIds.toSet() }
    val rendered = remember { mutableStateListOf<QuickActionChipId>().apply { addAll(liveIds) } }
    LaunchedEffect(liveIds) {
        val merged = liveIds.toMutableList()
        rendered.forEachIndexed { index, id ->
            if (id !in merged) merged.add(index.coerceAtMost(merged.size), id)
        }
        if (merged != rendered.toList()) {
            rendered.clear()
            rendered.addAll(merged)
        }
    }
    val currentChips by rememberUpdatedState(chips)

    val scroll = rememberScrollState()
    // Every open starts at the top card, whatever an earlier open was scrolled to.
    LaunchedEffect(isVisible) { if (isVisible) scroll.scrollTo(0) }
    val glass = islandGlassCards()
    Column(
        modifier =
            Modifier.width(cardWidth)
                .heightIn(max = maxHeight)
                .then(if (glass) Modifier else Modifier.fadeScrolledEdge(scroll))
                .verticalScroll(scroll)
    ) {
        rendered.forEachIndexed { index, id ->
            key(id) {
                val chip = models[id]
                if (chip != null) {
                    StackedCard(
                        chip = chip,
                        index = index,
                        entering = id !in openedWith,
                        present = id in liveIds,
                        stackVisible = isVisible,
                        onTopCardHeight = if (index == 0) onTopCardHeight else null,
                        onRemoved = { rendered.remove(id) },
                        onDismiss = {
                            // Hiding the chip that holds the popup open would close the stack, so
                            // hand the popup to a card that stays first.
                            if (chip.isPopupShown) {
                                currentChips.firstOrNull { it.chipId != id }?.showPopup?.invoke()
                            }
                            chip.hide()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun StackedCard(
    chip: QuickActionChipModel.PopupChip,
    index: Int,
    entering: Boolean,
    present: Boolean,
    stackVisible: Boolean,
    onTopCardHeight: ((Float) -> Unit)?,
    onRemoved: () -> Unit,
    onDismiss: () -> Unit,
) {
    val visibleState = remember { MutableTransitionState(!entering) }
    visibleState.targetState = present
    LaunchedEffect(visibleState.isIdle, visibleState.currentState, present) {
        if (visibleState.isIdle && !visibleState.currentState && !present) onRemoved()
    }

    // 0 = tucked under the top card, 1 = in place. The top card rides the popup's own reveal.
    val open = remember { Animatable(if (entering || index == 0) 1f else 0f) }
    LaunchedEffect(stackVisible) {
        if (stackVisible) {
            delay(StackStaggerBaseMs + index * StackStaggerMs)
            open.animateTo(1f, tween(StackCardInMs, easing = CosCardGrow))
        } else if (index > 0) {
            open.animateTo(0f, tween(StackCardOutMs, easing = CosCardEase))
        }
    }

    var topInStack by remember { mutableFloatStateOf(0f) }
    val swipe = remember { Animatable(0f) }
    var cardWidth by remember { mutableFloatStateOf(0f) }
    // Shared by the layer alpha and the window blur behind a glass card, which ignores the layer.
    val cardAlpha = remember {
        {
            val swipeFade = if (cardWidth > 0f) 1f - abs(swipe.value) / cardWidth else 1f
            (open.value * 1.6f).coerceAtMost(1f) * swipeFade.coerceIn(0f, 1f)
        }
    }
    val parentGlassAlpha = LocalIslandGlassAlpha.current
    val scope = rememberCoroutineScope()
    val currentOnDismiss by rememberUpdatedState(onDismiss)

    AnimatedVisibility(
        visibleState = visibleState,
        modifier =
            Modifier.zIndex(-index.toFloat()).onPlaced { topInStack = it.positionInParent().y },
        enter =
            expandVertically(spring(dampingRatio = 0.9f, stiffness = 380f), Alignment.Top) +
                fadeIn(tween(220, delayMillis = 80)) +
                scaleIn(tween(320, easing = CosCardGrow), initialScale = 0.92f),
        exit =
            shrinkVertically(tween(320, easing = CosCardEase), Alignment.Top) +
                fadeOut(tween(160)) +
                scaleOut(tween(260, easing = CosCardEase), targetScale = 0.92f),
    ) {
        Box(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(bottom = StackGap)
                    .graphicsLayer {
                        val f = open.value
                        translationY = -topInStack * (1f - f)
                        val scale = StackTuckedScale + (1f - StackTuckedScale) * f
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(0.5f, 0f)
                        translationX = swipe.value
                        alpha = cardAlpha()
                    }
                    .onSizeChanged { cardWidth = it.width.toFloat() }
                    .pointerInput(chip.chipId) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                val width = size.width.toFloat()
                                val offset = swipe.value
                                scope.launch {
                                    if (abs(offset) > width * StackDismissFraction) {
                                        swipe.animateTo(
                                            sign(offset) * width,
                                            tween(180, easing = CosCardEase),
                                        )
                                        currentOnDismiss()
                                    } else {
                                        swipe.animateTo(
                                            0f,
                                            spring(dampingRatio = 0.75f, stiffness = 500f),
                                        )
                                    }
                                }
                            },
                            onDragCancel = {
                                scope.launch { swipe.animateTo(0f, spring(stiffness = 500f)) }
                            },
                            onHorizontalDrag = { change, amount ->
                                change.consume()
                                scope.launch { swipe.snapTo(swipe.value + amount) }
                            },
                        )
                    }
                    .then(
                        if (onTopCardHeight != null) {
                            Modifier.onSizeChanged { onTopCardHeight(it.height.toFloat()) }
                        } else {
                            Modifier
                        }
                    ),
            propagateMinConstraints = true,
        ) {
            CompositionLocalProvider(
                LocalIslandGlassAlpha provides { parentGlassAlpha() * cardAlpha() }
            ) {
                IslandPopupContent(chip, fillWidth = true)
            }
        }
    }
}

/**
 * Fades the bottom edge out while more cards are below it, instead of cutting a card off. Glass
 * cards skip this: their blur is drawn by the window and would stay behind the faded content.
 */
private fun Modifier.fadeScrolledEdge(scroll: ScrollState): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            if (scroll.value < scroll.maxValue) {
                val fade = StackFadeEdge.toPx().coerceAtMost(size.height / 2f)
                drawRect(
                    brush =
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            1f to Color.Black,
                            startY = size.height - fade,
                            endY = size.height,
                        ),
                    blendMode = BlendMode.DstOut,
                )
            }
        }

private val StackFadeEdge = 48.dp
private val StackSideMargin = 16.dp
private val StackMaxWidth = 420.dp
private val StackGap = 8.dp
private val StackVerticalClearance = 96.dp
private val StackMinHeight = 160.dp
private const val StackTuckedScale = 0.9f
private const val StackDismissFraction = 0.3f
private const val StackStaggerBaseMs = 90L
private const val StackStaggerMs = 45L
private const val StackCardInMs = 380
private const val StackCardOutMs = 260

/** Space between two stacked cards of the ColorOS style list. */
private val CardGap = 8.dp

/** How far past its own edges a card may be drawn while it is swiped out of a scrolled list. */
private val SwipeClipSlack = 96.dp

/** How long a swiped card waits for its chip to go away before it comes back. */
private const val DismissGraceMillis = 1000L

/**
 * The popup for several island activities when its stack is opened: every card is shown at once,
 * one under another at the width of the front card, in the order of [chips], which starts with
 * the front card. [expansion] runs from 0, the stack, to 1, the list: the front card stays where
 * it is and the others slide down from under it, or back under it. A horizontal swipe dismisses
 * one card and the cards below close up; when the list is taller than the room it has, it scrolls
 * vertically. [onFrontHeight] gets the height of the front card whenever the list is measured.
 * [onDismissed] runs once a card has left the screen and must remove its chip. [onCardUsed] runs
 * when a control of a card takes a touch.
 */
@Composable
internal fun IslandCardList(
    chips: List<QuickActionChipModel.PopupChip>,
    expansion: () -> Float,
    onFrontHeight: (Int) -> Unit,
    interactive: () -> Boolean,
    fadeDistancePx: Float,
    dismissThresholdPx: Float,
    flingVelocityPx: Float,
    flingMinDistancePx: Float,
    onDismissed: (QuickActionChipModel.PopupChip) -> Unit,
    onCardUsed: (QuickActionChipModel.PopupChip) -> Unit,
) {
    val density = LocalDensity.current
    val gapPx = with(density) { CardGap.roundToPx() }
    val clipSlackPx = with(density) { SwipeClipSlack.toPx() }
    val clipShape = remember(clipSlackPx) { ListClipShape(clipSlackPx) }
    val baseBlur = LocalIslandCardBlurState.current
    var scrollPx by remember { mutableFloatStateOf(0f) }
    var overflowing by remember { mutableStateOf(false) }
    val maxScroll = remember { FloatArray(1) }
    val scrollState = rememberScrollableState { delta ->
        val old = scrollPx
        val new = (old - delta).coerceIn(0f, maxScroll[0])
        scrollPx = new
        old - new
    }

    val revealBelowFront: () -> Float = { (expansion() * 2f).coerceIn(0f, 1f) }

    Layout(
        modifier =
            Modifier.scrollable(scrollState, Orientation.Vertical, enabled = overflowing)
                .graphicsLayer {
                    // Only a list that scrolls is clipped, so a card that fits keeps its shadow.
                    clip = overflowing
                    shape = clipShape
                },
        content = {
            chips.forEachIndexed { index, chip ->
                key(chip.chipId) {
                    IslandListCard(
                        chip = chip,
                        // The cards under the front one come in as they slide out.
                        reveal = if (index == 0) AlwaysRevealed else revealBelowFront,
                        interactive = interactive,
                        baseBlur = baseBlur,
                        gapPx = gapPx,
                        fadeDistancePx = fadeDistancePx,
                        dismissThresholdPx = dismissThresholdPx,
                        flingVelocityPx = flingVelocityPx,
                        flingMinDistancePx = flingMinDistancePx,
                        onDismissed = onDismissed,
                        onCardUsed = onCardUsed,
                    )
                }
            }
        },
    ) { measurables, constraints ->
        if (measurables.isEmpty()) return@Layout layout(0, 0) {}
        // The front card has the width it has as part of the stack; the others take it.
        val front = measurables[0].measure(Constraints(maxWidth = constraints.maxWidth))
        val width = front.width
        val cards =
            listOf(front) +
                measurables.drop(1).map {
                    it.measure(Constraints(minWidth = width, maxWidth = width, minHeight = 0))
                }
        // Each card reports the gap below it, so the closing card takes its gap with it.
        onFrontHeight((front.height - gapPx).coerceAtLeast(1))
        val total = (cards.sumOf { it.height } - gapPx).coerceAtLeast(0)
        val height = if (constraints.hasBoundedHeight) minOf(total, constraints.maxHeight) else total
        maxScroll[0] = (total - height).coerceAtLeast(0).toFloat()
        val over = maxScroll[0] > 0f
        if (overflowing != over) overflowing = over
        val offset = scrollPx.coerceIn(0f, maxScroll[0]).roundToInt()
        layout(width, height) {
            // Read here, so the opening only places the cards again.
            val open = expansion().coerceIn(0f, 1f)
            var y = 0
            cards.forEachIndexed { index, card ->
                // The cards below the front one come from the front card's place, and the front
                // card is on top of them.
                val placed = if (index == 0) -offset else (y * open).roundToInt() - offset
                card.place(0, placed, zIndex = (cards.size - index).toFloat())
                y += card.height
            }
        }
    }
}

@Composable
private fun IslandListCard(
    chip: QuickActionChipModel.PopupChip,
    reveal: () -> Float,
    interactive: () -> Boolean,
    baseBlur: IslandCardBlurState,
    gapPx: Int,
    fadeDistancePx: Float,
    dismissThresholdPx: Float,
    flingVelocityPx: Float,
    flingMinDistancePx: Float,
    onDismissed: (QuickActionChipModel.PopupChip) -> Unit,
    onCardUsed: (QuickActionChipModel.PopupChip) -> Unit,
) {
    var swipeX by remember { mutableFloatStateOf(0f) }
    var dismissing by remember { mutableStateOf(false) }
    var swipeJob by remember { mutableStateOf<Job?>(null) }
    val collapse = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val currentChip by rememberUpdatedState(chip)
    val currentInteractive by rememberUpdatedState(interactive)
    val currentOnDismissed by rememberUpdatedState(onDismissed)
    val currentOnCardUsed by rememberUpdatedState(onCardUsed)
    val dismissible = chip.onDismissed != null
    val fade = (1f - abs(swipeX) / fadeDistancePx).coerceIn(0f, 1f) * reveal()

    fun settle(dismiss: Boolean, direction: Float) {
        swipeJob?.cancel()
        swipeJob =
            scope.launch {
                if (dismiss) {
                    dismissing = true
                    animate(
                        initialValue = swipeX,
                        targetValue = direction * maxOf(abs(swipeX), fadeDistancePx),
                        animationSpec = tween(160),
                    ) { value, _ ->
                        swipeX = value
                    }
                    // The cards below close up before the chip goes, so removing it moves nothing.
                    collapse.animateTo(0f, tween(240, easing = CosCardGrow))
                    currentOnDismissed(currentChip)
                    // A chip that stays after its dismissal came back; show its card again.
                    delay(DismissGraceMillis)
                    dismissing = false
                    launch { collapse.animateTo(1f, tween(200, easing = CosCardGrow)) }
                    animate(swipeX, 0f, animationSpec = tween(200)) { value, _ -> swipeX = value }
                } else {
                    animate(
                        initialValue = swipeX,
                        targetValue = 0f,
                        animationSpec =
                            spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow),
                    ) { value, _ ->
                        swipeX = value
                    }
                }
            }
    }

    Box(
        modifier =
            Modifier.layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    val height =
                        ((placeable.height + gapPx) * collapse.value.coerceIn(0f, 1f)).roundToInt()
                    layout(placeable.width, height) { placeable.place(0, 0) }
                }
                .pointerInput(dismissible) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (!currentInteractive() || dismissing) return@awaitEachGesture
                        val velocityTracker = VelocityTracker()
                        var horizontal: Boolean? = null
                        var overSlop = 0f
                        val drag =
                            awaitTouchSlopOrCancellation(down.id) { change, amount ->
                                // Choose once; vertical motion is left to the list's scroll.
                                if (horizontal == null) {
                                    horizontal = abs(amount.x) >= abs(amount.y)
                                }
                                if (horizontal == true) {
                                    overSlop = amount.x
                                    change.consume()
                                }
                            } ?: return@awaitEachGesture
                        if (horizontal != true) return@awaitEachGesture

                        swipeJob?.cancel()
                        swipeX += overSlop
                        velocityTracker.addPosition(drag.uptimeMillis, drag.position)
                        val completed =
                            horizontalDrag(drag.id) { change ->
                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                swipeX += change.positionChange().x
                                change.consume()
                            }
                        if (!completed) {
                            settle(dismiss = false, direction = 0f)
                            return@awaitEachGesture
                        }
                        val velocity = velocityTracker.calculateVelocity().x
                        val flung =
                            abs(velocity) > flingVelocityPx &&
                                abs(swipeX) > flingMinDistancePx &&
                                sign(velocity) == sign(swipeX)
                        val dismiss =
                            dismissible && (abs(swipeX) >= dismissThresholdPx || flung)
                        settle(dismiss = dismiss, direction = sign(swipeX))
                    }
                },
        propagateMinConstraints = true,
    ) {
        Box(
            modifier =
                Modifier.pointerInput(Unit) {
                        // In the main pass the controls of the card have already had the touch,
                        // while the swipe above and the list's scroll have not.
                        awaitEachGesture {
                            var used = awaitFirstDown(requireUnconsumed = false).isConsumed
                            while (!used) {
                                val event = awaitPointerEvent()
                                used = event.changes.any { it.isConsumed }
                                if (event.changes.none { it.pressed }) break
                            }
                            if (used && currentInteractive()) currentOnCardUsed(currentChip)
                        }
                    }
                    .offset { IntOffset(swipeX.roundToInt(), 0) }
                    .graphicsLayer { alpha = fade },
            propagateMinConstraints = true,
        ) {
            // The card moves by layout, so its glass follows it; the fade has to reach the glass.
            CompositionLocalProvider(
                LocalIslandCardBlurState provides
                    IslandCardBlurState(
                        shift = baseBlur.shift,
                        alpha = baseBlur.alpha * fade,
                        // The cards slide in and out of the stack, so glass and content fade as one.
                        glassFollowsAlpha = true,
                    )
            ) {
                IslandPopupContent(chip, openMediaOnTap = true)
            }
        }
    }
}

private val AlwaysRevealed: () -> Float = { 1f }

/** A clip a bit wider than the list, so a card being swiped away is not cut at once. */
private class ListClipShape(private val slackPx: Float) : Shape {
    override fun createOutline(
        size: androidx.compose.ui.geometry.Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline = Outline.Rectangle(Rect(-slackPx, 0f, size.width + slackPx, size.height))
}

private val Grow = CubicBezierEasing(0.3f, 0f, 0.1f, 1f)
private val Ease = CubicBezierEasing(0.33f, 0f, 0.67f, 1f)
private val CloseMove = CubicBezierEasing(0.6f, 0f, 0.1f, 1f)
private val UpdateEase = Easing { ((1 - kotlin.math.cos(Math.PI * it)) / 2).toFloat() }
private val CardShape = RoundedCornerShape(28.dp)
// Liquid Glass shade over the blur: opaque at the top, letting the blur through toward the bottom.
private val GlassShade = Brush.verticalGradient(listOf(Color.Black, Color(0x05000000)))
private val GlassNoBlurFill = Color(0xFF262626)

@Composable
internal fun CosIslandPopup(
    chip: QuickActionChipModel.PopupChip,
    visible: Boolean,
    anchor: Rect?,
    chips: List<QuickActionChipModel.PopupChip>,
    onCardUsed: (QuickActionChipModel.PopupChip) -> Unit,
    onPageSelected: (QuickActionChipModel.PopupChip) -> Unit,
    onMorphActiveChanged: (Boolean) -> Unit,
    onHidden: () -> Unit,
    appHandleBounds: List<android.graphics.Rect>,
) {
    val density = LocalDensity.current
    val config = LocalConfiguration.current
    val status = maxOf(
        LocalContext.current.resources.getDimensionPixelSize(R.dimen.status_bar_height),
        appHandleBounds.maxOfOrNull { it.bottom } ?: 0,
    )
    val statusDp = with(density) { status.toDp() }
    val gapPx = with(density) { 8.dp.toPx() }
    val width =
        when {
            min(config.screenWidthDp, config.screenHeightDp) >= 600 -> 476.dp
            config.screenWidthDp < config.screenHeightDp -> (config.screenWidthDp - 32).dp
            else -> 456.dp
        }
    val dismissedIds = remember { mutableStateListOf<Any>() }
    val admitted = chips.filter { it.chipId !in dismissedIds }.take(4)
    val swipeThresholds = islandSwipeThresholds()
    LaunchedEffect(chips.map { it.chipId }) {
        dismissedIds.removeAll { id -> chips.none { it.chipId == id } }
    }
    val initialIds = remember { admitted.map { it.chipId } }
    val fixedAnchor = remember(config.windowConfiguration.rotation) { anchor }
    val seenIds = remember { mutableSetOf<Any>() }
    val retained = remember {
        mutableStateListOf<QuickActionChipModel.PopupChip>().apply { addAll(admitted) }
    }
    val currentHidden by rememberUpdatedState(onHidden)
    val currentMorph by rememberUpdatedState(onMorphActiveChanged)
    val currentPageSelected by rememberUpdatedState(onPageSelected)
    val hide by rememberUpdatedState({ chip.hidePopup() })
    val activeCards = remember { mutableStateListOf<Any>() }
    val closedCards = remember { mutableStateListOf<Any>() }
    var scroll by remember { mutableFloatStateOf(0f) }
    var maxScroll by remember { mutableFloatStateOf(0f) }
    var collapseAllowed by remember { mutableStateOf(false) }
    var upward by remember { mutableFloatStateOf(0f) }
    val placements = remember { mutableMapOf<Any, CardPlacement>() }
    val state = rememberScrollableState { delta ->
        val old = scroll
        scroll = (scroll - delta).coerceIn(0f, maxScroll)
        old - scroll
    }
    var viewport by remember { mutableStateOf(Rect.Zero) }
    LaunchedEffect(admitted) {
        val removed = retained.filter { old -> admitted.none { it.chipId == old.chipId } }
        val merged = admitted + removed
        retained.clear()
        retained.addAll(merged)
    }
    // The chips are new instances whenever one of them changes, which media does every second.
    // Only a different set of cards returns the list to the top.
    val admittedIds = admitted.map { it.chipId }
    LaunchedEffect(admittedIds) { scroll = 0f }
    LaunchedEffect(visible) {
        currentMorph(false)
        if (visible) closedCards.clear()
    }
    LaunchedEffect(visible, activeCards.toList(), closedCards.toList()) {
        if (!visible && activeCards.all { it in closedCards }) currentHidden()
    }
    DisposableEffect(Unit) { onDispose { currentMorph(false) } }
    val scrollConnection =
        remember(state) {
            object : NestedScrollConnection {
                override suspend fun onPostFling(
                    consumed: Velocity,
                    available: Velocity,
                ): Velocity {
                    upward = 0f
                    return Velocity.Zero
                }

                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (
                        source == NestedScrollSource.UserInput &&
                            collapseAllowed &&
                            available.y < 0f &&
                            scroll >= maxScroll
                    ) {
                        upward -= available.y
                        if (upward > 50f) {
                            upward = 0f
                            hide()
                        }
                        return Offset(0f, available.y)
                    }
                    upward = 0f
                    return Offset.Zero
                }
            }
        }
    Popup(
        popupPositionProvider =
            remember {
                object : PopupPositionProvider {
                    override fun calculatePosition(
                        anchorBounds: IntRect,
                        windowSize: IntSize,
                        layoutDirection: LayoutDirection,
                        popupContentSize: IntSize,
                    ) = IntOffset.Zero
                }
            },
        onDismissRequest = { hide() },
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
        IslandPopupSystemDialogs { hide() }
        IslandPopupGestureInsets(appHandleBounds)
        Box(
            Modifier.fillMaxSize().clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                hide()
            },
            contentAlignment = Alignment.TopCenter,
        ) {
            Layout(
                modifier =
                    Modifier.width(width)
                        .fillMaxHeight()
                        .padding(vertical = statusDp)
                        .onGloballyPositioned { viewport = it.boundsInWindow() }
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(
                                    requireUnconsumed = false,
                                    pass = PointerEventPass.Initial,
                                )
                                collapseAllowed = scroll >= maxScroll
                                upward = 0f
                                do {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                } while (event.changes.any { it.pressed })
                                collapseAllowed = false
                            }
                        }
                        .nestedScroll(scrollConnection)
                        .scrollable(state, Orientation.Vertical),
                content = {
                    retained.forEachIndexed { index, item ->
                        key(item.chipId) {
                            val placement = placements.getOrPut(item.chipId) { CardPlacement() }
                            if (placement.initialized) {
                                val position =
                                    animateFloatAsState(
                                        placement.target.toFloat(),
                                        tween(250, easing = UpdateEase),
                                        label = "cardPosition",
                                    )
                                placement.position = { position.value }
                            }
                            val present = admitted.any { it.chipId == item.chipId }
                            val runOpening = remember {
                                item.chipId in initialIds && item.chipId !in seenIds
                            }
                            CosListCard(
                                item,
                                visible,
                                present,
                                runOpening,
                                if (visible)
                                    33L *
                                        (initialIds.size - 1 - initialIds.indexOf(item.chipId))
                                            .coerceAtLeast(0)
                                else 33L * index,
                                fixedAnchor,
                                viewport,
                                onCardUsed,
                                position = { size ->
                                    val y =
                                        viewport.top +
                                            gapPx +
                                            (placement.position?.invoke()
                                                ?: placement.target.toFloat()) -
                                            scroll.coerceAtMost(maxScroll)
                                    Rect(
                                        viewport.left,
                                        y,
                                        viewport.left + size.width,
                                        y + size.height,
                                    )
                                },
                                onActive = { active ->
                                    if (active) {
                                        seenIds.add(item.chipId)
                                        if (item.chipId !in activeCards)
                                            activeCards.add(item.chipId)
                                    } else activeCards.remove(item.chipId)
                                },
                                onClosed = {
                                    if (item.chipId !in closedCards) closedCards.add(item.chipId)
                                },
                                onRemoved = {
                                    retained.removeAll { it.chipId == item.chipId }
                                    placements.remove(item.chipId)
                                },
                                swipeThresholds = swipeThresholds,
                                onDismissed = {
                                    val next = admitted.firstOrNull { it.chipId != item.chipId }
                                    if (item.chipId == chip.chipId && next != null) {
                                        currentPageSelected(next)
                                    }
                                    val last = next == null
                                    dismissedIds.add(item.chipId)
                                    retained.removeAll { it.chipId == item.chipId }
                                    placements.remove(item.chipId)
                                    item.onDismissed?.invoke()
                                    if (last) hide()
                                },
                                modifier = Modifier,
                            )
                        }
                    }
                },
            ) { measurables, constraints ->
                val cards =
                    measurables.map { it.measure(Constraints.fixedWidth(constraints.maxWidth)) }
                var y = 0
                cards.forEachIndexed { index, card ->
                    val item = retained.getOrNull(index) ?: return@forEachIndexed
                    placements[item.chipId]?.apply {
                        target = y
                        initialized = true
                    }
                    y += card.height
                }
                val height = constraints.maxHeight
                maxScroll = (y - height).coerceAtLeast(0).toFloat()
                layout(constraints.maxWidth, height) {
                    cards.forEachIndexed { index, card ->
                        val item = retained.getOrNull(index) ?: return@forEachIndexed
                        val placement = placements[item.chipId] ?: return@forEachIndexed
                        card.place(
                            0,
                            ((placement.position?.invoke() ?: placement.target.toFloat()) -
                                    scroll.coerceAtMost(maxScroll))
                                .roundToInt(),
                        )
                    }
                }
            }
        }
    }
}

private class CardPlacement {
    var initialized by mutableStateOf(false)
    var target by mutableIntStateOf(0)
    var position: (() -> Float)? = null
}

private data class Motion(
    val scale: Float = 0.4f,
    val reveal: Float = 0f,
    val travel: Float = 1f,
    val content: Float = 0f,
    val blur: Float = 12f,
    val veil: Float = 1f,
    val alpha: Float = 0f,
    val shadow: Float = 0f,
    val shiftX: Float = 0f,
    val shiftY: Float = 0f,
    val rect: Rect = Rect.Zero,
    val radius: Float = 0f,
    val topClipped: Boolean = false,
    val bottomClipped: Boolean = false,
)

@Composable
private fun CosListCard(
    chip: QuickActionChipModel.PopupChip,
    visible: Boolean,
    present: Boolean,
    initial: Boolean,
    stagger: Long,
    anchor: Rect?,
    viewport: Rect,
    onCardUsed: (QuickActionChipModel.PopupChip) -> Unit,
    position: (IntSize) -> Rect,
    onActive: (Boolean) -> Unit,
    onClosed: () -> Unit,
    onRemoved: () -> Unit,
    swipeThresholds: IslandSwipeThresholds,
    onDismissed: () -> Unit,
    modifier: Modifier,
) {
    val scale = remember { Animatable(if (initial) 0.4f else 1f) }
    val reveal = remember { Animatable(if (initial) 0f else 1f) }
    val travel = remember { Animatable(if (initial) 1f else 0f) }
    val content = remember { Animatable(if (initial) 0f else 1f) }
    val blur = remember { Animatable(if (initial) 12f else 0f) }
    val veil = remember { Animatable(if (initial) 1f else 0f) }
    val fade = remember { Animatable(if (initial) 1f else 0f) }
    val shadow = remember { Animatable(if (initial) 0f else 0.8f) }
    var started by remember { mutableStateOf(false) }
    var entranceStarted by remember { mutableStateOf(!initial) }
    var closed by remember { mutableStateOf(false) }
    val currentOnClosed by rememberUpdatedState(onClosed)
    val currentOnRemoved by rememberUpdatedState(onRemoved)
    val currentOnActive by rememberUpdatedState(onActive)
    DisposableEffect(Unit) {
        currentOnActive(true)
        onDispose { currentOnActive(false) }
    }
    var swipeX by remember { mutableFloatStateOf(0f) }
    var dismissing by remember { mutableStateOf(false) }
    var swipeJob by remember { mutableStateOf<Job?>(null) }
    val swipeScope = rememberCoroutineScope()
    val currentDismissed by rememberUpdatedState(onDismissed)
    val currentChip by rememberUpdatedState(chip)
    val currentVisible by rememberUpdatedState(visible)
    val currentThresholds by rememberUpdatedState(swipeThresholds)
    fun settleSwipe(dismiss: Boolean) {
        swipeJob?.cancel()
        swipeJob =
            swipeScope.launch {
                if (dismiss) dismissing = true
                val target =
                    if (dismiss) sign(swipeX) * maxOf(abs(swipeX), currentThresholds.fadeDistancePx)
                    else 0f
                animate(
                    swipeX,
                    target,
                    animationSpec =
                        if (dismiss) tween(160)
                        else spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow),
                ) { value, _ ->
                    swipeX = value
                }
                if (dismiss) currentDismissed()
            }
    }
    var frozen by remember { mutableStateOf(Motion()) }
    var measured by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val radius = with(density) { 28.dp.toPx() }
    val blurUnit = with(density) { 1.dp.toPx() }
    LaunchedEffect(present) {
        fade.animateTo(if (present) 1f else 0f, tween(240, easing = UpdateEase))
        if (!present) currentOnRemoved()
    }
    val rotation = LocalConfiguration.current.windowConfiguration.rotation
    LaunchedEffect(visible, measured, rotation, density) {
        if (!measured) return@LaunchedEffect
        try {
            if (visible) {
                val atRest =
                    scale.value == 1f && reveal.value == 1f && travel.value == 0f &&
                        shadow.value == 0.8f && content.value == 1f && blur.value == 0f &&
                        veil.value == 0f
                val animate = (initial && !started) || closed || (started && !atRest)
                started = true
                closed = false
                if (animate) {
                    delay(stagger)
                    entranceStarted = true
                    coroutineScope {
                        launch {
                            scale.animateTo(1.03f, tween(300, easing = Grow))
                            scale.animateTo(1f, tween(300, easing = Ease))
                        }
                        launch { reveal.animateTo(1f, tween(300, easing = Grow)) }
                        launch { travel.animateTo(0f, tween(300, easing = Grow)) }
                        launch { shadow.animateTo(0.8f, tween(300, easing = Ease)) }
                        launch { content.animateTo(1f, tween(300, easing = Ease)) }
                        launch { blur.animateTo(0f, tween(300, easing = Ease)) }
                        launch { veil.animateTo(0f, tween(200, delayMillis = 100, easing = Ease)) }
                    }
                }
            } else if (!visible) {
                delay(stagger)
                coroutineScope {
                    launch { scale.animateTo(0.4f, tween(400, easing = Ease)) }
                    launch { reveal.animateTo(0f, tween(400, easing = Ease)) }
                    launch { travel.animateTo(1f, tween(400, easing = CloseMove)) }
                    launch { shadow.animateTo(0f, tween(400, easing = Ease)) }
                    launch { content.animateTo(0f, tween(150, easing = Ease)) }
                    launch { blur.animateTo(12f, tween(400, easing = Ease)) }
                    launch { veil.animateTo(1f, tween(150, easing = Ease)) }
                }
                closed = true
                currentOnClosed()
            }
        } finally {
            // Preserve in-flight values when visibility changes so the next transition can resume.
            if (visible == currentVisible) {
                withContext(NonCancellable) {
                    val shown = currentVisible
                    scale.snapTo(if (shown) 1f else 0.4f)
                    reveal.snapTo(if (shown) 1f else 0f)
                    travel.snapTo(if (shown) 0f else 1f)
                    shadow.snapTo(if (shown) 0.8f else 0f)
                    content.snapTo(if (shown) 1f else 0f)
                    blur.snapTo(if (shown) 0f else 12f)
                    veil.snapTo(if (shown) 0f else 1f)
                    entranceStarted = true
                    closed = !shown
                }
            }
        }
    }
    // The popup background of the settings is drawn from the first frame to the last: the
    // window blur for the blur and Liquid Glass, a solid fill for the others.
    val colorMode = rememberPopupColorMode()
    val liquid = liquidGlassEnabled() && colorMode != POPUP_COLOR_MODE_SOLID_BLACK
    val blurred = liquid || colorMode == POPUP_COLOR_MODE_BLUR
    // The COS glass frames the card with light edges in the artwork colour of a media card, and
    // with a neutral hairline for the others.
    val cosGlass = liquid && liquidGlassCosStyle()
    val frameArtwork =
        if (cosGlass) {
            val model = (chip.popupContent as? PopupContentModel.Media)?.model
            // The app icon stands in when a track has no artwork, and counts as none here.
            remember(model?.artworkIcon, model?.appIcon) {
                (model?.artworkIcon?.takeIf { it !== model.appIcon } as? UiIcon.Loaded)?.drawable
            }
        } else {
            null
        }
    val frame = rememberCosIslandFrame(frameArtwork)
    val currentBlurred = rememberUpdatedState(blurred)
    // The COS glass draws a rounded region of material 0 as tile glass, so the cards ask for the
    // island material there; other styles keep their own glass.
    val currentMaterial =
        rememberUpdatedState(
            if (liquid && liquidGlassCosStyle()) BackgroundBlurDrawable.MATERIAL_ISLAND
            else BackgroundBlurDrawable.MATERIAL_NONE
        )
    val region = rememberAxBlurRegion { size ->
        val motion =
            Motion(
                scale.value,
                reveal.value,
                travel.value,
                content.value,
                blur.value,
                veil.value,
                if (closed || !entranceStarted) 0f
                else
                    fade.value *
                        (1f - abs(swipeX) / swipeThresholds.fadeDistancePx).coerceIn(0f, 1f),
                shadow.value,
            )
        measured = true
        val q = size.width * 0.12f
        val w = q + (size.width - q) * motion.reveal
        val h = q + (size.height - q) * motion.reveal
        val rect =
            android.graphics.RectF(
                (size.width - w) / 2,
                (size.height - h) / 2,
                (size.width + w) / 2,
                (size.height + h) / 2,
            )
        val localRadius = min(radius, min(w, h) / 2)
        val resting = position(size)
        val centerY = resting.center.y
        val dx =
            ((anchor?.center?.x ?: resting.center.x) - resting.center.x) * motion.travel + swipeX
        val dy = ((anchor?.center?.y ?: viewport.top / 2) - centerY) * motion.travel
        val top = (viewport.top - centerY - dy) / motion.scale + size.height / 2
        val bottom = (viewport.bottom - centerY - dy) / motion.scale + size.height / 2
        val atRest = motion.reveal == 1f && motion.travel == 0f && motion.scale == 1f
        val topClipped = atRest && top > rect.top
        val bottomClipped = atRest && bottom < rect.bottom
        if (atRest) {
            rect.top = maxOf(rect.top, top)
            rect.bottom = minOf(rect.bottom, bottom)
        }
        val radii =
            floatArrayOf(
                if (topClipped) 0f else localRadius,
                if (topClipped) 0f else localRadius,
                if (topClipped) 0f else localRadius,
                if (topClipped) 0f else localRadius,
                if (bottomClipped) 0f else localRadius,
                if (bottomClipped) 0f else localRadius,
                if (bottomClipped) 0f else localRadius,
                if (bottomClipped) 0f else localRadius,
            )
        frozen =
            motion.copy(
                shiftX = dx,
                shiftY = dy,
                rect = Rect(rect.left, rect.top, rect.right, rect.bottom),
                radius = localRadius,
                topClipped = topClipped,
                bottomClipped = bottomClipped,
            )
        AxBlurRegionFrame(
            rect,
            AxBlurRegionCorners(
                CornerRadii(radii),
                CornerRadii(FloatArray(8) { radii[it] * motion.scale }),
            ),
            // The region follows the card's geometry and fades with the card and its veil.
            if (currentBlurred.value) motion.alpha * (1f - motion.veil) else 0f,
            currentMaterial.value,
        )
    }
    val colors = MaterialTheme.colorScheme
    Box(modifier.padding(top = 8.dp)) {
        Box(
            Modifier.fillMaxWidth()
                .pointerInput(chip.chipId) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (
                            !currentVisible || dismissing || scale.value != 1f || reveal.value != 1f
                        ) {
                            return@awaitEachGesture
                        }
                        var horizontal: Boolean? = null
                        var overSlop = 0f
                        val drag =
                            awaitTouchSlopOrCancellation(down.id) { change, amount ->
                                if (horizontal == null) horizontal = abs(amount.x) >= abs(amount.y)
                                if (horizontal == true) {
                                    overSlop = amount.x
                                    change.consume()
                                }
                            } ?: return@awaitEachGesture
                        if (horizontal != true) return@awaitEachGesture
                        swipeJob?.cancel()
                        swipeX += overSlop
                        val tracker = VelocityTracker()
                        tracker.addPosition(drag.uptimeMillis, drag.position)
                        val completed =
                            horizontalDrag(drag.id) { change ->
                                tracker.addPosition(change.uptimeMillis, change.position)
                                swipeX += change.positionChange().x
                                change.consume()
                            }
                        val velocity = tracker.calculateVelocity().x
                        val thresholds = currentThresholds
                        val flung =
                            abs(velocity) > thresholds.flingVelocityPx &&
                                abs(swipeX) > thresholds.flingDistancePx &&
                                sign(velocity) == sign(swipeX)
                        settleSwipe(
                            completed &&
                                currentChip.onDismissed != null &&
                                (abs(swipeX) >= thresholds.dismissDistancePx || flung)
                        )
                    }
                }
                .graphicsLayer {
                    val m = frozen
                    // Clip the finished veil once so the light surface cannot fringe its edge.
                    compositingStrategy =
                        if (m.veil > 0f) CompositingStrategy.Offscreen
                        else CompositingStrategy.Auto
                    alpha = m.alpha
                    scaleX = m.scale
                    scaleY = m.scale
                    translationX = m.shiftX
                    translationY = m.shiftY
                    shadowElevation = 16.dp.toPx()
                    // A see-through card would show its own shadow as a dark slab.
                    val shadowAlpha = if (currentBlurred.value) 0f else m.shadow
                    spotShadowColor = Color.Black.copy(alpha = shadowAlpha)
                    ambientShadowColor = Color.Black.copy(alpha = shadowAlpha)
                    shape =
                        object : Shape {
                            override fun createOutline(
                                size: Size,
                                layoutDirection: LayoutDirection,
                                density: Density,
                            ): Outline {
                                val topRadius = CornerRadius(if (m.topClipped) 0f else m.radius)
                                val bottomRadius =
                                    CornerRadius(if (m.bottomClipped) 0f else m.radius)
                                return Outline.Rounded(
                                    RoundRect(
                                        m.rect.left,
                                        m.rect.top,
                                        maxOf(m.rect.left, m.rect.right),
                                        maxOf(m.rect.top, m.rect.bottom),
                                        topRadius,
                                        topRadius,
                                        bottomRadius,
                                        bottomRadius,
                                    )
                                )
                            }
                        }
                    clip = true
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {}
        ) {
            AxBlurSurface(
                modifier =
                    Modifier.fillMaxWidth()
                        .then(
                            when {
                                cosGlass -> Modifier.cosIslandFrameStroke(frame, 28.dp)
                                liquid -> Modifier.liquidGlassRim(CardShape)
                                else -> Modifier
                            }
                        ),
                shape = CardShape,
                cornerRadius = 28.dp,
                region = region,
                alpha = 1f - frozen.veil,
                propagateMinConstraints = true,
                // Without blur the shade sits on an opaque gray instead of a see-through tint.
                surfaceColor =
                    if (liquid) GlassNoBlurFill
                    else colors.surfaceContainerHigh.copy(alpha = 0.9f),
                tintColor =
                    when {
                        // The island material mixes the gray into the blur itself.
                        liquid && liquidGlassCosStyle() -> Color.Transparent
                        liquid -> Color(liquidGlassIslandTint(colorMode))
                        colors.surface.luminance() < 0.5f -> Color.White.copy(alpha = 0.10f)
                        else -> Color.White.copy(alpha = 0.55f)
                    },
            ) {
                if (!blurred) {
                    Box(
                        Modifier.matchParentSize()
                            .graphicsLayer { alpha = 1f - frozen.veil }
                            .background(
                                if (colorMode == POPUP_COLOR_MODE_SOLID_BLACK) Color.Black
                                else colors.surfaceContainerHigh,
                                CardShape,
                            )
                    )
                }
                if (liquid) {
                    Box(
                        Modifier.matchParentSize()
                            .graphicsLayer { alpha = 1f - frozen.veil }
                            .background(GlassShade, CardShape)
                    )
                }
                if (cosGlass) {
                    Box(
                        Modifier.matchParentSize()
                            .graphicsLayer { alpha = 1f - frozen.veil }
                            .cosIslandFrameLight(frame, 28.dp)
                    )
                }
                Box(
                    Modifier.fillMaxWidth()
                        .pointerInput(chip.chipId) {
                            awaitEachGesture {
                                var used = awaitFirstDown(requireUnconsumed = false).isConsumed
                                while (!used) {
                                    val event = awaitPointerEvent()
                                    used = event.changes.any { it.isConsumed }
                                    if (event.changes.none { it.pressed }) break
                                }
                                if (used) onCardUsed(chip)
                            }
                        }
                        .graphicsLayer {
                            alpha = frozen.content
                            val r = frozen.blur * blurUnit
                            renderEffect = if (r > 0f) BlurEffect(r, r, TileMode.Decal) else null
                        },
                    propagateMinConstraints = true,
                ) {
                    val darkSurface = liquid || colorMode == POPUP_COLOR_MODE_SOLID_BLACK
                    val body: @Composable () -> Unit = {
                        CompositionLocalProvider(
                            LocalPopupContainer provides true,
                            LocalIslandCosStyle provides true,
                            LocalPopupDarkSurface provides darkSurface,
                            LocalContentColor provides
                                if (darkSurface) Color.White else colors.onSurface,
                        ) {
                            IslandPopupContent(chip, openMediaOnTap = true)
                        }
                    }
                    // A dark card keeps light content in the light theme too, including the
                    // controls that take their colors from the theme.
                    if (darkSurface) PlatformTheme(isDarkTheme = true, content = body) else body()
                }
                Box(
                    Modifier.matchParentSize()
                        .graphicsLayer { alpha = frozen.veil }
                        .background(Color.Black)
                )
            }
        }
    }
}
