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

import android.view.DisplayCutout
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.systemui.common.shared.model.Icon as IconModel
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.res.R
import com.android.systemui.statusbar.quickactions.popups.shared.DynamicIslandFeatureSettings
import com.android.systemui.statusbar.quickactions.popups.shared.model.SystemEventKind
import com.android.systemui.statusbar.quickactions.screenrecord.shared.model.ScreenRecordPopupModel
import com.android.systemui.statusbar.quickactions.popups.shared.model.SystemEventKind
import com.android.systemui.statusbar.quickactions.shared.model.PopupContentModel
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipModel
import com.android.systemui.statusbar.quickactions.shared.model.chipText
import kotlinx.coroutines.coroutineScope
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

import com.android.systemui.statusbar.quickactions.ui.compose.*

/** Single centered status bar capsule styled like a compact dynamic island. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun IslandChip(
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
) {
    val isMediaChip = viewModel.popupContent is PopupContentModel.Media
    val chipShape = RoundedCornerShape(50)
    val cosMedia = (viewModel.popupContent as? PopupContentModel.Media)?.takeIf { cos }?.model
    val cosArtTint =
        if (cosMedia != null) {
            rememberCosArtTint(
                remember(cosMedia.artworkIcon) { (cosMedia.artworkIcon as? IconModel.Loaded)?.drawable }
            )
        } else {
            Color.Transparent
        }
    val colors = viewModel.colors
    val (widthScale, heightScale) = rememberDynamicIslandSizeScale()
    val chipContentColor =
        colors.chipContent(
            isSelected = viewModel.isPopupShown,
            colorScheme = MaterialTheme.colorScheme,
        )
    val chipOutline =
        colors.chipOutline(
            isSelected = viewModel.isPopupShown,
            colorScheme = MaterialTheme.colorScheme,
        )
    val view = LocalView.current
    val boundsModifier =
        Modifier.onGloballyPositioned { coordinates ->
            onChipBoundsChanged(coordinates.boundsInScreen(view))
        }
    val hapticOnTap: () -> Unit = {
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        onTap()
    }
    val mediaOpenApp: (() -> Unit)? =
        (viewModel.popupContent as? PopupContentModel.Media)
            ?.takeIf { it.model.isPlaying }
            ?.model
            ?.openApp
    val hapticOnLongPress: () -> Unit = {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        mediaOpenApp?.invoke()
    }
    if (!cos && viewModel.popupContent.isUtilityStatusContent() && viewModel.icons.isNotEmpty()) {
        UtilityStatusIslandChip(
            viewModel = viewModel,
            onTap = hapticOnTap,
            pageCount = pageCount,
            pageIndex = pageIndex,
            onNextPage = onNextPage,
            cutoutSpec = cutoutSpec,
            widthScale = widthScale,
            heightScale = heightScale,
            chipContentColor = chipContentColor,
            chipOutline = chipOutline,
            modifier = modifier.then(boundsModifier),
        )
        return
    }

    val compactWidth = compactIslandWidthFor(viewModel.popupContent)?.times(widthScale)
    val hasInlineTimer = viewModel.popupContent is PopupContentModel.Stopwatch
    val trailingDecorationWidth =
        when (val popupContent = viewModel.popupContent) {
            is PopupContentModel.Media ->
                if (popupContent.model.isPlaying) {
                    14.dp
                } else {
                    pageDotsWidth(pageCount)
                }
            is PopupContentModel.ScreenRecord -> pageDotsWidth(pageCount).coerceAtLeast(11.dp)
            else -> pageDotsWidth(pageCount)
        }
    val leadingDecorationWidth =
        when {
            viewModel.icons.isEmpty() -> 0.dp
            else -> 18.dp + (8.dp * (viewModel.icons.size - 1))
        }
    val maxTextWidth =
        ((CompactIslandMaxWidth * widthScale) - 24.dp - leadingDecorationWidth - trailingDecorationWidth)
            .coerceAtLeast(56.dp)

    val collapseState = rememberDynamicIslandCollapseState(viewModel.isPopupShown)
    val isStartAnchored = cutoutSpec.anchor == DynamicIslandAnchor.START
    val startGap = if (isStartAnchored) cutoutSpec.embeddedGapWidth else 0.dp

    // A chip centred on the camera keeps its icon and text on either side of the lens, so short
    // labels such as "REC" are not hidden behind it.
    val cameraGap =
        cutoutSpec.embeddedGapWidth.takeIf {
            !isMediaChip && !isStartAnchored && cutoutSpec.cutoutCenterX != null
        }

    CameraSplitRow(
        leadingCount = viewModel.icons.size,
        cameraGap = cameraGap,
        modifier =
            modifier
                .then(boundsModifier)
                .then(
                    if (cos) {
                        Modifier.height(
                                CosIsland.height(
                                    cutoutSpec.holeSize,
                                    heightScale,
                                    viewModel.isCallChip(),
                                )
                            )
                            .widthIn(
                                min =
                                    if (viewModel.isCallChip()) CosIsland.CallMinWidth
                                    else CosIsland.MinWidth,
                                max = CosIsland.MaxWidth,
                            )
                            // The tint is inside the capsule, not a box over its rounded ends.
                            .background(cosArtTint, RoundedCornerShape(50))
                    } else {
                        Modifier.defaultMinSize(minHeight = 32.dp * heightScale)
                            .widthIn(
                                min = if (isStartAnchored) startGap else (compactWidth ?: 0.dp),
                                max =
                                    (compactWidth ?: (CompactIslandMaxWidth * widthScale)) +
                                        startGap,
                            )
                            .graphicsLayer {
                                scaleX = collapseState.scale
                                if (isStartAnchored) transformOrigin = TransformOrigin(0f, 0.5f)
                            }
                            .clip(chipShape)
                            .background(Color.Black)
                            .border(width = 1.dp, color = chipOutline, shape = chipShape)
                    }
                )
                .combinedClickable(
                    onClick = hapticOnTap,
                    onLongClick = mediaOpenApp?.let { { hapticOnLongPress() } },
                )
                .then(
                    if (cos) {
                        Modifier.padding(horizontal = CosIsland.Padding)
                    } else {
                        Modifier.padding(
                                start = if (isStartAnchored) startGap else 10.dp * widthScale,
                                end = if (isStartAnchored) 8.dp * widthScale else 12.dp * widthScale,
                                top = 7.dp * heightScale,
                                bottom = 7.dp * heightScale,
                            )
                            .graphicsLayer { alpha = collapseState.contentAlpha }
                    }
                ),
        horizontalArrangement =
            if (isMediaChip && !isStartAnchored) {
                Arrangement.SpaceBetween
            } else {
                Arrangement.spacedBy(8.dp)
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        viewModel.icons.forEachIndexed { index, chipIcon ->
            val isArtworkLike =
                index == 0 &&
                    (viewModel.popupContent is PopupContentModel.Media ||
                        viewModel.popupContent is PopupContentModel.LiveScore)
            UprightContent {
                Icon(
                    icon = chipIcon.icon,
                    modifier = Modifier
                        .size(
                            when {
                                cos && isArtworkLike -> CosIsland.IconSize
                                cos -> CosIsland.IconSize * 0.8f
                                isArtworkLike -> 18.dp
                                else -> 16.dp
                            }
                        )
                        .then(
                            if (isArtworkLike) {
                                Modifier.clip(CircleShape)
                            } else {
                                Modifier
                            }
                        ),
                    tint = if (isArtworkLike) Color.Unspecified else chipContentColor,
                )
            }
        }

        viewModel.chipText
            ?.takeIf {
                !isMediaChip &&
                    viewModel.popupContent !is PopupContentModel.ScreenRecord &&
                    !hasInlineTimer &&
                    it.isNotBlank()
            }
            ?.let { text ->
                UprightContent {
                    Text(
                        text = text,
                        style =
                            if (cos) {
                                MaterialTheme.typography.labelLarge.copy(
                                    fontSize = CosIsland.TextSize,
                                    fontWeight = FontWeight.Medium,
                                )
                            } else {
                                MaterialTheme.typography.labelLarge
                            },
                        color = if (cos) CosIsland.TextColor else chipContentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier =
                            Modifier.widthIn(
                                max =
                                    if (isStartAnchored) minOf(maxTextWidth, 88.dp)
                                    else maxTextWidth
                            ),
                    )
                }
            }

        // A connected Bluetooth device shows its Fast Pair artwork after the glyph, which puts it
        // on the far side of the lens when the island is centred on a camera.
        (viewModel.popupContent as? PopupContentModel.SystemEvent)
            ?.takeIf { it.kind == SystemEventKind.Bluetooth }
            ?.image
            ?.let { artwork ->
                UprightContent {
                    Icon(
                        icon = artwork,
                        tint = Color.Unspecified,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

        when (val popupContent = viewModel.popupContent) {
            is PopupContentModel.Media -> {
                val artworkDrawable =
                    remember(popupContent.model.artworkIcon) {
                        (popupContent.model.artworkIcon as? IconModel.Loaded)?.drawable
                    }
                if (popupContent.model.isPlaying) {
                    UprightContent {
                        AudioReactiveBars(
                            isPlaying = true,
                            color = chipContentColor,
                            artworkDrawable = artworkDrawable,
                        )
                    }
                } else if (pageCount > 1) {
                    UprightContent {
                        PageDots(
                            count = pageCount,
                            index = pageIndex,
                            color = chipContentColor,
                            onClick = onNextPage,
                        )
                    }
                }
            }
            is PopupContentModel.ScreenRecord ->
                when (val model = popupContent.model) {
                    is ScreenRecordPopupModel.Starting ->
                        StatusContent(
                            text = viewModel.chipText.orEmpty(),
                            color = chipContentColor,
                            pageCount = pageCount,
                            pageIndex = pageIndex,
                            onNextPage = onNextPage,
                        )
                    is ScreenRecordPopupModel.Recording ->
                        StatusContent(
                            text = viewModel.chipText.orEmpty(),
                            color = chipContentColor,
                            pageCount = pageCount,
                            pageIndex = pageIndex,
                            onNextPage = onNextPage,
                        )
                }
            is PopupContentModel.Stopwatch ->
                StatusContent(
                    text =
                        popupContent.model.elapsedTimeText
                            ?: rememberElapsedDurationText(
                                popupContent.model.baseElapsedRealtimeMs
                            ),
                    color = chipContentColor,
                    pageCount = pageCount,
                    pageIndex = pageIndex,
                    onNextPage = onNextPage,
                )
            else -> {
                if (pageCount > 1) {
                    UprightContent {
                        PageDots(
                            count = pageCount,
                            index = pageIndex,
                            color = chipContentColor,
                            onClick = onNextPage,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UtilityStatusIslandChip(
    viewModel: QuickActionChipModel.PopupChip,
    onTap: () -> Unit,
    pageCount: Int,
    pageIndex: Int,
    onNextPage: () -> Unit,
    cutoutSpec: DynamicIslandCutoutSpec,
    widthScale: Float = 1f,
    heightScale: Float = 1f,
    chipContentColor: Color,
    chipOutline: Color,
    modifier: Modifier = Modifier,
) {
    val collapseState = rememberDynamicIslandCollapseState(viewModel.isPopupShown)
    val isStartAnchored = cutoutSpec.anchor == DynamicIslandAnchor.START
    val contentTint = viewModel.contentTint ?: chipContentColor
    val startText =
        when (val popupContent = viewModel.popupContent) {
            is PopupContentModel.Alarm ->
                rememberAlarmCountdownText(popupContent.model.triggerTimeMs)
            else -> viewModel.startText
        }
    val dotsWidth = pageDotsWidth(pageCount)
    val rightSegmentWidth =
        (when (viewModel.popupContent) {
            is PopupContentModel.Flashlight -> 52.dp
            is PopupContentModel.Alarm -> 72.dp
            is PopupContentModel.Charging -> 56.dp
            else -> 80.dp
        }) * widthScale
    val connectedIslandWidth =
        ((CompactUtilityConnectedIslandChromeWidth * widthScale) +
                cutoutSpec.embeddedGapWidth +
                rightSegmentWidth +
                dotsWidth +
                (if (startText != null) CompactUtilityStartTextWidth * widthScale else 0.dp))
            .coerceIn(
                CompactUtilityConnectedIslandMinWidth * widthScale,
                CompactUtilityConnectedIslandMaxWidth * widthScale,
            )
    // Centred on a camera, the two sides have to be as wide as each other, or the gap drifts off
    // the lens towards the shorter side and the text ends up behind it.
    val splitAroundCamera = !isStartAnchored && cutoutSpec.cutoutCenterX != null
    val utilityText =
        when (val popupContent = viewModel.popupContent) {
            is PopupContentModel.ScreenRecord ->
                when (val model = popupContent.model) {
                    is ScreenRecordPopupModel.Starting -> "${model.secondsUntilStarted}s"
                    is ScreenRecordPopupModel.Recording ->
                        rememberElapsedDurationText(model.startElapsedRealtimeMs)
                }
            is PopupContentModel.Stopwatch ->
                popupContent.model.elapsedTimeText
                    ?: rememberElapsedDurationText(popupContent.model.baseElapsedRealtimeMs)
            is PopupContentModel.Alarm -> viewModel.chipText.orEmpty()
            is PopupContentModel.Charging -> viewModel.chipText.orEmpty()
            is PopupContentModel.Flashlight -> viewModel.chipText.orEmpty()
            else -> ""
        }

    val icon: @Composable () -> Unit = {
        UprightContent {
            Icon(
                icon = viewModel.icons.first().icon,
                modifier = Modifier.size(16.dp),
                tint = contentTint,
            )
        }
    }
    val startLabel: @Composable () -> Unit = {
        if (startText != null) {
            UprightContent {
                Text(
                    text = startText,
                    style = MaterialTheme.typography.labelLarge,
                    color = contentTint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    val pageDots: @Composable () -> Unit = {
        if (pageCount > 1) {
            UprightContent {
                PageDots(
                    count = pageCount,
                    index = pageIndex,
                    color = chipContentColor,
                    onClick = onNextPage,
                )
            }
        }
    }

    CameraSplitRow(
        leadingCount = if (startText != null) 2 else 1,
        cameraGap = cutoutSpec.embeddedGapWidth.takeIf { splitAroundCamera },
        modifier =
            modifier
                .graphicsLayer {
                    scaleX = collapseState.scale
                    if (isStartAnchored) transformOrigin = TransformOrigin(0f, 0.5f)
                }
                .defaultMinSize(minHeight = 32.dp * heightScale)
                .then(
                    when {
                        isStartAnchored -> Modifier
                        splitAroundCamera ->
                            Modifier.widthIn(
                                min = CompactUtilityConnectedIslandMinWidth * widthScale,
                                max = CompactUtilityCameraSplitMaxWidth * widthScale,
                            )
                        else -> Modifier.width(connectedIslandWidth)
                    }
                )
                .clip(RoundedCornerShape(50))
                .background(Color.Black)
                .border(width = 1.dp, color = chipOutline, shape = RoundedCornerShape(50))
                .clickable(onClick = onTap)
                .graphicsLayer { alpha = collapseState.contentAlpha }
                .then(
                    if (splitAroundCamera) Modifier.padding(horizontal = 10.dp * widthScale)
                    else Modifier
                ),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (splitAroundCamera) {
            icon()
            startLabel()
            UprightContent {
                Text(
                    text = utilityText,
                    style = MaterialTheme.typography.labelLarge,
                    color = contentTint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            pageDots()
        } else if (isStartAnchored) {
            Spacer(modifier = Modifier.width(cutoutSpec.embeddedGapWidth))
            icon()
            if (startText != null) {
                Spacer(modifier = Modifier.width(6.dp * widthScale))
                startLabel()
            }
            Spacer(modifier = Modifier.width(6.dp * widthScale))
            UprightContent {
                Text(
                    text = utilityText,
                    style = MaterialTheme.typography.labelLarge,
                    color = contentTint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Start,
                    modifier =
                        Modifier.widthIn(min = 36.dp * widthScale, max = rightSegmentWidth)
                            .padding(vertical = 7.dp * heightScale),
                )
            }
            if (pageCount > 1) Spacer(modifier = Modifier.width(4.dp * widthScale))
            pageDots()
            Spacer(modifier = Modifier.width(10.dp * widthScale))
        } else {
            Spacer(modifier = Modifier.width(10.dp * widthScale))
            icon()
            if (startText != null) {
                Spacer(modifier = Modifier.width(6.dp * widthScale))
                startLabel()
            }
            Spacer(modifier = Modifier.width(cutoutSpec.embeddedGapWidth))
            UprightContent {
                Row(
                    modifier =
                        Modifier.width(rightSegmentWidth + dotsWidth)
                            .padding(
                                start = 6.dp * widthScale,
                                top = 7.dp * heightScale,
                                bottom = 7.dp * heightScale,
                                end = 6.dp * widthScale,
                            ),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = utilityText,
                        style = MaterialTheme.typography.labelLarge,
                        color = contentTint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    pageDots()
                }
            }
            Spacer(modifier = Modifier.width(10.dp * widthScale))
        }
    }
}

/**
 * The time left until the alarm as short text such as `in 7h 5m`, kept current once a minute. Null
 * when there is no upcoming alarm to count down to, e.g. while one is ringing.
 */
