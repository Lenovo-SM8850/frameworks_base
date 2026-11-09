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

package com.android.systemui.statusbar.quickactions.alarm.ui.viewmodel

import androidx.compose.runtime.getValue
import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.pm.UserInfo
import android.text.format.DateFormat
import android.util.SparseArray
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
import com.android.systemui.statusbar.policy.NextAlarmController
import com.android.systemui.statusbar.quickactions.alarm.shared.model.AlarmPopupModel
import com.android.systemui.statusbar.quickactions.popups.shared.ClockNotificationKind
import com.android.systemui.statusbar.quickactions.popups.shared.parseClockNotification
import com.android.systemui.statusbar.quickactions.popups.shared.toActivityLaunchAction
import com.android.systemui.statusbar.quickactions.popups.ui.viewmodel.StatusBarPopupChipViewModel
import com.android.systemui.statusbar.quickactions.shared.model.ChipContent
import com.android.systemui.statusbar.quickactions.shared.model.ChipIcon
import com.android.systemui.statusbar.quickactions.shared.model.PopupContentModel
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipId
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipModel
import com.android.systemui.statusbar.quickactions.ui.compose.ChipColors
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import com.android.systemui.statusbar.quickactions.popups.shared.DynamicIslandFeatureSettings
import com.android.systemui.statusbar.quickactions.popups.shared.PopupItemDismissal
import com.android.systemui.statusbar.quickactions.popups.shared.model.PopupActionModel
import com.android.systemui.statusbar.quickactions.popups.shared.toSendPopupAction
import java.util.Locale
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** ViewModel backing the next-alarm page inside the dynamic island. */
class AlarmPopupChipViewModel
@AssistedInject
constructor(
    @Application private val context: Context,
    private val nextAlarmController: NextAlarmController,
    private val notifCollection: CommonNotifCollection,
    private val activityStarter: ActivityStarter,
    private val activityIntentHelper: ActivityIntentHelper,
    private val lockscreenUserManager: NotificationLockscreenUserManager,
    private val keyguardStateController: KeyguardStateController,
) : StatusBarPopupChipViewModel, HydratedActivatable() {

    private val dismissal = PopupItemDismissal()

    override val chip: QuickActionChipModel by
        callbackFlow<AlarmPopupState> {
                var nextAlarm: AlarmManager.AlarmClockInfo? = null
                fun emitState() {
                    trySend(
                        AlarmPopupState(
                            nextAlarm = nextAlarm,
                            activeNotification =
                                notifCollection.allNotifs.firstOrNull {
                                    lockscreenUserManager.isCurrentProfile(it.sbn.userId) &&
                                        it.isAlarmCandidate(context)
                                },
                        )
                    )
                }

                val callback =
                    NextAlarmController.NextAlarmChangeCallback { updatedAlarm ->
                        nextAlarm = updatedAlarm
                        emitState()
                    }
                val notificationListener =
                    object : NotifCollectionListener {
                        override fun onEntryAdded(entry: NotificationEntry) = emitState()

                        override fun onEntryUpdated(entry: NotificationEntry) = emitState()

                        override fun onEntryRemoved(entry: NotificationEntry, reason: Int) =
                            emitState()

                        override fun onRankingApplied() = emitState()
                    }

                val userChangedListener =
                    object : NotificationLockscreenUserManager.UserChangedListener {
                        override fun onUserChanged(userId: Int) = emitState()

                        override fun onCurrentProfilesChanged(currentProfiles: SparseArray<UserInfo>) =
                            emitState()
                    }

                lockscreenUserManager.addUserChangedListener(userChangedListener)
                nextAlarmController.addCallback(callback)
                notifCollection.addCollectionListener(notificationListener)
                emitState()
                awaitClose {
                    nextAlarmController.removeCallback(callback)
                    notifCollection.removeCollectionListener(notificationListener)
                    lockscreenUserManager.removeUserChangedListener(userChangedListener)
                }
            }
            .map(::toPopupChipModel)
            .let(dismissal::filterDismissed)
            .combine(
                DynamicIslandFeatureSettings.observeDynamicIslandFeatureEnabled(
                    context,
                    DynamicIslandFeatureSettings.ALARMS,
                )
            ) { model, enabled ->
                if (enabled) model else QuickActionChipModel.Hidden(QuickActionChipId.Alarm)
            }
            .hydratedStateOf(
                traceName = "chip",
                initialValue = QuickActionChipModel.Hidden(QuickActionChipId.Alarm),
            )

    private fun toPopupChipModel(state: AlarmPopupState): QuickActionChipModel {
        val alarm = state.nextAlarm?.takeIf { it.triggerTime > 0L }
        val activeNotification = state.activeNotification
        if (alarm == null && activeNotification == null) {
            return QuickActionChipModel.Hidden(QuickActionChipId.Alarm)
        }

        val chipTimeText =
            alarm?.let { formatAlarmTime(it.triggerTime, skeleton = chipSkeleton()) }
                ?: activeNotification?.notificationText().orEmpty().ifBlank {
                    context.getString(R.string.dynamic_island_alarm_active_title)
                }
        val fullTimeText =
            alarm?.let { formatAlarmTime(it.triggerTime, skeleton = chipSkeleton()) }
                ?: chipTimeText
        val dayText =
            alarm?.let { formatAlarmTime(it.triggerTime, skeleton = daySkeleton()) }
                ?: activeNotification?.notificationTitle().orEmpty().ifBlank {
                    context.getString(R.string.dynamic_island_alarm_active_title)
                }
        val onOpen =
            activeNotification?.sbn?.notification?.contentIntent.toActivityLaunchAction(
                activityStarter = activityStarter,
                activityIntentHelper = activityIntentHelper,
                lockscreenUserManager = lockscreenUserManager,
                keyguardStateController = keyguardStateController,
            )
                ?: alarm?.showIntent.toActivityLaunchAction(
                    activityStarter = activityStarter,
                    activityIntentHelper = activityIntentHelper,
                    lockscreenUserManager = lockscreenUserManager,
                    keyguardStateController = keyguardStateController,
                )

        val model =
            AlarmPopupModel(
                title =
                    if (activeNotification?.hasSnoozeAction() == true) {
                        context.getString(R.string.dynamic_island_alarm_active_title)
                    } else {
                        context.getString(R.string.dynamic_island_alarm_title)
                    },
                triggerTimeMs = alarm?.triggerTime ?: 0L,
                chipTimeText = chipTimeText,
                fullTimeText = fullTimeText,
                dayText = dayText,
                onOpen = onOpen,
                actions =
                    listOfNotNull(
                        activeNotification
                            ?.snoozeAction()
                            .toSendPopupAction(
                                context.getString(R.string.dynamic_island_action_snooze),
                                emphasized = true,
                            )
                    ),
                dismissAction = activeNotification?.dismissAction(),
            )
        val contentDescription =
            alarm?.let {
                ContentDescription.Loaded(
                    context.getString(
                        R.string.accessibility_quick_settings_alarm,
                        formatAlarmTime(it.triggerTime, skeleton = fullDescriptionSkeleton()),
                    )
                )
            } ?: ContentDescription.Loaded(model.title)

        return QuickActionChipModel.PopupChip(
            chipId = QuickActionChipId.Alarm,
            icons =
                listOf(
                    ChipIcon(
                        icon =
                            Icon.Resource(
                                resId = R.drawable.ic_dynamic_island_alarm,
                                contentDescription = ContentDescription.Resource(R.string.status_bar_alarm),
                            ),
                        onClick = model.onOpen,
                    )
                ),
            chipContent = ChipContent.Text(model.chipTimeText),
            colors = ChipColors.DynamicIsland,
            contentDescription = contentDescription,
            popupContent = PopupContentModel.Alarm(model),
            dismissKey = alarm?.triggerTime to activeNotification?.sbn?.key,
        )
    }

    private fun chipSkeleton(): String = if (DateFormat.is24HourFormat(context)) "Hm" else "hma"

    private fun fullDescriptionSkeleton(): String =
        if (DateFormat.is24HourFormat(context)) "EHm" else "Ehma"

    private fun daySkeleton(): String = "EEEE"

    private fun formatAlarmTime(triggerTimeMs: Long, skeleton: String): String {
        val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton)
        return DateFormat.format(pattern, triggerTimeMs).toString()
    }

    @AssistedFactory
    interface Factory {
        fun create(): AlarmPopupChipViewModel
    }
}

