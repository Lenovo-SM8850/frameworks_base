// SPDX-License-Identifier: Apache-2.0
package io.github.miner7222.systemui.island

import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
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
import androidx.compose.ui.layout.onGloballyPositioned
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
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.android.axion.blur.AxBlurRegionCorners
import com.android.axion.blur.AxBlurRegionFrame
import com.android.axion.blur.AxBlurSurface
import com.android.axion.blur.CornerRadii
import com.android.axion.blur.rememberAxBlurRegion
import com.android.systemui.res.R
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipModel
import com.android.systemui.statusbar.quickactions.ui.compose.LocalIslandCosStyle
import com.android.systemui.statusbar.quickactions.ui.compose.LocalPopupContainer
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Grow = CubicBezierEasing(0.3f, 0f, 0.1f, 1f)
private val Ease = CubicBezierEasing(0.33f, 0f, 0.67f, 1f)
private val CloseMove = CubicBezierEasing(0.6f, 0f, 0.1f, 1f)
private val UpdateEase = Easing { ((1 - kotlin.math.cos(Math.PI * it)) / 2).toFloat() }
private val CardShape = RoundedCornerShape(28.dp)

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
        scroll = 0f
    }
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
                    placements.getValue(retained[index].chipId).apply {
                        target = y
                        initialized = true
                    }
                    y += card.height
                }
                val height = constraints.maxHeight
                maxScroll = (y - height).coerceAtLeast(0).toFloat()
                layout(constraints.maxWidth, height) {
                    cards.forEachIndexed { index, card ->
                        val placement = placements.getValue(retained[index].chipId)
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
        val atRest = motion.reveal == 1f && motion.travel == 0f
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
            motion.alpha,
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
                    alpha = m.alpha
                    scaleX = m.scale
                    scaleY = m.scale
                    translationX = m.shiftX
                    translationY = m.shiftY
                    shadowElevation = 16.dp.toPx()
                    spotShadowColor = Color.Black.copy(alpha = m.shadow)
                    ambientShadowColor = Color.Black.copy(alpha = m.shadow)
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
                modifier = Modifier.fillMaxWidth(),
                shape = CardShape,
                cornerRadius = 28.dp,
                region = region,
                propagateMinConstraints = true,
                surfaceColor = colors.surfaceContainerHigh.copy(alpha = 0.9f),
                tintColor =
                    if (colors.surface.luminance() < 0.5f) Color.White.copy(alpha = 0.10f)
                    else Color.White.copy(alpha = 0.55f),
            ) {
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
                    CompositionLocalProvider(
                        LocalPopupContainer provides true,
                        LocalIslandCosStyle provides true,
                        LocalContentColor provides colors.onSurface,
                    ) {
                        PopupBody(chip)
                    }
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
