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

}
