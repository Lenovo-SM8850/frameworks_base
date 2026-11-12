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

package com.android.systemui.statusbar.quickactions.ui.compose

import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.axion.blur.AxBlurSurface
import com.android.axion.blur.AxBlurSurfaceDefaults
import com.android.axion.blur.BlurEngine
import com.android.axion.blur.LocalAxBlurEnabled
import com.android.axion.blur.settings.AxBackdropBlurSettingsSpec
import com.android.internal.graphics.drawable.BackgroundBlurDrawable
import com.android.systemui.qs.shared.style.LiquidGlassTune
import com.android.systemui.qs.shared.style.liquidGlassEnabled
import com.android.systemui.qs.shared.style.liquidGlassRim
import com.android.systemui.statusbar.quickactions.popups.shared.DynamicIslandFeatureSettings.POPUP_COLOR_MODE_BLUR
import com.android.systemui.statusbar.quickactions.popups.shared.DynamicIslandFeatureSettings.POPUP_COLOR_MODE_SOLID_BLACK
import com.android.systemui.statusbar.quickactions.popups.shared.DynamicIslandFeatureSettings.observeDynamicIslandPopupColorMode
import com.android.systemui.statusbar.quickactions.popups.shared.DynamicIslandFeatureSettings.readDynamicIslandPopupColorMode
import kotlin.math.roundToInt

/**
 * How much of the window blur behind island glass shows. The blur is drawn by the window, not
 * the layer, so a parent that fades or tucks a card away passes its fade on through this.
 */
internal val LocalIslandGlassAlpha = compositionLocalOf<() -> Float> { { 1f } }

@Composable
private fun Modifier.plainBlurBehind(shape: Shape, tint: Int, radius: Dp): Modifier {
    val view = LocalView.current
    val context = LocalContext.current
    val holder = remember(view) { arrayOfNulls<BackgroundBlurDrawable>(1) }
    val glassAlpha = LocalIslandGlassAlpha.current
    val blurEnabled =
        remember(context) {
            context.getSystemService(WindowManager::class.java)?.isCrossWindowBlurEnabled == true
        }
    DisposableEffect(view) { onDispose { holder[0]?.setVisible(false, false) } }
    return drawBehind {
        val root = view.viewRootImpl
        if (!blurEnabled || root == null) {
            // Without blur a light glass tint would leave white text on white.
            drawOutline(shape.createOutline(size, layoutDirection, this), GlassNoBlurFill)
            return@drawBehind
        }
        val blur =
            holder[0]
                ?: root.createBackgroundBlurDrawable().also {
                    it.setXfermode(null)
                    holder[0] = it
                }
        val corner =
            when (val outline = shape.createOutline(size, layoutDirection, this)) {
                is Outline.Rounded -> outline.roundRect.topLeftCornerRadius.x
                else -> 0f
            }
        blur.setVisible(true, false)
        blur.setMaterial(PLAIN_BLUR)
        blur.setColor(tint)
        blur.alpha = (glassAlpha().coerceIn(0f, 1f) * 255).roundToInt()
        blur.setBlurRadius(radius.toPx().toInt())
        blur.setCornerRadius(corner)
        blur.setBounds(0, 0, size.width.toInt(), size.height.toInt())
        drawIntoCanvas { blur.draw(it.nativeCanvas) }
    }
}

@Composable
fun rememberPopupColorMode(): Int {
    val context = LocalContext.current
    val initial = remember(context) { context.contentResolver.readDynamicIslandPopupColorMode() }
    val flow = remember(context) { observeDynamicIslandPopupColorMode(context) }
    val mode by flow.collectAsState(initial = initial)
    return mode
}

/** Whether the popup content sits on a dark surface: the black background or Liquid Glass. */
internal val LocalPopupDarkSurface = compositionLocalOf { false }

// The morph supplies one surface for the entire popup, including its lyrics.
internal val LocalPopupContainer = staticCompositionLocalOf { false }

