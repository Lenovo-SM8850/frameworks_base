/*
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.miner7222.systemui.island

import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.settingslib.widget.AdaptiveIcon
import com.android.systemui.common.shared.model.Icon as UiIcon
import com.android.systemui.common.ui.compose.Icon as UiIconView
import com.android.systemui.media.controls.shared.model.MediaAction
import com.android.systemui.res.R
import com.android.systemui.statusbar.quickactions.media.shared.model.MediaControlChipModel
import com.android.systemui.statusbar.quickactions.media.ui.compose.MediaActionButton

internal val CosArtworkShape = RoundedCornerShape(12.dp)

internal val CosAppIconSize = 28.dp

@Composable
internal fun rememberMarqueeMotionEnabled(): Boolean {
    val context = LocalContext.current
    fun readEnabled() =
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) > 0f
    var enabled by remember(context) { mutableStateOf(readEnabled()) }
    DisposableEffect(context) {
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    enabled = readEnabled()
                }
            }
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        enabled = readEnabled()
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return enabled
}

@Composable
internal fun MediaMarqueeText(text: String, style: TextStyle, color: Color, enabled: Boolean) {
    val measurer = rememberTextMeasurer()
    val textWidth =
        remember(text, style, measurer) {
            measurer.measure(text, style, softWrap = false, maxLines = 1).size.width
        }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val scrolls = enabled && textWidth > constraints.maxWidth
        Text(
            text = text,
            style = style,
            color = color,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier.fillMaxWidth()
                    .clipToBounds()
                    .then(
                        if (scrolls)
                            Modifier.basicMarquee(
                                iterations = Int.MAX_VALUE,
                                initialDelayMillis = 1500,
                                repeatDelayMillis = 2000,
                            )
                        else Modifier
                    ),
        )
    }
}

@Composable
internal fun MediaOutputChip(
    model: MediaControlChipModel,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val name =
        model.outputDevice?.name?.toString() ?: stringResource(R.string.media_seamless_other_device)
    val icon = rememberMediaOutputIcon(model)
    val foreground = LocalContentColor.current
    Box(modifier = modifier.widthIn(max = 180.dp)) {
        Row(
            modifier =
                Modifier.height(36.dp)
                    .clip(RoundedCornerShape(50))
                    .background(foreground.copy(alpha = 0.1f))
                    .alpha(if (onClick != null) 1f else 0.38f)
                    .clickable(enabled = onClick != null, role = Role.Button) { onClick?.invoke() }
                    .semantics(mergeDescendants = true) { contentDescription = name }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UiIconView(icon = icon, modifier = Modifier.size(18.dp), tint = foreground)
            Text(
                text = name,
                style = MaterialTheme.typography.labelMedium,
                color = foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).clearAndSetSemantics {},
            )
        }
    }
}

@Composable
private fun rememberMediaOutputIcon(model: MediaControlChipModel): UiIcon {
    // The media card shows the same drawable; painting it here must not change its bounds.
    val outputIcon = model.outputDevice?.icon
    return remember(outputIcon) {
        outputIcon?.let { drawable ->
            val source = if (drawable is AdaptiveIcon) drawable.getDrawable(1) else drawable
            UiIcon.Loaded(source.constantState?.newDrawable()?.mutate() ?: source, null)
        }
    } ?: UiIcon.Resource(R.drawable.ic_media_home_devices, null)
}

/** The controls row of the ColorOS style: plain glyphs, with only the play button filled. */
@Composable
internal fun CosMediaControls(
    model: MediaControlChipModel,
    appAction: MediaAction?,
    onOutputClick: (() -> Unit)?,
) {
    val foreground = LocalContentColor.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MediaOutputIconButton(model = model, onClick = onOutputClick)
        MediaActionButton(
            action = model.previousAction,
            iconOverrideId = R.drawable.ic_skip_previous_filled,
            containerColor = Color.Transparent,
            iconTint = foreground,
            buttonSize = 40.dp,
        )
        MediaActionButton(
            action = model.playOrPause,
            containerColor = foreground,
            iconTint = MaterialTheme.colorScheme.surface,
            buttonSize = 50.dp,
            iconSize = 26.dp,
            emphasized = true,
        )
        MediaActionButton(
            action = model.nextAction,
            iconOverrideId = R.drawable.ic_skip_next_filled,
            containerColor = Color.Transparent,
            iconTint = foreground,
            buttonSize = 40.dp,
        )
        if (appAction != null) {
            // The app's own glyph, so each app shows its own heart, thumb or plus.
            MediaActionButton(
                action = appAction,
                containerColor = Color.Transparent,
                iconTint = foreground,
                buttonSize = 40.dp,
                keepAppColors = true,
            )
        } else {
            // Keeps the other buttons where they are without the action.
            Spacer(modifier = Modifier.size(40.dp))
        }
    }
}

/** The output device as a bare icon; it opens the same switcher as [MediaOutputChip]. */
@Composable
private fun MediaOutputIconButton(model: MediaControlChipModel, onClick: (() -> Unit)?) {
    val name =
        model.outputDevice?.name?.toString() ?: stringResource(R.string.media_seamless_other_device)
    val icon = rememberMediaOutputIcon(model)
    Box(
        modifier =
            Modifier.size(40.dp)
                .clip(CircleShape)
                .alpha(if (onClick != null) 1f else 0.38f)
                .clickable(enabled = onClick != null, role = Role.Button) { onClick?.invoke() }
                .semantics(mergeDescendants = true) { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        UiIconView(
            icon = icon,
            modifier = Modifier.size(24.dp).clearAndSetSemantics {},
            tint = LocalContentColor.current,
        )
    }
}

/**
 * Whether the glyph has colour of its own. Most apps send plain glyphs, which take the card's
 * content colour; a coloured one, such as a green check for a saved track, keeps its colours.
 */
internal fun Drawable.hasOwnColor(): Boolean {
    // The media card shows the same drawable; drawing here must not change its bounds.
    val copy = constantState?.newDrawable()?.mutate() ?: this
    val oldBounds = if (copy === this) Rect(copy.bounds) else null
    val side = 32
    val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
    return try {
        copy.setBounds(0, 0, side, side)
        copy.draw(Canvas(bitmap))
        val hsv = FloatArray(3)
        var colored = 0
        for (x in 0 until side) {
            for (y in 0 until side) {
                val pixel = bitmap.getPixel(x, y)
                if ((pixel ushr 24) < 128) continue
                android.graphics.Color.colorToHSV(pixel, hsv)
                if (hsv[1] > 0.35f && hsv[2] > 0.2f) colored++
            }
        }
        // A few saturated pixels are antialiasing, not colour.
        colored > side * side / 40
    } catch (e: RuntimeException) {
        false
    } finally {
        oldBounds?.let { copy.setBounds(it.left, it.top, it.right, it.bottom) }
        bitmap.recycle()
    }
}
