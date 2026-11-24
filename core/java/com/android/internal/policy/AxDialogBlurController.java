/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.policy;

import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.view.CrossWindowBlurListeners;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewRootImpl;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;

import com.android.internal.R;
import com.android.internal.app.AlertActivity;
import com.android.internal.graphics.drawable.BackgroundBlurDrawable;
import com.android.internal.util.ArrayUtils;

import java.util.function.Consumer;

/** @hide */
public final class AxDialogBlurController {
    private static final String BLUR_RADIUS = "system_blur_radius";
    private static final float MAX_BLUR_RADIUS_DP = 34f;
    private static final int SURFACE_ALPHA = 0x8A;
    private static final int LIGHT_FILL = 0x94FAFAFA;
    private static final float LIGHT_DIM = 0.25f;
    // The Liquid Glass switch of Evolver, a per-user secure setting. With it on a dialog takes the
    // dialog glass, which carries its own veil, so the buffer adds little to it.
    private static final String LIQUID_GLASS = "liquid_glass";
    private static final int GLASS_LIGHT_FILL = 0x00FAFAFA;
    private static final int GLASS_DARK_ALPHA = 0x33;
    private static final float GLASS_LIGHT_DIM = 0.30f;
    private static final float GLASS_BLUR_DP = 22f;
    private static final float GLASS_MIN_BLUR_DP = 4f;
    private static final String TUNE = "debug.lg.";
    private static final String[] BLUR_EXCLUDED_PACKAGES = {
            "com.google.android.projection.gearhead",
            "com.google.android.youtube",
            "com.google.android.apps.maps",
            "ru.yandex.yandexmaps",
            "ru.yandex.yandexnavi",
            "com.waze",
    };

