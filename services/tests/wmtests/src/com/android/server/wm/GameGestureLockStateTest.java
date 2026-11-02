// SPDX-License-Identifier: Apache-2.0
package com.android.server.wm;

import static android.view.WindowManagerPolicyConstants.NAV_BAR_MODE_2BUTTON;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_MODE_3BUTTON;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.GameGestureLock;
import android.view.MotionEvent;

import androidx.test.filters.SmallTest;
import org.junit.Test;

@SmallTest
public class GameGestureLockStateTest {
    private static final int BACK = 0;
    private static final int HOME = 1;
    private static final int REVEAL = 4;
    private final GameGestureLockState mState = new GameGestureLockState();

    private void first(int kind, long down, long end) {
        assertFalse(mState.begin(kind, 0, down));
        assertTrue(mState.recognize(kind, 0, down));
        assertTrue(mState.end(kind, 0, down, end, false));
    }

    private void reveal() {
        first(REVEAL, 1000, 1100);
        assertTrue(mState.begin(REVEAL, 0, 1350));
        assertFalse(mState.recognize(REVEAL, 0, 1350));
    }

    @Test public void confirmedRevealAllowsShadeOnSameAndLaterStreamsUntilHidden() {
        reveal();
        assertTrue(mState.begin(GameGestureLock.SHADE, 0, 1350));
        assertFalse(mState.recognize(GameGestureLock.SHADE, 0, 1350));
        mState.setStatusBarVisible(true);
        assertTrue(mState.begin(GameGestureLock.SHADE, 1, 5000));
        assertFalse(mState.recognize(GameGestureLock.SHADE, 1, 5000));
        assertTrue(mState.begin(REVEAL, 0, 5100));
        assertFalse(mState.recognize(REVEAL, 0, 5100));
        assertFalse(mState.begin(HOME, 0, 5000));
        assertFalse(mState.begin(BACK, 0, 5000));
        mState.setStatusBarVisible(false);
        assertFalse(mState.begin(GameGestureLock.SHADE, 0, 6000));
        assertFalse(mState.begin(REVEAL, 0, 6000));
    }

    @Test public void pendingRevealSurvivesHiddenSnapshotUntilBarBecomesVisible() {
        reveal();
        mState.setStatusBarVisible(false);
        assertTrue(mState.isShadeUnlocked());
        mState.setStatusBarVisible(true);
        mState.setStatusBarVisible(false);
        assertFalse(mState.isShadeUnlocked());
        // A late second observer of the same reveal must not reopen the dismissed bar.
        assertFalse(mState.recognize(REVEAL, 0, 1350));
        assertFalse(mState.isShadeUnlocked());
    }

    @Test public void revealThatNeverShowsCannotLeaveShadeUnlocked() {
        reveal();
        mState.setStatusBarVisible(false);
        assertFalse(mState.begin(GameGestureLock.SHADE, 0, 3351));
        assertFalse(mState.isShadeUnlocked());
    }

    @Test public void firstOrUnrecognizedOrCancelledRevealDoesNotUnlockShade() {
        first(REVEAL, 1000, 1100);
        assertFalse(mState.isShadeUnlocked());
        assertTrue(mState.begin(REVEAL, 0, 1350));
        mState.setStatusBarVisible(true);
        assertFalse(mState.isShadeUnlocked());
        mState.end(REVEAL, 0, 1350, 1450, true);
        assertFalse(mState.recognize(REVEAL, 0, 1350));
        assertFalse(mState.isShadeUnlocked());
    }

    @Test public void confirmedHomeSwipeKeepsBottomOpenUntilTheBarHides() {
        first(HOME, 1000, 1100);
        assertFalse(mState.isHomeUnlocked(1200));
        assertTrue(mState.begin(HOME, 0, 1350));
        assertFalse(mState.recognize(HOME, 0, 1350));
        assertTrue(mState.isHomeUnlocked(1400));
        mState.setNavigationBarVisible(true);
        assertTrue(mState.isHomeUnlocked(9000));
        mState.setNavigationBarVisible(false);
        assertFalse(mState.isHomeUnlocked(9100));
    }

    @Test public void homeSwipeWithoutARevealedBarExpires() {
        first(HOME, 1000, 1100);
        assertTrue(mState.begin(HOME, 0, 1350));
        assertFalse(mState.recognize(HOME, 0, 1350));
        assertFalse(mState.isHomeUnlocked(3351));
    }

