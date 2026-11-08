/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.statusbar.quickactions.media.ui.viewmodel

import android.media.session.MediaSession
import android.content.applicationContext
import android.platform.test.flag.junit.FlagsParameterization
import android.provider.Settings
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.activity.data.repository.activityManagerRepository
import com.android.systemui.activityIntentHelper
import com.android.systemui.flags.parameterizeSceneContainerFlag
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.backgroundScope
import com.android.systemui.kosmos.runCurrent
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.testScope
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.lifecycle.activateIn
import com.android.systemui.log.logcatLogBuffer
import com.android.systemui.media.controls.domain.pipeline.MediaDataManager
import com.android.systemui.media.controls.shared.model.MediaButton
import com.android.systemui.media.controls.shared.model.MediaData
import com.android.systemui.media.remedia.data.repository.mediaPauseActionButton
import com.android.systemui.media.remedia.data.repository.mediaPlayActionButton
import com.android.systemui.media.remedia.data.repository.mediaRepository
import com.android.systemui.media.remedia.shared.flag.MediaControlsInComposeFlag
import com.android.systemui.plugins.activityStarter
import com.android.systemui.statusbar.notificationLockscreenUserManager
import com.android.systemui.statusbar.policy.keyguardStateController
import com.android.systemui.statusbar.quickactions.media.domain.interactor.MediaControlChipInteractor
import com.android.systemui.statusbar.quickactions.popups.shared.DynamicIslandFeatureSettings
import com.android.systemui.statusbar.quickactions.shared.model.PopupContentModel
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipModel
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.MockitoAnnotations
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

@SmallTest
@RunWith(ParameterizedAndroidJunit4::class)
class MediaControlChipViewModelTest(flags: FlagsParameterization) : SysuiTestCase() {
    private val kosmos = testKosmos().useUnconfinedTestDispatcher()
    private val mediaControlChipInteractor by lazy {
        with(kosmos) {
            MediaControlChipInteractor(
                context = applicationContext,
                backgroundScope = backgroundScope,
                mediaRepository = mediaRepository,
                activityStarter = activityStarter,
                activityIntentHelper = activityIntentHelper,
                lockscreenUserManager = notificationLockscreenUserManager,
                keyguardStateController = keyguardStateController,
                activityManagerRepository = activityManagerRepository,
                logBuffer = logcatLogBuffer("MediaControlChipTest"),
            )
        }
    }
    private val Kosmos.underTest by
        Kosmos.Fixture { MediaControlChipViewModel(mediaControlChipInteractor) }
    @Captor lateinit var listener: ArgumentCaptor<MediaDataManager.Listener>

    companion object {
        @JvmStatic
        @Parameters(name = "{0}")
        fun getParams(): List<FlagsParameterization> {
            return parameterizeSceneContainerFlag()
        }
    }

    @Before
    fun setUp() {
        Settings.System.putInt(
            mContext.contentResolver,
            Settings.System.STATUS_BAR_SHOW_DYNAMIC_ISLAND,
            1,
        )
        Settings.System.putInt(
            mContext.contentResolver,
            DynamicIslandFeatureSettings.MEDIA_CONTROLS,
            1,
        )
        MockitoAnnotations.initMocks(this)
        mediaControlChipInteractor.initialize()
        kosmos.underTest.activateIn(kosmos.testScope)
    }

    init {
        mSetFlagsRule.setFlagsParameterization(flags)
    }

    @Test
    fun chip_noActiveMedia_IsHidden() =
        kosmos.runTest {
            val chip = underTest.chip

            assertThat(chip).isInstanceOf(QuickActionChipModel.Hidden::class.java)
        }

    @Test
    fun chip_activeMedia_IsShown() =
        kosmos.runTest {
            val userMedia = MediaData(active = true, song = "test")
            updateMedia(userMedia)

            assertThat(underTest.chip).isInstanceOf(QuickActionChipModel.PopupChip::class.java)
        }

