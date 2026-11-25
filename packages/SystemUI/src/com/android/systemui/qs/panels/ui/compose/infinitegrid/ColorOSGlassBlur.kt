/*
 * Copyright (C) 2026 The Evolution X Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.panels.ui.compose.infinitegrid

import android.app.ActivityManager
import android.graphics.RectF
import android.os.SystemProperties
import android.view.CrossWindowBlurListeners
import android.view.View
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.android.internal.graphics.drawable.BackgroundBlurDrawable
import com.android.systemui.qs.shared.style.LiquidGlassTune
import com.android.systemui.qs.shared.style.liquidGlassOn
import java.util.function.Consumer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Blur regions for the glass of the expanded ColorOS panel. SurfaceFlinger blurs what is behind the
 * shade inside each region and draws the ColorOS glass material over it, so the wallpaper shows
 * through the pills, the tiles that are off, the sliders and the media card the way it does on
 * ColorOS.
 *
 * The regions are not part of the Compose layers. The panel provides [LocalColorOSGlassBlur] only
 * where the glass can be drawn, and everything that draws a flat fill as the glass keeps drawing it
 * while the local is null.
 */
internal class ColorOSGlassBlur(
    /**
     * The alpha of the panel. The regions do not follow the alpha of the Compose layers, so they
     * are hidden with this, and 0 hides them.
     */
    val alpha: () -> Float
)

/** The glass of the expanded ColorOS panel, null where the regions cannot be used. */
internal val LocalColorOSGlassBlur = compositionLocalOf<ColorOSGlassBlur?> { null }

/**
 * The part of the window the panel is clipped out of, where the notifications are drawn over it,
 * in window coordinates, or null when the panel is not clipped. The view that holds the panel
 * clips its Compose layers, but not the regions, so each region leaves this part out itself. It
 * reads snapshot state, so a draw that calls it is redrawn when the clip moves.
 */
internal val LocalColorOSGlassClipOut = staticCompositionLocalOf<() -> RectF?> { { null } }

/** Where an element with a glass region was last placed, read again when it is drawn. */
private class ColorOSGlassPlacement {
    var coordinates: LayoutCoordinates? = null
}

/**
 * The blur radius. It is 62px on a 440dpi device, the one the glass was matched against ColorOS
 * on, and scales with the density like the tiles it blurs behind do.
 */
private val ColorOSGlassBlurRadius = 22.5.dp

/**
 * Overrides the blur radius, in pixels, to match it by eye on a device:
 * `setprop debug.evolution.lg.cos_blur 80`. Unset or 0 keeps [ColorOSGlassBlurRadius]. With Liquid
 * Glass on, [ColorOSTileBlurPxName] and [ColorOSPanelBlurPxName] take precedence for their regions.
 * It is read again when `debug.lg.gen` changes.
 */
private const val ColorOSGlassBlurRadiusProperty = "debug.evolution.lg.cos_blur"

/**
 * The `debug.lg.` names of the radius overrides in pixels, one for the glass of the tiles, the
 * pills and the sliders, one for the glass behind the shade. Finite and above 0, or ignored.
 */
private const val ColorOSTileBlurPxName = "cos_tile_blur_px"
private const val ColorOSPanelBlurPxName = "cos_panel_blur_px"

/** How close to the edge of the window counts as touching it, in pixels. */
private const val ColorOSGlassEdgeSlop = 0.5f

/**
 * Overrides the alpha, 0 to 255, of the white tint on the glass of the light panel, to match it by
 * eye on a device: `setprop debug.evolution.lg.cos_light_tile 120`. Unset or negative keeps the
 * default. It is read again when `debug.lg.gen` changes.
 */
private const val ColorOSLightTileTintProperty = "debug.evolution.lg.cos_light_tile"

/**
 * The `debug.lg.` name of the ARGB override of the light tile tint, hex like `0x26FFFFFF`. It takes
 * precedence over [ColorOSLightTileTintProperty] while Liquid Glass is on.
 */
private const val ColorOSLightTileTintArgbName = "cos_light_tile_argb"

/**
 * The radius and tint overrides. The `debug.lg.` ones go through [LiquidGlassTune], which drops its
 * cache when `debug.lg.gen` changes. The `debug.evolution.lg.` ones are read again on the same
 * change. Every read here also reads [LiquidGlassTune.generation], so the draw or the composition
 * that asks is redone then. A system property change callback does not fire for `setprop`.
 */
