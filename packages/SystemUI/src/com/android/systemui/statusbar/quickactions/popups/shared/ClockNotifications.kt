// SPDX-License-Identifier: Apache-2.0

package com.android.systemui.statusbar.quickactions.popups.shared

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop

import android.app.Notification
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.TextView
import com.android.systemui.res.R
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.quickactions.popups.shared.model.PopupActionModel
import java.util.Locale
import kotlin.math.abs

/** Kind of clock notification surfaced by the dynamic island. */
enum class ClockNotificationKind {
    TIMER,
    STOPWATCH,
    ALARM,
}

/**
 * A MetricStyle time difference (see [Notification.Metric.TimeDifference]) parsed from the
 * notification extras. Timers count down to their zero point; stopwatches count up from it.
 */
data class ClockMetricTime(
    val label: String?,
    val isCountDown: Boolean,
    val zeroTimeMs: Long?,
    val zeroElapsedRealtimeMs: Long?,
    val pausedDurationMs: Long?,
    val format: Int,
) {
    val isRunning: Boolean
        get() = pausedDurationMs == null

    /** Remaining time for timers or elapsed time for stopwatches, in milliseconds. */
    fun timeMs(nowElapsedRealtimeMs: Long, nowEpochMs: Long): Long? {
        pausedDurationMs?.let {
            return it
        }
        zeroElapsedRealtimeMs?.let { zero ->
            val difference =
                if (isCountDown) zero - nowElapsedRealtimeMs
                else nowElapsedRealtimeMs - zero
            return if (isCountDown) difference else difference.coerceAtLeast(0L)
        }
        zeroTimeMs?.let { zero ->
            val difference =
                if (isCountDown) zero - nowEpochMs else nowEpochMs - zero
            return if (isCountDown) difference else difference.coerceAtLeast(0L)
        }
        return null
    }
}

/** Chronometer state built from the notification extras or its RemoteViews. */
data class ClockChronometerSnapshot(
    val baseElapsedRealtimeMs: Long,
    val isCountDown: Boolean,
    val text: String?,
)

/** Classification and timing state of a clock notification, as used by the dynamic island. */
data class ClockNotificationInfo(
    val kind: ClockNotificationKind,
    val label: String?,
    val isRunning: Boolean,
    val metric: ClockMetricTime?,
    val chronometer: ClockChronometerSnapshot?,
    /** A time shown as plain text, by clocks that drop the chronometer while paused. */
    val staticTimeText: String? = null,
) {
    /** Fixed time text published by clocks while paused, from a chronometer or plain text. */
    val pausedTimeText: String?
        get() = chronometer?.text?.takeUnless { it.isBlank() } ?: staticTimeText
}

/** Clock packages handled like the legacy dynamic bar; other packages still match by hints. */
private val CLOCK_PACKAGES = setOf("com.google.android.deskclock", "com.android.deskclock")

/** DeskClock action intents, copied from the legacy dynamic bar classification. */
object DeskClockActions {
    private const val PREFIX = "com.android.deskclock.action."
    const val START_TIMER = PREFIX + "START_TIMER"
    const val PAUSE_TIMER = PREFIX + "PAUSE_TIMER"
    const val RESET_TIMER = PREFIX + "RESET_TIMER"
    const val ADD_MINUTE_TIMER = PREFIX + "ADD_MINUTE_TIMER"
    const val SHOW_TIMER = PREFIX + "SHOW_TIMER"
    const val START_STOPWATCH = PREFIX + "START_STOPWATCH"
    const val PAUSE_STOPWATCH = PREFIX + "PAUSE_STOPWATCH"
    const val RESET_STOPWATCH = PREFIX + "RESET_STOPWATCH"
    const val LAP_STOPWATCH = PREFIX + "LAP_STOPWATCH"
    const val SHOW_STOPWATCH = PREFIX + "SHOW_STOPWATCH"
    const val ALARM_SNOOZE = "com.android.deskclock.ALARM_SNOOZE"
    const val ALARM_DISMISS = "com.android.deskclock.ALARM_DISMISS"
}

private val STOPWATCH_ACTIONS =
    setOf(
        DeskClockActions.START_STOPWATCH,
        DeskClockActions.PAUSE_STOPWATCH,
        DeskClockActions.RESET_STOPWATCH,
        DeskClockActions.LAP_STOPWATCH,
        DeskClockActions.SHOW_STOPWATCH,
    )

