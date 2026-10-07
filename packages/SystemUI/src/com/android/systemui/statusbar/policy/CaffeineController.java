/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.policy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;

import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;

import java.util.concurrent.CopyOnWriteArrayList;

import javax.inject.Inject;

/** Owns Caffeine independently of whether its QS tile exists or is listening. */
@SysUISingleton
public class CaffeineController {
    public static final int DEFAULT_DURATION_SECONDS = 5 * 60;
    public static final int INFINITE_DURATION = -1;

    private final PowerManager.WakeLock mWakeLock;
    private final Handler mHandler;
    private final CopyOnWriteArrayList<Runnable> mCallbacks = new CopyOnWriteArrayList<>();
    private int mDurationSeconds;
    private long mDeadline;
    private final Runnable mTick = this::tick;

    @Inject
    public CaffeineController(PowerManager powerManager, @Main Handler handler,
            BroadcastDispatcher broadcastDispatcher) {
        mHandler = handler;
        mWakeLock = powerManager.newWakeLock(PowerManager.FULL_WAKE_LOCK, "CaffeineTile");
        mWakeLock.setReferenceCounted(false);
        broadcastDispatcher.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // The power button must also cancel an infinite session.
                if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                    setEnabled(false);
                }
            }
        }, new IntentFilter(Intent.ACTION_SCREEN_OFF));
    }

    public void addCallback(Runnable callback) {
        mCallbacks.addIfAbsent(callback);
        mHandler.post(callback);
    }

    public void removeCallback(Runnable callback) {
        mCallbacks.remove(callback);
    }

    public synchronized boolean isEnabled() {
        return mWakeLock.isHeld();
    }

    public synchronized int getDurationSeconds() {
        return mDurationSeconds;
    }

    public synchronized int getSecondsRemaining() {
        if (!mWakeLock.isHeld()) return 0;
        if (mDurationSeconds == INFINITE_DURATION) return INFINITE_DURATION;
        return (int) Math.max(0, (mDeadline - SystemClock.elapsedRealtime() + 999) / 1000);
    }

    public synchronized void setEnabled(boolean enabled) {
        if (enabled) {
            if (!mWakeLock.isHeld()) start(DEFAULT_DURATION_SECONDS);
        } else {
            mHandler.removeCallbacks(mTick);
            if (mWakeLock.isHeld()) mWakeLock.release();
            mDurationSeconds = 0;
            mDeadline = 0;
            notifyCallbacks();
        }
    }

    public synchronized void start(int durationSeconds) {
        if (durationSeconds != INFINITE_DURATION && durationSeconds <= 0) {
            throw new IllegalArgumentException("Caffeine duration must be positive or infinite");
        }
        mHandler.removeCallbacks(mTick);
        mDurationSeconds = durationSeconds;
        if (durationSeconds == INFINITE_DURATION) {
            mDeadline = 0;
            mWakeLock.acquire();
        } else {
            long durationMillis = durationSeconds * 1000L;
            mDeadline = SystemClock.elapsedRealtime() + durationMillis;
            // Keep expiry in this controller: a queued WakeLock timeout cannot distinguish
            // an old finite session from a newly extended or infinite one.
            mWakeLock.acquire();
            mHandler.postDelayed(mTick, Math.min(1000, durationMillis));
        }
        notifyCallbacks();
    }

    private synchronized void tick() {
        // A tick already dispatched on Main may race a QS change on its background looper.
        if (mDurationSeconds <= 0) return;
        mHandler.removeCallbacks(mTick);
        long remaining = mDeadline - SystemClock.elapsedRealtime();
        if (!mWakeLock.isHeld() || remaining <= 0) {
            setEnabled(false);
        } else {
            notifyCallbacks();
            mHandler.postDelayed(mTick, Math.min(1000, remaining));
        }
    }

    private void notifyCallbacks() {
        for (Runnable callback : mCallbacks) mHandler.post(callback);
    }
}
