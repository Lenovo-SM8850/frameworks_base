// SPDX-License-Identifier: Apache-2.0
package com.android.wm.shell.splitscreen

import android.app.TaskInfo
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.UserHandle
import android.util.Slog
import java.util.concurrent.Executor

/** Tracks advertised playback state independently for each package and user. */
class ActiveSplitMediaSessions(
    private val manager: MediaSessionManager?,
    private val executor: Executor,
    private val handler: Handler,
) : SplitScreenFrameRateController.MediaPlayback {
    private class Session(val controller: MediaController, val callback: MediaController.Callback,
        var playing: Boolean)
    private val sessions = mutableMapOf<MediaSession.Token, Session>()
    private var changed = Runnable {}
    override var available = false
        private set

    override fun start(listener: Runnable) {
        changed = listener
        val service = manager ?: return
        try {
            service.addOnActiveSessionsChangedListener(null, UserHandle.ALL, executor,
                ::onSessionsChanged)
            available = true
            onSessionsChanged(service.getActiveSessionsForUser(null, UserHandle.ALL))
        } catch (e: SecurityException) {
            available = false
            sessions.values.forEach { it.controller.unregisterCallback(it.callback) }
            sessions.clear()
            Slog.w(TAG, "Could not observe active media sessions", e)
            changed.run()
        }
    }

    private fun onSessionsChanged(controllers: List<MediaController>?) {
        val active = controllers.orEmpty().associateBy { it.sessionToken }
        (sessions.keys - active.keys).forEach { token ->
            sessions.remove(token)?.let { it.controller.unregisterCallback(it.callback) }
        }
        active.forEach { (token, controller) ->
            if (sessions.containsKey(token)) return@forEach
            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    sessions[token]?.playing = state?.state == PlaybackState.STATE_PLAYING
                    changed.run()
                }
                override fun onSessionDestroyed() {
                    sessions.remove(token)?.let { it.controller.unregisterCallback(it.callback) }
                    changed.run()
                }
            }
            val session = Session(controller, callback, false)
            sessions[token] = session
            controller.registerCallback(callback, handler)
            session.playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING
        }
        changed.run()
    }

    override fun isPlaying(task: TaskInfo): Boolean {
        val packages = SplitScreenFrameRateController.packages(task)
        return sessions.any { (token, session) -> session.playing &&
            UserHandle.getUserId(token.uid) == task.userId &&
            session.controller.packageName in packages }
    }

    private companion object { const val TAG = "SplitMediaSessions" }
}
