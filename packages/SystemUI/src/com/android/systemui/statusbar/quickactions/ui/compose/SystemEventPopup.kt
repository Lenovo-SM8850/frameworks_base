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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.systemui.common.ui.compose.Icon as StatusBarIcon
import com.android.systemui.res.R
import com.android.systemui.statusbar.quickactions.popups.shared.model.BluetoothBatteryComponent
import com.android.systemui.statusbar.quickactions.popups.shared.model.BluetoothBatteryModel
import com.android.systemui.statusbar.quickactions.popups.shared.model.SystemEventKind
import com.android.systemui.statusbar.quickactions.shared.model.PopupContentModel

private val EventPopupShape = RoundedCornerShape(32.dp)

/** Popup for a system event: a connected Bluetooth device, the hotspot or a VPN connection. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SystemEventPopup(model: PopupContentModel.SystemEvent, modifier: Modifier = Modifier) {
    model.call
        ?.takeIf { model.kind == SystemEventKind.Call && islandCosCards() }
        ?.let {
            CallIslandCard(model, it, modifier)
            return
        }
    val accent = model.accent ?: IslandAccents.Connectivity
    if (islandCosCards()) {
        SystemEventGlassCard(model = model, accent = accent, modifier = modifier)
        return
    }
    PopupSurface(
        shape = EventPopupShape,
        modifier = modifier.widthIn(min = 280.dp, max = 360.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            EventHeader(model, accent)

            val earbuds =
                model.bluetoothBatteries.filter { it.component != BluetoothBatteryComponent.Case }
            if (earbuds.isNotEmpty()) EarbudBatteries(earbuds, accent)

            model.prominentText?.let { value ->
                Text(
                    value,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                )
            }
            if (model.indeterminate) {
                LinearWavyProgressIndicator(
                    color = accent,
                    trackColor = accent.copy(alpha = 0.16f),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                model.progress?.let { value ->
                    LinearWavyProgressIndicator(
                        progress = { value.coerceIn(0f, 1f) },
                        color = accent,
                        trackColor = accent.copy(alpha = 0.16f),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (model.actions.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    model.actions.forEach { action ->
                        PopupActionChips(
                            actions = listOf(action.copy(emphasized = true)),
                            accent = accent,
                            modifier = Modifier.widthIn(max = 320.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EventHeader(model: PopupContentModel.SystemEvent, accent: Color) {
    val caseBattery =
        model.bluetoothBatteries.firstOrNull { it.component == BluetoothBatteryComponent.Case }
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (model.image != null) {
            StatusBarIcon(icon = model.image, tint = Color.Unspecified, modifier = Modifier.size(56.dp))
        } else {
            IslandGlyphBadge(accent = accent, size = 44.dp) {
                if (model.icon != null) {
                    // Glyphs shipped as resources are plain white and take the accent; icons loaded
                    // from a device keep their own colors.
                    StatusBarIcon(
                        icon = model.icon,
                        tint = eventIconTint(model, accent),
                        modifier = Modifier.size(22.dp),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Bluetooth,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                model.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            model.callStartTimeMs?.let { startMs ->
                val base =
                    android.os.SystemClock.elapsedRealtime() -
                        (System.currentTimeMillis() - startMs).coerceAtLeast(0L)
                Text(
                    rememberElapsedDurationText(base),
                    style =
                        MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                    color = LocalContentColor.current.copy(alpha = 0.73f),
                    maxLines = 1,
                )
            }
            if (model.text.isNotBlank()) {
                Text(
                    model.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(alpha = 0.73f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            caseBattery?.let { battery -> CaseBatteryRow(battery, accent) }
        }
    }
}

/**
 * The tint of the glyph of an event. Glyphs shipped as resources are plain white and take the
 * accent. The glyph of a live update is the small icon of the app, also white, so it takes the
 * accent the app asked for or else the colour of the card content, which follows the card theme.
 * Other icons loaded from a device keep their own colors.
 */
@Composable
private fun eventIconTint(model: PopupContentModel.SystemEvent, accent: Color): Color =
    when {
        model.icon is com.android.systemui.common.shared.model.Icon.Resource -> accent
        model.kind == SystemEventKind.LiveUpdate ->
            if (model.accent != null) accent else LocalContentColor.current
        else -> Color.Unspecified
    }

@Composable
private fun CaseBatteryRow(battery: BluetoothBatteryModel, accent: Color) {
    Row(
        modifier = Modifier.semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.dynamic_island_earbuds_case),
            contentDescription = null,
            tint = LocalContentColor.current.copy(alpha = 0.7f),
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = battery.label,
            style = MaterialTheme.typography.labelSmall,
            color = LocalContentColor.current.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (battery.isCharging) ChargingBolt(accent, size = 14.dp)
        Text(
            text = "${battery.level}%",
            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.SemiBold,
            color = accent,
            maxLines = 1,
        )
    }
}

private val GlassBlue = Color(0xFF2F7BFF)
private val CallAcceptGreen = Color(0xFF34C759)
private val CallEndRed = Color(0xFFFF5F52)

