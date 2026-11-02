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
import android.app.AlarmManager;
import android.app.GameManagerInternal;
import android.app.IProcessObserver;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.telecom.TelecomManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Process;
import android.util.Slog;

import com.android.server.LocalServices;
import com.android.server.ServiceThread;
import com.android.server.SystemService;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

public final class GameSpaceService extends SystemService {
    private static final String TAG = "GameSpaceService";
    private static final long SESSION_TIMEOUT_MS = 5 * 60 * 1000L;
    private static final long BOOST_DELAY_MS = 500L;

    private final GameListManager mGameListManager;
    private final GameStateDispatcher mGameStateDispatcher;
    private final GamePackageHandler mGamePackageHandler;
    private final ServiceThread mThread;
    private final Handler mHandler;

    private boolean mStarted;
    private boolean mKeyguardShowing;
    private String mCurrentGame;
    private Task mCurrentGameTask;
    private boolean mOverlayStarted;
    private boolean mManagedSession;
    private boolean mSuspended;
    private boolean mScreenOff;
    private boolean mCallActive;
    private final GameCallListener mCallListener = new GameCallListener();

    private final class GameCallListener extends TelephonyCallback
            implements TelephonyCallback.CallStateListener {
        @Override
        public void onCallStateChanged(int state) {
            if (state == TelephonyManager.CALL_STATE_OFFHOOK) mCallActive = true;
            else if (state == TelephonyManager.CALL_STATE_IDLE) mCallActive = false;
            if (mCallActive) suspendOverlay();
            else updateGameTask(mCurrentGameTask, null);
        }
    }
    private int mGamePid;
    private int mGameUid;
    private int mSessionUserId;
    private long mSuspendedAt;
    private final AlarmManager.OnAlarmListener mEndSuspendedSession = () -> {
        if (mSuspended && SystemClock.elapsedRealtime() - mSuspendedAt >= SESSION_TIMEOUT_MS) {
            stopOverlay();
        }
    };
    private final IProcessObserver mProcessObserver = new IProcessObserver.Stub() {
        @Override
        public void onProcessStarted(int pid, int processUid, int packageUid,
                String packageName, String processName) {}
        @Override
        public void onForegroundActivitiesChanged(int pid, int uid, boolean foreground) {}
        @Override
        public void onForegroundServicesChanged(int pid, int uid, int types) {}
        @Override
        public void onProcessDied(int pid, int uid) {
            mHandler.post(() -> {
                if (mOverlayStarted && pid == mGamePid && uid == mGameUid) stopOverlay();
            });
        }
    };
    private final WindowContainerListener mGameTaskListener = new WindowContainerListener() {
        @Override
        public void onVisibleRequestedChanged(boolean visible) {
            mHandler.post(() -> updateGameTask(mCurrentGameTask, null));
        }

        @Override
        public void onRemoved() {
            mHandler.post(() -> updateGameTask(mCurrentGameTask, null));
        }
    };
    private Runnable mPendingBoost;

    public GameSpaceService(Context context) {
        super(context);
        mThread = new ServiceThread(TAG, Process.THREAD_PRIORITY_BACKGROUND, true);
        mThread.start();
        mHandler = new Handler(mThread.getLooper());
        mGameListManager = new GameListManager(context);
        mGameStateDispatcher = new GameStateDispatcher(context, mHandler);
        mGamePackageHandler = new GamePackageHandler(context, mGameListManager, mHandler);
    }

