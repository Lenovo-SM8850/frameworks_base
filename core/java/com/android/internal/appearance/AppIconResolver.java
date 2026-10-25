// SPDX-License-Identifier: Apache-2.0

package com.android.internal.appearance;

import android.app.ActivityThread;
import android.app.Application;
import android.app.ApplicationPackageManager;
import android.content.BroadcastReceiver;
import android.content.ComponentCallbacks;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.ThemeEngine;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.LruCache;
import android.util.SparseArray;

import java.lang.ref.WeakReference;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Shared, unbadged app icon policy. Raw resources and package-item identity enter here before
 * presentation. No shortcut, avatar, provider or arbitrary drawable is eligible.
 * @hide
 */
public final class AppIconResolver {
    private static final ThreadLocal<Integer> RAW_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final Map<Resources, ResourceOwner> RESOURCE_APPS = new WeakHashMap<>();
    private static final ThreadLocal<ResourceOwner> RESOURCE_LOAD = new ThreadLocal<>();

    private static final class ResourceOwner {
        final WeakReference<Resources> resources;
        final ApplicationInfo app;
        boolean ambiguousUser;

        ResourceOwner(Resources resources, ApplicationInfo app) {
            this.resources = new WeakReference<>(resources);
            this.app = app;
        }
    }
    private static final LruCache<String, IconIndex> INDEX = new LruCache<>(256);
    private static final LruCache<String, Drawable.ConstantState> ICONS = new LruCache<>(192);
    private static final LruCache<String, Boolean> UNCHANGED = new LruCache<>(256);
    private static final Map<Integer, Bundle> STATES = new HashMap<>();
    private static volatile boolean sInitialized;
    private static android.app.PropertyInvalidatedCache.NonceWatcher sRevisionWatcher;
    private static long sGeneration;

    private AppIconResolver() {}

    public static void beginRawLoad() {
        RAW_DEPTH.set(RAW_DEPTH.get() + 1);
    }

    public static void endRawLoad() {
        RAW_DEPTH.set(Math.max(0, RAW_DEPTH.get() - 1));
    }