private val TIMER_ACTIONS =
    setOf(
        DeskClockActions.START_TIMER,
        DeskClockActions.PAUSE_TIMER,
        DeskClockActions.RESET_TIMER,
        DeskClockActions.ADD_MINUTE_TIMER,
        DeskClockActions.SHOW_TIMER,
    )

private const val METRIC_TYPE_TIME_DIFFERENCE = 1
private const val METRIC_FORMAT_CHRONOMETER = 2

/** Classifies [this] as a timer, stopwatch or alarm notification, or returns null. */
fun NotificationEntry.parseClockNotification(context: Context): ClockNotificationInfo? {
    val notification = sbn.notification
    // Group summaries share the timer channel but carry no time, so only their children count.
    if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) {
        return null
    }
    val packageName = sbn.packageName.orEmpty()
    val isClockPackage =
        packageName in CLOCK_PACKAGES || packageName.contains("clock", ignoreCase = true)
    val isAlarmCategory = notification.category == Notification.CATEGORY_ALARM
    if (!isClockPackage && !isAlarmCategory) {
        return null
    }

    val extras = notification.extras
    val channelId = notification.channelId?.lowercase(Locale.ROOT).orEmpty()
    val actions = notification.actions.orEmpty().toList()
    val actionLabels = actions.map { it.title?.toString()?.lowercase(Locale.ROOT).orEmpty() }
    val actionIntents = actions.mapNotNull { actionIntentString(it) }
    val metric = extractClockMetric(notification)
    val chronometer = extractChronometerSnapshot(context)
    // A paused stopwatch or timer of the Clock app shows its time as plain text instead.
    val staticTimeText =
        if (metric == null && chronometer == null) extractStaticTimeText(context) else null

    val hasStopwatchIntent = actionIntents.any { it in STOPWATCH_ACTIONS }
    val hasTimerIntent = actionIntents.any { it in TIMER_ACTIONS }
    val hasPauseStopwatchIntent = actionIntents.any { it == DeskClockActions.PAUSE_STOPWATCH }
    val hasStartStopwatchIntent = actionIntents.any { it == DeskClockActions.START_STOPWATCH }
    val hasLapIntent = actionIntents.any { it == DeskClockActions.LAP_STOPWATCH }
    val hasPauseTimerIntent = actionIntents.any { it == DeskClockActions.PAUSE_TIMER }
    val hasStartTimerIntent = actionIntents.any { it == DeskClockActions.START_TIMER }
    val hasLapLabel = actionLabels.any { it.contains("lap") }
    val hasChronometerCountDown =
        extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN, false)
    val isCountDown =
        chronometer?.isCountDown == true || hasChronometerCountDown || metric?.isCountDown == true

    val isStopwatch =
        hasStopwatchIntent ||
            channelId.contains("stopwatch") ||
            hasLapLabel ||
            notification.category == Notification.CATEGORY_STOPWATCH ||
            chronometer?.isCountDown == false ||
            metric?.isCountDown == false
    val isTimer =
        !isStopwatch &&
            (hasTimerIntent ||
                channelId.contains("timer") ||
                channelId.contains("firing") ||
                isCountDown ||
                actionLabels.any { it.contains("+1") || it.contains("add") } ||
                metric?.isCountDown == true)

    val kind =
        when {
            isStopwatch -> ClockNotificationKind.STOPWATCH
            isTimer -> ClockNotificationKind.TIMER
            isAlarmCategory ||
                actionIntents.any {
                    it == DeskClockActions.ALARM_SNOOZE || it == DeskClockActions.ALARM_DISMISS
                } ||
                channelId.contains("alarm") -> ClockNotificationKind.ALARM
            else -> return null
        }

    val label =
        metric?.label?.takeUnless { it.isBlank() }
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeUnless {
                it.isBlank()
            }

    val isRunning =
        when {
            metric != null -> metric.isRunning
            kind == ClockNotificationKind.STOPWATCH ->
                when {
                    hasPauseStopwatchIntent || hasLapIntent -> true
                    hasStartStopwatchIntent -> false
                    else ->
                        actionLabels.any { it.contains("pause") || it.contains("lap") } ||
                            notification.flags and Notification.FLAG_ONGOING_EVENT != 0
                }
            kind == ClockNotificationKind.TIMER ->
                when {
                    hasPauseTimerIntent -> true
                    hasStartTimerIntent -> false
                    else ->
                        actionLabels.any { it.contains("pause") } ||
                            notification.flags and Notification.FLAG_ONGOING_EVENT != 0
                }
            else -> notification.flags and Notification.FLAG_ONGOING_EVENT != 0
        }

    return ClockNotificationInfo(
        kind = kind,
        label = label,
        isRunning = isRunning,
        metric = metric,
        chronometer = chronometer,
        staticTimeText = staticTimeText,
    )
}