@Composable
fun PopupSurface(
    shape: Shape,
    modifier: Modifier = Modifier,
    shadowElevation: Dp = 12.dp,
    colorMode: Int = rememberPopupColorMode(),
    cornerRadius: Dp = 32.dp,
    containerProgress: Float = 1f,
    blurFraction: Float? = null,
    backgroundAlpha: Float = 1f,
    surfaceKey: Any? = null,
    content: @Composable () -> Unit,
) {
    val cameraClearance = LocalPopupCameraClearance.current
    val body: @Composable () -> Unit = {
        Box(
            Modifier.padding(top = cameraClearance),
            propagateMinConstraints = LocalIslandCosStyle.current,
        ) { content() }
    }
    if (LocalPopupContainer.current) {
        // The Liquid Glass surface is tinted dark, so its content is white in both themes.
        val contentColor =
            if (colorMode == POPUP_COLOR_MODE_SOLID_BLACK || liquidGlassEnabled()) Color.White
            else MaterialTheme.colorScheme.onSurface
        Box(modifier = modifier, propagateMinConstraints = LocalIslandCosStyle.current) {
            CompositionLocalProvider(
                LocalContentColor provides contentColor,
                LocalPopupDarkSurface provides
                    (colorMode == POPUP_COLOR_MODE_SOLID_BLACK || liquidGlassEnabled()),
            ) {
                body()
            }
        }
        return
    }
    val surfaceContent: @Composable () -> Unit = {
        Box(modifier = if (containerProgress < 1f) Modifier.fillMaxSize() else Modifier) {
            body()
            if (containerProgress < 1f) {
                Box(
                    Modifier.matchParentSize()
                        .background(Color.Black.copy(alpha = 1f - containerProgress))
                )
            }
        }
    }
    // Liquid glass: frosted blur of what is behind, a dark tint and the glass rim. Content is
    // white in light and dark themes, so the tint must keep it readable over a light app too.
    if (liquidGlassEnabled() && colorMode != POPUP_COLOR_MODE_SOLID_BLACK) {
        val heavy = colorMode == POPUP_COLOR_MODE_BLUR
        Box(
            modifier =
                modifier
                    .plainBlurBehind(
                        shape,
                        // Dark enough for white text over a white app (about 5.7:1); tune with
                        // debug.lg.island_blur (dp) and debug.lg.island_surface.
                        tint =
                            if (heavy) LiquidGlassTune.color("island_blur_surface", HeavyGlassTint)
                            else LiquidGlassTune.color("island_surface", GlassTint),
                        radius =
                            if (heavy) PopupBlurRadius
                            else LiquidGlassTune.f("island_blur", 14f).dp,
                    )
                    // The morph turns the glass into the black island as it collapses.
                    .liquidGlassRim(shape, strength = containerProgress.coerceIn(0f, 1f))
                    .clip(shape)
        ) {
            CompositionLocalProvider(
                LocalContentColor provides Color.White,
                LocalPopupDarkSurface provides true,
            ) {
                surfaceContent()
            }
        }
        return
    }
    // The ColorOS style cards all share one glass, the media card included. With Liquid Glass on,
    // that glass calls this function, so it must not call it back.
    if (islandGlassCards() && !liquidGlassEnabled()) {
        IslandGlassSurface(modifier = modifier, content = body)
        return
    }
    // Every background runs all the way up around the camera; the content is only pushed down by
    // the clearance so it stays off the lens.
    when (colorMode) {
        POPUP_COLOR_MODE_BLUR -> {
            CompositionLocalProvider(
                LocalContentColor provides MaterialTheme.colorScheme.onSurface
            ) {
                if (blurFraction != null) {
                    MorphBlurSurface(
                        modifier = modifier,
                        shape = shape,
                        cornerRadius = cornerRadius,
                        blurFraction = blurFraction,
                        surfaceProgress = containerProgress,
                        backgroundAlpha = backgroundAlpha,
                        surfaceKey = surfaceKey,
                        content = surfaceContent,
                    )
                } else {
                    AxBlurSurface(
                        modifier = modifier,
                        shape = shape,
                        cornerRadius = cornerRadius,
                        highlight = AxBlurSurfaceDefaults.highlightBrush(),
                    ) {
                        surfaceContent()
                    }
                }
            }
        }
        POPUP_COLOR_MODE_SOLID_BLACK -> {
            Surface(
                color = Color.Black,
                contentColor = Color.White,
                shape = shape,
                shadowElevation = shadowElevation,
                modifier = modifier,
            ) {
                CompositionLocalProvider(LocalPopupDarkSurface provides true) { surfaceContent() }
            }
        }
        else -> {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = shape,
                // The island window clips the shadow into ghost edges at the card corners.
                shadowElevation = 0.dp,
                modifier = modifier,
            ) {
                surfaceContent()
            }
        }
    }
}

