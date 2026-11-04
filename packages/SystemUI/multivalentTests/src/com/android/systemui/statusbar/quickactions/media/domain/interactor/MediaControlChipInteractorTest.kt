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

package com.android.systemui.statusbar.quickactions.media.domain.interactor

import android.content.applicationContext
import android.graphics.drawable.Drawable
import android.platform.test.flag.junit.FlagsParameterization
import android.provider.Settings
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.activity.data.repository.activityManagerRepository
import com.android.systemui.activityIntentHelper
import com.android.systemui.classifier.falsingManager
import com.android.systemui.flags.parameterizeSceneContainerFlag
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.backgroundScope
import com.android.systemui.kosmos.collectLastValue
import com.android.systemui.kosmos.runCurrent
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.log.logcatLogBuffer
import com.android.systemui.media.controls.domain.pipeline.MediaDataManager
import com.android.systemui.media.controls.shared.model.MediaAction
import com.android.systemui.media.controls.shared.model.MediaButton
import com.android.systemui.media.controls.shared.model.MediaData
import com.android.systemui.media.mediaOutputDialogManager
import com.android.systemui.media.remedia.data.repository.mediaPauseActionButton
import com.android.systemui.media.remedia.data.repository.mediaPlayActionButton
import com.android.systemui.media.remedia.data.repository.mediaRepository
import com.android.systemui.media.remedia.shared.flag.MediaControlsInComposeFlag
import com.android.systemui.media.remedia.shared.model.MediaSessionState
import com.android.systemui.plugins.activityStarter
import com.android.systemui.statusbar.notificationLockscreenUserManager
import com.android.systemui.statusbar.policy.configurationController
import com.android.systemui.statusbar.policy.keyguardStateController
import com.android.systemui.statusbar.quickactions.popups.shared.DynamicIslandFeatureSettings
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.mock
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

