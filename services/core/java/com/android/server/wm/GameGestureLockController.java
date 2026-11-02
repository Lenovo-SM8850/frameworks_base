// SPDX-License-Identifier: Apache-2.0
package com.android.server.wm;

import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.Display;
import android.view.GameGestureLock;
import android.view.IGameGestureLockListener;
import android.view.InputDevice;
import android.view.MotionEvent;

import com.android.server.LocalServices;

import java.io.PrintWriter;

/** Session authority shared by policy, input and SystemUI. No WM lock is acquired here. */
public final class GameGestureLockController {
    private static final String KEY = "ax_gaming_gesture_lock";
    interface SettingsStore {
        boolean get(String key, int user);
        void turnOff(int user);
    }
    private final SettingsStore mSettings;
    private long mIncarnation;
    private final Handler mHandler;
    private final GameGestureLockState mState = new GameGestureLockState();
    private int mTask = -1;
    private int mUser = -1;
    private int mDisplay = -1;
    private int mRotation = -1;
    private long mGeneration;
    private long mGenerationStart;
    private boolean mEligible;
    private int mInputTask = -1;
    private int mInputUser = -1;
    private int mInputRotation = -1;
    private boolean mInputEligible;
    private int mNavigationMode;
    private int mInputWidth;
    private int mInputHeight;
    private int mBottomGestureHeight;
    private boolean mWasActive;
    private boolean mBound;
    private boolean mRequested;
    private boolean mInputFilterInstalled;
    private boolean mAccessibilityEnabled;
    private boolean mOneHandedEnabled;
    private Runnable mTurnOffCallback;
    private IGameGestureLockListener mListener;
    private IGameGestureLockListener mPublishedListener;
    private boolean mPublishedActive;
    private IBinder.DeathRecipient mListenerDeath;

