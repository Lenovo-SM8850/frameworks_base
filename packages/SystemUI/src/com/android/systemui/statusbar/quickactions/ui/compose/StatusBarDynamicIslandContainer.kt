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

import io.github.miner7222.systemui.island.IslandContainer

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.android.systemui.statusbar.quickactions.popups.shared.DynamicIslandFeatureSettings.observeDynamicIslandColorOSStyle
import com.android.systemui.statusbar.quickactions.popups.shared.DynamicIslandFeatureSettings.readDynamicIslandColorOSStyle
import com.android.systemui.statusbar.quickactions.popups.shared.model.SystemEventKind
import com.android.systemui.statusbar.quickactions.shared.model.PopupContentModel
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipId
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipModel
import kotlinx.coroutines.delay
import kotlin.math.abs

/** Phone-only centered dynamic island that pages through active popup chips. */
@Composable
fun StatusBarDynamicIslandContainer(
    chips: List<QuickActionChipModel.PopupChip>,
    onMediaControlPopupVisibilityChanged: (Boolean) -> Unit,
    onIslandBoundsChanged: (android.graphics.Rect) -> Unit = {},
    onIslandActiveChanged: (Boolean) -> Unit = {},
    onIslandExpandedChanged: (Boolean) -> Unit = {},
    appHandleBounds: List<android.graphics.Rect> = emptyList(),
    modifier: Modifier = Modifier,
    sideEdge: DynamicIslandCutoutEdge? = null,
) =
    IslandContainer(
        chips = chips,
        onMediaControlPopupVisibilityChanged = onMediaControlPopupVisibilityChanged,
        onIslandBoundsChanged = onIslandBoundsChanged,
        onIslandActiveChanged = onIslandActiveChanged,
        onIslandExpandedChanged = onIslandExpandedChanged,
        appHandleBounds = appHandleBounds,
        modifier = modifier,
        sideEdge = sideEdge,
    )

/** Whether the island uses the ColorOS style; follows its setting live. */
@Composable
internal fun rememberDynamicIslandColorOSStyle(): Boolean {
    val context = LocalContext.current
    val initial = remember(context) { context.contentResolver.readDynamicIslandColorOSStyle() }
    val flow = remember(context) { observeDynamicIslandColorOSStyle(context) }
    val enabled by flow.collectAsState(initial = initial)
    return enabled
}

private const val TABLET_MIN_WIDTH_DP = 600
private val AppHandleGap = 8.dp

/** Whether the island steps aside for app handles: tablets only, phones keep their layout. */
@Composable
internal fun dynamicIslandAvoidsAppHandles(): Boolean =
    LocalConfiguration.current.smallestScreenWidthDp >= TABLET_MIN_WIDTH_DP

/**
 * How far to move an island at [island] so it clears every app handle it overlaps, stepping
 * towards the end side and leaving [gapPx] between them. Zero when nothing overlaps.
 */
private fun appHandleClearance(
    island: Rect,
    appHandles: List<android.graphics.Rect>,
    gapPx: Float,
    isRtl: Boolean,
): Float {
    if (island.width <= 0f) return 0f
    var left = island.left
    repeat(appHandles.size) {
        val hit =
            appHandles.firstOrNull {
                !it.isEmpty &&
                    it.left < left + island.width &&
                    it.right > left &&
                    it.top < island.bottom &&
                    it.bottom > island.top
            } ?: return left - island.left
        left = if (isRtl) hit.left - gapPx - island.width else hit.right + gapPx
    }
    return left - island.left
}

/** Hides the island on a swipe up; a pull down is left to the shade. */
private fun Modifier.swipeUpToHide(key: Any, slopPx: Float, onHide: () -> Unit): Modifier =
    pointerInput(key) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var change = down
            while (change.pressed) {
                change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                val delta = change.position - down.position
                if (-delta.y >= slopPx && -delta.y > abs(delta.x)) {
                    change.consume()
                    onHide()
                    break
                }
            }
        }
    }

internal fun QuickActionChipModel.PopupChip.isCallChip(): Boolean =
    (popupContent as? PopupContentModel.SystemEvent)?.kind == SystemEventKind.Call
