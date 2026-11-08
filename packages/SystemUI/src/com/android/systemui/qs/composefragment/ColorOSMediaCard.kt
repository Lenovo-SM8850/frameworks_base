/*
 * SPDX-FileCopyrightText: MiraCLO
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.composefragment

import android.app.PendingIntent
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.core.graphics.drawable.toBitmap
import com.android.systemui.media.controls.domain.pipeline.MediaDataManager
import com.android.systemui.media.controls.shared.MediaControlDrawables
import com.android.systemui.media.controls.shared.model.MediaAction
import com.android.systemui.media.controls.shared.model.MediaData
import com.android.systemui.res.R
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * keeps the media sessions shown in the ColorOS-style QS media slot.
 *
 * Fed by [MediaDataManager], which is the media pipeline in both the legacy (view) and the compose
 * media modes, so it works whichever one the device uses (some builds still use the legacy one).
 */
class ColorOSMediaSessions : MediaDataManager.Listener {
    private val entries = mutableStateMapOf<String, MediaData>()

    /** The session to show: a playing one first, otherwise the latest active one. */
    val current: MediaData?
        get() {
            val active = entries.values.filter { it.active }
            return active.lastOrNull { it.isPlaying == true } ?: active.lastOrNull()
        }

    override fun onMediaDataLoaded(
        key: String,
        oldKey: String?,
        data: MediaData,
        immediately: Boolean,
    ) {
        if (oldKey != null && oldKey != key) entries.remove(oldKey)
        // Re-insert so the most recently updated session is last.
        entries.remove(key)
        entries[key] = data
    }

    override fun onMediaDataRemoved(key: String, userInitiated: Boolean) {
        entries.remove(key)
    }
}

/** Height of the card the sizes below are drawn for, the two tile rows of a phone. */
private val ColorOSMediaBaseHeight = 145.dp

/** Text and buttons grow at half the rate of the card, to stay near the labels around it. */
private fun colorOSMediaScale(extraHeight: Dp): Float =
    1f + extraHeight / ColorOSMediaBaseHeight / 2f

private val LocalColorOSMediaScale = compositionLocalOf { 1f }

/** Card insets and artwork dimensions. */
private class ColorOSMediaLayoutDimensions(extraHeight: Dp) {
    val top = 14.dp
    val start = 16.dp + extraHeight * 0.10f
    val end = 12.dp + extraHeight * 0.10f
    val bottom = 8.dp
    val artSize = 50.dp
    val controlsInset = 5.dp + extraHeight * 0.10f
    val outputTop = 8.dp + extraHeight * 0.15f
    val artShape = RoundedCornerShape(10.dp + extraHeight * 0.05f)
}

private val ColorOSMediaButtonSize = 44.dp
private val ColorOSMediaOutputIconSize = 24.dp

/** The headphones of the output button are larger than its other icons, like in ColorOS. */
private val ColorOSMediaHeadphonesIconSize = 32.dp

/** The icon of the device the sound goes to, when it is not headphones. */
private val ColorOSMediaDeviceIconSize = 26.dp

/**
 * How much larger the text of the idle card is than the title style it is set in. The playing
 * card keeps its own title and artist sizes, only the idle text is scaled.
 */
private const val ColorOSIdleTextScale = 1.16f

/** The icon of the app in the top left while the art fills the card, as big as the output one. */
private val ColorOSMediaAppIconSize = ColorOSMediaOutputIconSize

/** Corner radius of the glass slot the card sits on, which the light along its edge follows. */
private val ColorOSMediaLightCorner = 32.dp

/** Colour of the text and icons of the card while the album art fills it. */
private val ColorOSMediaOnArt = Color.White

/** Darkening of the album art behind the text and the controls, from the top to the bottom. */
private const val ColorOSMediaScrimTop = 0.25f
private const val ColorOSMediaScrimBottom = 0.60f

/** Alpha of a control that cannot be used, like the dimmed glyphs of ColorOS when idle. */
private const val ColorOSMediaDimAlpha = 0.35f

