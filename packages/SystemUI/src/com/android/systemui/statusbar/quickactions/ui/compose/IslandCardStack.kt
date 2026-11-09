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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min
import androidx.compose.ui.zIndex
import com.android.systemui.statusbar.quickactions.shared.model.PopupContentModel
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipId
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipModel
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
                PopupBody(chip)
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
