// SPDX-License-Identifier: Apache-2.0
package io.github.miner7222.systemui.island

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.graphics.Region
import android.os.UserHandle
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalView
import com.android.systemui.Dependency
import com.android.systemui.broadcast.BroadcastDispatcher

internal fun islandPopupTouchableBottom(view: View): Int {
    val metrics = view.context.getSystemService(WindowManager::class.java).maximumWindowMetrics
    val insets =
        metrics.windowInsets.getInsets(
            WindowInsets.Type.systemGestures() or WindowInsets.Type.navigationBars()
        )
    val location = IntArray(2)
    view.getLocationOnScreen(location)
    return (metrics.bounds.bottom - insets.bottom - location[1]).coerceIn(0, view.height)
}

@Composable
internal fun IslandPopupSystemDialogs(onDismiss: () -> Unit) {
    val view = LocalView.current
    val dismiss by rememberUpdatedState(onDismiss)
    DisposableEffect(view) {
        val dispatcher = Dependency.get(BroadcastDispatcher::class.java)
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == Intent.ACTION_CLOSE_SYSTEM_DIALOGS) dismiss()
                }
            }
        dispatcher.registerReceiver(
            receiver,
            IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS),
            executor = view.context.mainExecutor,
            user = UserHandle.ALL,
        )
        onDispose { dispatcher.unregisterReceiver(receiver) }
    }
}

internal fun excludeIslandAppHandles(region: Region, view: View, bounds: List<Rect>) {
    val location = IntArray(2)
    view.getLocationOnScreen(location)
    bounds.forEach { handle ->
        val local = Rect(handle)
        local.offset(-location[0], -location[1])
        region.op(local, Region.Op.DIFFERENCE)
    }
}

@Composable
internal fun IslandPopupGestureInsets(appHandleBounds: List<Rect>) {
    val view = LocalView.current
    val handles by rememberUpdatedState(appHandleBounds)
    DisposableEffect(view) {
        val listener =
            ViewTreeObserver.OnComputeInternalInsetsListener { info ->
                // Home starts below the popup even when the window covers the whole screen.
                info.setTouchableInsets(ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION)
                info.touchableRegion.set(0, 0, view.width, islandPopupTouchableBottom(view))
                excludeIslandAppHandles(info.touchableRegion, view, handles)
            }
        view.viewTreeObserver.addOnComputeInternalInsetsListener(listener)
        onDispose { view.viewTreeObserver.removeOnComputeInternalInsetsListener(listener) }
    }
}
