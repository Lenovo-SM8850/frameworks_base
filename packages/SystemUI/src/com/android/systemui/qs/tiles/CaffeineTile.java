/*
 * Copyright (C) 2016 The CyanogenMod Project
 * Copyright (c) 2017 The LineageOS Project
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

package com.android.systemui.qs.tiles;

import static com.android.internal.logging.MetricsLogger.VIEW_UNKNOWN;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.quicksettings.Tile;

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
import com.android.systemui.statusbar.policy.CaffeineController;

import javax.inject.Inject;

/** Quick settings tile: Caffeine **/
public class CaffeineTile extends QSTileImpl<BooleanState> {

    public static final String TILE_SPEC = "caffeine";

    @Nullable
    private Icon mIcon = null;

    private final CaffeineController mCaffeineController;
    private final Runnable mCallback = this::refreshState;
    private static int[] DURATIONS = new int[] {
        5 * 60,   // 5 min
        10 * 60,  // 10 min
        30 * 60,  // 30 min
        -1,       // infinity
    };
    private static final int INFINITE_DURATION_INDEX = DURATIONS.length - 1;
    public long mLastClickTime = -1;

    @Inject
    public CaffeineTile(
            QSHost host,
            QsEventLogger uiEventLogger,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            CaffeineController caffeineController
    ) {
        super(host, uiEventLogger, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);
        mCaffeineController = caffeineController;
        mCaffeineController.addCallback(mCallback);
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    protected void handleDestroy() {
        super.handleDestroy();
        mCaffeineController.removeCallback(mCallback);
    }

    @Override
    public void handleSetListening(boolean listening) {
    }

    @Override
    protected void handleClick(@Nullable Expandable expandable) {
        // Rapid clicks cycle through the same durations, including infinity and off.
        if (mCaffeineController.isEnabled() && mLastClickTime != -1
                && SystemClock.elapsedRealtime() - mLastClickTime < 5000) {
            int duration = 0;
            while (duration < DURATIONS.length
                    && DURATIONS[duration] != mCaffeineController.getDurationSeconds()) {
                duration++;
            }
            duration++;
            if (duration >= DURATIONS.length) {
                mCaffeineController.setEnabled(false);
            } else {
                mCaffeineController.start(DURATIONS[duration]);
            }
        } else {
            mCaffeineController.setEnabled(!mCaffeineController.isEnabled());
        }
        mLastClickTime = SystemClock.elapsedRealtime();
        refreshState();
    }

    @Override
    protected void handleLongClick(@Nullable Expandable expandable) {
        if (mCaffeineController.isEnabled()
                && mCaffeineController.getDurationSeconds() == DURATIONS[INFINITE_DURATION_INDEX]) {
            return;
        }
        mCaffeineController.start(DURATIONS[INFINITE_DURATION_INDEX]);
        refreshState();
    }

    @Override
    public Intent getLongClickIntent() {
        return null;
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_caffeine_label);
    }

    @Override
    public int getMetricsCategory() {
        return VIEW_UNKNOWN;
    }

    private String formatValueWithRemainingTime() {
        int secondsRemaining = mCaffeineController.getSecondsRemaining();
        if (secondsRemaining == -1) {
            return "\u221E"; // infinity
        }
        return String.format("%02d:%02d", secondsRemaining / 60 % 60, secondsRemaining % 60);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        state.value = mCaffeineController.isEnabled();
        if (mIcon == null) {
            mIcon = maybeLoadResourceIcon(R.drawable.ic_qs_caffeine);
        }
        state.icon = mIcon;
        state.label = mContext.getString(R.string.quick_settings_caffeine_label);
        state.hasLongClickEffect = false;
        if (state.value) {
            state.secondaryLabel = formatValueWithRemainingTime();
            state.contentDescription =  mContext.getString(
                    R.string.accessibility_quick_settings_caffeine_on);
            state.state = Tile.STATE_ACTIVE;
        } else {
            state.secondaryLabel = null;
            state.contentDescription =  mContext.getString(
                    R.string.accessibility_quick_settings_caffeine_off);
            state.state = Tile.STATE_INACTIVE;
        }
    }

}
