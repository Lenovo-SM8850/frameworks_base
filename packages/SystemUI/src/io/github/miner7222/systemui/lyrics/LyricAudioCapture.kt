// SPDX-License-Identifier: Apache-2.0

package io.github.miner7222.systemui.lyrics

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.audiopolicy.AudioMix
import android.media.audiopolicy.AudioMixingRule
import android.media.audiopolicy.AudioPolicy
import android.os.SystemClock
import com.android.systemui.dagger.qualifiers.Application
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class LyricAudioCapture @Inject constructor(@Application private val context: Context) {
    data class Window(val pcm: ShortArray, val startPositionMs: Double)

    suspend fun capture(uid: Int, anchor: LyricPlaybackSnapshot): Window? =
        withContext(Dispatchers.IO) {
            val manager =
                context.getSystemService(AudioManager::class.java) ?: return@withContext null
            if (hasLowLatencyOutput(manager.activePlaybackConfigurations)) return@withContext null
            val format =
                AudioFormat.Builder()
                    .setSampleRate(LyricOffsetEstimator.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            val rule =
                AudioMixingRule.Builder()
                    .setTargetMixRole(AudioMixingRule.MIX_ROLE_PLAYERS)
                    .addMixRule(AudioMixingRule.RULE_MATCH_UID, uid)
                    .addRule(
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build(),
                        AudioMixingRule.RULE_MATCH_ATTRIBUTE_USAGE,
                    )
                    .allowPrivilegedPlaybackCapture(true)
                    .build()
            val mix =
                AudioMix.Builder(rule)
                    .setFormat(format)
                    .setRouteFlags(AudioMix.ROUTE_FLAG_LOOP_BACK or AudioMix.ROUTE_FLAG_RENDER)
                    .build()
            val policy = AudioPolicy.Builder(context).addMix(mix).build()
            var registered = false
            var record: AudioRecord? = null
            try {
                currentCoroutineContext().ensureActive()
                if (hasLowLatencyOutput(manager.activePlaybackConfigurations))
                    return@withContext null
                if (manager.registerAudioPolicy(policy) != AudioManager.SUCCESS)
                    return@withContext null
                registered = true
                val sink = policy.createAudioRecordSink(mix) ?: return@withContext null
                record = sink
                if (sink.state != AudioRecord.STATE_INITIALIZED) return@withContext null
                sink.startRecording()
                if (sink.recordingState != AudioRecord.RECORDSTATE_RECORDING)
                    return@withContext null
                val pcm =
                    ShortArray(
                        (LyricCalibrationRules.WINDOW_MS * LyricOffsetEstimator.SAMPLE_RATE / 1000)
                            .toInt()
                    )
                val timestamp = AudioTimestamp()
                var samples = 0
                val clock = LyricCaptureClock()
                val deadline = SystemClock.elapsedRealtime() + LyricCalibrationRules.WINDOW_MS + 500
                while (samples < pcm.size) {
                    currentCoroutineContext().ensureActive()
                    if (SystemClock.elapsedRealtime() > deadline) return@withContext null
                    val count =
                        sink.read(
                            pcm,
                            samples,
                            minOf(320, pcm.size - samples),
                            AudioRecord.READ_NON_BLOCKING,
                        )
                    if (count < 0) return@withContext null
                    samples += count
                    if (
                        sink.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_BOOTTIME) ==
                            AudioRecord.SUCCESS
                    ) {
                        if (
                            timestamp.framePosition - samples > sink.bufferSizeInFrames ||
                                !clock.observe(
                                    timestamp.framePosition,
                                    timestamp.nanoTime,
                                    SystemClock.elapsedRealtimeNanos(),
                                )
                        ) {
                            return@withContext null
                        }
                    }
                    if (count == 0) delay(10)
                }
                val start =
                    clock.startPositionMs(anchor, SystemClock.elapsedRealtimeNanos())
                        ?: return@withContext null
                Window(pcm, start)
            } finally {
                try {
                    record?.let {
                        try {
                            if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop()
                        } finally {
                            it.release()
                        }
                    }
                } finally {
                    if (registered) manager.unregisterAudioPolicy(policy)
                }
            }
        }

    companion object {
        fun hasLowLatencyOutput(configs: List<AudioPlaybackConfiguration>): Boolean =
            configs.any {
                it.isActive &&
                    (it.playerType == AudioPlaybackConfiguration.PLAYER_TYPE_AAUDIO ||
                        it.audioAttributes.allFlags and AudioAttributes.FLAG_LOW_LATENCY != 0)
            }
    }
}
