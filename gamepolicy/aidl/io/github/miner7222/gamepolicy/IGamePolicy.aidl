// SPDX-License-Identifier: Apache-2.0
package io.github.miner7222.gamepolicy;

import io.github.miner7222.gamepolicy.GameSession;

/** Only the system session authority may call this interface. */
@VintfStability
interface IGamePolicy {
    /** Start or update the single visible session. Client death releases it. */
    void setSession(in GameSession session, IBinder client);
    /** Generations must increase for both updates and stops within a client lifetime. */
    void stopSession(long generation, IBinder client);
    /** FPS feedback belongs to the exact current generation and client lifetime. */
    oneway void reportFps(long generation, float fps, IBinder client);
    /** Scene feedback belongs to the exact current generation and client lifetime. */
    oneway void reportScene(long generation, int scene, IBinder client);
    /** Presentation feedback from a single game layer; fallback reports carry no jank counts. */
    oneway void reportFrame(long generation, float fps, int appJankFrames, int totalFrames,
                           boolean layerChanged, IBinder client);
}
