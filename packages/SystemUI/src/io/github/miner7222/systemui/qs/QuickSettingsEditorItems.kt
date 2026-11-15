/*
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.miner7222.systemui.qs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.systemui.qs.panels.ui.compose.infinitegrid.ColorOSGap
import com.android.systemui.qs.panels.ui.compose.infinitegrid.ColorOSIconRowGap
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults
import com.android.systemui.qs.panels.ui.compose.infinitegrid.colorOSTileRim
import com.android.systemui.res.R
import com.android.systemui.grid.ui.compose.GridSpan
import com.android.systemui.qs.composefragment.ColorOSTabletCellTile
import com.android.systemui.qs.composefragment.ColorOSTabletItem
import com.android.systemui.qs.composefragment.EditorHorizontalPadding

/** Widest editor on a tablet: four tile columns with their gaps and the padding around them. */
internal val ColorOSEditorTabletMaxWidth =
    (CommonTileDefaults.ColorOSTileHeight + ColorOSGap) * 4 + EditorHorizontalPadding * 2

/** The label of an item of the large screen panel that is not a tile. */
@Composable
internal fun colorOSItemLabel(key: String): String =
    stringResource(
        when (key) {
            ColorOSTabletItem.MEDIA -> R.string.controls_media_title
            ColorOSTabletItem.BRIGHTNESS -> R.string.quick_settings_brightness_dialog_title
            ColorOSTabletItem.VOLUME -> R.string.media_output_dialog_accessibility_seekbar
            ColorOSTabletItem.RINGER -> R.string.volume_ringer_status_normal
            else -> R.string.qs_coloros_my_devices
        }
    )

private fun colorOSItemIcon(key: String): Int =
    when (key) {
        ColorOSTabletItem.MEDIA -> R.drawable.ic_music_note
        ColorOSTabletItem.BRIGHTNESS -> R.drawable.ic_coloros_brightness
        ColorOSTabletItem.VOLUME -> R.drawable.ic_coloros_volume
        ColorOSTabletItem.RINGER -> R.drawable.ic_volume_ringer
        else -> R.drawable.ic_coloros_my_devices
    }

/**
 * An item of the large screen panel in the editor, in its cells: a card for the media card, the
 * sliders and the wide items, a round tile with its label for a one cell item.
 */
@Composable
internal fun EditorItemTile(
    key: String,
    span: GridSpan,
    badge: (@Composable () -> Unit)?,
    isLifted: Boolean = false,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val fill =
        if (isLifted) {
            if (dark) Color.White.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.85f)
        } else {
            if (dark) Color.White.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.55f)
        }
    val label = colorOSItemLabel(key)
    val round = span.colSpan == 1 && span.rowSpan == 1
    // Like the tiles, an item is as tall as the tiles of its rows and the gaps between them, and the
    // space for the labels under its last row is the gap below it.
    Box(
        if (round) Modifier.fillMaxWidth()
        else
            Modifier.fillMaxWidth()
                .height(
                    CommonTileDefaults.ColorOSTileHeight + EditorRowPitch * (span.rowSpan - 1)
                )
                .padding(horizontal = 4.dp)
    ) {
        if (round) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(CommonTileDefaults.ColorOSTileHeight)
                        .clip(CircleShape)
                        .background(fill, CircleShape)
                        .border(1.dp, colorOSTileRim(), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(colorOSItemIcon(key)),
                        contentDescription = null,
                        tint = onSurface,
                        modifier = Modifier.size(CommonTileDefaults.LargeTileIconSize),
                    )
                }
                Text(
                    text = label,
                    color = onSurface.copy(alpha = 0.9f),
                    fontSize = 12.sp,
                    lineHeight = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp),
                )
            }
        } else {
            if (span.rowSpan == 1) {
                // A one row item is a pill with its icon and label in a row, as the wide tiles
                // beside it.
                val pill = RoundedCornerShape(percent = 50)
                Row(
                    Modifier.matchParentSize()
                        .clip(pill)
                        .background(fill, pill)
                        .border(1.dp, colorOSTileRim(), pill)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(colorOSItemIcon(key)),
                        contentDescription = null,
                        tint = onSurface,
                        modifier = Modifier.size(CommonTileDefaults.LargeTileIconSize),
                    )
                    Text(
                        text = label,
                        color = onSurface.copy(alpha = 0.9f),
                        fontSize = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 10.dp).weight(1f),
                    )
                }
                if (badge != null) {
                    Box(Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp)) {
                        badge()
                    }
                }
                return@Box
            }
            val shape = RoundedCornerShape(24.dp)
            Column(
                Modifier.matchParentSize()
                    .clip(shape)
                    .background(fill, shape)
                    .border(1.dp, colorOSTileRim(), shape)
                    .padding(8.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    painter = painterResource(colorOSItemIcon(key)),
                    contentDescription = null,
                    tint = onSurface,
                    modifier = Modifier.size(CommonTileDefaults.LargeTileIconSize),
                )
                Text(
                    text = label,
                    color = onSurface.copy(alpha = 0.9f),
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        if (badge != null) {
            Box(Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp)) { badge() }
        }
    }
}

/**
 * Tiles that are wide in the panel's default layout, as the Wi-Fi, Bluetooth and mobile data cards
 * of ColorOS.
 */
internal val ColorOSTabletDefaultWide =
    setOf("internet", "wifi", "bt", "bluetooth", ColorOSTabletCellTile)

/** Height of a row of the editor: a round tile, its label and the gap to the next row. */
internal val EditorRowPitch =
    CommonTileDefaults.ColorOSTileHeight + CommonTileDefaults.ClassicLabelHeight + ColorOSIconRowGap
