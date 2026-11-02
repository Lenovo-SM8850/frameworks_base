// SPDX-License-Identifier: Apache-2.0
package android.view;

/** @hide */
oneway interface IGameGestureLockListener {
    /** @hide */
    void onNotice(long generation, int userId, boolean visible);
    /** @hide */
    void onStateChanged(boolean active);
}