private data class AlarmPopupState(
    val nextAlarm: AlarmManager.AlarmClockInfo?,
    val activeNotification: NotificationEntry?,
)

private fun NotificationEntry.isAlarmCandidate(context: Context): Boolean {
    val notification = sbn.notification
    // Clock timers are not alarms; they show as their own Live Update card.
    val isTimer =
        notification.category != Notification.CATEGORY_ALARM &&
            (notification.channelId.orEmpty().contains("timer", ignoreCase = true) ||
                notification.flags and Notification.FLAG_PROMOTED_ONGOING != 0)
    if (isTimer) return false
    if (
        notification.flags and Notification.FLAG_GROUP_SUMMARY != 0 ||
        notification.contentIntent == null
    ) {
        return false
    }
    // Clock notifications that are actually timers or stopwatches belong to their own chips.
    parseClockNotification(context)?.let {
        return it.kind == ClockNotificationKind.ALARM
    }
    return notification.category == Notification.CATEGORY_ALARM
}

private fun NotificationEntry.notificationTitle(): String? {
    return sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
}

private fun NotificationEntry.notificationText(): String? {
    return sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
}

private fun NotificationEntry.hasSnoozeAction(): Boolean {
    return snoozeAction() != null
}

/**
 * The action that ends the alarm: one marked as a delete, one whose intent dismisses or stops the
 * alarm, or else the only action left once snooze is set aside. Its own title is its label, as the
 * alarm app wrote it.
 */