/**
 * Media card for the ColorOS-style Quick Settings (two columns of the tile grid), laid out like
 * ColorOS: album art thumbnail at the top left, output switcher at the top right, title and artist,
 * then previous, play/pause and next in one row. The glass behind it comes from the slot, so the
 * card itself draws no background. Tapping the card opens the media app.
 */
@Composable
internal fun ColorOSMediaCard(
    data: MediaData,
    onOpen: (PendingIntent) -> Unit,
    onOutputSwitcher: (MediaData) -> Unit,
    modifier: Modifier = Modifier,
    artFill: Boolean = false,
    extraHeight: Dp = 0.dp,
) {
    val context = LocalContext.current
    val artwork by
        produceState<ImageBitmap?>(initialValue = null, data.artwork) {
            value =
                data.artwork?.let { icon ->
                    withContext(Dispatchers.IO) {
                        runCatching { icon.loadDrawable(context)?.toBitmap()?.asImageBitmap() }
                            .getOrNull()
                    }
                }
        }
    val buttons = data.semanticActions
    val playing = data.isPlaying == true
    val outputEnabled = data.device?.enabled != false && !data.resumption
    val scale = colorOSMediaScale(extraHeight)
    val deviceIcon = rememberDrawableBitmap(data.device?.icon, ColorOSMediaDeviceIconSize * scale)
    val headphones = rememberColorOSHeadphonesOutput()
    // With the art filling the card the text and icons sit on the art and not on the glass, so they
    // are light whatever the theme is. Without art there is nothing to fill, the thumbnail layout
    // stays.
    val art = artwork
    val fillArt = artFill && art != null
    // The thumbnail is gone while the art fills the card, the icon of the app takes its place.
    val appIcon by
        produceState<Drawable?>(initialValue = null, data.appIcon, fillArt) {
            value =
                data.appIcon?.takeIf { fillArt }?.let { icon ->
                    withContext(Dispatchers.IO) {
                        runCatching { icon.loadDrawable(context) }.getOrNull()
                    }
                }
        }
    val appIconBitmap = rememberDrawableBitmap(appIcon, ColorOSMediaAppIconSize * scale)
    val scheme = MaterialTheme.colorScheme
    val contentScheme =
        if (fillArt) {
            scheme.copy(onSurface = ColorOSMediaOnArt, onSurfaceVariant = ColorOSMediaOnArt)
        } else {
            scheme
        }
    val onSurface = contentScheme.onSurface

    val previousGlyph = painterResource(R.drawable.ic_coloros_media_previous)
    val nextGlyph = painterResource(R.drawable.ic_coloros_media_next)
    val playPauseGlyph =
        painterResource(
            if (playing) R.drawable.ic_coloros_media_pause else R.drawable.ic_coloros_media_play
        )

    // Only the standard previous and next actions get the ColorOS glyphs. The slots can also hold
    // a custom action of the app (like, shuffle, ...), which keeps the icon the app provides.
    val prev = buttons?.prevOrCustom
    val next = buttons?.nextOrCustom
    val prevIsStandard = prev != null && prev.icon === MediaControlDrawables.getPrevIcon(context)
    val nextIsStandard = next != null && next.icon === MediaControlDrawables.getNextIcon(context)

    // The light in the color of the art starts from the thumbnail. With the art over the whole
    // card the art is the light, so it has none.
    val density = LocalDensity.current
    val dimensions = ColorOSMediaLayoutDimensions(extraHeight)
    val thumbnailCenter: (Float) -> Offset = { cardHeight ->
        with(density) {
            val tall = extraHeight > 0.dp
            val artSize = if (tall) cardHeight * 0.30f else dimensions.artSize.toPx()
            val top = if (tall) cardHeight * 0.10f else dimensions.top.toPx()
            Offset(dimensions.start.toPx() + artSize / 2, top + artSize / 2)
        }
    }
    Box(
        modifier
            // The sweep goes round the card over the art and the light, when the track changes.
            .colorOSStatusGlow(
                data.song?.toString() to data.artist?.toString(),
                ColorOSMediaLightCorner,
            )
            .then(
                if (fillArt) {
                    Modifier
                } else {
                    Modifier.colorOSMediaLight(art, ColorOSMediaLightCorner, thumbnailCenter)
                }
            )
    ) {
        if (fillArt && art != null) {
            Image(
                bitmap = art,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
            // Keeps the text and the controls readable over bright art.
            Box(
                Modifier.matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = ColorOSMediaScrimTop),
                                Color.Black.copy(alpha = ColorOSMediaScrimBottom),
                            )
                        )
                    )
            )
        }
        MaterialTheme(colorScheme = contentScheme) {
            ColorOSMediaLayout(
                modifier = Modifier.fillMaxSize(),
                extraHeight = extraHeight,
                showThumbnail = !fillArt,
                appIcon = appIconBitmap.takeIf { fillArt },
                description = listOfNotNull(data.song, data.artist, data.app).joinToString(", "),
                onClick = data.clickIntent?.let { intent -> { onOpen(intent) } },
                thumbnail = {
                    val bitmap = artwork
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        ColorOSMediaNoteTile()
                    }
                },
                output = {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier =
                            Modifier.size(ColorOSMediaButtonSize * scale)
                                .clip(CircleShape)
                                .clickable(enabled = outputEnabled) { onOutputSwitcher(data) }
                                .semantics {
                                    contentDescription = data.device?.name?.toString().orEmpty()
                                },
                    ) {
                        val tint =
                            onSurface.copy(alpha = if (outputEnabled) 1f else ColorOSMediaDimAlpha)
                        if (headphones) {
                            Icon(
                                painter = painterResource(R.drawable.ic_coloros_volume_headphones),
                                contentDescription = null,
                                tint = tint,
                                modifier = Modifier.size(ColorOSMediaHeadphonesIconSize * scale),
                            )
                        } else if (deviceIcon != null) {
                            Icon(
                                bitmap = deviceIcon,
                                contentDescription = null,
                                tint = tint,
                                modifier = Modifier.size(ColorOSMediaDeviceIconSize * scale),
                            )
                        } else {
                            Icon(
                                painter = painterResource(R.drawable.ic_media_home_devices),
                                contentDescription = null,
                                tint = tint,
                                modifier = Modifier.size(ColorOSMediaDeviceIconSize * scale),
                            )
                        }
                    }
                },
                title = data.song?.toString().orEmpty(),
                subtitle = data.artist?.toString().orEmpty(),
                controls = {
                    ColorOSMediaButton(
                        action = prev,
                        glyph = if (prevIsStandard) previousGlyph else null,
                        baseIconSize = 22.dp,
                    )
                    ColorOSMediaButton(
                        action = buttons?.playOrPause,
                        glyph = playPauseGlyph,
                        baseIconSize = 26.dp,
                    )
                    ColorOSMediaButton(
                        action = next,
                        glyph = if (nextIsStandard) nextGlyph else null,
                        baseIconSize = 22.dp,
                    )
                },
            )
        }
    }
}

