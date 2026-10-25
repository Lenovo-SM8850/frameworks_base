// SPDX-License-Identifier: Apache-2.0

package com.android.systemui.qs.tiles

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.Tile
import android.view.Display.DEFAULT_DISPLAY
import android.widget.Switch
import com.android.internal.logging.MetricsLogger
import com.android.internal.logging.nano.MetricsProto.MetricsEvent
import com.android.systemui.animation.Expandable
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.qs.QSTile.BooleanState
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.res.R
import com.android.wm.shell.desktopmode.api.DesktopMode
import com.android.wm.shell.desktopmode.data.DesktopRepository.DeskChangeListener
import java.util.Optional
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlin.jvm.optionals.getOrNull

class DesktopModeTile @Inject constructor(
    host: QSHost,
    uiEventLogger: QsEventLogger,
    @Background backgroundLooper: Looper,
    @Main mainHandler: Handler,
    falsingManager: FalsingManager,
    metricsLogger: MetricsLogger,
    statusBarStateController: StatusBarStateController,
    activityStarter: ActivityStarter,
    qsLogger: QSLogger,
    private val desktopMode: Optional<DesktopMode>,
) : QSTileImpl<BooleanState>(
    host,
    uiEventLogger,
    backgroundLooper,
    mainHandler,
    falsingManager,
    metricsLogger,
    statusBarStateController,
    activityStarter,
    qsLogger,
) {
    private val callbackExecutor = Executor { mHandler.post(it) }
    private val deskListener = object : DeskChangeListener {
        override fun onDeskAdded(displayId: Int, deskId: Int) = updateDisplay(displayId)

        override fun onDeskRemoved(displayId: Int, deskId: Int) = updateDisplay(displayId)

        override fun onActiveDeskChanged(
            displayId: Int,
            newActiveDeskId: Int,
            oldActiveDeskId: Int,
        ) = updateDisplay(displayId)

        override fun onCanCreateDesksChanged(canCreateDesks: Boolean) {}

        override fun onTaskAppearingInDesk(taskId: Int, displayId: Int, deskId: Int) {}
    }

    private fun updateDisplay(displayId: Int) {
        if (displayId == DEFAULT_DISPLAY) refreshState()
    }

    override fun newTileState() = BooleanState()

    override fun isAvailable() =
        desktopMode.getOrNull()?.isDesktopModeSupportedOnDisplay(DEFAULT_DISPLAY) == true

    override fun getTileLabel(): CharSequence =
        mContext.getString(R.string.quick_settings_desktop_mode_label)

    override fun getLongClickIntent() = Intent(Settings.ACTION_DISPLAY_SETTINGS)

    override fun getMetricsCategory() = MetricsEvent.QS_PANEL

    override fun handleInitialize() {
        desktopMode.getOrNull()?.addDeskChangeListener(deskListener, callbackExecutor)
        refreshState()
    }

    override fun handleSetListening(listening: Boolean) {
        super.handleSetListening(listening)
        if (listening) refreshState()
    }

    override fun handleUserSwitch(newUserId: Int) {
        desktopMode.getOrNull()?.let {
            it.removeDeskChangeListener(deskListener)
            it.addDeskChangeListener(deskListener, callbackExecutor)
        }
        refreshState()
    }

    override fun handleClick(expandable: Expandable?) {
        if (!isAvailable()) return
        mActivityStarter.postQSRunnableDismissingKeyguard {
            desktopMode.getOrNull()?.toggleDesktopMode(DEFAULT_DISPLAY)
        }
    }

    override fun handleUpdateState(state: BooleanState, arg: Any?) {
        state.label = getTileLabel()
        state.contentDescription = state.label
        state.icon = ResourceIcon.get(R.drawable.ic_qs_desktop_mode)
        state.value = desktopMode.getOrNull()?.isDisplayInDesktopMode(DEFAULT_DISPLAY) == true
        state.state = when {
            !isAvailable() -> Tile.STATE_UNAVAILABLE
            state.value -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        state.expandedAccessibilityClassName = Switch::class.java.name
    }

    override fun handleDestroy() {
        desktopMode.getOrNull()?.removeDeskChangeListener(deskListener)
        super.handleDestroy()
    }

    companion object {
        const val TILE_SPEC = "desktop_mode"
    }
}
