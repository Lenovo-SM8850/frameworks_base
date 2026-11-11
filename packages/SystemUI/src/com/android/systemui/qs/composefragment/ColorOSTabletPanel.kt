/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.composefragment

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.settingslib.volume.domain.interactor.AudioVolumeInteractor
import com.android.systemui.common.ui.compose.PagerDots
import com.android.systemui.grid.ui.compose.GridSpan
import com.android.systemui.grid.ui.compose.PlacedGridItem
import com.android.systemui.grid.ui.compose.TwoDimensionalSpannedGrid
import com.android.systemui.grid.ui.compose.calculate2DPlacements
import com.android.systemui.qs.panels.ui.compose.infinitegrid.ColorOSGap
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults
import com.android.systemui.res.R
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color

/** Items of the large screen ColorOS panel that are not tiles, placed in its grid like tiles. */
internal object ColorOSTabletItem {
    const val MEDIA = "coloros_media"
    const val BRIGHTNESS = "coloros_brightness"
    const val VOLUME = "coloros_volume"
    const val RINGER = "coloros_ringer"
    const val MY_DEVICES = "coloros_mydevices"

    val ALL = listOf(MEDIA, BRIGHTNESS, VOLUME, RINGER, MY_DEVICES)

    fun isItem(key: String) = key in ALL

    /** Items that are one cell or a two column pill, like a tile. */
    fun isResizable(key: String) = key == RINGER || key == MY_DEVICES
}

/** Secure setting with the order of the panel's tiles and items, and the small items. */
internal const val COLOROS_TABLET_LAYOUT_SETTING = "sysui_coloros_tablet_layout"

/** One entry of the large screen panel: a tile spec or an item, and whether an item is small. */
internal data class ColorOSTabletEntry(val key: String, val small: Boolean = false)

internal fun parseColorOSTabletLayout(raw: String?): List<ColorOSTabletEntry>? =
    raw
        ?.takeIf { it.isNotBlank() }
        ?.split(",")
        ?.mapNotNull { item ->
            val parts = item.trim().split(":")
            parts[0].takeIf { it.isNotEmpty() }?.let {
                ColorOSTabletEntry(it, small = parts.getOrNull(1) == "s")
            }
        }
        ?.distinctBy { it.key }

internal fun formatColorOSTabletLayout(entries: List<ColorOSTabletEntry>): String =
    entries.joinToString(",") { if (it.small) "${it.key}:s" else it.key }

internal fun readColorOSTabletLayout(context: Context): List<ColorOSTabletEntry>? =
    parseColorOSTabletLayout(
        Settings.Secure.getStringForUser(
            context.contentResolver,
            COLOROS_TABLET_LAYOUT_SETTING,
            UserHandle.USER_CURRENT,
        )
    )

internal fun writeColorOSTabletLayout(context: Context, entries: List<ColorOSTabletEntry>) {
    Settings.Secure.putStringForUser(
        context.contentResolver,
        COLOROS_TABLET_LAYOUT_SETTING,
        formatColorOSTabletLayout(entries),
        UserHandle.USER_CURRENT,
    )
}

/** The saved order of the panel, null before it was ever edited, kept up to date. */
@Composable
internal fun rememberColorOSTabletLayout(): State<List<ColorOSTabletEntry>?> {
    val context = LocalContext.current
    val state = remember(context) { mutableStateOf(readColorOSTabletLayout(context)) }
    DisposableEffect(context) {
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    state.value = readColorOSTabletLayout(context)
                }
            }
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(COLOROS_TABLET_LAYOUT_SETTING),
            false,
            observer,
            UserHandle.USER_ALL,
        )
        state.value = readColorOSTabletLayout(context)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return state
}

/**
 * The entries of the panel in order: the saved ones that still exist and then the tiles added since.
 * Before the panel was edited, the Wi-Fi and Bluetooth tiles come first, then the media card, the
 * sliders, the ringer switch and My Devices, as on ColorOS, then the other tiles. A device with
 * mobile data has its tile there instead of My Devices.
 */
