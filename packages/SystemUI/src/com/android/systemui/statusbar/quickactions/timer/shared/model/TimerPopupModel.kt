// SPDX-License-Identifier: Apache-2.0

package com.android.systemui.statusbar.quickactions.timer.shared.model

import com.android.systemui.common.shared.model.Icon
import com.android.systemui.statusbar.quickactions.popups.shared.model.PopupActionModel

/** Popup content for a running or paused timer inside the dynamic island. */
data class TimerPopupModel(
    val icon: Icon?,
    val title: String,
    val subtitle: String?,
    /** Elapsed realtime at which the countdown reaches zero. */
    val endElapsedRealtimeMs: Long,
    val isRunning: Boolean,
    /** Fixed remaining time for paused timers. */
    val remainingTimeText: String?,
    val onOpen: (() -> Unit)?,
    val actions: List<PopupActionModel> = emptyList(),
    /** The key of the notification behind the timer. */
    val key: String = "",
)
