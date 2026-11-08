/*
 Copyright (C) 2026 The Evolution X Project
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

package io.github.miner7222.systemui.qs

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults
import com.android.systemui.res.R
import kotlin.math.ceil
import com.android.compose.modifiers.thenIf
import androidx.compose.foundation.gestures.detectTapGestures

/** Wraps a tile label between words, in the languages that could otherwise break inside one. */
internal val TileLabelLineBreak =
    LineBreak(
        strategy = LineBreak.Strategy.Simple,
        strictness = LineBreak.Strictness.Default,
        wordBreak = LineBreak.WordBreak.Phrase,
    )

/** Label style for wrapping tiles: a phrase line break keeps a Hangul syllable with its word. */
internal fun wrappingTileLabelStyle(style: TextStyle): TextStyle =
    style.copy(lineBreak = TileLabelLineBreak)

/** Room for the Classic title, state line and top padding, including font scaling. */
@Composable
@ReadOnlyComposable
internal fun classicStateLabelHeight(): Dp {
    val typography = MaterialTheme.typography
    return with(LocalDensity.current) {
        4.dp +
            ceil(typography.labelMedium.lineHeight.toPx()).toDp() +
            ceil(typography.labelSmall.lineHeight.toPx()).toDp()
    }
}

/**
 * A ColorOS round tile label: centred under the tile on its own and given the whole width, with
 * the chevron hanging after its widest line, into the gap beside the tile if it must. The chevron
 * alone is too small a target, so its whole row opens it.
 */
@Composable
internal fun ColorOSTileLabelRow(
    chevronTint: Color,
    onChevronClick: (() -> Unit)?,
    labels: @Composable () -> Unit,
) {
    val chevronClick by rememberUpdatedState(onChevronClick)
    Layout(
        content = {
            Column(Modifier.width(IntrinsicSize.Max)) { labels() }
            Image(
                painter = painterResource(R.drawable.ic_coloros_tile_chevron),
                contentDescription = null,
                colorFilter = ColorFilter.tint(chevronTint),
                modifier = Modifier.size(10.dp),
            )
        },
        modifier =
            Modifier.fillMaxWidth().thenIf(onChevronClick != null) {
                Modifier.pointerInput(Unit) {
                    detectTapGestures {
                        chevronClick?.invoke()
                    }
                }
            },
    ) { measurables, constraints ->
        val chevronPlaceable = measurables[1].measure(Constraints())
        val gap = 1.dp.roundToPx()
        val width = constraints.maxWidth
        val labelPlaceable =
            measurables[0].measure(
                constraints.copy(
                    minWidth = 0,
                    maxWidth = width,
                    minHeight = 0,
                    maxHeight = Constraints.Infinity,
                )
            )
        val height =
            maxOf(labelPlaceable.height, chevronPlaceable.height)
                .coerceIn(constraints.minHeight, constraints.maxHeight)
        val labelTop = CommonTileDefaults.ColorOSLabelTopPadding.roundToPx()
        layout(width, height) {
            val x = (width - labelPlaceable.width) / 2
            labelPlaceable.placeRelative(x, 0)
            chevronPlaceable.placeRelative(
                x + labelPlaceable.width + gap,
                labelTop + (labelPlaceable.height - labelTop - chevronPlaceable.height) / 2,
            )
        }
    }
}
