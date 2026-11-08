// SPDX-License-Identifier: Apache-2.0

package com.android.systemui.statusbar.quickactions.popups.shared

import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

/**
 * Keeps the item of one chip that the user swiped away out of the island until the chip shows a
 * different item. The state is only held in memory. Chips identify their item with
 * [QuickActionChipModel.PopupChip.dismissKey].
 */
class PopupItemDismissal {
    private val dismissedKey = MutableStateFlow<Any?>(null)

    /** Hides the dismissed item and attaches the dismiss action to the chip that shows one. */
    fun filterDismissed(chips: Flow<QuickActionChipModel>): Flow<QuickActionChipModel> =
        chips.combine(dismissedKey) { chip, dismissed ->
            val key = (chip as? QuickActionChipModel.PopupChip)?.dismissKey
            if (dismissed != null && key != dismissed) {
                // The dismissed item is gone or was replaced, so a later return shows again.
                dismissedKey.value = null
            }
            when {
                chip !is QuickActionChipModel.PopupChip || key == null -> chip
                key == dismissed -> QuickActionChipModel.Hidden(chip.chipId)
                else -> chip.copy(onDismissed = { dismissedKey.value = key })
            }
        }
}