internal fun colorOSTabletEntries(
    saved: List<ColorOSTabletEntry>?,
    tileSpecs: List<String>,
): List<ColorOSTabletEntry> {
    if (saved == null) {
        val first = tileSpecs.filter { it in ColorOSTabletFirstTiles }
        val cell = ColorOSTabletCellTile.takeIf { it in tileSpecs }
        val items =
            if (cell == null) ColorOSTabletItem.ALL
            else ColorOSTabletItem.ALL.map { if (it == ColorOSTabletItem.MY_DEVICES) cell else it }
        return (first + items + tileSpecs.filterNot { it in first || it == cell }).map {
            ColorOSTabletEntry(it)
        }
    }
    val present = tileSpecs.toSet()
    val kept = saved.filter { ColorOSTabletItem.isItem(it.key) || it.key in present }
    val keys = kept.mapTo(HashSet()) { it.key }
    return kept + tileSpecs.filterNot { it in keys }.map { ColorOSTabletEntry(it) }
}

private val ColorOSTabletFirstTiles = setOf("internet", "wifi", "bt", "bluetooth")

/** The mobile data tile, which takes the place of My Devices in the default layout. */
internal const val ColorOSTabletCellTile = "cell"

/** Cells an entry takes: the media card 2x2, a slider 1x2, a pill 2x1 and a round tile 1x1. */
internal fun colorOSTabletSpan(entry: ColorOSTabletEntry, large: Set<String>): GridSpan =
    when (entry.key) {
        ColorOSTabletItem.MEDIA -> GridSpan(colSpan = 2, rowSpan = 2)
        ColorOSTabletItem.BRIGHTNESS,
        ColorOSTabletItem.VOLUME -> GridSpan(colSpan = 1, rowSpan = 2)
        ColorOSTabletItem.RINGER,
        ColorOSTabletItem.MY_DEVICES -> GridSpan(colSpan = if (entry.small) 1 else 2, rowSpan = 1)
        else -> GridSpan(colSpan = if (entry.key in large) 2 else 1, rowSpan = 1)
    }

/** Sizes of the large screen panel: its tile (pill height, slider width) and its gap. */
internal data class ColorOSTabletMetrics(val tile: Dp, val gap: Dp) {
    val width: Dp
        get() = tile * ColorOSTabletColumns + gap * (ColorOSTabletColumns - 1)
}

internal const val ColorOSTabletColumns = 4

/** ColorOS tablets have a 15dp gap to a 56dp pill. */
private const val ColorOSTabletGapRatio = 15f / 56f

/**
 * Height of a landscape screen around the grid of the panel: the status bar, the space above the
 * grid, the page dots and the footer under it.
 */
private const val ColorOSTabletChromeDp = 142

/**
 * The tile of the large screen panel: the largest that fits the landscape panel with its default
 * six rows, three of them round tiles with labels, so it is the same in both orientations and on
 * every page. The gap keeps the ColorOS proportion.
 */
internal fun colorOSTabletMetrics(shortSideDp: Int, labelHeight: Dp): ColorOSTabletMetrics {
    val height = (shortSideDp - ColorOSTabletChromeDp).dp - labelHeight * 3
    val tile = (height / (6f + 5f * ColorOSTabletGapRatio)).coerceIn(52.dp, 80.dp)
    return ColorOSTabletMetrics(tile, tile * ColorOSTabletGapRatio)
}

/** The panel has the tile and the gap of its editor, the same everywhere. */
@Composable
internal fun rememberColorOSTabletMetrics(): ColorOSTabletMetrics =
    ColorOSTabletMetrics(CommonTileDefaults.ColorOSTileHeight, ColorOSGap)

/** Whether a round one cell entry has a label under it, which makes its row taller. */
private fun GridSpan.isLabelled() = colSpan == 1 && rowSpan == 1

/** Rows of [placements] that hold a labelled entry, by their index in the page. */
private fun labelledRows(placements: List<PlacedGridItem>, spans: List<GridSpan>): Set<Int> =
    placements.filter { spans[it.index].isLabelled() }.mapTo(HashSet()) { it.row }

