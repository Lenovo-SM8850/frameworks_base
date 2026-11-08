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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.android.systemui.statusbar.quickactions.popups.shared.DynamicIslandFeatureSettings.observeDynamicIslandColorOSStyle
import com.android.systemui.statusbar.quickactions.popups.shared.DynamicIslandFeatureSettings.readDynamicIslandColorOSStyle
import com.android.systemui.statusbar.quickactions.popups.shared.model.SystemEventKind
import com.android.systemui.statusbar.quickactions.shared.model.PopupContentModel
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipModel
import io.github.miner7222.systemui.island.IslandContainer
import androidx.compose.runtime.getValue

/** Whether the island uses the ColorOS style; follows its setting live. */
@Composable
internal fun rememberDynamicIslandColorOSStyle(): Boolean {
    val context = LocalContext.current
    val initial = remember(context) { context.contentResolver.readDynamicIslandColorOSStyle() }
    val flow = remember(context) { observeDynamicIslandColorOSStyle(context) }
    val enabled by flow.collectAsState(initial = initial)
    return enabled
}

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

internal fun QuickActionChipModel.PopupChip.isCallChip(): Boolean =
    (popupContent as? PopupContentModel.SystemEvent)?.kind == SystemEventKind.Call
