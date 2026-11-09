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
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
    val blurState = LocalIslandCardBlurState.current
    // Move the blur by layout and fade it with the card; it must not stay behind a card that is
    // being dragged or closed.
    val glassModifier =
        modifier
            .offset { IntOffset(blurState.shift.x.roundToInt(), blurState.shift.y.roundToInt()) }
            .graphicsLayer { alpha = blurState.alpha }
    if (liquidGlassEnabled()) {
        PopupSurface(shape = GlassCardShape, modifier = glassModifier, content = content)
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
    AxBlurSurface(
        modifier = glassModifier,
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
 * large primary line over a small label, and up to two round buttons, on frosted glass.
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
    secondaryActionColor: Color = Color.Unspecified,
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
            secondaryActionColor,
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
    secondaryFill: Color,
    below: (@Composable () -> Unit)?,
    clearance: Dp,
) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .padding(top = clearance)
                .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) { glyph() }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = primary,
                    fontSize = 26.sp,
                    lineHeight = 30.sp,
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
                            color = LocalContentColor.current.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
            }
            if (primaryAction != null || secondaryAction != null) {
                Spacer(Modifier.width(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    primaryAction?.let {
                        GlassRoundButton(it, fill = primaryFill, tint = Color.White, size = 52)
                    }
                    secondaryAction?.let {
                        val customColor = secondaryFill != Color.Unspecified
                        GlassRoundButton(
                            it,
                            fill = if (customColor) secondaryFill
                                else LocalContentColor.current.copy(alpha = 0.16f),
                            tint = if (customColor) Color.White else LocalContentColor.current,
                            size = 48,
                        )
                    }
                }
            }
        }
        below?.invoke()
    }
}

@Composable
private fun GlassRoundButton(action: PopupActionModel, fill: Color, tint: Color, size: Int) {
    Box(
        modifier =
            Modifier.size(size.dp)
                .pressScale(onClick = action.onClick)
                .background(fill, CircleShape)
                .semantics { contentDescription = action.label },
        contentAlignment = Alignment.Center,
    ) {
        val icon = action.icon
        val vector = action.vector
        when {
            vector != null ->
                MaterialIcon(
                    imageVector = vector,
                    contentDescription = null,
                    modifier = Modifier.size(26.dp),
                    tint = tint,
                )
            icon != null -> Icon(icon = icon, modifier = Modifier.size(24.dp), tint = tint)
            else ->
                Text(
                    text = action.label.take(2),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = tint,
                )
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

/**
 * Where the island popup card is while it is dragged or revealed. The card body moves with it, but
 * its glass is composited outside the card layer and would stay where it was; the glass reads this
 * to follow the card and fade with it.
 */
data class IslandCardBlurState(
    val shift: Offset = Offset.Zero,
    val alpha: Float = 1f,
)

internal val LocalIslandCardBlurState = compositionLocalOf { IslandCardBlurState() }