    @Test public void resetRevokesRevealedShadeAndVisibleBarAloneDoesNotUnlock() {
        reveal();
        mState.setStatusBarVisible(true);
        mState.reset();
        assertFalse(mState.isShadeUnlocked());
        assertFalse(mState.begin(GameGestureLock.SHADE, 0, 1700));
        mState.setStatusBarVisible(true);
        assertFalse(mState.isShadeUnlocked());
    }

    @Test public void threeButtonNavigationRevealsAlwaysBypassConfirmation() {
        // Bottom in portrait, right in landscape, left in seascape. Bar visibility must not matter.
        for (int edge = 2; edge <= 4; edge++) {
            assertEquals(GameGestureLock.NONE,
                    GameGestureLock.transientKind(NAV_BAR_MODE_3BUTTON, edge, false));
            assertEquals(GameGestureLock.NONE,
                    GameGestureLock.transientKind(NAV_BAR_MODE_3BUTTON, edge, true));
        }
    }

    @Test public void topRevealAndShadeStayProtectedInEveryNavigationMode() {
        for (int mode : new int[] {NAV_BAR_MODE_3BUTTON, NAV_BAR_MODE_2BUTTON,
                NAV_BAR_MODE_GESTURAL}) {
            assertEquals(REVEAL, GameGestureLock.transientKind(mode, 1, false));
            assertEquals(GameGestureLock.SHADE, GameGestureLock.transientKind(mode, 1, true));
        }
    }

    @Test public void gestureNavigationKindsStillRequireConfirmation() {
        assertEquals(HOME, GameGestureLock.transientKind(NAV_BAR_MODE_GESTURAL, 2, false));
        assertEquals(BACK, GameGestureLock.transientKind(NAV_BAR_MODE_GESTURAL, 3, false));
        assertEquals(BACK, GameGestureLock.transientKind(NAV_BAR_MODE_GESTURAL, 4, false));
        first(GameGestureLock.transientKind(NAV_BAR_MODE_GESTURAL, 2, false), 1000, 1100);
        assertTrue(mState.begin(HOME, 0, 1350));
    }

    @Test public void bottomInputConfirmationIsExclusiveToGestureNavigation() {
        assertFalse(GameGestureLock.isGestureNavigation(NAV_BAR_MODE_3BUTTON));
        assertFalse(GameGestureLock.isGestureNavigation(NAV_BAR_MODE_2BUTTON));
        assertTrue(GameGestureLock.isGestureNavigation(NAV_BAR_MODE_GESTURAL));
    }

    @Test public void twoButtonRevealPolicyIsPreserved() {
        for (int edge = 2; edge <= 4; edge++) {
            assertEquals(REVEAL, GameGestureLock.transientKind(NAV_BAR_MODE_2BUTTON, edge, false));
        }
    }

    @Test public void firstAttemptNeedsCompletion() {
        assertFalse(mState.begin(BACK, 0, 1000));
        assertTrue(mState.recognize(BACK, 0, 1000));
        assertFalse(mState.begin(BACK, 1, 1400));
        assertTrue(mState.end(BACK, 0, 1000, 1500, false));
        assertFalse(mState.begin(BACK, 0, 1749));
        assertTrue(mState.begin(BACK, 0, 1750));
    }

    @Test public void minimumIsInclusive() {
        first(BACK, 1000, 1100);
        assertFalse(mState.begin(BACK, 0, 1349));
        assertTrue(mState.begin(BACK, 0, 1350));
    }

    @Test public void maximumIsInclusive() {
        first(BACK, 1000, 1100);
        assertTrue(mState.begin(BACK, 0, 3100));
    }

    @Test public void expiryStartsNewFirst() {
        first(BACK, 1000, 1100);
        first(BACK, 3101, 3200);
        assertTrue(mState.begin(BACK, 0, 3450));
    }

    @Test public void earlyRepeatDoesNotExtendDeadline() {
        first(BACK, 1000, 1100);
        first(BACK, 1200, 1300);
        assertTrue(mState.begin(BACK, 0, 1350));
    }

    @Test public void earlyRepeatDoesNotExtendExpiry() {
        first(BACK, 1000, 1100);
        first(BACK, 1200, 1300);
        assertFalse(mState.begin(BACK, 0, 3101));
    }

