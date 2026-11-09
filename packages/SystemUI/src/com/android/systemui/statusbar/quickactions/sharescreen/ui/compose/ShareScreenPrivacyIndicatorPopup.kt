/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.statusbar.quickactions.sharescreen.ui.compose

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ScreenShare
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.systemui.res.R
import com.android.systemui.statusbar.quickactions.popups.shared.model.PopupActionModel
import com.android.systemui.statusbar.quickactions.sharescreen.ui.viewmodel.ShareScreenPrivacyIndicatorPopupViewModel
import com.android.systemui.statusbar.quickactions.ui.compose.IslandAccents
import com.android.systemui.statusbar.quickactions.ui.compose.IslandGlassCard
import com.android.systemui.statusbar.quickactions.ui.compose.islandGlassCards

@Composable
fun ShareScreenPrivacyIndicatorPopup(
    viewModel: ShareScreenPrivacyIndicatorPopupViewModel,
    modifier: Modifier = Modifier,
) {
    if (islandGlassCards()) {
        val stopLabel = stringResource(R.string.screen_share_privacy_indicator_stop_sharing)
        IslandGlassCard(
            primary = stopLabel,
            secondary = null,
            glyph = {
                Icon(
                    imageVector = Icons.Rounded.ScreenShare,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = IslandAccents.Recording,
                )
            },
            modifier = modifier,
            primaryAction =
                PopupActionModel(
                    label = stopLabel,
                    onClick = { viewModel.stopShare() },
                    vector = Icons.Rounded.Stop,
                ),
            primaryActionColor = Color(0xFFFF5F52),
        )
        return
    }
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceBright,
        shadowElevation = 2.dp,
        modifier = modifier,
    ) {
        Button(
            onClick = { viewModel.stopShare() },
            shape = RoundedCornerShape(20.dp), // Outer radius (28) - border (8)
            modifier = Modifier.padding(8.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
        ) {
            Text(
                text = stringResource(R.string.screen_share_privacy_indicator_stop_sharing),
                softWrap = false,
            )
        }
    }
}
