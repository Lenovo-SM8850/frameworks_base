// SPDX-License-Identifier: Apache-2.0
package com.android.server.wm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import android.os.Handler;
import android.os.SystemClock;
import android.view.GameGestureLock;
import android.os.test.TestLooper;
import android.view.IGameGestureLockListener;
import android.view.InputDevice;
import android.view.MotionEvent;
import androidx.test.filters.SmallTest;
import org.junit.Test;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

@SmallTest
public class GameGestureLockControllerTest {
    private final TestLooper mLooper = new TestLooper();
    private final Handler mHandler = new Handler(mLooper.getLooper());
    private final List<String> mEffects = new ArrayList<>();
    private final List<Boolean> mNotices = new ArrayList<>();
    private int mProcess = 100;
    private Runnable mDuringWrite;
    private boolean mRequested = true;
    private boolean mAccessibility;
    private boolean mOneHanded;
    private final GameGestureLockController mController = new GameGestureLockController(mHandler,
            new GameGestureLockController.SettingsStore() {
                @Override public boolean get(String key, int user) {
                    if (key.equals("ax_gaming_gesture_lock")) return mRequested;
                    if (key.equals(android.provider.Settings.Secure.ACCESSIBILITY_ENABLED)) {
                        return mAccessibility;
                    }
                    if (key.equals(android.provider.Settings.Secure.ONE_HANDED_MODE_ENABLED)) {
                        return mOneHanded;
                    }
                    return false;
                }
                @Override public void turnOff(int user) {
                    mEffects.add("setting:" + mProcess);
                    if (mDuringWrite != null) mDuringWrite.run();
                }
            });

    private void session(int process) {
        mProcess = process;
        mController.startSession();
        mController.setSession(1, 0, 0, 0, true);
        mController.setInputTarget(0, 1, 0, 0, true);
        mController.setBindingAlive(true);
    }
    private void initialize() {
        mController.setListener(new IGameGestureLockListener.Stub() {
            @Override public void onNotice(long generation, int user, boolean visible) { mNotices.add(visible); }
            @Override public void onStateChanged(boolean active) { }
        });
        mController.setTurnOffCallback(() -> mEffects.add("command:" + mProcess));
        session(100);
        mLooper.dispatchAll();
        assertTrue(mController.isActive(0));
    }

    private MotionEvent touch(float x, float y, int display, int source, int tool) {
        MotionEvent.PointerProperties properties = new MotionEvent.PointerProperties();
        properties.id = 0;
        properties.toolType = tool;
        MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
        coords.x = x;
        coords.y = y;
        MotionEvent event = MotionEvent.obtain(1000, 1000, MotionEvent.ACTION_DOWN, 1,
                new MotionEvent.PointerProperties[] {properties},
                new MotionEvent.PointerCoords[] {coords}, 0, 0, 1, 1, 7, 0, source, 0);
        event.setDisplayId(display);
        return event;
    }

    private boolean candidate(float x, float y, int display, int source, int tool) {
        MotionEvent event = touch(x, y, display, source, tool);
        try { return mController.isHomeGestureCandidate(event); }
        finally { event.recycle(); }
    }

    @Test public void bottomCandidatesUseCurrentUserModeAndLogicalDisplayGeometry() {
        initialize();
        for (int[] size : new int[][] {{1904, 3040}, {3040, 1904}}) {
            mController.setInputGeometry(0, 2, size[0], size[1], 88);
            for (float x : new float[] {0, 1, size[0] / 2f, size[0] - 1}) {
                assertTrue(candidate(x, size[1] - 1, 0, InputDevice.SOURCE_TOUCHSCREEN,
                        MotionEvent.TOOL_TYPE_FINGER));
                assertTrue(candidate(x, size[1] - 88, 0, InputDevice.SOURCE_TOUCHSCREEN,
                        MotionEvent.TOOL_TYPE_FINGER));
            }
            assertFalse(candidate(100, size[1] - 89, 0, InputDevice.SOURCE_TOUCHSCREEN,
                    MotionEvent.TOOL_TYPE_FINGER));
            assertFalse(candidate(size[0], size[1] - 1, 0, InputDevice.SOURCE_TOUCHSCREEN,
                    MotionEvent.TOOL_TYPE_FINGER));
            assertFalse(candidate(100, size[1], 0, InputDevice.SOURCE_TOUCHSCREEN,
                    MotionEvent.TOOL_TYPE_FINGER));
            for (int mode : new int[] {0, 1}) {
                mController.setInputGeometry(0, mode, size[0], size[1], 88);
                assertFalse(candidate(100, size[1] - 1, 0, InputDevice.SOURCE_TOUCHSCREEN,
                        MotionEvent.TOOL_TYPE_FINGER));
            }
        }
    }

