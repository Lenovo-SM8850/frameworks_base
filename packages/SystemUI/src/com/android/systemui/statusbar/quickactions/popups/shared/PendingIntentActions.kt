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

package com.android.systemui.statusbar.quickactions.popups.shared

import android.app.ActivityOptions
import android.app.BroadcastOptions
import android.app.PendingIntent
import android.util.Log
import com.android.systemui.ActivityIntentHelper
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.statusbar.quickactions.popups.shared.model.PopupActionModel

fun PendingIntent?.toActivityLaunchAction(
    activityStarter: ActivityStarter,
    activityIntentHelper: ActivityIntentHelper,
    lockscreenUserManager: NotificationLockscreenUserManager,
    keyguardStateController: KeyguardStateController,
): (() -> Unit)? {
    return this?.let { pendingIntent ->
        {
            val showOverLockscreen =
                keyguardStateController.isShowing &&
                    activityIntentHelper.wouldPendingShowOverLockscreen(
                        pendingIntent,
                        lockscreenUserManager.currentUserId,
                    )

            if (showOverLockscreen) {
                activityStarter.startPendingIntentMaybeDismissingKeyguard(
                    pendingIntent,
                    null,
                    null,
                )
            } else {
                activityStarter.postStartActivityDismissingKeyguard(pendingIntent, null)
            }
        }
    }
}

/** An action chip that sends this intent, or null without one. */
fun PendingIntent?.toSendPopupAction(
    label: String,
    emphasized: Boolean = false,
    vector: androidx.compose.ui.graphics.vector.ImageVector? = null,
): PopupActionModel? {
    val action = toSendAction() ?: return null
    return PopupActionModel(
        label = label,
        onClick = action,
        emphasized = emphasized,
        vector = vector,
        launchesApp = this?.isActivity == true,
    )
}

fun PendingIntent?.toSendAction(): (() -> Unit)? {
    return this?.let { pendingIntent ->
        {
            // Sent like a tap in the shade: the user tapped it, so the app may start an
            // activity. Without this, Answer on a call card is "BAL blocked".
            runCatching {
                    pendingIntent.send(
                        BroadcastOptions.makeBasic()
                            .apply {
                                isInteractive = true
                                pendingIntentBackgroundActivityStartMode =
                                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
                            }
                            .toBundle()
                    )
                }
                .onFailure {
                    Log.w("DynamicIsland", "Unable to deliver pending intent action", it)
                }
        }
    }
}
