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

package com.android.systemui.statusbar.quickactions.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import com.android.systemui.statusbar.quickactions.popups.shared.model.PopupActionModel

/** Closes the popup that hosts the composable. */
val LocalHidePopup = compositionLocalOf<() -> Unit> { {} }

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