/**
 * The ColorOS style live card of a system event. The glyph, the leading value and the label sit in
 * the card, up to two actions are its buttons, and what the card has no place for (progress, the
 * earbud batteries, further actions) goes below.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SystemEventGlassCard(
    model: PopupContentModel.SystemEvent,
    accent: Color,
    modifier: Modifier,
) {
    val elapsed =
        model.callStartTimeMs?.let { startMs ->
            val base =
                android.os.SystemClock.elapsedRealtime() -
                    (System.currentTimeMillis() - startMs).coerceAtLeast(0L)
            rememberElapsedDurationText(base)
        }
    val prominent = model.prominentText?.takeIf { it.isNotBlank() }
    val text = model.text.takeIf { it.isNotBlank() }
    // A value such as a battery level or a time left leads the card and the title labels it;
    // otherwise the title leads and the running time or the text labels it.
    val primary = prominent ?: model.title
    val secondary =
        if (prominent != null) listOfNotNull(model.title, elapsed).joinToString(" · ")
        else listOfNotNull(elapsed, text).joinToString(" · ")
    // The text that has no line of its own in the card because a value took the lead.
    val detail = text.takeIf { prominent != null }

    // A ringing call lists decline before answer, which is also the order of its red and green
    // buttons. The model carries no meaning for the actions, so a ringing call with two actions
    // is taken to be that pair.
    val isCall = model.kind == SystemEventKind.Call
    val ringingPair = isCall && model.isRinging && model.actions.size == 2
    val primaryAction = model.actions.getOrNull(0)
    val secondaryAction = model.actions.getOrNull(1)
    val extraActions = model.actions.drop(2)

    val earbuds = model.bluetoothBatteries.filter { it.component != BluetoothBatteryComponent.Case }
    val caseBattery =
        model.bluetoothBatteries.firstOrNull { it.component == BluetoothBatteryComponent.Case }
    val hasProgress = model.indeterminate || model.progress != null
    val below: (@Composable () -> Unit)? =
        if (
            detail != null ||
                hasProgress ||
                earbuds.isNotEmpty() ||
                caseBattery != null ||
                extraActions.isNotEmpty()
        ) {
            {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    detail?.let {
                        Text(
                            text = it,
                            fontSize = 14.sp,
                            lineHeight = 18.sp,
                            color = LocalContentColor.current.copy(alpha = 0.55f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (model.indeterminate) {
                        LinearWavyProgressIndicator(
                            color = accent,
                            trackColor = accent.copy(alpha = 0.16f),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        model.progress?.let { IslandGlassProgress(progress = it, accent = accent) }
                    }
                    if (earbuds.isNotEmpty()) EarbudBatteries(earbuds, accent)
                    caseBattery?.let { CaseBatteryRow(it, accent) }
                    if (extraActions.isNotEmpty()) {
                        PopupActionChips(actions = extraActions, accent = accent)
                    }
                }
            }
        } else {
            null
        }

    IslandGlassCard(
        primary = primary,
        secondary = secondary,
        glyph = {
            if (model.image != null) {
                StatusBarIcon(
                    icon = model.image,
                    tint = Color.Unspecified,
                    modifier = Modifier.size(50.dp),
                )
            } else if (model.icon != null) {
                // Glyphs shipped as resources are plain white and take the accent; icons loaded from
                // a device keep their own colors.
                StatusBarIcon(
                    icon = model.icon,
                    tint = eventIconTint(model, accent),
                    modifier = Modifier.size(40.dp),
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Bluetooth,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(40.dp),
                )
            }
        },
        modifier = modifier,
        primaryAction = primaryAction,
        secondaryAction = secondaryAction,
        // The button that ends a call is red and answer is green; the other actions are blue.
        primaryActionColor = if (isCall) CallEndRed else GlassBlue,
        secondaryActionColor = if (ringingPair) CallAcceptGreen else null,
        secondaryActionTint = if (ringingPair) Color.White else null,
        below = below,
    )
}

@Composable
private fun ChargingBolt(tint: Color, size: Dp) {
    Icon(
        imageVector = Icons.Filled.Bolt,
        contentDescription = stringResource(R.string.dynamic_island_charging_title),
        tint = tint,
        modifier = Modifier.size(size),
    )
}

@Composable
private fun EarbudBatteries(batteries: List<BluetoothBatteryModel>, accent: Color) {
    val columns = if (LocalDensity.current.fontScale > 1.3f) 1 else 2
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        batteries.chunked(columns).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { battery ->
                    Row(
                        modifier =
                            Modifier.weight(1f)
                                .heightIn(min = 56.dp)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(12.dp))
                                .background(LocalContentColor.current.copy(alpha = 0.05f))
                                .semantics(mergeDescendants = true) {}
                                .padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.dynamic_island_earbud),
                            contentDescription = null,
                            tint = LocalContentColor.current.copy(alpha = 0.85f),
                            modifier =
                                Modifier.size(24.dp).graphicsLayer {
                                    scaleX =
                                        if (battery.component == BluetoothBatteryComponent.RightEarbud)
                                            -1f
                                        else 1f
                                },
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = battery.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = LocalContentColor.current.copy(alpha = 0.7f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (battery.isCharging) ChargingBolt(accent, size = 16.dp)
                                Text(
                                    text = "${battery.level}%",
                                    style =
                                        MaterialTheme.typography.titleMedium.copy(
                                            fontFeatureSettings = "tnum"
                                        ),
                                    fontWeight = FontWeight.SemiBold,
                                    color = accent,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