/** Height of the rows of a page, with the labelled ones taller by [label]. */
private fun pageHeight(
    placements: List<PlacedGridItem>,
    spans: List<GridSpan>,
    metrics: ColorOSTabletMetrics,
    label: Dp,
): Dp {
    val rows = placements.maxOfOrNull { it.row + it.rowSpan } ?: 0
    if (rows == 0) return 0.dp
    // The labels of the last row hang under it.
    return metrics.tile * rows + colorOSTabletRowGap(metrics, label) * (rows - 1) + label
}

/** Gap between the rows of the panel: room for the labels of round tiles, the gap without them. */
internal fun colorOSTabletRowGap(metrics: ColorOSTabletMetrics, label: Dp): Dp =
    maxOf(metrics.gap, label)

/** One page of the panel: the indices of its entries and where they are, from row 0. */
internal data class ColorOSTabletPage(val indices: List<Int>, val placements: List<PlacedGridItem>)

/**
 * The pages of the panel: as many rows a page as fit [available], at least two, flowing [spans] in
 * order over the four columns. Each placement is reindexed to its page.
 */
internal fun colorOSTabletPages(
    spans: List<GridSpan>,
    metrics: ColorOSTabletMetrics,
    label: Dp,
    available: Dp,
): List<ColorOSTabletPage> {
    fun paged(rows: Int): List<ColorOSTabletPage> {
        val placed = calculate2DPlacements(spans, ColorOSTabletColumns, maxRowsPerPage = rows)
        return placed
            .groupBy { it.row / rows }
            .toSortedMap()
            .values
            .map { items ->
                val indices = items.map { it.index }
                ColorOSTabletPage(
                    indices,
                    items.mapIndexed { i, p -> p.copy(index = i, row = p.row % rows) },
                )
            }
            .ifEmpty { listOf(ColorOSTabletPage(emptyList(), emptyList())) }
    }
    if (available == Dp.Unspecified || available <= 0.dp) return paged(ColorOSTabletDefaultRows)
    for (rows in ColorOSTabletMaxRows downTo 2) {
        val pages = paged(rows)
        val fits =
            pages.all { page ->
                pageHeight(page.placements, page.indices.map { spans[it] }, metrics, label) <=
                    available
            }
        if (fits) return pages
    }
    return paged(2)
}

private const val ColorOSTabletDefaultRows = 6
private const val ColorOSTabletMaxRows = 10

/** Height of the page dots under the grid of the large screen panel. */
private val ColorOSTabletDotsHeight = 20.dp

/**
 * The grid of the large screen panel: every tile and item on four columns, in pages. [item] draws
 * one entry in the cells it is given. The pager spans the width it is given so the next page slides
 * in whole, and each page keeps to the four columns in its middle.
 */
@Composable
internal fun ColorOSTabletGrid(
    entries: List<ColorOSTabletEntry>,
    spans: List<GridSpan>,
    metrics: ColorOSTabletMetrics,
    label: Dp,
    availableHeight: Dp,
    fullWidth: Dp,
    item: @Composable (entry: ColorOSTabletEntry, span: GridSpan, modifier: Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gridHeight =
        if (availableHeight == Dp.Unspecified) Dp.Unspecified
        else availableHeight - ColorOSTabletDotsHeight
    val pages =
        remember(spans, metrics, label, gridHeight) {
            colorOSTabletPages(spans, metrics, label, gridHeight)
        }
    val pagerState = rememberPagerState { pages.size }
    val inset = ((fullWidth - metrics.width) / 2).coerceAtLeast(0.dp)
    Column(modifier.requiredWidth(fullWidth), horizontalAlignment = Alignment.CenterHorizontally) {
        HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(horizontal = inset),
            pageSpacing = inset,
            beyondViewportPageCount = 1,
            verticalAlignment = Alignment.Top,
            modifier = Modifier.fillMaxWidth(),
        ) { pageIndex ->
            val page = pages[pageIndex]
            val pageSpans = page.indices.map { spans[it] }
            TwoDimensionalSpannedGrid(
                columns = ColorOSTabletColumns,
                columnSpacing = metrics.gap,
                rowSpacing = colorOSTabletRowGap(metrics, label),
                spans = pageSpans,
                customPlacements = page.placements,
                rowHeight = metrics.tile,
                // The labels under the last row stay above the page dots.
                modifier = Modifier.width(metrics.width).padding(bottom = label),
                keys = { entries[page.indices[it]].key },
            ) { index, _ ->
                item(entries[page.indices[index]], pageSpans[index], Modifier)
            }
        }
        if (pages.size > 1) {
            Box(Modifier.height(ColorOSTabletDotsHeight), contentAlignment = Alignment.Center) {
                PagerDots(
                    pagerState = pagerState,
                    activeColor = MaterialTheme.colorScheme.onSurface,
                    nonActiveColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                    roundDots = true,
                )
            }
        }
    }
}

