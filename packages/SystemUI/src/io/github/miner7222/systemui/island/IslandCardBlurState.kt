// SPDX-License-Identifier: Apache-2.0
package io.github.miner7222.systemui.island

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.geometry.Offset

/**
 * Where the island popup card is while it is dragged or revealed. The card body moves with it, but
 * its glass is composited outside the card layer and would stay where it was; the glass reads this
 * to follow the card and fade with it.
 */
data class IslandCardBlurState(
    val shift: Offset = Offset.Zero,
    val alpha: Float = 1f,
    /**
     * The glass takes [alpha] as its own opacity, and the card content is left to the layer it is
     * in, so the glass and the content fade with the same alpha. Otherwise the glass is drawn at
     * full strength whatever [alpha] is, and only [alpha] reaching zero removes it.
     */
    val glassFollowsAlpha: Boolean = false,
)

internal val LocalIslandCardBlurState = compositionLocalOf { IslandCardBlurState() }
