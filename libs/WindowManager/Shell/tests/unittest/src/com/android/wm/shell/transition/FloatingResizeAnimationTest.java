// SPDX-License-Identifier: Apache-2.0
package com.android.wm.shell.transition;

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.view.WindowManager.TRANSIT_CHANGE;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

import android.app.ActivityManager.RunningTaskInfo;
import android.graphics.Point;
import android.graphics.Rect;
import android.view.SurfaceControl;
import android.window.TransitionInfo;
import android.window.WindowContainerToken;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.wm.shell.ShellTestCase;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class FloatingResizeAnimationTest extends ShellTestCase {
    private SurfaceControl validSurface() {
        final SurfaceControl surface = mock(SurfaceControl.class);
        when(surface.isValid()).thenReturn(true);
        return surface;
    }

    @Test
    public void emptyBookkeepingChangeDoesNotEndRunningRotation() {
        final TransitionInfo.Change change = change(null);
        final FloatingResizeAnimation animation = new FloatingResizeAnimation(change,
                new Point(), 20f, 30f, validSurface(),
                mock(com.android.wm.shell.windowdecor.ResizeVeil.class));
        final java.util.List<WindowAnimation> running = java.util.List.of(new WindowAnimation(
                change, 20f, animation, android.animation.ValueAnimator.ofFloat(0f, 1f)));
        final TransitionInfo empty = new TransitionInfo(TRANSIT_CHANGE, 0);
        assertTrue(DefaultTransitionHandler.shouldDeferEmptyFloatingRotationChange(empty, running));
        assertFalse(DefaultTransitionHandler.shouldDeferEmptyFloatingRotationChange(
                new TransitionInfo(android.view.WindowManager.TRANSIT_OPEN, 0), running));
        empty.addChange(change);
        assertFalse(DefaultTransitionHandler.shouldDeferEmptyFloatingRotationChange(empty, running));
        assertFalse(DefaultTransitionHandler.shouldDeferEmptyFloatingRotationChange(
                new TransitionInfo(TRANSIT_CHANGE, 0), java.util.List.of()));
    }

    @Test
    public void deferredFinishKeepsRealFrameValidForAnimation() {
        final SurfaceControl frame = new SurfaceControl.Builder()
                .setName("Floating rotation finish test").setContainerLayer().build();
        final com.android.wm.shell.windowdecor.ResizeVeil veil =
                mock(com.android.wm.shell.windowdecor.ResizeVeil.class);
        try (SurfaceControl.Transaction finish = new SurfaceControl.Transaction()) {
            DefaultTransitionHandler.finishFloatingRotation(finish, frame, veil, Runnable::run);
            // Removing the frame here releases the Java handle even though finish was never applied.
            assertTrue(frame.isValid());
            final FloatingResizeAnimation animation = new FloatingResizeAnimation(change(null),
                    new Point(), 20f, 30f, frame, veil);
            final SurfaceControl.Transaction middle = MockTransactionPool.create();
            animation.applyAtTime(middle, FloatingResizeAnimation.MORPH_START + 200, true);
            verify(middle).setCornerRadius(frame, 20f);
            verify(veil).updateTransitionFrame(org.mockito.ArgumentMatchers.eq(middle),
                    org.mockito.ArgumentMatchers.any(Rect.class),
                    org.mockito.ArgumentMatchers.eq(1f), org.mockito.ArgumentMatchers.eq(1f));
            org.mockito.Mockito.verify(veil, org.mockito.Mockito.never()).dispose();
        } finally {
            frame.release();
        }
    }

    @Test
    public void invalidLeashOrVeil_skipsLateAnimationFrames() {
        final SurfaceControl veil = validSurface();
        final TransitionInfo.Change change = change(null);
        final FloatingResizeAnimation animation =
                new FloatingResizeAnimation(change, new Point(), 20f, 30f, veil,
                        mock(com.android.wm.shell.windowdecor.ResizeVeil.class));
        final SurfaceControl.Transaction t = MockTransactionPool.create();
        when(change.getLeash().isValid()).thenReturn(false);
        animation.applyAtTime(t, 283, true);
        verifyNoInteractions(t);
        when(change.getLeash().isValid()).thenReturn(true);
        when(veil.isValid()).thenReturn(false);
        animation.applyAtTime(t, 283, true);
        verifyNoInteractions(t);
    }

    @Test
    public void backExitUsesClosingAnimationButMiniMinimizeDoesNot() {
        final TransitionInfo.Change change = change(null);
        change.setMode(android.view.WindowManager.TRANSIT_TO_BACK);
        assertFalse(DefaultTransitionHandler.isFloatingExit(change));
        change.getTaskInfo().isFloatingWindow = false;
        assertTrue(DefaultTransitionHandler.isFloatingExit(change));
    }

    @Test
    public void missingCapturedPresentation_doesNotSelectRotation() {
        final TransitionInfo.Change change = change(null);
        change.setStartFloatingWindowBounds(new Rect());
        assertFalse(DefaultTransitionHandler.isFloatingRotation(change));
        change.setStartFloatingWindowBounds(new Rect(500, 400, 1100, 1300));
        assertTrue(DefaultTransitionHandler.isFloatingRotation(change));
    }

    private TransitionInfo.Change change(SurfaceControl snapshot) {
        final RunningTaskInfo task = new RunningTaskInfo();
        task.isFloatingWindow = true;
        task.floatingWindowScale = .75f;
        task.positionInParent = new Point(350, 550);
        task.configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_FREEFORM);
        task.configuration.windowConfiguration.setBounds(new Rect(0, 0, 1200, 800));
        final TransitionInfo.Change change =
                new TransitionInfo.Change(mock(WindowContainerToken.class), validSurface());
        change.setTaskInfo(task);
        change.setMode(TRANSIT_CHANGE);
        change.setStartAbsBounds(new Rect(0, 0, 800, 1200));
        change.setEndAbsBounds(new Rect(0, 0, 1200, 800));
        change.setStartFloatingWindowBounds(new Rect(500, 400, 1100, 1300));
        change.setSnapshot(snapshot, .5f);
        return change;
    }

    @Test
    public void manualShapeChangeAnimatesButPresentationScaleAndDisplayRotationDoNot() {
        final TransitionInfo.Change change = change(null);
        assertTrue(DefaultTransitionHandler.isFloatingRotation(change));
        change.setEndAbsBounds(change.getStartAbsBounds());
        assertFalse(DefaultTransitionHandler.isFloatingRotation(change));
        change.setEndAbsBounds(new Rect(0, 0, 1200, 800));
        change.setRotation(change.getStartRotation(), change.getStartRotation() + 1);
        assertFalse(DefaultTransitionHandler.isFloatingRotation(change));
        change.setRotation(0, 0);
        change.setDisplayId(0, 1);
        assertFalse(DefaultTransitionHandler.isFloatingRotation(change));
        change.setDisplayId(0, 0);
        change.getTaskInfo().isFloatingWindow = false;
        assertFalse(DefaultTransitionHandler.isFloatingRotation(change));
    }

    @Test
    public void veilCoversEntireMorphThenRevealsReadyContent() {
        final SurfaceControl snapshot = validSurface();
        final SurfaceControl frame = validSurface();
        final TransitionInfo.Change change = change(snapshot);
        final com.android.wm.shell.windowdecor.ResizeVeil veil =
                mock(com.android.wm.shell.windowdecor.ResizeVeil.class);
        final FloatingResizeAnimation animation =
                new FloatingResizeAnimation(change, new Point(), 20f, 30f, frame, veil);
        assertEquals(950, animation.getDuration());
        final SurfaceControl.Transaction start = MockTransactionPool.create();
        animation.applyAtTime(start, 0, true);
        verify(veil).updateTransitionFrame(start, new Rect(500, 400, 1100, 1300), 0f, 0f);
        verify(start).setAlpha(change.getLeash(), 0f);
        verify(start).setAlpha(snapshot, 1f);

        final SurfaceControl.Transaction covered = MockTransactionPool.create();
        animation.applyAtTime(covered, FloatingResizeAnimation.MORPH_START, true);
        verify(veil).updateTransitionFrame(covered, new Rect(500, 400, 1100, 1300), 1f, .34f);
        verify(covered).setAlpha(snapshot, 0f);
        final SurfaceControl.Transaction middle = MockTransactionPool.create();
        animation.applyAtTime(middle, FloatingResizeAnimation.MORPH_START + 200, true);
        final float progress = com.android.wm.shell.shared.animation.Interpolators
                .EMPHASIZED_DECELERATE.getInterpolation(.5f);
        final int x = Math.round(500 - 150 * progress);
        final int y = Math.round(400 + 150 * progress);
        final int width = Math.round(600 + 300 * progress);
        final int height = Math.round(900 - 300 * progress);
        verify(veil).updateTransitionFrame(middle, new Rect(x, y, x + width, y + height), 1f, 1f);
        verify(middle).setAlpha(change.getLeash(), 0f);
        // Neither content layer is stretched or exposed during the opaque frame morph.
        verify(middle).setMatrix(snapshot, .75f, 0f, 0f, .75f);
        verify(middle).setMatrix(change.getLeash(), .75f, 0f, 0f, .75f);
        verify(middle).setCornerRadius(frame, 20f);
        verify(middle).setShadowRadius(frame, 30f);

        final SurfaceControl.Transaction landed = MockTransactionPool.create();
        animation.applyAtTime(landed, FloatingResizeAnimation.MORPH_END, true);
        verify(veil).updateTransitionFrame(landed, new Rect(350, 550, 1250, 1150), 1f, 1f);
        verify(landed).setAlpha(change.getLeash(), 1f);
        final SurfaceControl.Transaction reveal = MockTransactionPool.create();
        animation.applyAtTime(reveal, FloatingResizeAnimation.MORPH_END + 100, true);
        verify(veil).updateTransitionFrame(reveal, new Rect(350, 550, 1250, 1150), .5f, .5f);
        verify(reveal).setShadowRadius(frame, 0f);
        verify(reveal).setCornerRadius(change.getLeash(), 20f / .75f);
        verify(reveal).setShadowRadius(change.getLeash(), 30f / .75f);
        assertFalse(animation.isFinished());
        animation.applyAtTime(MockTransactionPool.create(), FloatingResizeAnimation.MORPH_END + 200,
                true);
        assertTrue(animation.isFinished());
    }

    @Test
    public void revealOnlyFadesVeilWithOneShadowAndOpaqueContentThroughFinish() {
        final SurfaceControl snapshot = validSurface();
        final SurfaceControl frame = validSurface();
        final TransitionInfo.Change change = change(snapshot);
        final FloatingResizeAnimation animation = new FloatingResizeAnimation(change, new Point(),
                20f, 30f, frame, mock(com.android.wm.shell.windowdecor.ResizeVeil.class));
        animation.applyAtTime(MockTransactionPool.create(), FloatingResizeAnimation.MORPH_END, true);
        for (long offset : new long[] {0, 1, 50, 100, 199, 200, 500}) {
            final SurfaceControl.Transaction t = MockTransactionPool.create();
            animation.applyAtTime(t, FloatingResizeAnimation.MORPH_END + offset, true);
            verify(t).setAlpha(change.getLeash(), 1f);
            verify(t).setAlpha(snapshot, 0f);
            verify(t).setShadowRadius(frame, 0f);
            verify(t).setShadowRadius(change.getLeash(), 30f / .75f);
            verify(t).setCornerRadius(change.getLeash(), 20f / .75f);
            verify(t).setMatrix(change.getLeash(), .75f, 0f, 0f, .75f);
            verify(t).setPosition(change.getLeash(), 350f, 550f);
        }
        assertTrue(animation.isFinished());
    }

    @Test
    public void startingWindowKeepsVeilUntilRealContentDraws() {
        final SurfaceControl frame = validSurface();
        final TransitionInfo.Change change = change(null);
        final com.android.wm.shell.windowdecor.ResizeVeil veil =
                mock(com.android.wm.shell.windowdecor.ResizeVeil.class);
        final FloatingResizeAnimation animation =
                new FloatingResizeAnimation(change, new Point(), 20f, 30f, frame, veil);
        final SurfaceControl.Transaction hold = MockTransactionPool.create();
        animation.applyAtTime(hold, 550, false);
        verify(veil).updateTransitionFrame(hold, new Rect(350, 550, 1250, 1150), 1f, 1f);
        verify(hold).setAlpha(change.getLeash(), 0f);
        assertFalse(animation.isFinished());
        animation.applyAtTime(MockTransactionPool.create(), 600, true);
        assertFalse(animation.isFinished());
        animation.applyAtTime(MockTransactionPool.create(), 800, true);
        assertTrue(animation.isFinished());
    }

    @Test
    public void drawTimeoutReleasesVeilAfterThreeHundredMillisecondsAtLandedFrame() {
        final FloatingResizeAnimation animation = new FloatingResizeAnimation(change(null),
                new Point(), 20f, 30f, validSurface(),
                mock(com.android.wm.shell.windowdecor.ResizeVeil.class));
        animation.applyAtTime(MockTransactionPool.create(), 749, false);
        assertFalse(animation.isFinished());
        animation.applyAtTime(MockTransactionPool.create(), 750, false);
        assertFalse(animation.isFinished());
        animation.applyAtTime(MockTransactionPool.create(), 950, false);
        assertTrue(animation.isFinished());
    }

    @Test
    public void missingSnapshotStartsOpaqueAndNeverExposesNewContentBeforeLanding() {
        final com.android.wm.shell.windowdecor.ResizeVeil veil =
                mock(com.android.wm.shell.windowdecor.ResizeVeil.class);
        final TransitionInfo.Change change = change(null);
        final FloatingResizeAnimation animation = new FloatingResizeAnimation(change,
                new Point(), 20f, 30f, validSurface(), veil);
        final SurfaceControl.Transaction start = MockTransactionPool.create();
        animation.applyAtTime(start, 0, true);
        verify(veil).updateTransitionFrame(start, new Rect(500, 400, 1100, 1300), 1f, 0f);
        verify(start).setAlpha(change.getLeash(), 0f);
    }

    @Test
    public void animationScaleAppliesToEveryStageAndZeroScaleFinishesImmediately() {
        final FloatingResizeAnimation animation = new FloatingResizeAnimation(change(null),
                new Point(), 20f, 30f, validSurface(),
                mock(com.android.wm.shell.windowdecor.ResizeVeil.class));
        animation.setDuration(1900);
        animation.applyAtTime(MockTransactionPool.create(), 900, true);
        assertFalse(animation.isFinished());
        animation.applyAtTime(MockTransactionPool.create(), 1300, true);
        assertTrue(animation.isFinished());
        final TransitionInfo.Change change = change(null);
        final FloatingResizeAnimation zero = new FloatingResizeAnimation(change,
                new Point(100, 200), 20f, 30f, validSurface(),
                mock(com.android.wm.shell.windowdecor.ResizeVeil.class));
        zero.setDuration(0);
        final SurfaceControl.Transaction end = MockTransactionPool.create();
        zero.applyAtTime(end, 0, false);
        assertTrue(zero.isFinished());
        verify(end).setMatrix(change.getLeash(), .75f, 0f, 0f, .75f);
        verify(end).setPosition(change.getLeash(), 250f, 350f);
        verify(end).setAlpha(change.getLeash(), 1f);
    }
}