    @Test public void bottomCandidatesRejectForeignDisplayMouseStylusAndUnknownGeometry() {
        initialize();
        assertFalse(candidate(100, 1903, 0, InputDevice.SOURCE_TOUCHSCREEN, MotionEvent.TOOL_TYPE_FINGER));
        mController.setInputGeometry(0, 2, 3040, 1904, 88);
        assertFalse(candidate(100, 1903, 1, InputDevice.SOURCE_TOUCHSCREEN, MotionEvent.TOOL_TYPE_FINGER));
        assertFalse(candidate(100, 1903, 0, InputDevice.SOURCE_MOUSE, MotionEvent.TOOL_TYPE_FINGER));
        assertFalse(candidate(100, 1903, 0, InputDevice.SOURCE_TOUCHSCREEN, MotionEvent.TOOL_TYPE_STYLUS));
        mController.setInputGeometry(1, 0, 0, 0, 0);
        assertTrue(candidate(100, 1903, 0, InputDevice.SOURCE_TOUCHSCREEN, MotionEvent.TOOL_TYPE_FINGER));
    }

    @Test public void geometryChangeClearsHomeConfirmationAndFirstSwipeNotifies() {
        initialize();
        mController.setInputGeometry(0, 2, 1904, 3040, 88);
        long down = SystemClock.uptimeMillis();
        assertEquals(GameGestureLock.BLOCK, mController.begin(GameGestureLock.HOME, 0, 7, down));
        assertTrue(mController.recognize(GameGestureLock.HOME, 0, 7, down));
        mController.end(GameGestureLock.HOME, 0, 7, down, down + 100, false);
        mLooper.dispatchAll();
        assertTrue(mNotices.contains(true));
        assertEquals(GameGestureLock.ALLOW, mController.begin(GameGestureLock.HOME, 0, 7, down + 350));
        long generation = mController.generation();
        mController.setInputGeometry(0, 2, 3040, 1904, 88);
        assertTrue(mController.generation() > generation);
        assertEquals(GameGestureLock.BLOCK, mController.begin(GameGestureLock.HOME, 0, 7, down + 700));
    }


