/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.qs.panels.ui.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastMap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.scene.ContentScope
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.grid.ui.compose.GridSpan
import com.android.systemui.grid.ui.compose.TwoDimensionalSpannedGrid
import com.android.systemui.grid.ui.compose.VerticalSpannedGrid
import com.android.systemui.grid.ui.compose.calculate2DPlacements
import com.android.systemui.qs.composefragment.ui.GridAnchor
import com.android.systemui.qs.panels.shared.model.SizedTile
import com.android.systemui.qs.panels.shared.model.SizedTileImpl
import com.android.systemui.qs.panels.shared.model.splitInRowsSequence
import com.android.systemui.qs.panels.ui.compose.infinitegrid.LocalColorOSQsStyle
import com.android.systemui.qs.panels.ui.compose.infinitegrid.LocalNothingQsStyle
import com.android.systemui.qs.panels.ui.compose.infinitegrid.LocalQSBluetoothDevices
import com.android.systemui.qs.panels.ui.compose.infinitegrid.LocalQSQuadTiles
import com.android.systemui.qs.panels.ui.compose.infinitegrid.Tile
import com.android.systemui.qs.panels.ui.compose.infinitegrid.rememberQSPanelStyle
import com.android.systemui.qs.shared.style.LocalGlassBlurAlpha
import com.android.systemui.qs.panels.ui.compose.infinitegrid.rememberNothingQSMaxCellSize
import com.android.systemui.qs.panels.ui.compose.infinitegrid.forNothingColumns
import com.android.systemui.qs.panels.ui.compose.infinitegrid.inNothingLayoutOrder
import com.android.systemui.qs.panels.ui.compose.infinitegrid.isNothingReflow
import com.android.systemui.qs.panels.ui.compose.infinitegrid.rememberNothingLayout
import com.android.systemui.qs.panels.ui.compose.infinitegrid.rememberNothingQSColumns
import com.android.systemui.qs.panels.ui.viewmodel.BounceableTileViewModel
import com.android.systemui.qs.panels.ui.viewmodel.QuickQuickSettingsViewModel
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.shared.ui.QuickSettings.Elements.toElementKey
import com.android.systemui.res.R
import io.github.miner7222.systemui.qs.ColorOSTabletGap
import io.github.miner7222.systemui.qs.ColorOSTabletTileSize
import com.android.systemui.qs.composefragment.colorOSPinnedOf
import com.android.systemui.qs.composefragment.rememberColorOSPinned
import com.android.systemui.qs.panels.ui.compose.infinitegrid.ColorOSGridColumns
import com.android.systemui.qs.panels.ui.compose.infinitegrid.LocalColorOSTablet
import com.android.systemui.qs.panels.ui.compose.infinitegrid.LocalColorOSTileSize
import androidx.compose.runtime.compositionLocalOf
import com.android.systemui.qs.composefragment.ColorOSTabletColumns
import com.android.systemui.qs.composefragment.ColorOSTabletEntry
import com.android.systemui.qs.composefragment.ColorOSTabletItem
import com.android.systemui.qs.composefragment.colorOSTabletEntries
import com.android.systemui.qs.composefragment.colorOSTabletSpan
import com.android.systemui.qs.composefragment.rememberColorOSTabletLayout
import com.android.systemui.qs.composefragment.rememberColorOSTabletMetrics

