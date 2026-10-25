// SPDX-License-Identifier: Apache-2.0
package com.android.wm.shell.transition;

import android.graphics.Point;
import android.graphics.Rect;
import android.view.SurfaceControl;
import android.view.animation.Animation;
import android.view.animation.Transformation;
import android.window.TransitionInfo;

import com.android.wm.shell.shared.animation.Interpolators;
import com.android.wm.shell.windowdecor.ResizeVeil;

/** Stages a themed veil, an opaque frame morph, and a content-ready reveal. */
final class FloatingResizeAnimation extends Animation {
    static final long VEIL_IN = 50;
    static final long ICON_DELAY = 33;
    static final long MORPH_START = VEIL_IN;
    static final long MORPH_DURATION = 400;
    static final long MORPH_END = MORPH_START + MORPH_DURATION;
    static final long DRAW_TIMEOUT = 300;
    static final long VEIL_OUT = 200;
    static final long MAX_DURATION = MORPH_END + DRAW_TIMEOUT + VEIL_OUT;

    private final Rect mStart;
    private final Rect mEnd;
    private final Rect mOldContent;
    private final Rect mNewContent;
    private final Rect mFrame = new Rect();
    private final Point mRootOffset;
    private final SurfaceControl mLeash;
    private final SurfaceControl mSnapshot;
    private final SurfaceControl mVeil;
    private final ResizeVeil mResizeVeil;
    private final float mRadius;
    private final float mShadow;
    private final float mScale;
    private float mRevealStart = -1f;
    private boolean mFinished;

    FloatingResizeAnimation(TransitionInfo.Change change, Point rootOffset, float radius,
            float shadow, SurfaceControl veil, ResizeVeil resizeVeil) {
        setDuration(MAX_DURATION);
        setInterpolator(Interpolators.LINEAR);
        mStart = new Rect(change.getStartFloatingWindowBounds());
        mEnd = new Rect(change.getTaskInfo().getPresentationBounds());
        mOldContent = new Rect(change.getStartAbsBounds());
        mNewContent = new Rect(change.getEndAbsBounds());
        mRootOffset = new Point(rootOffset);
        mLeash = change.getLeash();
        mSnapshot = change.getSnapshot();
        mVeil = veil;
        mResizeVeil = resizeVeil;
        mRadius = radius;
        mShadow = shadow;
        mScale = change.getTaskInfo().floatingWindowScale;
    }

    void applyAtTime(SurfaceControl.Transaction t, long elapsed, boolean contentReady) {
        // The transition can finish and release these surfaces before an animation frame runs.
        if (!mVeil.isValid() || !mLeash.isValid()) {
            return;
        }
        final float time = getDuration() == 0 ? MAX_DURATION
                : Math.max(0f, Math.min(MAX_DURATION, elapsed * (float) MAX_DURATION / getDuration()));
        if (time >= MORPH_END && mRevealStart < 0f
                && (contentReady || time >= MORPH_END + DRAW_TIMEOUT)) {
            // Never extend the timeout if the animator is ended or skips directly to its last frame.
            mRevealStart = Math.min(time, MORPH_END + DRAW_TIMEOUT);
        }
        final float out = mRevealStart < 0f ? 1f : 1f - clamp((time - mRevealStart) / VEIL_OUT);
        final float progress = morphProgress(time);
        final int width = Math.round(lerp(mStart.width(), mEnd.width(), progress));
        final int height = Math.round(lerp(mStart.height(), mEnd.height(), progress));
        final int x = Math.round(lerp(mStart.left, mEnd.left, progress)) - mRootOffset.x;
        final int y = Math.round(lerp(mStart.top, mEnd.top, progress)) - mRootOffset.y;
        mFrame.set(x, y, x + width, y + height);
        final boolean hasSnapshot = mSnapshot != null && mSnapshot.isValid();
        final float background = (hasSnapshot ? clamp(time / VEIL_IN) : 1f) * out;
        final float icon = clamp((time - ICON_DELAY) / VEIL_IN) * out;
        mResizeVeil.updateTransitionFrame(t, mFrame, background, icon);
        // Hand the shadow to the opaque task before fading the translucent veil above it.
        // A container shadow otherwise fills underneath the veil and darkens exposed content.
        t.setMatrix(mVeil, 1f, 0f, 0f, 1f).setCornerRadius(mVeil, mRadius)
                .setShadowRadius(mVeil, mRevealStart >= 0f ? 0f : mShadow);
        t.setMatrix(mLeash, mScale, 0f, 0f, mScale)
                .setPosition(mLeash, mEnd.left - mRootOffset.x, mEnd.top - mRootOffset.y)
                .setWindowCrop(mLeash, mNewContent.width(), mNewContent.height())
                .setCornerRadius(mLeash, mRadius / Math.max(.001f, mScale))
                .setShadowRadius(mLeash, mShadow / Math.max(.001f, mScale))
                .setAlpha(mLeash, mRevealStart >= 0f ? 1f : 0f);
        if (mSnapshot != null && mSnapshot.isValid()) {
            final float scale = (float) mStart.width() / Math.max(1, mOldContent.width());
            t.setMatrix(mSnapshot, scale, 0f, 0f, scale)
                    .setPosition(mSnapshot, mStart.left - mRootOffset.x, mStart.top - mRootOffset.y)
                    .setWindowCrop(mSnapshot, mOldContent.width(), mOldContent.height())
                    .setCornerRadius(mSnapshot, mRadius / Math.max(.001f, scale))
                    .setAlpha(mSnapshot, time < VEIL_IN ? 1f : 0f);
        }
        mFinished = mRevealStart >= 0f && out == 0f;
    }

    boolean isFinished() {
        return mFinished;
    }

    private static float morphProgress(float time) {
        return Interpolators.EMPHASIZED_DECELERATE.getInterpolation(
                clamp((time - MORPH_START) / MORPH_DURATION));
    }

    @Override
    protected void applyTransformation(float progress, Transformation transformation) {
        final float morph = morphProgress(progress * MAX_DURATION);
        final float sx = lerp(mStart.width(), mEnd.width(), morph) / Math.max(1, mOldContent.width());
        final float sy = lerp(mStart.height(), mEnd.height(), morph) / Math.max(1, mOldContent.height());
        transformation.getMatrix().setScale(sx, sy);
        transformation.getMatrix().postTranslate(
                lerp(mStart.left, mEnd.left, morph) - mRootOffset.x - mOldContent.left * sx,
                lerp(mStart.top, mEnd.top, morph) - mRootOffset.y - mOldContent.top * sy);
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static float lerp(float start, float end, float progress) {
        return start + (end - start) * progress;
    }
}