private fun NotificationEntry.dismissAction(): PopupActionModel? {
    val snooze = snoozeAction()
    val others = sbn.notification.actions.orEmpty().filter { it.actionIntent != snooze }
    val action =
        others.firstOrNull { it.semanticAction == Notification.Action.SEMANTIC_ACTION_DELETE }
            ?: others.firstOrNull { it.actionIntent.intentActionMatches("dismiss", "stop") }
            ?: others.singleOrNull()
    val label = action?.title?.toString()?.takeIf { it.isNotBlank() } ?: return null
    return action.actionIntent.toSendPopupAction(label)
}

/**
 * The action that snoozes the alarm. Notification actions carry no snooze meaning, so it is found
 * by its intent, which does not depend on the language, then by an English title, then as the
 * first of the several actions a ringing alarm has.
 */
private fun NotificationEntry.snoozeAction(): PendingIntent? {
    val actions = sbn.notification.actions.orEmpty()
    return actions.firstOrNull { it.actionIntent.intentActionMatches("snooze") }?.actionIntent
        ?: actions.firstOrNull { action ->
            action.title?.toString()?.contains("snooze", ignoreCase = true) == true
        }?.actionIntent
        ?: actions
            .takeIf { sbn.notification.category == Notification.CATEGORY_ALARM && it.size > 1 }
            ?.firstOrNull()
            ?.actionIntent
}

/** Whether the intent behind this pending intent has an action naming one of [words]. */
private fun PendingIntent?.intentActionMatches(vararg words: String): Boolean {
    // SystemUI holds GET_INTENT_SENDER_INTENT, so it may read the intent of another app's action.
    val action = this?.let { runCatching { it.intent?.action }.getOrNull() } ?: return false
    return words.any { action.contains(it, ignoreCase = true) }
}