@Composable
fun ContentScope.QuickQuickSettings(
    viewModel: QuickQuickSettingsViewModel,
    modifier: Modifier = Modifier,
    listening: () -> Boolean,
) {
    if (LocalNothingQsStyle.current) {
        NothingQuickQuickSettings(viewModel, modifier, listening)
        return
    }

    if (LocalColorOSQsStyle.current && LocalColorOSTablet.current) {
        ColorOSTabletQuickQuickSettings(viewModel, modifier, listening)
        return
    }

    val columns = viewModel.columns
    val sizedTiles = viewModel.tileViewModels
    val tiles = sizedTiles.fastMap { it.tile }
    val squishiness by viewModel.squishinessViewModel.squishiness.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val classicStyle = rememberQSPanelStyle()
    // ColorOS style: QQS shows only the round icons, the labels stay in the expanded panel.
    val colorOSStyle = LocalColorOSQsStyle.current
    // ColorOS style: a row the next, too wide, tile did not fit into hands its free columns to its
    // last icon tile, so QQS does not show a lone circle next to an empty gap.
    val rowTiles =
        remember(sizedTiles, columns, colorOSStyle, classicStyle) {
            if (colorOSStyle && !classicStyle) {
                sizedTiles.widenLastIconTiles(columns, viewModel.largeTilesSpan)
            } else {
                sizedTiles
            }
        }

    Box(modifier = modifier) {
        GridAnchor()

        val bounceables = remember(rowTiles) { List(rowTiles.size) { BounceableTileViewModel() } }
        val spans by remember(rowTiles) { derivedStateOf { rowTiles.fastMap { it.width } } }
        VerticalSpannedGrid(
            columns = columns,
            // Share the QS spacing so tiles don't shift when the panel expands.
            columnSpacing =
                if (colorOSStyle) dimensionResource(R.dimen.qs_tile_margin_horizontal)
                else qsTileColumnSpacing(columns),
            rowSpacing =
                if (colorOSStyle) dimensionResource(R.dimen.qs_tile_margin_vertical)
                else qsTileRowSpacing(columns, classicStyle),
            spans = spans,
            modifier = Modifier.sysuiResTag("qqs_tile_layout"),
            // Center compact tiles on the same row as taller large tiles.
            verticalAlignment = Alignment.CenterVertically,
            keys = { rowTiles[it].tile.spec },
        ) { spanIndex, column, isFirstInColumn, isLastInColumn ->
            val it = rowTiles[spanIndex]
            // Tile glass is a window blur region and ignores the element's fade, so a QQS tile
            // faded out over the expanded QS grid would still blur through it.
            val key = it.tile.spec.toElementKey()
            val outerGlass = LocalGlassBlurAlpha.current
            val glass = remember(outerGlass, key) { { outerGlass() * (key.currentAlpha() ?: 1f) } }
            Element(key, Modifier) {
                CompositionLocalProvider(LocalGlassBlurAlpha provides glass) {
                    Tile(
                        tile = it.tile,
                        iconOnly = classicStyle || it.isIcon,
                        squishiness = { squishiness },
                        coroutineScope = scope,
                        bounceableInfo =
                            bounceables.bounceableInfo(
                                it,
                                index = spanIndex,
                                column = column,
                                columns = columns,
                                isFirstInRow = isFirstInColumn,
                                isLastInRow = isLastInColumn,
                                // Compact tiles bounce their content instead of their container.
                                bouncesContainer = { index ->
                                    colorOSStyle || !(classicStyle || rowTiles[index].isIcon)
                                },
                            ),
                        tileHapticsViewModelFactory = viewModel.tileHapticsViewModelFactory,
                        // There should be no QuickQuickSettings when the details view is enabled.
                        detailsViewModel = null,
                        isVisible = listening,
                        hideLabel = colorOSStyle,
                    )
                }
            }
        }
    }

    TileListener(tiles, listening)
}

/**
 * Hands the free columns of every row of this [columns] wide grid to the last icon tile of the row,
 * widened up to [largeSpan], so a row never ends in a gap that the next tile did not fit into.
 */
private fun <T> List<SizedTile<T>>.widenLastIconTiles(
    columns: Int,
    largeSpan: Int,
): List<SizedTile<T>> =
    splitInRowsSequence(this, columns)
        .flatMap { row ->
            val free = columns - row.sumOf { it.width }
            val last = row.indexOfLast { it.isIcon }
            row.mapIndexed { index, tile ->
                if (index == last && free > 0) {
                    SizedTileImpl(tile.tile, (tile.width + free).coerceAtMost(largeSpan))
                } else {
                    tile
                }
            }
        }
        .toList()

/**
 * The collapsed panel of the large screen ColorOS style: the entries of the expanded panel that are
 * one row tall, in the same order and sizes on the same four columns, for its first rows. The items
 * that are not tiles are drawn by [LocalColorOSTabletItemContent].
 */