private object ColorOSGlassBlurTune {
    @Volatile private var syncedGeneration = -1

    /** The radius override in pixels, 0 for none. */
    @Volatile private var legacyBlurPx = 0

    /** The alpha override of the light tile tint, negative for none. */
    @Volatile private var legacyLightTileAlpha = -1

    private fun sync() {
        val generation = LiquidGlassTune.generation.value
        if (generation == syncedGeneration) return
        legacyBlurPx = SystemProperties.getInt(ColorOSGlassBlurRadiusProperty, 0)
        legacyLightTileAlpha = SystemProperties.getInt(ColorOSLightTileTintProperty, -1)
        syncedGeneration = generation
    }

    /** The blur radius of the region of [material] in pixels. */
    fun blurRadiusPx(material: Int, glassOn: Boolean, defaultPx: Int): Int {
        sync()
        if (glassOn) {
            val panel =
                material == BackgroundBlurDrawable.MATERIAL_PANEL_DARK ||
                    material == BackgroundBlurDrawable.MATERIAL_PANEL_LIGHT
            val name = if (panel) ColorOSPanelBlurPxName else ColorOSTileBlurPxName
            val px = LiquidGlassTune.f(name, 0f)
            if (px.isFinite() && px > 0f) return px.roundToInt()
        }
        return legacyBlurPx.takeIf { it > 0 } ?: defaultPx
    }

    /** The tint the region of [material] gets, as ARGB, 0 for none. */
    fun tint(material: Int, glassOn: Boolean): Int {
        sync()
        if (material != BackgroundBlurDrawable.MATERIAL_TILE_LIGHT) return 0
        if (glassOn) {
            // The cache of LiquidGlassTune has no unset state: a prop that is set gives the same
            // colour whatever the default is.
            val argb = LiquidGlassTune.color(ColorOSLightTileTintArgbName, 0)
            if (argb == LiquidGlassTune.color(ColorOSLightTileTintArgbName, -1)) return argb
        }
        val alpha = legacyLightTileAlpha
        if (alpha >= 0) return (alpha.coerceAtMost(255) shl 24) or 0x00FFFFFF
        return if (glassOn) ColorOSLightTileTintGlass else ColorOSLightTileTint.toArgb()
    }
}

/** Below this alpha a region is not worth keeping in SurfaceFlinger. */
private const val ColorOSGlassMinAlpha = 0.01f

/**
 * How many frames the shade and its window blur have to be gone before the glass behind it goes, see
 * [ColorOSGlassRegion.linger]. The blur of the shade window follows its content a frame or two late.
 */
private const val ColorOSBackdropLingerFrames = 3

/**
 * The white laid over the blur behind the glass of the light panel's pills, tiles that are off,
 * sliders and media card. The material SurfaceFlinger draws for them is faint over the pale blur of
 * the light panel, so the glass reads as flat. ColorOS shows them as clearly lighter than the panel
 * around them. The dark panel and the tiles that are on are not tinted.
 */
private val ColorOSLightTileTint = Color(0x66FFFFFF)

/** The tint, as ARGB, while Liquid Glass is on: the glass of the tiles stays see-through. */
private const val ColorOSLightTileTintGlass = 0x26FFFFFF

/**
 * Returns the glass of the panel while SurfaceFlinger can blur behind the shade, and null when it
 * cannot: no support, a low end device, the window blur turned off by the user or by battery
 * saver, or `persist.sysui.disableBlur`. The same conditions as the blur of the shade itself.
 */
@Composable
internal fun rememberColorOSGlassBlur(alpha: () -> Float): ColorOSGlassBlur? {
    val enabled = rememberCrossWindowBlurEnabled()
    val currentAlpha by rememberUpdatedState(alpha)
    val blur = remember { ColorOSGlassBlur { currentAlpha() } }
    return blur.takeIf { enabled }
}

@Composable
private fun rememberCrossWindowBlurEnabled(): Boolean {
    val supported = remember {
        CrossWindowBlurListeners.CROSS_WINDOW_BLUR_SUPPORTED &&
            ActivityManager.isHighEndGfx() &&
            !SystemProperties.getBoolean("persist.sysui.disableBlur", false)
    }
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(false) }
    DisposableEffect(supported, context) {
        if (!supported) {
            enabled = false
            return@DisposableEffect onDispose {}
        }
        val listeners = CrossWindowBlurListeners.getInstance()
        val consumer = Consumer<Boolean> { enabled = it }
        listeners.addListener(context.mainExecutor, consumer)
        onDispose { listeners.removeListener(consumer) }
    }
    return supported && enabled
}

