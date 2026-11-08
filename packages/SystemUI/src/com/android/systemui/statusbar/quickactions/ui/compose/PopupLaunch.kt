// SPDX-License-Identifier: Apache-2.0

package com.android.systemui.statusbar.quickactions.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import com.android.systemui.statusbar.quickactions.popups.shared.model.PopupActionModel

/** Closes the popup that hosts the composable. */
val LocalHidePopup = compositionLocalOf<() -> Unit> { {} }

/** Whether popup text may start scrolling after the container settles. */
val LocalPopupMarqueeEnabled = compositionLocalOf { true }

/** Runs an accepted launch only after its popup has finished collapsing. */
val LocalLaunchAfterPopupHidden = compositionLocalOf<(() -> Unit) -> Unit> { { it() } }

/** Wraps [action], which opens an app, so that the popup closes together with it. */
@Composable
fun rememberPopupLaunchAction(action: (() -> Unit)?): (() -> Unit)? {
    val hidePopup = LocalHidePopup.current
    return remember(action, hidePopup) {
        action?.let { open ->
            {
                open()
                hidePopup()
            }
        }
    }
}

/** Makes the [actions] that open an app close the popup, and leaves the others as they are. */
@Composable
fun rememberPopupActions(actions: List<PopupActionModel>): List<PopupActionModel> {
    val hidePopup = LocalHidePopup.current
    return remember(actions, hidePopup) {
        actions.map { action ->
            if (action.launchesApp) {
                action.copy(
                    onClick = {
                        action.onClick()
                        hidePopup()
                    }
                )
            } else {
                action
            }
        }
    }
}
