// SPDX-License-Identifier: Apache-2.0
package com.android.systemui.gaming;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import android.os.Handler;
import android.os.test.TestLooper;
import android.view.GameGestureLock;
import android.view.InputDevice;
import android.view.MotionEvent;
import androidx.test.filters.SmallTest;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;

@SmallTest
public class GameGestureGateTest {
    private final TestLooper mLooper = new TestLooper();
    private final List<Integer> mSent = new ArrayList<>();
    private long mTime = 1000;
    private long mGeneration = 1;
    private int mRecognitions;
    private int mBegins;
    private final GameGestureGate mGate = new GameGestureGate(new GameGestureGate.Authority() {
        @Override public int begin(MotionEvent event) { mBegins++; return GameGestureLock.BLOCK; }
        @Override public boolean recognize(MotionEvent event) { mRecognitions++; return true; }
        @Override public void end(MotionEvent event) { }
    }, new Handler(mLooper.getLooper()), (context, event) ->
            GameGestureGate.isTouchscreenFinger(event) && event.getRawY() <= 50,
            context -> 8, () -> mTime, () -> mGeneration);

    private boolean event(int action, float x, float y, int source) {
        MotionEvent.PointerProperties properties = new MotionEvent.PointerProperties();
        properties.id = 0;
        properties.toolType = source == InputDevice.SOURCE_STYLUS ? MotionEvent.TOOL_TYPE_STYLUS
                : source == InputDevice.SOURCE_MOUSE ? MotionEvent.TOOL_TYPE_MOUSE : MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
        coords.x = x; coords.y = y;
        MotionEvent event = MotionEvent.obtain(1000, mTime, action, 1,
                new MotionEvent.PointerProperties[] {properties}, new MotionEvent.PointerCoords[] {coords},
                0, 0, 1, 1, 0, 0, source, 0, 0);
        boolean consumed = mGate.onTouch(null, event, replay -> mSent.add(replay.getActionMasked()));
        event.recycle();
        return consumed;
    }
    private boolean touch(int action, float x, float y) {
        return event(action, x, y, InputDevice.SOURCE_TOUCHSCREEN);
    }
    private void advance(long ms) {
        mTime += ms; mLooper.moveTimeForward(ms); mLooper.dispatchAll();
    }
    @Test public void topEdgeTapReplaysCompleteStreamWithoutArming() {
        assertTrue(touch(MotionEvent.ACTION_DOWN, 100, 10));
        mTime += 20;
        assertTrue(touch(MotionEvent.ACTION_UP, 100, 10));
        assertEquals(List.of(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), mSent);
        assertEquals(0, mRecognitions);
    }
    @Test public void headsUpTapOutsideExpansionRegionPassesUnchanged() {
        assertFalse(touch(MotionEvent.ACTION_DOWN, 100, 100));
        assertFalse(touch(MotionEvent.ACTION_UP, 100, 100));
        assertEquals(0, mBegins);
    }
    @Test public void mouseAndStylusAreNotExpansionCandidates() {
        assertFalse(event(MotionEvent.ACTION_DOWN, 100, 10, InputDevice.SOURCE_MOUSE));
        assertFalse(event(MotionEvent.ACTION_UP, 100, 10, InputDevice.SOURCE_MOUSE));
        assertFalse(event(MotionEvent.ACTION_DOWN, 100, 10, InputDevice.SOURCE_STYLUS));
        assertEquals(0, mBegins);
    }
    @Test public void downwardFirstGestureIsAbsorbed() {
        touch(MotionEvent.ACTION_DOWN, 100, 10);
        mTime += 20;
        assertTrue(touch(MotionEvent.ACTION_MOVE, 100, 100));
        touch(MotionEvent.ACTION_UP, 100, 100);
        assertTrue(mSent.isEmpty());
        assertEquals(1, mRecognitions);
    }
    @Test public void holdGetsDownAtDeadlineThenCancelBeforeDownwardMove() {
        touch(MotionEvent.ACTION_DOWN, 100, 10);
        advance(100);
        assertEquals(List.of(MotionEvent.ACTION_DOWN), mSent);
        touch(MotionEvent.ACTION_MOVE, 100, 100);
        touch(MotionEvent.ACTION_UP, 100, 100);
        assertEquals(List.of(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), mSent);
    }
    @Test public void sidewaysCandidateKeepsCompleteStream() {
        touch(MotionEvent.ACTION_DOWN, 100, 10);
        touch(MotionEvent.ACTION_MOVE, 200, 10);
        touch(MotionEvent.ACTION_UP, 200, 10);
        assertEquals(List.of(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP), mSent);
        assertEquals(0, mRecognitions);
    }
    @Test public void staleGenerationDoesNotReplayIntoReplacementWindow() {
        touch(MotionEvent.ACTION_DOWN, 100, 10);
        mGeneration++;
        advance(100);
        touch(MotionEvent.ACTION_UP, 100, 10);
        assertTrue(mSent.isEmpty());
    }
}
