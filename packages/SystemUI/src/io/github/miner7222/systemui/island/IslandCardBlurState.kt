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
)

internal val LocalIslandCardBlurState = compositionLocalOf { IslandCardBlurState() }
