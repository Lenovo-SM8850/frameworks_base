// SPDX-License-Identifier: Apache-2.0
package com.android.server.app;

import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Slog;

import java.util.function.BiFunction;

import io.github.miner7222.gamepolicy.GameSession;
import io.github.miner7222.gamepolicy.IGamePolicy;

/** Sends verified visible-game sessions; all state is confined to the service handler. */
final class GamePolicyClient {
    private static final String TAG = "GamePolicyClient";
    private static final String SERVICE = IGamePolicy.DESCRIPTOR + "/default";
    private final Handler mHandler;
    private final BiFunction<String, Integer, GameSession> mResolver;
    private final IBinder mToken = new Binder();
    private final Runnable mUpdate = this::update;
    private final IBinder.DeathRecipient mDeath;
    private IGamePolicy mService;
    private GameSession mSent;
    private String mPackage;
    private int mUserId;
    private long mGeneration;
    private Boolean mDeclared;

    GamePolicyClient(Handler handler,
            BiFunction<String, Integer, GameSession> resolver) {
        mHandler = handler;
        mResolver = resolver;
        mDeath = () -> mHandler.post(() -> {
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
        } catch (RemoteException | RuntimeException e) {
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
        if (mPackage != null || mSent != null) mHandler.postDelayed(mUpdate, 1000);
    }

    private static boolean sameSession(GameSession a, GameSession b) {
        return b != null && a.userId == b.userId && a.uid == b.uid && a.pid == b.pid
                && a.processStartTimeTicks == b.processStartTimeTicks
                && a.mode == b.mode && a.packageName.equals(b.packageName);
    }
}
