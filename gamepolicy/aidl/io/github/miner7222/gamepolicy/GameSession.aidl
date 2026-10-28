// SPDX-License-Identifier: Apache-2.0
package io.github.miner7222.gamepolicy;

@VintfStability
parcelable GameSession {
    int userId;
    int uid;
    String packageName;
    int pid;
    /** Kernel /proc stat field 22: clock ticks since boot, captured by AMS. */
    long processStartTimeTicks;
    int mode;
    long generation;
}
