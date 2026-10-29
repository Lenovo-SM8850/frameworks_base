// SPDX-License-Identifier: Apache-2.0
package com.android.server.app;

import android.content.Context;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.util.Slog;
import android.view.WindowManager;
import android.window.TaskFpsCallback;

import com.android.server.LocalServices;
import com.android.server.wm.ActivityAssistInfo;
import com.android.server.wm.ActivityTaskManagerInternal;

import java.util.function.BiFunction;

import io.github.miner7222.gamepolicy.GameSession;
import io.github.miner7222.gamepolicy.IGamePolicy;

/** Sends verified visible-game sessions; session mutations and sends use the service handler. */
final class GamePolicyClient {
    private static final String TAG = "GamePolicyClient";
    private static final String SERVICE = IGamePolicy.DESCRIPTOR + "/default";
    private final Handler mHandler;
    private final BiFunction<String, Integer, GameSession> mResolver;
    private final IBinder mToken = new Binder();
    private final Runnable mUpdate = this::update;
    private final IBinder.DeathRecipient mDeath;
    private IGamePolicy mService;
    private volatile GameSession mSent;
    private String mPackage;
    private int mUserId;
    private long mGeneration;
    private Boolean mDeclared;
    private final WindowManager mWindowManager;
    private TaskFpsCallback mFpsCallback;
    private FrameCallback mFrameCallback;
    private long mFrameListener;
    private long mLastFrameCallback;
    private int mFpsTaskId = -1;
    private long mFpsGeneration;
    private long mLastFpsReport;

    GamePolicyClient(Context context, Handler handler,
            BiFunction<String, Integer, GameSession> resolver) {
        mHandler = handler;
        mWindowManager = context.getSystemService(WindowManager.class);
        mResolver = resolver;
        mDeath = () -> mHandler.post(() -> {
            stopFps();
            mService = null;
            mSent = null;
            refresh();
        });
    }

    void setGame(String packageName, int userId) {
        mHandler.post(() -> {
            mPackage = packageName;
            mUserId = userId;
            update();
        });
    }

    void refresh() {
        mHandler.post(mUpdate);
    }

    void reportScene(int uid, int pid, int scene) {
        final GameSession sent = mSent;
        if (sent == null || sent.uid != uid || sent.pid != pid) return;
        final android.app.ActivityManagerInternal activityManager =
                LocalServices.getService(android.app.ActivityManagerInternal.class);
        if (activityManager == null || activityManager.getProcessStartTimeTicks(
                pid, uid, sent.packageName) != sent.processStartTimeTicks) return;
        mHandler.post(() -> {
            if (mSent != sent || mService == null || mPackage == null) return;
            // Resolve again on this handler: a queued report cannot follow a process
            // restart, package change, visibility loss or mode change into a new session.
            final GameSession current = mResolver.apply(mPackage, mUserId);
            if (current == null || !sameSession(current, mSent)) return;
            try {
                mService.reportScene(mSent.generation, scene, mToken);
            } catch (RemoteException | RuntimeException e) {
                refresh();
            }
        });
    }

    private void update() {
        mHandler.removeCallbacks(mUpdate);
        if (mDeclared == null) mDeclared = ServiceManager.isDeclared(SERVICE);
        if (!mDeclared) return;
        final GameSession session = mPackage == null ? null : mResolver.apply(mPackage, mUserId);
        try {
            boolean reconnected = false;
            if (mService == null && (session != null || mSent != null)) {
                final IBinder binder = ServiceManager.checkService(SERVICE);
                if (binder != null) {
                    binder.linkToDeath(mDeath, 0);
                    mService = IGamePolicy.Stub.asInterface(binder);
                    reconnected = true;
                }
            }
            if (mService != null) {
                if (session == null && mSent != null) {
                    mService.stopSession(++mGeneration, mToken);
                    mSent = null;
                } else if (session != null && (reconnected || !sameSession(session, mSent))) {
                    session.generation = ++mGeneration;
                    mService.setSession(session, mToken);
                    mSent = session;
                }
            }
            updateFps();
        } catch (RemoteException | RuntimeException e) {
            stopFps();
            Slog.w(TAG, "Cannot update game policy session", e);
            if (mService != null) {
                try {
                    mService.asBinder().unlinkToDeath(mDeath, 0);
                } catch (java.util.NoSuchElementException ignored) {
                    // Death notification may already have removed the registration.
                }
            }
            mService = null;
        }
        // Retry process startup or service restart and verify package/UID membership.
        // Visibility expiry is owned by the task dispatcher, with no additional grace.
        if (mPackage != null || mSent != null) {
            mHandler.postDelayed(mUpdate, mService == null || mSent == null
                    || (mFpsCallback == null && mFrameListener == 0) ? 1000 : 5000);
        }
    }

    private int findTask(GameSession session) {
        final ActivityTaskManagerInternal tasks =
                LocalServices.getService(ActivityTaskManagerInternal.class);
        if (tasks == null) return -1;
        for (ActivityAssistInfo activity : tasks.getTopVisibleActivities()) {
            if (activity.getUserId() != session.userId
                    || !activity.getComponentName().getPackageName().equals(session.packageName)) {
                continue;
            }
            // A null token selects the task's top attached activity; the lookup matches only
            // shareable activity tokens otherwise.
            final ActivityTaskManagerInternal.ActivityTokens tokens =
                    tasks.getAttachedNonFinishingActivityForTask(activity.getTaskId(), null);
            if (tokens != null && tokens.getUid() == session.uid) return activity.getTaskId();
        }
        return -1;
    }

