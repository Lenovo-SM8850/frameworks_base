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

import android.content.Context
import android.view.DisplayCutout
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import com.android.compose.ui.graphics.painter.rememberDrawablePainter
import com.android.systemui.common.shared.model.Icon as IconModel
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.common.ui.compose.load
import com.android.systemui.res.R
import com.android.systemui.statusbar.quickactions.popups.shared.DynamicIslandFeatureSettings
import com.android.systemui.statusbar.quickactions.popups.shared.model.SystemEventKind
import com.android.systemui.statusbar.quickactions.screenrecord.shared.model.ScreenRecordPopupModel
import com.android.systemui.statusbar.quickactions.shared.model.PopupContentModel
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipModel
import com.android.systemui.statusbar.quickactions.shared.model.chipText
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import io.github.miner7222.systemui.island.IslandChip

// Only icon/text layers animate. Surface measurement remains the popup's stable morph anchor.
internal val LocalIslandContentProgress = staticCompositionLocalOf<() -> Float> { { 1f } }
internal val LocalIslandContentSpatialMotion = staticCompositionLocalOf { true }
internal val LocalDynamicIslandWidth = staticCompositionLocalOf { 120.dp }
internal val CameraSplitSpacing = 8.dp
internal const val AlarmCountdownTickMs = 30_000L
private val DynamicIslandEmbeddedGapFallbackWidth = 38.dp
private val DynamicIslandEmbeddedGapMinWidth = 34.dp
private val DynamicIslandEmbeddedGapMaxWidth = 88.dp
private val DynamicIslandEmbeddedGapSidePadding = 10.dp
enum class DynamicIslandAnchor {
    CENTER,
    START,
}

data class DynamicIslandCutoutSpec(
    val embeddedGapWidth: Dp,
    val horizontalOffset: Dp,
    val anchor: DynamicIslandAnchor = DynamicIslandAnchor.CENTER,
    /**
     * Window x of the camera hole's centre when the island is to be centred on it, or null when
     * [horizontalOffset] (or no shift at all) applies. The status bar's centred area can have
     * uneven side paddings, so an offset measured from the screen centre misses the camera;
     * the container instead moves the island by the distance from where it was actually placed.
     */
    val cutoutCenterX: Float? = null,
    /** Width of the camera hole, which the ColorOS island grows out of. */
    val holeSize: Dp = 20.dp,
    val minimumHeight: Dp = 0.dp,
    val minimumWidth: Dp = 0.dp,
    val cutoutWidth: Dp = 0.dp,
    val holeHeight: Dp = 20.dp,
    val holeCenterY: Dp? = null,
)
internal val DynamicIslandCutoutFillMargin = 1.5.dp
internal val DynamicIslandOutlineWidth = 1.dp
/** Collapsed height at a height scale of one, with or without a camera to cover. */
internal val DynamicIslandBaseHeight = 35.dp

/** Vertical centre of the island without a camera, where it sits on a camera that has one. */
internal val DynamicIslandDefaultCenterY = 24.5.dp

private fun cutoutMinimumHeight(crossAxisSize: Dp): Dp =
    crossAxisSize + (DynamicIslandCutoutFillMargin + DynamicIslandOutlineWidth) * 2

private fun cutoutMinimumWidth(mainAxisSize: Dp, minimumHeight: Dp): Dp =
    mainAxisSize + minimumHeight + (DynamicIslandCutoutFillMargin + DynamicIslandOutlineWidth) * 2

private val DynamicIslandHoleSidePadding = 4.dp
private val DynamicIslandScreenEdgeMargin = 4.dp
private const val DynamicIslandStartAnchorMaxFraction = 0.35f
private const val DynamicIslandCenterMinFraction = 0.35f
private const val DynamicIslandCenterMaxFraction = 0.65f

fun resolveDynamicIslandAnchor(
    cutoutCenterX: Float?,
    windowWidthPx: Int,
    isRtl: Boolean,
): DynamicIslandAnchor {
    if (cutoutCenterX == null || windowWidthPx <= 0 || isRtl) return DynamicIslandAnchor.CENTER
    return if (cutoutCenterX / windowWidthPx < DynamicIslandStartAnchorMaxFraction) {
        DynamicIslandAnchor.START
    } else {
        DynamicIslandAnchor.CENTER
    }
}

private fun embeddedGapFor(cutoutWidthPx: Int, density: Float): Dp =
    ((cutoutWidthPx / density).dp + (DynamicIslandEmbeddedGapSidePadding * 2))
        .coerceIn(DynamicIslandEmbeddedGapMinWidth, DynamicIslandEmbeddedGapMaxWidth)

