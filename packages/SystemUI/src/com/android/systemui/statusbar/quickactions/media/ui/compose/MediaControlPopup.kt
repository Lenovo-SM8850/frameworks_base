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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import com.android.systemui.statusbar.quickactions.ui.compose.LocalLaunchAfterPopupHidden
import com.android.systemui.statusbar.quickactions.ui.compose.LocalPopupMarqueeEnabled
import com.android.systemui.statusbar.quickactions.ui.compose.PopupSurface
import com.android.systemui.statusbar.quickactions.ui.compose.rememberPopupLaunchAction
import io.github.miner7222.systemui.lyrics.LyricTiming
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

private val PopupShape = RoundedCornerShape(34.dp)
private val ArtworkShape = RoundedCornerShape(24.dp)

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
    val accent = MaterialTheme.colorScheme.primary
    val openApp = rememberPopupLaunchAction(model.openApp)
    val launchAfterHide = LocalLaunchAfterPopupHidden.current
    val openOutput: (() -> Unit)? =
        model.openOutputSwitcher?.let { open -> { open(launchAfterHide) } }
    val motionEnabled = rememberMarqueeMotionEnabled()
    val marqueeEnabled = LocalPopupMarqueeEnabled.current && motionEnabled
    var popupWidth by remember { mutableIntStateOf(0) }
    val compactOutput = popupWidth < with(LocalDensity.current) { 320.dp.toPx() }
    PopupSurface(shape = PopupShape, modifier = modifier.widthIn(min = 320.dp, max = 400.dp)) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 18.dp)
                    .onSizeChanged { popupWidth = it.width },
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier =
                        Modifier.weight(1f).clickable(enabled = openApp != null) {
                            openApp?.invoke()
                        }
                ) {
                    model.appName
                        ?.takeIf { it.isNotBlank() }
                        ?.let { appName ->
                            MediaMarqueeText(
                                text = appName.uppercase(),
                                style =
                                    MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.SemiBold
                                    ),
                                color = accent.copy(alpha = 0.82f),
                                enabled = marqueeEnabled,
                            )
                        }
                }
                MediaOutputChip(model = model, onClick = openOutput, iconOnly = compactOutput)
            }
            Row(
                modifier =
                    Modifier.fillMaxWidth().clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = openApp != null,
                    ) {
                        openApp?.invoke()
                    },
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MediaArtwork(model = model)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    MediaMarqueeText(
                        enabled = marqueeEnabled,
                        text = model.songName?.toString().orEmpty(),
                        style =
                            MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                        color = LocalContentColor.current,
                    )
                    model.artistName
                        ?.takeIf { it.isNotBlank() }
                        ?.let { artist ->
                            MediaMarqueeText(
                                enabled = marqueeEnabled,
                                text = artist.toString(),
                                style = MaterialTheme.typography.bodyLarge,
                                color = LocalContentColor.current.copy(alpha = 0.8f),
                            )
                        }
                }
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
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
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
                    iconTint = MaterialTheme.colorScheme.onPrimary,
                    buttonSize = 72.dp,
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
    iconOnly: Boolean,
) {
    val name =
        model.outputDevice?.name?.toString() ?: stringResource(R.string.media_seamless_other_device)
    val icon =
        model.outputDevice?.icon?.let { drawable ->
            UiIcon.Loaded(if (drawable is AdaptiveIcon) drawable.getDrawable(1) else drawable, null)
        } ?: UiIcon.Resource(R.drawable.ic_media_home_devices, null)
    val foreground = LocalContentColor.current
    BoxWithConstraints(modifier = Modifier.widthIn(max = 180.dp)) {
        // A narrow popup reserves its text column by using the QS icon-only variant.
        Row(
            modifier =
                Modifier.clip(RoundedCornerShape(50))
                    .background(foreground.copy(alpha = 0.1f))
                    .alpha(if (onClick != null) 1f else 0.38f)
                    .clickable(enabled = onClick != null, role = Role.Button) { onClick?.invoke() }
                    .semantics(mergeDescendants = true) { contentDescription = name }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UiIconView(icon = icon, modifier = Modifier.size(18.dp), tint = foreground)
            if (!iconOnly)
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
private fun MediaArtwork(model: MediaControlChipModel) {
    val contentDescription = model.appName?.let { ContentDescription.Loaded(it) }
    val artwork =
        model.artworkIcon
            ?: model.appIcon
            ?: UiIcon.Resource(
                resId = com.android.internal.R.drawable.ic_audio_media,
                contentDescription = contentDescription,
            )
    // Without artwork the app icon stands in; keep it icon-sized inside the artwork frame.
    val isArtwork = model.artworkIcon != null && model.artworkIcon !== model.appIcon
    Box(
        modifier =
            Modifier.size(76.dp)
                .clip(ArtworkShape)
                .background(LocalContentColor.current.copy(alpha = 0.08f)),
        contentAlignment = Alignment.Center,
    ) {
        UiIconView(
            icon = artwork,
            modifier = Modifier.size(if (isArtwork) 76.dp else 40.dp),
            tint = Color.Unspecified,
        )
    }
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

    LaunchedEffect(positionMs, positionUpdatedAtMs, playbackSpeed, durationMs, isPlaying, isScrubbing) {
        if (isScrubbing) {
            return@LaunchedEffect
        }

        fun currentPosition() =
            LyricTiming.position(
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
            delay(16L)
            displayedPositionMs = currentPosition()
            if (displayedPositionMs >= durationMs || displayedPositionMs <= 0L) {
                break
            }
        }
    }

    val accentArgb = accent.toArgb()
    val trackAlphaArgb = accent.copy(alpha = 0.20f).toArgb()
    val timeStyle = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum")

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

        Box(modifier = Modifier.weight(1f).height(28.dp)) {
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
                    modifier = Modifier.fillMaxWidth().height(28.dp).then(gestureModifier),
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
                    modifier = Modifier.fillMaxWidth().height(28.dp).then(gestureModifier),
                    factory = { context ->
                        SeekBar(context).apply {
                            max = durationMs.toClampedInt()
                            splitTrack = false
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
                                        strokeWidth =
                                            context.resources
                                                .getDimensionPixelSize(
                                                    R.dimen.qs_media_seekbar_progress_stroke_width
                                                )
                                                .toFloat()
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
    buttonSize: Dp = 54.dp,
    iconSize: Dp = 22.dp,
    emphasized: Boolean = false,
    iconOverrideId: Int? = null,
) {
    val act = action
    if (act == null || (act.icon == null && iconOverrideId == null)) {
        Spacer(modifier = Modifier.size(buttonSize))
        return
    }

    var toggleCount by remember { mutableIntStateOf(0) }
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
                act.icon?.let { UiIcon.Loaded(it, contentDescription) }
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