    private void updateFps() {
        final int taskId = mSent == null || mService == null ? -1 : findTask(mSent);
        if ((mFpsCallback != null || (mFrameListener != 0
                && SystemClock.uptimeMillis() - mLastFrameCallback < 1500)) && taskId == mFpsTaskId
                && mFpsGeneration == mSent.generation) return;
        stopFps();
        if (taskId < 0 || mWindowManager == null) return;
        final long generation = mSent.generation;
        final FrameCallback frameCallback = new FrameCallback() {
            @Override
            void onFrameReported(float fps, int appJank, int frames, int windowMs,
                    boolean layerChanged, boolean stalled) {
                mHandler.post(() -> {
                    if (mFrameCallback != this || mSent == null || mService == null
                            || mSent.generation != generation || windowMs != 500
                            || !Float.isFinite(fps)) return;
                    mLastFrameCallback = SystemClock.uptimeMillis();
                    sendFrame(stalled ? 0 : fps, appJank, frames, layerChanged);
                });
            }
        };
        try {
            mFrameCallback = frameCallback;
            mFrameListener = nativeRegisterFrameListener(frameCallback, taskId, mSent.uid);
            if (mFrameListener != 0) {
                mLastFrameCallback = SystemClock.uptimeMillis();
                mFpsTaskId = taskId; mFpsGeneration = generation;
                Slog.i(TAG, "Game frame listener registered for task " + taskId);
                return;
            }
        } catch (LinkageError | RuntimeException e) {
            // Older compositors/JNI libraries retain the task-counter fallback.
        }
        mFrameCallback = null;
        final TaskFpsCallback callback = new TaskFpsCallback() {
            @Override
            public void onFpsReported(float fps) {
                if (mFpsCallback != this || mSent == null || mService == null
                        || generation != mSent.generation || !Float.isFinite(fps)) return;
                sendFps(fps);
            }
        };
        try {
            // The system process holds ACCESS_FPS_COUNTER. Delivery stays on this handler.
            mWindowManager.registerTaskFpsCallback(taskId, mHandler::post, callback);
            mFpsTaskId = taskId;
            mFpsGeneration = generation;
            mFpsCallback = callback;
            Slog.i(TAG, "Task frame rate callback registered for task " + taskId);
        } catch (RuntimeException e) {
            // Tasks may disappear between lookup and registration; retry at the next update.
            Slog.w(TAG, "Cannot register game frame rate callback", e);
        }
    }

    private void sendFps(float fps) {
        if (mFpsCallback == null || mSent == null || mService == null
                || mFpsGeneration != mSent.generation) return;
        final long now = SystemClock.uptimeMillis();
        if (mLastFpsReport != 0 && now - mLastFpsReport < 400) return;
        mLastFpsReport = now;
        try {
            mService.reportFps(mFpsGeneration, fps, mToken);
        } catch (RemoteException | RuntimeException e) {
            stopFps();
            if (mService != null) {
                try {
                    mService.asBinder().unlinkToDeath(mDeath, 0);
                } catch (java.util.NoSuchElementException ignored) {
                    // The death notification may already have removed the registration.
                }
            }
            mService = null;
            refresh();
        }
    }

    private void sendFrame(float fps, int appJank, int frames, boolean layerChanged) {
        if (mFrameListener == 0 || mSent == null || mService == null
                || mFpsGeneration != mSent.generation) return;
        try {
            mService.reportFrame(mFpsGeneration, fps, appJank, frames, layerChanged, mToken);
        } catch (RemoteException | RuntimeException e) {
            stopFps();
            refresh();
        }
    }

    private abstract static class FrameCallback {
        abstract void onFrameReported(float fps, int appJank, int frames, int windowMs,
                boolean layerChanged, boolean stalled);
    }

    private static native long nativeRegisterFrameListener(FrameCallback callback, int taskId, int uid);
    private static native void nativeUnregisterFrameListener(long listener);

    private void stopFps() {
        mFrameCallback = null;
        final long frameListener = mFrameListener;
        mFrameListener = 0;
        if (frameListener != 0) {
            try {
                nativeUnregisterFrameListener(frameListener);
            } catch (LinkageError | RuntimeException e) {
                Slog.w(TAG, "Cannot unregister game frame listener", e);
            }
        }
        mLastFpsReport = 0;
        final TaskFpsCallback callback = mFpsCallback;
        mFpsCallback = null;
        mFpsTaskId = -1;
        if (callback != null) {
            try {
                mWindowManager.unregisterTaskFpsCallback(callback);
            } catch (RuntimeException e) {
                Slog.w(TAG, "Cannot unregister game frame rate callback", e);
            }
        }
    }

    private static boolean sameSession(GameSession a, GameSession b) {
        return b != null && a.userId == b.userId && a.uid == b.uid && a.pid == b.pid
                && a.processStartTimeTicks == b.processStartTimeTicks
                && a.mode == b.mode && a.packageName.equals(b.packageName);
    }
}
