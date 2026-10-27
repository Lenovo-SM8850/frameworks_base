// SPDX-License-Identifier: Apache-2.0
package com.android.server.wm;

import static android.view.GameGestureLock.REVEAL;
import static android.view.GameGestureLock.SHADE;

import java.util.HashMap;
import java.util.Map;

/** Handler-independent confirmation state; the owner serializes access. */
final class GameGestureLockState {
    static final long MIN_REPEAT_MS = 250;
    static final long MAX_REPEAT_MS = 2000;
    private final long[] mCompleted = {-1, -1, -1, -1, -1};
    private final Map<String, Attempt> mStreams = new HashMap<>();
    private boolean mStatusBarVisible;
    private boolean mShadeUnlocked;
    private boolean mRevealSeenVisible;
    private long mRevealDown;

    private static final class Attempt {
        final int kind;
        boolean allowed;
        long reservedEnd = -1;
        boolean recognized;
        boolean ended;
        long endTime;
        boolean cancelled;
        Attempt(int kind) { this.kind = kind; }
    }

    // Confirmations are per kind; a confirmed reveal separately grants shade access.
    private String key(int kind, int device, long down) { return kind + ":" + device + ":" + down; }
    private boolean ready(int kind, long down) {
        long end = mCompleted[kind];
        return end >= 0 && down - end >= MIN_REPEAT_MS && down - end <= MAX_REPEAT_MS;
    }

    boolean begin(int kind, int device, long down) {
        final String key = key(kind, device, down);
        Attempt attempt = mStreams.get(key);
        if (attempt != null) return attempt.allowed;
        if (mStreams.size() >= 160) mStreams.entrySet().removeIf(e -> e.getValue().ended);
        attempt = new Attempt(kind);
        if ((kind == SHADE
                || kind == REVEAL && mStatusBarVisible) && isShadeUnlocked(down)) {
            attempt.allowed = true;
        } else if (ready(kind, down)) {
            attempt.reservedEnd = mCompleted[kind];
            mCompleted[kind] = -1;
            attempt.allowed = true;
        }
        mStreams.put(key, attempt);
        return attempt.allowed;
    }

    boolean recognize(int kind, int device, long down) {
        begin(kind, device, down);
        Attempt attempt = mStreams.get(key(kind, device, down));
        if (!attempt.recognized) {
            attempt.recognized = true;
            if (kind == REVEAL && attempt.allowed && !attempt.cancelled) {
                mShadeUnlocked = true;
                mRevealSeenVisible = mStatusBarVisible;
                mRevealDown = down;
                mCompleted[SHADE] = -1;
            }
            if (attempt.allowed && mCompleted[kind] == attempt.reservedEnd) mCompleted[kind] = -1;
            if (attempt.ended) complete(attempt, down);
        }
        return !attempt.allowed;
    }

    boolean end(int kind, int device, long down, long end, boolean cancelled) {
        Attempt attempt = mStreams.get(key(kind, device, down));
        if (attempt == null || attempt.ended) return false;
        attempt.ended = true;
        attempt.endTime = end;
        attempt.cancelled = cancelled;
        return complete(attempt, down);
    }

    private boolean complete(Attempt attempt, long down) {
        if (attempt.allowed) {
            if ((!attempt.recognized || attempt.cancelled) && mCompleted[attempt.kind] < 0) {
                mCompleted[attempt.kind] = attempt.reservedEnd;
            }
            return false;
        }
        if (!attempt.recognized || attempt.cancelled) return false;
        long previous = mCompleted[attempt.kind];
        if (previous < 0 || down - previous > MAX_REPEAT_MS) mCompleted[attempt.kind] = attempt.endTime;
        return true;
    }

    boolean isStatusBarVisible() { return mStatusBarVisible; }

    boolean isShadeUnlocked() { return mShadeUnlocked; }

    boolean isShadeUnlocked(long down) {
        if (mShadeUnlocked && !mRevealSeenVisible && down - mRevealDown > MAX_REPEAT_MS) {
            mShadeUnlocked = false;
        }
        return mShadeUnlocked;
    }

    void setStatusBarVisible(boolean visible) {
        mStatusBarVisible = visible;
        if (visible && mShadeUnlocked) mRevealSeenVisible = true;
        if (!visible && mRevealSeenVisible) {
            mShadeUnlocked = false;
            mRevealSeenVisible = false;
            mCompleted[SHADE] = -1;
        }
    }

    void reset() {
        mShadeUnlocked = false;
        mRevealSeenVisible = false;
        java.util.Arrays.fill(mCompleted, -1);
        mStreams.clear();
    }
}