@Composable
private fun ContentScope.ColorOSTabletQuickQuickSettings(
    viewModel: QuickQuickSettingsViewModel,
    modifier: Modifier,
    listening: () -> Boolean,
) {
    val metrics = rememberColorOSTabletMetrics()
    val all = viewModel.allTileViewModels
    val saved by rememberColorOSTabletLayout()
    val largeSpecs by viewModel.iconTilesViewModel.largeTiles.collectAsStateWithLifecycle()
    val large = remember(largeSpecs) { largeSpecs.mapTo(HashSet()) { it.spec } }
    val bySpec = remember(all) { all.associateBy { it.tile.spec.spec } }
    val rowsShown = viewModel.tileRows
    val shown =
        remember(all, saved, large, rowsShown) {
            val entries =
                colorOSTabletEntries(saved, all.map { it.tile.spec.spec })
                    .filter { ColorOSTabletItem.isItem(it.key) || it.key in bySpec }
                    .map { it to colorOSTabletSpan(it, large) }
                    .filter { (_, span) -> span.rowSpan == 1 }
            val placements =
                calculate2DPlacements(
                    entries.map { it.second },
                    ColorOSTabletColumns,
                    maxRows = rowsShown,
                )
            Triple(entries.map { it.first }, entries.map { it.second }, placements)
        }
    val (entries, spans, placements) = shown
    val tiles = placements.mapNotNull { bySpec[entries[it.index].key]?.tile }
    val squishiness by viewModel.squishinessViewModel.squishiness.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val itemContent = LocalColorOSTabletItemContent.current

    CompositionLocalProvider(LocalColorOSTileSize provides metrics.tile) {
        Box(modifier = modifier) {
            GridAnchor()
            val bounceables = remember(placements) { List(spans.size) { BounceableTileViewModel() } }
            TwoDimensionalSpannedGrid(
                columns = ColorOSTabletColumns,
                columnSpacing = metrics.gap,
                rowSpacing = metrics.gap,
                spans = spans,
                customPlacements = placements,
                rowHeight = metrics.tile,
                modifier = Modifier.sysuiResTag("qqs_tile_layout"),
                keys = { entries[it].key },
            ) { index, placement ->
                val entry = entries[index]
                val tile = bySpec[entry.key]?.tile
                if (tile == null) {
                    // An element like the tiles, so it fades out with them.
                    Element(TileSpec.create(entry.key).toElementKey(), Modifier) {
                        itemContent?.invoke(entry, placement.colSpan > 1, Modifier)
                    }
                    return@TwoDimensionalSpannedGrid
                }
                Element(tile.spec.toElementKey(), Modifier) {
                    Tile(
                        tile = tile,
                        iconOnly = placement.colSpan == 1,
                        squishiness = { squishiness },
                        coroutineScope = scope,
                        bounceableInfo =
                            BounceableInfo(
                                bounceable = bounceables[index],
                                previousTile = null,
                                nextTile = null,
                                bounceEnd = false,
                            ),
                        tileHapticsViewModelFactory = viewModel.tileHapticsViewModelFactory,
                        detailsViewModel = null,
                        isVisible = listening,
                        hideLabel = true,
                    )
                }
            }
        }
    }

    TileListener(tiles, listening)
}

/**
 * Draws an item of the large screen ColorOS panel that is not a tile (the ringer switch, My Devices)
 * in the collapsed panel, wide or as one cell. Provided by the host, which has what it needs.
 */
internal val LocalColorOSTabletItemContent =
    compositionLocalOf<(@Composable (ColorOSTabletEntry, Boolean, Modifier) -> Unit)?> { null }

/** Columns a ColorOS pill spans. */
private const val ColorOSWideSpan = 2

/**
 * Nothing style QQS: the first two rows of the same 2D placements as the expanded panel, on 4
 * square columns, where tiles are circles, pills (2x1) or quads (2x2).
 */