@Composable
private fun rememberAlarmCountdownText(triggerTimeMs: Long): String? {
    val context = LocalContext.current
    val now by
        produceState(System.currentTimeMillis(), triggerTimeMs) {
            while (triggerTimeMs > 0L) {
                delay(AlarmCountdownTickMs)
                value = System.currentTimeMillis()
            }
        }
    if (triggerTimeMs <= 0L) return null
    val minutes = ((triggerTimeMs - now + 59_999L) / 60_000L).toInt()
    if (minutes <= 0) return null
    val hours = minutes / 60
    return when {
        minutes < 60 -> context.getString(R.string.dynamic_island_alarm_in_minutes, minutes)
        minutes < 24 * 60 && minutes % 60 == 0 ->
            context.getString(R.string.dynamic_island_alarm_in_hours, hours)
        minutes < 24 * 60 ->
            context.getString(R.string.dynamic_island_alarm_in_hours_minutes, hours, minutes % 60)
        else -> context.getString(R.string.dynamic_island_alarm_in_days, minutes / (24 * 60))
    }
}

@Composable
private fun StatusContent(
    text: String,
    color: Color,
    pageCount: Int,
    pageIndex: Int,
    onNextPage: () -> Unit,
) {
    UprightContent {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = color,
                maxLines = 1,
            )
            if (pageCount > 1) {
                PageDots(count = pageCount, index = pageIndex, color = color, onClick = onNextPage)
            }
        }
    }
}