@RunWith(ParameterizedAndroidJunit4::class)
@SmallTest
class MediaControlChipInteractorTest(flags: FlagsParameterization) : SysuiTestCase() {
    private val kosmos = testKosmos().useUnconfinedTestDispatcher()
    private val mediaRepository = kosmos.mediaRepository
    private val Kosmos.underTest by
        Kosmos.Fixture {
            MediaControlChipInteractor(
                context = applicationContext,
                backgroundScope = backgroundScope,
                mediaRepository = mediaRepository,
                mediaOutputDialogManager = mediaOutputDialogManager,
                falsingManager = falsingManager,
                activityStarter = activityStarter,
                activityIntentHelper = activityIntentHelper,
                lockscreenUserManager = notificationLockscreenUserManager,
                keyguardStateController = keyguardStateController,
                configurationController = configurationController,
                activityManagerRepository = activityManagerRepository,
                logBuffer = logcatLogBuffer("MediaControlChipTest"),
            )
        }
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
        kosmos.underTest.initialize()
        MockitoAnnotations.initMocks(this)
    }

    init {
        mSetFlagsRule.setFlagsParameterization(flags)
    }

    @Test
    fun mediaControlChipModel_noActiveMedia_null() =
        kosmos.runTest {
            val model by collectLastValue(underTest.mediaControlChipModel)

            assertThat(model).isNull()
        }

    @Test
    fun mediaControlChipModel_activeMedia_notNull() =
        kosmos.runTest {
            val model by collectLastValue(underTest.mediaControlChipModel)

            val userMedia = MediaData(active = true)

            updateMedia(userMedia)

            assertThat(model).isNotNull()
        }

    @Test
    fun mediaControlChipModel_mediaRemoved_null() =
        kosmos.runTest {
            val model by collectLastValue(underTest.mediaControlChipModel)

            val userMedia = MediaData(active = true)

            updateMedia(userMedia)

            assertThat(model).isNotNull()

            removeMedia(userMedia)

            assertThat(model).isNull()
        }

    @Test
    fun mediaControlChipModel_songNameChanged_emitsUpdatedModel() =
        kosmos.runTest {
            val model by collectLastValue(underTest.mediaControlChipModel)

            val initialSongName = "Initial Song"
            val newSongName = "New Song"
            val userMedia = MediaData(active = true, song = initialSongName)

            updateMedia(userMedia)

            assertThat(model).isNotNull()
            assertThat(model?.songName).isEqualTo(initialSongName)

            val updatedUserMedia = userMedia.copy(song = newSongName)
            updateMedia(updatedUserMedia)

            assertThat(model?.songName).isEqualTo(newSongName)
        }

    @Test
    fun mediaControlChipModel_playPauseActionChanges_emitsUpdatedModel() =
        kosmos.runTest {
            val model by collectLastValue(underTest.mediaControlChipModel)

            val mockDrawable = mock<Drawable>()

            val initialAction =
                MediaAction(
                    icon = mockDrawable,
                    action = {},
                    contentDescription = "Initial Action",
                    background = mockDrawable,
                )
            val favorite = initialAction.copy(contentDescription = "Favorite")
            val shuffle = initialAction.copy(contentDescription = "Shuffle")
            val mediaButton = MediaButton(
                playOrPause = initialAction,
                custom0 = favorite,
                custom1 = shuffle,
            )
            val userMedia = MediaData(active = true, semanticActions = mediaButton)
            updateMedia(userMedia)

            assertThat(model).isNotNull()
            assertThat(model?.playOrPause).isEqualTo(initialAction)
            assertThat(model?.customActions).containsExactly(favorite, shuffle).inOrder()

            val newAction =
                MediaAction(
                    icon = mockDrawable,
                    action = {},
                    contentDescription = "New Action",
                    background = mockDrawable,
                )
            val updatedMediaButton = MediaButton(playOrPause = newAction)
            val updatedUserMedia = userMedia.copy(semanticActions = updatedMediaButton)
            updateMedia(updatedUserMedia)

            assertThat(model?.playOrPause).isEqualTo(newAction)
            assertThat(model?.customActions).isEmpty()
        }

    @Test
    fun mediaControlChipModel_playPauseActionRemoved_playPauseNull() =
        kosmos.runTest {
            val model by collectLastValue(underTest.mediaControlChipModel)

            val mockDrawable = mock<Drawable>()

            val initialAction =
                MediaAction(
                    icon = mockDrawable,
                    action = {},
                    contentDescription = "Initial Action",
                    background = mockDrawable,
                )
            val mediaButton = MediaButton(playOrPause = initialAction)
            val userMedia = MediaData(active = true, semanticActions = mediaButton)
            updateMedia(userMedia)

            assertThat(model).isNotNull()
            assertThat(model?.playOrPause).isEqualTo(initialAction)

            val updatedUserMedia = userMedia.copy(semanticActions = MediaButton())
            updateMedia(updatedUserMedia)

            assertThat(model?.playOrPause).isNull()
        }

    @Test
    fun mediaControlChipModel_withPackageName_preserved() =
        kosmos.runTest {
            val model by collectLastValue(underTest.mediaControlChipModel)

            val userMedia =
                MediaData(active = true, packageName = "com.google.android.apps.youtube.music")
            updateMedia(userMedia)

            assertThat(model).isNotNull()
            assertThat(model?.packageName).isEqualTo("com.google.android.apps.youtube.music")
        }

    @Test
    fun mediaControlChipModel_visibleApp_retainsModelAndTracksPlayback() =
        kosmos.runTest {
            kosmos.activityManagerRepository.startingIsAppVisibleValue = true
            val model by collectLastValue(underTest.mediaControlChipModel)
            val userMedia =
                MediaData(
                    active = true,
                    appUid = 1012345,
                    song = "Playing",
                    isPlaying = true,
                    semanticActions = MediaButton(playOrPause = mediaPauseActionButton),
                )
            updateMedia(userMedia)
            if (MediaControlsInComposeFlag.isEnabled) {
                mediaRepository.currentMedia[0] =
                    mediaRepository.currentMedia[0].copy(state = MediaSessionState.Playing)
                runCurrent()
            }

            assertThat(model).isNotNull()
            assertThat(model?.isAppVisible).isTrue()

            assertThat(model?.isPlaying).isTrue()
            kosmos.activityManagerRepository.startingIsAppVisibleValue = false
            updateMedia(
                userMedia.copy(
                    song = "Paused",
                    isPlaying = false,
                    semanticActions = MediaButton(playOrPause = mediaPlayActionButton),
                )
            )
            assertThat(model?.songName).isEqualTo("Paused")
            assertThat(model?.isPlaying).isFalse()
            assertThat(model?.isAppVisible).isTrue()

            kosmos.activityManagerRepository.setIsAppVisible(userMedia.appUid, false)
            assertThat(model?.isAppVisible).isFalse()
            assertThat(model?.songName).isEqualTo("Paused")
        }

    @Test
    fun mediaControlChipModel_uidChanges_ignoresPreviousUid() =
        kosmos.runTest {
            val model by collectLastValue(underTest.mediaControlChipModel)
            val userMedia = MediaData(active = true, appUid = 1012345, song = "Song")
            updateMedia(userMedia)
            kosmos.activityManagerRepository.setIsAppVisible(userMedia.appUid, true)
            assertThat(model?.isAppVisible).isTrue()

            val otherUserMedia = userMedia.copy(appUid = 1112345)
            updateMedia(otherUserMedia)
            assertThat(model?.isAppVisible).isFalse()

            kosmos.activityManagerRepository.setIsAppVisible(userMedia.appUid, false)
            kosmos.activityManagerRepository.setIsAppVisible(userMedia.appUid, true)
            assertThat(model?.isAppVisible).isFalse()
            kosmos.activityManagerRepository.setIsAppVisible(otherUserMedia.appUid, true)
            assertThat(model?.isAppVisible).isTrue()
        }

    @Test
    fun mediaControlChipModel_invalidUid_notVisible() =
        kosmos.runTest {
            kosmos.activityManagerRepository.startingIsAppVisibleValue = true
            val model by collectLastValue(underTest.mediaControlChipModel)
            updateMedia(MediaData(active = true, appUid = -1))
            assertThat(model).isNotNull()
            assertThat(model?.isAppVisible).isFalse()
        }

    private fun updateMedia(mediaData: MediaData) {
        if (MediaControlsInComposeFlag.isEnabled) {
            mediaRepository.addCurrentUserMediaEntry(mediaData)
            kosmos.runCurrent()
        } else {
            kosmos.underTest.updateMediaControlChipModelLegacy(mediaData)
        }
    }

    private fun removeMedia(mediaData: MediaData) {
        if (MediaControlsInComposeFlag.isEnabled) {
            val instanceId = mediaData.instanceId
            mediaRepository.removeCurrentUserMediaEntry(instanceId, mediaData)
        } else {
            kosmos.underTest.updateMediaControlChipModelLegacy(null)
        }
    }
}
