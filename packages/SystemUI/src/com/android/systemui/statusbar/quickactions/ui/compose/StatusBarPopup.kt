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

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipModel
import io.github.miner7222.systemui.island.IslandPopup
import androidx.compose.ui.unit.dp

internal val CosCardEase = CubicBezierEasing(0.33f, 0f, 0.67f, 1f)
internal val CosCardMove = CubicBezierEasing(0.6f, 0f, 0.1f, 1f)
internal val CosCardGrow = CubicBezierEasing(0.3f, 0f, 0.1f, 1f)
internal val CosCardCorner = 32.dp
internal const val CosCardCloseMillis = 400

/**
 * Reuses the popup window as a shared container: it starts at the island's screen bounds and hands
 * those bounds back only after collapsing. The original chip stays laid out for touch and cutout
 * alignment, but is hidden while this window owns the shape.
 *
 * When [chips] holds more than one popup chip, swipe up for the next card or down for the previous
 * one, wrapping through [chips] and calling [onPageSelected] for the new front chip. Horizontal
 * swipes dismiss the front item when its onDismissed callback is available, or spring back otherwise.
 * Each drag locks to one axis after touch slop; taps on the card's controls keep their own actions.
 */
@Composable
fun StatusBarPopup(
    viewModel: QuickActionChipModel.PopupChip,
    isVisible: Boolean,
    chipBoundsInScreen: Rect? = null,
    sideEdge: DynamicIslandCutoutEdge? = null,
    cos: Boolean = false,
    chips: List<QuickActionChipModel.PopupChip> = listOf(viewModel),
    onPageSelected: (QuickActionChipModel.PopupChip) -> Unit = {},
    onMorphActiveChanged: (Boolean) -> Unit = {},
    onHidden: () -> Unit = {},
    appHandleBounds: List<android.graphics.Rect> = emptyList(),
) =
    IslandPopup(
        viewModel = viewModel,
        isVisible = isVisible,
        chipBoundsInScreen = chipBoundsInScreen,
        sideEdge = sideEdge,
        cos = cos,
        chips = chips,
        onPageSelected = onPageSelected,
        onMorphActiveChanged = onMorphActiveChanged,
        onHidden = onHidden,
        appHandleBounds = appHandleBounds,
    )
