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

package com.android.server.theme;

import static android.content.res.ThemeEngine.*;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.IThemeEngineCallback;
import android.content.res.IThemeEngineManager;
import android.content.res.Resources;
import android.content.res.ThemeEngine;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.UserManager;
import android.content.pm.ResolveInfo;
import android.content.res.XmlResourceParser;
import android.util.Xml;
import com.android.server.LocalServices;
import com.android.server.pm.LauncherAppsService;
import java.io.InputStream;
import java.io.FilterInputStream;
import java.util.Collections;
import java.util.LinkedHashSet;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.LruCache;
import android.util.Slog;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;

import com.android.server.SystemService;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** @hide */
public class ThemeEngineManagerService extends SystemService {
    private static final String TAG = "ThemeEngineManagerService";
    private static final boolean DEBUG = false;

    private static final String TARGET_ARRAY_SYSTEMUI = "target_systemui";
    private static final String TARGET_ARRAY_ANDROID = "target_android";
    private static final String TARGET_ARRAY_SETTINGS = "target_settings";

    private static final int BITMAP_CACHE_SIZE = 5 * 1024 * 1024;

    private final Context mContext;
    private final BinderService mBinderService;
    private final Handler mHandler;
    private final HandlerThread mHandlerThread;

    private final Map<String, String> mEnabledThemes = new ConcurrentHashMap<>();
    private final Map<String, Resources> mThemeResourcesCache = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> mTargetArrayCache = new ConcurrentHashMap<>();
    private final Map<String, String> mResourceCategoryCache = new ConcurrentHashMap<>();
    private final Map<String, String> mCategoryThemes = new ConcurrentHashMap<>();
    private final List<String> mSystemThemeIconTargets = new CopyOnWriteArrayList<>();

    private volatile String mActiveSystemThemeIcons = null;
    private volatile String mIconPackPackage = null;

    private final Map<ComponentName, String> mIconPackMap = new ConcurrentHashMap<>();
    private final List<String> mIconBackList = new CopyOnWriteArrayList<>();
    private final List<String> mIconMaskList = new CopyOnWriteArrayList<>();
    private volatile float mIconScale = 1.0f;

    private final Map<String, String> mPerAppIconPacks = new ConcurrentHashMap<>();
    private final Map<String, Map<ComponentName, String>> mPerAppIconPackMaps =
            new ConcurrentHashMap<>();

    private final Map<Integer, List<String>> mInstalledIconPacksCache = new ConcurrentHashMap<>();

    private final LruCache<String, Bitmap> mBitmapCache;
    private int mLastUiMode = Configuration.UI_MODE_NIGHT_UNDEFINED;
    private int mCachedTextColorPrimary = 0;
    private boolean mTextColorPrimaryCached = false;
    private ContentObserver mSettingsObserver;

    // Published only after parsing on mHandler. Readers never observe a half-loaded pack.
    private final Map<Integer, IconAppearance> mIconAppearances = new ConcurrentHashMap<>();
    private long mIconRevision;

    private static final class IconPack {
        final String packageName;
        final Map<ComponentName, String> icons;
        final Map<ComponentName, String> calendars;
        final List<String> backs;
        final List<String> masks;
        final List<String> upons;
        final float scale;
        final int capabilities;

        IconPack(String packageName, Map<ComponentName, String> icons,
                Map<ComponentName, String> calendars, List<String> backs, List<String> masks,
                List<String> upons, float scale, int capabilities) {
            this.packageName = packageName;
            this.icons = Collections.unmodifiableMap(new HashMap<>(icons));
            this.calendars = Collections.unmodifiableMap(new HashMap<>(calendars));
            this.backs = List.copyOf(backs);
            this.masks = List.copyOf(masks);
            this.upons = List.copyOf(upons);
            this.scale = scale;
            this.capabilities = capabilities;
        }
    }

    private static final class IconAppearance {
        final IconPack pack;
        final Map<String, IconPack> perApp;
        final long revision;

        IconAppearance(IconPack pack, Map<String, IconPack> perApp, long revision) {
            this.pack = pack;
            this.perApp = Collections.unmodifiableMap(new HashMap<>(perApp));
            this.revision = revision;
        }
    }

    private void reloadIconAppearances() {
        Map<Integer, IconAppearance> next = new HashMap<>();
        UserManager users = mContext.getSystemService(UserManager.class);
        for (android.content.pm.UserInfo user : users.getUsers()) {
            Context context = mContext.createContextAsUser(UserHandle.of(user.id), 0);
            IconPack pack = null;
            Map<String, IconPack> perApp = new HashMap<>();
            Map<String, IconPack> parsed = new HashMap<>();
            try {
                String json = Settings.Secure.getStringForUser(context.getContentResolver(),
                        SETTINGS_THEME_ENGINE_DATA, user.id);
                JSONObject config = json == null ? new JSONObject() : new JSONObject(json);
                JSONObject themes = config.optJSONObject("themes");
                JSONObject category = themes == null ? null : themes.optJSONObject(CATEGORY_ICON_PACK);
                if (category != null && category.optBoolean("enabled", false)) {
                    String name = category.optString("packageName", "");
                    pack = readIconPack(context.getPackageManager(), name);
                    if (pack != null) parsed.put(name, pack);
                }
                JSONObject apps = config.optJSONObject("perAppIconPacks");
                if (apps != null) {
                    Iterator<String> keys = apps.keys();
                    while (keys.hasNext()) {
                        String name = keys.next();
                        String selected = apps.optString(name, "");
                        IconPack appPack = parsed.get(selected);
                        if (appPack == null) {
                            appPack = readIconPack(context.getPackageManager(), selected);
                            if (appPack != null) parsed.put(selected, appPack);
                        }
                        if (appPack != null) perApp.put(name, appPack);
                    }
                }
            } catch (JSONException | RuntimeException e) {
                Slog.w(TAG, "Failed to load app icon appearance for user " + user.id, e);
            }
            next.put(user.id, new IconAppearance(pack, perApp, ++mIconRevision));
        }
        mIconAppearances.keySet().retainAll(next.keySet());
        mIconAppearances.putAll(next);
    }

