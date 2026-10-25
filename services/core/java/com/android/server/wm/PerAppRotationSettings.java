/* SPDX-License-Identifier: Apache-2.0 */

package com.android.server.wm;

import android.content.ContentResolver;
import android.provider.Settings;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Surface;

import org.json.JSONException;
import org.json.JSONObject;

/** Per-user rotation choices. All access is serialized by the window manager lock. */
final class PerAppRotationSettings {
    static final String SETTING_NAME = "per_app_manual_rotation";
    static final int NO_ROTATION = -1;
    private static final String TAG = "PerAppRotationSettings";

    private final ContentResolver mResolver;
    private final SparseArray<JSONObject> mRotations = new SparseArray<>();

    PerAppRotationSettings(ContentResolver resolver) {
        mResolver = resolver;
    }

    int getRotation(String packageName, int userId) {
        final int rotation = getRotations(userId).optInt(packageName, NO_ROTATION);
        return rotation >= Surface.ROTATION_0 && rotation <= Surface.ROTATION_270
                ? rotation : NO_ROTATION;
    }

    void setRotation(String packageName, int userId, int rotation) {
        if (rotation < Surface.ROTATION_0 || rotation > Surface.ROTATION_270) {
            return;
        }
        final JSONObject rotations = getRotations(userId);
        try {
            rotations.put(packageName, rotation);
            persist(userId, rotations);
        } catch (JSONException e) {
            Slog.w(TAG, "Unable to save app rotation", e);
        }
    }

    void removePackage(String packageName, int userId) {
        final JSONObject rotations = getRotations(userId);
        if (rotations.has(packageName)) {
            rotations.remove(packageName);
            persist(userId, rotations);
        }
    }

    void clearUser(int userId) {
        final JSONObject rotations = new JSONObject();
        mRotations.put(userId, rotations);
        persist(userId, rotations);
    }

    void invalidate() {
        mRotations.clear();
    }

    void removeUser(int userId) {
        mRotations.remove(userId);
        // SettingsProvider removes the user's persistent settings when the user is removed.
    }

    private JSONObject getRotations(int userId) {
        JSONObject rotations = mRotations.get(userId);
        if (rotations == null) {
            final String value = Settings.Secure.getStringForUser(mResolver, SETTING_NAME, userId);
            try {
                rotations = value == null ? new JSONObject() : new JSONObject(value);
            } catch (JSONException e) {
                Slog.w(TAG, "Ignoring invalid app rotations for user " + userId, e);
                rotations = new JSONObject();
            }
            mRotations.put(userId, rotations);
        }
        return rotations;
    }

    private void persist(int userId, JSONObject rotations) {
        if (!Settings.Secure.putStringForUser(mResolver, SETTING_NAME,
                rotations.length() == 0 ? null : rotations.toString(), userId)) {
            // A failed write must not leave a choice that only exists in memory.
            mRotations.remove(userId);
            Slog.w(TAG, "Unable to persist app rotations for user " + userId);
        }
    }
}
