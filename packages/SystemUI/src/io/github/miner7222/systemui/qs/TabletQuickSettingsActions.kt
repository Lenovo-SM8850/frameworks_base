/*
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.miner7222.systemui.qs

import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.compose.animation.Expandable
import com.android.systemui.animation.Expandable
import com.android.systemui.qs.footer.ui.compose.rememberSystemSettingEnabled
import com.android.systemui.shade.QsHeaderImageTint
import com.android.systemui.qs.panels.ui.compose.infinitegrid.LocalColorOSGlassBlur
import com.android.systemui.res.R
import com.android.systemui.qs.composefragment.ColorOSHeaderButton
import com.android.systemui.qs.composefragment.ColorOSHeaderButtonSize
import com.android.systemui.qs.composefragment.colorOSGlass
import com.android.systemui.qs.composefragment.glassSurface

/**
 * Height a large screen panel keeps at its top for the edit and settings buttons, the buttons and
 * the gap under them. Its own header stays as it is.
 */
internal val ColorOSTabletHeaderHeight = ColorOSHeaderButtonSize + ColorOSTabletGap

/** The glass of the header buttons, for buttons drawn by another component. */
@Composable
internal fun Modifier.colorOSHeaderGlass(shape: Shape): Modifier =
    glassSurface(colorOSGlass(blurred = LocalColorOSGlassBlur.current != null), shape)

/** Size of the round buttons in the top row of the large screen panel. */
internal val ColorOSTabletHeaderButtonSize = ColorOSHeaderButtonSize

/**
 * Size of the footer buttons of the large screen panel: smaller than the header buttons of a phone,
 * as the edit and settings buttons of the ColorOS tablet panel.
 */
internal val ColorOSTabletFooterButtonSize = 34.dp

/**
 * The edit button at the start of the top row of the large screen panel, with the glyph as large as
 * the footer buttons beside it. The footer setting that hides it leaves its place empty.
 */
@Composable
internal fun ColorOSTabletEditButton(
    size: Dp = ColorOSTabletFooterButtonSize,
    modifier: Modifier = Modifier,
    onEdit: (Expandable) -> Unit,
) {
    val showEdit by rememberSystemSettingEnabled(Settings.System.QS_FOOTER_SHOW_EDIT)
    if (!showEdit) {
        Spacer(modifier.size(size))
        return
    }
    Box(modifier) { ColorOSHeaderButton(
        iconRes = R.drawable.ic_edit,
        iconSize = ColorOSTabletHeaderIconSize,
        region = QsHeaderImageTint.Region.EDIT,
        description = stringResource(R.string.accessibility_quick_settings_edit),
        glass = colorOSGlass(blurred = LocalColorOSGlassBlur.current != null),
        onClick = onEdit,
        size = size,
    ) }
}

/** Size of the stock footer buttons, which the large screen panel keeps. */
internal val ColorOSTabletStockButtonSize = 40.dp

/** Glyph size of the footer buttons of the large screen panel. */
private val ColorOSTabletHeaderIconSize = 18.dp