    @Test
    fun chip_visibleApp_hiddenThenShowsPausedMediaOnLeaving() =
        kosmos.runTest {
            kosmos.activityManagerRepository.startingIsAppVisibleValue = true
            val userMedia =
                MediaData(
                    active = true,
                    appUid = 1012345,
                    song = "test",
                    isPlaying = true,
                    semanticActions = MediaButton(playOrPause = mediaPauseActionButton),
                )
            updateMedia(userMedia)
            runCurrent()
            assertThat(underTest.chip).isInstanceOf(QuickActionChipModel.Hidden::class.java)
            kosmos.activityManagerRepository.startingIsAppVisibleValue = false

            updateMedia(
                userMedia.copy(
                    isPlaying = false,
                    semanticActions = MediaButton(playOrPause = mediaPlayActionButton),
                )
            )
            runCurrent()
            assertThat(underTest.chip).isInstanceOf(QuickActionChipModel.Hidden::class.java)

            kosmos.activityManagerRepository.setIsAppVisible(userMedia.appUid, false)
            runCurrent()
            val chip = underTest.chip
            assertThat(chip).isInstanceOf(QuickActionChipModel.PopupChip::class.java)
            val media = (chip as QuickActionChipModel.PopupChip).popupContent
            assertThat((media as PopupContentModel.Media).model.isPlaying).isFalse()
        }

    @Test
    fun chip_dismissed_visibilityRoundTrip_staysHidden() =
        kosmos.runTest {
            val userMedia = MediaData(active = true, appUid = 1012345, song = "test")
            updateMedia(userMedia)
            runCurrent()
            (underTest.chip as QuickActionChipModel.PopupChip).onDismissed?.invoke()
            runCurrent()
            assertThat(underTest.chip).isInstanceOf(QuickActionChipModel.Hidden::class.java)

            kosmos.activityManagerRepository.setIsAppVisible(userMedia.appUid, true)
            runCurrent()
            assertThat(underTest.chip).isInstanceOf(QuickActionChipModel.Hidden::class.java)
            kosmos.activityManagerRepository.setIsAppVisible(userMedia.appUid, false)
            runCurrent()
            assertThat(underTest.chip).isInstanceOf(QuickActionChipModel.Hidden::class.java)
        }

    @Test
    fun chip_dismissed_trackChangeStaysHidden_pauseThenPlayShowsAgain() =
        kosmos.runTest {
            val playing = MediaData(
                active = true,
                packageName = "test.player",
                song = "first",
                isPlaying = true,
                semanticActions = MediaButton(playOrPause = mediaPauseActionButton),
            )
            updateMedia(playing)
            runCurrent()
            (underTest.chip as QuickActionChipModel.PopupChip).onDismissed?.invoke()
            runCurrent()
            updateMedia(playing.copy(song = "second"))
            runCurrent()
            assertThat(underTest.chip).isInstanceOf(QuickActionChipModel.Hidden::class.java)
            updateMedia(playing.copy(
                isPlaying = false,
                semanticActions = MediaButton(playOrPause = mediaPlayActionButton),
            ))
            runCurrent()
            assertThat(underTest.chip).isInstanceOf(QuickActionChipModel.Hidden::class.java)
            updateMedia(playing)
            runCurrent()
            assertThat(underTest.chip).isInstanceOf(QuickActionChipModel.PopupChip::class.java)
        }

    @Test
    fun chip_dismissed_differentSessionInSameAppShowsAgain() =
        kosmos.runTest {
            val first = MediaSession(context, "first")
            val second = MediaSession(context, "second")
            try {
                val playing = MediaData(
                    active = true,
                    packageName = "test.player",
                    token = first.sessionToken,
                    song = "first",
                    isPlaying = true,
                    semanticActions = MediaButton(playOrPause = mediaPauseActionButton),
                )
                updateMedia(playing)
                runCurrent()
                (underTest.chip as QuickActionChipModel.PopupChip).onDismissed?.invoke()
                runCurrent()
                updateMedia(playing.copy(token = second.sessionToken))
                runCurrent()
                assertThat(underTest.chip).isInstanceOf(QuickActionChipModel.PopupChip::class.java)
            } finally {
                first.release()
                second.release()
            }
        }

    private fun updateMedia(mediaData: MediaData) {
        if (MediaControlsInComposeFlag.isEnabled) {
            kosmos.mediaRepository.addCurrentUserMediaEntry(mediaData)
            kosmos.runCurrent()
        } else {
            mediaControlChipInteractor.updateMediaControlChipModelLegacy(mediaData)
        }
    }
}
