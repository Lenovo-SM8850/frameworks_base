// SPDX-License-Identifier: Apache-2.0
package com.android.wm.shell.desktopmode

import com.android.wm.shell.pinnedlayer.phone.FloatingMiniState.Corner

/** Drop zones for an app handle dragged out of a fullscreen or split task, by pointer position. */
object HandleDropZones {
    enum class Zone { FLOATING, FULLSCREEN, SPLIT_LEFT, SPLIT_RIGHT, MINI }

    /** [corner] is set only for [Zone.MINI]. */
    data class Target(val zone: Zone, val corner: Corner? = null)

    /** Zone sizes in px: [corner] is the side of each corner square, [splitEdge] the side bands. */
    data class Sizes(val corner: Int, val splitEdge: Int)

    /**
     * Corner squares win over the side bands and the top band. The side bands cover only the
     * height between the corners, and the top band only the width between them. Without minis
     * the side bands and the top band span the whole display, as before.
     */
    @JvmStatic
    fun pick(x: Float, y: Float, width: Int, height: Int, top: Int, sizes: Sizes,
        allowSplit: Boolean, allowMini: Boolean): Target {
        val c = sizes.corner
        val left = x < c
        val right = x >= width - c
        if (allowMini && (left || right) && (y < c || y >= height - c)) {
            val upper = y < c
            return Target(Zone.MINI, when {
                left && upper -> Corner.TOP_LEFT
                upper -> Corner.TOP_RIGHT
                left -> Corner.BOTTOM_LEFT
                else -> Corner.BOTTOM_RIGHT
            })
        }
        if (allowSplit) {
            if (x < sizes.splitEdge) return Target(Zone.SPLIT_LEFT)
            if (x >= width - sizes.splitEdge) return Target(Zone.SPLIT_RIGHT)
        }
        if (y < top && (!allowMini || (!left && !right))) return Target(Zone.FULLSCREEN)
        return Target(Zone.FLOATING)
    }
}