    private static boolean isPresentationProcess() {
        Application application = ActivityThread.currentApplication();
        if (application == null || (application.getApplicationInfo().flags
                & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0) {
            return false;
        }
        String name = ActivityThread.currentPackageName();
        return "com.google.android.apps.nexuslauncher".equals(name)
                || "com.android.launcher3".equals(name)
                || "com.android.systemui".equals(name);
    }

    public static void registerResources(Resources resources, ApplicationInfo app) {
        if (!isPresentationProcess()) return;
        ResourceOwner load = new ResourceOwner(resources, app);
        RESOURCE_LOAD.set(load);
        synchronized (RESOURCE_APPS) {
            ResourceOwner owner = RESOURCE_APPS.get(resources);
            if (owner == null) {
                RESOURCE_APPS.put(resources, load);
            } else if (owner.app.uid != app.uid) {
                // ResourcesManager can share an APK's Resources across profile UIDs.
                // An immediate getResourcesForApplication/load pair retains the caller's user;
                // later unqualified cross-thread loads must fail closed rather than borrow it.
                owner.ambiguousUser = true;
            }
        }
    }

    private static void initialize(Context context) {
        if (sInitialized) return;
        synchronized (STATES) {
            if (sInitialized) return;
            sRevisionWatcher = android.app.PropertyInvalidatedCache.getNonceWatcher(
                    ThemeEngine.APP_ICON_CACHE_KEY);
            Handler handler = new Handler(Looper.getMainLooper());
            ContentObserver observer = new ContentObserver(handler) {
                @Override
                public void onChange(boolean selfChange) {
                    invalidate();
                }
            };
            for (String key : new String[] {ThemeEngine.SETTINGS_THEME_ENGINE_DATA,
                    Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES}) {
                context.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(key),
                        false, observer);
            }
            ThemeEngine engine = ThemeEngine.getInstance(context);
            if (engine != null) engine.addThemeChangeListener(category -> invalidate());
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_PACKAGE_ADDED);
            filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
            filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
            filter.addDataScheme("package");
            context.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context ignored, Intent intent) {
                    invalidate();
                }
            }, filter, null, handler);
            context.registerComponentCallbacks(new ComponentCallbacks() {
                @Override
                public void onConfigurationChanged(Configuration configuration) {
                    invalidate();
                }
                @Override
                public void onLowMemory() {
                    invalidate();
                }
            });
            sInitialized = true;
        }
    }

    public static void invalidate() {
        synchronized (STATES) {
            sGeneration++;
            STATES.clear();
            INDEX.evictAll();
            ICONS.evictAll();
            UNCHANGED.evictAll();
        }
        ApplicationPackageManager.invalidateAppIconCache();
    }

    private static Bundle state(Context context, int userId) {
        initialize(context);
        synchronized (STATES) {
            // Shared-memory revision closes the race between theme and launcher Binder callbacks.
            // This read is local; there is no Binder/XML/image work on a warm icon load.
            if (sRevisionWatcher.isChanged()) invalidate();
            Bundle state = STATES.get(userId);
            if (state != null) return state;
            ThemeEngine engine = ThemeEngine.getInstance(context);
            state = engine == null ? null : engine.getAppIconAppearance(null, userId);
            // Do not cache service startup/death failures as a permanently disabled policy.
            if (state != null) STATES.put(userId, state);
            return state;
        }
    }

    private static boolean enabled(Context context, int userId) {
        Bundle state = state(context, userId);
        return state != null && state.getBoolean("enabled");
    }

    private static final class IconIndex {
        final SparseArray<ComponentName> resources = new SparseArray<>();
        final String version;
        Resources exactResources;

        IconIndex(String packageName, Bundle data) {
            version = data.getLong("version") + ":" + data.getLong("updated");
            int[] ids = data.getIntArray("ids");
            String[] classes = data.getStringArray("classes");
            if (ids == null || classes == null || ids.length != classes.length) return;
            for (int i = 0; i < ids.length; i++) {
                resources.put(ids[i], new ComponentName(packageName, classes[i]));
            }
        }
    }

    private static IconIndex index(Context context, ApplicationInfo app, int userId) {
        String key = userId + ":" + app.packageName;
        IconIndex found = INDEX.get(key);
        if (found != null) return found;
        long generation;
        synchronized (STATES) {
            generation = sGeneration;
        }
        ThemeEngine engine = ThemeEngine.getInstance(context);
        Bundle data = engine == null ? null : engine.getAppIconResources(app.packageName, userId);
        if (data == null) return null;
        found = new IconIndex(app.packageName, data);
        synchronized (STATES) {
            if (generation == sGeneration) INDEX.put(key, found);
        }
        return found;
    }

    public static Drawable resolveResource(Resources resources, int id, int density,
            Resources.Theme theme, Drawable raw) {
        if (raw == null || RAW_DEPTH.get() != 0 || !isPresentationProcess()) return raw;
        Application application = ActivityThread.currentApplication();
        if (application == null) return raw;
        ApplicationInfo app;
        ResourceOwner load = RESOURCE_LOAD.get();
        if (load != null && load.resources.get() == resources) {
            app = load.app;
            RESOURCE_LOAD.remove();
        } else {
            synchronized (RESOURCE_APPS) {
                ResourceOwner owner = RESOURCE_APPS.get(resources);
                app = owner == null || owner.ambiguousUser ? null : owner.app;
            }
        }
        if (app == null) return raw;
        int userId = UserHandle.getUserId(app.uid);
        try {
            if (!enabled(application, userId)) return raw;
            IconIndex index = index(application, app, userId);
            if (index == null) return raw;
            ComponentName component = index.resources.get(id);
            if (component == null) return raw;
            return resolve(application, app, component, userId, id, density, resources, theme,
                    index.version, raw);
        } catch (RuntimeException e) {
            return raw;
        }
    }

    public static Drawable resolveItem(Context context, PackageItemInfo item,
            ApplicationInfo app, Drawable raw) {
        if (RAW_DEPTH.get() != 0 || raw == null || !isPresentationProcess()) return raw;
        if (item instanceof ApplicationInfo) app = (ApplicationInfo) item;
        if (!(item instanceof ActivityInfo) && !(item instanceof ApplicationInfo)) return raw;
        if (app == null && item instanceof ActivityInfo) app = ((ActivityInfo) item).applicationInfo;
        if (app == null) return raw;
        ComponentName component = new ComponentName(app.packageName,
                item instanceof ActivityInfo ? item.name : "");
        int id = item instanceof ActivityInfo ? ((ActivityInfo) item).getIconResource() : item.icon;
        return resolveExact(context, app, component, UserHandle.getUserId(app.uid), id, 0, raw);
    }

    /** Whether a launcher fallback must load unbadged artwork before composition. */
    public static boolean shouldResolve(int userId) {
        Application application = ActivityThread.currentApplication();
        if (application == null) return false;
        try {
            Bundle state = state(application, userId);
            return state != null && state.getBoolean("enabled");
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static Drawable resolveActivity(ActivityInfo activity, int userId, int density,
            Drawable raw) {
        Application application = ActivityThread.currentApplication();
        // Keep the existing LauncherActivityInfo pack behavior for other launcher clients too.
        if (application == null || RAW_DEPTH.get() != 0) return raw;
        return resolveExact(application, activity.applicationInfo,
                new ComponentName(activity.packageName, activity.name), userId,
                activity.getIconResource(), density, raw);
    }

    private static Drawable resolveExact(Context context, ApplicationInfo app,
            ComponentName component, int userId, int id, int density, Drawable raw) {
        if (raw == null) return null;
        try {
            if (!enabled(context, userId)) return raw;
            IconIndex index = index(context, app, userId);
            if (index == null) return raw;
            beginRawLoad();
            try {
                Resources resources = index.exactResources;
                if (resources == null) {
                    resources = context.getPackageManager().getResourcesForApplication(app);
                    index.exactResources = resources;
                }
                return resolve(context, app, component, userId, id, density, resources, null,
                        index.version, raw);
            } finally {
                endRawLoad();
            }
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return raw;
        }
    }

    private static Drawable resolve(Context context, ApplicationInfo app, ComponentName component,
            int userId, int id, int density, Resources resources, Resources.Theme theme,
            String version, Drawable raw) {
        Bundle state = state(context, userId);
        if (state == null || !state.getBoolean("enabled")) return raw;
        long generation;
        synchronized (STATES) {
            generation = sGeneration;
        }
        String key = userId + ":" + component.flattenToString() + ":" + version + ":" + id
                + ":" + density + ":" + resources.getConfiguration() + ":"
                + context.getResources().getString(com.android.internal.R.string.config_icon_mask)
                + ":" + state.getLong("revision") + ":" + generation + ":" + LocalDate.now()
                + ":" + (theme == null ? 0 : theme.hashCode());
        if (UNCHANGED.get(key) != null) return raw;
        Drawable.ConstantState cached = ICONS.get(key);
        if (cached != null) return cached.newDrawable(resources, theme).mutate();
        beginRawLoad();
        try {
            Drawable replacement = null;
            if (state.getBoolean("enabled")) {
                ThemeEngine engine = ThemeEngine.getInstance(context);
                Bundle recipe = engine == null ? null
                        : engine.getAppIconAppearance(component, userId);
                if (recipe != null) {
                    try {
                        replacement = fromPack(context, recipe, component, density, resources, raw);
                    } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
                        // A stale/corrupt pack must leave the original icon intact.
                    }
                }
            }
            if (replacement == null) {
                // Negative entries also cover icons without ConstantState, with no warm Binder call.
                synchronized (STATES) {
                    if (generation == sGeneration) UNCHANGED.put(key, true);
                }
                return raw;
            }
            Drawable.ConstantState constant = replacement.getConstantState();
            if (constant != null) {
                synchronized (STATES) {
                    if (generation == sGeneration) ICONS.put(key, constant);
                }
                return constant.newDrawable(resources, theme).mutate();
            }
            return replacement;
        } catch (RuntimeException e) {
            return raw;
        } finally {
            endRawLoad();
        }
    }

    /** Existing ThemeEngine convenience API, using the same recipe/compositor. */
    public static Drawable resolvePack(Context context, ComponentName component, int density) {
        beginRawLoad();
        try {
            ActivityInfo info = context.getPackageManager().getActivityInfo(component, 0);
            Resources resources = context.getPackageManager().getResourcesForApplication(
                    info.applicationInfo);
            Drawable raw = resources.getDrawableForDensityRaw(info.getIconResource(), density, null);
            ThemeEngine engine = ThemeEngine.getInstance(context);
            Bundle recipe = engine == null ? null
                    : engine.getAppIconAppearance(component, context.getUserId());
            return recipe == null ? null
                    : fromPack(context, recipe, component, density, resources, raw);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return null;
        } finally {
            endRawLoad();
        }
    }

    private static Drawable packDrawable(Resources resources, String pack, String name, int density) {
        if (name == null) return null;
        int id = resources.getIdentifier(name, "drawable", pack);
        return id == 0 ? null : resources.getDrawableForDensityRaw(id, density, null);
    }

    private static Drawable fromPack(Context context, Bundle recipe, ComponentName component,
            int density, Resources target, Drawable raw) throws PackageManager.NameNotFoundException {
        String pack = recipe.getString("pack");
        if (pack == null) return null;
        ApplicationInfo packInfo = recipe.getParcelable("packInfo", ApplicationInfo.class);
        if (packInfo == null) return null;
        Resources resources = context.getPackageManager().getResourcesForApplication(packInfo);
        String name = recipe.getString("calendar");
        Drawable mapped = name == null ? null : packDrawable(resources, pack,
                name + LocalDate.now().getDayOfMonth(), density);
        if (mapped == null) {
            mapped = packDrawable(resources, pack, recipe.getString("drawable"), density);
        }
        if (mapped instanceof AdaptiveIconDrawable) {
            AdaptiveIconDrawable adaptive = (AdaptiveIconDrawable) mapped;
            if (adaptive.getMonochrome() == null && raw instanceof AdaptiveIconDrawable
                    && ((AdaptiveIconDrawable) raw).getMonochrome() != null) {
                return new AdaptiveIconDrawable(adaptive.getBackground(), adaptive.getForeground(),
                        copyMonochrome(raw, target));
            }
            return adaptive;
        }
        if (mapped != null) {
            AdaptiveIconDrawable result = compatibility(target, mapped, density);
            Drawable monochrome = recipe.getBoolean("monochrome")
                    ? new InsetDrawable(mapped, (1f - visibleLayerFraction(1f)) / 2f)
                    : raw instanceof AdaptiveIconDrawable
                            ? copyMonochrome(raw, target) : null;
            return new AdaptiveIconDrawable(result.getBackground(), result.getForeground(),
                    monochrome);
        }
        Drawable back = packDrawable(resources, pack, recipe.getString("back"), density);
        Drawable mask = packDrawable(resources, pack, recipe.getString("mask"), density);
        Drawable upon = packDrawable(resources, pack, recipe.getString("upon"), density);
        float scale = recipe.getFloat("scale", 1f);
        if (back == null && mask == null && upon == null && scale == 1f) return null;
        Bitmap image = composeFallbackBitmap(raw, back, mask, upon,
                scale, renderSize(target, density));
        return new AdaptiveIconDrawable(new ColorDrawable(Color.TRANSPARENT),
                new InsetDrawable(new BitmapDrawable(target, image),
                        (1f - visibleLayerFraction(1f)) / 2f),
                raw instanceof AdaptiveIconDrawable
                        ? copyMonochrome(raw, target) : null);
    }

    static Bitmap composeFallbackBitmap(Drawable raw, Drawable back, Drawable mask,
            Drawable upon, float scale, int size) {
        Bitmap image = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(image);
        drawFitted(canvas, raw, size, scale);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        if (mask != null) {
            Bitmap maskImage = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            drawFitted(new Canvas(maskImage), mask, size, 1f);
            paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
            canvas.drawBitmap(maskImage, 0, 0, paint);
            maskImage.recycle();
            paint.setXfermode(null);
        }
        if (back != null) {
            Bitmap backImage = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            drawFitted(new Canvas(backImage), back, size, 1f);
            paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OVER));
            canvas.drawBitmap(backImage, 0, 0, paint);
            backImage.recycle();
            paint.setXfermode(null);
        }
        if (upon != null) drawFitted(canvas, upon, size, 1f);
        return image;
    }

    private static Drawable copyMonochrome(Drawable raw, Resources resources) {
        Drawable mono = ((AdaptiveIconDrawable) raw).getMonochrome();
        Drawable.ConstantState state = mono == null ? null : mono.getConstantState();
        return state == null ? null : state.newDrawable(resources).mutate();
    }

    private static AdaptiveIconDrawable compatibility(Resources resources, Drawable artwork,
            int density) {
        Bitmap image = Bitmap.createBitmap(renderSize(resources, density),
                renderSize(resources, density), Bitmap.Config.ARGB_8888);
        drawFitted(new Canvas(image), artwork, image.getWidth(), 1f);
        return new AdaptiveIconDrawable(new ColorDrawable(Color.TRANSPARENT),
                new InsetDrawable(new BitmapDrawable(resources, image),
                        (1f - visibleLayerFraction(1f)) / 2f));
    }

    /** Fraction of the oversized adaptive layer needed for a given visible-mask width. */
    public static float visibleLayerFraction(float visibleFraction) {
        return visibleFraction / (1f + 2f * AdaptiveIconDrawable.getExtraInsetFraction());
    }

    private static int renderSize(Resources resources, int density) {
        int dpi = density == 0 ? resources.getDisplayMetrics().densityDpi : density;
        return Math.max(96, Math.min(384, dpi * 48 / 160));
    }

    private static void drawFitted(Canvas canvas, Drawable drawable, int size, float scale) {
        Rect old = new Rect(drawable.getBounds());
        int width = Math.max(1, drawable.getIntrinsicWidth());
        int height = Math.max(1, drawable.getIntrinsicHeight());
        float factor = size * scale / Math.max(width, height);
        int w = Math.max(1, Math.round(width * factor));
        int h = Math.max(1, Math.round(height * factor));
        try {
            drawable.setBounds((size - w) / 2, (size - h) / 2,
                    (size + w) / 2, (size + h) / 2);
            drawable.draw(canvas);
        } finally {
            drawable.setBounds(old);
        }
    }
}
