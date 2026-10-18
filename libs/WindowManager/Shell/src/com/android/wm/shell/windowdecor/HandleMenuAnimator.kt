/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.wm.shell.windowdecor

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.view.View
import android.view.View.ALPHA
import android.view.View.SCALE_X
import android.view.View.SCALE_Y
import android.view.View.TRANSLATION_Y
import android.view.View.TRANSLATION_Z
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import androidx.core.animation.doOnEnd
import androidx.core.view.children
import com.android.wm.shell.R
import com.android.wm.shell.shared.animation.Interpolators

/** Animates the Handle Menu's action pills without an app info header. */
class HandleMenuAnimator(
    private val context: Context,
    private val handleMenu: View,
    private val menuWidth: Int,
    private val captionHeight: Float,
) {
    companion object {
        private const val BODY_SCALE_OPEN_DURATION: Long = 180
        private const val BODY_ALPHA_OPEN_DURATION: Long = 150
        private const val BODY_ELEVATION_OPEN_DURATION: Long = 83
        private const val BODY_CONTENT_ALPHA_OPEN_DURATION: Long = 167
        private const val ELEVATION_OPEN_DELAY: Long = 33
        private const val BODY_SCALE_OPEN_DELAY: Long = 50
        private const val BODY_ALPHA_OPEN_DELAY: Long = 133
        private const val HALF_INITIAL_SCALE: Float = 0.5f
        private const val BODY_CLOSE_DURATION: Long = 50
    }

    private val animators: MutableList<Animator> = mutableListOf()
    private var runningAnimation: AnimatorSet? = null
    private val windowingPill: ViewGroup = handleMenu.requireViewById(R.id.windowing_pill)
    private val moreActionsPill: ViewGroup = handleMenu.requireViewById(R.id.more_actions_pill)
    private val openInAppOrBrowserPill: ViewGroup =
        handleMenu.requireViewById(R.id.open_in_app_or_browser_pill)

    fun animateCaptionHeaderExpandToOpen(headerView: View) = animateOpenMenu()

    fun animateCaptionHandleExpandToOpen(handleView: View) = animateOpenMenu()

    fun animateCollapseIntoHandleClose(handleView: View, after: () -> Unit) =
        animateCloseMenu(after)

    fun animateCollapseIntoHeaderClose(headerView: View, after: () -> Unit) =
        animateCloseMenu(after)

    private fun animateOpenMenu() {
        prepareMenuForAnimation()
        animateWindowingPillOpen()
        animateMoreActionsPillOpen()
        animateOpenInAppOrBrowserPill()
        val animationCallback: () -> Unit = {
            handleMenu.post {
                val actions = ArrayList<View>()
                handleMenu.addFocusables(actions, View.FOCUS_FORWARD)
                actions.firstOrNull()?.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED)
            }
        }
        if (!handleMenu.isAttachedToWindow) {
            handleMenu.addOnAttachStateChangeListener(
                object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        runAnimations(animationCallback)
                        handleMenu.removeOnAttachStateChangeListener(this)
                    }

                    override fun onViewDetachedFromWindow(v: View) {}
                }
            )
        } else {
            runAnimations(animationCallback)
        }
    }

    private fun animateCloseMenu(after: () -> Unit) {
        windowingPillClose()
        moreActionsPillClose()
        openInAppOrBrowserPillClose()
        runAnimations(after)
    }

    private fun prepareMenuForAnimation() {
        // There is no app info row above the actions to use as their animation pivot.
        listOf(windowingPill, moreActionsPill, openInAppOrBrowserPill).forEach {
            it.alpha = 0f
            it.pivotX = menuWidth / 2f
            it.pivotY = 0f
        }
        handleMenu.pivotX = menuWidth / 2f
        handleMenu.pivotY = 0f
    }

    private fun animateWindowingPillOpen() {
        // Windowing X & Y Scaling Animation
        animators +=
            ObjectAnimator.ofFloat(windowingPill, SCALE_X, HALF_INITIAL_SCALE, 1f).apply {
                startDelay = BODY_SCALE_OPEN_DELAY
                duration = BODY_SCALE_OPEN_DURATION
            }

        animators +=
            ObjectAnimator.ofFloat(windowingPill, SCALE_Y, HALF_INITIAL_SCALE, 1f).apply {
                startDelay = BODY_SCALE_OPEN_DELAY
                duration = BODY_SCALE_OPEN_DURATION
            }

        // Windowing Opacity Animation
        animators +=
            ObjectAnimator.ofFloat(windowingPill, ALPHA, 1f).apply {
                startDelay = BODY_ALPHA_OPEN_DELAY
                duration = BODY_ALPHA_OPEN_DURATION
            }

        // Windowing Elevation Animation
        animators +=
            ObjectAnimator.ofFloat(windowingPill, TRANSLATION_Z, 1f).apply {
                startDelay = ELEVATION_OPEN_DELAY
                duration = BODY_ELEVATION_OPEN_DURATION
            }

        // Windowing Content Opacity Animation
        windowingPill.children.forEach {
            animators +=
                ObjectAnimator.ofFloat(it, ALPHA, 1f).apply {
                    startDelay = BODY_ALPHA_OPEN_DELAY
                    duration = BODY_CONTENT_ALPHA_OPEN_DURATION
                    interpolator = Interpolators.FAST_OUT_SLOW_IN
                }
        }
    }

    private fun animateMoreActionsPillOpen() {
        // More Actions X & Y Scaling Animation
        animators +=
            ObjectAnimator.ofFloat(moreActionsPill, SCALE_X, HALF_INITIAL_SCALE, 1f).apply {
                startDelay = BODY_SCALE_OPEN_DELAY
                duration = BODY_SCALE_OPEN_DURATION
            }

        animators +=
            ObjectAnimator.ofFloat(moreActionsPill, SCALE_Y, HALF_INITIAL_SCALE, 1f).apply {
                startDelay = BODY_SCALE_OPEN_DELAY
                duration = BODY_SCALE_OPEN_DURATION
            }

        // More Actions Opacity Animation
        animators +=
            ObjectAnimator.ofFloat(moreActionsPill, ALPHA, 1f).apply {
                startDelay = BODY_ALPHA_OPEN_DELAY
                duration = BODY_ALPHA_OPEN_DURATION
            }

        // More Actions Elevation Animation
        animators +=
            ObjectAnimator.ofFloat(moreActionsPill, TRANSLATION_Z, 1f).apply {
                startDelay = ELEVATION_OPEN_DELAY
                duration = BODY_ELEVATION_OPEN_DURATION
            }

        // More Actions Content Opacity Animation
        moreActionsPill.children.forEach {
            animators +=
                ObjectAnimator.ofFloat(it, ALPHA, 1f).apply {
                    startDelay = BODY_ALPHA_OPEN_DELAY
                    duration = BODY_CONTENT_ALPHA_OPEN_DURATION
                    interpolator = Interpolators.FAST_OUT_SLOW_IN
                }
        }
    }

    private fun animateOpenInAppOrBrowserPill() {
        // Open in Browser X & Y Scaling Animation
        animators +=
            ObjectAnimator.ofFloat(openInAppOrBrowserPill, SCALE_X, HALF_INITIAL_SCALE, 1f).apply {
                startDelay = BODY_SCALE_OPEN_DELAY
                duration = BODY_SCALE_OPEN_DURATION
            }

        animators +=
            ObjectAnimator.ofFloat(openInAppOrBrowserPill, SCALE_Y, HALF_INITIAL_SCALE, 1f).apply {
                startDelay = BODY_SCALE_OPEN_DELAY
                duration = BODY_SCALE_OPEN_DURATION
            }

        // Open in Browser Opacity Animation
        animators +=
            ObjectAnimator.ofFloat(openInAppOrBrowserPill, ALPHA, 1f).apply {
                startDelay = BODY_ALPHA_OPEN_DELAY
                duration = BODY_ALPHA_OPEN_DURATION
            }

        // Open in Browser Elevation Animation
        animators +=
            ObjectAnimator.ofFloat(openInAppOrBrowserPill, TRANSLATION_Z, 1f).apply {
                startDelay = ELEVATION_OPEN_DELAY
                duration = BODY_ELEVATION_OPEN_DURATION
            }

        // Open in Browser Button Opacity Animation
        val button =
            openInAppOrBrowserPill.requireViewById<View>(R.id.open_in_app_or_browser_button)
        animators +=
            ObjectAnimator.ofFloat(button, ALPHA, 1f).apply {
                startDelay = BODY_ALPHA_OPEN_DELAY
                duration = BODY_CONTENT_ALPHA_OPEN_DURATION
                interpolator = Interpolators.FAST_OUT_SLOW_IN
            }
    }

    private fun windowingPillClose() {
        // Windowing X & Y Scaling Animation
        animators +=
            ObjectAnimator.ofFloat(windowingPill, SCALE_X, HALF_INITIAL_SCALE).apply {
                duration = BODY_CLOSE_DURATION
            }

        animators +=
            ObjectAnimator.ofFloat(windowingPill, SCALE_Y, HALF_INITIAL_SCALE).apply {
                duration = BODY_CLOSE_DURATION
            }

        // windowing Animation
        animators +=
            ObjectAnimator.ofFloat(windowingPill, ALPHA, 0f).apply {
                duration = BODY_CLOSE_DURATION
            }

        animators +=
            ObjectAnimator.ofFloat(windowingPill, ALPHA, 0f).apply {
                duration = BODY_CLOSE_DURATION
            }
    }

    private fun moreActionsPillClose() {
        // More Actions X & Y Scaling Animation
        animators +=
            ObjectAnimator.ofFloat(moreActionsPill, SCALE_X, HALF_INITIAL_SCALE).apply {
                duration = BODY_CLOSE_DURATION
            }

        animators +=
            ObjectAnimator.ofFloat(moreActionsPill, SCALE_Y, HALF_INITIAL_SCALE).apply {
                duration = BODY_CLOSE_DURATION
            }

        // More Actions Opacity Animation
        animators +=
            ObjectAnimator.ofFloat(moreActionsPill, ALPHA, 0f).apply {
                duration = BODY_CLOSE_DURATION
            }

        animators +=
            ObjectAnimator.ofFloat(moreActionsPill, ALPHA, 0f).apply {
                duration = BODY_CLOSE_DURATION
            }

        // upward more actions pill y-translation animation
        val yStart: Float = -captionHeight / 2
        animators +=
            ObjectAnimator.ofFloat(moreActionsPill, TRANSLATION_Y, yStart).apply {
                duration = BODY_CLOSE_DURATION
            }
    }

    private fun openInAppOrBrowserPillClose() {
        // Open in Browser X & Y Scaling Animation
        animators +=
            ObjectAnimator.ofFloat(openInAppOrBrowserPill, SCALE_X, HALF_INITIAL_SCALE).apply {
                duration = BODY_CLOSE_DURATION
            }

        animators +=
            ObjectAnimator.ofFloat(openInAppOrBrowserPill, SCALE_Y, HALF_INITIAL_SCALE).apply {
                duration = BODY_CLOSE_DURATION
            }

        // Open in Browser Opacity Animation
        animators +=
            ObjectAnimator.ofFloat(openInAppOrBrowserPill, ALPHA, 0f).apply {
                duration = BODY_CLOSE_DURATION
            }

        animators +=
            ObjectAnimator.ofFloat(openInAppOrBrowserPill, ALPHA, 0f).apply {
                duration = BODY_CLOSE_DURATION
            }

        // Upward Open in Browser y-translation Animation
        val yStart: Float = -captionHeight / 2
        animators +=
            ObjectAnimator.ofFloat(openInAppOrBrowserPill, TRANSLATION_Y, yStart).apply {
                duration = BODY_CLOSE_DURATION
            }
    }

    /**
     * Runs the list of hide animators concurrently.
     *
     * @param after runs after animation finishes.
     */
    private fun runAnimations(after: (() -> Unit)? = null) {
        runningAnimation?.apply {
            // Remove all listeners, so that the after function isn't triggered upon cancel.
            removeAllListeners()
            // If an animation runs while running animation is triggered, gracefully cancel.
            cancel()
        }

        runningAnimation =
            AnimatorSet().apply {
                playTogether(animators)
                animators.clear()
                doOnEnd {
                    after?.invoke()
                    runningAnimation = null
                }
                start()
            }
    }
}
