// SPDX-License-Identifier: Apache-2.0
package com.android.systemui.navigationbar.gestural;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.view.Display.DEFAULT_DISPLAY;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.ActivityManager.RunningTaskInfo;
import android.app.ActivityTaskManager.RootTaskInfo;
import android.app.IActivityTaskManager;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.RemoteException;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class EdgeBackGestureHandlerTest extends SysuiTestCase {
    private final IActivityTaskManager mTaskManager = mock(IActivityTaskManager.class);

    private RootTaskInfo floating(int id) {
        final RootTaskInfo task = new RootTaskInfo();
        task.taskId = id;
        task.displayId = DEFAULT_DISPLAY;
        task.isFloatingWindow = true;
        task.isVisible = true;
        task.isFocused = true;
        task.floatingWindowScale = 1f;
        task.positionInParent = new Point(832, 271);
        task.configuration.windowConfiguration.setBounds(new Rect(0, 0, 1210, 794));
        return task;
    }

    private RunningTaskInfo background(int type) {
        final RunningTaskInfo task = new RunningTaskInfo();
        task.taskId = 2;
        task.displayId = DEFAULT_DISPLAY;
        task.isVisible = true;
        task.configuration.windowConfiguration.setActivityType(type);
        return task;
    }

    private void tasks(RootTaskInfo focused, RunningTaskInfo... tasks) throws RemoteException {
        when(mTaskManager.getFocusedRootTaskInfo()).thenReturn(focused);
        when(mTaskManager.getTasks(Integer.MAX_VALUE, false, false, DEFAULT_DISPLAY))
                .thenReturn(List.of(tasks));
    }

    @Test
    public void focusedFloatingAboveHome_allowsBothEdgesOutsideFloatingBounds()
            throws RemoteException {
        final RootTaskInfo floating = floating(1);
        tasks(floating, floating, background(ACTIVITY_TYPE_HOME));
        assertTrue(ignoreAt(0, 900));
        assertTrue(ignoreAt(2559, 900));
        assertFalse(ignoreAt(1000, 900));
    }

    @Test
    public void fullscreenAppAboveHome_preservesExclusion() throws RemoteException {
        final RootTaskInfo floating = floating(1);
        tasks(floating, floating, background(ACTIVITY_TYPE_STANDARD), background(ACTIVITY_TYPE_HOME));
        assertFalse(ignoreAt(0, 900));
        assertFalse(ignoreAt(2559, 900));
    }

    @Test
    public void homeFocusOrNoFocusedTask_preservesExclusion() throws RemoteException {
        final RootTaskInfo home = new RootTaskInfo();
        home.displayId = DEFAULT_DISPLAY;
        home.isVisible = true;
        tasks(home, floating(1), background(ACTIVITY_TYPE_HOME));
        assertFalse(ignoreAt(0, 900));
        when(mTaskManager.getFocusedRootTaskInfo()).thenReturn(null);
        assertFalse(ignoreAt(2559, 900));
    }

    @Test
    public void invisibleOrOtherDisplayOrStaleFloatingFocus_preservesExclusion()
            throws RemoteException {
        final RootTaskInfo floating = floating(1);
        tasks(floating, floating, background(ACTIVITY_TYPE_HOME));
        floating.isVisible = false;
        assertFalse(ignoreAt(0, 900));
        floating.isVisible = true;
        floating.displayId = 1;
        assertFalse(ignoreAt(0, 900));
        floating.displayId = DEFAULT_DISPLAY;
        floating.isFocused = false;
        assertFalse(ignoreAt(0, 900));
    }

    @Test
    public void anotherFloatingWindowAtEdge_preservesItsExclusion() throws RemoteException {
        final RootTaskInfo focused = floating(1);
        final RootTaskInfo other = floating(3);
        other.isFocused = false;
        other.positionInParent = new Point(0, 0);
        tasks(focused, focused, other, background(ACTIVITY_TYPE_HOME));
        assertFalse(ignoreAt(0, 400));
        assertTrue(ignoreAt(2559, 400));
    }

    @Test
    public void missingHomeOrBinderFailure_preservesExclusion() throws RemoteException {
        final RootTaskInfo floating = floating(1);
        tasks(floating, floating);
        assertFalse(ignoreAt(0, 900));
        when(mTaskManager.getFocusedRootTaskInfo()).thenThrow(new RemoteException());
        assertFalse(ignoreAt(2559, 900));
    }

    private boolean ignoreAt(int x, int y) {
        return EdgeBackGestureHandler.shouldIgnoreHomeExclusion(
                mTaskManager, DEFAULT_DISPLAY, x, y);
    }
}
