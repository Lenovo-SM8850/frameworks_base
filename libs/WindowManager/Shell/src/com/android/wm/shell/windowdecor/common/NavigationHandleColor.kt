// SPDX-License-Identifier: Apache-2.0
package com.android.wm.shell.windowdecor.common

import android.content.Context
import com.android.wm.shell.R

/** Resolves the navigation handle's overlaid color in the SystemUI resource context. */
object NavigationHandleColor {
    fun get(context: Context, light: Boolean): Int {
        val name = if (light) "navigation_bar_home_handle_light_color" else
            "navigation_bar_home_handle_dark_color"
        val id = context.resources.getIdentifier(name, "color", context.packageName)
        return context.getColor(if (id != 0) id else if (light)
            R.color.desktop_mode_caption_handle_bar_light else R.color.desktop_mode_caption_handle_bar_dark)
    }
}
