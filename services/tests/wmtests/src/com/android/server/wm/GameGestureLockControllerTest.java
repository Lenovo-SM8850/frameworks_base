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
            @Override public void onNotice(long generation, int user, boolean visible) { }
            @Override public void onStateChanged(boolean active) { }
        });
        mController.setTurnOffCallback(() -> mEffects.add("command:" + mProcess));
        session(100);
        mLooper.dispatchAll();
        assertTrue(mController.isActive(0));
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
