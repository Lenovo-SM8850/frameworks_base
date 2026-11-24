/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.statusbar.quickactions.ui.compose

import android.graphics.RuntimeShader
import android.graphics.drawable.Drawable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.systemui.qs.composefragment.ColorOSLightShaders
import com.google.ux.material.libmonet.hct.Hct
import com.google.ux.material.libmonet.scheme.SchemeContent

/**
 * The frame of a COS style island card on Liquid Glass: soft light edges tinted with the artwork
 * colour (a thin band along the top, a wide glow along the bottom and its corners) while the card
 * has artwork, a neutral hairline otherwise.
 */
@Stable
internal class CosIslandFrame(val color: State<Color>, val lit: State<Float>)

/** The frame of a card that shows [artwork]; unlit without artwork. */
@Composable
internal fun rememberCosIslandFrame(artwork: Drawable?): CosIslandFrame {
    val accent = rememberAlbumArtAccent(artwork, fallback = Color.Unspecified)
    val target =
        remember(accent, artwork) {
            if (artwork != null && accent != Color.Unspecified) lightToneOf(accent)
            else Color.Unspecified
        }
    // The last colour stays while the light fades out.
    val held = remember { arrayOf(Color.White) }
    if (target != Color.Unspecified) held[0] = target
    val color = animateColorAsState(held[0], tween(COLOR_FADE_MS), label = "cos_frame_color")
    val lit =
        animateFloatAsState(
            if (target != Color.Unspecified) 1f else 0f,
            tween(COLOR_FADE_MS),
            label = "cos_frame_lit",
        )
    return remember(color, lit) { CosIslandFrame(color, lit) }
}

/** The neutral hairline, fading out as the light fades in. Drawn above the content. */
internal fun Modifier.cosIslandFrameStroke(frame: CosIslandFrame, corner: Dp): Modifier =
    drawWithCache {
        val width = STROKE_WIDTH.toPx()
        val radius = CornerRadius(maxOf(corner.toPx() - width / 2f, 0f))
        val stroke = Stroke(width)
        val inner = Size(size.width - width, size.height - width)
        onDrawWithContent {
            drawContent()
            val alpha = 1f - frame.lit.value
            if (alpha > MIN_ALPHA) {
                drawRoundRect(
                    STROKE_COLOR.copy(alpha = STROKE_COLOR.alpha * alpha),
                    topLeft = Offset(width / 2f, width / 2f),
                    size = inner,
                    cornerRadius = radius,
                    style = stroke,
                )
            }
        }
    }

/**
 * The light edges and the inner glow of the frame, drawn behind the card content. The shader and
 * its brush are created once; drawing only updates uniforms.
 */
@Composable
internal fun Modifier.cosIslandFrameLight(frame: CosIslandFrame, corner: Dp): Modifier {
    val shader = remember { newLightShader() }
    val brush = remember(shader) { ShaderBrush(shader) }
    return drawWithCache {
        val width = size.width
        val height = size.height
        // The reference sizes are pixels of a card of REF_WIDTH by REF_HEIGHT.
        val scale = width / REF_WIDTH
        shader.setFloatUniform("uSize", width, height)
        shader.setFloatUniform("uRadius", corner.toPx())
        shader.setFloatUniform(
            "uEdgeBands",
            NEAR_LINE * scale,
            (NEAR_LINE + NEAR_FALL) * scale,
            FAR_LINE * scale,
            (FAR_LINE + FAR_FALL) * scale,
        )
        shader.setFloatUniform("uGlowLength", GLOW_DEPTH * (width + height) / (REF_WIDTH + REF_HEIGHT))
        shader.setFloatUniform("uGlowOffset", 0f, -GLOW_OFFSET_Y * height / REF_HEIGHT)
        onDrawBehind {
            val alpha = frame.lit.value
            if (alpha > MIN_ALPHA) {
                val c = frame.color.value
                shader.setFloatUniform("uColor", c.red, c.green, c.blue)
                shader.setFloatUniform("uAlpha", alpha)
                drawRect(brush, size = size)
            }
        }
    }
}

private fun newLightShader() =
    RuntimeShader(LIGHT_SOURCE).apply {
        setFloatUniform("uGlowAlpha", GLOW_ALPHA)
        setFloatUniform("uGlowScale", GLOW_SCALE_X, GLOW_SCALE_Y)
        setFloatUniform("uGlowCurve", GLOW_CURVE_MIX)
        setFloatUniform("uEdgeAlpha", NEAR_ALPHA, FAR_ALPHA)
        setFloatUniform(
            "uEdgeSpread",
            NEAR_SPREAD_START,
            NEAR_SPREAD_START + NEAR_SPREAD_DECAY,
            FAR_SPREAD_START,
            FAR_SPREAD_START + FAR_SPREAD_DECAY,
        )
        setFloatUniform("uEdgeTurn", EDGE_TURN)
        setFloatUniform("uEdgeCurve", EDGE_CURVE_MIX)
    }

