// SPDX-License-Identifier: Apache-2.0

package com.android.systemui.statusbar.quickactions.ui.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import kotlin.math.floor

internal val LocalCollapsedIslandTextHeight = staticCompositionLocalOf { Dp.Unspecified }

/** Fit both Latin metrics and the Korean fallback into the collapsed content area. */
@Composable
internal fun collapsedIslandTextStyle(
    baseStyle: TextStyle = MaterialTheme.typography.labelLarge,
    availableHeight: Dp = LocalCollapsedIslandTextHeight.current,
): TextStyle {
    // Some event labels are also used by expanded panels.
    if (availableHeight == Dp.Unspecified) return baseStyle
    val density = LocalDensity.current
    val labelSize = MaterialTheme.typography.labelLarge.fontSize
    val measurer = rememberTextMeasurer()
    return remember(baseStyle, availableHeight, density, labelSize, measurer) {
        val heightPx = with(density) { floor(availableHeight.toPx()).coerceAtLeast(1f) }
        val maximumSize = if (baseStyle.fontSize == TextUnit.Unspecified) {
            labelSize.value
        } else {
            minOf(baseStyle.fontSize.value, labelSize.value)
        }
        fun style(size: Float) = baseStyle.copy(
            fontSize = size.sp,
            lineHeight = size.sp,
            lineHeightStyle = LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Center,
                trim = LineHeightStyle.Trim.Both,
            ),
            platformStyle = PlatformTextStyle(includeFontPadding = false),
        )
        fun fits(size: Float) = measurer.measure(
            text = "Ag완충",
            style = style(size),
            softWrap = false,
            maxLines = 1,
        ).multiParagraph.height <= heightPx

        if (fits(maximumSize)) return@remember style(maximumSize)
        var low = 0f
        var high = maximumSize
        repeat(12) {
            val middle = (low + high) / 2f
            if (fits(middle)) low = middle else high = middle
        }
        style(low)
    }
}
