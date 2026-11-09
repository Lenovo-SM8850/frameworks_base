// SPDX-License-Identifier: Apache-2.0
package io.github.miner7222.systemui.island

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

private const val MaxCosPageDots = 5
private val CosDotSize = 6.dp

// The dots are 11dp apart centre to centre.
private val CosDotSpacing = 5.dp

/** Width of the edge that keeps a dot readable over a backdrop of mixed brightness. */
private val CosDotOutline = 0.5.dp

/** Visible space between the card and the dots. */
private val CosDotCardGap = 9.dp

private val CosDotsTouchWidth = 48.dp
private val CosDotsTouchHeight = 48.dp

/**
 * The page indicator of the ColorOS style popup: a bare column of dots beside the card. The touch
 * target is larger than the dots and starts at the card's edge, so the popup places it without any
 * gap of its own. [cardOnLeft] is true when the card is to the left of the dots, and false when the
 * dots are to its left. The dots are drawn in [color] with a thin edge of [outlineColor], which the
 * popup picks to suit what is behind them. [onClickLabel] names what a tap does for accessibility
 * services.
 */
@Composable
internal fun CosPageDots(
    count: Int,
    index: Int,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    outlineColor: Color = Color.Transparent,
    cardOnLeft: Boolean = true,
    onClickLabel: String? = null,
) {
    val visible = minOf(count, MaxCosPageDots).coerceAtLeast(1)
    val first = (index - visible / 2).coerceIn(0, (count - visible).coerceAtLeast(0))
    Box(
        modifier =
            modifier
                .defaultMinSize(minWidth = CosDotsTouchWidth, minHeight = CosDotsTouchHeight)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClickLabel = onClickLabel,
                    onClick = onClick,
                )
                // The gap to the card is on the side of the card, whichever side that is.
                .absolutePadding(
                    left = if (cardOnLeft) CosDotCardGap else CosDotsTouchWidth - CosDotCardGap - CosDotSize,
                    right = if (cardOnLeft) CosDotsTouchWidth - CosDotCardGap - CosDotSize else CosDotCardGap,
                    top = 16.dp,
                    bottom = 16.dp,
                )
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(CosDotSpacing),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            repeat(visible) { slot ->
                val alpha by
                    animateFloatAsState(
                        targetValue = if (first + slot == index) 1f else 0.4f,
                        label = "cosPageDotAlpha",
                    )
                Box(
                    modifier =
                        Modifier.size(CosDotSize)
                            .background(color.copy(alpha = alpha), CircleShape)
                            .border(CosDotOutline, outlineColor.copy(alpha = outlineColor.alpha * alpha), CircleShape)
                )
            }
        }
    }
}
