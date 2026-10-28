/*
 * Copyright (C) 2024-2026 Lunaris AOSP
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

package com.android.systemui.statusbar.phone;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.android.systemui.Dependency;
import com.android.systemui.statusbar.quickactions.media.domain.interactor.LyricState;
import com.android.systemui.statusbar.quickactions.media.domain.interactor.MediaControlChipInteractor;
import com.android.systemui.util.kotlin.JavaAdapter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import kotlinx.coroutines.Job;

/**
 * Passes the lyrics that {@link MediaControlChipInteractor} looks up for the media session, with
 * the line playing, to the status bar.
 */
public class LyricsFetcher {

    private static volatile LyricsFetcher sInstance;

    public static synchronized LyricsFetcher getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new LyricsFetcher();
        }
        return sInstance;
    }

    public static class LyricLine {
        public final long timestampMs;
        public final String text;

        public LyricLine(long timestampMs, String text) {
            this.timestampMs = timestampMs;
            this.text = text;
        }
    }

    public interface Callback {
        void onSyncedLineChanged(String prevLine, String currentLine, String nextLine);
        void onPlainLyricsAvailable(String plainLyrics);
        void onLyricsCleared();
    }

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private final Set<Callback> mCallbacks = new CopyOnWriteArraySet<>();
    private boolean mStarted;
    private Job mLyricStateJob;

    private List<com.android.systemui.statusbar.quickactions.media.domain.interactor.LyricLine> mStateLines =
            Collections.emptyList();
    private List<LyricLine> mCurrentLines = Collections.emptyList();
    private String mLastPlainLyrics;
    private int mLastActiveIndex = -1;
    private boolean mPlainLyricsDelivered;

    private LyricsFetcher() {
    }

    public void addCallback(Callback callback) {
        mMainHandler.post(() -> {
            mCallbacks.add(callback);
            if (!mStarted) {
                mStarted = true;
                startInternal();
            } else {
                replayCurrentStateTo(callback);
            }
        });
    }


    public void removeCallback(Callback callback) {
        mMainHandler.post(() -> {
            mCallbacks.remove(callback);
            if (mCallbacks.isEmpty() && mStarted) {
                mStarted = false;
                stopInternal();
            }
        });
    }

    private void replayCurrentStateTo(Callback callback) {
        if (!mCurrentLines.isEmpty()) {
            if (mLastActiveIndex >= 0) {
                String prev = mLastActiveIndex > 0
                        ? mCurrentLines.get(mLastActiveIndex - 1).text : null;
                String current = mCurrentLines.get(mLastActiveIndex).text;
                String next = mLastActiveIndex + 1 < mCurrentLines.size()
                        ? mCurrentLines.get(mLastActiveIndex + 1).text : null;
                callback.onSyncedLineChanged(prev, current, next);
            }
        } else if (mPlainLyricsDelivered && mLastPlainLyrics != null) {
            callback.onPlainLyricsAvailable(mLastPlainLyrics);
        }
    }

    private void startInternal() {
        mLyricStateJob = Dependency.get(JavaAdapter.class).alwaysCollectFlow(
                Dependency.get(MediaControlChipInteractor.class).getLyricState(),
                this::onLyricStateChanged);
    }

    private void stopInternal() {
        if (mLyricStateJob != null) {
            mLyricStateJob.cancel(null);
            mLyricStateJob = null;
        }
        clearLyrics();
    }

    private void clearLyrics() {
        mStateLines = Collections.emptyList();
        mCurrentLines = Collections.emptyList();
        mLastPlainLyrics = null;
        mLastActiveIndex = -1;
        mPlainLyricsDelivered = false;
        for (Callback cb : mCallbacks) cb.onLyricsCleared();
    }

    private void onLyricStateChanged(LyricState state) {
        if (!state.getLines().equals(mStateLines)) {
            if (!mCurrentLines.isEmpty()) clearLyrics();
            mStateLines = state.getLines();
            List<LyricLine> lines = new ArrayList<>(mStateLines.size());
            for (com.android.systemui.statusbar.quickactions.media.domain.interactor.LyricLine line : mStateLines) {
                lines.add(new LyricLine(line.getTimestampMs(), line.getText()));
            }
            mCurrentLines = lines;
        }
        String plainLyrics = state.getPlainLyrics();
        if (!mCurrentLines.isEmpty()) {
            dispatchPlaybackUpdate(state);
        } else if (!TextUtils.isEmpty(plainLyrics)) {
            if (!TextUtils.equals(plainLyrics, mLastPlainLyrics)) {
                mPlainLyricsDelivered = true;
                mLastPlainLyrics = plainLyrics;
                for (Callback cb : mCallbacks) cb.onPlainLyricsAvailable(plainLyrics);
            }
        } else if (mPlainLyricsDelivered) {
            clearLyrics();
        }
    }

    private void dispatchPlaybackUpdate(LyricState state) {
        if (!state.isPlaying()) {
            if (mLastActiveIndex != -1) {
                mLastActiveIndex = -1;
                for (Callback cb : mCallbacks) cb.onSyncedLineChanged(null, null, null);
            }
            return;
        }

        int activeIndex = state.getActiveIndex();

        if (activeIndex != mLastActiveIndex) {
            mLastActiveIndex = activeIndex;
            String prev = activeIndex > 0
                    ? mCurrentLines.get(activeIndex - 1).text : null;
            String current = activeIndex >= 0
                    ? mCurrentLines.get(activeIndex).text : null;
            String next = activeIndex >= 0 && activeIndex + 1 < mCurrentLines.size()
                    ? mCurrentLines.get(activeIndex + 1).text : null;
            String prevOut = TextUtils.isEmpty(prev) ? null : prev;
            String currentOut = TextUtils.isEmpty(current) ? null : current;
            String nextOut = TextUtils.isEmpty(next) ? null : next;
            for (Callback cb : mCallbacks) {
                cb.onSyncedLineChanged(prevOut, currentOut, nextOut);
            }
        }
    }
}
