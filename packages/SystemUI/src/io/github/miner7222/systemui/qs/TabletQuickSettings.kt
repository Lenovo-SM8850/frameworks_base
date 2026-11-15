/*
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.miner7222.systemui.qs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.compose.animation.scene.ContentScope
import com.android.settingslib.volume.domain.interactor.AudioVolumeInteractor
import com.android.systemui.brightness.ui.viewmodel.BrightnessSliderViewModel
import com.android.systemui.qs.panels.ui.compose.infinitegrid.ColorOSGridFooterHeight
import com.android.systemui.qs.panels.ui.compose.infinitegrid.ColorOSGridHeadroom
import com.android.systemui.qs.panels.ui.compose.infinitegrid.ColorOSGridRows
import com.android.systemui.qs.panels.ui.compose.infinitegrid.ColorOSMinGridRows
import com.android.systemui.qs.panels.ui.compose.infinitegrid.ColorOSGlassBlur
import com.android.systemui.qs.panels.ui.compose.infinitegrid.LocalColorOSGlassBlur
import com.android.systemui.qs.panels.ui.compose.infinitegrid.InfiniteGridLayout
import com.android.systemui.qs.panels.ui.compose.infinitegrid.LocalColorOSGap
import com.android.systemui.qs.panels.ui.compose.infinitegrid.LocalColorOSTablet
import com.android.systemui.qs.panels.ui.compose.infinitegrid.LocalColorOSTileSize
import com.android.systemui.qs.panels.ui.viewmodel.TileViewModel
import kotlin.math.roundToInt
import com.android.systemui.qs.composefragment.ColorOSBrightnessSlider
import com.android.systemui.qs.composefragment.ColorOSFit
import com.android.systemui.qs.composefragment.ColorOSGlass
import com.android.systemui.qs.composefragment.ColorOSGridTopPadding
import com.android.systemui.qs.composefragment.ColorOSIdleMediaCard
import com.android.systemui.qs.composefragment.ColorOSMediaBaseHeight
import com.android.systemui.qs.composefragment.ColorOSMediaShape
import com.android.systemui.qs.composefragment.ColorOSMyDevices
import com.android.systemui.qs.composefragment.ColorOSMyDevicesToggle
import com.android.systemui.qs.composefragment.ColorOSRingerSwitch
import com.android.systemui.qs.composefragment.ColorOSRingerToggle
import com.android.systemui.qs.composefragment.ColorOSTabletGrid
import com.android.systemui.qs.composefragment.ColorOSTabletItem
import com.android.systemui.qs.composefragment.ColorOSVolumeSlider
import com.android.systemui.qs.composefragment.colorOSTabletEntries
import com.android.systemui.qs.composefragment.colorOSTabletSpan
import com.android.systemui.qs.composefragment.glassSurface
import com.android.systemui.qs.composefragment.rememberColorOSTabletLayout
import com.android.systemui.qs.composefragment.rememberColorOSTabletMetrics

/**
 * Sizes of the panel on a large screen, the same collapsed and expanded and in both orientations:
 * the round tile (also the height of the pills and the width of the sliders), the gap between
 * tiles, rows and cards, and the gap between rows of labelled round tiles. ColorOS tablets use a
 * 56dp pill and a 15dp gap.
 */
internal val ColorOSTabletTileSize = 56.dp

internal val ColorOSTabletGap = 15.dp

internal val ColorOSTabletLabelRowGap = 12.dp

/** Width of the large screen panel: its four tile columns and their gaps. */
internal val ColorOSTabletPanelWidth = ColorOSTabletTileSize * 4 + ColorOSTabletGap * 3

/** Most rows of round tiles a page of the large screen panel shows. */
private const val ColorOSTabletMaxGridRows = 5

/**
 * Rows of round tiles per page of the large screen panel, whose tiles keep their size: as many as
 * fit [availableHeight] under the cards, at least [ColorOSMinGridRows].
 */
internal fun colorOSTabletFit(availableHeight: Dp, labelHeight: Dp, rowGap: Dp): ColorOSFit {
    val t = ColorOSTabletTileSize
    if (availableHeight == Dp.Unspecified || availableHeight <= 0.dp) {
        return ColorOSFit(t, ColorOSGridRows, fits = true)
    }
    for (rows in ColorOSTabletMaxGridRows downTo ColorOSMinGridRows) {
        val needed =
            t * 4 + ColorOSTabletGap * 4 + ColorOSGridTopPadding + ColorOSGridHeadroom +
                (t + labelHeight) * rows + rowGap * (rows - 1) + ColorOSGridFooterHeight
        if (needed <= availableHeight) return ColorOSFit(t, rows, fits = true)
    }
    return ColorOSFit(t, ColorOSMinGridRows, fits = false)
}

/**
 * Lays the content out [baseHeight] tall, with the width that keeps its proportions, and scales it
 * down to the height it is given when that is less, instead of squeezing its parts.
 */
