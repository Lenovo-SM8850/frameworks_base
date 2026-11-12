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

import android.content.Context
import android.view.DisplayCutout
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
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
import com.android.systemui.statusbar.quickactions.popups.shared.DynamicIslandFeatureSettings
import com.android.systemui.statusbar.quickactions.popups.shared.model.SystemEventKind
import com.android.systemui.statusbar.quickactions.screenrecord.shared.model.ScreenRecordPopupModel
import com.android.systemui.statusbar.quickactions.shared.model.PopupContentModel
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipModel
import com.android.systemui.statusbar.quickactions.shared.model.chipText
import com.android.systemui.statusbar.quickactions.ui.compose.DynamicIslandAnchor
import com.android.systemui.statusbar.quickactions.ui.compose.DynamicIslandCutoutFillMargin
import com.android.systemui.statusbar.quickactions.ui.compose.DynamicIslandCutoutSpec
import com.android.systemui.statusbar.quickactions.ui.compose.DynamicIslandDefaultCenterY
import com.android.systemui.statusbar.quickactions.ui.compose.LocalDynamicIslandWidth
import com.android.systemui.statusbar.quickactions.ui.compose.LocalIslandContentProgress
import com.android.systemui.statusbar.quickactions.ui.compose.LocalIslandContentSpatialMotion
import com.android.systemui.statusbar.quickactions.ui.compose.PageDots
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import com.android.systemui.statusbar.quickactions.ui.compose.AudioReactiveBars
import com.android.systemui.statusbar.quickactions.ui.compose.CapsuleEnd
import com.android.systemui.statusbar.quickactions.ui.compose.capsuleEnds
import com.android.systemui.statusbar.quickactions.ui.compose.isCallChip
import com.android.systemui.statusbar.quickactions.ui.compose.rememberCosArtTint
import com.android.systemui.statusbar.quickactions.ui.compose.textCapHeight
import com.android.systemui.statusbar.quickactions.ui.compose.CosIsland
import com.android.systemui.statusbar.quickactions.ui.compose.UprightContent
import com.android.systemui.statusbar.quickactions.ui.compose.collapsedIslandTextStyle
import com.android.systemui.statusbar.quickactions.ui.compose.rememberDynamicIslandSizeScale
import com.android.systemui.statusbar.quickactions.ui.compose.rememberElapsedDurationText
import com.android.systemui.statusbar.quickactions.ui.compose.rememberRemainingDurationText
import com.android.systemui.statusbar.quickactions.ui.compose.islandBoundsInScreen
import com.android.systemui.statusbar.quickactions.ui.compose.DynamicIslandOutlineWidth
import com.android.systemui.statusbar.quickactions.ui.compose.AlarmCountdownTickMs
import com.android.systemui.statusbar.quickactions.ui.compose.CameraSplitSpacing