/**
 * Maps the notification actions to popup buttons using the stopwatch mapping: the first action
 * pauses or resumes, the second is the extra action.
 */
fun Notification.toClockPopupActions(
    context: Context,
    isRunning: Boolean,
    extraLabelRes: Int,
    preferNotificationLabel: Boolean = false,
    vectorActions: Boolean = false,
): List<PopupActionModel> {
    val actions = actions.orEmpty()
    if (actions.isEmpty()) {
        return emptyList()
    }

    val firstLabel =
        (if (preferNotificationLabel) {
                actions.first().title?.toString()?.takeUnless { it.isBlank() }
            } else {
                null
            })
            ?: context.getString(
                if (isRunning) R.string.controls_media_button_pause
                else R.string.dynamic_island_action_resume
            )
    val extraAction = actions.getOrNull(1)
    val extraLabel =
        (if (preferNotificationLabel) {
                extraAction?.title?.toString()?.takeUnless { it.isBlank() }
            } else {
                null
            })
            ?: context.getString(
                if (isRunning) extraLabelRes else R.string.dynamic_island_action_stop
            )

    return listOfNotNull(
        actions.getOrNull(0)?.actionIntent.toSendPopupAction(firstLabel, vector = if (!vectorActions) null else if (isRunning) Icons.Rounded.Pause else Icons.Rounded.PlayArrow),
        extraAction?.actionIntent.toSendPopupAction(extraLabel, emphasized = !isRunning, vector = if (!vectorActions) null else if (isRunning) Icons.Rounded.Flag else Icons.Rounded.Stop),
    )
}

/** Formats [milliseconds] as mm:ss or h:mm:ss, matching the island timers. */
fun formatClockDuration(milliseconds: Long): String {
    val totalSeconds = abs(Math.floorDiv(milliseconds, 1000L))
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    val duration = if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
    return if (milliseconds < 0L) "−$duration" else duration
}

private fun actionIntentString(action: Notification.Action): String? {
    val pendingIntent = action.actionIntent ?: return null
    return runCatching { pendingIntent.intent?.action }.getOrNull()
}

private fun extractClockMetric(notification: Notification): ClockMetricTime? {
    val extras = notification.extras
    @Suppress("DEPRECATION")
    val metrics =
        extras.getParcelableArrayList<Bundle>(KEY_METRICS)?.filterNotNull() ?: return null
    if (metrics.isEmpty()) {
        return null
    }

    val criticalIndex = extras.getInt(KEY_METRICS_CRITICAL_INDEX, 0).coerceAtLeast(0)
    val ordered =
        if (criticalIndex in metrics.indices) {
            listOf(metrics[criticalIndex]) + metrics.filterIndexed { index, _ ->
                index != criticalIndex
            }
        } else {
            metrics
        }

    for (metricBundle in ordered) {
        val value = metricBundle.getBundle(KEY_VALUE) ?: continue
        if (value.getInt(KEY_TYPE, -1) != METRIC_TYPE_TIME_DIFFERENCE) {
            continue
        }
        val format = value.getInt(KEY_FORMAT, METRIC_FORMAT_CHRONOMETER)
        val zeroTime = value.longOrNull(KEY_ZERO_TIME)
        val zeroElapsedRealtime = value.longOrNull(KEY_ZERO_ELAPSED_REALTIME)
        val pausedDuration = value.longOrNull(KEY_PAUSED_DURATION)
        if (zeroTime == null && zeroElapsedRealtime == null && pausedDuration == null) {
            continue
        }
        return ClockMetricTime(
            label = metricBundle.getString(KEY_LABEL),
            isCountDown = value.getBoolean(KEY_COUNT_DOWN, false),
            zeroTimeMs = zeroTime,
            zeroElapsedRealtimeMs = zeroElapsedRealtime,
            pausedDurationMs = pausedDuration,
            format = format,
        )
    }
    return null
}