internal fun Modifier.colorOSFitScale(baseHeight: Dp): Modifier =
    layout { measurable, constraints ->
        val base = baseHeight.roundToPx()
        val height = constraints.maxHeight
        if (!constraints.hasBoundedHeight || !constraints.hasBoundedWidth || height >= base ||
            height <= 0
        ) {
            val placeable = measurable.measure(constraints)
            return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
        val scale = height.toFloat() / base
        val width = constraints.maxWidth
        val placeable = measurable.measure(Constraints.fixed((width / scale).roundToInt(), base))
        layout(width, height) {
            placeable.placeWithLayer(0, 0) {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0f)
            }
        }
    }

/**
 * The large screen ColorOS panel: every tile, the media card, the sliders, the ringer switch and My
 * Devices on one four column grid, in the order and sizes of the editor, in pages.
 */
@Composable
internal fun ContentScope.ColorOSTabletLayout(
    tiles: List<TileViewModel>,
    largeTiles: Set<String>,
    infiniteGridLayout: InfiniteGridLayout,
    brightnessViewModel: BrightnessSliderViewModel,
    audioVolumeInteractor: AudioVolumeInteractor,
    showMedia: Boolean,
    media: @Composable (Modifier, Dp) -> Unit,
    listening: () -> Boolean,
    glass: ColorOSGlass,
    glassBlur: ColorOSGlassBlur?,
    labelHeight: Dp,
    labelsHidden: Boolean,
    availableHeight: Dp,
    fullWidth: Dp,
    onOpenDevices: () -> Unit,
    reveal: @Composable (Modifier) -> Modifier,
) {
    val metrics = rememberColorOSTabletMetrics()
    val saved by rememberColorOSTabletLayout()
    val bySpec = remember(tiles) { tiles.associateBy { it.spec.spec } }
    val entries =
        remember(saved, tiles) { colorOSTabletEntries(saved, tiles.map { it.spec.spec }) }
    val spans = remember(entries, largeTiles) { entries.map { colorOSTabletSpan(it, largeTiles) } }
    CompositionLocalProvider(
        LocalColorOSTileSize provides metrics.tile,
        LocalColorOSGap provides metrics.gap,
        LocalColorOSTablet provides true,
        LocalColorOSGlassBlur provides glassBlur,
    ) {
        ColorOSTabletGrid(
            entries = entries,
            spans = spans,
            metrics = metrics,
            label = labelHeight,
            availableHeight = availableHeight,
            fullWidth = fullWidth,
            item = { entry, span, itemModifier ->
                val cell = reveal(itemModifier.fillMaxSize())
                val top: @Composable (@Composable (Modifier) -> Unit) -> Unit = { content ->
                    Box(cell, contentAlignment = Alignment.TopCenter) {
                        content(Modifier.fillMaxWidth().height(metrics.tile))
                    }
                }
                when (entry.key) {
                    ColorOSTabletItem.MEDIA ->
                        Box(cell.glassSurface(glass, ColorOSMediaShape)) {
                            val content = Modifier.fillMaxSize().colorOSFitScale(ColorOSMediaBaseHeight)
                            if (showMedia) media(content, 0.dp) else ColorOSIdleMediaCard(content)
                        }
                    ColorOSTabletItem.BRIGHTNESS ->
                        ColorOSBrightnessSlider(brightnessViewModel, glass, cell)
                    ColorOSTabletItem.VOLUME -> ColorOSVolumeSlider(audioVolumeInteractor, glass, cell)
                    ColorOSTabletItem.RINGER ->
                        if (span.colSpan > 1) {
                            top { ColorOSRingerSwitch(audioVolumeInteractor, glass, metrics.tile, it) }
                        } else {
                            ColorOSRingerToggle(
                                audioVolumeInteractor,
                                glass,
                                metrics.tile,
                                labelsHidden,
                                cell,
                            )
                        }
                    ColorOSTabletItem.MY_DEVICES ->
                        if (span.colSpan > 1) {
                            top { ColorOSMyDevices(glass, metrics.tile, onOpenDevices, it) }
                        } else {
                            ColorOSMyDevicesToggle(glass, metrics.tile, labelsHidden, onOpenDevices, cell)
                        }
                    else ->
                        bySpec[entry.key]?.let { tile ->
                            if (span.colSpan > 1) {
                                top {
                                    with(infiniteGridLayout) {
                                        ColorOSWideTile(
                                            tile,
                                            listening,
                                            it,
                                            iconOnly = false,
                                            shared = false,
                                        )
                                    }
                                }
                            } else {
                                Box(cell, contentAlignment = Alignment.TopCenter) {
                                    with(infiniteGridLayout) {
                                        ColorOSWideTile(
                                            tile,
                                            listening,
                                            Modifier.fillMaxWidth()
                                                .wrapContentHeight(Alignment.Top, unbounded = true),
                                            iconOnly = true,
                                            shared = false,
                                        )
                                    }
                                }
                            }
                        }
                }
            },
        )
    }
}
