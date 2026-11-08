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

package com.android.systemui.statusbar.quickactions.stopwatch.ui.viewmodel

import android.content.Context
import android.content.pm.UserInfo
import android.os.SystemClock
import android.util.SparseArray
import androidx.compose.runtime.getValue
import com.android.systemui.ActivityIntentHelper
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.lifecycle.HydratedActivatable
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.res.R
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.notifcollection.CommonNotifCollection
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.statusbar.quickactions.popups.shared.ClockNotificationKind
import com.android.systemui.statusbar.quickactions.popups.shared.DynamicIslandFeatureSettings
import com.android.systemui.statusbar.quickactions.popups.shared.PopupItemDismissal
import com.android.systemui.statusbar.quickactions.popups.shared.formatClockDuration
import com.android.systemui.statusbar.quickactions.popups.shared.parseClockNotification
import com.android.systemui.statusbar.quickactions.popups.shared.toActivityLaunchAction
import com.android.systemui.statusbar.quickactions.popups.shared.toClockPopupActions
import com.android.systemui.statusbar.quickactions.popups.ui.viewmodel.StatusBarPopupChipViewModel
import com.android.systemui.statusbar.quickactions.shared.model.ChipIcon
import com.android.systemui.statusbar.quickactions.shared.model.PopupContentModel
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipId
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipModel
import com.android.systemui.statusbar.quickactions.stopwatch.shared.model.StopwatchPopupModel
import com.android.systemui.statusbar.quickactions.ui.compose.ChipColors
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** ViewModel backing the stopwatch page inside the dynamic island. */
class StopwatchPopupChipViewModel
@AssistedInject
constructor(
    @Application private val context: Context,
    private val notifCollection: CommonNotifCollection,
    private val activityStarter: ActivityStarter,
    private val activityIntentHelper: ActivityIntentHelper,
    private val lockscreenUserManager: NotificationLockscreenUserManager,
    private val keyguardStateController: KeyguardStateController,
) : StatusBarPopupChipViewModel, HydratedActivatable() {

    private val dismissal = PopupItemDismissal()

    override val chip: QuickActionChipModel by
        callbackFlow {
                val listener =
                    object : NotifCollectionListener {
                        override fun onEntryAdded(entry: NotificationEntry) {
                            trySend(Unit)
                        }

                        override fun onEntryUpdated(entry: NotificationEntry) {
                            trySend(Unit)
                        }

                        override fun onEntryRemoved(entry: NotificationEntry, reason: Int) {
                            trySend(Unit)
                        }

                        override fun onRankingApplied() {
                            trySend(Unit)
                        }
                    }

                val userChangedListener =
                    object : NotificationLockscreenUserManager.UserChangedListener {
                        override fun onUserChanged(userId: Int) {
                            trySend(Unit)
                        }

                        override fun onCurrentProfilesChanged(currentProfiles: SparseArray<UserInfo>) {
                            trySend(Unit)
                        }
                    }

                lockscreenUserManager.addUserChangedListener(userChangedListener)
                notifCollection.addCollectionListener(listener)
                trySend(Unit)
                awaitClose {
                    notifCollection.removeCollectionListener(listener)
                    lockscreenUserManager.removeUserChangedListener(userChangedListener)
                }
            }
            .map {
                notifCollection.allNotifs
                    .asSequence()
                    .filter { lockscreenUserManager.isCurrentProfile(it.sbn.userId) }
                    .mapNotNull { entry ->
                        entry.toStopwatchModel(
                            context = context,
                            activityStarter = activityStarter,
                            activityIntentHelper = activityIntentHelper,
                            lockscreenUserManager = lockscreenUserManager,
                            keyguardStateController = keyguardStateController,
                        )
                    }
                    .firstOrNull()
            }
            .map(::toPopupChipModel)
            .let(dismissal::filterDismissed)
            .combine(
                DynamicIslandFeatureSettings.observeDynamicIslandFeatureEnabled(
                    context,
                    DynamicIslandFeatureSettings.STOPWATCH,
                )
            ) { model, enabled ->
                if (enabled) model else QuickActionChipModel.Hidden(QuickActionChipId.Stopwatch)
            }
            .hydratedStateOf(
                traceName = "chip",
                initialValue = QuickActionChipModel.Hidden(QuickActionChipId.Stopwatch),
            )

    private fun toPopupChipModel(model: StopwatchPopupModel?): QuickActionChipModel {
        if (model == null) {
            return QuickActionChipModel.Hidden(QuickActionChipId.Stopwatch)
        }

        return QuickActionChipModel.PopupChip(
            chipId = QuickActionChipId.Stopwatch,
            icons = listOfNotNull(model.icon?.let { ChipIcon(icon = it, onClick = model.onOpen) }),
            chipContent = null,
            colors = ChipColors.DynamicIsland,
            contentDescription = ContentDescription.Loaded(model.title),
            popupContent = PopupContentModel.Stopwatch(model),
            dismissKey = model.key,
        )
    }

    @AssistedFactory
    interface Factory {
        fun create(): StopwatchPopupChipViewModel
    }
}

