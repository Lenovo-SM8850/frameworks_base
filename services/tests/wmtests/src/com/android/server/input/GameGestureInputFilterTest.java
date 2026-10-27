// SPDX-License-Identifier: Apache-2.0
package com.android.server.input;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import android.os.Handler;
import android.os.test.TestLooper;
import android.view.GameGestureLock;
import android.view.MotionEvent;
import androidx.test.filters.SmallTest;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;

@SmallTest
public class GameGestureInputFilterTest {
    private final TestLooper mLooper = new TestLooper();
    private final Handler mHandler = new Handler(mLooper.getLooper());
    private final List<Integer> mSent = new ArrayList<>();
    private long mTime = 1000;
    private long mGeneration = 1;
    private int mRecognitions;
    private int mBegins;
    private int mEnds;
    private int mDecision = GameGestureLock.BLOCK;
    private boolean mCandidate = true;
    private final GameGestureInputFilter mFilter = new GameGestureInputFilter(
            (event, flags) -> mSent.add(event.getActionMasked()), new GameGestureInputFilter.Authority() {
                @Override public int begin(MotionEvent event) { mBegins++; return mDecision; }
                @Override public boolean recognize(MotionEvent event) { mRecognitions++; return mDecision == GameGestureLock.BLOCK; }
                @Override public long generation() { return mGeneration; }
                @Override public void end(MotionEvent event, boolean cancelled) { mEnds++; }
            }, mHandler, new Object(), event -> mCandidate, 8, () -> mTime);

    private boolean event(int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(1000, mTime, action, x, y, 0);
        boolean consumed = mFilter.filter(event, 0);
        event.recycle();
        return consumed;
    }
    private void advance(long ms) {
        mTime += ms;
        mLooper.moveTimeForward(ms);
        mLooper.dispatchAll();
    }

    @Test public void blockedBottomSwipeCompletesBeforeNextAllowedSwipePasses() {
        assertTrue(event(MotionEvent.ACTION_DOWN, 100, 1903));
        mTime += 20;
        assertTrue(event(MotionEvent.ACTION_MOVE, 100, 1800));
        assertTrue(event(MotionEvent.ACTION_UP, 100, 1800));
        assertEquals(1, mRecognitions);
        assertEquals(1, mEnds);
        assertTrue(mSent.isEmpty());
        mDecision = GameGestureLock.ALLOW;
        mTime += 350;
        assertFalse(event(MotionEvent.ACTION_DOWN, 100, 1903));
        assertFalse(event(MotionEvent.ACTION_MOVE, 100, 1800));
        assertFalse(event(MotionEvent.ACTION_UP, 100, 1800));
        assertEquals(2, mRecognitions);
        assertEquals(2, mEnds);
        assertTrue(mSent.isEmpty());
    }

    @Test public void inactiveSessionNeverHoldsOrRecognizesBottomInput() {
        mDecision = GameGestureLock.INACTIVE;
        assertFalse(event(MotionEvent.ACTION_DOWN, 100, 1903));
        assertFalse(event(MotionEvent.ACTION_MOVE, 100, 1800));
        assertFalse(event(MotionEvent.ACTION_UP, 100, 1800));
        assertEquals(0, mRecognitions);
        assertEquals(0, mEnds);
    }

    @Test public void stationaryDownReplaysAtBoundedDeadlineAndContinuesInOrder() {
        assertTrue(event(MotionEvent.ACTION_DOWN, 100, 1000));
        assertTrue(mSent.isEmpty());
        advance(100);
        assertEquals(List.of(MotionEvent.ACTION_DOWN), mSent);
        advance(500);
        assertTrue(event(MotionEvent.ACTION_MOVE, 101, 1000));
        assertTrue(event(MotionEvent.ACTION_UP, 101, 1000));
        assertEquals(List.of(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP), mSent);
        assertEquals(0, mRecognitions);
    }
    @Test public void laterUpwardGestureCancelsForwardedDownBeforeMove() {
        event(MotionEvent.ACTION_DOWN, 100, 1000);
        advance(100);
        assertTrue(event(MotionEvent.ACTION_MOVE, 100, 900));
        assertTrue(event(MotionEvent.ACTION_UP, 100, 900));
        assertEquals(List.of(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), mSent);
        assertEquals(1, mRecognitions);
    }
    @Test public void firstUpwardGestureIsNeverReplayed() {
        event(MotionEvent.ACTION_DOWN, 100, 1000);
        mTime += 20;
        assertTrue(event(MotionEvent.ACTION_MOVE, 100, 900));
        advance(100);
        event(MotionEvent.ACTION_UP, 100, 900);
        assertTrue(mSent.isEmpty());
    }
    @Test public void secondGesturePassesImmediatelyWithoutReinjection() {
        mDecision = GameGestureLock.ALLOW;
        assertFalse(event(MotionEvent.ACTION_DOWN, 100, 1000));
        assertFalse(event(MotionEvent.ACTION_MOVE, 100, 900));
        assertFalse(event(MotionEvent.ACTION_UP, 100, 900));
        assertTrue(mSent.isEmpty());
        assertEquals(1, mRecognitions);
    }
    @Test public void ineligibleModeNeverCallsAuthorityOrHoldsInput() {
        mCandidate = false;
        assertFalse(event(MotionEvent.ACTION_DOWN, 100, 1000));
        advance(500);
        assertFalse(event(MotionEvent.ACTION_UP, 100, 1000));
        assertEquals(0, mBegins);
        assertTrue(mSent.isEmpty());
    }
    @Test public void starvedDeadlineDoesNotReplayStaleDown() {
        event(MotionEvent.ACTION_DOWN, 100, 1000);
        advance(2000);
        event(MotionEvent.ACTION_UP, 100, 1000);
        assertTrue(mSent.isEmpty());
    }
    @Test public void generationChangeDiscardsPendingStream() {
        event(MotionEvent.ACTION_DOWN, 100, 1000);
        mGeneration++;
        advance(100);
        event(MotionEvent.ACTION_UP, 100, 1000);
        assertTrue(mSent.isEmpty());
    }
    @Test public void resetCancelsDeadline() {
        event(MotionEvent.ACTION_DOWN, 100, 1000);
        mFilter.reset();
        advance(100);
        assertTrue(mSent.isEmpty());
    }
    @Test public void cancellationBeforeDeadlineKeepsCompleteStream() {
        event(MotionEvent.ACTION_DOWN, 100, 1000);
        event(MotionEvent.ACTION_CANCEL, 100, 1000);
        advance(100);
        assertEquals(List.of(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), mSent);
    }
    @Test public void cancellationAfterDeadlineKeepsCompleteStream() {
        event(MotionEvent.ACTION_DOWN, 100, 1000);
        advance(100);
        event(MotionEvent.ACTION_CANCEL, 100, 1000);
        assertEquals(List.of(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), mSent);
    }

}
