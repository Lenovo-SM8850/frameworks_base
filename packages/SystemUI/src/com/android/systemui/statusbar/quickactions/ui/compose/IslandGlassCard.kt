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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon as MaterialIcon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.axion.blur.AxBlurSurface
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.qs.panels.ui.compose.infinitegrid.ColorOSTileRim
import com.android.systemui.qs.shared.style.LiquidGlassBlue
import com.android.systemui.qs.shared.style.liquidGlassEnabled
import com.android.systemui.statusbar.quickactions.popups.shared.model.PopupActionModel
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import io.github.miner7222.systemui.island.LocalIslandCardBlurState
import kotlin.math.roundToInt

/**
 * True when the island draws its ColorOS style glass cards: the setting is on and the popup is not
 * a container with its own surface, as it is beside a side camera and in the tail of a stack.
 */
@Composable
fun islandGlassCards(): Boolean {
    val container = LocalPopupContainer.current
    return rememberDynamicIslandColorOSStyle() && !container
}

/**
 * Set by the island popup in the ColorOS style, also for a card that sits in a container with its
 * own surface, such as the media card above its lyrics, where [islandGlassCards] is false.
 */
internal val LocalIslandCosStyle = staticCompositionLocalOf { false }

/** True for a card that follows the ColorOS style, whether it draws its own glass or not. */
@Composable
internal fun islandCosCards(): Boolean = LocalIslandCosStyle.current || islandGlassCards()

private val GlassCardShape = RoundedCornerShape(28.dp)
private val GlassTintDark = Color.White.copy(alpha = 0.10f)
private val GlassTintLight = Color.White.copy(alpha = 0.55f)
private val GlassPrimaryFill = Color(0xFF2F7BFF)

/**
 * The glass of every ColorOS style island card. The content follows the theme, so it stays
 * readable over the pale blur of the light theme. With liquid glass on, the card uses that glass.
 */
@Composable
internal fun IslandGlassSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    if (LocalIslandCosStyle.current && LocalPopupContainer.current) {
        Box(modifier = modifier, propagateMinConstraints = true) { content() }
        return
    }
    val blurState = LocalIslandCardBlurState.current
    // Move the blur by layout and fade it with the card; it must not stay behind a card that is
    // being dragged or closed.
    val shiftModifier =
        modifier.offset {
            IntOffset(blurState.shift.x.roundToInt(), blurState.shift.y.roundToInt())
        }
    if (liquidGlassEnabled()) {
        PopupSurface(
            shape = GlassCardShape,
            modifier = shiftModifier.graphicsLayer { alpha = blurState.alpha },
            content = content,
        )
        return
    }
    val colors = MaterialTheme.colorScheme
    val currentContent by rememberUpdatedState(content)
    // Moves between the two parents below without losing the state of the card.
    val body = remember {
        movableContentOf {
            CompositionLocalProvider(
                LocalContentColor provides MaterialTheme.colorScheme.onSurface,
                content = currentContent,
            )
        }
    }
    // The blur of the surface is composited outside the card layer at its full size, whatever the
    // layer alpha is. So there is no surface until the card has grown, or it would blur the island.
    if (blurState.alpha <= 0f) {
        Box(modifier = modifier) { body() }
        return
    }
    AxBlurSurface(
        modifier =
            shiftModifier.then(
                if (blurState.glassFollowsAlpha) Modifier
                else Modifier.graphicsLayer { alpha = blurState.alpha }
            ),
        alpha = if (blurState.glassFollowsAlpha) blurState.alpha else 1f,
        shape = GlassCardShape,
        cornerRadius = 28.dp,
        // Without a blur region the card is a plain fill in the theme's surface colour.
        surfaceColor = colors.surfaceContainerHigh.copy(alpha = 0.9f),
        tintColor = if (colors.surface.luminance() < 0.5f) GlassTintDark else GlassTintLight,
        highlight = ColorOSTileRim,
    ) {
        body()
    }
}

/**
 * The ColorOS style live card used by the island features with the ColorOS style on: a glyph, a
 * large primary line over a small label, and up to two buttons, on frosted glass. A button whose
 * action has no icon is drawn as a pill with the label of the action.
 */