/** Height of camera cutout the popup content has to stay clear of at its top edge. */
val LocalPopupCameraClearance = compositionLocalOf { 0.dp }

// AxBlurSurface exposes opacity but not the blur radius. The shared morph needs both to reach
// zero before handing its shape back; keep ordinary popup surfaces on the standard renderer.
@Composable
private fun MorphBlurSurface(
    modifier: Modifier,
    shape: Shape,
    cornerRadius: Dp,
    blurFraction: Float,
    surfaceProgress: Float,
    backgroundAlpha: Float,
    surfaceKey: Any?,
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    val density = LocalDensity.current
    val enabled = LocalAxBlurEnabled.current
    val blur = remember(view, surfaceKey) {
        BlurEngine(view).apply { useSettings(AxBackdropBlurSettingsSpec.system()) }
    }
    val fallback = AxBlurSurfaceDefaults.surfaceColor()
    val tint = AxBlurSurfaceDefaults.tintColor().toArgb()
    val highlight = AxBlurSurfaceDefaults.highlightBrush()
    val highlightWidth = with(density) { AxBlurSurfaceDefaults.HighlightWidth.toPx() }
    val radius = with(density) { cornerRadius.toPx() }
    val effectsAlpha = surfaceProgress.coerceIn(0f, 1f)
    DisposableEffect(blur) { onDispose(blur::dispose) }
    SideEffect {
        blur.setEnabled(enabled)
        if (blurFraction == 0f) {
            // The engine coalesces changes below 0.01; ensure the closed endpoint is exactly zero.
            blur.setBlurFraction(1f)
        }
        blur.setBlurFraction(blurFraction)
        blur.setAlpha((255 * effectsAlpha * backgroundAlpha.coerceIn(0f, 1f)).roundToInt())
        blur.setOverlayColor(tint)
    }
    Box(
        modifier = modifier
            .clip(shape)
            .drawWithContent {
                var drewBlur = false
                if (enabled) {
                    drawIntoCanvas { canvas ->
                        drewBlur = blur.draw(
                            canvas.nativeCanvas,
                            0,
                            0,
                            size.width.roundToInt(),
                            size.height.roundToInt(),
                            radius,
                        )
                    }
                }
                if (!drewBlur) {
                    drawRoundRect(
                        color = fallback,
                        cornerRadius = CornerRadius(radius),
                        alpha = effectsAlpha,
                    )
                }
                drawContent()
                val inset = highlightWidth / 2f
                drawRoundRect(
                    brush = highlight,
                    topLeft = Offset(inset, inset),
                    size = Size(
                        (size.width - highlightWidth).coerceAtLeast(0f),
                        (size.height - highlightWidth).coerceAtLeast(0f),
                    ),
                    cornerRadius = CornerRadius((radius - inset).coerceAtLeast(0f)),
                    style = Stroke(width = highlightWidth),
                    alpha = effectsAlpha,
                )
            },
    ) {
        content()
    }
}

/**
 * Tint of the Liquid Glass island surface for [colorMode]: a dark gray mixed into the blur under the
 * card's vertical shade. The blur background is never darker than the default one.
 */
internal fun liquidGlassIslandTint(colorMode: Int): Int =
    if (colorMode == POPUP_COLOR_MODE_BLUR) {
        LiquidGlassTune.color("island_blur_surface", IslandGlassMix)
    } else {
        LiquidGlassTune.color("island_surface", IslandGlassMix)
    }

private const val PLAIN_BLUR = -1
private const val IslandGlassMix = 0xB3262626.toInt()
private const val GlassTint = 0x99000000.toInt()
private const val HeavyGlassTint = 0xB3000000.toInt()
private val PopupBlurRadius = 34.dp
private val GlassNoBlurFill = Color(0xE62C2C2E)
