// SPDX-License-Identifier: Apache-2.0
package com.android.wm.shell.transition;

import android.graphics.Point;
import android.graphics.Rect;
import android.view.SurfaceControl;
import android.view.animation.Animation;
import android.view.animation.Transformation;
import android.window.TransitionInfo;

/** Cross-fades uniformly fitted content over a changing resize veil. */
final class FloatingResizeAnimation extends Animation {
    private final Rect mStart;
    private final Rect mEnd;
    private final Rect mOldContent;
    private final Rect mNewContent;
    private final Point mRootOffset;
    private final SurfaceControl mLeash;
    private final SurfaceControl mSnapshot;
    private final SurfaceControl mVeil;
    private final float mRadius;
    private final float mScale;

    FloatingResizeAnimation(TransitionInfo.Change change, Point rootOffset, float radius,
            SurfaceControl veil) {
        mStart = new Rect(change.getStartFloatingWindowBounds());
        mEnd = new Rect(change.getTaskInfo().getPresentationBounds());
        mOldContent = new Rect(change.getStartAbsBounds());
        mNewContent = new Rect(change.getEndAbsBounds());
        mRootOffset = new Point(rootOffset);
        mLeash = change.getLeash();
        mSnapshot = change.getSnapshot();
        mVeil = veil;
        mRadius = radius;
        mScale = change.getTaskInfo().floatingWindowScale;
    }

    void apply(SurfaceControl.Transaction t, float progress) {
        // The transition can finish and release these surfaces before an animation frame runs.
        if (!mVeil.isValid() || !mLeash.isValid()) {
            return;
        }
        final float width = lerp(mStart.width(), mEnd.width(), progress);
        final float height = lerp(mStart.height(), mEnd.height(), progress);
        final float x = lerp(mStart.left, mEnd.left, progress) - mRootOffset.x;
        final float y = lerp(mStart.top, mEnd.top, progress) - mRootOffset.y;
        t.setPosition(mVeil, x, y).setWindowCrop(mVeil, Math.round(width), Math.round(height))
                .setCornerRadius(mVeil, mRadius);
        fit(t, mLeash, mNewContent, width, height, x, y, progress, progress == 1f);
        if (mSnapshot != null && mSnapshot.isValid()) {
            fit(t, mSnapshot, mOldContent, width, height, x, y, 1f - progress, progress == 0f);
        }
    }

    private void fit(SurfaceControl.Transaction t, SurfaceControl surface, Rect content,
            float width, float height, float x, float y, float alpha, boolean exact) {
        final float scale = exact ? mScale : Math.min(width / Math.max(1, content.width()),
                height / Math.max(1, content.height()));
        t.setMatrix(surface, scale, 0f, 0f, scale)
                .setPosition(surface, exact ? x : x + (width - content.width() * scale) / 2f,
                        exact ? y : y + (height - content.height() * scale) / 2f)
                .setWindowCrop(surface, content.width(), content.height())
                .setCornerRadius(surface, mRadius / Math.max(.001f, scale))
                .setAlpha(surface, alpha);
    }

    @Override
    protected void applyTransformation(float progress, Transformation transformation) {
        final float sx = lerp(mStart.width(), mEnd.width(), progress)
                / Math.max(1, mOldContent.width());
        final float sy = lerp(mStart.height(), mEnd.height(), progress)
                / Math.max(1, mOldContent.height());
        // Describe the outline for transition handoff, without stretching either content layer.
        transformation.getMatrix().setScale(sx, sy);
        transformation.getMatrix().postTranslate(
                lerp(mStart.left, mEnd.left, progress) - mRootOffset.x - mOldContent.left * sx,
                lerp(mStart.top, mEnd.top, progress) - mRootOffset.y - mOldContent.top * sy);
    }

    private static float lerp(float start, float end, float progress) {
        return start + (end - start) * progress;
    }
}