@Composable
fun IslandGlassCard(
    primary: String,
    secondary: String?,
    glyph: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    primaryAction: PopupActionModel? = null,
    secondaryAction: PopupActionModel? = null,
    primaryActionColor: Color = Color.Unspecified,
    primaryActionTint: Color = Color.White,
    secondaryActionColor: Color? = null,
    secondaryActionTint: Color? = null,
    below: (@Composable () -> Unit)? = null,
) {
    val liquid = liquidGlassEnabled()
    val primaryFill =
        primaryActionColor.takeOrElse { if (liquid) LiquidGlassBlue else GlassPrimaryFill }
    IslandGlassSurface(
        modifier =
            modifier
                .widthIn(min = 300.dp, max = 400.dp)
                .pressScale(enabled = onClick != null, pressedScale = 0.97f) { onClick?.invoke() },
    ) {
        GlassCardBody(
            primary,
            secondary,
            glyph,
            primaryAction,
            secondaryAction,
            primaryFill,
            primaryActionTint,
            secondaryActionColor,
            secondaryActionTint,
            below,
            // Liquid glass keeps the content off the camera lens itself; the plain glass runs up
            // around the camera, so its content keeps the clearance.
            clearance = if (liquid) 0.dp else LocalPopupCameraClearance.current,
        )
    }
}

@Composable
private fun GlassCardBody(
    primary: String,
    secondary: String?,
    glyph: @Composable () -> Unit,
    primaryAction: PopupActionModel?,
    secondaryAction: PopupActionModel?,
    primaryFill: Color,
    primaryTint: Color,
    secondaryFill: Color?,
    secondaryTint: Color?,
    below: (@Composable () -> Unit)?,
    clearance: Dp,
) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .padding(top = clearance)
                .heightIn(min = GlassCardMinHeight)
                .padding(start = 20.dp, end = 18.dp, top = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(50.dp), contentAlignment = Alignment.Center) { glyph() }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = primary,
                    fontSize = 30.sp,
                    lineHeight = 34.sp,
                    fontWeight = FontWeight.Bold,
                    color = LocalContentColor.current,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                secondary
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        Text(
                            text = it,
                            fontSize = 14.sp,
                            lineHeight = 18.sp,
                            color = LocalContentColor.current.copy(alpha = 0.55f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
            }
            if (primaryAction != null || secondaryAction != null) {
                Spacer(Modifier.width(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    primaryAction?.let { GlassActionButton(it, fill = primaryFill, tint = primaryTint) }
                    secondaryAction?.let {
                        GlassActionButton(
                            it,
                            fill = secondaryFill ?: LocalContentColor.current.copy(alpha = 0.16f),
                            tint = secondaryTint
                                ?: if (secondaryFill != null) Color.White
                                else LocalContentColor.current,
                        )
                    }
                }
            }
        }
        below?.invoke()
    }
}

private val GlassCardMinHeight = 100.dp

@Composable
private fun GlassActionButton(action: PopupActionModel, fill: Color, tint: Color) {
    val icon = action.icon
    val vector = action.vector
    if (vector == null && icon == null) {
        // Without a glyph the label is the button.
        Box(
            modifier =
                Modifier.height(40.dp)
                    .pressScale(onClick = action.onClick)
                    .background(fill, CircleShape)
                    .padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = action.label,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = tint,
                maxLines = 1,
            )
        }
        return
    }
    Box(
        modifier =
            Modifier.size(50.dp)
                .pressScale(onClick = action.onClick)
                .background(fill, CircleShape)
                .semantics { contentDescription = action.label },
        contentAlignment = Alignment.Center,
    ) {
        if (vector != null) {
            MaterialIcon(
                imageVector = vector,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = tint,
            )
        } else if (icon != null) {
            Icon(icon = icon, modifier = Modifier.size(24.dp), tint = tint)
        }
    }
}

/** A thin glass progress track for live cards. */
@Composable
fun IslandGlassProgress(progress: Float, accent: Color, modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(LocalContentColor.current.copy(alpha = 0.18f), CircleShape)
    ) {
        Box(
            modifier =
                Modifier.fillMaxWidth(progress.coerceIn(0f, 1f))
                    .height(4.dp)
                    .background(accent, CircleShape)
        )
    }
}
