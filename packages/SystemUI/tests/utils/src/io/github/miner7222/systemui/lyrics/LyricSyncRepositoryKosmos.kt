// SPDX-License-Identifier: Apache-2.0

package io.github.miner7222.systemui.lyrics

import android.content.applicationContext
import com.android.systemui.broadcast.broadcastDispatcher
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.settings.userTracker

val Kosmos.lyricAudioCapture by Kosmos.Fixture { LyricAudioCapture(applicationContext) }
val Kosmos.lyricSyncRepository by
    Kosmos.Fixture {
        LyricSyncRepository(
            applicationContext,
            applicationCoroutineScope,
            userTracker,
            lyricAudioCapture,
            broadcastDispatcher,
        )
    }
