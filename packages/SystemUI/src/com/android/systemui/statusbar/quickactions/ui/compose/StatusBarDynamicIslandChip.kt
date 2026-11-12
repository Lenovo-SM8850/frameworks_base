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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
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
import kotlin.math.max
import kotlin.math.sqrt
import kotlinx.coroutines.coroutineScope
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

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
fun StatusBarDynamicIslandChip(
    viewModel: QuickActionChipModel.PopupChip,
    cutoutSpec: DynamicIslandCutoutSpec,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    onChipBoundsChanged: (Rect) -> Unit = {},
    /** Draw for the ColorOS island shell, which supplies the capsule, instead of our own. */
    cos: Boolean = false,
) {
    val isMediaChip = viewModel.popupContent is PopupContentModel.Media
    val media = (viewModel.popupContent as? PopupContentModel.Media)?.model
    val mediaArtwork =
        media?.let { it.artworkIcon != null && it.artworkIcon !== it.appIcon } == true
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

    val leadingTextMargin = if (cos && viewModel.icons.size == 1) CosIsland.TextMargin * 2 else 0.dp
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
                        cos -> CosIsland.IconSize
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
                                    CosIsland.ContentPadding +
                                        if (isStartAnchored) cutoutSpec.holeSize + 8.dp else 0.dp,
                                end = CosIsland.ContentPadding,
                            )
                        else
                            Modifier.capsuleEnds(
                                leading = leadingEnd,
                                trailing = trailingEnd,
                                glyph = leadingEnd?.diameter ?: IslandGlyphSize,
                                symmetric = cameraGap != null,
                                fixedStart = startGap.takeIf { isStartAnchored },
                            )
                    )
                    .then(
                        if (cos) Modifier else Modifier.padding(vertical = 7.dp * heightScale)
                    ),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier =
                    Modifier.padding(
                        start =
                            if (
                                cos &&
                                    !isMediaChip &&
                                    !mediaArtwork &&
                                    viewModel.icons.size == 1 &&
                                    viewModel.popupContent !is PopupContentModel.LiveScore
                            )
                                CosIsland.NormalIconMargin
                            else 0.dp,
                        end = leadingTextMargin,
                    ),
                horizontalArrangement =
                    Arrangement.spacedBy(if (cos) CosIsland.IconListGap else 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                viewModel.icons.forEachIndexed { index, chipIcon ->
                    val isArtworkLike =
                        index == 0 && viewModel.popupContent is PopupContentModel.LiveScore
                    UprightContent {
                        if (index == 0 && isMediaChip) {
                            CollapsedMediaArtwork(
                                chipIcon.icon,
                                mediaArtwork,
                                media?.packageName,
                                chipContentColor,
                                contentMotion,
                                cos,
                            )
                        } else {
                            Icon(
                                icon = chipIcon.icon,
                                modifier =
                                    Modifier.size(
                                            when {
                                                cos -> CosIsland.IconSize
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
                            modifier =
                                if (cos) {
                                    Modifier.padding(
                                        start = CosIsland.TextMargin * 2,
                                        end = CosIsland.NormalIconMargin,
                                    ).then(contentMotion)
                                } else contentMotion,
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
                            modifier =
                                if (cos)
                                    Modifier.padding(
                                            start = CosIsland.TextMargin,
                                            end = CosIsland.TextGoneMargin,
                                        )
                                        .then(contentMotion)
                                else Modifier.then(contentMotion),
                            style = textStyle,
                            color = if (cos) CosIsland.TextColor else chipContentColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.End,
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
    packageName: String?,
    glyphTint: Color,
    modifier: Modifier = Modifier,
    cos: Boolean = false,
) {
    if (!isArtwork) {
        val boxSize = if (cos) CosIsland.IconSize else 18.dp
        // Without artwork, the app icon of the expanded card, already in the icon mask shape.
        val appIcon = rememberAppIconBitmap(packageName, fallback = null, size = boxSize)
        Box(modifier.size(boxSize), contentAlignment = Alignment.Center) {
            if (appIcon != null) {
                Image(
                    bitmap = appIcon,
                    contentDescription = icon.contentDescription?.load(),
                    modifier = Modifier.size(boxSize),
                )
            } else {
                // A notification glyph is fitted inside the box, not cropped to it.
                Icon(
                    icon = icon,
                    modifier = Modifier.size(if (cos) 14.dp else 16.dp),
                    tint = glyphTint,
                )
            }
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
private fun PopupContentModel.isUtilityStatusContent(): Boolean {
    return this is PopupContentModel.ScreenRecord ||
        this is PopupContentModel.Stopwatch ||
        this is PopupContentModel.Timer ||
        this is PopupContentModel.Alarm ||
        this is PopupContentModel.Charging ||
        this is PopupContentModel.Flashlight
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
        // No upper limit: a fixed ceiling would clip the utility labels at larger font scales.
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

/** What sits at one rounded end of a capsule, which decides how far it must stay from the edge. */
internal sealed interface CapsuleEnd {
    /** A circle (icon or artwork) centred vertically. */
    data class Round(val diameter: Dp) : CapsuleEnd

    /** Text or bars with a visible [height], centred vertically. */
    data class Box(val height: Dp) : CapsuleEnd
}

/** Share of the font size that capital letters rise above the baseline. */
private const val CapHeightRatio = 0.72f

/** Ink height of capital letters, which is what the eye measures; the line box is taller. */
internal fun Density.textCapHeight(fontSize: TextUnit): Dp =
    if (fontSize != TextUnit.Unspecified) (fontSize.toPx() * CapHeightRatio).toDp() else 0.dp

/**
 * Horizontal distance in px from the capsule end to [end], so its outer edge keeps [gap] from the
 * rounded outline. A round item needs exactly [gap]; a box needs its corners [gap] from the arc.
 */
internal fun capsuleEndInset(radius: Float, gap: Float, end: CapsuleEnd, density: Density): Float =
    when (end) {
        is CapsuleEnd.Round -> gap
        is CapsuleEnd.Box -> {
            val half = with(density) { end.height.toPx() } / 2f
            val reach = radius - gap
            if (half >= reach) radius else max(gap, radius - sqrt(reach * reach - half * half))
        }
    }

/**
 * The start and end insets in px for a capsule of [height] px. A null [leading] means a round glyph
 * of size [glyph]. With [symmetric] both sides take the larger inset, which keeps a centred camera
 * gap over the camera.
 */
internal fun capsuleInsets(
    height: Float,
    leading: CapsuleEnd?,
    trailing: CapsuleEnd,
    glyph: Dp,
    density: Density,
    symmetric: Boolean = false,
): Pair<Float, Float> {
    val radius = height / 2f
    val gap = ((height - with(density) { glyph.toPx() }) / 2f).coerceAtLeast(0f)
    val start = capsuleEndInset(radius, gap, leading ?: CapsuleEnd.Round(glyph), density)
    val end = capsuleEndInset(radius, gap, trailing, density)
    return if (symmetric) max(start, end).let { it to it } else start to end
}

/**
 * Keeps content evenly spaced inside a capsule whose ends are fully rounded (radius = height / 2).
 * The content's outer edge stays [gap] from the outline on every side, the rounded end included,
 * where gap = (height - glyph) / 2 is the space above and below the leading glyph. A round item
 * needs x = gap; a box of visible height h needs x = R - sqrt((R - gap)^2 - (h / 2)^2). The insets
 * come from the real height at layout time, so they follow the size and camera settings.
 *
 * Place it directly inside the capsule. The height is the fixed one if there is one, else the
 * minimum, else the bounded maximum, else the content's own. [fixedStart] replaces the start inset
 * (a camera sits at that end).
 */
internal fun Modifier.capsuleEnds(
    leading: CapsuleEnd?,
    trailing: CapsuleEnd,
    glyph: Dp,
    symmetric: Boolean = false,
    fixedStart: Dp? = null,
): Modifier = layout { measurable, constraints ->
    val height =
        when {
            constraints.hasFixedHeight -> constraints.maxHeight
            constraints.minHeight > 0 -> constraints.minHeight
            constraints.hasBoundedHeight -> constraints.maxHeight
            else -> measurable.minIntrinsicHeight(if (constraints.hasBoundedWidth) constraints.maxWidth else 0)
        }
    val (startInset, endInset) =
        capsuleInsets(height.toFloat(), leading, trailing, glyph, this, symmetric)
    val start = fixedStart?.roundToPx() ?: startInset.roundToInt()
    val end = endInset.roundToInt()
    val inset = start + end
    val placeable =
        measurable.measure(
            constraints.copy(
                minWidth = (constraints.minWidth - inset).coerceAtLeast(0),
                maxWidth =
                    if (constraints.hasBoundedWidth) (constraints.maxWidth - inset).coerceAtLeast(0)
                    else Constraints.Infinity,
            )
        )
    layout(constraints.constrainWidth(placeable.width + inset), placeable.height) {
        placeable.placeRelative(start, 0)
    }
}
