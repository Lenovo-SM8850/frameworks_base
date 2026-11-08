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

import io.github.miner7222.systemui.island.IslandPopup

import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.android.systemui.res.R
import com.android.systemui.statusbar.quickactions.alarm.ui.compose.AlarmPopup
import com.android.systemui.statusbar.quickactions.charging.ui.compose.ChargingPopup
import com.android.systemui.statusbar.quickactions.flashlight.ui.compose.FlashlightPopup
import com.android.systemui.statusbar.quickactions.livescore.ui.compose.LiveScorePopup
import com.android.systemui.statusbar.quickactions.media.ui.compose.LyricsCard
import com.android.systemui.statusbar.quickactions.media.ui.compose.MediaControlPopup
import com.android.systemui.statusbar.quickactions.screenrecord.ui.compose.ScreenRecordPopup
import com.android.systemui.statusbar.quickactions.shared.model.PopupContentModel
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipModel
import com.android.systemui.statusbar.quickactions.stopwatch.ui.compose.StopwatchPopup
import com.android.systemui.statusbar.quickactions.popups.shared.DynamicIslandFeatureSettings.POPUP_COLOR_MODE_BLUR
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Displays a popup in the status bar area. When [chipBoundsInScreen] is provided, the popup opens
 * from the chip's own top edge, over the status bar and around the camera, and grows out of the
 * chip's on-screen position and aspect ratio with an independent-axis squish animation. Without
 * it the popup is drawn below the status bar.
 *
 * With a [sideEdge] camera the island is docked to the left or right edge, so the popup opens
 * beside the pill instead: next to it on the inner side and centered on the camera, growing out
 * of the pill from that edge.
 */
@Composable
fun StatusBarPopup(
    viewModel: QuickActionChipModel.PopupChip,
    isVisible: Boolean,
    chipBoundsInScreen: Rect? = null,
    /**
     * The x the island is moved onto, when it is centred on a top camera. The popup centres on it
     * ahead of the chip bounds, which can lag behind the island's own translation.
     */
    anchorCenterX: Float? = null,
    sideEdge: DynamicIslandCutoutEdge? = null,
    /** Open and close like a ColorOS card: out of the island, behind a growing reveal. */
    cos: Boolean = false,
    /** Every live activity as a stack of cards, when the island opened as one. */
    stackedChips: List<QuickActionChipModel.PopupChip>? = null,
) =
    IslandPopup(
        viewModel = viewModel,
        isVisible = isVisible,
        chipBoundsInScreen = chipBoundsInScreen,
        anchorCenterX = anchorCenterX,
        sideEdge = sideEdge,
        cos = cos,
        stackedChips = stackedChips,
    )

private val PopupEdgePadding = 8.dp

internal val CosCardEase = CubicBezierEasing(0.33f, 0f, 0.67f, 1f)
private val CosCardMove = CubicBezierEasing(0.6f, 0f, 0.1f, 1f)
internal val CosCardGrow = CubicBezierEasing(0.3f, 0f, 0.1f, 1f)
private val CosCardCorner = 32.dp
internal const val CosCardCloseMillis = 400

/** Reveals the card from a small rounded rectangle in its middle out to its full size. */
private class CosRevealShape(
    private val fraction: Float,
    private val cornerPx: Float,
    private val anchorTop: Boolean = false,
    /** A closing stack folds into its top card, so it ends in the island like a single card. */
    private val foldHeight: Float = 0f,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val start = size.width * 0.12f
        val w = start + (size.width - start) * fraction
        val full =
            if (foldHeight > 0f) foldHeight + (size.height - foldHeight) * fraction
            else size.height
        val h = start + (full - start) * fraction
        val left = (size.width - w) / 2f
        val top = if (anchorTop) 0f else (size.height - h) / 2f
        val r = minOf(cornerPx, w / 2f, h / 2f)
        return Outline.Rounded(RoundRect(left, top, left + w, top + h, CornerRadius(r)))
    }
}

private fun LayoutCoordinates.boundsInScreen(view: android.view.View): Rect {
    val location = IntArray(2)
    view.getLocationOnScreen(location)
    return boundsInRoot().translate(Offset(location[0].toFloat(), location[1].toFloat()))
}

/** Renders the per-feature card for [viewModel]; the single popup and the card stack show these. */
@Composable
internal fun IslandPopupContent(
    viewModel: QuickActionChipModel.PopupChip,
    fillWidth: Boolean = false,
) {
    val cardModifier = if (fillWidth) Modifier.fillMaxWidth() else Modifier
    when (val popupContent = viewModel.popupContent) {
        is PopupContentModel.Media -> {
            val model = popupContent.model
            val useWaveform = popupContent.useWaveform
            val hasLyrics = !model.lyrics.isNullOrBlank() || !model.syncedLyrics.isNullOrBlank()
            if (hasLyrics) {
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
        is PopupContentModel.Charging -> ChargingPopup(model = popupContent.model)
        is PopupContentModel.SystemEvent -> SystemEventPopup(model = popupContent)
        PopupContentModel.None -> Unit
    }
}
