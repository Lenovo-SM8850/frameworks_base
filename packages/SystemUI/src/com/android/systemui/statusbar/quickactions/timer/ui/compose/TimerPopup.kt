// SPDX-License-Identifier: Apache-2.0

package com.android.systemui.statusbar.quickactions.timer.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.statusbar.quickactions.timer.shared.model.TimerPopupModel
import com.android.systemui.statusbar.quickactions.ui.compose.PopupActionChips
import com.android.systemui.statusbar.quickactions.ui.compose.PopupSurface
import com.android.systemui.statusbar.quickactions.ui.compose.rememberPopupActions
import com.android.systemui.statusbar.quickactions.ui.compose.rememberPopupLaunchAction
import com.android.systemui.statusbar.quickactions.ui.compose.rememberRemainingDurationText

private val PopupShape = RoundedCornerShape(32.dp)

/** Expanded timer card surfaced in the dynamic island. */
@Composable
fun TimerPopup(
    model: TimerPopupModel,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    val onOpen = rememberPopupLaunchAction(model.onOpen)
    PopupSurface(
        shape = PopupShape,
        modifier =
            modifier
                .widthIn(min = 300.dp, max = 360.dp)
                .clickable(enabled = onOpen != null) { onOpen?.invoke() },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                model.icon?.let {
                    Icon(
                        icon = it,
                        modifier = Modifier.size(24.dp),
                        // The glyph is drawn white for the black island chip; tint it for the card.
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = model.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = LocalContentColor.current,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    model.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = LocalContentColor.current.copy(alpha = 0.73f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Text(
                text =
                    model.remainingTimeText
                        ?: rememberRemainingDurationText(model.endElapsedRealtimeMs),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = accent,
            )

            PopupActionChips(actions = rememberPopupActions(model.actions), accent = accent)
        }
    }
}
