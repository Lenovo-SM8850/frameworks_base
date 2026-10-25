// SPDX-License-Identifier: Apache-2.0
package com.android.systemui.statusbar.policy;

import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Handler;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** Combines full-UID codec selection and playback activity from complete privileged snapshots. */
public final class DolbyAtmosController {
    private final AudioManager mAudioManager;
    private final Handler mHandler;
    private final Consumer<Boolean> mCallback;
    private final Set<Integer> mActiveUids = new HashSet<>();
    private final Set<Integer> mJocUids = new HashSet<>();
    private boolean mStarted;
    private boolean mVisible;
    private long mSnapshotGeneration;

    private final AudioManager.AudioPlaybackCallback mPlaybackCallback =
            new AudioManager.AudioPlaybackCallback() {
                @Override
                public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                    ++mSnapshotGeneration;
                    replaceSnapshot(configs);
                }
            };

    public DolbyAtmosController(AudioManager audioManager, Handler handler,
            Consumer<Boolean> callback) {
        mAudioManager = audioManager;
        mHandler = handler;
        mCallback = callback;
    }

    /** Register first, then recover the complete state after SystemUI initialization. */
    public void start() {
        mHandler.post(() -> {
            if (mStarted) return;
            mStarted = true;
            final long generation = mSnapshotGeneration;
            mAudioManager.registerAudioPlaybackCallback(mPlaybackCallback, mHandler);
            querySnapshot(generation);
        });
    }

    /** Refresh after a user lifecycle change without filtering the all-UID predicate. */
    public void refresh() {
        mHandler.post(() -> {
            if (mStarted) querySnapshot(mSnapshotGeneration);
        });
    }

    private void querySnapshot(long generation) {
        List<AudioPlaybackConfiguration> configs =
                mAudioManager.getActivePlaybackConfigurations();
        // A newer callback wins if it arrives during registration or the initial query.
        if (generation == mSnapshotGeneration) replaceSnapshot(configs);
    }

    private void replaceSnapshot(List<AudioPlaybackConfiguration> configs) {
        mActiveUids.clear();
        mJocUids.clear();
        for (AudioPlaybackConfiguration config : configs) {
            final int uid = config.getClientUid();
            if (uid < 0) continue;
            if (config.isActive()) {
                mActiveUids.add(uid);
            }
            if (config.hasStartedJocCodecForUid()) mJocUids.add(uid);
        }
        boolean visible = false;
        for (int uid : mJocUids) {
            if (mActiveUids.contains(uid)) {
                visible = true;
                break;
            }
        }
        if (mVisible != visible) {
            mVisible = visible;
            mCallback.accept(visible);
        }
    }
}
