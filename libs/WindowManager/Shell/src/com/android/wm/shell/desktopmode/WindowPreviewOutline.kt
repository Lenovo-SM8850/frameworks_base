// SPDX-License-Identifier: Apache-2.0

package com.android.wm.shell.desktopmode

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import com.android.wm.shell.R

/** Exact, inputless destination outline shared by floating mini and sidebar drag previews. */
class WindowPreviewOutline(private val view: View) {
    private val target = Rect()
    private val outline = (view.context.getDrawable(
        R.drawable.desktop_windowing_transition_background)!!.mutate() as LayerDrawable).apply {
        findDrawableByLayerId(R.id.indicator_solid).alpha = 0
    }

    init {
        view.alpha = 0f
        view.background = object : Drawable() {
            override fun draw(canvas: Canvas) {
                outline.bounds = this@WindowPreviewOutline.target
                outline.draw(canvas)
            }
            override fun setAlpha(alpha: Int) {
                outline.alpha = alpha
                outline.findDrawableByLayerId(R.id.indicator_solid).alpha = 0
            }
            override fun setColorFilter(filter: ColorFilter?) { outline.colorFilter = filter }
            override fun getOpacity() = PixelFormat.TRANSLUCENT
        }
    }

    fun update(target: Rect?, radius: Float) {
        view.animate().cancel()
        if (target != null) {
            this.target.set(target)
            (outline.findDrawableByLayerId(R.id.indicator_stroke) as GradientDrawable)
                .cornerRadius = radius
            view.invalidate()
        }
        view.animate().alpha(if (target != null) 1f else 0f).setDuration(120).start()
    }

    fun clear() {
        view.animate().cancel()
        view.alpha = 0f
        target.setEmpty()
        view.invalidate()
    }
}