fun dynamicIslandStartEdgePx(cutout: android.graphics.Rect, density: Float): Int =
    (cutout.left - DynamicIslandHoleSidePadding.value * density)
        .roundToInt()
        .coerceAtLeast((DynamicIslandScreenEdgeMargin.value * density).roundToInt())

private fun startAnchoredGapFor(cutout: android.graphics.Rect, density: Float): Dp {
    val edgePx = dynamicIslandStartEdgePx(cutout, density)
    val gapPx = cutout.right + DynamicIslandHoleSidePadding.value * density - edgePx
    return (gapPx / density).dp
}

/** Extra status bar window space; side cameras keep their upstream dedicated window. */
fun dynamicIslandWindowOverflowPx(context: Context, statusBarHeight: Int): Int {
    val overflow =
        context.resources.getDimensionPixelSize(R.dimen.status_bar_window_island_overflow)
    val density = context.resources.displayMetrics.density
    val hole =
        resolveDynamicIslandTopCutout(context)
            ?: return maxOf(
                overflow,
                ceil(
                    (DynamicIslandDefaultCenterY.value +
                        DynamicIslandBaseHeight.value * DynamicIslandFeatureSettings.SCALE_MAX / 2f) *
                        density
                ).toInt() - statusBarHeight,
            )
    val minimumHeight = cutoutMinimumHeight((hole.height() / density).dp).value * density
    val maximumHeight =
        maxOf(
            minimumHeight,
            DynamicIslandBaseHeight.value * DynamicIslandFeatureSettings.SCALE_MAX * density,
        )
    val padding = DynamicIslandHoleSidePadding.value * density
    val bottom = ceil(hole.exactCenterY() + maximumHeight / 2f + padding).toInt()
    return maxOf(overflow, bottom - statusBarHeight)
}

private data class DynamicIslandCutoutSnapshot(
    val topCutout: android.graphics.Rect?,
    val heightCutout: android.graphics.Rect?,
    val windowWidthPx: Int,
    val isRtl: Boolean,
)

private fun readDynamicIslandCutoutSnapshot(view: View): DynamicIslandCutoutSnapshot {
    val topCutout =
        resolveDynamicIslandCutoutEdge(view)?.takeIf { it.edge == DynamicIslandEdge.TOP }?.hole
    val heightCutout = resolveDynamicIslandTopCutout(view)
    val windowWidthPx =
        when {
            view.rootView.width > 0 -> view.rootView.width
            view.width > 0 -> view.width
            else -> view.resources.configuration.windowConfiguration.maxBounds.width()
        }
    return DynamicIslandCutoutSnapshot(
        topCutout = topCutout,
        heightCutout = heightCutout,
        windowWidthPx = windowWidthPx,
        isRtl = view.layoutDirection == View.LAYOUT_DIRECTION_RTL,
    )
}

@Composable
fun rememberDynamicIslandCutoutSpec(): DynamicIslandCutoutSpec {
    val density = LocalDensity.current
    val view = LocalView.current
    var windowLayout by remember { mutableIntStateOf(0) }
    DisposableEffect(view) {
        val root = view.rootView
        val listener =
            View.OnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
                if (r - l != oldR - oldL || b - t != oldB - oldT) windowLayout++
            }
        root.addOnLayoutChangeListener(listener)
        onDispose { root.removeOnLayoutChangeListener(listener) }
    }
    @Suppress("UNUSED_EXPRESSION") windowLayout
    var snapshot by remember { mutableStateOf(readDynamicIslandCutoutSnapshot(view)) }
    DisposableEffect(view) {
        val listener =
            View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                val updated = readDynamicIslandCutoutSnapshot(view)
                if (updated != snapshot) snapshot = updated
            }
        view.rootView.addOnLayoutChangeListener(listener)
        snapshot = readDynamicIslandCutoutSnapshot(view)
        onDispose { view.rootView.removeOnLayoutChangeListener(listener) }
    }

    val topCutout = snapshot.topCutout
    val rootWidthPx = snapshot.windowWidthPx

    return with(density) {
        if (topCutout == null || rootWidthPx <= 0) {
            DynamicIslandCutoutSpec(
                embeddedGapWidth = DynamicIslandEmbeddedGapFallbackWidth,
                horizontalOffset = 0.dp,
                holeHeight = snapshot.heightCutout?.height()?.toDp() ?: 20.dp,
                holeCenterY = snapshot.heightCutout?.exactCenterY()?.toDp(),
                minimumHeight = snapshot.heightCutout?.let { cutoutMinimumHeight(it.height().toDp()) }
                    ?: 0.dp,
            )
        } else {
            val minimumHeight = cutoutMinimumHeight(topCutout.height().toDp())
            val anchor =
                resolveDynamicIslandAnchor(topCutout.exactCenterX(), rootWidthPx, snapshot.isRtl)
            val fraction = topCutout.exactCenterX() / rootWidthPx
            val centreOnCamera =
                anchor == DynamicIslandAnchor.CENTER &&
                    fraction in DynamicIslandCenterMinFraction..DynamicIslandCenterMaxFraction
            val horizontalOffsetDp =
                if (centreOnCamera) {
                    (topCutout.exactCenterX() - (rootWidthPx / 2f)).toDp()
                } else {
                    0.dp
                }
            DynamicIslandCutoutSpec(
                embeddedGapWidth =
                    if (anchor == DynamicIslandAnchor.START) {
                        startAnchoredGapFor(topCutout, this.density)
                    } else {
                        embeddedGapFor(topCutout.width(), this.density)
                    },
                horizontalOffset = horizontalOffsetDp,
                anchor = anchor,
                cutoutCenterX = if (centreOnCamera) topCutout.exactCenterX() else null,
                holeSize = topCutout.width().toDp(),
                holeHeight = topCutout.height().toDp(),
                holeCenterY = topCutout.exactCenterY().toDp(),
                minimumHeight = minimumHeight,
                minimumWidth = cutoutMinimumWidth(topCutout.width().toDp(), minimumHeight),
                cutoutWidth = topCutout.width().toDp(),
            )
        }
    }
}

