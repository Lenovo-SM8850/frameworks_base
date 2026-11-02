// SPDX-License-Identifier: Apache-2.0
package android.view;

import android.annotation.NonNull;
import android.os.RemoteException;

/**
 * Shared gesture confirmation protocol. All times use the MotionEvent uptime clock.
 *
 * @hide
 */
public final class GameGestureLock {
    /** @hide */
    public static final int NONE = -1;
    /** @hide */
    public static final int BACK = 0;
    /** @hide */
    public static final int HOME = 1;
    /** @hide */
    public static final int SHADE = 2;
    /** @hide */
    public static final int THREE_FINGER = 3;
    /** @hide */
    public static final int REVEAL = 4;
    /** @hide */
    public static final int INACTIVE = 0;
    /** @hide */
    public static final int BLOCK = 1;
    /** @hide */
    public static final int ALLOW = 2;
    private GameGestureLock() {}

    /**
     * Edge constants here match the two system-bar gesture detectors.
     * @hide
     */
    public static int transientKind(@NonNull android.content.Context context, int edge) {
        final int navigationMode = context.getResources().getInteger(
                com.android.internal.R.integer.config_navBarInteractionMode);
        final boolean statusBarVisible = edge == 1
                && context.getSystemService(WindowManager.class).getCurrentWindowMetrics()
                        .getWindowInsets().isVisible(WindowInsets.Type.statusBars());
        return transientKind(navigationMode, edge, statusBarVisible);
    }

    /** @hide */
    public static boolean isGestureNavigation(int navigationMode) {
        return navigationMode == WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL;
    }

    /**
     * Classifies a detected edge swipe without spending a confirmation token.
     * Button navigation must retain its first-swipe escape path at bottom and side edges.
     * The top-edge status bar reveal and shade remain protected in every navigation mode.
     * @hide
     */
    public static int transientKind(int navigationMode, int edge, boolean statusBarVisible) {
        if (navigationMode == WindowManagerPolicyConstants.NAV_BAR_MODE_3BUTTON && edge != 1) {
            return NONE;
        }
        if (edge == 2 && isGestureNavigation(navigationMode)) return HOME;
        if ((edge == 3 || edge == 4) && isGestureNavigation(navigationMode)) return BACK;
        if (edge == 1 && statusBarVisible) return SHADE;
        return REVEAL;
    }

    /** @hide */
    public static final class TransientTracker {
        private final java.util.Map<String, Integer> mKinds = new java.util.HashMap<>();
        /** @hide */
        public TransientTracker() {}
        private String key(MotionEvent event) {
            return event.getDisplayId() + ":" + event.getDeviceId() + ":" + event.getDownTime();
        }
        /** @hide */
        public void record(int kind, @NonNull MotionEvent event) {
            if (kind != NONE) mKinds.put(key(event), kind);
        }
        /** @hide */
        public int finish(@NonNull MotionEvent event) {
            if (event.getActionMasked() != MotionEvent.ACTION_UP
                    && event.getActionMasked() != MotionEvent.ACTION_CANCEL) return NONE;
            Integer kind = mKinds.remove(key(event));
            return kind != null ? kind : NONE;
        }
    }

    /** @hide */
    public static long generation() {
        try { return WindowManagerGlobal.getWindowManagerService().getGameGestureLockGeneration(); }
        catch (RemoteException e) { return -1; }
    }

    /** @hide */
    public static boolean matchesStream(@NonNull MotionEvent event, int display, int device,
            long down, long generation, long currentGeneration) {
        return event.getDisplayId() == display && event.getDeviceId() == device
                && event.getDownTime() == down && generation == currentGeneration;
    }

    /** @hide */
    public static int begin(int kind, @NonNull MotionEvent event) {
        try {
            return WindowManagerGlobal.getWindowManagerService().beginGameGesture(
                    kind, event.getDisplayId(), event.getDeviceId(), event.getDownTime());
        } catch (RemoteException e) {
            return INACTIVE;
        }
    }

    /** @hide */
    public static boolean recognize(int kind, @NonNull MotionEvent event) {
        try {
            return WindowManagerGlobal.getWindowManagerService().recognizeGameGesture(
                    kind, event.getDisplayId(), event.getDeviceId(), event.getDownTime());
        } catch (RemoteException e) {
            return false;
        }
    }

    /** @hide */
    public static void end(int kind, @NonNull MotionEvent event) {
        if (event.getActionMasked() != MotionEvent.ACTION_UP
                && event.getActionMasked() != MotionEvent.ACTION_CANCEL) return;
        try {
            WindowManagerGlobal.getWindowManagerService().endGameGesture(kind,
                    event.getDisplayId(), event.getDeviceId(), event.getDownTime(),
                    event.getEventTime(), event.getActionMasked() == MotionEvent.ACTION_CANCEL);
        } catch (RemoteException ignored) {}
    }
}