    private static String chooseLayer(List<String> names, ComponentName component) {
        return names.isEmpty() ? null
                : names.get(Math.floorMod(component.hashCode(), names.size()));
    }

    private static final long PERSIST_DEBOUNCE_MS = 500;
    private final Runnable mPersistPerAppRunnable = this::persistPerAppIconPacksNow;

    private final Object mThemeChangeLock = new Object();
    private boolean mThemeChangeDispatching;
    private boolean mThemeChangePending;
    @Nullable
    private String mPendingThemeChangeCategory;

    private final RemoteCallbackList<IThemeEngineCallback> mCallbacks =
            new RemoteCallbackList<>();

    public ThemeEngineManagerService(Context context) {
        super(context);
        mContext = context;
        mBinderService = new BinderService();
        mHandlerThread = new HandlerThread("ThemeEngineManagerService");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());

        mBitmapCache = new LruCache<String, Bitmap>(BITMAP_CACHE_SIZE) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return bitmap.getByteCount();
            }
        };
    }

    @Override
    public void onStart() {
        publishBinderService(Context.THEME_ENGINE_SERVICE, mBinderService);
        Slog.i(TAG, "ThemeEngineManagerService started");
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_ACTIVITY_MANAGER_READY) {
            mSettingsObserver = new ContentObserver(mHandler) {
                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    notifyThemeChangedInternal(null);
                }
            };

            mContext.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(SETTINGS_THEME_ENGINE_DATA),
                    false, mSettingsObserver, UserHandle.USER_ALL);

            mContext.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES),
                    false, mSettingsObserver, UserHandle.USER_ALL);
            mHandler.post(() -> notifyThemeChangedInternal(null));

            mLastUiMode = mContext.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK;

            IntentFilter configFilter = new IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED);
            mContext.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    int newUiMode = mContext.getResources().getConfiguration().uiMode
                            & Configuration.UI_MODE_NIGHT_MASK;
                    if (newUiMode != mLastUiMode) {
                        mLastUiMode = newUiMode;
                        mBitmapCache.evictAll();
                        mTextColorPrimaryCached = false;
                    }
                    notifyThemeChangedInternal(CATEGORY_ICON_PACK);
                }
            }, configFilter, null, mHandler);

            IntentFilter packageFilter = new IntentFilter();
            packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
            packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
            packageFilter.addAction(Intent.ACTION_PACKAGE_REPLACED);
            packageFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
            packageFilter.addDataScheme("package");
            mContext.registerReceiverAsUser(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    mInstalledIconPacksCache.clear();
                    mThemeResourcesCache.clear();
                    mIconPackPackage = null;
                    mIconPackMap.clear();
                    notifyThemeChangedInternal(CATEGORY_ICON_PACK);
                }
            }, UserHandle.ALL, packageFilter, null, mHandler);

            IntentFilter appearanceFilter = new IntentFilter(Intent.ACTION_OVERLAY_CHANGED);
            appearanceFilter.addDataScheme("package");
            mContext.registerReceiverAsUser(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    notifyThemeChangedInternal(CATEGORY_ICON_PACK);
                }
            }, UserHandle.ALL, appearanceFilter, null, mHandler);
            IntentFilter userFilter = new IntentFilter(Intent.ACTION_USER_SWITCHED);
            userFilter.addAction(Intent.ACTION_USER_UNLOCKED);
            userFilter.addAction(Intent.ACTION_USER_ADDED);
            userFilter.addAction(Intent.ACTION_USER_REMOVED);
            userFilter.addAction(Intent.ACTION_DATE_CHANGED);
            userFilter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            mContext.registerReceiverAsUser(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    notifyThemeChangedInternal(CATEGORY_ICON_PACK);
                }
            }, UserHandle.ALL, userFilter, null, mHandler);


            Slog.i(TAG, "ThemeEngineManagerService ready");
        }
    }

    private synchronized void loadThemeConfig() {
        mEnabledThemes.clear();
        mThemeResourcesCache.clear();
        mTargetArrayCache.clear();
        mResourceCategoryCache.clear();
        mActiveSystemThemeIcons = null;
        mSystemThemeIconTargets.clear();
        mCategoryThemes.clear();
        mBitmapCache.evictAll();
        mPerAppIconPacks.clear();
        mPerAppIconPackMaps.clear();
        mIconPackPackage = null;
        mIconPackMap.clear();
        mIconBackList.clear();
        mIconMaskList.clear();
        mIconScale = 1.0f;

        try {
            String json = Settings.Secure.getStringForUser(
                    mContext.getContentResolver(),
                    SETTINGS_THEME_ENGINE_DATA,
                    UserHandle.USER_CURRENT);

            if (json == null || json.isEmpty()) {
                return;
            }

            JSONObject config = new JSONObject(json);
            JSONObject themes = config.optJSONObject("themes");

            if (themes != null) {
                Iterator<String> keys = themes.keys();
                while (keys.hasNext()) {
                    String category = keys.next();
                    JSONObject categoryConfig = themes.getJSONObject(category);
                    boolean enabled = categoryConfig.optBoolean("enabled", false);
                    String packageName = categoryConfig.optString("packageName", null);
                    if (enabled && packageName != null && !packageName.isEmpty()) {
                        mEnabledThemes.put(category, packageName);
                    }
                }
            }

            String systemThemeIcons = config.optString("systemThemeIcons", null);
            if (systemThemeIcons != null && !systemThemeIcons.isEmpty()) {
                mActiveSystemThemeIcons = systemThemeIcons;
                loadTargetArrays(systemThemeIcons);
            }

            JSONArray targets = config.optJSONArray("systemThemeIconTargets");
            if (targets != null) {
                for (int i = 0; i < targets.length(); i++) {
                    String target = targets.optString(i);
                    if (target != null && !target.isEmpty()) {
                        mSystemThemeIconTargets.add(target);
                    }
                }
            }

            JSONObject categoryThemes = config.optJSONObject("categoryThemes");
            if (categoryThemes != null) {
                Iterator<String> catKeys = categoryThemes.keys();
                while (catKeys.hasNext()) {
                    String category = catKeys.next();
                    String pkgName = categoryThemes.optString(category);
                    boolean isCategoryEnabled = mEnabledThemes.containsKey(category)
                            || mSystemThemeIconTargets.contains(category);
                    if (pkgName != null && !pkgName.isEmpty() && isCategoryEnabled) {
                        mCategoryThemes.put(category, pkgName);
                        loadTargetArrays(pkgName);
                    }
                }
            }

            JSONObject perAppPacks = config.optJSONObject("perAppIconPacks");
            if (perAppPacks != null) {
                Iterator<String> appKeys = perAppPacks.keys();
                while (appKeys.hasNext()) {
                    String appPkg = appKeys.next();
                    String iconPackPkg = perAppPacks.optString(appPkg);
                    if (iconPackPkg != null && !iconPackPkg.isEmpty()) {
                        mPerAppIconPacks.put(appPkg, iconPackPkg);
                    }
                }
            }

            String iconPackPkg = mEnabledThemes.get(CATEGORY_ICON_PACK);
            if (iconPackPkg != null
                    && (!iconPackPkg.equals(mIconPackPackage) || mIconPackMap.isEmpty())) {
                loadIconPack(iconPackPkg);
            } else if (iconPackPkg == null && mIconPackPackage != null) {
                mIconPackPackage = null;
                mIconPackMap.clear();
                mIconBackList.clear();
                mIconMaskList.clear();
            }

        } catch (JSONException e) {
            Slog.e(TAG, "Failed to parse theme config", e);
        }
    }

    private void loadTargetArrays(@NonNull String packageName) {
        Resources themeResources = getThemeResources(packageName);
        if (themeResources == null) return;

        Set<String> allTargets = new HashSet<>();

        Map<String, String> arrayCategoryMap = new HashMap<>();
        arrayCategoryMap.put(TARGET_ARRAY_SYSTEMUI, CATEGORY_SYSTEMUI);
        arrayCategoryMap.put(TARGET_ARRAY_ANDROID, CATEGORY_ANDROID);
        arrayCategoryMap.put(TARGET_ARRAY_SETTINGS, "settings");
        arrayCategoryMap.put("target_wifi", CATEGORY_STATUSBAR_WIFI);
        arrayCategoryMap.put("target_signal", CATEGORY_STATUSBAR_SIGNAL);
        arrayCategoryMap.put("target_systemui_icons", CATEGORY_SYSTEMUI);

        for (Map.Entry<String, String> entry : arrayCategoryMap.entrySet()) {
            String arrayName = entry.getKey();
            String category = entry.getValue();
            int arrayId = themeResources.getIdentifier(arrayName, "array", packageName);
            if (arrayId != 0) {
                try {
                    String[] targetNames = themeResources.getStringArray(arrayId);
                    for (String target : targetNames) {
                        allTargets.add(target);
                        mResourceCategoryCache.put(target, category);
                    }
                } catch (Exception e) {
                    Slog.w(TAG, "Failed to load target array: " + arrayName, e);
                }
            }
        }

        if (allTargets.isEmpty()) {
            String overlayCategory = null;
            try {
                android.content.pm.PackageInfo pi = mContext.getPackageManager()
                        .getPackageInfo(packageName, 0);
                overlayCategory = pi.overlayCategory;
            } catch (Exception e) {
                Slog.d(TAG, "Failed to get overlay category for " + packageName, e);
            }

            String resCategory = null;
            if ("android.theme.customization.signal_icon".equals(overlayCategory)) {
                resCategory = "signal";
            } else if ("android.theme.customization.wifi_icon".equals(overlayCategory)) {
                resCategory = "wifi";
            }

            String[] knownNames = {
                "ic_signal_cellular_0_4_bar", "ic_signal_cellular_1_4_bar",
                "ic_signal_cellular_2_4_bar", "ic_signal_cellular_3_4_bar",
                "ic_signal_cellular_4_4_bar",
                "ic_signal_cellular_0_5_bar", "ic_signal_cellular_1_5_bar",
                "ic_signal_cellular_2_5_bar", "ic_signal_cellular_3_5_bar",
                "ic_signal_cellular_4_5_bar", "ic_signal_cellular_5_5_bar",
                "ic_wifi_signal_0", "ic_wifi_signal_1", "ic_wifi_signal_2",
                "ic_wifi_signal_3", "ic_wifi_signal_4",
            };
            for (String name : knownNames) {
                if (themeResources.getIdentifier(name, "drawable", packageName) != 0) {
                    allTargets.add(name);
                    if (resCategory != null) mResourceCategoryCache.put(name, resCategory);
                }
            }
        }

        mTargetArrayCache.put(packageName, allTargets);
    }

    private void loadIconPack(String packageName) {
        IconPack pack = readIconPack(mContext.getPackageManager(), packageName);
        if (pack == null) return;
        mIconPackPackage = packageName;
        mIconPackMap.putAll(pack.icons);
        mIconBackList.addAll(pack.backs);
        mIconMaskList.addAll(pack.masks);
        mIconScale = pack.scale;
    }

    private void loadPerAppIconPack(String packageName) {
        if (mPerAppIconPackMaps.containsKey(packageName)) return;
        IconPack pack = readIconPack(mContext.getPackageManager(), packageName);
        if (pack != null) mPerAppIconPackMaps.put(packageName, pack.icons);
    }

    // Limit both decoded XML work and uncompiled asset size. Never run package code.
    private static final int MAX_FILTER_ENTRIES = 100_000;
    private static final int MAX_FILTER_EVENTS = 500_000;
    private static final int MAX_FILTER_BYTES = 16 * 1024 * 1024;

    private static final class FilterInput extends FilterInputStream {
        private int remaining = MAX_FILTER_BYTES;
        FilterInput(InputStream input) {
            super(input);
        }
        @Override
        public int read() throws IOException {
            if (--remaining < 0) throw new IOException("Appfilter too large");
            return super.read();
        }
        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (remaining <= 0) throw new IOException("Appfilter too large");
            int read = super.read(bytes, offset, Math.min(length, remaining));
            if (read > 0) remaining -= read;
            return read;
        }
    }

    @Nullable
    private IconPack readIconPack(PackageManager pm, String packageName) {
        if (packageName == null || packageName.isEmpty()) return null;
        try {
            Resources res = pm.getResourcesForApplication(packageName);
            int id = res.getIdentifier("appfilter", "xml", packageName);
            if (id != 0) {
                try (InputStream input = res.openRawResource(id)) {
                    if (input.available() > MAX_FILTER_BYTES) {
                        throw new IOException("Appfilter too large");
                    }
                    try (XmlResourceParser parser = res.getXml(id)) {
                        return parseAppFilter(parser, packageName,
                                readPackCapabilities(pm, packageName));
                    }
                } catch (IOException | XmlPullParserException | RuntimeException e) {
                    Slog.w(TAG, "Failed compiled appfilter for " + packageName, e);
                }
            }
            try (InputStream input = new FilterInput(res.getAssets().open("appfilter.xml"))) {
                XmlPullParser parser = Xml.newPullParser();
                parser.setInput(input, "UTF-8");
                return parseAppFilter(parser, packageName,
                        readPackCapabilities(pm, packageName));
            }
        } catch (PackageManager.NameNotFoundException | IOException
                | XmlPullParserException | RuntimeException e) {
            return null;
        }
    }

    private static IconPack parseAppFilter(XmlPullParser parser, String packageName,
            int capabilities) throws IOException, XmlPullParserException {
        Map<ComponentName, String> icons = new HashMap<>();
        Map<ComponentName, String> calendars = new HashMap<>();
        List<String> backs = new ArrayList<>();
        List<String> masks = new ArrayList<>();
        List<String> upons = new ArrayList<>();
        float scale = 1f;
        boolean root = false;
        int events = 0;
        for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT;
                event = parser.next()) {
            if (++events > MAX_FILTER_EVENTS) throw new IOException("Appfilter too complex");
            if (event != XmlPullParser.START_TAG) continue;
            String tag = parser.getName();
            if (parser.getDepth() == 1) {
                if (!"resources".equals(tag)) throw new IOException("Invalid appfilter root");
                root = true;
            }
            if ("item".equals(tag) || "calendar".equals(tag)) {
                ComponentName component = parseComponentInfo(
                        parser.getAttributeValue(null, "component"));
                String name = parser.getAttributeValue(null,
                        "calendar".equals(tag) ? "prefix" : "drawable");
                if (component != null && validDrawableName(name)) {
                    if (icons.size() + calendars.size() >= MAX_FILTER_ENTRIES) {
                        throw new IOException("Too many appfilter entries");
                    }
                    ("calendar".equals(tag) ? calendars : icons).put(component, name);
                }
            } else if ("iconback".equals(tag) || "iconmask".equals(tag)
                    || "iconupon".equals(tag)) {
                List<String> target = "iconback".equals(tag) ? backs
                        : "iconmask".equals(tag) ? masks : upons;
                for (int i = 0; i < parser.getAttributeCount() && target.size() < 32; i++) {
                    String name = parser.getAttributeValue(i);
                    if (validDrawableName(name)) target.add(name);
                }
            } else if ("scale".equals(tag)) {
                try {
                    float value = Float.parseFloat(parser.getAttributeValue(null, "factor"));
                    if (Float.isFinite(value) && value > 0 && value <= 2f) scale = value;
                } catch (RuntimeException ignored) {
                }
            }
        }
        if (!root || (icons.isEmpty() && calendars.isEmpty() && backs.isEmpty()
                && masks.isEmpty() && upons.isEmpty())) {
            throw new IOException("Empty appfilter");
        }
        return new IconPack(packageName, icons, calendars, backs, masks, upons, scale, capabilities);
    }

    private static boolean validDrawableName(String name) {
        return name != null && name.length() <= 255 && name.matches("[a-zA-Z0-9_]+");
    }

    @Nullable
    private static ComponentName parseComponentInfo(String value) {
        if (value == null || value.length() > 2048) return null;
        if (value.startsWith("ComponentInfo{") && value.endsWith("}")) {
            value = value.substring(14, value.length() - 1);
        }
        return ComponentName.unflattenFromString(value);
    }

    private static final String[] ICON_PACK_ACTIONS = {
            "org.adw.launcher.THEMES", "com.novalauncher.THEME",
            "com.gau.go.launcherex.theme", "com.dlto.atom.launcher.THEME",
            "com.anddoes.launcher.THEME", "ch.deletescape.lawnchair.ICONPACK",
            "ginlemon.smartlauncher.THEMES", "com.teslacoilsw.launcher.THEME",
            "com.fede.launcher.THEME_ICONPACK", "org.adw.launcher.icons.ACTION_PICK_ICON",
            "net.oneplus.launcher.icons.ACTION_PICK_ICON", "com.sonymobile.home.ICON_PACK",
            "app.lawnchair.icons.THEMED_ICON"
    };

    private static int readPackCapabilities(PackageManager pm, String packageName) {
        int capabilities = pm.queryIntentActivities(new Intent("app.lawnchair.icons.THEMED_ICON")
                .setPackage(packageName), PackageManager.GET_RESOLVED_FILTER).isEmpty() ? 0 : 1;
        for (ResolveInfo info : pm.queryIntentActivities(new Intent("org.adw.launcher.THEMES")
                .setPackage(packageName), PackageManager.GET_RESOLVED_FILTER)) {
            if (info.filter == null) continue;
            if (info.filter.hasCategory("org.icontheme.CHANGES_WITH_MATERIAL_YOU_COLORS")) {
                capabilities |= 2;
            }
            if (info.filter.hasCategory("org.icontheme.CHANGES_WITH_DARK_MODE")) {
                capabilities |= 4;
            }
            if (info.filter.hasCategory("org.icontheme.FALLBACK_TO_THEMED_ICON")) {
                capabilities |= 8;
            }
        }
        // All snapshots are invalidated on configuration/overlay changes, covering these flags.
        return capabilities;
    }

    @Nullable
    private Resources getThemeResources(@NonNull String packageName) {
        Resources cached = mThemeResourcesCache.get(packageName);
        if (cached != null) return cached;

        try {
            Context themeContext = mContext.createPackageContext(
                    packageName, Context.CONTEXT_IGNORE_SECURITY);
            Resources resources = themeContext.getResources();
            mThemeResourcesCache.put(packageName, resources);
            return resources;
        } catch (PackageManager.NameNotFoundException e) {
            Slog.w(TAG, "Theme package not found: " + packageName);
            return null;
        }
    }

    @Nullable
    private String getThemePackageForResource(@NonNull String resourceName) {
        String category = mResourceCategoryCache.get(resourceName);

        if (category != null && mCategoryThemes.containsKey(category)) {
            return mCategoryThemes.get(category);
        }

        if (category != null) {
            String aliasCategory = "statusbar_" + category;
            if (mCategoryThemes.containsKey(aliasCategory)) {
                return mCategoryThemes.get(aliasCategory);
            }
        }

        if (mActiveSystemThemeIcons != null) {
            if (category == null || mSystemThemeIconTargets.isEmpty()
                    || mSystemThemeIconTargets.contains(category)) {
                return mActiveSystemThemeIcons;
            }
            if (category != null
                    && mSystemThemeIconTargets.contains("statusbar_" + category)) {
                return mActiveSystemThemeIcons;
            }
        }

        return null;
    }

    @NonNull
    private Bitmap drawableToBitmap(@NonNull Drawable drawable, int density, boolean clearTint) {
        int size = density > 0 ? density : 192;

        if (clearTint) {
            drawable.setColorFilter(null);
            drawable.setTint(getTextColorPrimary());
        }

        if (!clearTint && drawable instanceof BitmapDrawable) {
            Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
            if (bitmap != null) return bitmap;
        }

        if (drawable instanceof AdaptiveIconDrawable) {
            size = Math.max(size, 108);
        }

        int width = drawable.getIntrinsicWidth() > 0 ? drawable.getIntrinsicWidth() : size;
        int height = drawable.getIntrinsicHeight() > 0 ? drawable.getIntrinsicHeight() : size;

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, width, height);
        drawable.draw(canvas);
        return bitmap;
    }

    private int getTextColorPrimary() {
        if (mTextColorPrimaryCached) return mCachedTextColorPrimary;
        try {
            int themeResId = mContext.getResources().getConfiguration().isNightModeActive()
                    ? android.R.style.Theme_DeviceDefault
                    : android.R.style.Theme_DeviceDefault_Light;
            Context themedContext = new ContextThemeWrapper(mContext, themeResId);
            TypedValue typedValue = new TypedValue();
            themedContext.getTheme().resolveAttribute(
                    android.R.attr.textColorPrimary, typedValue, true);
            if (typedValue.resourceId != 0) {
                mCachedTextColorPrimary = themedContext.getColor(typedValue.resourceId);
            } else {
                mCachedTextColorPrimary = typedValue.data != 0 ? typedValue.data : Color.WHITE;
            }
        } catch (Exception e) {
            mCachedTextColorPrimary = mContext.getResources().getConfiguration().isNightModeActive()
                    ? Color.WHITE : Color.BLACK;
        }
        mTextColorPrimaryCached = true;
        return mCachedTextColorPrimary;
    }

    private void notifyThemeChangedInternal(@Nullable String category) {
        if (Looper.myLooper() != mHandler.getLooper()) {
            mHandler.post(() -> notifyThemeChangedInternal(category));
            return;
        }
        String dispatchCategory = category;
        boolean clearDispatching = true;
        synchronized (mThemeChangeLock) {
            if (mThemeChangeDispatching) {
                queuePendingThemeChangeLocked(category);
                return;
            }
            mThemeChangeDispatching = true;
        }

        try {
            while (true) {
                dispatchThemeChanged(dispatchCategory);

                synchronized (mThemeChangeLock) {
                    if (!mThemeChangePending) {
                        mThemeChangeDispatching = false;
                        clearDispatching = false;
                        return;
                    }
                    dispatchCategory = mPendingThemeChangeCategory;
                    mPendingThemeChangeCategory = null;
                    mThemeChangePending = false;
                }
            }
        } finally {
            if (clearDispatching) {
                synchronized (mThemeChangeLock) {
                    mThemeChangeDispatching = false;
                }
            }
        }
    }

    private void queuePendingThemeChangeLocked(@Nullable String category) {
        if (!mThemeChangePending) {
            mPendingThemeChangeCategory = category;
        } else {
            mPendingThemeChangeCategory =
                    mergeThemeChangeCategories(mPendingThemeChangeCategory, category);
        }
        mThemeChangePending = true;
    }

    @Nullable
    private static String mergeThemeChangeCategories(
            @Nullable String current, @Nullable String next) {
        if (current == null || next == null) {
            return null;
        }
        return current.equals(next) ? current : null;
    }

    private void dispatchThemeChanged(@Nullable String category) {
        final long ident = Binder.clearCallingIdentity();
        try {
            loadThemeConfig();
            reloadIconAppearances();
            android.app.PropertyInvalidatedCache.invalidateCache(ThemeEngine.APP_ICON_CACHE_KEY);

            int count = mCallbacks.beginBroadcast();
            try {
                for (int i = 0; i < count; i++) {
                    try {
                        mCallbacks.getBroadcastItem(i).onThemeChanged(category);
                    } catch (RemoteException | RuntimeException e) {
                        Slog.w(TAG, "Failed to notify theme callback", e);
                    }
                }
            } finally {
                mCallbacks.finishBroadcast();
            }

            LauncherAppsService.LauncherAppsServiceInternal launcher =
                    LocalServices.getService(LauncherAppsService.LauncherAppsServiceInternal.class);
            if (launcher != null) {
                for (int userId : mIconAppearances.keySet()) {
                    launcher.refreshAppIcons(userId);
                }
            }

            if (category == null || CATEGORY_ICON_PACK.equals(category)) {
                Intent intent = new Intent(ThemeEngine.ACTION_THEME_CHANGED);
                intent.putExtra(ThemeEngine.EXTRA_THEME_CATEGORY, CATEGORY_ICON_PACK);
                intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
                mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @NonNull
    private List<String> scanInstalledIconPacks(int userId) {
        PackageManager pm = mContext.createContextAsUser(UserHandle.of(userId), 0)
                .getPackageManager();
        Set<String> candidates = new LinkedHashSet<>();
        for (String action : ICON_PACK_ACTIONS) {
            for (ResolveInfo info : pm.queryIntentActivities(new Intent(action),
                    PackageManager.GET_RESOLVED_FILTER)) {
                candidates.add(info.activityInfo.packageName);
            }
        }
        for (ResolveInfo info : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN)
                .addCategory("com.anddoes.launcher.THEME"), PackageManager.GET_RESOLVED_FILTER)) {
            candidates.add(info.activityInfo.packageName);
        }
        // Intent declarations are hints, never a requirement or sufficient validation.
        for (ApplicationInfo app : pm.getInstalledApplications(0)) {
            candidates.add(app.packageName);
        }
        List<String> result = new ArrayList<>();
        for (String packageName : candidates) {
            if (readIconPack(pm, packageName) != null) result.add(packageName);
        }
        return result;
    }

    private final class BinderService extends IThemeEngineManager.Stub {

        private void enforceIconUser(int userId) {
            if (userId < 0) throw new IllegalArgumentException("Invalid user");
            int callerUser = UserHandle.getCallingUserId();
            if (callerUser == userId) return;
            if (mContext.checkCallingOrSelfPermission(
                    android.Manifest.permission.INTERACT_ACROSS_USERS_FULL)
                    == PackageManager.PERMISSION_GRANTED
                    || mContext.checkCallingOrSelfPermission(
                            android.Manifest.permission.INTERACT_ACROSS_USERS)
                            == PackageManager.PERMISSION_GRANTED) return;
            // Privileged presentation apps can read icon metadata in their enabled profiles.
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.WRITE_SECURE_SETTINGS, "getAppIconAppearance");
            final long identity = Binder.clearCallingIdentity();
            try {
                UserManager users = mContext.getSystemService(UserManager.class);
                android.content.pm.UserInfo user = users.getUserInfo(userId);
                if (user == null || !user.isEnabled()
                        || !users.isSameProfileGroup(callerUser, userId)) {
                    throw new SecurityException("Profile is not accessible");
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public Bundle getAppIconResources(String packageName, int userId) {
            enforceIconUser(userId);
            int uid = Binder.getCallingUid();
            if (packageName == null || LocalServices.getService(
                    android.content.pm.PackageManagerInternal.class)
                    .filterAppAccess(packageName, uid, userId)) return null;
            final long identity = Binder.clearCallingIdentity();
            try {
                PackageManager pm = mContext.createContextAsUser(UserHandle.of(userId), 0)
                        .getPackageManager();
                android.content.pm.PackageInfo info = pm.getPackageInfo(packageName,
                        PackageManager.GET_ACTIVITIES | PackageManager.MATCH_DISABLED_COMPONENTS);
                android.util.SparseArray<String> icons = new android.util.SparseArray<>();
                addIconResource(icons, info.applicationInfo.icon, "");
                addIconResource(icons, info.applicationInfo.roundIconRes, "");
                if (info.activities != null) {
                    for (android.content.pm.ActivityInfo activity : info.activities) {
                        addIconResource(icons, activity.getIconResource(), activity.name);
                    }
                }
                int[] ids = new int[icons.size()];
                String[] classes = new String[icons.size()];
                for (int i = 0; i < icons.size(); i++) {
                    ids[i] = icons.keyAt(i);
                    classes[i] = icons.valueAt(i);
                }
                Bundle result = new Bundle();
                result.putLong("version", info.getLongVersionCode());
                result.putLong("updated", info.lastUpdateTime);
                result.putIntArray("ids", ids);
                result.putStringArray("classes", classes);
                return result;
            } catch (PackageManager.NameNotFoundException e) {
                return null;
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        private void addIconResource(android.util.SparseArray<String> icons, int id,
                String activity) {
            if (id == 0) return;
            // Bound the per-package transaction; exceptional excess IDs retain their raw icons.
            if (icons.indexOfKey(id) < 0) {
                if (icons.size() < 512) icons.put(id, activity.length() <= 512 ? activity : "");
            } else if (!icons.get(id).equals(activity)) {
                // A shared source ID has lost activity identity: application fallback is stable.
                icons.put(id, "");
            }
        }

        @Override
        public Bundle getAppIconAppearance(ComponentName component, int userId) {
            enforceIconUser(userId);
            int uid = Binder.getCallingUid();
            IconAppearance state = mIconAppearances.get(userId);
            if (state == null) return null;
            Bundle result = new Bundle();
            result.putBoolean("enabled", state.pack != null || !state.perApp.isEmpty());
            result.putLong("revision", state.revision);
            if (component == null) return result;
            final long identity = Binder.clearCallingIdentity();
            try {
                PackageManager pm = mContext.createContextAsUser(UserHandle.of(userId), 0)
                        .getPackageManager();
                // Do not expose another user's hidden packages through the descriptor API.
                if (LocalServices.getService(android.content.pm.PackageManagerInternal.class)
                        .filterAppAccess(component.getPackageName(), uid, userId)) return result;
                IconPack pack = state.perApp.getOrDefault(component.getPackageName(), state.pack);
                if (pack == null) return result;
                if (component.getClassName().isEmpty()) {
                    List<ResolveInfo> activities = pm.queryIntentActivities(
                            new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                                    .setPackage(component.getPackageName()), 0);
                    // A resource ID collision cannot recover an arbitrary activity's identity.
                    if (activities.size() == 1) {
                        component = new ComponentName(activities.get(0).activityInfo.packageName,
                                activities.get(0).activityInfo.name);
                    }
                }
                if (LocalServices.getService(android.content.pm.PackageManagerInternal.class)
                        .filterAppAccess(pack.packageName, uid, userId)) return result;
                try {
                    result.putParcelable("packInfo", pm.getApplicationInfo(pack.packageName, 0));
                } catch (PackageManager.NameNotFoundException e) {
                    return result;
                }
                result.putString("pack", pack.packageName);
                result.putString("drawable", pack.icons.get(component));
                result.putString("calendar", pack.calendars.get(component));
                result.putString("back", chooseLayer(pack.backs, component));
                result.putString("mask", chooseLayer(pack.masks, component));
                result.putString("upon", chooseLayer(pack.upons, component));
                result.putFloat("scale", pack.scale);
                result.putBoolean("monochrome", (pack.capabilities & 1) != 0);
                result.putInt("capabilities", pack.capabilities);
                return result;
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public Bitmap getIconPackIcon(ComponentName component, int density) {
            final String iconPackPkg;
            final String drawableName;
            synchronized (ThemeEngineManagerService.this) {
                iconPackPkg = mIconPackPackage;
                if (component == null || iconPackPkg == null) return null;
                drawableName = mIconPackMap.get(component);
            }
            if (drawableName == null) return null;

            String cacheKey = "iconpack:" + component.flattenToShortString() + ":" + density;
            Bitmap cached = mBitmapCache.get(cacheKey);
            if (cached != null) return cached;

            Resources res = getThemeResources(iconPackPkg);
            if (res == null) return null;

            try {
                int resId = res.getIdentifier(drawableName, "drawable", iconPackPkg);
                if (resId != 0) {
                    Drawable drawable = res.getDrawableInternal(resId);
                    if (drawable != null) {
                        Bitmap bitmap = drawableToBitmap(drawable, density, false);
                        mBitmapCache.put(cacheKey, bitmap);
                        return bitmap;
                    }
                }
            } catch (Exception e) {
                Slog.w(TAG, "Failed to get icon pack icon: " + component, e);
            }
            return null;
        }

        @Override
        public boolean hasActiveIconPack() {
            synchronized (ThemeEngineManagerService.this) {
                return mIconPackPackage != null;
            }
        }

        @Override
        public String getIconPackPackage() {
            synchronized (ThemeEngineManagerService.this) {
                return mIconPackPackage;
            }
        }

        @Override
        public Bitmap getPerAppIconPackIcon(String packageName, String className, int density) {
            if (packageName == null || className == null) return null;

            String iconPackPkg = mPerAppIconPacks.get(packageName);
            if (iconPackPkg == null) return null;

            loadPerAppIconPack(iconPackPkg);
            Map<ComponentName, String> packMap = mPerAppIconPackMaps.get(iconPackPkg);
            if (packMap == null) return null;

            ComponentName cn = new ComponentName(packageName, className);
            String drawableName = packMap.get(cn);
            if (drawableName == null) return null;

            String cacheKey = "perapp:" + cn.flattenToShortString()
                    + ":" + iconPackPkg + ":" + density;
            Bitmap cached = mBitmapCache.get(cacheKey);
            if (cached != null) return cached;

            Resources res = getThemeResources(iconPackPkg);
            if (res == null) return null;

            try {
                int resId = res.getIdentifier(drawableName, "drawable", iconPackPkg);
                if (resId != 0) {
                    Drawable drawable = res.getDrawableInternal(resId);
                    if (drawable != null) {
                        Bitmap bitmap = drawableToBitmap(drawable, density, false);
                        mBitmapCache.put(cacheKey, bitmap);
                        return bitmap;
                    }
                }
            } catch (Exception e) {
                Slog.w(TAG, "Failed to get per-app icon pack icon", e);
            }
            return null;
        }

        @Override
        public void setPerAppIconPack(String packageName, String iconPackPackage) {
            if (packageName == null) return;
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.WRITE_SECURE_SETTINGS,
                    "setPerAppIconPack");
            final long ident = Binder.clearCallingIdentity();
            try {
                if (iconPackPackage != null && !iconPackPackage.isEmpty()) {
                    mPerAppIconPacks.put(packageName, iconPackPackage);
                } else {
                    mPerAppIconPacks.remove(packageName);
                }
                persistPerAppIconPacks();
                notifyThemeChangedInternal(CATEGORY_ICON_PACK);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public void clearPerAppIconPack(String packageName) {
            setPerAppIconPack(packageName, null);
        }

        @Override
        public String getPerAppIconPack(String packageName) {
            if (packageName == null) return null;
            return mPerAppIconPacks.get(packageName);
        }

        @Override
        public List<String> getInstalledIconPacks() {
            int userId = UserHandle.getCallingUserId();
            int callerUid = Binder.getCallingUid();
            java.util.concurrent.FutureTask<List<String>> scan =
                    new java.util.concurrent.FutureTask<>(() -> {
                        List<String> packs = mInstalledIconPacksCache.get(userId);
                        if (packs == null) {
                            packs = scanInstalledIconPacks(userId);
                            mInstalledIconPacksCache.put(userId, packs);
                        }
                        return new ArrayList<>(packs);
                    });
            mHandler.post(scan);
            try {
                List<String> result = scan.get(30, java.util.concurrent.TimeUnit.SECONDS);
                android.content.pm.PackageManagerInternal pm =
                        LocalServices.getService(android.content.pm.PackageManagerInternal.class);
                result.removeIf(packageName -> pm.filterAppAccess(packageName, callerUid, userId));
                return result;
            } catch (Exception e) {
                return Collections.emptyList();
            }
        }

        @Override
        public Bitmap getSystemThemeIconDrawable(String resourceName, int density) {
            if (resourceName == null) return null;

            String cacheKey = "theme:" + resourceName + ":" + density;
            Bitmap cached = mBitmapCache.get(cacheKey);
            if (cached != null) return cached;

            final String themePackage;
            final boolean resourceAllowed;
            synchronized (ThemeEngineManagerService.this) {
                themePackage = getThemePackageForResource(resourceName);
                if (themePackage == null) return null;
                Set<String> targets = mTargetArrayCache.get(themePackage);
                resourceAllowed = targets == null || targets.contains(resourceName);
            }
            if (!resourceAllowed) return null;

            Resources themeResources = getThemeResources(themePackage);
            if (themeResources == null) return null;

            try {
                int resId = themeResources.getIdentifier(
                        resourceName, "drawable", themePackage);
                if (resId != 0) {
                    Drawable drawable = themeResources.getDrawableInternal(resId);
                    if (drawable != null) {
                        Bitmap bitmap = drawableToBitmap(drawable, density, true);
                        mBitmapCache.put(cacheKey, bitmap);
                        return bitmap;
                    }
                }
            } catch (Exception e) {
                Slog.w(TAG, "Failed to get system theme icon: " + resourceName, e);
            }
            return null;
        }

        @Override
        public boolean isTargetedResource(String resourceName) {
            if (resourceName == null) return false;
            synchronized (ThemeEngineManagerService.this) {
                String category = mResourceCategoryCache.get(resourceName);

                if (category != null && mCategoryThemes.containsKey(category)) {
                    String pkgName = mCategoryThemes.get(category);
                    Set<String> targets = mTargetArrayCache.get(pkgName);
                    return targets != null && targets.contains(resourceName);
                }

                if (mActiveSystemThemeIcons == null) return false;

                Set<String> targets = mTargetArrayCache.get(mActiveSystemThemeIcons);
                if (targets == null || !targets.contains(resourceName)) return false;

                if (category != null && !mSystemThemeIconTargets.isEmpty()) {
                    return mSystemThemeIconTargets.contains(category);
                }

                return !mSystemThemeIconTargets.isEmpty();
            }
        }

        @Override
        public boolean hasActiveSystemThemeIcons() {
            synchronized (ThemeEngineManagerService.this) {
                return mActiveSystemThemeIcons != null
                        && (!mSystemThemeIconTargets.isEmpty()
                            || mTargetArrayCache.containsKey(mActiveSystemThemeIcons));
            }
        }

        @Override
        public String getActiveSystemThemeIcons() {
            synchronized (ThemeEngineManagerService.this) {
                return mActiveSystemThemeIcons;
            }
        }

        @Override
        public String getCategoryTheme(String category) {
            if (category == null) return null;
            return mCategoryThemes.get(category);
        }

        @Override
        public void registerCallback(IThemeEngineCallback callback) {
            if (callback != null) {
                mCallbacks.register(callback);
                // Also reconnect clients whose first request raced service initialization.
                mHandler.post(() -> {
                    try {
                        callback.onThemeChanged(CATEGORY_ICON_PACK);
                    } catch (RemoteException ignored) {
                    }
                });
            }
        }

        @Override
        public void unregisterCallback(IThemeEngineCallback callback) {
            if (callback != null) mCallbacks.unregister(callback);
        }

        @Override
        public void notifyThemeChanged(String category) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.WRITE_SECURE_SETTINGS,
                    "notifyThemeChanged");
            notifyThemeChangedInternal(category);
        }

        @Override
        public List<String> getAvailableOverlays(String category) {
            return scanOverlaysByCategory(category);
        }
    }

    @NonNull
    private List<String> scanOverlaysByCategory(@Nullable String category) {
        List<String> result = new ArrayList<>();
        if (category == null) return result;
        PackageManager pm = mContext.getPackageManager();
        for (ApplicationInfo app : pm.getInstalledApplications(0)) {
            try {
                android.content.pm.PackageInfo pi = pm.getPackageInfo(app.packageName, 0);
                if (pi.overlayTarget != null && category.equals(pi.overlayCategory)) {
                    result.add(app.packageName);
                }
            } catch (Exception e) {
                Slog.d(TAG, "Failed to check overlay for " + app.packageName, e);
            }
        }
        return result;
    }

    private void persistPerAppIconPacks() {
        mHandler.removeCallbacks(mPersistPerAppRunnable);
        mHandler.postDelayed(mPersistPerAppRunnable, PERSIST_DEBOUNCE_MS);
    }

    private void persistPerAppIconPacksNow() {
        try {
            String json = Settings.Secure.getStringForUser(
                    mContext.getContentResolver(),
                    SETTINGS_THEME_ENGINE_DATA,
                    UserHandle.USER_CURRENT);

            JSONObject config = (json != null && !json.isEmpty())
                    ? new JSONObject(json) : new JSONObject();

            JSONObject perAppPacks = new JSONObject();
            for (Map.Entry<String, String> entry : mPerAppIconPacks.entrySet()) {
                perAppPacks.put(entry.getKey(), entry.getValue());
            }
            config.put("perAppIconPacks", perAppPacks);

            Settings.Secure.putStringForUser(
                    mContext.getContentResolver(),
                    SETTINGS_THEME_ENGINE_DATA,
                    config.toString(),
                    UserHandle.USER_CURRENT);
        } catch (JSONException e) {
            Slog.e(TAG, "Failed to persist per-app icon packs", e);
        }
    }
}