    private final Context mContext;
    private final Window mWindow;
    private final View mRootView;
    private final WindowManager.LayoutParams mToastParams;
    private final ContentResolver mResolver;
    private final Handler mHandler;
    private final WindowManager mWindowManager;
    private final ArrayMap<View, BackgroundRenderer> mBackgrounds = new ArrayMap<>();
    private final ArraySet<BackgroundRenderer> mActiveRenderers = new ArraySet<>();
    private final ContentObserver mSettingsObserver;
    private final Consumer<Boolean> mBlurListener;
    private final ViewTreeObserver.OnPreDrawListener mPreDrawListener = () -> {
        updateBackgrounds();
        return true;
    };
    private final View.OnAttachStateChangeListener mAttachListener =
            new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View view) {
                    start();
                }

                @Override
                public void onViewDetachedFromWindow(View view) {
                    stop();
                }
            };
    private boolean mStarted;
    private boolean mCrossWindowBlurEnabled;
    private int mRadius;
    private int mGeneration;
    // Live-tunable through debug.lg.*, read with the settings and not while drawing.
    private int mLightFill = LIGHT_FILL;
    private int mDarkAlpha = SURFACE_ALPHA;
    private float mLightDim = LIGHT_DIM;
    private boolean mLiquidGlass;
    private boolean mDimOverridden;
    private boolean mDimYielded;
    private float mDimOriginal;
    private float mDimApplied;

    public static AxDialogBlurController create(Window window, DecorView decor, boolean isDialog) {
        if ((!isDialog && !window.isFloating()
                && !(window.getCallback() instanceof AlertActivity))
                || !isSupported(window.getContext())) {
            return null;
        }
        // An input method window is floating but is no dialog, and it can attach before it has
        // a window manager.
        final WindowManager windowManager = window.getWindowManager();
        if (windowManager == null
                || window.getAttributes().type == WindowManager.LayoutParams.TYPE_INPUT_METHOD) {
            return null;
        }
        return new AxDialogBlurController(window.getContext(), decor, window, null,
                windowManager);
    }

    public static AxDialogBlurController createToast(Context context, View view,
            WindowManager windowManager, WindowManager.LayoutParams params, String packageName) {
        if (params.type != WindowManager.LayoutParams.TYPE_TOAST
                || ArrayUtils.contains(BLUR_EXCLUDED_PACKAGES, packageName)
                || !isSupported(context)) {
            return null;
        }
        return new AxDialogBlurController(context, view, null, params, windowManager);
    }

    private static boolean isSupported(Context context) {
        final ApplicationInfo app = context.getApplicationInfo();
        return (app.isSystemApp() || app.isUpdatedSystemApp())
                && !ArrayUtils.contains(BLUR_EXCLUDED_PACKAGES, app.packageName)
                && CrossWindowBlurListeners.CROSS_WINDOW_BLUR_SUPPORTED
                && ActivityManager.isHighEndGfx()
                && !SystemProperties.getBoolean("persist.sysui.disableBlur", false);
    }

    private AxDialogBlurController(Context context, View rootView, Window window,
            WindowManager.LayoutParams toastParams, WindowManager windowManager) {
        mContext = context;
        mWindow = window;
        mRootView = rootView;
        mToastParams = toastParams;
        mResolver = context.getContentResolver();
        mHandler = new Handler(Looper.getMainLooper());
        mWindowManager = windowManager;
        mSettingsObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                updateSettings();
            }
        };
        mBlurListener = enabled -> {
            mCrossWindowBlurEnabled = enabled;
            updateSettings();
        };
    }

    public void prepare() {
        mCrossWindowBlurEnabled = mWindowManager.isCrossWindowBlurEnabled();
        readSettings();
        updateBackgrounds();
    }

    public void attachToView() {
        mRootView.addOnAttachStateChangeListener(mAttachListener);
        prepare();
        if (mRootView.isAttachedToWindow()) {
            start();
        }
    }

    public void start() {
        if (mStarted) return;
        mStarted = true;
        mCrossWindowBlurEnabled = mWindowManager.isCrossWindowBlurEnabled();
        mResolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.DISABLE_WINDOW_BLURS), false,
                mSettingsObserver);
        mResolver.registerContentObserver(Settings.Secure.getUriFor(BLUR_RADIUS), false,
                mSettingsObserver);
        mResolver.registerContentObserver(Settings.Secure.getUriFor(LIQUID_GLASS), false,
                mSettingsObserver);
        mResolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.DIALOG_BLUR_ENABLED), false,
                mSettingsObserver);
        mWindowManager.addCrossWindowBlurEnabledListener(mHandler::post, mBlurListener);
        mRootView.getViewTreeObserver().addOnPreDrawListener(mPreDrawListener);
        updateSettings();
    }

    public void stop() {
        mRootView.removeOnAttachStateChangeListener(mAttachListener);
        if (mStarted) {
            mStarted = false;
            mResolver.unregisterContentObserver(mSettingsObserver);
            mWindowManager.removeCrossWindowBlurEnabledListener(mBlurListener);
            if (mRootView.getViewTreeObserver().isAlive()) {
                mRootView.getViewTreeObserver().removeOnPreDrawListener(mPreDrawListener);
            }
        }
        // The window may be shown again, so it must not keep the dim of the blurred dialog. It is
        // going away here, so the attributes are not dispatched.
        if (mDimOverridden) {
            mDimOverridden = false;
            final WindowManager.LayoutParams attrs = mWindow.getAttributes();
            if (attrs.dimAmount == mDimApplied) {
                attrs.dimAmount = mDimOriginal;
            }
        }
        mDimYielded = false;
        mRadius = 0;
        updateBackgrounds();
        for (int i = mActiveRenderers.size() - 1; i >= 0; i--) {
            final BackgroundRenderer renderer = mActiveRenderers.valueAt(i);
            renderer.clear();
            renderer.mView.invalidate();
        }
    }

    boolean hasBlurBackgrounds() {
        return !mBackgrounds.isEmpty();
    }

    private void updateSettings() {
        if (!mStarted) return;
        readSettings();
        updateBackgrounds();
        for (int i = 0; i < mActiveRenderers.size(); i++) {
            final BackgroundRenderer renderer = mActiveRenderers.valueAt(i);
            renderer.mBlur.setBlurRadius(mRadius);
            renderer.mView.invalidate();
        }
        for (int i = 0; i < mBackgrounds.size(); i++) {
            mBackgrounds.keyAt(i).invalidate();
        }
        mRootView.invalidate();
    }

    private void readSettings() {
        final int defaultDisabled =
                SystemProperties.getBoolean("ro.custom.blur.enable", true) ? 0 : 1;
        final boolean enabled = mCrossWindowBlurEnabled
                && Settings.Global.getInt(mResolver, Settings.Global.DISABLE_WINDOW_BLURS,
                        defaultDisabled) == 0
                && Settings.System.getInt(mResolver, Settings.System.DIALOG_BLUR_ENABLED, 1) != 0;
        final float maxRadius = MAX_BLUR_RADIUS_DP
                * mContext.getResources().getDisplayMetrics().density;
        final float radius = Settings.Secure.getFloat(mResolver, BLUR_RADIUS, maxRadius);
        final float cappedRadius = Float.isFinite(radius)
                ? Math.max(0f, Math.min(radius, maxRadius)) : maxRadius;
        // Toasts keep the committed look; the dialog glass is for windows.
        mLiquidGlass = mWindow != null && Settings.Secure.getIntForUser(mResolver, LIQUID_GLASS, 0,
                mContext.getUserId()) != 0;
        float targetRadius = cappedRadius;
        if (mLiquidGlass) {
            final float blurDp = Math.max(GLASS_MIN_BLUR_DP,
                    Math.min(MAX_BLUR_RADIUS_DP, tuneFloat("dialog_blur_dp", GLASS_BLUR_DP)));
            targetRadius = Math.min(cappedRadius,
                    blurDp * mContext.getResources().getDisplayMetrics().density);
        }
        mRadius = enabled ? Math.round(targetRadius) : 0;
        mLightFill = tuneColor("dialog_light_fill", mLiquidGlass ? GLASS_LIGHT_FILL : LIGHT_FILL);
        mDarkAlpha = Math.max(0, Math.min(255, SystemProperties.getInt(TUNE + "dialog_dark_alpha",
                mLiquidGlass ? GLASS_DARK_ALPHA : SURFACE_ALPHA)));
        mLightDim = Math.max(0f, Math.min(1f, tuneFloat("dialog_light_dim",
                mLiquidGlass ? GLASS_LIGHT_DIM : LIGHT_DIM)));
    }

    private static int tuneColor(String name, int def) {
        String value = SystemProperties.get(TUNE + name);
        if (value.startsWith("0x")) value = value.substring(2);
        else if (value.startsWith("#")) value = value.substring(1);
        try {
            return (int) Long.parseLong(value, 16);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static float tuneFloat(String name, float def) {
        try {
            final float value = Float.parseFloat(SystemProperties.get(TUNE + name));
            return Float.isFinite(value) ? value : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /**
     * A blurred dialog with a light surface dims what is behind it less, as the dim shows through
     * the light glass. The dim the dialog had is back when the blur or the light surface is gone.
     */
    private void updateDim() {
        if (mWindow == null) return;
        final WindowManager.LayoutParams attrs = mWindow.getAttributes();
        if (mDimOverridden && attrs.dimAmount != mDimApplied) {
            // The app set a dim of its own meanwhile; it keeps it while this window lives.
            mDimOverridden = false;
            mDimYielded = true;
        }
        // The dim is decided whether or not the window dims yet: the window manager ignores the
        // amount until the dim flag is set, so the light amount is already in place when the flag
        // arrives and the dim fades in once. A light surface with no alpha is hidden, for instance
        // while the dialog launches from or returns to its view.
        boolean light = false;
        boolean lightHidden = false;
        if (!mDimYielded && mRadius > 0) {
            for (int i = mBackgrounds.size() - 1; i >= 0 && !light; i--) {
                final BackgroundRenderer renderer = mBackgrounds.valueAt(i);
                final Drawable surface = surfaceDrawable(renderer.mBackground);
                if (surface == null || renderer.isDarkSurface(surface)) continue;
                if (renderer.mBackground.getAlpha() == 0) {
                    lightHidden = true;
                } else {
                    light = surface.getOpacity() != PixelFormat.TRANSPARENT;
                }
            }
        }
        if (lightHidden) {
            // A hidden light surface takes or keeps the light dim, so the dim does not change
            // while the surface is hidden and shown again.
            light = true;
        }
        if (light) {
            if (!mDimOverridden) {
                // A dim that differs from the theme dim was set by the app before the window was
                // shown, so the app keeps it.
                if (mLiquidGlass && dimDiffersFromTheme(attrs.dimAmount)) {
                    mDimYielded = true;
                    return;
                }
                mDimOriginal = attrs.dimAmount;
                mDimOverridden = true;
            } else if (mLiquidGlass && dimDiffersFromTheme(mDimOriginal)) {
                // Liquid Glass came on while the light dim held, and the app's own dim differs
                // from the theme dim: the app gets its dim back and keeps it.
                mDimOverridden = false;
                mDimYielded = true;
                mWindow.setDimAmount(mDimOriginal);
                return;
            }
            // The light dim never makes a dialog that dims less than it darker.
            final float lightDim = Math.min(mLightDim, mDimOriginal);
            if (attrs.dimAmount != lightDim) {
                mWindow.setDimAmount(lightDim);
            }
            mDimApplied = lightDim;
        } else if (mDimOverridden) {
            mDimOverridden = false;
            mWindow.setDimAmount(mDimOriginal);
        }
    }

    private boolean dimDiffersFromTheme(float dimAmount) {
        // The default is the one the window takes when its theme names no dim.
        final TypedArray a = mContext.obtainStyledAttributes(
                new int[] {android.R.attr.backgroundDimAmount});
        try {
            return Math.abs(dimAmount - a.getFloat(0, 0.5f)) > 0.001f;
        } finally {
            a.recycle();
        }
    }

    private void updateBackgrounds() {
        final boolean hadBackgrounds = hasBlurBackgrounds();
        mGeneration++;
        if (mRadius > 0) {
            addBackgrounds(mRootView);
        }
        for (int i = mBackgrounds.size() - 1; i >= 0; i--) {
            final BackgroundRenderer renderer = mBackgrounds.valueAt(i);
            if (renderer.mGeneration != mGeneration) {
                final View view = mBackgrounds.keyAt(i);
                if (view.getAxDialogBlurRenderer() == renderer) {
                    view.setAxDialogBlurRenderer(null);
                } else {
                    renderer.clear();
                }
                mBackgrounds.removeAt(i);
            }
        }
        if (mWindow != null && hadBackgrounds != hasBlurBackgrounds()) {
            ((DecorView) mRootView).updateSystemDialogBlurOpacity();
        }
        updateDim();
    }

    private void addBackgrounds(View view) {
        if (Boolean.TRUE.equals(view.getTag(R.id.ax_dialog_blur_owned))) return;
        final Drawable background = view.getBackground();
        final boolean fullScreenDecor = mWindow != null && view == mRootView
                && mWindow.getAttributes().width == ViewGroup.LayoutParams.MATCH_PARENT
                && mWindow.getAttributes().height == ViewGroup.LayoutParams.MATCH_PARENT;
        BackgroundRenderer renderer = mBackgrounds.get(view);
        if (!fullScreenDecor && background != null) {
            if (renderer != null && renderer.mBackground == background
                    && view.getAxDialogBlurRenderer() == renderer) {
                renderer.mGeneration = mGeneration;
                return;
            }
            if (background.getOpacity() != PixelFormat.TRANSPARENT) {
                if (surfaceDrawable(background) != null) {
                    renderer = new BackgroundRenderer(view, background);
                    mBackgrounds.put(view, renderer);
                    view.setAxDialogBlurRenderer(renderer);
                    renderer.mGeneration = mGeneration;
                }
                return;
            }
        }
        if (view instanceof ViewGroup) {
            final ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                final View child = group.getChildAt(i);
                if (child instanceof ViewGroup) {
                    addBackgrounds(child);
                }
            }
        }
    }

    /** Whether the background draws nothing but its surface, through state and wrapper drawables. */
    private static boolean isPlainSurface(Drawable background, Drawable surface) {
        Drawable drawable = background;
        while (drawable != null && drawable != surface) {
            if (drawable.getCurrent() != drawable) {
                drawable = drawable.getCurrent();
            } else if (drawable instanceof DrawableWrapper) {
                drawable = ((DrawableWrapper) drawable).getDrawable();
            } else {
                return false;
            }
        }
        return drawable == surface;
    }

    private static Drawable surfaceDrawable(Drawable drawable) {
        if (drawable == null || drawable instanceof BackgroundBlurDrawable) return null;
        if (drawable.getCurrent() != drawable) {
            return surfaceDrawable(drawable.getCurrent());
        }
        if (drawable instanceof DrawableWrapper) {
            return surfaceDrawable(((DrawableWrapper) drawable).getDrawable());
        }
        if (drawable instanceof LayerDrawable) {
            final LayerDrawable layers = (LayerDrawable) drawable;
            for (int i = 0; i < layers.getNumberOfLayers(); i++) {
                if (layers.getDrawable(i) instanceof BackgroundBlurDrawable) return null;
            }
            for (int i = 0; i < layers.getNumberOfLayers(); i++) {
                final Drawable surface = surfaceDrawable(layers.getDrawable(i));
                if (surface != null && surface.getOpacity() != PixelFormat.TRANSPARENT) {
                    return surface;
                }
            }
            return null;
        }
        if (drawable instanceof ColorDrawable) return drawable;
        if (drawable instanceof GradientDrawable) {
            final GradientDrawable gradient = (GradientDrawable) drawable;
            return gradient.getShape() == GradientDrawable.RECTANGLE ? gradient : null;
        }
        return null;
    }

    public final class BackgroundRenderer {
        private final View mView;
        private final Drawable mBackground;
        private BackgroundBlurDrawable mBlur;
        private ViewRootImpl mBlurRoot;
        private int mGeneration;

        private BackgroundRenderer(View view, Drawable background) {
            mView = view;
            mBackground = background;
        }

        public BackgroundRenderer copyForView(View view) {
            return new BackgroundRenderer(view, mBackground);
        }

        public void draw(Canvas canvas, Drawable background) {
            final ViewRootImpl root = mView.getViewRootImpl();
            if (mRadius <= 0 || !canvas.isHardwareAccelerated() || root == null
                    || background.getAlpha() == 0
                    || Boolean.TRUE.equals(mView.getTag(R.id.ax_dialog_blur_owned))) {
                clear();
                background.draw(canvas);
                return;
            }
            final Drawable surface = surfaceDrawable(background);
            if (surface == null || surface.getBounds().isEmpty()) {
                clear();
                background.draw(canvas);
                return;
            }
            if (mBlurRoot != root) {
                clear();
            }
            if (mBlur == null) {
                mBlurRoot = root;
                mBlur = root.createBackgroundBlurDrawable();
                mBlur.setColor(Color.TRANSPARENT);
                mActiveRenderers.add(this);
            }
            mBlur.setBounds(surface.getBounds());
            mBlur.setBlurRadius(mRadius);
            final boolean dark = isDarkSurface(surface);
            if (mLiquidGlass) {
                mBlur.setMaterial(dark
                        ? BackgroundBlurDrawable.MATERIAL_DIALOG_DARK
                        : BackgroundBlurDrawable.MATERIAL_DIALOG_LIGHT);
            } else {
                mBlur.setMaterial(dark
                        ? BackgroundBlurDrawable.MATERIAL_CARD_DARK
                        : BackgroundBlurDrawable.MATERIAL_CARD_LIGHT);
            }
            final float windowAlpha =
                    mWindow != null ? mWindow.getAttributes().alpha : mToastParams.alpha;
            final float viewAlpha = mView.getAlpha();
            mBlur.setAlpha(Float.isFinite(windowAlpha) && Float.isFinite(viewAlpha)
                    ? Math.round(background.getAlpha()
                            * Math.max(0f, Math.min(1f, windowAlpha))
                            * Math.max(0f, Math.min(1f, viewAlpha))) : 0);
            if (surface instanceof GradientDrawable) {
                final GradientDrawable gradient = (GradientDrawable) surface;
                final float[] radii = gradient.getCornerRadii();
                if (radii != null && radii.length >= 8) {
                    mBlur.setCornerRadius((radii[0] + radii[1]) * 0.5f,
                            (radii[2] + radii[3]) * 0.5f,
                            (radii[6] + radii[7]) * 0.5f,
                            (radii[4] + radii[5]) * 0.5f);
                } else {
                    mBlur.setCornerRadius(gradient.getCornerRadius());
                }
            } else {
                mBlur.setCornerRadius(0f);
            }
            mBlur.draw(canvas);
            final Rect bounds = background.getBounds();
            // A plain light surface is replaced by a neutral fill in the same shape, so the
            // rounded corners stay as the app drew them. Any other keeps the app's own fill,
            // scaled down, as a layered one would lose the colours of its other layers.
            final boolean neutral = !dark && isPlainSurface(background, surface);
            if (neutral && Color.alpha(mLightFill) == 0) {
                // The glass is the whole surface; a fill of no alpha would only erase the layer.
                return;
            }
            final int save = neutral
                    ? canvas.saveLayer(bounds.left, bounds.top, bounds.right, bounds.bottom,
                            null)
                    : canvas.saveLayerAlpha(bounds.left, bounds.top, bounds.right, bounds.bottom,
                            mDarkAlpha);
            try {
                background.draw(canvas);
                if (neutral) {
                    canvas.drawColor(mLightFill, PorterDuff.Mode.SRC_IN);
                }
            } finally {
                canvas.restoreToCount(save);
            }
        }

        /**
         * Whether the surface drawn over the blur is dark, so the blur takes the dark card glass
         * under it and the light one otherwise, instead of a glass that does not match it.
         */
        private boolean isDarkSurface(Drawable surface) {
            // What is drawn is the colour of an opaque filter, not the solid under it: AppCompat
            // dialogs hold a white solid and tint it to the theme background that way. A tint
            // list has no getter on these drawables, so it cannot be read here.
            int color = opaqueFilterColor(surface);
            if (color == Color.TRANSPARENT) {
                color = opaqueFilterColor(mBackground);
            }
            if (color != Color.TRANSPARENT) {
                return Color.luminance(color) < 0.5f;
            }
            boolean known = false;
            if (surface instanceof ColorDrawable) {
                color = ((ColorDrawable) surface).getColor();
                known = true;
            } else if (surface instanceof GradientDrawable) {
                final ColorStateList list = ((GradientDrawable) surface).getColor();
                if (list != null) {
                    color = list.getColorForState(mView.getDrawableState(),
                            list.getDefaultColor());
                    known = true;
                }
            }
            if (known && Color.alpha(color) != 0) {
                return Color.luminance(color) < 0.5f;
            }
            return (mView.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        }

        private int opaqueFilterColor(Drawable drawable) {
            final ColorFilter filter = drawable != null ? drawable.getColorFilter() : null;
            if (filter instanceof PorterDuffColorFilter) {
                final PorterDuffColorFilter tint = (PorterDuffColorFilter) filter;
                final PorterDuff.Mode mode = tint.getMode();
                if ((mode == PorterDuff.Mode.SRC_IN || mode == PorterDuff.Mode.SRC)
                        && Color.alpha(tint.getColor()) == 0xFF) {
                    return tint.getColor();
                }
            }
            return Color.TRANSPARENT;
        }

        public void clear() {
            if (mBlur != null) {
                mBlur.setBlurRadius(0);
                mBlur = null;
            }
            mBlurRoot = null;
            mActiveRenderers.remove(this);
        }
    }
}
