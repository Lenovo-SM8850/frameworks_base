/*
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.miner7222.systemui.island

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.android.systemui.statusbar.quickactions.ui.compose.MaxPageDots
import com.android.systemui.statusbar.quickactions.ui.compose.PageDotSize
import com.android.systemui.statusbar.quickactions.ui.compose.PageDotSpacing
import com.android.systemui.statusbar.quickactions.ui.compose.PageDotsTapPadding

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

private val PageDotActiveHeight = 10.dp

private val PageDotsTouchSize = 48.dp