/** Width of [PageDots] for [count] pages, including the padding that makes it tappable. */
private fun pageDotsWidth(count: Int): Dp {
    if (count <= 1) return 0.dp
    val visible = minOf(count, MaxPageDots)
    return PageDotActiveWidth +
        (PageDotSize + PageDotSpacing) * (visible - 1) +
        PageDotsTapPadding * 2
}

/**
 * One dot per stacked chip with the current one stretched, so it shows how many chips are stacked
 * and where this one is among them. A tap goes to the next chip. With more than [MaxPageDots]
 * chips, the dots follow the current one.
 */
@Composable
private fun CameraSplitRow(
    leadingCount: Int,
    cameraGap: Dp?,
    modifier: Modifier,
    horizontalArrangement: Arrangement.Horizontal,
    verticalAlignment: Alignment.Vertical,
    content: @Composable () -> Unit,
) {
    if (cameraGap == null) {
        Row(
            modifier = modifier,
            horizontalArrangement = horizontalArrangement,
            verticalAlignment = verticalAlignment,
        ) {
            content()
        }
        return
    }
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val gapPx = cameraGap.roundToPx()
        val spacingPx = CameraSplitSpacing.roundToPx()
        val sideMaxPx = ((constraints.maxWidth - gapPx) / 2).coerceAtLeast(0)

        fun measureSide(items: List<Measurable>): List<Placeable> {
            var remaining = sideMaxPx
            return items.map { item ->
                item
                    .measure(
                        Constraints(
                            maxWidth = remaining.coerceAtLeast(0),
                            maxHeight = constraints.maxHeight,
                        )
                    )
                    .also { remaining -= it.width + spacingPx }
            }
        }

        fun List<Placeable>.span() =
            if (isEmpty()) 0 else sumOf { it.width } + spacingPx * (size - 1)

        val leading = measureSide(measurables.take(leadingCount))
        val trailing = measureSide(measurables.drop(leadingCount))
        val side = maxOf(leading.span(), trailing.span())
        val width = (side * 2 + gapPx).coerceIn(constraints.minWidth, constraints.maxWidth)
        val height =
            (leading + trailing)
                .maxOfOrNull { it.height }
                ?.coerceIn(constraints.minHeight, constraints.maxHeight)
                ?: constraints.minHeight
        layout(width, height) {
            var x = 0
            leading.forEach {
                it.placeRelative(x, (height - it.height) / 2)
                x += it.width + spacingPx
            }
            x = width
            trailing.asReversed().forEach {
                x -= it.width
                it.placeRelative(x, (height - it.height) / 2)
                x -= spacingPx
            }
        }
    }
}

