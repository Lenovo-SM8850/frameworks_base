// SPDX-License-Identifier: Apache-2.0
package com.android.server.input;

import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;
import android.view.GameGestureLock;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import com.android.server.wm.GameGestureLockController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/** Bottom-up hold before launcher receipt. All work, including deadlines, holds IMS's filter lock. */
final class GameGestureInputFilter {
    interface Sender { void send(MotionEvent event, int flags); }
    interface Authority {
        int begin(MotionEvent event);
        boolean recognize(MotionEvent event);
        long generation();
        void end(MotionEvent event, boolean cancelled);
    }
    static final long DECISION_MS = 100;
    static final long MAX_REPLAY_AGE_MS = 1000;
    private final Sender mSender;
    private final Authority mAuthority;
    private final Handler mHandler;
    private final Object mLock;
    private final Predicate<MotionEvent> mCandidate;
    private final LongSupplier mClock;
    private final int mSlop;
    private final Map<String, Stream> mStreams = new HashMap<>();

    private static final class Held {
        final MotionEvent event;
        final int flags;
        Held(MotionEvent event, int flags) { this.event = MotionEvent.obtain(event); this.flags = flags; }
    }
    private static final class Stream {
        final ArrayList<Held> held = new ArrayList<>();
        final float x;
        final float y;
        final long down;
        final long generation;
        boolean recognized;
        boolean candidate = true;
        boolean dropping;
        boolean forwarded;
        boolean allowed;
        boolean reinject;
        Runnable deadline;
        Stream(MotionEvent down, long generation) {
            x = down.getX(); y = down.getY(); this.down = down.getDownTime();
            this.generation = generation;
        }
    }

    GameGestureInputFilter(Context context, Sender sender, Handler handler, Object lock) {
        this(sender, new Authority() {
            @Override public int begin(MotionEvent event) {
                GameGestureLockController c = GameGestureLockController.get();
                return c == null ? GameGestureLock.INACTIVE : c.begin(GameGestureLock.HOME,
                        event.getDisplayId(), event.getDeviceId(), event.getDownTime());
            }
            @Override public boolean recognize(MotionEvent event) {
                GameGestureLockController c = GameGestureLockController.get();
                return c != null && c.recognize(GameGestureLock.HOME, event.getDisplayId(),
                        event.getDeviceId(), event.getDownTime());
            }
            @Override public long generation() {
                GameGestureLockController c = GameGestureLockController.get();
                return c != null ? c.generation() : -1;
            }
            @Override public void end(MotionEvent event, boolean cancelled) {
                GameGestureLockController c = GameGestureLockController.get();
                if (c != null) c.end(GameGestureLock.HOME, event.getDisplayId(), event.getDeviceId(),
                        event.getDownTime(), event.getEventTime(), cancelled);
            }
        }, handler, lock, GameGestureInputFilter::isCandidate,
                ViewConfiguration.get(context).getScaledTouchSlop(), SystemClock::uptimeMillis);
    }

    GameGestureInputFilter(Sender sender, Authority authority, Handler handler, Object lock,
            Predicate<MotionEvent> candidate, int slop, LongSupplier clock) {
        mSender = sender; mAuthority = authority; mHandler = handler; mLock = lock;
        mCandidate = candidate; mSlop = slop; mClock = clock;
    }

    private static boolean isCandidate(MotionEvent event) {
        GameGestureLockController controller = GameGestureLockController.get();
        return controller != null && controller.isHomeGestureCandidate(event);
    }

    private String key(MotionEvent event) {
        return event.getDisplayId() + ":" + event.getDeviceId() + ":" + event.getDownTime();
    }

    boolean filter(MotionEvent event, int flags) {
        String key = key(event);
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            if (!mCandidate.test(event)) return false;
            long generation = mAuthority.generation();
            int decision = mAuthority.begin(event);
            if (decision == GameGestureLock.INACTIVE) return false;
            Stream stream = new Stream(event, generation);
            stream.forwarded = decision == GameGestureLock.ALLOW;
            stream.allowed = stream.forwarded;
            mStreams.put(key, stream);
            if (!stream.forwarded) {
                stream.deadline = () -> {
                    synchronized (mLock) {
                        if (mStreams.get(key) == stream && !stream.dropping && !stream.forwarded) {
                            if (stream.generation != mAuthority.generation()
                                    || mClock.getAsLong() - stream.down > MAX_REPLAY_AGE_MS) {
                                stream.dropping = true;
                                clearHeld(stream);
                            } else forward(stream);
                        }
                    }
                };
                mHandler.postDelayed(stream.deadline,
                        Math.max(0, DECISION_MS - (mClock.getAsLong() - event.getDownTime())));
            }
        }
        Stream stream = mStreams.get(key);
        if (stream == null) return false;
        boolean terminal = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL;
        boolean valid = stream.generation == mAuthority.generation();
        if (!valid && stream.allowed) {
            // A confirmed gesture may itself open Recents and end the lock; let it finish.
            stream.candidate = false;
        } else if (!valid && !stream.dropping) {
            if (stream.forwarded) cancel(event, flags);
            stream.dropping = true;
            clearHeld(stream);
        }
        if (!stream.dropping && stream.candidate && !stream.recognized) {
            float dx = Math.abs(event.getX() - stream.x);
            float up = stream.y - event.getY();
            if (action == MotionEvent.ACTION_MOVE && up > mSlop && up > dx) {
                stream.recognized = true;
                if (mAuthority.recognize(event)) {
                    if (stream.forwarded) cancel(event, flags);
                    stream.dropping = true;
                    clearHeld(stream);
                }
            } else if (action == MotionEvent.ACTION_POINTER_DOWN || dx > mSlop || -up > mSlop) {
                stream.candidate = false;
                if (!stream.forwarded) forward(stream);
            }
        }
        boolean consumed;
        if (stream.dropping) consumed = true;
        else if (stream.forwarded) {
            if (stream.reinject) mSender.send(event, flags);
            consumed = stream.reinject;
        } else {
            stream.held.add(new Held(event, flags));
            if (stream.held.size() > 64) stream.held.remove(1).event.recycle();
            if (terminal) forward(stream);
            consumed = true;
        }
        if (terminal) {
            if (valid) mAuthority.end(event, action == MotionEvent.ACTION_CANCEL);
            mStreams.remove(key);
            recycle(stream);
        }
        return consumed;
    }

    private void cancel(MotionEvent event, int flags) {
        MotionEvent cancel = MotionEvent.obtain(event);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        mSender.send(cancel, flags);
        cancel.recycle();
    }
    private void forward(Stream stream) {
        if (mClock.getAsLong() - stream.down > MAX_REPLAY_AGE_MS
                || stream.generation != mAuthority.generation()) {
            stream.dropping = true;
            clearHeld(stream);
            return;
        }
        if (stream.deadline != null) mHandler.removeCallbacks(stream.deadline);
        stream.forwarded = true;
        stream.reinject = true;
        for (Held held : stream.held) mSender.send(held.event, held.flags);
        clearHeld(stream);
    }
    void reset() {
        // IMS resets native streams before changing accessibility filter ownership.
        for (Stream stream : mStreams.values()) recycle(stream);
        mStreams.clear();
    }
    private void recycle(Stream stream) {
        if (stream.deadline != null) mHandler.removeCallbacks(stream.deadline);
        clearHeld(stream);
    }
    private void clearHeld(Stream stream) {
        for (Held held : stream.held) held.event.recycle();
        stream.held.clear();
    }
}