@Composable
private fun ContentScope.NothingQuickQuickSettings(
    viewModel: QuickQuickSettingsViewModel,
    modifier: Modifier,
    listening: () -> Boolean,
) {
    val columns = rememberNothingQSColumns()
    val squishiness by viewModel.squishinessViewModel.squishiness.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val (quadTiles, savedTileLayout) = rememberNothingLayout()
    val tileLayout = savedTileLayout.forNothingColumns(columns)
    val largeTiles by viewModel.iconTilesViewModel.largeTiles.collectAsStateWithLifecycle()
    val noBounce = remember { BounceableInfo(BounceableTileViewModel(), null, null, false) }

    val allTiles =
        viewModel.allTileViewModels
            .map { it.tile }
            .inNothingLayoutOrder(savedTileLayout, columns) { it.spec }
    val allSpans =
        remember(allTiles, largeTiles, quadTiles) {
            allTiles.map {
                when {
                    quadTiles.contains(it.spec) -> GridSpan(colSpan = 2, rowSpan = 2)
                    largeTiles.contains(it.spec) -> GridSpan(colSpan = 2, rowSpan = 1)
                    else -> GridSpan(colSpan = 1, rowSpan = 1)
                }
            }
        }
    val preferredPlacements =
        remember(allTiles, tileLayout) {
            allTiles
                .mapIndexedNotNull { index, tile -> tileLayout[tile.spec]?.let { index to it } }
                .toMap()
        }
    // Same placements as the first page of the expanded panel, cut to the first rows, so the tiles
    // do not move when the panel expands. Tiles saved further down stay out of the QQS.
    val qqsPlacements =
        remember(allSpans, preferredPlacements, columns) {
            calculate2DPlacements(
                    allSpans,
                    columns,
                    preferredPlacements = preferredPlacements,
                    maxRowsPerPage = NOTHING_ROWS_PER_PAGE,
                    fillGaps = isNothingReflow(columns),
                )
                .filter { it.row + it.rowSpan <= NOTHING_QQS_MAX_ROWS }
        }
    val qqsTiles = remember(qqsPlacements, allTiles) { qqsPlacements.map { allTiles[it.index] } }
    val qqsSpans = remember(qqsPlacements, allSpans) { qqsPlacements.map { allSpans[it.index] } }

    Box(modifier = modifier) {
        GridAnchor()

        TwoDimensionalSpannedGrid(
            columns = columns,
            columnSpacing = NOTHING_SPACING,
            rowSpacing = NOTHING_SPACING,
            spans = qqsSpans,
            customPlacements = qqsPlacements.mapIndexed { idx, p -> p.copy(index = idx) },
            useSquareCells = true,
            maxCellSize = rememberNothingQSMaxCellSize(),
            keys = { qqsTiles[it].spec },
            modifier = Modifier.sysuiResTag("qqs_tile_layout"),
        ) { spanIndex, _ ->
            val it = qqsTiles[spanIndex]
            val key = it.spec.toElementKey()
            val outerGlass = LocalGlassBlurAlpha.current
            val glass = remember(outerGlass, key) { { outerGlass() * (key.currentAlpha() ?: 1f) } }
            Element(key, Modifier) {
                CompositionLocalProvider(
                    LocalQSQuadTiles provides quadTiles,
                    LocalQSBluetoothDevices provides viewModel.connectedBluetoothDevices,
                    LocalGlassBlurAlpha provides glass,
                ) {
                    Tile(
                        tile = it,
                        iconOnly = !largeTiles.contains(it.spec) && !quadTiles.contains(it.spec),
                        squishiness = { squishiness },
                        coroutineScope = scope,
                        bounceableInfo = noBounce,
                        tileHapticsViewModelFactory = viewModel.tileHapticsViewModelFactory,
                        detailsViewModel = null,
                        isVisible = listening,
                    )
                }
            }
        }
    }

    TileListener(qqsTiles, listening)
}

private const val NOTHING_QQS_MAX_ROWS = 2
// Keep in sync with the rows per page of the expanded panel in PaginatedGridLayout.
private const val NOTHING_ROWS_PER_PAGE = 4
private val NOTHING_SPACING = 16.dp