/**
 * Spec for an island docked to a camera on a side edge. The pill is laid out in its own rotated
 * frame, where its main axis runs along the edge, so the camera gap is the hole's height.
 */
@Composable
fun rememberDynamicIslandSideCutoutSpec(edge: DynamicIslandCutoutEdge): DynamicIslandCutoutSpec {
    val density = LocalDensity.current.density
    return remember(edge, density) {
        val minimumHeight = cutoutMinimumHeight((edge.hole.width() / density).dp)
        DynamicIslandCutoutSpec(
            embeddedGapWidth = embeddedGapFor(edge.hole.height(), density),
            horizontalOffset = 0.dp,
            minimumHeight = minimumHeight,
            minimumWidth = cutoutMinimumWidth((edge.hole.height() / density).dp, minimumHeight),
            cutoutWidth = (edge.hole.height() / density).dp,
        )
    }
}

private fun DisplayCutout.topBoundingRectOrNull() =
    getBoundingRectTop().takeUnless { it.isEmpty }

fun DisplayCutout.dynamicIslandHoleRect(): android.graphics.Rect? =
    topBoundingRectOrNull()?.let { dynamicIslandHoleWithin(it) }

/**
 * The camera hole inside [boundingRect], a bounding rect of this cutout on any edge. The cutout
 * path is clipped to [boundingRect] first, so a display with several holes (a dual punch-hole, or
 * a hole plus a notch) reports only the one on this edge instead of the bounds of all of them.
 */
fun DisplayCutout.dynamicIslandHoleWithin(
    boundingRect: android.graphics.Rect
): android.graphics.Rect {
    val path = cutoutPath ?: return android.graphics.Rect(boundingRect)
    val clip =
        android.graphics.Path().apply {
            addRect(android.graphics.RectF(boundingRect), android.graphics.Path.Direction.CW)
        }
    val clipped = android.graphics.Path(path)
    if (!clipped.op(clip, android.graphics.Path.Op.INTERSECT) || clipped.isEmpty) {
        return android.graphics.Rect(boundingRect)
    }
    val pathBounds = android.graphics.RectF()
    clipped.computeBounds(pathBounds, true)
    if (pathBounds.isEmpty) return android.graphics.Rect(boundingRect)
    val hole = android.graphics.Rect()
    pathBounds.roundOut(hole)
    return hole
}
@Composable
internal fun rememberDynamicIslandSizeScale(): Pair<Float, Float> {
    val context = LocalContext.current
    val widthScale by
        remember {
            DynamicIslandFeatureSettings.observeDynamicIslandScale(
                context,
                DynamicIslandFeatureSettings.WIDTH_SCALE,
            )
        }.collectAsState(initial = 1f)
    val heightScale by
        remember {
            DynamicIslandFeatureSettings.observeDynamicIslandScale(
                context,
                DynamicIslandFeatureSettings.HEIGHT_SCALE,
            )
        }.collectAsState(initial = 1f)
    return widthScale to heightScale
}

internal fun LayoutCoordinates.islandBoundsInScreen(view: android.view.View): Rect {
    val location = IntArray(2)
    view.getLocationOnScreen(location)
    return findRootCoordinates().localBoundingBoxOf(this, clipBounds = false)
        .translate(Offset(location[0].toFloat(), location[1].toFloat()))
}