private val CompactUtilityStartTextWidth = 36.dp
private val CompactIslandMaxWidth = 192.dp
private val CompactMediaIslandWidth = 108.dp
private val CompactTimerIslandWidth = 116.dp
private val CompactRecordingIslandWidth = 88.dp
private val CompactAlarmIslandWidth = 92.dp
private val CompactUtilityIslandWidth = 74.dp
private val CompactUtilityConnectedIslandChromeWidth = 42.dp
private val CompactUtilityConnectedIslandMinWidth = 132.dp
private val CompactUtilityConnectedIslandMaxWidth = 188.dp
private val CompactUtilityCameraSplitMaxWidth = 232.dp

private fun PopupContentModel.isUtilityStatusContent(): Boolean {
    return this is PopupContentModel.ScreenRecord ||
        this is PopupContentModel.Stopwatch ||
        this is PopupContentModel.Alarm ||
        this is PopupContentModel.Charging ||
        this is PopupContentModel.Flashlight
}

private fun compactIslandWidthFor(content: PopupContentModel): Dp? {
    return when (content) {
        is PopupContentModel.Media -> CompactMediaIslandWidth
        is PopupContentModel.ScreenRecord ->
            when (content.model) {
                is ScreenRecordPopupModel.Starting -> CompactTimerIslandWidth
                is ScreenRecordPopupModel.Recording -> CompactRecordingIslandWidth
            }
        is PopupContentModel.Stopwatch -> CompactTimerIslandWidth
        is PopupContentModel.Alarm -> CompactAlarmIslandWidth
        is PopupContentModel.Flashlight -> CompactUtilityIslandWidth
        is PopupContentModel.Charging -> CompactUtilityIslandWidth
        else -> null
    }
}