    GameGestureLockController(Context context, Handler handler) {
        this(handler, new SettingsStore() {
            @Override public boolean get(String key, int user) {
                return Settings.Secure.getIntForUser(context.getContentResolver(), key, 0, user) == 1;
            }
            @Override public void turnOff(int user) {
                Settings.Secure.putIntForUser(context.getContentResolver(), KEY, 0, user);
            }
        });
        final ContentObserver observer = new ContentObserver(handler) {
            @Override public void onChange(boolean selfChange) { refreshSettings(); }
        };
        for (String key : new String[] {KEY, Settings.Secure.ACCESSIBILITY_ENABLED,
                Settings.Secure.ONE_HANDED_MODE_ENABLED}) {
            context.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(key),
                    false, observer, UserHandle.USER_ALL);
        }
    }

    GameGestureLockController(Handler handler, SettingsStore settings) {
        mHandler = handler;
        mSettings = settings;
    }

    private void refreshSettings() {
        final int user;
        synchronized (this) { user = mUser; }
        if (user < 0) return;
        final boolean requested = setting(KEY, user);
        final boolean accessibility = setting(Settings.Secure.ACCESSIBILITY_ENABLED, user);
        final boolean oneHanded = setting(Settings.Secure.ONE_HANDED_MODE_ENABLED, user);
        synchronized (this) {
            if (user != mUser) return;
            applySettings(requested, accessibility, oneHanded);
        }
    }

    private boolean setting(String key, int user) {
        return mSettings.get(key, user);
    }

    private void applySettings(boolean requested, boolean accessibility, boolean oneHanded) {
        if (mRequested != requested || mAccessibilityEnabled != accessibility
                || mOneHandedEnabled != oneHanded) {
            mRequested = requested;
            mAccessibilityEnabled = accessibility;
            mOneHandedEnabled = oneHanded;
            invalidate();
        }
    }

    public static GameGestureLockController get() {
        return LocalServices.getService(GameGestureLockController.class);
    }

    void setSession(int task, int user, int display, int rotation, boolean eligible) {
        // Never call Settings/AM/WM while holding this monitor: input and WM both enter it.
        final boolean requested = setting(KEY, user);
        final boolean accessibility = setting(Settings.Secure.ACCESSIBILITY_ENABLED, user);
        final boolean oneHanded = setting(Settings.Secure.ONE_HANDED_MODE_ENABLED, user);
        synchronized (this) {
            boolean identityChanged = task != mTask || user != mUser || display != mDisplay;
            if (identityChanged || rotation != mRotation || eligible != mEligible) invalidate();
            mTask = task;
            mUser = user;
            mDisplay = display;
            mRotation = rotation;
            mEligible = eligible;
            applySettings(requested, accessibility, oneHanded);
        }
    }

    synchronized void setInputTarget(int display, int task, int user, int rotation,
            boolean eligible) {
        if (display != Display.DEFAULT_DISPLAY) return;
        if (mInputTask != task || mInputUser != user || mInputEligible != eligible
                || mInputRotation != rotation) invalidate();
        mInputRotation = rotation;
        mInputTask = task;
        mInputUser = user;
        mInputEligible = eligible;
    }

    synchronized void setInputGeometry(int display, int navigationMode, int width, int height,
            int bottomGestureHeight) {
        if (display != Display.DEFAULT_DISPLAY) return;
        if (mNavigationMode != navigationMode || mInputWidth != width || mInputHeight != height
                || mBottomGestureHeight != bottomGestureHeight) {
            mNavigationMode = navigationMode;
            mInputWidth = width;
            mInputHeight = height;
            mBottomGestureHeight = bottomGestureHeight;
            invalidate();
        }
    }

    public synchronized boolean isHomeGestureCandidate(MotionEvent event) {
        return event.getDisplayId() == Display.DEFAULT_DISPLAY
                && GameGestureLock.isGestureNavigation(mNavigationMode)
                && event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)
                && event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER
                && mInputWidth > 0 && mInputHeight > 0 && mBottomGestureHeight > 0
                && event.getX() >= 0 && event.getX() < mInputWidth
                && event.getY() >= mInputHeight - mBottomGestureHeight
                && event.getY() < mInputHeight;
    }

    synchronized void setStatusBarVisible(int display, boolean visible) {
        if (display == Display.DEFAULT_DISPLAY) mState.setStatusBarVisible(visible);
    }

    synchronized void setNavigationBarVisible(int display, boolean visible) {
        if (display == Display.DEFAULT_DISPLAY) mState.setNavigationBarVisible(visible);
    }

    synchronized void startSession() {
        ++mIncarnation;
        mTask = -1;
        mEligible = false;
        invalidate();
    }

    synchronized void release() {
        if (mEligible || mWasActive) {
            mEligible = false;
            invalidate();
        }
    }

    synchronized void setBindingAlive(boolean alive) {
        if (mBound != alive) { mBound = alive; invalidate(); }
    }

    public synchronized void setAccessibilityInputFilter(boolean installed) {
        if (mInputFilterInstalled != installed) {
            mInputFilterInstalled = installed;
            invalidate();
        }
    }

    public synchronized long generation() { return mGeneration; }

    public synchronized boolean isActive(int display) {
        if (display != Display.DEFAULT_DISPLAY || display != mDisplay) return false;
        boolean active = isActiveLocked();
        if (mWasActive != active) {
            mWasActive = active;
            if (!active) invalidate();
            else mHandler.post(this::publishState);
        }
        return active;
    }

    private boolean isActiveLocked() {
        return mDisplay == Display.DEFAULT_DISPLAY && mEligible && mInputEligible
                && mInputTask == mTask && mInputUser == mUser && mBound && mRequested
                && mListener != null && !mInputFilterInstalled && !mAccessibilityEnabled
                && !mOneHandedEnabled;
    }

    synchronized void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + "GameGestureLockController:");
        pw.println(prefix + "  active=" + isActiveLocked() + " requested=" + mRequested
                + " generation=" + mGeneration + " generationStart=" + mGenerationStart
                + " incarnation=" + mIncarnation + " wasActive=" + mWasActive);
        pw.println(prefix + "  session: task=" + mTask + " user=" + mUser + " display=" + mDisplay
                + " rotation=" + mRotation + " eligible=" + mEligible);
        pw.println(prefix + "  input: task=" + mInputTask + " user=" + mInputUser
                + " rotation=" + mInputRotation + " eligible=" + mInputEligible);
        pw.println(prefix + "  navigationMode=" + mNavigationMode + " inputSize=" + mInputWidth
                + "x" + mInputHeight + " bottomGestureHeight=" + mBottomGestureHeight
                + " statusBarVisible=" + mState.isStatusBarVisible()
                + " shadeUnlocked=" + mState.isShadeUnlocked()
                + " homeUnlocked=" + mState.isHomeUnlocked());
        pw.println(prefix + "  bound=" + mBound + " listenerPresent=" + (mListener != null)
                + " accessibilityInputFilterInstalled=" + mInputFilterInstalled
                + " accessibilityEnabled=" + mAccessibilityEnabled
                + " oneHandedEnabled=" + mOneHandedEnabled);
    }

    private boolean validKind(int kind) { return kind >= 0 && kind <= GameGestureLock.REVEAL; }

    public synchronized int begin(int kind, int display, int device, long down) {
        if (!validKind(kind) || !isActive(display) || down < mGenerationStart) {
            return GameGestureLock.INACTIVE;
        }
        // The revealed bar owns shade input until it is hidden, including shade focus changes.
        if (kind == GameGestureLock.SHADE && mState.isShadeUnlocked(down)) return GameGestureLock.INACTIVE;
        if (kind == GameGestureLock.HOME && mState.isHomeUnlocked(down)) {
            return GameGestureLock.INACTIVE;
        }
        return mState.begin(kind, device, down) ? GameGestureLock.ALLOW : GameGestureLock.BLOCK;
    }

    public synchronized boolean recognize(int kind, int display, int device, long down) {
        if (!validKind(kind) || !isActive(display) || down < mGenerationStart) return false;
        boolean blocked = mState.recognize(kind, device, down);
        if (blocked) notice(true);
        return blocked;
    }

    public synchronized void end(int kind, int display, int device, long down, long end,
            boolean cancelled) {
        if (validKind(kind) && isActive(display) && down >= mGenerationStart
                && mState.end(kind, device, down, end, cancelled)) notice(true);
    }

    private void invalidate() {
        ++mGeneration;
        mGenerationStart = android.os.SystemClock.uptimeMillis();
        mState.reset();
        notice(false);
        mHandler.post(this::publishState);
    }

    private void publishState() {
        final IGameGestureLockListener listener;
        final boolean active;
        synchronized (this) {
            listener = mListener;
            active = isActive(Display.DEFAULT_DISPLAY);
            if (listener == mPublishedListener && active == mPublishedActive) return;
            mPublishedListener = listener;
            mPublishedActive = active;
        }
        if (listener != null) {
            try { listener.onStateChanged(active); }
            catch (RemoteException ignored) { }
        }
    }

    private void notice(boolean visible) {
        final IGameGestureLockListener listener = mListener;
        final long generation = mGeneration;
        final int user = mUser;
        if (listener == null) return;
        mHandler.post(() -> {
            try { listener.onNotice(generation, user, visible); }
            catch (RemoteException ignored) { }
        });
    }

    synchronized void setListener(IGameGestureLockListener listener) {
        if (mListener != null && mListenerDeath != null) {
            mListener.asBinder().unlinkToDeath(mListenerDeath, 0);
        }
        mListener = listener;
        invalidate();
        if (listener != null) {
            mListenerDeath = () -> {
                synchronized (GameGestureLockController.this) {
                    if (mListener == listener) { mListener = null; invalidate(); }
                }
            };
            try { listener.asBinder().linkToDeath(mListenerDeath, 0); }
            catch (RemoteException e) { mListener = null; invalidate(); }
        }
    }

    synchronized void setTurnOffCallback(Runnable callback) { mTurnOffCallback = callback; }

    void turnOff(long generation, int user) {
        final long incarnation;
        synchronized (this) {
            if (generation != mGeneration || user != mUser || !isActive(Display.DEFAULT_DISPLAY)) return;
            incarnation = mIncarnation;
        }
        // The dispatcher and all start/resume/end session changes use this same handler.
        // A slow Settings provider cannot let a replacement session overtake either effect.
        mHandler.post(() -> {
            final Runnable callback;
            synchronized (GameGestureLockController.this) {
                if (generation != mGeneration || incarnation != mIncarnation || user != mUser
                        || !isActive(Display.DEFAULT_DISPLAY)) return;
                mRequested = false;
                invalidate();
                callback = mTurnOffCallback;
            }
            mSettings.turnOff(user);
            synchronized (GameGestureLockController.this) {
                if (incarnation != mIncarnation || user != mUser) return;
            }
            if (callback != null) callback.run();
        });
    }
}
