// SPDX-License-Identifier: Apache-2.0
package com.android.systemui.gaming;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.view.Gravity;
import android.view.IGameGestureLockListener;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.systemui.CoreStartable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.res.R;

import javax.inject.Inject;

/** System-owned notice: no focus, and only the action participates in hit testing. */
@SysUISingleton
public final class GameGestureLockController implements CoreStartable {
    private static boolean sActive;
    public static boolean isActive() { return sActive; }
    private final com.android.systemui.statusbar.CommandQueue mCommandQueue;
    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final WindowManager mWindows;
    private LinearLayout mPill;
    private long mGeneration;
    private int mUser;
    private final Runnable mHide = this::hide;
    private final IGameGestureLockListener mListener = new IGameGestureLockListener.Stub() {
        @Override public void onStateChanged(boolean active) {
            mHandler.post(() -> {
                sActive = active;
                mCommandQueue.recomputeDisableFlags(android.view.Display.DEFAULT_DISPLAY, false);
                if (!active) hide();
            });
        }
        @Override public void onNotice(long generation, int user, boolean visible) {
            mHandler.post(() -> {
                if (generation < mGeneration) return;
                mGeneration = generation; mUser = user;
                if (visible) show(); else hide();
            });
        }
    };

    @Inject
    public GameGestureLockController(Context context,
            com.android.systemui.statusbar.CommandQueue commandQueue) {
        mCommandQueue = commandQueue;
        mContext = context.createWindowContext(WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL,
                null);
        mWindows = mContext.getSystemService(WindowManager.class);
    }

    @Override public void start() {
        try { WindowManagerGlobal.getWindowManagerService().setGameGestureLockListener(mListener); }
        catch (RemoteException ignored) { }
    }

    private int dp(int value) {
        return Math.round(value * mContext.getResources().getDisplayMetrics().density);
    }

    private void show() {
        mHandler.removeCallbacks(mHide);
        if (mPill == null) {
            LinearLayout pill = new LinearLayout(mContext);
            pill.setGravity(Gravity.CENTER_VERTICAL);
            pill.setPadding(dp(16), 0, 0, 0);
            GradientDrawable background = new GradientDrawable();
            background.setColor(0xF02A2D31);
            background.setCornerRadius(dp(28));
            pill.setBackground(background);
            TextView message = new TextView(mContext);
            message.setText(R.string.game_gesture_lock_swipe_again);
            message.setTextColor(Color.WHITE);
            message.setTextSize(14);
            pill.addView(message);
            TextView action = new TextView(mContext);
            action.setText(R.string.game_gesture_lock_turn_off);
            action.setTextColor(0xFFA8C7FA);
            action.setTextSize(14);
            action.setGravity(Gravity.CENTER);
            action.setPadding(dp(16), 0, dp(16), 0);
            action.setMinimumHeight(dp(48));
            action.setFocusable(true);
            action.setOnClickListener(view -> {
                try {
                    WindowManagerGlobal.getWindowManagerService()
                            .turnOffGameGestureLock(mGeneration, mUser);
                } catch (RemoteException ignored) { }
                hide();
            });
            pill.addView(action);
            pill.getViewTreeObserver().addOnComputeInternalInsetsListener(info -> {
                info.setTouchableInsets(ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION);
                Rect bounds = new Rect();
                action.getHitRect(bounds);
                info.touchableRegion.set(bounds);
            });
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            params.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY;
            params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            params.y = dp(48);
            params.setTitle("Game gesture confirmation");
            try {
                mWindows.addView(pill, params);
                mPill = pill;
            } catch (WindowManager.BadTokenException | WindowManager.InvalidDisplayException e) {
                // Without a notice/escape action the confirmation feature must fail open.
                try { WindowManagerGlobal.getWindowManagerService().setGameGestureLockListener(null); }
                catch (RemoteException ignored) { }
                return;
            }
        }
        // The actual repeat deadline is checked by system_server against event timestamps.
        mHandler.postDelayed(mHide, 2000);
    }

    private void hide() {
        mHandler.removeCallbacks(mHide);
        if (mPill != null) { mWindows.removeView(mPill); mPill = null; }
    }
}