/**
 * The media card of the ColorOS-style Quick Settings while nothing is playing: the same layout as
 * the playing card, with a placeholder tile, the cast icon and dimmed controls, like ColorOS.
 */
@Composable
internal fun ColorOSIdleMediaCard(modifier: Modifier = Modifier, extraHeight: Dp = 0.dp) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    ColorOSMediaLayout(
        modifier = modifier,
        extraHeight = extraHeight,
        description = stringResource(R.string.qs_coloros_no_media),
        onClick = null,
        thumbnail = { ColorOSMediaNoteTile() },
        output = {
            val scale = colorOSMediaScale(extraHeight)
            Box(Modifier.size(ColorOSMediaButtonSize * scale), contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(R.drawable.ic_coloros_media_output),
                    contentDescription = null,
                    tint = onSurface,
                    modifier = Modifier.size(ColorOSMediaOutputIconSize * scale),
                )
            }
        },
        title = null,
        subtitle = stringResource(R.string.qs_coloros_no_media),
        controls = {
            ColorOSMediaButton(
                action = null,
                glyph = painterResource(R.drawable.ic_coloros_media_previous),
                baseIconSize = 22.dp,
            )
            ColorOSMediaButton(
                action = null,
                glyph = painterResource(R.drawable.ic_coloros_media_play),
                baseIconSize = 26.dp,
            )
            ColorOSMediaButton(
                action = null,
                glyph = painterResource(R.drawable.ic_coloros_media_next),
                baseIconSize = 22.dp,
            )
        },
    )
}

