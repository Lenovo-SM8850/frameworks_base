// SPDX-License-Identifier: Apache-2.0
package com.android.wm.shell.transition;

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.view.WindowManager.TRANSIT_CHANGE;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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
    private TransitionInfo.Change change(SurfaceControl snapshot) {
        final RunningTaskInfo task = new RunningTaskInfo();
        task.isFloatingWindow = true;
        task.floatingWindowScale = .75f;
        task.positionInParent = new Point(350, 550);
        task.configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_FREEFORM);
        task.configuration.windowConfiguration.setBounds(new Rect(0, 0, 1200, 800));
        final TransitionInfo.Change change =
                new TransitionInfo.Change(mock(WindowContainerToken.class), mock(SurfaceControl.class));
        change.setTaskInfo(task);
        change.setMode(TRANSIT_CHANGE);
        change.setStartAbsBounds(new Rect(0, 0, 800, 1200));
        change.setEndAbsBounds(new Rect(0, 0, 1200, 800));
        change.setStartFloatingWindowBounds(new Rect(500, 400, 1100, 1300));
        change.setSnapshot(snapshot, .5f);
        return change;
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
    public void startsAtVisibleBoundsAndCrossFadesWithoutStretch() {
        final SurfaceControl snapshot = mock(SurfaceControl.class);
        final SurfaceControl veil = mock(SurfaceControl.class);
        final TransitionInfo.Change change = change(snapshot);
        final FloatingResizeAnimation animation =
                new FloatingResizeAnimation(change, new Point(), 20f, veil);
        final SurfaceControl.Transaction start = MockTransactionPool.create();
        animation.apply(start, 0f);
        verify(start).setPosition(veil, 500f, 400f);
        verify(start).setWindowCrop(veil, 600, 900);
        verify(start).setMatrix(snapshot, .75f, 0f, 0f, .75f);
        verify(start).setPosition(snapshot, 500f, 400f);
        verify(start).setAlpha(snapshot, 1f);
        verify(start).setAlpha(change.getLeash(), 0f);
        verify(start).setMatrix(change.getLeash(), .5f, 0f, 0f, .5f);

        final SurfaceControl.Transaction middle = MockTransactionPool.create();
        animation.apply(middle, .5f);
        verify(middle).setPosition(veil, 425f, 475f);
        verify(middle).setWindowCrop(veil, 750, 750);
        verify(middle).setMatrix(snapshot, .625f, 0f, 0f, .625f);
        verify(middle).setMatrix(change.getLeash(), .625f, 0f, 0f, .625f);
        verify(middle).setAlpha(snapshot, .5f);
        verify(middle).setAlpha(change.getLeash(), .5f);
    }

    @Test
    public void roundedVisibleSizeKeepsExactEndpointMatrices() {
        final SurfaceControl snapshot = mock(SurfaceControl.class);
        final TransitionInfo.Change change = change(snapshot);
        change.setStartAbsBounds(new Rect(0, 0, 800, 1199));
        change.setStartFloatingWindowBounds(new Rect(500, 400, 1100, 1299));
        change.setEndAbsBounds(new Rect(0, 0, 1200, 799));
        change.getTaskInfo().configuration.windowConfiguration.setBounds(new Rect(0, 0, 1200, 799));
        final FloatingResizeAnimation animation =
                new FloatingResizeAnimation(change, new Point(), 20f, mock(SurfaceControl.class));
        final SurfaceControl.Transaction start = MockTransactionPool.create();
        animation.apply(start, 0f);
        verify(start).setMatrix(snapshot, .75f, 0f, 0f, .75f);
        verify(start).setPosition(snapshot, 500f, 400f);
        final SurfaceControl.Transaction end = MockTransactionPool.create();
        animation.apply(end, 1f);
        verify(end).setMatrix(change.getLeash(), .75f, 0f, 0f, .75f);
        verify(end).setPosition(change.getLeash(), 350f, 550f);
    }

    @Test
    public void endsAtPresentationTransformIncludingRootOffsetWithoutSnapshot() {
        final SurfaceControl veil = mock(SurfaceControl.class);
        final TransitionInfo.Change change = change(null);
        final FloatingResizeAnimation animation =
                new FloatingResizeAnimation(change, new Point(100, 200), 20f, veil);
        final SurfaceControl.Transaction end = MockTransactionPool.create();
        animation.apply(end, 1f);
        verify(end).setPosition(veil, 250f, 350f);
        verify(end).setWindowCrop(veil, 900, 600);
        verify(end).setMatrix(change.getLeash(), .75f, 0f, 0f, .75f);
        verify(end).setPosition(change.getLeash(), 250f, 350f);
        verify(end).setAlpha(change.getLeash(), 1f);
        verify(end).setCornerRadius(change.getLeash(), 20f / .75f);
    }
}
