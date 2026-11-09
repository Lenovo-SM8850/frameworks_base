// SPDX-License-Identifier: Apache-2.0

package com.android.systemui.statusbar.quickactions.ui.compose

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

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
