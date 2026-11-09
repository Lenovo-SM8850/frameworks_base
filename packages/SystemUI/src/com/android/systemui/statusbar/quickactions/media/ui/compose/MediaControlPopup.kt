/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.systemui.statusbar.quickactions.media.ui.compose

import android.content.Context
import android.database.ContentObserver
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.format.DateUtils
import android.widget.SeekBar
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.android.settingslib.widget.AdaptiveIcon
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon as UiIcon
import com.android.systemui.common.ui.compose.Icon as UiIconView
import com.android.systemui.media.controls.shared.model.MediaAction
import com.android.systemui.media.controls.ui.drawable.SquigglyProgress
import com.android.systemui.media.controls.ui.view.WaveformSeekBar
import com.android.systemui.media.remedia.shared.flag.MediaControlsInComposeFlag
import com.android.systemui.media.remedia.ui.compose.Media
import com.android.systemui.media.remedia.ui.compose.MediaPresentationStyle
import com.android.systemui.media.remedia.ui.compose.MediaUiBehavior
import com.android.systemui.media.remedia.ui.viewmodel.MediaCarouselVisibility
import com.android.systemui.res.R
import com.android.systemui.statusbar.quickactions.media.shared.model.MediaControlChipModel
import com.android.systemui.statusbar.quickactions.media.ui.viewmodel.MediaControlPopupViewModel
import com.android.systemui.statusbar.quickactions.ui.compose.MediaArtFlip
import com.android.systemui.statusbar.quickactions.ui.compose.LocalLaunchAfterPopupHidden
import com.android.systemui.statusbar.quickactions.ui.compose.LocalPopupMarqueeEnabled
import com.android.systemui.statusbar.quickactions.ui.compose.LocalPopupContainer
import com.android.systemui.statusbar.quickactions.ui.compose.PopupSurface
import com.android.systemui.statusbar.quickactions.ui.compose.islandGlassCards
import com.android.systemui.statusbar.quickactions.ui.compose.rememberAlbumArtAccent
import com.android.systemui.statusbar.quickactions.ui.compose.rememberAppIconBitmap
import com.android.systemui.statusbar.quickactions.ui.compose.rememberPopupLaunchAction
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

private val PopupShape = RoundedCornerShape(32.dp)
private val ArtworkShape = RoundedCornerShape(16.dp)
private val CosArtworkShape = RoundedCornerShape(12.dp)
private val CosAppIconSize = 28.dp

/** Displays a popup containing the Media controls in QuickActionsOverlay. */
@Composable
fun MediaControlPopup(viewModel: MediaControlPopupViewModel, modifier: Modifier = Modifier) {
    val viewModelFactory = viewModel.mediaViewModelFactory
    val mediaHost = viewModel.mediaHost
    if (MediaControlsInComposeFlag.isEnabled) {
        Media(
            viewModelFactory = viewModelFactory,
            presentationStyle = MediaPresentationStyle.Default,
            behavior =
                MediaUiBehavior(
                    carouselVisibility = MediaCarouselVisibility.WhenAnyCardIsActive,
                    isCarouselDismissible = false,
                    isCarouselScrollingEnabled = false,
                ),
            location = Media.Location.STATUS_BAR_POPUP,
            onDismissed = {},
            modifier =
                modifier
                    .width(400.dp)
                    .height(200.dp)
                    .clip(
                        shape =
                            RoundedCornerShape(
                                dimensionResource(R.dimen.notification_corner_radius)
                            )
                    ),
        )
    } else {
        AndroidView(
            modifier =
                modifier
                    .width(400.dp)
                    .height(200.dp)
                    .clip(
                        shape =
                            RoundedCornerShape(
                                dimensionResource(R.dimen.notification_corner_radius)
                            )
                    ),
            factory = { mediaHost.hostView },
        )
    }
}

