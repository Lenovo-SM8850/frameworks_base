// SPDX-License-Identifier: Apache-2.0

package io.github.miner7222.systemui.lyrics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserHandle
import android.util.Log
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.settings.UserTracker
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

@SysUISingleton
class LyricSyncRepository
@Inject
constructor(
    @Application private val context: Context,
    @Application private val scope: CoroutineScope,
    private val userTracker: UserTracker,
    private val audioCapture: LyricAudioCapture,
    private val broadcastDispatcher: BroadcastDispatcher,
) {
    fun interface Listener {
        fun onLyricsChanged(state: LyricState)
    }

    private val main = Handler(Looper.getMainLooper())
    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val listeners = mutableSetOf<Listener>()
    private val consumers = mutableMapOf<Any, MediaSession.Token?>()
    private val mutableState = MutableStateFlow(LyricState())
    val state = mutableState.asStateFlow()
    private val offsets = LyricOffsetCache()
    private val attempts = LyricAttemptCache()
    private var calibrationDeadlineMs: Long? = null
    private var controller: MediaController? = null
    private var track: Track? = null
    private var parsed = ParsedLyrics(emptyList())
    private var record: LyricRecord? = null
    private var audioOffsetMs = 0L
    private var fetchJob: Job? = null
    private var captureJob: Job? = null
    private var generation = 0
    private var started = false
    private var occurrence = LyricCalibrationAttempt()
    private var captureAnchor: LyricPlaybackSnapshot? = null
    private var captureUid = -1
    private var captureRoute: String? = null
    private val playbackCallback =
        object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>) {
                if (
                    captureAnchor != null &&
                        (playerRoute(configs, captureUid) != captureRoute ||
                            LyricAudioCapture.hasLowLatencyOutput(configs))
                )
                    cancelCapture()
            }
        }
    private val tick = Runnable { update() }
    private val calibrate = Runnable { startCalibration() }
    private var sessionListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private val wakeReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                update()
            }
        }
    private val userCallback =
        object : UserTracker.Callback {
            override fun onUserChanged(newUser: Int, userContext: Context) {
                if (!started) return
                detach()
                try {
                    registerSessionListener()
                    refreshSessions()
                } catch (_: SecurityException) {
                    detach()
                }
            }
        }
    private val deviceCallback =
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(devices: Array<out AudioDeviceInfo>) {
                routeChanged(devices)
            }

            override fun onAudioDevicesRemoved(devices: Array<out AudioDeviceInfo>) {
                routeChanged(devices)
            }
        }
    private val callback =
        object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                captureAnchor?.let {
                    if (LyricCalibrationRules.cancels(it, snapshot())) cancelCapture()
                }
                update()
                if (state?.state != PlaybackState.STATE_PLAYING) refreshSessions()
            }

            override fun onMetadataChanged(metadata: MediaMetadata?) {
                metadataChanged()
            }

            override fun onAudioInfoChanged(info: MediaController.PlaybackInfo) {
                captureAnchor?.let { if (snapshot()?.route != it.route) cancelCapture() }
                update()
            }

            override fun onSessionDestroyed() {
                detach()
                refreshSessions()
            }
        }

    fun addListener(listener: Listener) {
        main.post {
            listeners.add(listener)
            setEnabledInternal(listener, true)
            listener.onLyricsChanged(state.value)
        }
    }

    fun removeListener(listener: Listener) {
        main.post {
            listeners.remove(listener)
            setEnabledInternal(listener, false)
        }
    }

    fun setConsumerEnabled(owner: Any, enabled: Boolean, token: MediaSession.Token? = null) {
        main.post { setEnabledInternal(owner, enabled, token) }
    }

    private fun setEnabledInternal(
        owner: Any,
        enabled: Boolean,
        token: MediaSession.Token? = null,
    ) {
        if (enabled) consumers[owner] = token else consumers.remove(owner)
        if (consumers.isNotEmpty() && !started) {
            started = true
            userTracker.addCallback(userCallback, Executor { main.post(it) })
            audio?.registerAudioDeviceCallback(deviceCallback, main)
            audio?.registerAudioPlaybackCallback(playbackCallback, main)
            try {
                broadcastDispatcher.registerReceiver(
                    wakeReceiver,
                    IntentFilter().apply {
                        addAction(Intent.ACTION_SCREEN_ON)
                        addAction(Intent.ACTION_USER_PRESENT)
                    },
                    Executor { main.post(it) },
                    UserHandle.ALL,
                )
                registerSessionListener()
                refreshSessions()
            } catch (_: SecurityException) {
                publish(LyricState())
            }
        } else if (consumers.isEmpty() && started) {
            started = false
            sessionListener?.let { sessions?.removeOnActiveSessionsChangedListener(it) }
            sessionListener = null
            broadcastDispatcher.unregisterReceiver(wakeReceiver)
            userTracker.removeCallback(userCallback)
            audio?.unregisterAudioDeviceCallback(deviceCallback)
            audio?.unregisterAudioPlaybackCallback(playbackCallback)
            detach()
        } else if (started) {
            refreshSessions()
        }
    }

    private fun registerSessionListener() {
        sessionListener?.let { sessions?.removeOnActiveSessionsChangedListener(it) }
        val userId = userTracker.userId
        val listener =
            MediaSessionManager.OnActiveSessionsChangedListener {
                if (started && userTracker.userId == userId) select(it)
            }
        sessionListener = listener
        sessions?.addOnActiveSessionsChangedListener(
            null,
            userTracker.userHandle,
            Executor { main.post(it) },
            listener,
        )
    }

    private fun refreshSessions() {
        if (!started) return
        try {
            select(sessions?.getActiveSessionsForUser(null, userTracker.userHandle))
        } catch (_: SecurityException) {
            detach()
        }
    }

    private fun select(available: List<MediaController>?) {
        val eligible =
            available.orEmpty().filter {
                it.playbackState != null &&
                    UserHandle.getUserId(it.sessionToken.uid) == userTracker.userId
            }
        val preferred = consumers.values.filterNotNull().toSet()
        val best =
            eligible.firstOrNull {
                it.sessionToken in preferred &&
                    it.playbackState?.state == PlaybackState.STATE_PLAYING
            }
                ?: eligible.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: eligible.firstOrNull { it.sessionToken in preferred }
                ?: eligible.firstOrNull()
        if (best?.sessionToken == controller?.sessionToken) return
        detach()
        controller = best
        best?.registerCallback(callback, main)
        metadataChanged()
    }

    private fun detach() {
        controller?.unregisterCallback(callback)
        controller = null
        track = null
        clearTrack()
    }

    private fun clearTrack() {
        generation++
        fetchJob?.cancel()
        fetchJob = null
        main.removeCallbacks(tick)
        main.removeCallbacks(calibrate)
        cancelCapture()
        occurrence = LyricCalibrationAttempt()
        calibrationDeadlineMs = null
        parsed = ParsedLyrics(emptyList())
        record = null
        audioOffsetMs = 0
        publish(LyricState())
    }

    private fun metadataChanged() {
        val current = controller ?: return
        val metadata = current.metadata
        if (metadata == null) {
            clearTrack()
            track = null
            return
        }
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
        val next =
            if (title.isNullOrBlank() || artist.isNullOrBlank()) null
            else
                Track(
                    userTracker.userId,
                    current.packageName,
                    metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty(),
                    title,
                    artist,
                    metadata.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
                    metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0),
                )
        if (next == track) {
            update()
            return
        }
        clearTrack()
        track = next
        if (next == null) return
        val expected = generation
        fetchJob =
            scope.launch(Dispatchers.Main) {
                for (attempt in 0..2) {
                    val result =
                        try {
                            withContext(Dispatchers.IO) { lookup(next) }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            null
                        }
                    if (generation != expected) return@launch
                    if (result != null) {
                        record = result.record
                        parsed = LyricTiming.parse(result.record?.synced.orEmpty())
                        key()?.let { audioOffsetMs = offsets[it] ?: 0 }
                        if (key()?.let { it in attempts || offsets[it] != null } == true) occurrence.begin()
                        update()
                        return@launch
                    }
                    if (attempt < 2) delay(4000)
                }
            }
    }

    private fun update() {
        main.removeCallbacks(tick)
        main.removeCallbacks(calibrate)
        val playback = controller?.playbackState
        val duration = track?.durationMs ?: 0
        val playing = playback?.state == PlaybackState.STATE_PLAYING
        val position = currentPosition(playback)
        val offset = audioOffsetMs - parsed.lrcOffsetMs
        val index = LyricTiming.activeIndex(parsed.lines, position, offset)
        publish(
            LyricState(
                parsed.lines,
                index,
                offset,
                record?.plain,
                record?.synced,
                track?.playerPackage,
                controller?.sessionToken?.hashCode(),
                track?.title,
                track?.artist,
                isPlaying = playing,
            )
        )
        if (playback != null) {
            LyricTiming.nextDelayMs(
                    parsed.lines,
                    position,
                    offset,
                    playback.playbackSpeed,
                    playing && playback.lastPositionUpdateTime > 0,
                    duration,
                )
                ?.let { main.postDelayed(tick, it) }
        }
        if (
            playing &&
                playback?.playbackSpeed == 1f &&
                !occurrence.attempted &&
                parsed.lines.isNotEmpty()
        ) {
            val start =
                LyricCalibrationRules.windowStart(
                    parsed.lines,
                    parsed.lrcOffsetMs,
                    position,
                    duration,
                )
            if (start == null || playback.lastPositionUpdateTime == 0L) {
                occurrence.begin()
            } else {
                val delayMs = (start - position).coerceAtLeast(0)
                calibrationDeadlineMs = SystemClock.elapsedRealtime() + delayMs
                main.postDelayed(calibrate, delayMs)
            }
        }
    }

    private fun currentPosition(playback: PlaybackState?): Long {
        if (playback == null) return 0
        return LyricTiming.position(
            playback.position,
            playback.lastPositionUpdateTime,
            SystemClock.elapsedRealtime(),
            playback.playbackSpeed,
            playback.state == PlaybackState.STATE_PLAYING,
            track?.durationMs ?: 0,
        )
    }

    private fun snapshot(): LyricPlaybackSnapshot? {
        val playback = controller?.playbackState ?: return null
        val info = controller?.playbackInfo
        return LyricPlaybackSnapshot(
            currentPosition(playback),
            SystemClock.elapsedRealtime(),
            playback.state == PlaybackState.STATE_PLAYING,
            playback.playbackSpeed,
            "${info?.playbackType}:${info?.volumeControlId}",
            generation.toString(),
        )
    }

    private fun playerRoute(configs: List<AudioPlaybackConfiguration>, uid: Int): String? {
        val players =
            configs.filter {
                it.clientUid == uid &&
                    it.isActive &&
                    it.audioAttributes.usage == AudioAttributes.USAGE_MEDIA
            }
        if (players.isEmpty()) return null
        return players
            .flatMap { it.audioDeviceInfos }
            .filter { it.type != AudioDeviceInfo.TYPE_REMOTE_SUBMIX }
            .map { it.id }
            .distinct()
            .sorted()
            .joinToString(",")
            .takeIf { it.isNotEmpty() }
    }

    private fun routeChanged(devices: Array<out AudioDeviceInfo>) {
        if (devices.any { it.isSink && it.type != AudioDeviceInfo.TYPE_REMOTE_SUBMIX })
            cancelCapture()
    }

    private fun cancelCapture() {
        captureJob?.cancel()
        captureJob = null
        captureAnchor = null
        captureUid = -1
        captureRoute = null
    }

    private fun startCalibration() {
        if (!started || occurrence.attempted) return
        val deadline = calibrationDeadlineMs ?: return
        val remaining = deadline - SystemClock.elapsedRealtime()
        if (remaining > 0) {
            main.postDelayed(calibrate, remaining)
            return
        }
        if (remaining < -250) {
            update()
            return
        }
        val anchor = snapshot() ?: return
        if (!anchor.playing || anchor.speed != 1f) return
        val current = track ?: return
        val key = key() ?: return
        val start =
            LyricCalibrationRules.windowStart(
                parsed.lines,
                parsed.lrcOffsetMs,
                anchor.positionMs,
                current.durationMs,
            )
                ?: run {
                    occurrence.begin()
                    return
                }
        if (start > anchor.positionMs) {
            update()
            return
        }
        val uid = controller?.sessionToken?.uid ?: return
        if (UserHandle.getUserId(uid) != current.userId) return
        val starts =
            parsed.lines.filter { it.text.isNotBlank() }.map { it.timestampMs - parsed.lrcOffsetMs }
        val expected = generation
        val configs = audio?.activePlaybackConfigurations.orEmpty()
        val route = playerRoute(configs, uid)
        if (route == null || LyricAudioCapture.hasLowLatencyOutput(configs)) {
            occurrence.begin()
            return
        }
        occurrence.begin()
        if (!attempts.begin(key)) return
        captureUid = uid
        captureRoute = route
        captureAnchor = anchor
        captureJob =
            scope.launch(Dispatchers.Main) {
                try {
                    val window = audioCapture.capture(uid, anchor)
                    val result =
                        if (window == null) LyricOffsetEstimator.Result()
                        else
                            withContext(Dispatchers.Default) {
                                LyricOffsetEstimator.estimate(
                                    window.pcm,
                                    window.startPositionMs,
                                    starts,
                                )
                            }
                    if (
                        generation != expected ||
                            snapshot()?.let { LyricCalibrationRules.cancels(anchor, it) } != false
                    )
                        return@launch
                    if (result.accepted) {
                        offsets.put(key, result.offsetMs)
                        audioOffsetMs = result.offsetMs
                        update()
                    }
                    Log.d(TAG, "Offset=${result.offsetMs}ms confidence=${result.confidence}")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    Log.d(TAG, "Offset=0ms confidence=0.0")
                } finally {
                    if (generation == expected && captureAnchor === anchor) {
                        captureAnchor = null
                        captureUid = -1
                        captureRoute = null
                        captureJob = null
                    }
                }
            }
    }

    private fun key(): LyricTrackKey? {
        val current = track ?: return null
        val lyrics = record ?: return null
        return LyricTrackKey(
            current.userId,
            current.playerPackage,
            current.mediaId.ifEmpty {
                "${current.artist}\u0000${current.title}\u0000${current.album}"
            },
            current.durationMs,
            "${lyrics.id}:${LyricTrackKey.contentHash(lyrics.synced.orEmpty())}",
        )
    }

    private fun publish(value: LyricState) {
        if (mutableState.value == value) return
        mutableState.value = value
        listeners.toList().forEach { it.onLyricsChanged(value) }
    }

    private data class Track(
        val userId: Int,
        val playerPackage: String,
        val mediaId: String,
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
    )

    private data class Lookup(val record: LyricRecord?)

    private suspend fun lookup(track: Track): Lookup {
        fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
        if (track.durationMs > 0) {
            val query =
                "track_name=${encode(track.title)}&artist_name=${encode(track.artist)}" +
                    "&album_name=${encode(track.album)}&duration=${track.durationMs / 1000.0}"
            try {
                request("get?$query")?.let { response ->
                    val exact = toRecord(JSONObject(response))
                    if (
                        kotlin.math.abs(exact.durationMs - track.durationMs) <= 2000 &&
                            !exact.synced.isNullOrBlank()
                    ) {
                        return Lookup(exact)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                /* Search remains available when the exact lookup fails. */
            }
        }
        val title = LyricLookup.title(track.title, track.durationMs)
        val query = encode("${LyricLookup.artist(track.artist)} $title")
        val response = request("search?q=$query") ?: error("Lyrics unavailable")
        val array = JSONArray(response)
        val results = (0 until array.length()).map { toRecord(array.getJSONObject(it)) }
        return Lookup(LyricLookup.choose(results, track.durationMs))
    }

    private fun toRecord(json: JSONObject) =
        LyricRecord(
            json.optString("id"),
            (json.optDouble("duration", 0.0) * 1000).toLong(),
            if (json.isNull("syncedLyrics")) null
            else json.optString("syncedLyrics").takeIf { it.isNotBlank() },
            if (json.isNull("plainLyrics")) null
            else json.optString("plainLyrics").takeIf { it.isNotBlank() },
        )

    private suspend fun request(path: String): String? {
        currentCoroutineContext().ensureActive()
        return suspendCancellableCoroutine { continuation ->
            val connection = URL("https://lrclib.net/api/$path").openConnection() as HttpURLConnection
            continuation.invokeOnCancellation { connection.disconnect() }
            try {
                if (!continuation.isActive) return@suspendCancellableCoroutine
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.setRequestProperty("User-Agent", "SystemUI-Lyrics/1.0")
                val response = if (connection.responseCode == 200)
                    connection.inputStream.bufferedReader().use { it.readText() }
                else null
                continuation.resumeWith(Result.success(response))
            } catch (e: Exception) {
                continuation.resumeWith(Result.failure(e))
            } finally {
                connection.disconnect()
            }
        }
    }

    companion object {
        private const val TAG = "LyricSyncRepository"
    }
}
