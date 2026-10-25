// SPDX-License-Identifier: Apache-2.0
package com.android.systemui.statusbar.policy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Handler;
import android.os.test.TestLooper;

import androidx.test.filters.SmallTest;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class DolbyAtmosControllerTest {
    private final TestLooper mLooper = new TestLooper();
    private final Handler mHandler = new Handler(mLooper.getLooper());
    private final AudioManager mAudioManager = mock(AudioManager.class);
    private final List<Boolean> mVisibility = new ArrayList<>();
    private DolbyAtmosController mController;
    private AudioManager.AudioPlaybackCallback mCallback;

    private static AudioPlaybackConfiguration player(int uid, int piid,
            boolean active, boolean joc) {
        AudioPlaybackConfiguration config = mock(AudioPlaybackConfiguration.class);
        when(config.getClientUid()).thenReturn(uid);
        when(config.getPlayerInterfaceId()).thenReturn(piid);
        when(config.isActive()).thenReturn(active);
        when(config.hasStartedJocCodecForUid()).thenReturn(joc);
        return config;
    }

    @Before
    public void setUp() {
        when(mAudioManager.getActivePlaybackConfigurations()).thenReturn(List.of());
        mController = new DolbyAtmosController(mAudioManager, mHandler, mVisibility::add);
        mController.start();
        mLooper.dispatchAll();
        ArgumentCaptor<AudioManager.AudioPlaybackCallback> callback =
                ArgumentCaptor.forClass(AudioManager.AudioPlaybackCallback.class);
        verify(mAudioManager).registerAudioPlaybackCallback(callback.capture(), eq(mHandler));
        mCallback = callback.getValue();
    }

    private void snapshot(AudioPlaybackConfiguration... configs) {
        mHandler.post(() -> mCallback.onPlaybackConfigChanged(List.of(configs)));
        mLooper.dispatchAll();
    }

    @Test
    public void sameUidIntersectionShowsButDifferentUidDoesNot() {
        snapshot(player(10001, 1, false, true), player(10002, 2, true, false));
        assertTrue(mVisibility.isEmpty());
        snapshot(player(10001, 1, false, true), player(10001, 2, true, false));
        assertEquals(List.of(true), mVisibility);
    }

    @Test
    public void pauseAndResumeUsePlaybackWithoutAnotherCodecStart() {
        snapshot(player(10001, 1, true, true));
        snapshot(player(10001, 1, false, true));
        snapshot(player(10001, 1, true, true));
        assertEquals(List.of(true, false, true), mVisibility);
    }

    @Test
    public void emptySnapshotClearsLastActivePlayer() {
        snapshot(player(10001, 1, true, true));
        snapshot();
        snapshot();
        assertEquals(List.of(true, false), mVisibility);
    }

    @Test
    public void lastCodecStopHidesWhileOrdinaryPlaybackContinues() {
        snapshot(player(10001, 1, true, true));
        snapshot(player(10001, 1, true, false));
        assertEquals(List.of(true, false), mVisibility);
    }

    @Test
    public void multiplePlayersAndDuplicateSnapshotsStayVisible() {
        snapshot(player(10001, 1, true, true), player(10001, 2, true, true));
        snapshot(player(10001, 2, true, true));
        snapshot(player(10001, 2, true, true));
        assertEquals(List.of(true), mVisibility);
    }

    @Test
    public void invalidAndDifferentAndroidUserUidsDoNotIntersect() {
        snapshot(player(-1, 1, true, true));
        snapshot(player(10001, 1, false, true), player(1010001, 2, true, false));
        assertTrue(mVisibility.isEmpty());
    }

    @Test
    public void refreshRecoversFullSnapshot() {
        when(mAudioManager.getActivePlaybackConfigurations())
                .thenReturn(List.of(player(10001, 1, true, true)));
        mController.refresh();
        mLooper.dispatchAll();
        assertEquals(List.of(true), mVisibility);
    }

    @Test
    public void callbackWinsOverStaleQueryResult() {
        doAnswer(invocation -> {
            mCallback.onPlaybackConfigChanged(List.of(player(10001, 1, true, true)));
            return List.of();
        }).when(mAudioManager).getActivePlaybackConfigurations();
        mController.refresh();
        mLooper.dispatchAll();
        assertEquals(List.of(true), mVisibility);
    }

    @Test
    public void startRegistersOnceAndRecoversInitialSnapshot() {
        mController.start();
        mLooper.dispatchAll();
        verify(mAudioManager, times(1)).registerAudioPlaybackCallback(mCallback, mHandler);
        AudioManager restoredAudio = mock(AudioManager.class);
        when(restoredAudio.getActivePlaybackConfigurations())
                .thenReturn(List.of(player(10001, 1, true, true)));
        DolbyAtmosController restored =
                new DolbyAtmosController(restoredAudio, mHandler, mVisibility::add);
        restored.start();
        mLooper.dispatchAll();
        assertEquals(List.of(true), mVisibility);
    }
}
