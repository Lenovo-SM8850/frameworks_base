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

import com.android.systemui.Dependency;

import io.github.miner7222.systemui.lyrics.LyricState;
import io.github.miner7222.systemui.lyrics.LyricSyncRepository;

import java.util.HashMap;
import java.util.Map;

public class LyricsFetcher {
    private static LyricsFetcher sInstance;

    public static synchronized LyricsFetcher getInstance(Context context) {
        if (sInstance == null) sInstance = new LyricsFetcher();
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

    private final LyricSyncRepository mRepository = Dependency.get(LyricSyncRepository.class);
    private final Map<Callback, LyricSyncRepository.Listener> mListeners = new HashMap<>();

    private LyricsFetcher() {}

    public synchronized void addCallback(Callback callback) {
        if (mListeners.containsKey(callback)) return;
        LyricSyncRepository.Listener listener = state -> {
            if (!state.getLines().isEmpty()) {
                int index = state.isPlaying() ? state.getActiveIndex() : -1;
                callback.onSyncedLineChanged(line(state, index - 1), line(state, index),
                        index >= 0 ? line(state, index + 1) : null);
            } else if (state.getPlainLyrics() != null) {
                callback.onPlainLyricsAvailable(state.getPlainLyrics());
            } else {
                callback.onLyricsCleared();
            }
        };
        mListeners.put(callback, listener);
        mRepository.addListener(listener);
    }

    public synchronized void removeCallback(Callback callback) {
        LyricSyncRepository.Listener listener = mListeners.remove(callback);
        if (listener != null) mRepository.removeListener(listener);
    }

    private static String line(LyricState state, int index) {
        if (index < 0 || index >= state.getLines().size()) return null;
        String text = state.getLines().get(index).getText();
        return text.isEmpty() ? null : text;
    }
}
