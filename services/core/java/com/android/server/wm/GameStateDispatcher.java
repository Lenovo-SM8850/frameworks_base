/*
 * Copyright (C) 2025-2026 AxionOS
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
package com.android.server.wm;

import android.app.ActivityManager;
import android.app.IActivityManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.database.ContentObserver;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AtomicFile;
import android.util.Slog;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

import org.json.JSONObject;

import lineageos.health.HealthInterface;
import lineageos.providers.LineageSettings;

class GameStateDispatcher {
    private static final String TAG = "GameStateDispatcher";
    static final long GAME_VISIBILITY_GRACE_MS = 1500;
    static final long SESSION_RETRY_DELAY_MS = 1000;
    private static final String GAME_SPACE_PACKAGE = "io.chaldeaprjkt.gamespace";
    private static final String GAME_SPACE_SESSION_SERVICE =
            "io.chaldeaprjkt.gamespace.gamebar.SessionService";
    private static final ComponentName GAME_SPACE_SESSION_COMPONENT =
            new ComponentName(GAME_SPACE_PACKAGE, GAME_SPACE_SESSION_SERVICE);
    private static final String ACTION_GAME_START = "game_start";
    private static final String ACTION_GAME_VISIBILITY = "game_visibility";
    private static final String EXTRA_GAME_VISIBLE = "game_visible";
    private static final String EXTRA_PACKAGE_NAME = "package_name";
    private static final String KEY_GAMING_MODE_ACTIVE = "ax_gaming_mode_active";
    private static final String KEY_BYPASS_CHARGE_ENABLED = "bypass_charge_enabled";
    private static final String KEY_POWER_MODE_PERF_BY_USER = "power_mode_perf_by_user";
    private static final String KEY_POWER_MODE_PERF = "persist.sys.power_mode_perf";
    private static final String EXTRA_BYPASS_CHARGE_ACTIVE = "bypass_charge_active";
    private static final String[] CHARGE_SETTINGS = {
            LineageSettings.System.CHARGING_CONTROL_ENABLED,
            LineageSettings.System.CHARGING_CONTROL_MODE,
            LineageSettings.System.CHARGING_CONTROL_LIMIT,
    };

    private final Context mContext;
    private final IActivityManager mActivityManager;

    private final Handler mHandler;
    private final AtomicFile mSavedChargeControl;
    private final Supplier<HealthInterface> mHealth;
    private ServiceConnection mSessionConnection;
    private String mGamePackage;
    private int mGameUserId = UserHandle.USER_SYSTEM;
    private boolean mGameVisible;
    private boolean mGameUiVisible;
    private boolean mSessionReady;
    private boolean mBypassChargeActive;
    private boolean mInitialized;
    private Runnable mPendingGameHidden;
    private boolean mSessionRetryPending;
    private final Runnable mRetryGameSession = () -> {
        mSessionRetryPending = false;
        startGameSession();
    };

    GameStateDispatcher(Context context, Handler handler) {
        this(context, handler, ActivityManager.getService(),
                new AtomicFile(new File(Environment.getDataSystemDirectory(),
                        "gamespace-bypass.json")),
                () -> HealthInterface.getInstance(context));
    }

    GameStateDispatcher(Context context, Handler handler, IActivityManager activityManager,
            AtomicFile savedChargeControl, Supplier<HealthInterface> health) {
        mContext = context;
        mHandler = handler;
        mActivityManager = activityManager;
        mSavedChargeControl = savedChargeControl;
        mHealth = health;
    }

    void initialize() {
        if (mInitialized) {
            return;
        }
        if (!restoreChargingControl()) {
            mHandler.postDelayed(this::initialize, 1000);
            return;
        }
        mInitialized = true;
        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(KEY_BYPASS_CHARGE_ENABLED), false,
                new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        updateBypassCharge();
                    }
                }, UserHandle.USER_ALL);
        updateBypassCharge();
    }

    void dispatchGameState(boolean active, String packageName) {
        cancelPendingGameHidden();
        Settings.Secure.putIntForUser(mContext.getContentResolver(), KEY_GAMING_MODE_ACTIVE,
                active ? 1 : 0, UserHandle.USER_CURRENT);
        mGameVisible = active;
        if (active) {
            mGameUiVisible = true;
            mGamePackage = packageName;
            mGameUserId = ActivityManager.getCurrentUser();
            startGameSession();
        } else {
            setGameUiVisible(false);
            updateBypassCharge();
            stopGameSession();
            mGamePackage = null;
        }
    }

    void setGameVisible(boolean visible, Runnable onHidden) {
        setGameUiVisible(visible);
        if (visible) {
            cancelPendingGameHidden();
            mGameVisible = true;
            updateBypassCharge();
        } else if (mGameVisible && mPendingGameHidden == null) {
            // Keep the session and its original charging snapshot through short transitions.
            // Repeated hidden notifications must not extend the deadline.
            mPendingGameHidden = () -> {
                mPendingGameHidden = null;
                mGameVisible = false;
                updateBypassCharge();
                onHidden.run();
            };
            mHandler.postDelayed(mPendingGameHidden, GAME_VISIBILITY_GRACE_MS);
        }
    }

    private void setGameUiVisible(boolean visible) {
        if (mGameUiVisible == visible) {
            return;
        }
        mGameUiVisible = visible;
        if (mGamePackage == null || mGameUserId != ActivityManager.getCurrentUser()) {
            return;
        }
        // Use the same ordered service-start channel without ending the session grace.
        final Intent intent = new Intent(ACTION_GAME_VISIBILITY)
                .setComponent(GAME_SPACE_SESSION_COMPONENT)
                .putExtra(EXTRA_PACKAGE_NAME, mGamePackage)
                .putExtra(EXTRA_GAME_VISIBLE, visible);
        try {
            mActivityManager.startService(null, intent, null, false,
                    mContext.getOpPackageName(), mContext.getAttributionTag(), mGameUserId);
        } catch (RemoteException | RuntimeException e) {
            Slog.w(TAG, "Failed to update GameSpace UI visibility", e);
        }
    }

    private void cancelPendingGameHidden() {
        if (mPendingGameHidden != null) {
            mHandler.removeCallbacks(mPendingGameHidden);
            mPendingGameHidden = null;
        }
    }

    void boostGame(boolean enable) {
        final int perfByUser = Settings.System.getIntForUser(mContext.getContentResolver(),
                KEY_POWER_MODE_PERF_BY_USER, 0, UserHandle.USER_CURRENT);
        if (perfByUser == 1) {
            return;
        }

        Settings.System.putIntForUser(mContext.getContentResolver(), KEY_POWER_MODE_PERF,
                enable ? 1 : 0, UserHandle.USER_CURRENT);
        SystemProperties.set(KEY_POWER_MODE_PERF, enable ? "1" : "0");
    }

    private Intent gameSessionIntent() {
        return new Intent(ACTION_GAME_START)
                .setComponent(GAME_SPACE_SESSION_COMPONENT)
                .putExtra(EXTRA_PACKAGE_NAME, mGamePackage)
                .putExtra(EXTRA_GAME_VISIBLE, mGameUiVisible)
                .putExtra(EXTRA_BYPASS_CHARGE_ACTIVE, mBypassChargeActive);
    }

    private void startGameSession() {
        if (mGamePackage == null || !mGameVisible
                || mGameUserId != ActivityManager.getCurrentUser()) {
            return;
        }
        try {
            final ComponentName result = mActivityManager.startService(null,
                    gameSessionIntent(), null, false, mContext.getOpPackageName(),
                    mContext.getAttributionTag(), mGameUserId);
            if (result == null) {
                Slog.w(TAG, "GameSpace session service not found");
                scheduleGameSessionRetry();
                return;
            }
            if (mSessionConnection != null) {
                scheduleGameSessionRetry();
                updateBypassCharge();
                return;
            }
            final ServiceConnection connection = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    if (mSessionConnection != this) {
                        return;
                    }
                    cancelGameSessionRetry();
                    mSessionReady = true;
                    updateBypassCharge();
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    if (mSessionConnection != this) {
                        return;
                    }
                    mSessionReady = false;
                    updateBypassCharge();
                    // Keep a reconnectable binding; START_NOT_STICKY still needs a start request.
                    scheduleGameSessionRetry();
                }

                @Override
                public void onBindingDied(ComponentName name) {
                    if (mSessionConnection != this) {
                        return;
                    }
                    clearSessionConnection();
                    updateBypassCharge();
                    scheduleGameSessionRetry();
                }

                @Override
                public void onNullBinding(ComponentName name) {
                    onBindingDied(name);
                }
            };
            mSessionConnection = connection;
            if (!mContext.bindServiceAsUser(gameSessionIntent(), connection, 0,
                    mHandler, UserHandle.of(mGameUserId))) {
                mSessionConnection = null;
            }
            scheduleGameSessionRetry();
        } catch (RemoteException | RuntimeException e) {
            clearSessionConnection();
            updateBypassCharge();
            scheduleGameSessionRetry();
            Slog.w(TAG, "Failed to start GameSpace session", e);
        }
    }

    private void scheduleGameSessionRetry() {
        if (!mSessionReady && !mSessionRetryPending && mGameVisible && mGamePackage != null
                && mGameUserId == ActivityManager.getCurrentUser()) {
            mSessionRetryPending = true;
            mHandler.postDelayed(mRetryGameSession, SESSION_RETRY_DELAY_MS);
        }
    }

    private void cancelGameSessionRetry() {
        mHandler.removeCallbacks(mRetryGameSession);
        mSessionRetryPending = false;
    }

    private void clearSessionConnection() {
        mSessionReady = false;
        final ServiceConnection connection = mSessionConnection;
        mSessionConnection = null;
        if (connection != null) {
            try {
                mContext.unbindService(connection);
            } catch (IllegalArgumentException e) {
                Slog.w(TAG, "Failed to unbind GameSpace session", e);
            }
        }
    }

    private void stopGameSession() {
        cancelGameSessionRetry();
        clearSessionConnection();
        final Intent intent = new Intent().setComponent(GAME_SPACE_SESSION_COMPONENT);
        try {
            mActivityManager.stopService(null, intent, null, mGameUserId);
        } catch (RemoteException | RuntimeException e) {
            Slog.w(TAG, "Failed to stop GameSpace session", e);
        }
    }

    private void updateBypassCharge() {
        if (!mInitialized) {
            return;
        }
        final boolean active = mGameVisible && mSessionReady
                && mGameUserId == ActivityManager.getCurrentUser() && bypassChargeEnabled();
        if (active == mBypassChargeActive && !mSavedChargeControl.exists()) {
            return;
        }
        if (active && mBypassChargeActive) {
            return;
        }
        if (active) {
            if (!restoreChargingControl()) {
                return;
            }
            final BatteryManager batteryManager = mContext.getSystemService(BatteryManager.class);
            final int level = batteryManager == null ? -1
                    : batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            if (level < 0 || level > 100) {
                return;
            }
            try {
                final HealthInterface health = mHealth.get();
                if (!health.isChargingControlSupported() || !saveChargingControl(health)) {
                    return;
                }
                if (!health.setEnabled(true) || !health.setMode(HealthInterface.MODE_LIMIT)
                        || !health.setLimit(level)) {
                    restoreChargingControl();
                    return;
                }
                mBypassChargeActive = true;
            } catch (Exception e) {
                Slog.w(TAG, "Failed to set charging bypass", e);
                restoreChargingControl();
                return;
            }
        } else {
            mBypassChargeActive = false;
            if (!restoreChargingControl()) {
                mHandler.postDelayed(this::updateBypassCharge, 1000);
                return;
            }
        }
        if (mSessionReady && mGamePackage != null) {
            // Reuse the session command to acknowledge a successfully applied request.
            startGameSession();
        }
    }

    private boolean bypassChargeEnabled() {
        return Settings.System.getIntForUser(mContext.getContentResolver(),
                KEY_BYPASS_CHARGE_ENABLED, 0, mGameUserId) == 1;
    }

    private boolean saveChargingControl(HealthInterface health) {
        FileOutputStream stream = null;
        try {
            final int userId = mGameUserId;
            final JSONObject saved = new JSONObject()
                    .put("user", userId)
                    .put("enabled", health.getEnabled())
                    .put("mode", health.getMode())
                    .put("limit", health.getLimit());
            for (String setting : CHARGE_SETTINGS) {
                saved.put(setting, LineageSettings.System.getStringForUser(
                        mContext.getContentResolver(), setting, userId));
            }
            // Persist the original settings before the first temporary change.
            stream = mSavedChargeControl.startWrite();
            stream.write(saved.toString().getBytes(StandardCharsets.UTF_8));
            mSavedChargeControl.finishWrite(stream);
            return true;
        } catch (Exception e) {
            mSavedChargeControl.failWrite(stream);
            Slog.w(TAG, "Failed to save charging control", e);
            return false;
        }
    }

    private boolean restoreChargingControl() {
        if (!mSavedChargeControl.exists()) {
            return true;
        }
        try {
            final JSONObject saved = new JSONObject(new String(
                    mSavedChargeControl.readFully(), StandardCharsets.UTF_8));
            final HealthInterface health = mHealth.get();
            if (!health.isChargingControlSupported()) {
                return false;
            }
            final int userId = saved.getInt("user");
            if (userId == ActivityManager.getCurrentUser()
                    && (!health.setLimit(saved.getInt("limit"))
                            || (saved.getInt("mode") != HealthInterface.MODE_NONE
                                    && !health.setMode(saved.getInt("mode")))
                            || !health.setEnabled(saved.getBoolean("enabled")))) {
                return false;
            }
            // Preserve absent settings as well as explicitly configured values.
            for (String setting : CHARGE_SETTINGS) {
                if (!restoreChargeSetting(setting,
                        saved.has(setting) ? saved.getString(setting) : null, userId)) {
                    return false;
                }
            }
            mSavedChargeControl.delete();
            return true;
        } catch (Exception e) {
            Slog.w(TAG, "Failed to restore charging control", e);
            return false;
        }
    }

    private boolean restoreChargeSetting(String setting, String value, int userId) {
        final ContentResolver resolver = mContext.getContentResolver();
        if (value != null) {
            return LineageSettings.System.putStringForUser(resolver, setting, value, userId);
        }
        // The provider rejects null values, so delete a setting that was absent before.
        final Bundle args = new Bundle();
        args.putInt(LineageSettings.CALL_METHOD_USER_KEY, userId);
        resolver.call(LineageSettings.System.CONTENT_URI,
                LineageSettings.CALL_METHOD_DELETE_SYSTEM, setting, args);
        return true;
    }
}
