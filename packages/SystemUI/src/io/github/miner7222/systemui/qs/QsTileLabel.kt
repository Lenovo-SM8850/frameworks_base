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

// The chevrons of a tile label, relative to the size of the label's font.
private const val ChevronHeightEm = 0.73f
private const val ChevronAspect = 11f / 16f
private const val ChevronGapEm = 0.3f

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
 * A ColorOS round tile label with its chevrons. A label on one line is centred together with them,
 * into the gap beside the tile if it must. A wrapped label stays just left of the centre and the
 * chevrons sit at the edge of the tile, halfway down its lines. The chevrons alone are too small a
 * target, so the whole row opens them.
 */
@Composable
internal fun ColorOSTileLabelRow(
    chevronTint: Color,
    onChevronClick: (() -> Unit)?,
    labels: @Composable () -> Unit,
) {
    val chevronClick by rememberUpdatedState(onChevronClick)
    val fontSize = MaterialTheme.typography.labelMedium.fontSize
    val density = LocalDensity.current
    val chevronHeight = with(density) { (fontSize.toPx() * ChevronHeightEm).toDp() }
    Layout(
        content = {
            Column(Modifier.width(IntrinsicSize.Max)) { labels() }
            Image(
                painter = painterResource(R.drawable.ic_coloros_tile_chevron),
                contentDescription = null,
                colorFilter = ColorFilter.tint(chevronTint),
                modifier = Modifier.size(chevronHeight * ChevronAspect, chevronHeight),
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
        val chevronWidth = chevronPlaceable.width
        val gap = (fontSize.toPx() * ChevronGapEm).toInt()
        val width = constraints.maxWidth
        val wrapped = measurables[0].maxIntrinsicWidth(Constraints.Infinity) > width
        val labelPlaceable =
            measurables[0].measure(
                constraints.copy(
                    minWidth = 0,
                    // A wrapped label keeps clear of the chevrons at the edge.
                    maxWidth = if (wrapped) width - chevronWidth else width,
                    minHeight = 0,
                    maxHeight = Constraints.Infinity,
                )
            )
        val height =
            maxOf(labelPlaceable.height, chevronPlaceable.height)
                .coerceIn(constraints.minHeight, constraints.maxHeight)
        val labelTop = CommonTileDefaults.ColorOSLabelTopPadding.roundToPx()
        layout(width, height) {
            val x =
                if (wrapped) -gap / 2 else (width - labelPlaceable.width - gap - chevronWidth) / 2
            labelPlaceable.placeRelative(x, 0)
            chevronPlaceable.placeRelative(
                if (wrapped) width - chevronWidth else x + labelPlaceable.width + gap,
                labelTop + (labelPlaceable.height - labelTop - chevronPlaceable.height) / 2,
            )
        }
    }
}