private fun Bundle.longOrNull(key: String): Long? =
    if (containsKey(key)) getLong(key) else null

private fun NotificationEntry.extractChronometerSnapshot(
    context: Context
): ClockChronometerSnapshot? {
    val notification = sbn.notification
    val packageContext =
        runCatching {
                context.createPackageContext(sbn.packageName, Context.CONTEXT_RESTRICTED)
            }
            .getOrDefault(context)

    val fromRemoteViews =
        sequenceOf(
                notification.contentView,
                notification.bigContentView,
                notification.headsUpContentView,
            )
            .filterNotNull()
            .mapNotNull { remoteViews ->
                val rootView =
                    runCatching {
                            remoteViews.apply(packageContext, FrameLayout(packageContext))
                        }
                        .getOrNull()
                        ?: return@mapNotNull null
                val chronometer = rootView.findChronometer() ?: return@mapNotNull null
                ClockChronometerSnapshot(
                    baseElapsedRealtimeMs = chronometer.base,
                    isCountDown = chronometer.isCountDown,
                    text = chronometer.text?.toString().orEmpty(),
                )
            }
            .firstOrNull()

    if (fromRemoteViews != null) {
        return fromRemoteViews
    }

    val extras = notification.extras
    val showsChronometer = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false)
    val isStopwatchCategory = Notification.CATEGORY_STOPWATCH.equals(notification.category)
    val isCountDown = extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN, false)
    if ((showsChronometer || isStopwatchCategory) && !isCountDown) {
        val whenTime = if (notification.getWhen() != 0L) notification.getWhen() else sbn.postTime
        val baseElapsedRealtimeMs =
            whenTime + (SystemClock.elapsedRealtime() - System.currentTimeMillis())
        return ClockChronometerSnapshot(
            baseElapsedRealtimeMs = baseElapsedRealtimeMs,
            isCountDown = false,
            text = "",
        )
    }

    return null
}

/** A clock time like 0:53, 00:53.05 or 1:02:03, the whole text of a view. */
private val CLOCK_TIME_TEXT = Regex("""^\d{1,2}(:\d{2}){1,2}([.,]\d{1,3})?$""")

/** The first text of the notification's views that is only a clock time. */
private fun NotificationEntry.extractStaticTimeText(context: Context): String? {
    val notification = sbn.notification
    val packageContext =
        runCatching {
                context.createPackageContext(sbn.packageName, Context.CONTEXT_RESTRICTED)
            }
            .getOrDefault(context)
    return sequenceOf(
            notification.contentView,
            notification.bigContentView,
            notification.headsUpContentView,
        )
        .filterNotNull()
        .mapNotNull { remoteViews ->
            runCatching { remoteViews.apply(packageContext, FrameLayout(packageContext)) }
                .getOrNull()
                ?.findClockTimeText()
        }
        .firstOrNull()
}

private fun View.findClockTimeText(): String? {
    if (this is TextView && this !is Chronometer) {
        val text = text?.toString()?.trim().orEmpty()
        if (CLOCK_TIME_TEXT.matches(text)) return text
    }
    if (this is ViewGroup) {
        for (index in 0 until childCount) {
            getChildAt(index).findClockTimeText()?.let {
                return it
            }
        }
    }
    return null
}

private fun View.findChronometer(): Chronometer? {
    if (this is Chronometer) {
        return this
    }
    if (this is ViewGroup) {
        for (index in 0 until childCount) {
            getChildAt(index).findChronometer()?.let {
                return it
            }
        }
    }
    return null
}

// Framework-private extras (Notification.EXTRA_METRICS and its critical-index sibling).
private const val KEY_METRICS = "android.metrics"
private const val KEY_METRICS_CRITICAL_INDEX = "android.metrics.criticalIndex"
private const val KEY_VALUE = "value"
private const val KEY_LABEL = "label"
private const val KEY_TYPE = "_type"
private const val KEY_ZERO_TIME = "zeroTime"
private const val KEY_ZERO_ELAPSED_REALTIME = "zeroElapsedRealtime"
private const val KEY_PAUSED_DURATION = "pausedDuration"
private const val KEY_COUNT_DOWN = "countDown"
private const val KEY_FORMAT = "format"