/**
 * Layout shared by the playing and the idle card. [title] is skipped when null, so the idle text
 * sits on the artist line. The top left corner holds the thumbnail, or with [showThumbnail] off the
 * [appIcon], if there is one.
 */
@Composable
private fun ColorOSMediaLayout(
    modifier: Modifier,
    extraHeight: Dp = 0.dp,
    showThumbnail: Boolean = true,
    appIcon: ImageBitmap? = null,
    description: String,
    onClick: (() -> Unit)?,
    thumbnail: @Composable () -> Unit,
    output: @Composable () -> Unit,
    title: String?,
    subtitle: String,
    controls: @Composable () -> Unit,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val scale = colorOSMediaScale(extraHeight)
    val dimensions = ColorOSMediaLayoutDimensions(extraHeight)
    BoxWithConstraints(modifier) {
        // A taller card places its content by its height, which differs with the orientation.
        val tall = extraHeight > 0.dp
        val top = if (tall) maxHeight * 0.10f else dimensions.top
        val artSize = if (tall) maxHeight * 0.30f else dimensions.artSize
        val bottom =
            if (tall) {
                maxOf(maxHeight * 0.17f - ColorOSMediaButtonSize * scale / 2, 0.dp)
            } else {
                dimensions.bottom
            }
        val textGap = if (tall) maxHeight * 0.02f else 0.dp
        Column(
            modifier =
                Modifier.fillMaxSize()
                    .clickable(enabled = onClick != null) { onClick?.invoke() }
                    .semantics { contentDescription = description }
                    // Side insets are applied per item; the extended ColorOS QS card also
                    // raises the controls to give the bottom edge more breathing room.
                    .padding(top = top, bottom = bottom)
        ) {
            val textInset = Modifier.padding(start = dimensions.start, end = dimensions.end)
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                if (showThumbnail) {
                    Box(
                        Modifier.padding(start = dimensions.start)
                            .size(minOf(artSize, maxHeight))
                            .clip(dimensions.artShape)
                    ) {
                        thumbnail()
                    }
                } else if (appIcon != null) {
                    Icon(
                        bitmap = appIcon,
                        contentDescription = null,
                        tint = onSurface,
                        modifier =
                            Modifier.padding(start = dimensions.start)
                                .size(minOf(ColorOSMediaAppIconSize * scale, maxHeight)),
                    )
                }
            }
            if (title != null) {
                Text(
                    text = title,
                    modifier = textInset,
                    color = onSurface,
                    style = MaterialTheme.typography.titleSmall.scaled(scale),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(textGap))
            }
            Text(
                text = subtitle,
                modifier = textInset,
                color =
                    if (title != null) onSurface.copy(alpha = 0.75f)
                    else onSurface.copy(alpha = 0.5f),
                style =
                    if (title != null) MaterialTheme.typography.bodySmall.scaled(scale)
                    else MaterialTheme.typography.titleSmall.scaled(ColorOSIdleTextScale * scale),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                // Keep 44dp targets while adding side insets in the extended card.
                modifier =
                    Modifier.fillMaxWidth()
                        .padding(
                            start = dimensions.controlsInset,
                            end = dimensions.controlsInset,
                            top = 1.dp,
                        ),
            ) {
                CompositionLocalProvider(LocalColorOSMediaScale provides scale) { controls() }
            }
        }
        // The output button follows the extended card's top and end insets too.
        Box(
            Modifier.align(Alignment.TopEnd)
                .padding(top = dimensions.outputTop, end = dimensions.controlsInset)
        ) {
            output()
        }
    }
}

/** This style with the font size and the line height multiplied by [factor]. */
private fun TextStyle.scaled(factor: Float): TextStyle =
    copy(
        fontSize = if (fontSize.isSpecified) fontSize * factor else fontSize,
        lineHeight = if (lineHeight.isSpecified) lineHeight * factor else lineHeight,
    )

/** Placeholder for the album art: a faint tile with a music note. */
@Composable
private fun ColorOSMediaNoteTile() {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.12f)),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_music_note),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            modifier = Modifier.size(24.dp),
        )
    }
}