    @Test public void secondIsConsumedOnlyOnce() {
        first(HOME, 1000, 1100);
        assertTrue(mState.begin(HOME, 0, 1350));
        assertFalse(mState.recognize(HOME, 0, 1350));
        assertFalse(mState.end(HOME, 0, 1350, 1500, false));
        assertFalse(mState.begin(HOME, 0, 1800));
    }

    @Test public void kindsAreIndependent() {
        first(BACK, 1000, 1100);
        assertFalse(mState.begin(HOME, 0, 1350));
        assertTrue(mState.begin(BACK, 0, 1351));
    }

    @Test public void tapDoesNotArm() {
        assertFalse(mState.begin(BACK, 0, 1000));
        assertFalse(mState.end(BACK, 0, 1000, 1100, false));
        assertFalse(mState.begin(BACK, 0, 1350));
    }

    @Test public void cancelledFirstDoesNotArm() {
        assertFalse(mState.begin(BACK, 0, 1000));
        assertTrue(mState.recognize(BACK, 0, 1000));
        assertFalse(mState.end(BACK, 0, 1000, 1100, true));
        assertFalse(mState.begin(BACK, 0, 1350));
    }

    @Test public void unrecognizedSecondReturnsReservation() {
        first(BACK, 1000, 1100);
        assertTrue(mState.begin(BACK, 0, 1350));
        assertFalse(mState.end(BACK, 0, 1350, 1400, false));
        assertTrue(mState.begin(BACK, 0, 1500));
    }

    @Test public void cancelledSecondReturnsReservation() {
        first(BACK, 1000, 1100);
        assertTrue(mState.begin(BACK, 0, 1350));
        assertFalse(mState.recognize(BACK, 0, 1350));
        assertFalse(mState.end(BACK, 0, 1350, 1400, true));
        assertTrue(mState.begin(BACK, 0, 1500));
    }

    @Test public void physicalStreamIsCountedOnce() {
        assertFalse(mState.begin(BACK, 0, 1000));
        assertTrue(mState.recognize(BACK, 0, 1000));
        assertTrue(mState.recognize(REVEAL, 0, 1000));
        assertTrue(mState.end(BACK, 0, 1000, 1100, false));
        assertFalse(mState.end(BACK, 0, 1000, 1100, false));
        assertFalse(mState.begin(REVEAL, 0, 1350));
        assertTrue(mState.begin(BACK, 0, 1351));
    }

    @Test public void firstRecognizerChoosesDeniedCandidateKind() {
        assertFalse(mState.begin(BACK, 0, 1000));
        assertTrue(mState.recognize(REVEAL, 0, 1000));
        assertTrue(mState.end(REVEAL, 0, 1000, 1100, false));
        assertFalse(mState.begin(BACK, 0, 1350));
        assertTrue(mState.begin(REVEAL, 0, 1351));
    }

    @Test public void delayedRecognitionUsesOriginalCompletion() {
        assertFalse(mState.begin(BACK, 0, 1000));
        assertFalse(mState.end(BACK, 0, 1000, 1100, false));
        assertTrue(mState.recognize(BACK, 0, 1000));
        assertTrue(mState.begin(BACK, 0, 1350));
    }

    @Test public void differentRecognizedKindCannotSpendReservation() {
        first(BACK, 1000, 1100);
        assertTrue(mState.begin(BACK, 0, 1350));
        assertTrue(mState.recognize(REVEAL, 0, 1350));
        assertTrue(mState.end(REVEAL, 0, 1350, 1400, false));
        assertFalse(mState.end(BACK, 0, 1350, 1400, false));
        assertTrue(mState.begin(BACK, 0, 1500));
    }

    @Test public void unrelatedDetectorCancellationDoesNotEndBack() {
        assertFalse(mState.begin(BACK, 0, 1000));
        assertTrue(mState.recognize(BACK, 0, 1000));
        assertFalse(mState.end(REVEAL, 0, 1000, 1050, true));
        assertTrue(mState.end(BACK, 0, 1000, 1100, false));
        assertTrue(mState.begin(BACK, 0, 1350));
    }

