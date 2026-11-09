// SPDX-License-Identifier: Apache-2.0
package io.github.miner7222.systemui.island

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipModel
import com.android.systemui.statusbar.quickactions.ui.compose.CosCardGrow
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Space between two stacked cards of the ColorOS style list. */
private val CardGap = 8.dp

/** How far past its own edges a card may be drawn while it is swiped out of a scrolled list. */
private val SwipeClipSlack = 96.dp

/** How long a swiped card waits for its chip to go away before it comes back. */
private const val DismissGraceMillis = 1000L

/**
 * The ColorOS style popup for several island activities: every card is shown at once, one under
 * another at the popup width, in the order of [chips]. A horizontal swipe dismisses one card and
 * the cards below close up; when the list is taller than the room it has, it scrolls vertically.
 * [onDismissed] runs once a card has left the screen and must remove its chip. [onCardUsed] runs when
 * a control of a card takes a touch.
 */
@Composable
internal fun IslandCardList(
    chips: List<QuickActionChipModel.PopupChip>,
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

    Layout(
        modifier =
            Modifier.scrollable(scrollState, Orientation.Vertical, enabled = overflowing)
                .graphicsLayer {
                    // Only a list that scrolls is clipped, so a card that fits keeps its shadow.
                    clip = overflowing
                    shape = clipShape
                },
        content = {
            chips.forEach { chip ->
                key(chip.chipId) {
                    IslandListCard(
                        chip = chip,
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
        val width = constraints.maxWidth
        val cards =
            measurables.map {
                it.measure(
                    Constraints(minWidth = width, maxWidth = width, minHeight = 0)
                )
            }
        // Each card reports the gap below it, so the closing card takes its gap with it.
        val total = (cards.sumOf { it.height } - gapPx).coerceAtLeast(0)
        val height = if (constraints.hasBoundedHeight) minOf(total, constraints.maxHeight) else total
        maxScroll[0] = (total - height).coerceAtLeast(0).toFloat()
        val over = maxScroll[0] > 0f
        if (overflowing != over) overflowing = over
        val offset = scrollPx.coerceIn(0f, maxScroll[0]).roundToInt()
        layout(width, height) {
            var y = -offset
            cards.forEach {
                it.place(0, y)
                y += it.height
            }
        }
    }
}

@Composable
private fun IslandListCard(
    chip: QuickActionChipModel.PopupChip,
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
    val fade = (1f - abs(swipeX) / fadeDistancePx).coerceIn(0f, 1f)

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
                    IslandCardBlurState(shift = baseBlur.shift, alpha = baseBlur.alpha * fade)
            ) {
                PopupBody(chip)
            }
        }
    }
}

/** A clip a bit wider than the list, so a card being swiped away is not cut at once. */
private class ListClipShape(private val slackPx: Float) : Shape {
    override fun createOutline(
        size: androidx.compose.ui.geometry.Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline = Outline.Rectangle(Rect(-slackPx, 0f, size.width + slackPx, size.height))
}