/** Expanded media controls for the centered dynamic island. */
@Composable
fun MediaControlPopup(
    model: MediaControlChipModel,
    useWaveform: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // The ColorOS island style has its own card; the lyrics card below it is unaffected.
    if (islandGlassCards()) {
        CosMediaCard(model = model, modifier = modifier)
        return
    }
    val artworkDrawable =
        remember(model.artworkIcon) { (model.artworkIcon as? UiIcon.Loaded)?.drawable }
    val accent =
        rememberAlbumArtAccent(artworkDrawable, fallback = MaterialTheme.colorScheme.primary)
    val onAccent =
        if (artworkDrawable == null) MaterialTheme.colorScheme.onPrimary
        else if (accent.luminance() > 0.5f) Color.Black else Color.White
    val openApp = rememberPopupLaunchAction(model.openApp)
    val launchAfterHide = LocalLaunchAfterPopupHidden.current
    val openOutput: (() -> Unit)? =
        model.openOutputSwitcher?.let { open -> { open(launchAfterHide) } }
    val motionEnabled = rememberMarqueeMotionEnabled()
    val marqueeEnabled = LocalPopupMarqueeEnabled.current && motionEnabled
    val isLargeScreen = LocalConfiguration.current.smallestScreenWidthDp >= 600
    val sharesLyricsSurface = LocalPopupContainer.current &&
        (!model.lyrics.isNullOrBlank() || !model.syncedLyrics.isNullOrBlank())
    val customActions = model.customActions.filter { it.action != null && it.icon != null }.take(2)
    PopupSurface(shape = PopupShape, modifier = modifier.widthIn(min = 320.dp, max = 400.dp)) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(start = 16.dp, top = 14.dp, end = 16.dp,
                        bottom = if (sharesLyricsSurface) 0.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (isLargeScreen) 12.dp else 8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.clickable(enabled = openApp != null) { openApp?.invoke() }
                ) {
                    MediaArtFlip(
                        track = model.songName?.toString() to model.packageName,
                        artwork = model.artworkIcon,
                    ) { artworkIcon ->
                        MediaArtwork(model = model, artworkIcon = artworkIcon)
                    }
                }
                Column(
                    modifier =
                        Modifier.weight(1f).clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            enabled = openApp != null,
                        ) {
                            openApp?.invoke()
                        },
                    verticalArrangement = Arrangement.spacedBy(if (isLargeScreen) 6.dp else 2.dp),
                ) {
                    MediaMarqueeText(
                        enabled = marqueeEnabled,
                        text = model.songName?.toString().orEmpty(),
                        style =
                            MaterialTheme.typography.titleMedium.copy(
                                fontSize = 16.sp,
                                lineHeight = 20.sp,
                                fontWeight = FontWeight.SemiBold,
                            ),
                        color = LocalContentColor.current,
                    )
                    model.artistName
                        ?.takeIf { it.isNotBlank() }
                        ?.let { artist ->
                            MediaMarqueeText(
                                enabled = marqueeEnabled,
                                text = artist.toString(),
                                style =
                                    MaterialTheme.typography.bodyMedium.copy(
                                        fontSize = 13.sp,
                                        lineHeight = 18.sp,
                                        fontWeight = FontWeight.Normal,
                                    ),
                                color = LocalContentColor.current.copy(alpha = 0.8f),
                            )
                        }
                }
                MediaOutputChip(
                    model = model,
                    onClick = openOutput,
                    modifier = Modifier.widthIn(max = 120.dp).align(Alignment.Top),
                )
            }

            if (model.durationMs > 0L) {
                MediaProgressSection(
                    positionMs = model.positionMs,
                    positionUpdatedAtMs = model.positionUpdatedAtMs,
                    playbackSpeed = model.playbackSpeed,
                    durationMs = model.durationMs,
                    isPlaying = model.isPlaying,
                    canBeScrubbed = model.canBeScrubbed,
                    onSeekTo = model.seekTo,
                    accent = accent,
                    useWaveform = useWaveform,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    customActions.getOrNull(0)?.let { action ->
                        MediaActionButton(
                            action = action,
                            containerColor = LocalContentColor.current.copy(alpha = 0.11f),
                            iconTint = LocalContentColor.current,
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MediaActionButton(
                        action = model.previousAction,
                        iconOverrideId = R.drawable.ic_skip_previous_filled,
                        containerColor = LocalContentColor.current.copy(alpha = 0.11f),
                        iconTint = LocalContentColor.current,
                    )
                    MediaActionButton(
                        action = model.playOrPause,
                        containerColor = accent,
                        iconTint = onAccent,
                        buttonSize = 56.dp,
                        iconSize = 28.dp,
                        emphasized = true,
                    )
                    MediaActionButton(
                        action = model.nextAction,
                        iconOverrideId = R.drawable.ic_skip_next_filled,
                        containerColor = LocalContentColor.current.copy(alpha = 0.11f),
                        iconTint = LocalContentColor.current,
                    )
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    customActions.getOrNull(1)?.let { action ->
                        MediaActionButton(
                            action = action,
                            containerColor = LocalContentColor.current.copy(alpha = 0.11f),
                            iconTint = LocalContentColor.current,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberMarqueeMotionEnabled(): Boolean {
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
private fun MediaMarqueeText(text: String, style: TextStyle, color: Color, enabled: Boolean) {
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
private fun MediaOutputChip(
    model: MediaControlChipModel,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val name =
        model.outputDevice?.name?.toString() ?: stringResource(R.string.media_seamless_other_device)
    // The media card shows the same drawable; painting it here must not change its bounds.
    val outputIcon = model.outputDevice?.icon
    val icon =
        remember(outputIcon) {
            outputIcon?.let { drawable ->
                val source = if (drawable is AdaptiveIcon) drawable.getDrawable(1) else drawable
                UiIcon.Loaded(source.constantState?.newDrawable()?.mutate() ?: source, null)
            }
        } ?: UiIcon.Resource(R.drawable.ic_media_home_devices, null)
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
private fun MediaArtwork(model: MediaControlChipModel, artworkIcon: UiIcon?, cos: Boolean = false) {
    val contentDescription = model.appName?.let { ContentDescription.Loaded(it) }
    // Without artwork the app icon stands in; keep it icon-sized inside the artwork frame. The
    // artwork of a model without any is its app icon.
    val isArtwork = artworkIcon != null && artworkIcon !== model.appIcon
    val frameSize = if (cos) 56.dp else 64.dp
    val appIconSize = if (cos) 32.dp else 36.dp
    // The same loader as the corner icon of the ColorOS card, so both show the complete icon.
    val appIconBitmap =
        if (isArtwork) {
            null
        } else {
            rememberAppIconBitmap(
                model.packageName,
                (model.appIcon as? UiIcon.Loaded)?.drawable,
                appIconSize,
            )
        }
    Box(
        modifier =
            Modifier.size(frameSize)
                .clip(if (cos) CosArtworkShape else ArtworkShape)
                .background(LocalContentColor.current.copy(alpha = 0.08f)),
        contentAlignment = Alignment.Center,
    ) {
        if (artworkIcon != null && isArtwork) {
            UiIconView(
                icon = artworkIcon,
                modifier = Modifier.size(frameSize),
                tint = Color.Unspecified,
            )
        } else if (appIconBitmap != null) {
            Image(
                bitmap = appIconBitmap,
                contentDescription = model.appName,
                modifier = Modifier.size(appIconSize),
            )
        } else {
            UiIconView(
                icon =
                    UiIcon.Resource(
                        resId = com.android.internal.R.drawable.ic_audio_media,
                        contentDescription = contentDescription,
                    ),
                modifier = Modifier.size(appIconSize),
                tint = Color.Unspecified,
            )
        }
    }
}

/** Position of a session reported at [updatedMs], advanced to [nowMs] at [speed] while playing. */
private fun mediaPositionMs(
    positionMs: Long,
    updatedMs: Long,
    nowMs: Long,
    speed: Float,
    playing: Boolean,
    durationMs: Long,
): Long {
    val elapsed = if (playing && updatedMs > 0) (nowMs - updatedMs).coerceAtLeast(0) else 0
    val value =
        (positionMs.coerceAtLeast(0) + elapsed * (if (speed.isFinite()) speed.toDouble() else 0.0))
            .toLong()
            .coerceAtLeast(0)
    return if (durationMs > 0) value.coerceAtMost(durationMs) else value
}

@Composable
private fun MediaProgressSection(
    positionMs: Long,
    positionUpdatedAtMs: Long,
    playbackSpeed: Float,
    durationMs: Long,
    isPlaying: Boolean,
    canBeScrubbed: Boolean,
    onSeekTo: ((Long) -> Unit)?,
    accent: Color,
    useWaveform: Boolean = false,
) {
    var isScrubbing by remember { mutableStateOf(false) }
    var displayedPositionMs by remember { mutableStateOf(positionMs.coerceIn(0L, durationMs)) }
    val currentSeekAction by rememberUpdatedState(onSeekTo)

    LaunchedEffect(
        positionMs,
        positionUpdatedAtMs,
        playbackSpeed,
        durationMs,
        isPlaying,
        isScrubbing,
    ) {
        if (isScrubbing) {
            return@LaunchedEffect
        }

        fun currentPosition() =
            mediaPositionMs(
                positionMs,
                positionUpdatedAtMs,
                SystemClock.elapsedRealtime(),
                playbackSpeed,
                isPlaying,
                durationMs,
            )
        displayedPositionMs = currentPosition()

        if (!isPlaying || durationMs <= 0L || playbackSpeed == 0f || positionUpdatedAtMs <= 0L) {
            return@LaunchedEffect
        }

        while (!isScrubbing) {
            delay(250L)
            displayedPositionMs = currentPosition()
            if (displayedPositionMs >= durationMs || displayedPositionMs <= 0L) {
                break
            }
        }
    }

    val accentArgb = accent.toArgb()
    val trackAlphaArgb = accent.copy(alpha = 0.20f).toArgb()
    val timeStyle =
        MaterialTheme.typography.labelMedium.copy(
            fontSize = 11.sp,
            lineHeight = 14.sp,
            fontFeatureSettings = "tnum",
        )

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // The elapsed time reserves the width of the duration so the seek bar keeps its length.
        Box(contentAlignment = Alignment.CenterEnd) {
            Text(
                text = formatElapsedTime(durationMs),
                style = timeStyle,
                modifier = Modifier.alpha(0f).clearAndSetSemantics {},
            )
            Text(
                text = formatElapsedTime(displayedPositionMs),
                style = timeStyle,
                color = LocalContentColor.current.copy(alpha = 0.60f),
            )
        }

        Box(modifier = Modifier.weight(1f).height(24.dp)) {
            var boxWidthPx by remember { mutableStateOf(0) }

            fun applyFraction(fraction: Float) {
                displayedPositionMs = (fraction.coerceIn(0f, 1f) * durationMs).toLong()
            }
            val gestureModifier =
                Modifier.onSizeChanged { boxWidthPx = it.width }
                    .pointerInput(canBeScrubbed, durationMs) {
                        if (!canBeScrubbed) return@pointerInput
                        detectTapGestures { offset ->
                            applyFraction(offset.x / boxWidthPx.coerceAtLeast(1).toFloat())
                            currentSeekAction?.invoke(displayedPositionMs)
                        }
                    }
                    .pointerInput(canBeScrubbed, durationMs) {
                        if (!canBeScrubbed) return@pointerInput
                        detectHorizontalDragGestures(
                            onDragStart = { offset ->
                                isScrubbing = true
                                applyFraction(offset.x / boxWidthPx.coerceAtLeast(1).toFloat())
                            },
                            onDragEnd = {
                                currentSeekAction?.invoke(displayedPositionMs)
                                isScrubbing = false
                            },
                            onDragCancel = { isScrubbing = false },
                            onHorizontalDrag = { change, _ ->
                                applyFraction(
                                    change.position.x / boxWidthPx.coerceAtLeast(1).toFloat()
                                )
                                change.consume()
                            },
                        )
                    }

            if (useWaveform) {
                AndroidView(
                    modifier = Modifier.fillMaxWidth().height(24.dp).then(gestureModifier),
                    factory = { context ->
                        WaveformSeekBar(context).apply {
                            max = 10_000
                            followsMediaColors = false
                            setWaveformColor(accentArgb)
                            setThumbColor(accentArgb)
                            isEnabled = false
                        }
                    },
                    update = { bar ->
                        bar.setWaveformColor(accentArgb)
                        bar.setThumbColor(accentArgb)
                        val target =
                            if (durationMs > 0L) {
                                ((displayedPositionMs.toFloat() / durationMs) * 10_000f)
                                    .toInt()
                                    .coerceIn(0, 10_000)
                            } else 0
                        if (bar.progress != target) bar.progress = target
                        when {
                            isPlaying && !bar.isPlaying -> bar.startWaveAnimation()
                            !isPlaying && bar.isPlaying -> bar.stopWaveAnimation()
                        }
                    },
                )
            } else {
                AndroidView(
                    modifier = Modifier.fillMaxWidth().height(24.dp).then(gestureModifier),
                    factory = { context ->
                        SeekBar(context).apply {
                            max = durationMs.toClampedInt()
                            splitTrack = false
                            minHeight = (3f * context.resources.displayMetrics.density).toInt()
                            maxHeight = minHeight
                            setPadding(0, 0, 0, 0)
                            isEnabled = false
                            thumb = createSeekBarThumb(context, accentArgb)
                            val layer = progressDrawable?.mutate() as? LayerDrawable
                            if (layer != null) {
                                layer
                                    .findDrawableByLayerId(android.R.id.background)
                                    ?.mutate()
                                    ?.setTint(trackAlphaArgb)
                                layer
                                    .findDrawableByLayerId(android.R.id.secondaryProgress)
                                    ?.mutate()
                                    ?.setTint(
                                        com.android.internal.graphics.ColorUtils.setAlphaComponent(
                                            accentArgb,
                                            60,
                                        )
                                    )
                                val squiggle =
                                    SquigglyProgress().apply {
                                        waveLength =
                                            context.resources
                                                .getDimensionPixelSize(
                                                    R.dimen.qs_media_seekbar_progress_wavelength
                                                )
                                                .toFloat()
                                        lineAmplitude =
                                            context.resources
                                                .getDimensionPixelSize(
                                                    R.dimen.qs_media_seekbar_progress_amplitude
                                                )
                                                .toFloat()
                                        phaseSpeed =
                                            context.resources
                                                .getDimensionPixelSize(
                                                    R.dimen.qs_media_seekbar_progress_phase
                                                )
                                                .toFloat()
                                        strokeWidth = 3f * context.resources.displayMetrics.density
                                        setTint(accentArgb)
                                        drawRemainingLine = false
                                        transitionEnabled = true
                                        animate = false
                                    }
                                layer.setDrawableByLayerId(android.R.id.progress, squiggle)
                                progressDrawable = layer
                            }
                        }
                    },
                    update = { seekBar ->
                        seekBar.max = durationMs.toClampedInt().coerceAtLeast(1)
                        seekBar.thumb?.alpha = if (canBeScrubbed) 255 else 120
                        if (!isScrubbing) {
                            seekBar.progress =
                                displayedPositionMs.coerceIn(0L, durationMs).toClampedInt()
                        }

                        (seekBar.thumb as? GradientDrawable)?.setColor(accentArgb)

                        val layer = seekBar.progressDrawable as? LayerDrawable
                        layer
                            ?.findDrawableByLayerId(android.R.id.background)
                            ?.setTint(trackAlphaArgb)
                        layer
                            ?.findDrawableByLayerId(android.R.id.secondaryProgress)
                            ?.setTint(
                                com.android.internal.graphics.ColorUtils.setAlphaComponent(
                                    accentArgb,
                                    60,
                                )
                            )
                        val squiggle =
                            layer?.findDrawableByLayerId(android.R.id.progress) as? SquigglyProgress
                        squiggle?.apply {
                            setTint(accentArgb)
                            setAlpha(if (canBeScrubbed) 255 else 120)
                            animate = isPlaying && canBeScrubbed && !isScrubbing
                        }
                    },
                )
            }
        }

        Text(
            text = formatElapsedTime(durationMs),
            style = timeStyle,
            color = LocalContentColor.current.copy(alpha = 0.60f),
        )
    }
}

@Composable
private fun MediaActionButton(
    action: MediaAction?,
    containerColor: Color,
    iconTint: Color,
    buttonSize: Dp = 48.dp,
    iconSize: Dp = 24.dp,
    emphasized: Boolean = false,
    iconOverrideId: Int? = null,
) {
    val act = action
    if (act == null || (act.icon == null && iconOverrideId == null)) {
        Spacer(modifier = Modifier.size(buttonSize))
        return
    }

    var toggleCount by remember { mutableIntStateOf(0) }
    // The media card shows the same drawable; tinting it here must not change its colors.
    val actionIcon =
        remember(act.icon) { act.icon?.let { it.constantState?.newDrawable()?.mutate() ?: it } }
    val haptics = LocalHapticFeedback.current
    val contentDescription =
        act.contentDescription?.toString()?.let { ContentDescription.Loaded(it) }
    Box(
        modifier =
            Modifier.squishAnimation(toggleCount)
                .size(buttonSize)
                .clip(CircleShape)
                .background(containerColor)
                .clickable(enabled = act.action != null) {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    act.action?.run()
                    toggleCount++
                },
        contentAlignment = Alignment.Center,
    ) {
        val actualIcon =
            if (iconOverrideId != null) {
                UiIcon.Resource(iconOverrideId, contentDescription)
            } else {
                actionIcon?.let { UiIcon.Loaded(it, contentDescription) }
            }
        if (actualIcon != null) {
            UiIconView(
                icon = actualIcon,
                modifier = Modifier.size(iconSize).padding(start = if (emphasized) 1.dp else 0.dp),
                tint = iconTint,
            )
        }
    }
}

private fun createSeekBarThumb(context: Context, tintColor: Int): GradientDrawable {
    return GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 999f
        setColor(tintColor)
        setSize(
            (context.resources.displayMetrics.density * 4f).toInt().coerceAtLeast(1),
            (context.resources.displayMetrics.density * 18f).toInt().coerceAtLeast(1),
        )
    }
}

private fun Long.toClampedInt(): Int {
    return coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
}

private fun formatElapsedTime(milliseconds: Long): String {
    return DateUtils.formatElapsedTime(milliseconds / DateUtils.SECOND_IN_MILLIS)
}

@Composable
private fun Modifier.squishAnimation(toggleCount: Int): Modifier {
    val scaleX = remember { Animatable(1f, visibilityThreshold = 0.01f) }
    val scaleY = remember { Animatable(1f, visibilityThreshold = 0.01f) }
    val currentToggleCount by rememberUpdatedState(toggleCount)
    LaunchedEffect(Unit) {
        snapshotFlow { currentToggleCount }
            .drop(1)
            .collectLatest {
                scaleX.snapTo(1f)
                scaleY.snapTo(1f)
                coroutineScope {
                    launch {
                        scaleX.animateTo(
                            targetValue = 1f,
                            animationSpec =
                                keyframes {
                                    durationMillis = 400
                                    1.066f at 120 using FastOutSlowInEasing
                                    0.967f at 260
                                    1f at 400
                                },
                        )
                    }
                    launch {
                        scaleY.animateTo(
                            targetValue = 1f,
                            animationSpec =
                                keyframes {
                                    durationMillis = 400
                                    0.945f at 120 using FastOutSlowInEasing
                                    1.033f at 260
                                    1f at 400
                                },
                        )
                    }
                }
            }
    }
    return this.graphicsLayer {
        this.scaleX = scaleX.value
        this.scaleY = scaleY.value
    }
}