@Composable
private fun islandContentMotion(): Modifier {
    val progress = LocalIslandContentProgress.current
    val spatialMotion = LocalIslandContentSpatialMotion.current
    return Modifier.graphicsLayer {
        val fraction = progress().coerceIn(0f, 1f)
        alpha = fraction
        scaleX = if (spatialMotion) 0.92f + 0.08f * fraction else 1f
        scaleY = scaleX
    }
}

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
    val contentMotion = islandContentMotion()
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
            onChipBoundsChanged(coordinates.islandBoundsInScreen(view))
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
            cutoutSpec = cutoutSpec,
            widthScale = widthScale,
            heightScale = heightScale,
            chipContentColor = chipContentColor,
            chipOutline = chipOutline,
            modifier = modifier.then(boundsModifier),
        )
        return
    }

    val isStartAnchored = cutoutSpec.anchor == DynamicIslandAnchor.START
    val startGap = if (isStartAnchored) cutoutSpec.embeddedGapWidth else 0.dp
    val cameraGap =
        (if (cos) cutoutSpec.holeSize + 8.dp else cutoutSpec.embeddedGapWidth).takeIf {
            !isStartAnchored && (cos || cutoutSpec.cutoutCenterX != null)
        }

    BoxWithConstraints(modifier = modifier, propagateMinConstraints = true) {
        val textHeight = with(LocalDensity.current) {
            val height = (32.dp * heightScale)
                .coerceAtMost(maxHeight).roundToPx()
            (height - 2 * (7.dp * heightScale).roundToPx()).coerceAtLeast(1).toDp()
        }
        val density = LocalDensity.current
        val textStyle =
            if (cos) {
                MaterialTheme.typography.labelLarge.copy(
                    fontSize = CosIsland.TextSize,
                    fontWeight = FontWeight.Medium,
                )
            } else {
                collapsedIslandTextStyle(availableHeight = textHeight)
            }
        // Plain icons are smaller than artwork.
        val largeLeading = isMediaChip || viewModel.popupContent is PopupContentModel.LiveScore
        val leadingEnd =
            viewModel.icons.firstOrNull()?.let {
                CapsuleEnd.Round(
                    when {
                        cos && largeLeading -> CosIsland.IconSize
                        cos -> CosIsland.IconSize * 0.8f
                        largeLeading -> IslandGlyphSize
                        else -> 16.dp
                    }
                )
            }
        val trailingEnd =
            CapsuleEnd.Box(
                if (isMediaChip) MediaBarsHeight else density.textCapHeight(textStyle.fontSize)
            )
        CameraSplitRow(
            leadingCount = 1,
            cameraGap = cameraGap,
            modifier =
                Modifier
                    .then(boundsModifier)
                    .then(
                        if (cos) {
                            val cosHeight =
                                CosIsland.height(cutoutSpec.holeHeight, heightScale, viewModel.isCallChip())
                            Modifier.height(cosHeight)
                                .widthIn(
                                    min =
                                        if (viewModel.isCallChip()) CosIsland.CallMinWidth
                                        else CosIsland.MinWidth * widthScale,
                                    max = CosIsland.maxWidth(isStartAnchored) * widthScale,
                                )
                                // The tint is inside the capsule, not a box over its rounded ends.
                                .background(cosArtTint, RoundedCornerShape(50))
                        } else {
                            Modifier.defaultMinSize(
                                    minHeight = (32.dp * heightScale)
                                )
                                .width(
                                    maxOf(
                                        LocalDynamicIslandWidth.current * widthScale,
                                        cutoutSpec.minimumWidth,
                                    )
                                )
                                .clip(chipShape)
                                .background(Color.Black)
                                .border(
                                    width = DynamicIslandOutlineWidth,
                                    color = chipOutline,
                                    shape = chipShape,
                                )
                        }
                    )
                    .combinedClickable(
                        onClick = hapticOnTap,
                        onLongClick = mediaOpenApp?.let { { hapticOnLongPress() } },
                    )
                    // Before the vertical padding, so it sees the full capsule height.
                    .then(
                        if (cos)
                            Modifier.padding(
                                start =
                                    5.dp +
                                        if (isStartAnchored) cutoutSpec.holeSize + 8.dp else 0.dp,
                                end = CosIsland.ContentPadding,
                            )
                        else
                            Modifier.capsuleEnds(
                                leading = leadingEnd,
                                trailing = trailingEnd,
                                glyph =
                                    leadingEnd?.diameter
                                        ?: if (cos) CosIsland.IconSize else IslandGlyphSize,
                                symmetric = cameraGap != null,
                                fixedStart = if (cos) null else startGap.takeIf { isStartAnchored },
                            )
                    )
                    .then(
                        if (cos) Modifier else Modifier.padding(vertical = 7.dp * heightScale)
                    ),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                viewModel.icons.forEachIndexed { index, chipIcon ->
                    val isArtworkLike =
                        index == 0 && viewModel.popupContent is PopupContentModel.LiveScore
                    UprightContent {
                        if (index == 0 && isMediaChip) {
                            val mediaModel = (viewModel.popupContent as? PopupContentModel.Media)?.model
                            val isArtwork = mediaModel?.let {
                                it.artworkIcon != null && it.artworkIcon !== it.appIcon
                            } == true
                            CollapsedMediaArtwork(chipIcon.icon, isArtwork, contentMotion, cos)
                        } else {
                            Icon(
                                icon = chipIcon.icon,
                                modifier =
                                    Modifier.size(
                                            when {
                                                cos && isArtworkLike -> CosIsland.IconSize
                                                cos -> CosIsland.IconSize * 0.8f
                                                isArtworkLike -> 18.dp
                                                else -> 16.dp
                                            }
                                        )
                                        .then(contentMotion)
                                        .then(if (isArtworkLike) Modifier.clip(CircleShape) else Modifier),
                                tint = if (isArtworkLike) Color.Unspecified else chipContentColor,
                            )
                        }
                    }
                }
                (viewModel.popupContent as? PopupContentModel.SystemEvent)
                    ?.takeIf { it.kind == SystemEventKind.Bluetooth }
                    ?.image
                    ?.let { artwork ->
                        UprightContent {
                            Icon(
                                icon = artwork,
                                tint = Color.Unspecified,
                                modifier = Modifier.size(20.dp).then(contentMotion),
                            )
                        }
                    }
            }
            when (val popupContent = viewModel.popupContent) {
                is PopupContentModel.Media -> {
                    val artworkDrawable =
                        remember(popupContent.model.artworkIcon) {
                            (popupContent.model.artworkIcon as? IconModel.Loaded)?.drawable
                        }
                    UprightContent {
                        AudioReactiveBars(
                            isPlaying = popupContent.model.isPlaying,
                            color = chipContentColor,
                            artworkDrawable = artworkDrawable,
                            modifier = contentMotion,
                            maxBarHeight = MediaBarsHeight,
                            centered = cos,
                        )
                    }
                }
                else -> {
                    val text = collapsedIslandText(viewModel)
                    UprightContent {
                        Text(
                            text = text,
                            style = textStyle,
                            color = if (cos) CosIsland.TextColor else chipContentColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.End,
                            modifier = Modifier.then(contentMotion),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CollapsedMediaArtwork(
    icon: IconModel,
    isArtwork: Boolean,
    modifier: Modifier = Modifier,
    cos: Boolean = false,
) {
    if (!isArtwork) {
        Box(modifier.size(if (cos) CosIsland.IconSize else 18.dp), contentAlignment = Alignment.Center) {
            Icon(icon = icon, modifier = Modifier.size(16.dp), tint = Color.Unspecified)
        }
        return
    }
    Image(
        painter =
            when (icon) {
                is IconModel.Loaded -> rememberDrawablePainter(icon.drawable)
                is IconModel.Resource -> painterResource(icon.resId)
            },
        contentDescription = icon.contentDescription?.load(),
        contentScale = ContentScale.Crop,
        alignment = Alignment.Center,
        modifier =
            modifier.layout { measurable, constraints ->
                val size = minOf((if (cos) CosIsland.IconSize else 18.dp).roundToPx(), constraints.maxWidth, constraints.maxHeight)
                val child = measurable.measure(Constraints.fixed(size, size))
                val width = constraints.constrainWidth(size)
                val height = constraints.constrainHeight(size)
                layout(width, height) { child.place((width - size) / 2, (height - size) / 2) }
            }.clip(CircleShape),
    )
}

@Composable
private fun UtilityStatusIslandChip(
    viewModel: QuickActionChipModel.PopupChip,
    onTap: () -> Unit,
    cutoutSpec: DynamicIslandCutoutSpec,
    widthScale: Float = 1f,
    heightScale: Float = 1f,
    chipContentColor: Color,
    chipOutline: Color,
    modifier: Modifier = Modifier,
) {
    val isStartAnchored = cutoutSpec.anchor == DynamicIslandAnchor.START
    val contentTint = viewModel.contentTint ?: chipContentColor
    val startText =
        when (val content = viewModel.popupContent) {
            is PopupContentModel.Alarm -> rememberAlarmCountdownText(content.model.triggerTimeMs)
            else -> viewModel.startText
        }
    val splitAroundCamera = !isStartAnchored && cutoutSpec.cutoutCenterX != null
    val contentMotion = islandContentMotion()
    val startGap = if (isStartAnchored) cutoutSpec.embeddedGapWidth else 0.dp
    val utilityText = collapsedIslandText(viewModel)

    val icon: @Composable () -> Unit = {
        UprightContent {
            Icon(
                icon = viewModel.icons.first().icon,
                modifier = Modifier.size(16.dp).then(contentMotion),
                tint = contentTint,
            )
        }
    }
    BoxWithConstraints(modifier = modifier, propagateMinConstraints = true) {
        val textHeight = with(LocalDensity.current) {
            val height = (32.dp * heightScale)
                .coerceAtMost(maxHeight).roundToPx()
            (height - 2 * (7.dp * heightScale).roundToPx()).coerceAtLeast(1).toDp()
        }
        val density = LocalDensity.current
        val textStyle = collapsedIslandTextStyle(availableHeight = textHeight)
        CameraSplitRow(
            leadingCount = 1,
            cameraGap = cutoutSpec.embeddedGapWidth.takeIf { splitAroundCamera },
            modifier =
                Modifier
                    .defaultMinSize(minHeight = (32.dp * heightScale))
                    .width(maxOf(LocalDynamicIslandWidth.current * widthScale, cutoutSpec.minimumWidth))
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black)
                    .border(
                        width = DynamicIslandOutlineWidth,
                        color = chipOutline,
                        shape = RoundedCornerShape(50),
                    )
                    .clickable(onClick = onTap)
                    // Before the vertical padding, so it sees the full capsule height.
                    .capsuleEnds(
                        leading = CapsuleEnd.Round(16.dp),
                        trailing = CapsuleEnd.Box(density.textCapHeight(textStyle.fontSize)),
                        glyph = 16.dp,
                        symmetric = splitAroundCamera,
                        fixedStart = startGap.takeIf { isStartAnchored },
                    )
                    .padding(vertical = 7.dp * heightScale),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                icon()
                if (startText != null) {
                    UprightContent {
                        Text(
                            text = startText,
                            style = textStyle,
                            color = contentTint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = contentMotion,
                        )
                    }
                }
            }
            UprightContent {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = utilityText,
                        style = textStyle,
                        color = contentTint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                        modifier = contentMotion.wrapContentHeight(Alignment.CenterVertically),
                    )
                }
            }
        }
    }
}

@Composable
private fun collapsedIslandText(viewModel: QuickActionChipModel.PopupChip): String =
    when (val content = viewModel.popupContent) {
        is PopupContentModel.ScreenRecord ->
            compactIslandDurationText(
                when (val model = content.model) {
                    is ScreenRecordPopupModel.Starting ->
                        "0:${model.secondsUntilStarted.toString().padStart(2, '0')}"
                    is ScreenRecordPopupModel.Recording ->
                        rememberElapsedDurationText(model.startElapsedRealtimeMs)
                }
            )
        is PopupContentModel.Stopwatch ->
            compactIslandDurationText(
                content.model.elapsedTimeText
                    ?: rememberElapsedDurationText(content.model.baseElapsedRealtimeMs)
            )
        is PopupContentModel.Timer ->
            compactIslandDurationText(
                content.model.remainingTimeText
                    ?: rememberRemainingDurationText(content.model.endElapsedRealtimeMs)
            )
        is PopupContentModel.Alarm ->
            viewModel.chipText.orEmpty()
        is PopupContentModel.SystemEvent -> {
            val startMs = content.callStartTimeMs.takeIf { content.kind == SystemEventKind.Call }
            if (startMs != null) {
                val base = remember(startMs) {
                    android.os.SystemClock.elapsedRealtime() -
                        (System.currentTimeMillis() - startMs).coerceAtLeast(0L)
                }
                compactIslandDurationText(rememberElapsedDurationText(base))
            } else {
                viewModel.chipText.orEmpty()
            }
        }
        else -> viewModel.chipText.orEmpty()
    }

private val CompactIslandDurationPattern = Regex("""([\p{Nd}]+):([\p{Nd}]{2})(?::([\p{Nd}]{2}))?""")

private fun compactIslandDurationText(text: String): String {
    val match = CompactIslandDurationPattern.find(text) ?: return text.take(5)
    val fields = match.groupValues.drop(1).map { field ->
        field.fold(0L) { value, digit ->
            (value * 10L + digit.digitToInt()).coerceAtMost(10_000L)
        }
    }
    val hasHours = match.groupValues[3].isNotEmpty()
    val totalMinutes = if (hasHours) fields[0] * 60L + fields[1] else fields[0]
    val hours = totalMinutes / 60L
    val sign = if (text.trimStart().startsWith("+")) "+" else ""
    if (hours >= 100L) return "${sign}99:59"
    val first = if (hours > 0L) hours else totalMinutes
    val second =
        if (hours > 0L) totalMinutes % 60L
        else if (hasHours) fields[2] else fields[1]
    return "$sign${first.toString().padStart(2, '0')}:${second.toString().padStart(2, '0')}"
}

/** Compact countdown for the collapsed island; popup clock formats remain separate. */
@Composable
private fun rememberAlarmCountdownText(triggerTimeMs: Long): String? {
    val now by
        produceState(System.currentTimeMillis(), triggerTimeMs) {
            while (triggerTimeMs > 0L) {
                delay(AlarmCountdownTickMs)
                value = System.currentTimeMillis()
            }
        }
    return compactIslandAlarmCountdownText(triggerTimeMs, now)
}

private fun compactIslandAlarmCountdownText(triggerTimeMs: Long, now: Long): String? {
    if (triggerTimeMs <= 0L) return null
    val minutes = (triggerTimeMs - now + 59_999L) / 60_000L
    if (minutes <= 0L) return null
    val bounded = minutes.coerceAtMost(99L * 60L + 59L)
    return "+${(bounded / 60L).toString().padStart(2, '0')}:${(bounded % 60L).toString().padStart(2, '0')}"
}

/** Leading artwork diameter of the normal capsule; it also sets the space around the glyph. */
private val IslandGlyphSize = 18.dp

/** The tallest the media bars grow, which is their visible height at the trailing end. */
private val MediaBarsHeight = 13.dp

/**
 * A [Row] that, given a [cameraGap], keeps the first [leadingCount] children before a centred gap
 * and the rest after it. Both sides take the same width so the gap stays over the camera.
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
private fun PopupContentModel.isUtilityStatusContent(): Boolean {
    return this is PopupContentModel.ScreenRecord ||
        this is PopupContentModel.Stopwatch ||
        this is PopupContentModel.Timer ||
        this is PopupContentModel.Alarm ||
        this is PopupContentModel.Charging ||
        this is PopupContentModel.Flashlight
}