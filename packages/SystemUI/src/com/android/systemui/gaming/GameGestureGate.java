// SPDX-License-Identifier: Apache-2.0
package com.android.systemui.gaming;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.GameGestureLock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import com.android.internal.policy.SystemBarUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.ToIntFunction;

/** Buffers only top-edge expansion candidates; taps and ordinary holds retain complete streams. */
public final class GameGestureGate {
    interface Authority {
        int begin(MotionEvent event);
        boolean recognize(MotionEvent event);
        void end(MotionEvent event);
    }
    static final long DECISION_MS = 100;
    private static final ThreadLocal<Boolean> sReplaying = ThreadLocal.withInitial(() -> false);
    private final Handler mHandler;
    private final Authority mAuthority;
    private final BiPredicate<Context, MotionEvent> mCandidate;
    private final ToIntFunction<Context> mSlop;
    private final LongSupplier mClock;
    private final LongSupplier mGeneration;
    private final Map<String, Stream> mStreams = new HashMap<>();

    private static final class Stream {
        final ArrayList<MotionEvent> held = new ArrayList<>();
        final float x;
        final float y;
        final Consumer<MotionEvent> replay;
        final long down;
        final long generation;
        boolean forwarded;
        boolean reinject;
        boolean dropping;
        boolean recognized;
        boolean candidate = true;
        Runnable deadline;
        Stream(MotionEvent event, Consumer<MotionEvent> replay, long generation) {
            x = event.getX(); y = event.getY(); this.replay = replay;
            down = event.getDownTime(); this.generation = generation;
        }
    }

    public GameGestureGate() {
        this(new Authority() {
            @Override public int begin(MotionEvent event) {
                return GameGestureLock.begin(GameGestureLock.SHADE, event);
            }
            @Override public boolean recognize(MotionEvent event) {
                return GameGestureLock.recognize(GameGestureLock.SHADE, event);
            }
            @Override public void end(MotionEvent event) { GameGestureLock.end(GameGestureLock.SHADE, event); }
        }, new Handler(Looper.getMainLooper()), (context, event) ->
                isTouchscreenFinger(event)
                        && event.getRawY() <= SystemBarUtils.getStatusBarHeight(context),
                context -> ViewConfiguration.get(context).getScaledTouchSlop(), SystemClock::uptimeMillis,
                GameGestureLock::generation);
    }

    GameGestureGate(Authority authority, Handler handler, BiPredicate<Context, MotionEvent> candidate,
            ToIntFunction<Context> slop, LongSupplier clock, LongSupplier generation) {
        mAuthority = authority; mHandler = handler; mCandidate = candidate; mSlop = slop; mClock = clock;
        mGeneration = generation;
    }

    static boolean isTouchscreenFinger(MotionEvent event) {
        return event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)
                && event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER;
    }

    public boolean onTouch(Context context, MotionEvent event, Consumer<MotionEvent> replay) {
        // A replay crosses the original view/controller path once, bypassing every nested gate.
        if (sReplaying.get()) return false;
        String key = event.getDisplayId() + ":" + event.getDeviceId() + ":" + event.getDownTime();
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            if (!mCandidate.test(context, event)) return false;
            long generation = mGeneration.getAsLong();
            int decision = mAuthority.begin(event);
            if (decision == GameGestureLock.INACTIVE) return false;
            Stream stream = new Stream(event, replay, generation);
            stream.forwarded = decision == GameGestureLock.ALLOW;
            mStreams.put(key, stream);
            if (!stream.forwarded) {
                stream.deadline = () -> {
                    if (mStreams.get(key) == stream && !stream.forwarded && !stream.dropping) forward(stream);
                };
                mHandler.postDelayed(stream.deadline,
                        Math.max(0, DECISION_MS - (mClock.getAsLong() - event.getDownTime())));
            }
        }
        Stream stream = mStreams.get(key);
        if (stream == null) return false;
        boolean terminal = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL;
        if (stream.generation != mGeneration.getAsLong() && !stream.dropping) {
            if (stream.forwarded) cancel(stream, event);
            stream.dropping = true;
            clear(stream);
        }
        if (!stream.dropping && stream.candidate && !stream.recognized) {
            float dy = event.getY() - stream.y;
            float dx = Math.abs(event.getX() - stream.x);
            int slop = mSlop.applyAsInt(context);
            boolean directQs = action == MotionEvent.ACTION_POINTER_DOWN && event.getPointerCount() == 2;
            if (directQs || (action == MotionEvent.ACTION_MOVE && dy > slop && dy > dx)) {
                stream.recognized = true;
                if (mAuthority.recognize(event)) {
                    if (stream.forwarded) {
                        cancel(stream, event);
                    }
                    stream.dropping = true;
                    clear(stream);
                }
            } else if (dx > slop || -dy > slop) {
                stream.candidate = false;
                if (!stream.forwarded) forward(stream);
            }
        }
        boolean consumed;
        if (stream.dropping) consumed = true;
        else if (stream.forwarded) {
            if (stream.reinject) replay(stream, event);
            consumed = stream.reinject;
        } else {
            stream.held.add(MotionEvent.obtain(event));
            if (stream.held.size() > 64) stream.held.remove(1).recycle();
            if (terminal) forward(stream);
            consumed = true;
        }
        if (terminal) {
            mAuthority.end(event);
            mStreams.remove(key);
            if (stream.deadline != null) mHandler.removeCallbacks(stream.deadline);
            clear(stream);
        }
        return consumed;
    }

    private void cancel(Stream stream, MotionEvent event) {
        MotionEvent cancel = MotionEvent.obtain(event);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        replay(stream, cancel);
        cancel.recycle();
    }
    private void forward(Stream stream) {
        if (stream.generation != mGeneration.getAsLong()
                || mClock.getAsLong() - stream.down > 1000) {
            stream.dropping = true;
            clear(stream);
            return;
        }
        if (stream.deadline != null) mHandler.removeCallbacks(stream.deadline);
        stream.forwarded = true;
        stream.reinject = true;
        for (MotionEvent event : stream.held) replay(stream, event);
        clear(stream);
    }
    private void replay(Stream stream, MotionEvent event) {
        boolean previous = sReplaying.get();
        sReplaying.set(true);
        try { stream.replay.accept(event); }
        finally { sReplaying.set(previous); }
    }
    private void clear(Stream stream) {
        for (MotionEvent event : stream.held) event.recycle();
        stream.held.clear();
    }
}