// The light tone of the artwork colour's scheme: the cards are dark, so the light stays bright.
private fun lightToneOf(color: Color): Color =
    Color(SchemeContent(Hct.fromInt(color.toArgb()), true, 0.0).primaryPalette.tone(LIGHT_TONE))

private const val COLOR_FADE_MS = 450
private const val MIN_ALPHA = 0.001f
private val STROKE_WIDTH = 1.dp
private val STROKE_COLOR = Color(0x33959595)
private const val LIGHT_TONE = 80

// Reference card, and the values of the light on it.
private const val REF_WIDTH = 1159f
private const val REF_HEIGHT = 497f

private const val NEAR_LINE = 2f
private const val NEAR_FALL = 20f
private const val NEAR_ALPHA = 0.30f
private const val NEAR_SPREAD_START = 0.20f
private const val NEAR_SPREAD_DECAY = 0.30f

private const val FAR_LINE = 2f
private const val FAR_FALL = 4f
private const val FAR_ALPHA = 0.25f
private const val FAR_SPREAD_START = 0.25f
private const val FAR_SPREAD_DECAY = 0.15f

private const val EDGE_TURN = 0.5f
private const val EDGE_CURVE_MIX = 0.6f

private const val GLOW_ALPHA = 0.20f
private const val GLOW_DEPTH = 80f
private const val GLOW_OFFSET_Y = 100f
private const val GLOW_SCALE_X = 1.1f
private const val GLOW_SCALE_Y = 1.4f
private const val GLOW_CURVE_MIX = 0.30f

private val LIGHT_SOURCE =
    """
    uniform float2 uSize;
    uniform float uRadius;
    uniform float3 uColor;
    uniform float uAlpha;

    uniform float uGlowAlpha;
    uniform float uGlowLength;
    uniform float2 uGlowOffset;
    uniform float2 uGlowScale;
    uniform float uGlowCurve;

    uniform float4 uEdgeBands;
    uniform float2 uEdgeAlpha;
    uniform float4 uEdgeSpread;
    uniform float uEdgeTurn;
    uniform float uEdgeCurve;

    ${ColorOSLightShaders.ROUNDED_BOX}
    ${ColorOSLightShaders.PERIMETER_LIGHT}
    ${ColorOSLightShaders.SOFT_LIGHT}

    float grain(float2 p) {
        return fract(sin(dot(p.yx, float2(79.1214, 78.233))) * 43760.5453);
    }

    float4 over(float4 top, float4 under) {
        return top + under * (1.0 - top.a);
    }

    float4 glow(float2 xy, float2 pos, float2 extent, float radius) {
        float2 p = pos - uGlowOffset;
        float2 glowExtent = extent * uGlowScale;
        float corner = max(min(min(extent.x, extent.y), uGlowLength * 1.2), radius);
        float alpha = 0.0;
        if (abs(p.x) > glowExtent.x - corner || abs(p.y) > glowExtent.y - corner) {
            float r = min(corner, min(glowExtent.x, glowExtent.y));
            alpha = smoothstep(-uGlowLength, 0.0, roundedBox(p, glowExtent, r));
            float steep = alpha * alpha * alpha;
            steep *= steep;
            alpha = mix(alpha, steep, uGlowCurve) * uGlowAlpha;
        }
        float n = clamp((grain(xy / 1080.0) - 0.5) * 0.01 + 0.5, 0.0, 1.0);
        alpha = clamp(softLight(n, alpha), 0.0, 1.0);
        return float4(uColor * alpha, alpha);
    }

    float4 edge(float2 pos, float2 extent, float d, float4 color) {
        if (d < -max(uEdgeBands.y, uEdgeBands.w)) {
            return color;
        }
        float2 light = perimeterLight(pos, extent, d, uEdgeTurn, uEdgeBands, uEdgeSpread,
            6.0, uEdgeCurve);
        float nearAlpha = light.x * uEdgeAlpha.x;
        float farAlpha = light.y * uEdgeAlpha.y;
        float4 near = float4(uColor * nearAlpha, nearAlpha);
        float4 far = float4(uColor * farAlpha, farAlpha);
        return over(over(near, far), color);
    }

    half4 main(float2 xy) {
        float2 extent = uSize * 0.5;
        float2 pos = xy - extent;
        float radius = min(uRadius, min(extent.x, extent.y));
        float d = roundedBox(pos, extent, radius);
        float shape = smoothstep(1.0, -1.0, d);
        if (shape < 0.001) {
            return half4(0.0);
        }
        float4 color = glow(xy, pos, extent, radius);
        color = edge(pos, extent, d, color);
        return half4(color * (shape * uAlpha));
    }
    """
        .trimIndent()
