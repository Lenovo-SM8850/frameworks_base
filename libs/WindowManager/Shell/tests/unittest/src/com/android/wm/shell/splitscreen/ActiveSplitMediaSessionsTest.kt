// SPDX-License-Identifier: Apache-2.0
package com.android.wm.shell.splitscreen

import android.content.ComponentName
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.UserHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.TestRunningTaskInfoBuilder
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.Executor
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class ActiveSplitMediaSessionsTest : ShellTestCase() {
    private fun task(pkg: String, user: Int) = TestRunningTaskInfoBuilder().build().apply {
        userId = user
        baseActivity = ComponentName(pkg, "Main")
        topActivity = baseActivity
        realActivity = baseActivity
        baseIntent = Intent().setComponent(baseActivity)
    }
    private fun state(value: Int) = PlaybackState.Builder().setState(value, 0, 1f).build()

    @Test fun playbackCallbacks_exemptOnlyMatchingPackageAndUser() {
        val manager = mock<MediaSessionManager>()
        val controller = mock<MediaController>()
        val token = mock<MediaSession.Token>()
        val handler = mock<Handler>()
        whenever(token.uid).thenReturn(UserHandle.getUid(10, 10001))
        whenever(controller.sessionToken).thenReturn(token)
        whenever(controller.packageName).thenReturn("split.media")
        whenever(controller.playbackState).thenReturn(state(PlaybackState.STATE_PAUSED))
        whenever(manager.getActiveSessionsForUser(null, UserHandle.ALL)).thenReturn(listOf(controller))
        val tracker = ActiveSplitMediaSessions(manager, Executor { it.run() }, handler)
        tracker.start(mock())
        val callback = argumentCaptor<MediaController.Callback>()
        verify(controller).registerCallback(callback.capture(), eq(handler))
        assertThat(tracker.available).isTrue()
        assertThat(tracker.isPlaying(task("split.media", 10))).isFalse()
        callback.firstValue.onPlaybackStateChanged(state(PlaybackState.STATE_PLAYING))
        assertThat(tracker.isPlaying(task("split.media", 10))).isTrue()
        assertThat(tracker.isPlaying(task("split.media", 0))).isFalse()
        assertThat(tracker.isPlaying(task("other.media", 10))).isFalse()
        callback.firstValue.onPlaybackStateChanged(state(PlaybackState.STATE_PAUSED))
        assertThat(tracker.isPlaying(task("split.media", 10))).isFalse()
        callback.firstValue.onPlaybackStateChanged(state(PlaybackState.STATE_PLAYING))
        callback.firstValue.onSessionDestroyed()
        assertThat(tracker.isPlaying(task("split.media", 10))).isFalse()
        verify(controller).unregisterCallback(callback.firstValue)
    }

    @Test fun sessionRemoval_unregistersPlaybackCallbackAndRemovesExemption() {
        val manager = mock<MediaSessionManager>()
        val controller = mock<MediaController>()
        val token = mock<MediaSession.Token>()
        val handler = mock<Handler>()
        val executor = Executor { it.run() }
        whenever(token.uid).thenReturn(UserHandle.getUid(0, 10001))
        whenever(controller.sessionToken).thenReturn(token)
        whenever(controller.packageName).thenReturn("split.media")
        whenever(controller.playbackState).thenReturn(state(PlaybackState.STATE_PLAYING))
        whenever(manager.getActiveSessionsForUser(null, UserHandle.ALL)).thenReturn(listOf(controller))
        val tracker = ActiveSplitMediaSessions(manager, executor, handler)
        tracker.start(mock())
        val listener = argumentCaptor<MediaSessionManager.OnActiveSessionsChangedListener>()
        verify(manager).addOnActiveSessionsChangedListener(isNull(), eq(UserHandle.ALL),
            eq(executor), listener.capture())
        val playback = argumentCaptor<MediaController.Callback>()
        verify(controller).registerCallback(playback.capture(), eq(handler))
        assertThat(tracker.isPlaying(task("split.media", 0))).isTrue()
        listener.firstValue.onActiveSessionsChanged(emptyList())
        assertThat(tracker.isPlaying(task("split.media", 0))).isFalse()
        verify(controller).unregisterCallback(playback.firstValue)
    }

    @Test fun missingPermission_disablesRateReduction() {
        val manager = mock<MediaSessionManager>()
        whenever(manager.getActiveSessionsForUser(null, UserHandle.ALL))
            .thenThrow(SecurityException("Permission denied"))
        val tracker = ActiveSplitMediaSessions(manager, Executor { it.run() }, mock())
        tracker.start(mock())
        assertThat(tracker.available).isFalse()
    }
}