    @Test public void sessionRotationKeepsLockAndClearsConfirmations() {
        initialize();
        long down = SystemClock.uptimeMillis();
        assertEquals(GameGestureLock.BLOCK, mController.begin(GameGestureLock.BACK, 0, 1, down));
        assertTrue(mController.recognize(GameGestureLock.BACK, 0, 1, down));
        mController.end(GameGestureLock.BACK, 0, 1, down, down + 100, false);
        long generation = mController.generation();
        mController.setSession(1, 0, 0, 1, true);
        mController.setInputTarget(0, 1, 0, 1, true);
        mLooper.dispatchAll();
        assertTrue(mController.isActive(0));
        assertTrue(mController.generation() > generation);
        long nextDown = Math.max(SystemClock.uptimeMillis(), down + 500);
        for (int kind = GameGestureLock.BACK; kind <= GameGestureLock.REVEAL; kind++) {
            assertEquals(GameGestureLock.BLOCK, mController.begin(kind, 0, 1, nextDown));
        }
    }
    @Test public void inputRotationBeforeSessionSnapshotKeepsLock() {
        initialize();
        for (int rotation : new int[] {1, 2, 3, 0}) {
            mController.setInputTarget(0, 1, 0, rotation, true);
            assertTrue(mController.isActive(0));
            mController.setSession(1, 0, 0, rotation, true);
            mLooper.dispatchAll();
            assertTrue(mController.isActive(0));
        }
    }
    @Test public void inputFocusCanSettleAfterSessionSnapshot() {
        initialize();
        mController.setInputTarget(0, -1, -1, 0, false);
        mController.setSession(1, 0, 0, 0, true);
        assertFalse(mController.isActive(0));
        mController.setInputTarget(0, 1, 0, 0, true);
        assertTrue(mController.isActive(0));
        mController.setInputTarget(0, 1, 0, 0, false);
        assertFalse(mController.isActive(0));
        mController.setInputTarget(0, 1, 0, 0, true);
        assertTrue(mController.isActive(0));
    }
    @Test public void mismatchedTaskUserOrDisplayCannotOwnInput() {
        initialize();
        mController.setInputTarget(0, 2, 0, 0, true);
        assertFalse(mController.isActive(0));
        mController.setInputTarget(0, 1, 10, 0, true);
        assertFalse(mController.isActive(0));
        mController.setInputTarget(1, 1, 0, 0, true);
        assertFalse(mController.isActive(0));
        mController.setInputTarget(0, 1, 0, 0, true);
        assertTrue(mController.isActive(0));
        assertFalse(mController.isActive(1));
        mController.setSession(1, 0, 1, 0, true);
        assertFalse(mController.isActive(0));
        assertFalse(mController.isActive(1));
    }
    @Test public void bindingAndListenerMustRecoverBeforeLockResumes() {
        initialize();
        mController.setBindingAlive(false);
        assertFalse(mController.isActive(0));
        mController.setBindingAlive(true);
        assertTrue(mController.isActive(0));
        mController.setListener(null);
        assertFalse(mController.isActive(0));
        mController.setListener(new IGameGestureLockListener.Stub() {
            @Override public void onNotice(long generation, int user, boolean visible) { }
            @Override public void onStateChanged(boolean active) { }
        });
        mLooper.dispatchAll();
        assertTrue(mController.isActive(0));
    }
    @Test public void accessibilityFilterOwnershipFailsOpenAndRecovers() {
        initialize();
        mController.setAccessibilityInputFilter(false);
        assertTrue(mController.isActive(0));
        mController.setAccessibilityInputFilter(true);
        assertFalse(mController.isActive(0));
        mController.setAccessibilityInputFilter(false);
        assertTrue(mController.isActive(0));
    }
    @Test public void settingsAndSessionEligibilityStillFailOpen() {
        initialize();
        mRequested = false;
        mController.setSession(1, 0, 0, 0, true);
        assertFalse(mController.isActive(0));
        mRequested = true;
        mAccessibility = true;
        mController.setSession(1, 0, 0, 0, true);
        assertFalse(mController.isActive(0));
        mAccessibility = false;
        mOneHanded = true;
        mController.setSession(1, 0, 0, 0, true);
        assertFalse(mController.isActive(0));
        mOneHanded = false;
        mController.setSession(1, 0, 0, 0, true);
        assertTrue(mController.isActive(0));
        mController.release();
        assertFalse(mController.isActive(0));
    }
    @Test public void dumpReportsGatesWithoutChangingGeneration() {
        initialize();
        long generation = mController.generation();
        StringWriter output = new StringWriter();
        mController.dump(new PrintWriter(output), "  ");
        String dump = output.toString();
        assertTrue(dump.contains("active=true requested=true generation=" + generation));
        assertTrue(dump.contains("session: task=1 user=0 display=0 rotation=0 eligible=true"));
        assertTrue(dump.contains("input: task=1 user=0 rotation=0 eligible=true"));
        assertTrue(dump.contains("bound=true listenerPresent=true"));
        assertTrue(dump.contains("accessibilityInputFilterInstalled=false"));
        assertTrue(dump.contains("accessibilityEnabled=false oneHandedEnabled=false"));
        assertEquals(generation, mController.generation());
    }

    @Test public void replacementQueuedBeforeCommandPreventsBothEffects() {
        initialize();
        long generation = mController.generation();
        mHandler.post(() -> session(200));
        mController.turnOff(generation, 0);
        mLooper.dispatchAll();
        assertTrue(mEffects.isEmpty());
    }
    @Test public void slowSettingWriteCannotLetQueuedReplacementOvertakeCommand() {
        initialize();
        mDuringWrite = () -> mHandler.post(() -> session(200));
        mController.turnOff(mController.generation(), 0);
        mLooper.dispatchAll();
        assertEquals(List.of("setting:100", "command:100"), mEffects);
        assertEquals(200, mProcess);
    }
    @Test public void wrongUserCannotChangeEitherEffect() {
        initialize();
        mController.turnOff(mController.generation(), 10);
        mLooper.dispatchAll();
        assertTrue(mEffects.isEmpty());
    }
}