    @Test public void newGenerationDropsOldCompletion() {
        assertFalse(mState.begin(BACK, 0, 1000));
        assertTrue(mState.recognize(BACK, 0, 1000));
        mState.reset();
        assertFalse(mState.end(BACK, 0, 1000, 1100, false));
        assertFalse(mState.begin(BACK, 0, 1350));
    }
    @Test public void allowedStreamDoesNotAuthorizeAnotherKind() {
        first(REVEAL, 1000, 1100);
        assertTrue(mState.begin(REVEAL, 0, 1350));
        assertFalse(mState.recognize(REVEAL, 0, 1350));
        assertFalse(mState.begin(GameGestureLock.THREE_FINGER, 0, 1350));
        assertTrue(mState.recognize(GameGestureLock.THREE_FINGER, 0, 1350));
        assertTrue(mState.end(GameGestureLock.THREE_FINGER, 0, 1350, 1450, false));
        assertTrue(mState.begin(GameGestureLock.THREE_FINGER, 0, 1700));
    }

    @Test public void transientTrackerEndsItsClassifiedKindOnUpAndCancel() {
        GameGestureLock.TransientTracker tracker = new GameGestureLock.TransientTracker();
        for (int kind : new int[] {BACK, HOME, GameGestureLock.SHADE, REVEAL}) {
            MotionEvent event = MotionEvent.obtain(1000, 1050, MotionEvent.ACTION_MOVE, 1, 1000, 0);
            tracker.record(kind, event);
            event.setAction(MotionEvent.ACTION_UP);
            assertEquals(kind, tracker.finish(event));
            assertEquals(GameGestureLock.NONE, tracker.finish(event));
            event.setAction(MotionEvent.ACTION_MOVE);
            tracker.record(kind, event);
            event.setAction(MotionEvent.ACTION_CANCEL);
            assertEquals(kind, tracker.finish(event));
            event.recycle();
        }
    }

    @Test public void cornerBottomRevealArmsHomeWithoutInputFilterCompletion() {
        MotionEvent event = MotionEvent.obtain(1000, 1050, MotionEvent.ACTION_MOVE, 1, 1000, 0);
        GameGestureLock.TransientTracker tracker = new GameGestureLock.TransientTracker();
        int kind = GameGestureLock.transientKind(NAV_BAR_MODE_GESTURAL, 2, false);
        tracker.record(kind, event);
        assertTrue(mState.recognize(kind, 0, 1000));
        event.setAction(MotionEvent.ACTION_UP);
        assertTrue(mState.end(tracker.finish(event), 0, 1000, 1100, false));
        assertTrue(mState.begin(HOME, 0, 1350));
        event.recycle();
    }

    @Test public void blockedStreamIdentityRejectsForeignDeviceDisplayAndGeneration() {
        MotionEvent event = MotionEvent.obtain(1000, 1100, MotionEvent.ACTION_MOVE,
                20, 20, 1, 1, 0, 1, 1, 7, 0);
        event.setDisplayId(0);
        assertTrue(GameGestureLock.matchesStream(event, 0, 7, 1000, 3, 3));
        assertFalse(GameGestureLock.matchesStream(event, 1, 7, 1000, 3, 3));
        assertFalse(GameGestureLock.matchesStream(event, 0, 8, 1000, 3, 3));
        assertFalse(GameGestureLock.matchesStream(event, 0, 7, 1001, 3, 3));
        assertFalse(GameGestureLock.matchesStream(event, 0, 7, 1000, 3, 4));
        event.setAction(MotionEvent.ACTION_CANCEL);
        event.setDisplayId(1);
        assertFalse(GameGestureLock.matchesStream(event, 0, 7, 1000, 3, 3));
        event.recycle();
    }

    @Test public void foreignTransientTerminalDoesNotClearPrimaryStream() {
        GameGestureLock.TransientTracker tracker = new GameGestureLock.TransientTracker();
        MotionEvent primary = MotionEvent.obtain(1000, 1050, MotionEvent.ACTION_MOVE, 1, 1000, 0);
        tracker.record(HOME, primary);
        MotionEvent foreign = MotionEvent.obtain(primary);
        foreign.setDisplayId(1);
        foreign.setAction(MotionEvent.ACTION_CANCEL);
        assertEquals(GameGestureLock.NONE, tracker.finish(foreign));
        primary.setAction(MotionEvent.ACTION_UP);
        assertEquals(HOME, tracker.finish(primary));
        foreign.recycle();
        primary.recycle();
    }

}
