// SPDX-License-Identifier: Apache-2.0
package com.android.server.wm;

import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND;
import static android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
import static android.view.WindowManager.LayoutParams.MATCH_PARENT;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;

import android.database.ContentObserver;
import android.graphics.Color;
import android.net.Uri;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.view.Gravity;
import android.view.WindowLayout;
import android.view.WindowManager.LayoutParams;
import android.window.ClientWindowFrames;

import java.util.Collection;

import org.json.JSONException;
import org.json.JSONObject;

/** Applies per-user game window geometry without changing activity aspect-ratio overrides. */
final class GameWindowRatioController {
    private static final String TAG = "GameWindowRatioController";
    // Values are a JSON object: {"package.name":"placement:ratio"}. Absence means full screen.
    private static final String SETTING = "game_window_ratios";

    private final WindowManagerService mService;
    private final DisplayContent mDisplay;
    private final ContentObserver mObserver;
    private final SparseArray<ArrayMap<String, Ratio>> mRatios = new SparseArray<>();
    private final SparseBooleanArray mPendingUsers = new SparseBooleanArray();
    private boolean mReleased;
    private final WindowLayout mWindowLayout = new WindowLayout();
    private final ClientWindowFrames mParentFrames = new ClientWindowFrames();

    GameWindowRatioController(WindowManagerService service, DisplayContent display) {
        mService = service;
        mDisplay = display;
        mObserver = new ContentObserver(service.mH) {
            @Override
            public void onChange(boolean selfChange) {
                reload();
            }

            @Override
            public void onChange(boolean selfChange, Collection<Uri> uris, int flags, int userId) {
                if (userId >= 0) reloadUser(userId); else reload();
            }
        };
        service.mH.post(() -> {
            if (mReleased) return;
            service.mContext.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(SETTING), false, mObserver, UserHandle.USER_ALL);
            reload();
        });
    }

    void onUserChanged() {
        mService.mH.post(this::reload);
    }

    void release() {
        mService.mH.post(() -> {
            mReleased = true;
            mService.mContext.getContentResolver().unregisterContentObserver(mObserver);
        });
    }

    private void reload() {
        if (mReleased) return;
        final SparseBooleanArray users = new SparseBooleanArray();
        synchronized (mService.mGlobalLock) {
            for (int i = 0; i < mRatios.size(); i++) {
                users.put(mRatios.keyAt(i), true);
            }
            mDisplay.forAllWindows(w -> {
                if (w.mActivityRecord != null) users.put(w.mActivityRecord.mUserId, true);
            }, true);
        }
        for (int i = 0; i < users.size(); i++) {
            reloadUser(users.keyAt(i));
        }
    }

    private void reloadUser(int userId) {
        if (mReleased) return;
        final String value = Settings.Secure.getStringForUser(
                mService.mContext.getContentResolver(), SETTING, userId);
        final ArrayMap<String, Ratio> ratios = new ArrayMap<>();
        if (value != null && !value.isEmpty()) {
            try {
                final JSONObject entries = new JSONObject(value);
                final var packages = entries.keys();
                while (packages.hasNext()) {
                    final String pkg = packages.next();
                    final Ratio ratio = Ratio.parse(entries.optString(pkg));
                    if (ratio != null) ratios.put(pkg, ratio);
                }
            } catch (JSONException e) {
                Slog.w(TAG, "Invalid game window ratios", e);
            }
        }
        synchronized (mService.mGlobalLock) {
            mPendingUsers.delete(userId);
            mRatios.put(userId, ratios);
            mDisplay.forAllWindows(w -> {
                if (w.mActivityRecord != null && w.mActivityRecord.mUserId == userId
                        && apply(w, w.mAttrs, w.getDisplayFrames(mDisplay.mDisplayFrames))) {
                    w.mLayoutNeeded = true;
                    w.forceReportingResized();
                }
            }, true);
            mDisplay.setLayoutNeeded();
            mService.mWindowPlacerLocked.requestTraversal();
        }
    }

    // Called only with freshly supplied app parameters, before they are modified by this feature.
    void adjustWindowParams(WindowState win, LayoutParams attrs) {
        if (attrs.type != TYPE_BASE_APPLICATION) return;
        win.mGameWindowRatioState = new State(attrs);
        apply(win, attrs, mDisplay.mDisplayFrames);
    }

    // Also runs during layout so rotations, mode changes and display resizes need no app relaunch.
    boolean apply(WindowState win, LayoutParams attrs, DisplayFrames frames) {
        final State state = win.mGameWindowRatioState;
        if (state == null) return false;
        final int userId = win.mActivityRecord != null ? win.mActivityRecord.mUserId
                : win.mShowUserId;
        final ArrayMap<String, Ratio> ratios = mRatios.get(userId);
        if (ratios == null && !mPendingUsers.get(userId)) {
            // Settings reads stay on the handler, outside the WM lock used by layout.
            mPendingUsers.put(userId, true);
            mService.mH.post(() -> reloadUser(userId));
        }
        final Ratio ratio = win.mActivityRecord != null && win.getTask() != null
                && ratios != null
                && win.getWindowingMode() == WINDOWING_MODE_FULLSCREEN
                && win.getTask().getWindowingMode() == WINDOWING_MODE_FULLSCREEN
                && win.getTask().getRootTask().getWindowingMode() == WINDOWING_MODE_FULLSCREEN
                && !AppCompatUtils.isDesktopFirst(win.getTask().getTaskDisplayArea())
                ? ratios.get(win.mActivityRecord.packageName) : null;
        final Params requested = state.rotations != null
                && frames.mRotation < state.rotations.length
                && state.rotations[frames.mRotation] != null
                ? state.rotations[frames.mRotation] : state.requested;
        // Dimming consumes base attributes even when layout uses a rotation variant.
        boolean changed = applyParams(win, attrs, state.requested, requested, ratio, frames,
                win.mGlobalScale);
        if (attrs.paramsForRotation != null && state.rotations != null) {
            for (int i = 0; i < attrs.paramsForRotation.length; i++) {
                if (attrs.paramsForRotation[i] != null && state.rotations[i] != null) {
                    changed |= applyParams(win, attrs.paramsForRotation[i], state.rotations[i],
                            state.rotations[i], ratio, frames, win.mGlobalScale);
                }
            }
        }
        changed |= state.applied != eligible(requested, ratio);
        state.applied = eligible(requested, ratio);
        state.layoutSizeChanged |= changed;
        return changed;
    }

    static boolean isAdjusted(WindowState win) {
        return win.mGameWindowRatioState != null && win.mGameWindowRatioState.applied;
    }

    static boolean consumeLayoutSizeChanged(WindowState win) {
        final State state = win.mGameWindowRatioState;
        if (state == null) return false;
        final boolean changed = state.layoutSizeChanged;
        state.layoutSizeChanged = false;
        return changed;
    }

    private static boolean eligible(Params requested, Ratio ratio) {
        return ratio != null && requested.height == MATCH_PARENT
                && (requested.flags & (FLAG_FULLSCREEN | FLAG_LAYOUT_IN_SCREEN)) != 0;
    }

    private boolean applyParams(WindowState win, LayoutParams attrs, Params requested,
            Params effectiveRequested, Ratio ratio, DisplayFrames frames, float globalScale) {
        final int oldWidth = attrs.width;
        final int oldHeight = attrs.height;
        final int oldGravity = attrs.gravity;
        final int oldX = attrs.x;
        final int oldY = attrs.y;
        final int oldFlags = attrs.flags;
        final float oldDim = attrs.dimAmount;
        final long oldColor = attrs.dimColor;
        requested.restore(attrs);
        if (eligible(requested, ratio)) {
            // Probe the same parent used by DisplayPolicy, including fit insets and cutout policy.
            final int cutoutMode = attrs.layoutInDisplayCutoutMode;
            if (attrs == win.mAttrs) {
                attrs.layoutInDisplayCutoutMode = mService.mAtmService.getCutoutMode(cutoutMode,
                        win.mActivityRecord.packageName, frames.mWidth, frames.mHeight);
            }
            try {
                mWindowLayout.computeFrames(attrs, win.getInsetsState(), frames.mDisplayCutoutSafe,
                        win.getBounds(), win.getWindowingMode(), WindowLayout.UNSPECIFIED_LENGTH,
                        WindowLayout.UNSPECIFIED_LENGTH, win.getRequestedVisibleTypes(), globalScale,
                        mParentFrames);
            } finally {
                attrs.layoutInDisplayCutoutMode = cutoutMode;
            }
            final WindowSize size = calculateWindowSize(mParentFrames.parentFrame.width(),
                    mParentFrames.parentFrame.height(), ratio.placement, ratio.value, globalScale);
            attrs.width = size.width;
            attrs.height = size.height;
            attrs.gravity = ratio.placement == 2 ? Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL
                    : Gravity.CENTER;
            attrs.x = 0;
            attrs.y = 0;
        }
        if (eligible(effectiveRequested, ratio)) {
            attrs.flags |= FLAG_DIM_BEHIND;
            attrs.dimAmount = 1f;
            attrs.dimColor = Color.pack(Color.BLACK);
        }
        return oldWidth != attrs.width || oldHeight != attrs.height || oldGravity != attrs.gravity
                || oldX != attrs.x || oldY != attrs.y || oldFlags != attrs.flags
                || oldDim != attrs.dimAmount || oldColor != attrs.dimColor;
    }

    // Ratio is long side / short side. MATCH_PARENT keeps placement relative to the effective parent.
    static WindowSize calculateWindowSize(int parentWidth, int parentHeight, int placement,
            float ratio, float globalScale) {
        final boolean portrait = parentWidth < parentHeight;
        final int longSide = Math.max(parentWidth, parentHeight);
        final int shortSide = Math.min(parentWidth, parentHeight);
        // Preserve landscape's bottom-placement sizing; portrait must fit on both axes.
        if ((placement == 1 || portrait) && (float) longSide / shortSide > ratio) {
            final int length = toWindowLength((int) (shortSide * ratio), globalScale);
            return portrait ? new WindowSize(MATCH_PARENT, length)
                    : new WindowSize(length, MATCH_PARENT);
        }
        final int length = toWindowLength((int) (longSide / ratio), globalScale);
        return portrait ? new WindowSize(length, MATCH_PARENT)
                : new WindowSize(MATCH_PARENT, length);
    }

    static final class WindowSize {
        final int width;
        final int height;

        WindowSize(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }

    private static int toWindowLength(int displayLength, float globalScale) {
        // WindowLayout applies the compatibility scale to positive layout dimensions.
        return Math.max(1, Math.round(displayLength / globalScale));
    }

    static final class State {
        final Params requested;
        final Params[] rotations;
        boolean applied;
        boolean layoutSizeChanged;

        State(LayoutParams attrs) {
            requested = new Params(attrs);
            rotations = attrs.paramsForRotation == null ? null
                    : new Params[attrs.paramsForRotation.length];
            if (rotations != null) {
                for (int i = 0; i < rotations.length; i++) {
                    if (attrs.paramsForRotation[i] != null) {
                        rotations[i] = new Params(attrs.paramsForRotation[i]);
                    }
                }
            }
        }
    }

    private static final class Params {
        final int width, height, gravity, x, y, flags;
        final float dimAmount;
        final long dimColor;

        Params(LayoutParams attrs) {
            width = attrs.width;
            height = attrs.height;
            gravity = attrs.gravity;
            x = attrs.x;
            y = attrs.y;
            flags = attrs.flags;
            dimAmount = attrs.dimAmount;
            dimColor = attrs.dimColor;
        }

        void restore(LayoutParams attrs) {
            attrs.width = width;
            attrs.height = height;
            attrs.gravity = gravity;
            attrs.x = x;
            attrs.y = y;
            attrs.flags = (attrs.flags & ~FLAG_DIM_BEHIND) | (flags & FLAG_DIM_BEHIND);
            attrs.dimAmount = dimAmount;
            attrs.dimColor = dimColor;
        }
    }

    private static final class Ratio {
        final int placement;
        final float value;

        Ratio(int placement, float value) {
            this.placement = placement;
            this.value = value;
        }

        static Ratio parse(String encoded) {
            final String[] parts = encoded.split(":", -1);
            if (parts.length != 2) return null;
            try {
                final int placement = Integer.parseInt(parts[0]);
                final float value = Float.parseFloat(parts[1]);
                if ((placement == 1 || placement == 2)
                        && ((value == 1.2f && placement == 1)
                                || value == 1.8f || value == 2.1f || value == 2.4f
                                || value == 2.8f || value == 3.5f)) {
                    return new Ratio(placement, value);
                }
            } catch (NumberFormatException ignored) {
                // Ignore malformed entries without discarding other packages.
            }
            return null;
        }
    }
}
