/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.composefragment

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.android.systemui.qs.panels.ui.compose.infinitegrid.ColorOSGlassBlur
import com.android.systemui.qs.panels.ui.compose.infinitegrid.LocalColorOSGlassBlur

/**
 * How much of the panel is out for the given [expansion], 0 when closed and 1 when open. The
 * modules cascade with it and their glass fades with them, so they follow the drag from the quick
 * settings to the panel and are never out of step: the glass is drawn by SurfaceFlinger and has no
 * other way to know how far the panel has come. Each module has its own window of it, see
 * [colorOSCascadeProgress].
 */
internal fun colorOSExpansionReveal(expansion: Float): Float = expansion.coerceIn(0f, 1f)

/** The launch of an activity from the shade has run this far before the glass starts to go out. */
private const val ColorOSLaunchGlassFadeStart = 0.7f

/** The glass is gone at this point of the launch, the app covers the panel by then. */
private const val ColorOSLaunchGlassFadeEnd = 0.9f

/**
 * How much of the glass is left while an activity opens from the shade, [progress] is how far the
 * launch has run. The glass is drawn by SurfaceFlinger, and it hears that a region is gone with
 * the next frame the window draws. The shade is closed in a single step when the launch ends, and
 * the window does not draw after that, so the regions would stay for a few frames over the app,
 * without the content they belong to. They are taken out while the launch is still drawing
 * frames, which the app covers.
 */
internal fun colorOSLaunchGlassFade(progress: Float): Float =
    1f -
        ((progress - ColorOSLaunchGlassFadeStart) /
                (ColorOSLaunchGlassFadeEnd - ColorOSLaunchGlassFadeStart))
            .coerceIn(0f, 1f)

/** The fixed row at the top of the panel starts to come in at this fraction of the panel. */
private const val ColorOSFixedStart = 0.05f

/** The media card and the sliders start to come in here, and the fixed row is done. */
private const val ColorOSMediaStart = 0.50f

/** The rows of tiles start to come in here, and the media card and the sliders are done. */
private const val ColorOSRowsStart = 0.86f

/**
 * The quick settings held open on the lockscreen start to fade out at this point of how far out
 * they really are, as they close. Their scene stays fully expanded there until they are closed, so
 * the cascade does not see the close while the notifications come up over the modules from below.
 */
internal const val ColorOSKeyguardCloseFadeStart = ColorOSRowsStart

/**
 * They are gone at this point. The lockscreen panel closes to no height at all, so the
 * notifications have come up over only the lowest part of the panel by then, and the modules above
 * it are gone before their content is clipped and their glass is left without it.
 */
private const val ColorOSKeyguardCloseFadeEnd = 0.70f

/**
 * How much of the modules and of their glass is left as the quick settings held open on the
 * lockscreen close, from 1 to 0. [expansion] is how far out they really are, 1 when they are not
 * closing there.
 */
internal fun colorOSKeyguardCloseFade(expansion: Float): Float =
    ((expansion - ColorOSKeyguardCloseFadeEnd) /
            (ColorOSKeyguardCloseFadeStart - ColorOSKeyguardCloseFadeEnd))
        .coerceIn(0f, 1f)

/**
 * How much of the glass behind the shade is left as the quick settings held open on the lockscreen
 * close, from 1 to 0. The shade stays fully out there, so without this the glass would stay at full
 * strength until they are closed and then go at once.
 */
internal fun colorOSKeyguardCloseBackdrop(expansion: Float): Float =
    (expansion / ColorOSKeyguardCloseFadeStart).coerceIn(0f, 1f)

/** Part of the panel by which each row of tiles starts after the one above it. */
private const val ColorOSRowStep = 0.01f

/** The size the fixed row, the first of the rows, starts at, and what each row after it loses. */
private const val ColorOSRowScaleFrom = 0.80f
private const val ColorOSRowScaleStep = 0.05f

/** The size the media card and the sliders start at. */
private const val ColorOSMediaScaleFrom = 0.72f

/** How much faster than the module itself it fades in. */
private const val ColorOSCascadeAlphaGain = 1.6f

/** How far a module sits above its place when it starts, per row of tiles that is above it. */
private val ColorOSCascadeDrop = 22.dp

/** The module's row among the rows of tiles: the fixed row is the first, the media card is none. */
private fun colorOSRowIndex(index: Int): Int = if (index <= 1) 0 else index - 2

private fun colorOSCascadeStart(index: Int): Float =
    when (index) {
        1 -> ColorOSFixedStart
        2 -> ColorOSMediaStart
        else -> ColorOSRowsStart + ColorOSRowStep * (index - 3)
    }

private fun colorOSCascadeEnd(index: Int): Float =
    when (index) {
        1 -> ColorOSMediaStart
        2 -> ColorOSRowsStart
        else -> 1f
    }

private fun colorOSCascadeScaleFrom(index: Int): Float =
    if (index == 2) ColorOSMediaScaleFrom
    else (ColorOSRowScaleFrom - ColorOSRowScaleStep * colorOSRowIndex(index)).coerceAtLeast(0.5f)

/** How far module [index] has come in, from 0 to 1, when [shown] of the panel is out. */
private fun colorOSCascadeProgress(shown: Float, index: Int): Float {
    val start = colorOSCascadeStart(index)
    val span = colorOSCascadeEnd(index) - start
    return ((shown - start) / span).coerceIn(0f, 1f)
}

private fun colorOSCascadeAlpha(shown: Float, index: Int): Float =
    (colorOSCascadeProgress(shown, index) * ColorOSCascadeAlphaGain).coerceAtMost(1f)

/**
 * Like ColorOS, the panel unfolds as a cascade that follows the finger: the fixed row first, then
 * the media card and the sliders, and last the rows of tiles, each growing, dropping in and fading
 * in from its own point of the pull. Closing, the bottom modules lift away first. [reveal] is how
 * much of the panel is out, from 0 to 1, and [index] counts from 1 at the top.
 */
@Composable
internal fun Modifier.colorOSModuleReveal(index: Int, reveal: () -> Float): Modifier {
    val drop =
        with(LocalDensity.current) {
            val rows = if (index == 2) 1 else colorOSRowIndex(index)
            (ColorOSCascadeDrop * rows).toPx()
        }
    val scaleFrom = colorOSCascadeScaleFrom(index)
    return graphicsLayer {
        val shown = reveal()
        val eased = LinearOutSlowInEasing.transform(colorOSCascadeProgress(shown, index))
        translationY = -drop * (1f - eased)
        val scale = scaleFrom + (1f - scaleFrom) * eased
        scaleX = scale
        scaleY = scale
        alpha = colorOSCascadeAlpha(shown, index)
    }
}

/**
 * The glass under a module is a window blur, which takes neither the fade nor the movement of the
 * module: it fades with the module here, so each tile keeps its outline while it shows.
 */
@Composable
internal fun ColorOSModuleGlass(index: Int, reveal: () -> Float, content: @Composable () -> Unit) {
    val outer = LocalColorOSGlassBlur.current
    if (outer == null) {
        content()
        return
    }
    val currentReveal by rememberUpdatedState(reveal)
    val blur =
        remember(outer, index) {
            ColorOSGlassBlur { outer.alpha() * colorOSCascadeAlpha(currentReveal(), index) }
        }
    CompositionLocalProvider(LocalColorOSGlassBlur provides blur, content = content)
}