@Composable
@ReadOnlyComposable
private fun colorOSDarkPanel(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f

/** The glass of the pills, of the round tiles that are off and of the sliders. */
@Composable
@ReadOnlyComposable
internal fun colorOSGlassTileMaterial(): Int =
    if (colorOSDarkPanel()) BackgroundBlurDrawable.MATERIAL_TILE_DARK
    else BackgroundBlurDrawable.MATERIAL_TILE_LIGHT

/**
 * The glass under the bubble of a round tile that is on. The bubble is a touch short of opaque, so
 * the lit rim and the glow of this material still show around it.
 */
@Composable
@ReadOnlyComposable
internal fun colorOSGlassActiveTileMaterial(): Int =
    if (colorOSDarkPanel()) BackgroundBlurDrawable.MATERIAL_TILE_ACTIVE_DARK
    else BackgroundBlurDrawable.MATERIAL_TILE_ACTIVE_LIGHT

/** The glass behind the whole shade, which the glass of the panel is drawn over. */
@Composable
@ReadOnlyComposable
internal fun colorOSGlassPanelMaterial(): Int =
    if (colorOSDarkPanel()) BackgroundBlurDrawable.MATERIAL_PANEL_DARK
    else BackgroundBlurDrawable.MATERIAL_PANEL_LIGHT

/** Keeps the blur drawable of one [colorOSGlassBlur], which is created when it is first drawn. */
private class ColorOSGlassRegion(private val view: View) {
    var drawable: BackgroundBlurDrawable? = null

    /** Whether the shade still needs the region, while it lingers. */
    private var lingerWhile: (() -> Boolean)? = null
    private var lingerFramesIn = 0
    private val lingerFrame =
        object : Runnable {
            override fun run() {
                val needed = lingerWhile ?: return
                lingerFramesIn = if (needed()) 0 else lingerFramesIn + 1
                if (lingerFramesIn >= ColorOSBackdropLingerFrames) hide()
                else view.postOnAnimation(this)
            }
        }

    /** The region that stands in for this one while it lingers, drawn by the window's root. */
    private var standIn: BackgroundBlurDrawable? = null

    /**
     * For the glass behind the shade: puts a stand-in that cannot be seen, the lowest alpha at a
     * 1px radius of the same material, in place of the region until a few frames after [needed]
     * turns false. SurfaceFlinger draws a shade window blur without such a region as the dark
     * panel glass. The window blur outlasts the panel when the shade is closed slowly, and it
     * reaches SurfaceFlinger after the content that goes with it, so without the stand-in the
     * screen dimmed at the end of a collapse. The stand-in is drawn by the root of the window, as
     * the panel stops drawing the region once it fades out, which takes the region away.
     */
    fun linger(needed: () -> Boolean) {
        val drawable = drawable ?: return
        if (lingerWhile != null || !drawable.isVisible) return
        val root = view.rootView
        val stand =
            standIn
                ?: view.viewRootImpl?.createBackgroundBlurDrawable()?.also {
                    it.setXfermode(null)
                    standIn = it
                }
                ?: return
        stand.setMaterial(drawable.material)
        stand.setBlurRadius(1)
        stand.setCornerRadius(0f)
        stand.alpha = 1
        stand.setBounds(0, 0, root.width, root.height)
        stand.setVisible(true, false)
        root.overlay.add(stand)
        drawable.setVisible(false, false)
        lingerWhile = needed
        lingerFramesIn = 0
        view.postOnAnimation(lingerFrame)
    }

    fun stopLingering() {
        if (lingerWhile == null) return
        lingerWhile = null
        view.removeCallbacks(lingerFrame)
        standIn?.let {
            it.setVisible(false, false)
            view.rootView.overlay.remove(it)
        }
    }

    fun hide() {
        val lingered = lingerWhile != null
        stopLingering()
        if (lingered) view.rootView.postInvalidateOnAnimation()
        val hidden = drawable?.setVisible(false, false) ?: return
        // Hiding a region does not draw anything, and SurfaceFlinger only hears of it with the
        // next frame of the window. Without one, which is what happens when the panel is closing
        // for an app, the glass stays on screen over the app.
        if (hidden) view.postInvalidateOnAnimation()
    }
}

/**
 * Draws the element on a blur region of [material], one of the `MATERIAL_*` values of
 * [BackgroundBlurDrawable], in the shape of [shape]. Does nothing where [LocalColorOSGlassBlur] is
 * null.
 *
 * The region is the part of the element that is visible in the window, so what a scroller or any
 * other parent clips does not leave glass behind. Where that part reaches the edge of the window,
 * the region goes on to the real edge of the element instead: what is past the window is not
 * seen, and the rim of the glass does not run along the edge of the window like it was a cut.
 * It is hidden while the panel is, and when the element leaves the composition.
 *
 * With [wholeWindow] the region is the whole window instead, whatever the size of the element and
 * whatever a parent clips, which is what the glass behind the shade needs. [shape] is then a
 * rectangle, and the region stays as an unseen stand-in while [windowBlurred] says the window
 * still has a blur.
 */
@Composable
internal fun Modifier.colorOSGlassBlur(
    shape: Shape,
    material: Int,
    wholeWindow: Boolean = false,
    windowBlurred: () -> Boolean = { false },
): Modifier {
    val blur = LocalColorOSGlassBlur.current ?: return this
    val view = LocalView.current
    val region = remember(view) { ColorOSGlassRegion(view) }
    val clipOut = LocalColorOSGlassClipOut.current
    val placement = remember { ColorOSGlassPlacement() }
    val defaultRadius = with(LocalDensity.current) { ColorOSGlassBlurRadius.roundToPx() }
    val glassOn = liquidGlassOn
    // The part of the element that is in the window, in its own coordinates.
    var visible by remember { mutableStateOf<Rect?>(null) }
    val goOut = {
        if (wholeWindow) region.linger { blur.alpha() > 0f || windowBlurred() } else region.hide()
    }

    DisposableEffect(region) { onDispose { region.hide() } }
    // The panel can be hidden without drawing the element again, and the region would stay.
    LaunchedEffect(blur, region) {
        snapshotFlow { blur.alpha().coerceIn(0f, 1f) }
            .collect { alpha ->
                val drawable = region.drawable ?: return@collect
                if (alpha < ColorOSGlassMinAlpha) {
                    goOut()
                } else {
                    drawable.alpha = (alpha * 255).toInt()
                }
            }
    }

    // The panel is not placed once the shade is closed, so nothing is drawn, and the effect above
    // hides the region a frame after the content is gone. That frame shows the glass over the app
    // that was opened. The measure pass of the frame the panel goes out in is early enough for
    // SurfaceFlinger to hear of it with the same buffer.
    val hidden by remember(blur) {
        derivedStateOf { blur.alpha().coerceIn(0f, 1f) < ColorOSGlassMinAlpha }
    }

    return this.layout { measurable, constraints ->
            if (hidden) goOut()
            val placeable = measurable.measure(constraints)
            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
        .onGloballyPositioned { coordinates ->
            if (!coordinates.isAttached) return@onGloballyPositioned
            placement.coordinates = coordinates
            if (wholeWindow) {
                visible =
                    Rect(
                        coordinates.windowToLocal(Offset.Zero),
                        coordinates.windowToLocal(
                            Offset(view.rootView.width.toFloat(), view.rootView.height.toFloat())
                        ),
                    )
                return@onGloballyPositioned
            }
            val clipped = coordinates.boundsInWindow()
            if (clipped.width <= 0f || clipped.height <= 0f) {
                // Nothing of it is in the window.
                visible = Rect.Zero
                return@onGloballyPositioned
            }
            val origin = coordinates.positionInWindow()
            val windowWidth = view.rootView.width.toFloat()
            val windowHeight = view.rootView.height.toFloat()
            val left =
                if (clipped.left <= ColorOSGlassEdgeSlop) min(clipped.left, origin.x)
                else clipped.left
            val top =
                if (clipped.top <= ColorOSGlassEdgeSlop) min(clipped.top, origin.y)
                else clipped.top
            val right =
                if (clipped.right >= windowWidth - ColorOSGlassEdgeSlop) {
                    max(clipped.right, origin.x + coordinates.size.width)
                } else {
                    clipped.right
                }
            val bottom =
                if (clipped.bottom >= windowHeight - ColorOSGlassEdgeSlop) {
                    max(clipped.bottom, origin.y + coordinates.size.height)
                } else {
                    clipped.bottom
                }
            visible =
                Rect(
                        coordinates.windowToLocal(Offset(left, top)),
                        coordinates.windowToLocal(Offset(right, bottom)),
                    )
                    .intersect(coordinates.size.toSize().toRect())
        }
        .drawBehind {
            val alpha = blur.alpha().coerceIn(0f, 1f)
            val shown = visible ?: size.toRect()
            var top = shown.top
            var bottom = shown.bottom
            // Leave out the part the notifications clip off the panel, as its layers are. The
            // clip spans the panel's width, so only the top and the bottom of the region move.
            val cut = if (wholeWindow) null else clipOut()
            val coordinates = placement.coordinates
            if (cut != null && coordinates != null && coordinates.isAttached) {
                val cutTop = coordinates.windowToLocal(Offset(cut.left, cut.top)).y
                val cutBottom = coordinates.windowToLocal(Offset(cut.left, cut.bottom)).y
                if (cutTop <= top && cutBottom >= bottom) {
                    bottom = top
                } else if (cutTop > top && cutTop < bottom) {
                    bottom = cutTop
                } else if (cutBottom > top && cutBottom < bottom) {
                    top = cutBottom
                }
            }
            if (
                alpha < ColorOSGlassMinAlpha || shown.width < 1f || bottom - top < 1f
            ) {
                goOut()
                return@drawBehind
            }
            val root = view.viewRootImpl ?: return@drawBehind
            val drawable =
                region.drawable
                    ?: root.createBackgroundBlurDrawable().also {
                        it.setXfermode(null)
                        region.drawable = it
                    }
            val corner =
                when (val outline = shape.createOutline(size, layoutDirection, this)) {
                    is Outline.Rounded -> outline.roundRect.topLeftCornerRadius.x
                    // A path shape, like the icon shapes of the tiles, has no corner radius of its
                    // own. The glass can only be a rounded rect, so give it the fullest one that
                    // fits in the bounds of the path, which is exact for the circle of the tiles.
                    is Outline.Generic ->
                        outline.path.getBounds().let { min(it.width, it.height) / 2f }
                    is Outline.Rectangle -> 0f
                }
            region.stopLingering()
            drawable.setVisible(true, false)
            drawable.setMaterial(material)
            drawable.setColor(ColorOSGlassBlurTune.tint(material, glassOn))
            val radius = ColorOSGlassBlurTune.blurRadiusPx(material, glassOn, defaultRadius)
            // The backdrop also fades by its radius, as the window blur does. A full radius at a
            // low alpha leaves a blurred ghost that snaps away when the region goes.
            // At least 1px, as SurfaceFlinger skips a region with no radius and would tint the
            // window blur under it.
            drawable.setBlurRadius(
                if (wholeWindow) (radius * alpha).roundToInt().coerceAtLeast(1) else radius
            )
            drawable.setCornerRadius(min(corner, (bottom - top) / 2f))
            drawable.alpha = (alpha * 255).toInt()
            drawable.setBounds(
                shown.left.toInt(),
                top.toInt(),
                shown.right.toInt(),
                bottom.toInt(),
            )
            // Drawing it is what reports its position to SurfaceFlinger.
            drawIntoCanvas { drawable.draw(it.nativeCanvas) }
        }
}

/**
 * The glass behind the whole shade, the notifications and the panel alike: a region of the panel
 * material over the full window, which the glass of the pills, the tiles and the sliders is drawn
 * over. The tint of the light panel is the scrim colour, see ScrimController. [alpha] is how much of
 * the shade is out, 0 hides it. [windowBlurred] is whether the shade window still has a blur, which
 * can outlast the shade. Nothing is drawn where SurfaceFlinger cannot blur, and the scrim of the
 * shade keeps its own colour then.
 */
@Composable
internal fun ColorOSBackdrop(
    alpha: () -> Float,
    windowBlurred: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val blur = rememberColorOSGlassBlur(alpha) ?: return
    CompositionLocalProvider(LocalColorOSGlassBlur provides blur) {
        Spacer(
            modifier.colorOSGlassBlur(
                RectangleShape,
                colorOSGlassPanelMaterial(),
                wholeWindow = true,
                windowBlurred = windowBlurred,
            )
        )
    }
}
