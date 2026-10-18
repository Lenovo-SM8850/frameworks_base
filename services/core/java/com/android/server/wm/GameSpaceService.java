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
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Process;
import android.util.Slog;

import com.android.server.ServiceThread;
import com.android.server.SystemService;

import java.util.function.Predicate;

public final class GameSpaceService extends SystemService {
    private static final String TAG = "GameSpaceService";
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
        // A quick-launched floating game must not take over a still-visible game's session.
        if (currentTask != null && isGameTask.test(currentTask) && isGameTaskVisible(currentTask)) {
            return currentTask;
        }
        return focusedTask != null && isGameTask.test(focusedTask)
                && isGameTaskVisible(focusedTask) ? focusedTask : null;
    }

    public void onKeyguardChanged(boolean showing) {
        mHandler.post(() -> {
            mKeyguardShowing = showing;
            if (showing) {
                stopOverlay();
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
        mGamePackageHandler.registerPackageReceiver();
        mGameListManager.registerGameListObserver(mHandler);
        mGameListManager.addListener(() -> mHandler.post(() -> {
            updateGameTask(mCurrentGameTask, null);
            if (mOverlayStarted) {
                mGameStateDispatcher.boostGame(mGameListManager.isGameInPerfMode(mCurrentGame));
            }
        }));
        Slog.i(TAG, "GameSpaceService initialized");
    }

    private void updateGameTask(Task focusedTask, Task removedTask) {
        final Task reference = focusedTask != null ? focusedTask : mCurrentGameTask;
        if (reference == null) {
            return;
        }
        final int userId = ActivityManager.getCurrentUser();
        final Task gameTask;
        final String gamePackage;
        final boolean visible;
        synchronized (reference.mAtmService.mGlobalLock) {
            final Predicate<Task> isGameTask = t -> t != removedTask && t.isLeafTask()
                    && t.isAttached() && t.mUserId == userId
                    && t.getTopNonFinishingActivity() != null
                    && getGamePackageName(t, mGameListManager::isGame) != null;
            Task candidate = getVisibleGameTask(mCurrentGameTask, focusedTask, isGameTask);
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
                    ? getGamePackageName(gameTask, mGameListManager::isGame) : null;
            visible = gameTask != null && isGameTaskVisible(gameTask);
            setCurrentGameTask(gameTask);
        }
        if (mCurrentGame != null && !mCurrentGame.equals(gamePackage)) {
            stopOverlay();
        }
        mCurrentGame = gamePackage;
        if (mKeyguardShowing || gameTask == null) {
            // A removed/finished game and keyguard are definitive exits, not transitions.
            stopOverlay();
        } else if (visible) {
            if (!mOverlayStarted) {
                startOverlay();
            }
            mGameStateDispatcher.setGameVisible(true, this::stopOverlay);
        } else if (mOverlayStarted) {
            mGameStateDispatcher.setGameVisible(false, this::stopOverlay);
        }
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
        mGameStateDispatcher.dispatchGameState(true, currentGame);
    }

    private void stopOverlay() {
        if (!mOverlayStarted) {
            return;
        }
        mOverlayStarted = false;
        cancelPendingBoost();
        mGameStateDispatcher.dispatchGameState(false, null);
        mGameStateDispatcher.boostGame(false);
    }

    private void cancelPendingBoost() {
        if (mPendingBoost == null) {
            return;
        }
        mHandler.removeCallbacks(mPendingBoost);
        mPendingBoost = null;
    }
}