    @Override
    public void onStart() {
        publishLocalService(GameSpaceService.class, this);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_ACTIVITY_MANAGER_READY) {
            startGameSpace();
        } else if (phase == PHASE_BOOT_COMPLETED) {
            mHandler.post(mGameStateDispatcher::initialize);
        }
    }

    @Override
    public void onUserSwitching(TargetUser from, TargetUser to) {
        mHandler.post(() -> {
            stopOverlay();
            mCurrentGame = null;
            if (mCurrentGameTask != null) {
                synchronized (mCurrentGameTask.mAtmService.mGlobalLock) {
                    setCurrentGameTask(null);
                }
            }
        });
    }

    public void onAppFocusChanged(ActivityRecord record, Task task) {
        // The activity at the top may belong to an in-game browser in the same task.
        mHandler.post(() -> updateGameTask(task, null));
    }

    // A game process that restarts inside its visible task changes neither focus nor visibility.
    public void onActivityProcessSet(Task task, int pid) {
        mHandler.post(() -> {
            if (task == mCurrentGameTask && isNewGameIncarnation(mOverlayStarted, mGamePid, pid)) {
                updateGameTask(task, null);
            }
        });
    }

    static boolean isNewGameIncarnation(boolean sessionStarted, int sessionPid, int pid) {
        return pid != 0 && (!sessionStarted || sessionPid != pid);
    }

    public void removeTask(Task task) {
        if (task != null) {
            mHandler.post(() -> updateGameTask(mCurrentGameTask, task));
        }
    }

    static String getGamePackageName(Task task, Predicate<String> isGame) {
        final Intent baseIntent = task.getBaseIntent();
        final ComponentName component = baseIntent != null ? baseIntent.getComponent() : null;
        if (component != null && isGame.test(component.getPackageName())) {
            return component.getPackageName();
        }
        final ActivityRecord game = task.getActivity(
                r -> !r.finishing && isGame.test(r.packageName));
        return game != null ? game.packageName : null;
    }

    static boolean isGameTaskVisible(Task task) {
        // Requested visibility reflects the end state of a transition, unlike focus or resume.
        return task.isAttached() && !task.isForceHidden() && task.isVisibleRequested();
    }

    static Task getVisibleGameTask(Task currentTask, Task focusedTask,
            Predicate<Task> isGameTask) {
        // Focusing another game ends the previous session even when both remain visible.
        if (focusedTask != null && isGameTask.test(focusedTask) && isGameTaskVisible(focusedTask)) {
            return focusedTask;
        }
        return currentTask != null && isGameTask.test(currentTask)
                && isGameTaskVisible(currentTask) ? currentTask : null;
    }

    public void onKeyguardChanged(boolean showing) {
        mHandler.post(() -> {
            mKeyguardShowing = showing;
            if (showing) {
                suspendOverlay();
            } else {
                updateGameTask(mCurrentGameTask, null);
            }
        });
    }

    private void startGameSpace() {
        if (mStarted) {
            return;
        }
        mStarted = true;
        final TelephonyManager telephony = getContext().getSystemService(TelephonyManager.class);
        if (telephony != null) {
            try {
                mCallActive = telephony.getCallState() == TelephonyManager.CALL_STATE_OFFHOOK;
                telephony.registerTelephonyCallback(mHandler::post, mCallListener);
            } catch (RuntimeException e) {
                Slog.w(TAG, "Unable to observe call state", e);
            }
        }
        try {
            ActivityManager.getService().registerProcessObserver(mProcessObserver);
        } catch (RemoteException e) {
            Slog.w(TAG, "Unable to observe game process death", e);
        }
        final IntentFilter screenFilter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        screenFilter.addAction(Intent.ACTION_SCREEN_ON);
        getContext().registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mScreenOff = Intent.ACTION_SCREEN_OFF.equals(intent.getAction());
                if (mScreenOff) suspendOverlay();
                else updateGameTask(mCurrentGameTask, null);
            }
        }, screenFilter, null, mHandler);
        mGamePackageHandler.registerPackageReceiver();
        mGameListManager.registerGameListObserver(mHandler);
        mGameListManager.addListener(() -> mHandler.post(() -> {
            updateGameTask(mCurrentGameTask, null);
            if (mOverlayStarted && !mSuspended && mManagedSession) {
                mGameStateDispatcher.boostGame(mGameListManager.isGameInPerfMode(mCurrentGame));
            }
        }));
        Slog.i(TAG, "GameSpaceService initialized");
    }

    private boolean isPolicyGame(String packageName, int userId) {
        final GameManagerInternal manager = LocalServices.getService(GameManagerInternal.class);
        return mGameListManager.isGame(packageName)
                || (manager != null && manager.isGame(packageName, userId));
    }

    private void updateGameTask(Task focusedTask, Task removedTask) {
        final Task reference = focusedTask != null ? focusedTask : mCurrentGameTask;
        if (reference == null) {
            return;
        }
        final int userId = ActivityManager.getCurrentUser();
        // Snapshot task identities under WM, classify outside it, then select from
        // the still-valid tasks using only immutable set membership under the lock.
        final Set<String> candidates = new HashSet<>();
        synchronized (reference.mAtmService.mGlobalLock) {
            reference.mAtmService.mRootWindowContainer.forAllLeafTasks(task -> {
                if (task != removedTask && task.isAttached() && task.mUserId == userId) {
                    getGamePackageName(task, pkg -> {
                        candidates.add(pkg);
                        return false; // Collect every activity package, including task root.
                    });
                }
                return false;
            });
        }
        candidates.removeIf(pkg -> !isPolicyGame(pkg, userId));
        final Set<String> games = Set.copyOf(candidates);
        if (userId != ActivityManager.getCurrentUser()) return;
        final Task gameTask;
        final String gamePackage;
        final boolean visible;
        final boolean transientCover;
        final boolean otherForegroundApp;
        final int gamePid;
        final int gameUid;
        boolean incarnationChanged = false;
        final String permissionController = getContext().getPackageManager()
                .getPermissionControllerPackageName();
        synchronized (reference.mAtmService.mGlobalLock) {
            final Predicate<Task> isGameTask = t -> t != removedTask && t.isLeafTask()
                    && t.isAttached() && t.mUserId == userId
                    && t.getTopNonFinishingActivity() != null
                    && getGamePackageName(t, games::contains) != null;
            final Task focusedRoot = reference.mAtmService.mRootWindowContainer
                    .getTopDisplayFocusedRootTask();
            final ActivityRecord focused = focusedRoot != null
                    ? focusedRoot.getTopNonFinishingActivity() : null;
            final Task actualFocusedTask = focused != null ? focused.getTask() : focusedTask;
            Task candidate = getVisibleGameTask(mCurrentGameTask, actualFocusedTask, isGameTask);
            if (candidate == null) {
                candidate = reference.mAtmService.mRootWindowContainer.getTask(
                        t -> isGameTask.test(t) && isGameTaskVisible(t));
            }
            // Retain a hidden task's listener so a show without a focus change restarts it.
            if (candidate == null && mCurrentGameTask != null
                    && isGameTask.test(mCurrentGameTask)) {
                candidate = mCurrentGameTask;
            }
            if (candidate == null && focusedTask != null && isGameTask.test(focusedTask)) {
                candidate = focusedTask;
            }
            gameTask = candidate;
            gamePackage = gameTask != null
                    ? getGamePackageName(gameTask, games::contains) : null;
            transientCover = isTransientGameCover(focused, permissionController);
            otherForegroundApp = focused != null && !focused.packageName.equals(gamePackage)
                    && !focusedRoot.isActivityTypeHomeOrRecents();
            final ActivityRecord gameActivity = gameTask != null
                    ? gameTask.getActivity(r -> !r.finishing && r.app != null
                            && r.packageName.equals(gamePackage)) : null;
            gamePid = gameActivity != null ? gameActivity.app.getPid() : 0;
            gameUid = gameActivity != null ? gameActivity.getUid() : 0;
            if (mOverlayStarted && (mCurrentGameTask != gameTask
                    || (mGamePid != 0 && mGamePid != gamePid))) {
                // Stop outside WM below, before assigning the next incarnation.
                incarnationChanged = true;
            }
            visible = gameTask != null && isGameTaskVisible(gameTask);
            setCurrentGameTask(gameTask);
        }
        if (incarnationChanged || (mCurrentGame != null && !mCurrentGame.equals(gamePackage))) {
            stopOverlay();
        }
        mCurrentGame = gamePackage;
        if (mOverlayStarted && mManagedSession != mGameListManager.isGame(gamePackage)) {
            stopOverlay();
        }
        final TelecomManager telecom = getContext().getSystemService(TelecomManager.class);
        final boolean foregroundCall = telecom != null && telecom.isInCall()
                && !transientCover && otherForegroundApp;
        if (gameTask == null) {
            stopOverlay();
        } else if (mKeyguardShowing || mScreenOff || mCallActive
                || !getContext().getSystemService(PowerManager.class).isInteractive()) {
            suspendOverlay();
        } else if ((visible || (transientCover && mOverlayStarted && !mSuspended))
                && !foregroundCall && gamePid != 0) {
            if (!mOverlayStarted) {
                mGamePid = gamePid;
                mGameUid = gameUid;
                startOverlay();
            } else if (mSuspended) {
                if (SystemClock.elapsedRealtime() - mSuspendedAt >= SESSION_TIMEOUT_MS) {
                    stopOverlay();
                    mGamePid = gamePid;
                    mGameUid = gameUid;
                    startOverlay();
                } else {
                    resumeOverlay();
                }
            }
            if (mGamePid == 0) {
                mGamePid = gamePid;
                mGameUid = gameUid;
            }
            mGameStateDispatcher.setGameVisible(true, this::suspendOverlay);
        } else if (mOverlayStarted) {
            mGameStateDispatcher.setGameVisible(false, this::suspendOverlay);
        }
    }

    private boolean isTransientGameCover(ActivityRecord record, String permissionController) {
        if (record == null) return false;
        // Only trusted system dialogs may retain a hidden game's effects.
        final boolean system = (record.info.applicationInfo.flags
                & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
        if (!system) return false;
        final String name = record.mActivityComponent.getClassName();
        return record.isResolverOrDelegateActivity()
                || (record.packageName.equals(permissionController)
                        && (name.endsWith(".GrantPermissionsActivity")
                                || name.endsWith(".ReviewPermissionsActivity")))
                || name.equals("android.accounts.ChooseTypeAndAccountActivity")
                || name.equals("android.accounts.ChooseAccountTypeActivity")
                || name.equals("android.accounts.GrantCredentialsPermissionActivity")
                || name.equals("android.accounts.ChooseAccountActivity");
    }

    private void suspendOverlay() {
        if (!mOverlayStarted || mSuspended) return;
        mSuspended = true;
        mSuspendedAt = SystemClock.elapsedRealtime();
        cancelPendingBoost();
        if (mManagedSession) {
            mGameStateDispatcher.suspendGameSession();
            mGameStateDispatcher.boostGame(false);
        }
        setPolicyActive(false);
        // Use elapsed time so sleeping cannot extend the retention window.
        getContext().getSystemService(AlarmManager.class).setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP, mSuspendedAt + SESSION_TIMEOUT_MS,
                TAG, mHandler::post, null, mEndSuspendedSession);
    }

    private void resumeOverlay() {
        if (mSuspended) getContext().getSystemService(AlarmManager.class).cancel(mEndSuspendedSession);
        mSuspended = false;
        if (mManagedSession) {
            mGameStateDispatcher.resumeGameSession();
            mGameStateDispatcher.boostGame(mGameListManager.isGameInPerfMode(mCurrentGame));
        }
        setPolicyActive(true);
    }

    private void setPolicyActive(boolean active) {
        final GameManagerInternal manager = LocalServices.getService(GameManagerInternal.class);
        if (manager == null) return;
        manager.setGameSessionSuspended(mCurrentGame, mSessionUserId, !active);
        manager.setGamePolicySession(active ? mCurrentGame : null, mSessionUserId);
    }

    // Caller holds the WM lock; callbacks only enqueue work on the GameSpace handler.
    private void setCurrentGameTask(Task task) {
        if (mCurrentGameTask == task) {
            return;
        }
        if (mCurrentGameTask != null) {
            mCurrentGameTask.unregisterWindowContainerListener(mGameTaskListener);
        }
        mCurrentGameTask = task;
        if (task != null) {
            task.registerWindowContainerListener(mGameTaskListener, false);
        }
    }

    private void startOverlay() {
        final String currentGame = mCurrentGame;
        if (currentGame == null || mKeyguardShowing) {
            return;
        }
        cancelPendingBoost();
        if (mGameListManager.isGameInPerfMode(currentGame)) {
            mPendingBoost = () -> mGameStateDispatcher.boostGame(true);
            mHandler.postDelayed(mPendingBoost, BOOST_DELAY_MS);
        }
        mOverlayStarted = true;
        mSessionUserId = ActivityManager.getCurrentUser();
        mSuspended = false;
        mManagedSession = mGameListManager.isGame(currentGame);
        if (mManagedSession) {
            mGameStateDispatcher.setSessionIdentity(mCurrentGameTask.mTaskId, mGamePid);
            mGameStateDispatcher.dispatchGameState(true, currentGame);
        }
        setPolicyActive(true);
    }

    private void stopOverlay() {
        if (!mOverlayStarted) {
            return;
        }
        mOverlayStarted = false;
        if (mSuspended) getContext().getSystemService(AlarmManager.class).cancel(mEndSuspendedSession);
        mSuspended = false;
        mGamePid = 0;
        cancelPendingBoost();
        if (mManagedSession) {
            mGameStateDispatcher.dispatchGameState(false, null);
            mGameStateDispatcher.boostGame(false);
        }
        mGameStateDispatcher.endPolicySession();
        setPolicyActive(false);
    }

    private void cancelPendingBoost() {
        if (mPendingBoost == null) {
            return;
        }
        mHandler.removeCallbacks(mPendingBoost);
        mPendingBoost = null;
    }
}
