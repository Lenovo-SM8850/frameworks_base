/*
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.miner7222.systemui.island

import android.view.View
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.systemui.res.R
import kotlin.math.ceil
import com.android.systemui.statusbar.quickactions.ui.compose.DynamicIslandCutoutSpec
import com.android.systemui.statusbar.quickactions.ui.compose.DynamicIslandEmbeddedGapSidePadding

internal val DynamicIslandCutoutFillMargin = 1.5.dp

internal val DynamicIslandOutlineWidth = 1.dp

/** Collapsed height at a height scale of one, with or without a camera to cover. */
internal val DynamicIslandBaseHeight = 35.dp

/** Vertical centre of the island without a camera, where it sits on a camera that has one. */
internal val DynamicIslandDefaultCenterY = 24.5.dp

internal fun cutoutMinimumHeight(crossAxisSize: Dp): Dp =
    crossAxisSize + (DynamicIslandCutoutFillMargin + DynamicIslandOutlineWidth) * 2

internal fun cutoutMinimumWidth(mainAxisSize: Dp, minimumHeight: Dp): Dp =
    mainAxisSize + minimumHeight + (DynamicIslandCutoutFillMargin + DynamicIslandOutlineWidth) * 2

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
