/*
 Copyright (C) 2026 The Evolution X Project
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

package io.github.miner7222.systemui.qs

import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/** Whether a carrier group shows any text at all; the group itself stays laid out when empty. */
internal fun hasVisibleCarrierText(view: View): Boolean =
    when {
        view.visibility != View.VISIBLE -> false
        view is TextView -> !view.text.isNullOrBlank()
        view is ViewGroup ->
            (0 until view.childCount).any { hasVisibleCarrierText(view.getChildAt(it)) }
        else -> false
    }

/**
 * Start padding that keeps the carrier clear of the date in the one-row ColorOS header: the date
 * start margin when both are visible on a large screen, otherwise none.
 */
internal fun carrierTextGapPx(
    colorOSStyle: Boolean,
    largeScreenActive: Boolean,
    dateVisible: Boolean,
    carrierVisible: Boolean,
    dateStartMarginPx: Int,
): Int =
    if (colorOSStyle && largeScreenActive && dateVisible && carrierVisible) {
        dateStartMarginPx.coerceAtLeast(0)
    } else {
        0
    }
