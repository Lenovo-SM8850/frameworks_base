/*
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.miner7222.systemui.qs

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State

/** Yellow of the automatic brightness icon on the slider. */
internal val ColorOSBrightnessAuto = Color(0xFFFFC107)

/** Whether automatic brightness is on, kept up to date while the setting changes. */
@Composable
internal fun rememberColorOSAutoBrightness(): State<Boolean> {
    val context = LocalContext.current
    fun read() =
        Settings.System.getIntForUser(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            UserHandle.USER_CURRENT,
        ) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
    val state = remember(context) { mutableStateOf(read()) }
    DisposableEffect(context) {
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    state.value = read()
                }
            }
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE),
            false,
            observer,
            UserHandle.USER_ALL,
        )
        state.value = read()
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return state
}

internal fun setColorOSAutoBrightness(context: Context, automatic: Boolean) {
    Settings.System.putIntForUser(
        context.contentResolver,
        Settings.System.SCREEN_BRIGHTNESS_MODE,
        if (automatic) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
        UserHandle.USER_CURRENT,
    )
}