/**
 * The ringer switch as a round tile: its glyph shows the mode and a tap goes to the next one, ring,
 * vibrate and silent, with the mode as its label.
 */
@Composable
internal fun ColorOSRingerToggle(
    audioVolumeInteractor: AudioVolumeInteractor,
    glass: ColorOSGlass,
    size: Dp,
    labelsHidden: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val audioManager = remember(context) { context.getSystemService(AudioManager::class.java) }
    val ringerMode by audioVolumeInteractor.ringerMode.collectAsStateWithLifecycle()
    val (icon, label, next) =
        when (ringerMode.value) {
            AudioManager.RINGER_MODE_VIBRATE ->
                Triple(
                    R.drawable.ic_volume_ringer_vibrate,
                    R.string.volume_ringer_status_vibrate,
                    AudioManager.RINGER_MODE_SILENT,
                )
            AudioManager.RINGER_MODE_SILENT ->
                Triple(
                    R.drawable.ic_volume_ringer_mute,
                    R.string.volume_ringer_status_silent,
                    AudioManager.RINGER_MODE_NORMAL,
                )
            else ->
                Triple(
                    R.drawable.ic_volume_ringer,
                    R.string.volume_ringer_status_normal,
                    AudioManager.RINGER_MODE_VIBRATE,
                )
        }
    val (fill, glyph) = colorOSRingerActiveColors(ringerMode.value, glass.dark)
    ColorOSRoundItem(
        icon = icon,
        label = stringResource(label),
        glass = glass,
        size = size,
        active = fill to glyph,
        labelsHidden = labelsHidden,
        onClick = { audioManager?.setRingerModeInternal(next) },
        modifier = modifier,
    )
}

/** My Devices as a round tile, which opens the Bluetooth devices. */
@Composable
internal fun ColorOSMyDevicesToggle(
    glass: ColorOSGlass,
    size: Dp,
    labelsHidden: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ColorOSRoundItem(
        icon = R.drawable.ic_coloros_my_devices,
        label = stringResource(R.string.qs_coloros_my_devices),
        glass = glass,
        size = size,
        labelsHidden = labelsHidden,
        onClick = onClick,
        modifier = modifier,
    )
}

/** A round glass item with a glyph and a label under it, like a round tile. */
@Composable
private fun ColorOSRoundItem(
    icon: Int,
    label: String,
    glass: ColorOSGlass,
    size: Dp,
    labelsHidden: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** The fill and glyph colour of an item that is lit, as a tile that is on; null when off. */
    active: Pair<Color, Color>? = null,
) {
    Column(
        modifier.wrapContentHeight(Alignment.Top, unbounded = true),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier.size(size)
                    .glassSurface(glass, CircleShape)
                    .then(
                        if (active != null) Modifier.background(active.first, CircleShape)
                        else Modifier
                    )
                    .clickable(onClick = onClick)
                    .semantics {
                        role = Role.Button
                        contentDescription = label
                    },
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = active?.second ?: MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(size * CommonTileDefaults.ColorOSIconRatio),
            )
        }
        if (!labelsHidden) {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurface,
                style =
                    MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        lineHeight = CommonTileDefaults.ColorOSLabelLineHeight,
                    ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp).fillMaxWidth(),
            )
        }
    }
}