/**
 * One transport button. Uses [glyph] when given, otherwise the icon of [action] (a custom action
 * of the app). Without a usable action the button is dimmed, and a null [action] with no [glyph]
 * leaves an empty slot, so the other buttons keep their place.
 */
@Composable
private fun ColorOSMediaButton(action: MediaAction?, glyph: Painter?, baseIconSize: Dp) {
    val scale = LocalColorOSMediaScale.current
    val iconSize = baseIconSize * scale
    val enabled = action?.action != null
    val tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else ColorOSMediaDimAlpha)
    val actionIcon = rememberDrawableBitmap(if (glyph == null) action?.icon else null, iconSize, actionIcon = true)
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier.size(ColorOSMediaButtonSize * scale)
                .clip(CircleShape)
                .clickable(enabled = enabled) { action?.action?.run() }
                .semantics { contentDescription = action?.contentDescription?.toString().orEmpty() },
    ) {
        if (glyph != null) {
            Icon(
                painter = glyph,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(iconSize),
            )
        } else if (actionIcon != null) {
            Icon(
                bitmap = actionIcon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

/**
 * Returns a static snapshot of a drawable of the media pipeline (an action icon or the output
 * device icon).
 *
 * These [Drawable]s are shared with the stock media player (the QQS/QS player binds the very same
 * instance to its ImageButton), so they must never be handed to a Compose painter.
 * `rememberDrawablePainter` takes over the drawable: it replaces its callback, starts it when it is
 * an [android.graphics.drawable.Animatable] and overwrites its colour filter. For the play/pause
 * AnimatedVectorDrawable that runs the pause to play morph, which left the stock QQS player showing
 * a play glyph while media was playing, and the white filter beat the player's own tint, giving a
 * white icon on its light button.
 *
 * So a private copy is drawn once into a bitmap instead, which shows the icon in its initial,
 * non-animated frame and leaves the original untouched.
 */
@Composable
private fun rememberDrawableBitmap(
    icon: Drawable?,
    size: Dp,
    actionIcon: Boolean = false,
): ImageBitmap? {
    val resources = LocalContext.current.resources
    val sizePx = with(LocalDensity.current) { size.roundToPx() }.coerceAtLeast(1)
    return remember(icon, sizePx, actionIcon) {
        snapshotColorOSMediaDrawable(icon, resources, sizePx, actionIcon)?.asImageBitmap()
    }
}

internal fun snapshotColorOSMediaDrawable(
    source: Drawable?,
    resources: android.content.res.Resources,
    sizePx: Int,
    actionIcon: Boolean,
): android.graphics.Bitmap? = source?.let {
    val copy = it.constantState?.newDrawable(resources)?.mutate() ?: it
    if (actionIcon && copy !== source) {
        copy.state = source.state.filterNot {
            it == -android.R.attr.state_enabled || it == android.R.attr.state_pressed ||
                it == android.R.attr.state_focused || it == android.R.attr.state_hovered
        }.plus(android.R.attr.state_enabled).distinct().toIntArray()
        copy.level = source.level
        copy.alpha = 255
        copy.clearColorFilter()
        copy.setTint(android.graphics.Color.WHITE)
        copy.jumpToCurrentState()
    }
    runCatching {
        val w = copy.intrinsicWidth
        val h = copy.intrinsicHeight
        if (w <= 0 || h <= 0 || w == h) {
            copy.toBitmap(sizePx, sizePx)
        } else {
            // An app's icon need not be square: fit it in the square instead of stretching it.
            val fit = sizePx / maxOf(w, h).toFloat()
            val dw = (w * fit).roundToInt().coerceAtLeast(1)
            val dh = (h * fit).roundToInt().coerceAtLeast(1)
            val bitmap =
                android.graphics.Bitmap.createBitmap(
                    sizePx,
                    sizePx,
                    android.graphics.Bitmap.Config.ARGB_8888,
                )
            val left = (sizePx - dw) / 2
            val top = (sizePx - dh) / 2
            copy.setBounds(left, top, left + dw, top + dh)
            copy.draw(android.graphics.Canvas(bitmap))
            bitmap
        }
    }.getOrNull()
}