private fun NotificationEntry.toStopwatchModel(
    context: Context,
    activityStarter: ActivityStarter,
    activityIntentHelper: ActivityIntentHelper,
    lockscreenUserManager: NotificationLockscreenUserManager,
    keyguardStateController: KeyguardStateController,
): StopwatchPopupModel? {
    val info = parseClockNotification(context) ?: return null
    if (info.kind != ClockNotificationKind.STOPWATCH) {
        return null
    }

    val notification = sbn.notification
    val nowElapsedRealtimeMs = SystemClock.elapsedRealtime()
    val nowEpochMs = System.currentTimeMillis()
    val metric = info.metric
    val chronometer = info.chronometer
    // Keep the chronometer/RemoteViews fallback, but never invent a stopwatch without a time. A
    // paused stopwatch may only show its time as text.
    if (metric == null && chronometer == null && info.pausedTimeText == null) {
        return null
    }
    val timeMs = metric?.timeMs(nowElapsedRealtimeMs, nowEpochMs)
    val baseElapsedRealtimeMs =
        when {
            metric != null -> nowElapsedRealtimeMs - (timeMs ?: 0L)
            chronometer != null -> chronometer.baseElapsedRealtimeMs
            else -> nowElapsedRealtimeMs
        }
    val elapsedTimeText =
        if (info.isRunning) {
            null
        } else {
            info.pausedTimeText
                ?: formatClockDuration(
                    (timeMs ?: (nowElapsedRealtimeMs - baseElapsedRealtimeMs)).coerceAtLeast(0L)
                )
        }
    val appLabel = resolveAppLabel(context, sbn.packageName)

    return StopwatchPopupModel(
        icon =
            Icon.Resource(
                resId = R.drawable.ic_dynamic_island_stopwatch,
                contentDescription = ContentDescription.Resource(R.string.dynamic_island_stopwatch_title),
            ),
        title =
            info.label?.takeUnless { it.isBlank() }
                ?: context.getString(R.string.dynamic_island_stopwatch_title),
        subtitle = appLabel.takeUnless { it.isBlank() },
        baseElapsedRealtimeMs = baseElapsedRealtimeMs,
        isRunning = info.isRunning,
        elapsedTimeText = elapsedTimeText,
        onOpen =
            notification.contentIntent?.toActivityLaunchAction(
                activityStarter = activityStarter,
                activityIntentHelper = activityIntentHelper,
                lockscreenUserManager = lockscreenUserManager,
                keyguardStateController = keyguardStateController,
            ),
        actions =
            notification.toClockPopupActions(
                context = context,
                isRunning = info.isRunning,
                extraLabelRes = R.string.dynamic_island_action_lap,
                vectorActions = true,
            ),
        key = sbn.key,
    )
}

private fun resolveAppLabel(context: Context?, packageName: String): String {
    val safeContext = context ?: return packageName
    return runCatching {
            val pm = safeContext.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo)?.toString()
        }
        .getOrNull()
        ?.takeUnless { it.isBlank() }
        ?: packageName
}