internal fun LayoutCoordinates.islandBoundsInWindow(): Rect {
    val root = findRootCoordinates()
    return root.localBoundingBoxOf(this, clipBounds = false).translate(root.positionInWindow())
}

@Composable
internal fun rememberDynamicIslandWidth(cutoutSpec: DynamicIslandCutoutSpec): Dp {
    val style = MaterialTheme.typography.labelLarge
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val context = LocalContext.current
    val full = context.getString(R.string.dynamic_island_charging_full)
    val flashlight = context.getString(R.string.dynamic_island_flashlight_short)
    val sideBudget = remember(style, measurer, density, full, flashlight) {
        fun textWidth(text: String): Dp =
            (measurer.measure(
                text = text,
                style = style,
                density = density,
                softWrap = false,
                maxLines = 1,
            ).multiParagraph.maxIntrinsicWidth / density.density).dp
        val startText = listOf("+99:59", "999.99W", flashlight).maxOf { textWidth(it) }
        val endText = listOf("+99:59", "99:59", "100%", full).maxOf { textWidth(it) }
        maxOf(16.dp + 6.dp + startText, endText)
    }
    return remember(sideBudget, cutoutSpec.cutoutWidth) {
        val clearance = DynamicIslandEmbeddedGapSidePadding * 2
        val width = (sideBudget + 10.dp) * 2 + cutoutSpec.cutoutWidth + clearance
        // A fixed 184dp ceiling clips the utility labels at larger font scales.
        (ceil(width.value / 4f) * 4f).dp.coerceAtLeast(120.dp)
    }
}

/**
 * The page indicator of the normal style popup: a pill of dots beside the card. The touch target is
 * at least [PageDotsTouchSize] square and the pill sits at the edge of it that faces the card, so
 * the popup's gap to the card is the visible one. [cardOnLeft] is true when the card is to the left
 * of the dots. [onClickLabel] names what a tap does for accessibility services.
 */
@Composable
internal fun PageDots(
    count: Int,
    index: Int,
    color: Color,
    onClick: () -> Unit,
    cardOnLeft: Boolean = true,
    onClickLabel: String? = null,
) {
    val visible = minOf(count, MaxPageDots)
    val first = (index - visible / 2).coerceIn(0, count - visible)
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier =
            Modifier.defaultMinSize(minWidth = PageDotsTouchSize, minHeight = PageDotsTouchSize)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.Button,
                    onClickLabel = onClickLabel,
                    onClick = onClick,
                ),
        contentAlignment = if (cardOnLeft) Alignment.CenterStart else Alignment.CenterEnd,
    ) {
        Column(
            modifier =
                Modifier.clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f))
                    // The press shows on the pill, not on the whole touch target.
                    .indication(interactionSource, LocalIndication.current)
                    .padding(horizontal = PageDotsTapPadding, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(PageDotSpacing),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            repeat(visible) { slot ->
                val isCurrent = first + slot == index
                val height by
                    animateDpAsState(
                        targetValue = if (isCurrent) PageDotActiveHeight else PageDotSize,
                        label = "pageDotHeight",
                    )
                Box(
                    modifier =
                        Modifier.size(width = PageDotSize, height = height)
                            .background(
                                color = color.copy(alpha = if (isCurrent) 0.9f else 0.45f),
                                shape = CircleShape,
                            )
                )
            }
        }
    }
}

private const val MaxPageDots = 5
private val PageDotSize = 4.dp
private val PageDotActiveHeight = 10.dp
private val PageDotSpacing = 3.dp
private val PageDotsTapPadding = 4.dp
private val PageDotsTouchSize = 48.dp

/** Single centered status bar capsule styled like a compact dynamic island. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StatusBarDynamicIslandChip(
    viewModel: QuickActionChipModel.PopupChip,
    pageCount: Int,
    cutoutSpec: DynamicIslandCutoutSpec,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    pageIndex: Int = 0,
    onNextPage: () -> Unit = {},
    onChipBoundsChanged: (Rect) -> Unit = {},
    /** Draw for the ColorOS island shell, which supplies the capsule, instead of our own. */
    cos: Boolean = false,
) =
    IslandChip(
        viewModel = viewModel,
        pageCount = pageCount,
        cutoutSpec = cutoutSpec,
        onTap = onTap,
        modifier = modifier,
        pageIndex = pageIndex,
        onNextPage = onNextPage,
        onChipBoundsChanged = onChipBoundsChanged,
        cos = cos,
    )
