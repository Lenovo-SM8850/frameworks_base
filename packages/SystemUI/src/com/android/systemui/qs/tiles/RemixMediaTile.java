/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.tiles;

import static com.android.internal.logging.MetricsLogger.VIEW_UNKNOWN;

import android.content.Intent;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.quicksettings.Tile;
import android.view.KeyEvent;
import android.widget.Switch;

import androidx.annotation.Nullable;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.animation.Expandable;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.res.R;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

public class RemixMediaTile extends QSTileImpl<BooleanState> {

    public static final String TILE_SPEC = "remixmedia";

    private final MediaSessionManager mSessionManager;
    private final List<MediaController> mControllers = new ArrayList<>();
    private boolean mListening;
    private volatile boolean mPlaying;

    private Icon mIcon = null;

    private final MediaSessionManager.OnActiveSessionsChangedListener mSessionsListener =
            this::updateControllers;

    private final MediaController.Callback mControllerCallback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(@Nullable PlaybackState state) {
            updatePlaying();
        }

        @Override
        public void onSessionDestroyed() {
            updatePlaying();
        }
    };

    @Inject
    public RemixMediaTile(
            QSHost host,
            QsEventLogger uiEventLogger,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            MediaSessionManager sessionManager
    ) {
        super(host, uiEventLogger, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);
        mSessionManager = sessionManager;
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void handleSetListening(boolean listening) {
        super.handleSetListening(listening);
        if (mListening == listening) return;
        mListening = listening;
        if (listening) {
            mSessionManager.addOnActiveSessionsChangedListener(mSessionsListener, null, mHandler);
            updateControllers(mSessionManager.getActiveSessions(null));
        } else {
            mSessionManager.removeOnActiveSessionsChangedListener(mSessionsListener);
            clearControllers();
            mPlaying = false;
        }
    }

    private void updateControllers(@Nullable List<MediaController> controllers) {
        clearControllers();
        if (controllers != null) {
            for (MediaController controller : controllers) {
                controller.registerCallback(mControllerCallback, mHandler);
                mControllers.add(controller);
            }
        }
        updatePlaying();
    }

    private void clearControllers() {
        for (MediaController controller : mControllers) {
            controller.unregisterCallback(mControllerCallback);
        }
        mControllers.clear();
    }

    private void updatePlaying() {
        boolean playing = false;
        for (MediaController controller : mControllers) {
            PlaybackState state = controller.getPlaybackState();
            if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                playing = true;
                break;
            }
        }
        if (playing != mPlaying) {
            mPlaying = playing;
            refreshState();
        }
    }

    @Override
    protected void handleClick(@Nullable Expandable expandable) {
        AudioManager audio = mContext.getSystemService(AudioManager.class);
        long now = SystemClock.uptimeMillis();
        audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0));
        audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0));
    }

    @Override
    public Intent getLongClickIntent() {
        return null;
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.qs_media_tile_play_pause_label);
    }

    @Override
    public int getMetricsCategory() {
        return VIEW_UNKNOWN;
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        if (mIcon == null) {
            mIcon = maybeLoadResourceIcon(R.drawable.ic_now_playing_note);
        }
        state.icon = mIcon;
        state.label = getTileLabel();
        state.contentDescription = state.label;
        state.value = mPlaying;
        state.state = state.value ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
        state.expandedAccessibilityClassName = Switch.class.getName();
    }
}
