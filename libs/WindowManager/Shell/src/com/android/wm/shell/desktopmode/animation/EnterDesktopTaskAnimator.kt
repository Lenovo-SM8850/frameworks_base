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
package com.android.wm.shell.desktopmode.animation

import android.animation.RectEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Rect
import android.view.SurfaceControl
import android.window.TransitionInfo
import androidx.core.animation.addListener
import com.android.wm.shell.shared.R
import com.android.wm.shell.transition.Transitions
import com.android.wm.shell.windowdecor.OnTaskResizeAnimationListener

/**
 * A animator util to animate a task moving to desktop that interpolates between the start and end
 * bounds.
 */
class EnterDesktopTaskAnimator(
    context: Context,
    private val transactionSupplier: () -> SurfaceControl.Transaction,
    private val onTaskResizeAnimationListener: OnTaskResizeAnimationListener?,
) {
    private val rectEvaluator = RectEvaluator()
    private val animationDuration =
        context.resources.getInteger(R.integer.to_desktop_animation_duration_ms)

    /** Starts the animation of [change]. */
    fun animate(
        change: TransitionInfo.Change,
        startTransaction: SurfaceControl.Transaction,
        finishTransaction: SurfaceControl.Transaction,
        finishCallback: Transitions.TransitionFinishCallback,
        onAnimationEnd: (() -> Unit)? = null,
    ) {
        val taskId = change.taskInfo?.taskId ?: error("Expected non-null taskId")
        val leash = change.leash
        val floatingStart = change.startFloatingWindowBounds.takeUnless { it.isEmpty }
        val startBounds = floatingStart ?: change.startAbsBounds
        val endBounds = change.endAbsBounds
        val endOffset = change.endRelOffset
        val veilBounds = Rect()
        fun listenerBounds(visible: Rect): Rect {
            if (floatingStart == null) return visible
            // The veil writes the parent crop, so keep it in unscaled task coordinates.
            veilBounds.set(visible.left, visible.top,
                visible.left + endBounds.width(), visible.top + endBounds.height())
            return veilBounds
        }

        startTransaction
            .setPosition(leash, startBounds.left.toFloat(), startBounds.top.toFloat())
            .setWindowCrop(leash, startBounds.width(), startBounds.height())
        if (floatingStart != null) {
            startTransaction.setMatrix(leash, startBounds.width().toFloat() / endBounds.width(),
                0f, 0f, startBounds.height().toFloat() / endBounds.height())
                .setWindowCrop(leash, endBounds.width(), endBounds.height())
        }
        val startTransactionApplied =
            onTaskResizeAnimationListener?.onAnimationStart(
                taskId = taskId,
                t = startTransaction,
                bounds = listenerBounds(startBounds),
            ) ?: false
        if (!startTransactionApplied) {
            startTransaction.apply()
        }

        val updateTransaction = transactionSupplier()
        ValueAnimator.ofObject(rectEvaluator, startBounds, endBounds).apply {
            duration = animationDuration.toLong()
            if (floatingStart != null) {
                duration = 300
                interpolator = com.android.wm.shell.shared.animation.Interpolators.FAST_OUT_SLOW_IN
            }
            addListener(
                onEnd = {
                    if (floatingStart != null) {
                        finishTransaction.setMatrix(leash, 1f, 0f, 0f, 1f)
                    }
                    finishTransaction
                        .setPosition(leash, endOffset.x.toFloat(), endOffset.y.toFloat())
                        .setWindowCrop(leash, endBounds.width(), endBounds.height())
                        .apply()
                    onTaskResizeAnimationListener?.onAnimationEnd(taskId)
                    finishCallback.onTransitionFinished(/* wct= */ null)
                    onAnimationEnd?.invoke()
                }
            )
            addUpdateListener { animation ->
                val rect = animation.animatedValue as Rect
                updateTransaction
                    .setPosition(leash, rect.left.toFloat(), rect.top.toFloat())
                    .setWindowCrop(leash, rect.width(), rect.height())
                if (floatingStart != null) {
                    updateTransaction.setMatrix(leash, rect.width().toFloat() / endBounds.width(),
                        0f, 0f, rect.height().toFloat() / endBounds.height())
                        .setWindowCrop(leash, endBounds.width(), endBounds.height())
                }
                val updateTransactionApplied =
                    onTaskResizeAnimationListener?.onBoundsChange(
                        taskId = taskId,
                        t = updateTransaction,
                        bounds = listenerBounds(rect),
                    ) ?: false
                if (!updateTransactionApplied) {
                    updateTransaction.apply()
                }
            }
            start()
        }
    }
}