private data class DynamicIslandCollapseState(val scale: Float, val contentAlpha: Float)

@Composable
private fun rememberDynamicIslandCollapseState(isOpen: Boolean): DynamicIslandCollapseState {
    val scaleX = remember { Animatable(1f, visibilityThreshold = 0.0005f) }
    val currentIsOpen by rememberUpdatedState(isOpen)

    LaunchedEffect(Unit) {
        snapshotFlow { currentIsOpen }
            .drop(1)
            .collectLatest { open ->
                if (open) {
                    scaleX.animateTo(
                        targetValue = 0f,
                        animationSpec = DynamicIslandMotion.PillShrink,
                    )
                } else {
                    scaleX.animateTo(
                        targetValue = 1f,
                        animationSpec =
                            keyframes {
                               durationMillis = 380
                                0f at 0
                                1.08f at 240 using FastOutSlowInEasing
                                0.96f at 320
                                1f at 380
                            },
                    )
                }
            }
    }
    val fadeThreshold = 0.7f
    val alpha = (scaleX.value / fadeThreshold).coerceIn(0f, 1f)
    return DynamicIslandCollapseState(scale = scaleX.value, contentAlpha = alpha)
}

private fun LayoutCoordinates.boundsInScreen(view: android.view.View): Rect {
    val location = IntArray(2)
    view.getLocationOnScreen(location)
    return boundsInRoot().translate(Offset(location[0].toFloat(), location[1].toFloat()))
}

private const val MaxPageDots = 5
private val PageDotSize = 4.dp
private val PageDotActiveWidth = 10.dp
private val PageDotSpacing = 3.dp
private val PageDotsTapPadding = 4.dp
