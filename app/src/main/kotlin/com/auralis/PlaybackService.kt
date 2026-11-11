package com.auralis

import android.app.Notification
import android.graphics.Bitmap
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.AudioManager
import android.media.AudioFocusRequest
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.os.PowerManager
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors
import kotlin.math.max

/** Playback is split into decoder, native DSP, and output threads. */
class PlaybackService : Service() {
    inner class LocalBinder : Binder() { fun service(): PlaybackService = this@PlaybackService }

    private val binder = LocalBinder()
    // Serializes configuration producers only; the native DSP consumer never takes this lock.
    private val configLock = Any()
    private val cleanupExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Auralis-cleanup").apply { isDaemon = true }
    }
    private val sessionLock = Any()
    private val queueLock = Any()
    private val queue = ArrayList<QueueEntry>()
    @Volatile private var queueIndex = -1
    @Volatile private var repeatMode = REPEAT_OFF
    @Volatile private var shuffleEnabled = false
    private var mediaSession: MediaSession? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var pausedForFocus = false
    @Volatile private var session: PlaybackSession? = null
    @Volatile private var stereoEnabled = false
    @Volatile private var stereoPercent = 0.0
    @Volatile private var balanceEnabled = false
    @Volatile private var balanceNormalized = 0.0
    @Volatile private var monoEnabled = false
    @Volatile private var limiterEnabled = false
    @Volatile private var limiterPath = 0
    @Volatile private var bassBoostEnabled = false
    @Volatile private var bassBoostAmount = 0.65
    @Volatile private var reverbEnabled = false
    @Volatile private var replayGainType = 0          // 0 None, 1 Apply, 2 Apply Peak
    @Volatile private var replayGainSource = 0        // 0 Album, 1 Track
    @Volatile private var replayGainDefaultMb = 0
    @Volatile private var masterGainDb = 0.0
    private val reverbParameters = doubleArrayOf(0.55, 0.50, 0.45, 0.15, 1.00, 0.50, 0.50)
    private val eqSettings = ConcurrentHashMap<Int, EqSetting>()

    @Volatile private var transitionSettings = PowerampTransitionSettings()
    @Volatile private var preloadedSession: PlaybackSession? = null
    @Volatile private var preloadedQueueIndex = -1
    @Volatile private var transitionIncoming: PlaybackSession? = null
    @Volatile private var transitionDurationMs = 0
    @Volatile private var pendingManualTarget = -1
    @Volatile private var pendingManualDurationMs = 0
    @Volatile private var outputTrack: AudioTrack? = null
    @Volatile private var outputPumpThread: Thread? = null
    @Volatile private var outputGeneration = 0L
    @Volatile private var transitionMixer: NativeTransitionMixer? = null
    @Volatile private var outputFadeMixer: NativeTransitionMixer? = null
    @Volatile private var pauseAfterFade = false
    @Volatile private var stopAfterFade = false
    @Volatile private var pendingSeekAfterFade: Pair<Long, Boolean>? = null
    @Volatile private var endSilenceRemainingFrames = 0L
    @Volatile private var endSilenceAppliedForSession = false
    @Volatile private var preloadBlockedUntil = 0L
    @Volatile private var fadeInOnStartMs = 0

    // Pause when headphones are unplugged or the Bluetooth route drops, instead of
    // blasting the track out of the phone speaker.
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && isPlaying()) pausePlayback()
        }
    }
    private var noisyReceiverRegistered = false

    private val sleepHandler = Handler(Looper.getMainLooper())
    @Volatile private var sleepDeadlineMs = 0L
    private val sleepRunnable = Runnable {
        sleepDeadlineMs = 0L
        pausePlayback()
    }

    /** Pauses playback after [minutes]; 0 cancels the timer. */
    fun setSleepTimer(minutes: Int) {
        sleepHandler.removeCallbacks(sleepRunnable)
        if (minutes <= 0) {
            sleepDeadlineMs = 0L
            return
        }
        val delayMs = minutes * 60_000L
        sleepDeadlineMs = SystemClock.elapsedRealtime() + delayMs
        sleepHandler.postDelayed(sleepRunnable, delayMs)
    }

    fun sleepTimerRemainingMs(): Long =
        if (sleepDeadlineMs == 0L) 0L else (sleepDeadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        transitionSettings = PowerampTransitionSettings.read(this)
        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Auralis:Playback")
        createNotificationChannel()
        createMediaSession()
        val noisyFilter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisyReceiver, noisyFilter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(noisyReceiver, noisyFilter)
        noisyReceiverRegistered = true
        startForeground(NOTIFICATION_ID, buildNotification("Auralis is ready"))
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> resumePlayback()
            ACTION_PAUSE -> pausePlayback()
            ACTION_NEXT -> skipToNext()
            ACTION_PREVIOUS -> skipToPrevious()
        }
        return START_NOT_STICKY
    }

    fun load(uri: Uri) {
        val displayName = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: (uri.lastPathSegment ?: "Selected audio")
        synchronized(queueLock) {
            queue.clear()
            queue.add(QueueEntry(uri, displayName, "Unknown artist", "Unknown album", 0L))
            queueIndex = 0
        }
        startQueueEntry(0, 0L)
    }

    internal fun playQueue(tracks: List<MusicTrack>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        synchronized(queueLock) {
            queue.clear()
            queue.addAll(tracks.map { QueueEntry(it.uri, it.title, it.artist, it.album, it.durationMs) })
            queueIndex = startIndex.coerceIn(0, queue.lastIndex)
        }
        startQueueEntry(queueIndex, 0L)
    }

    internal fun addToQueue(track: MusicTrack, playNext: Boolean = false) {
        val shouldStart = synchronized(queueLock) {
            val wasEmpty = queue.isEmpty()
            val item = QueueEntry(track.uri, track.title, track.artist, track.album, track.durationMs)
            val insertAt = if (playNext && queueIndex in queue.indices) queueIndex + 1 else queue.size
            queue.add(insertAt.coerceIn(0, queue.size), item)
            if (queueIndex < 0) queueIndex = 0
            else if (insertAt <= queueIndex) queueIndex++
            wasEmpty
        }
        if (shouldStart) startQueueEntry(0, 0L)
        else { invalidatePreload(); updateNotification("Added to queue"); persistSession() }
    }

    private val persistExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Auralis-persist").apply { isDaemon = true }
    }

    /** Saves the queue and position so the app can resume where the listener left off. */
    private fun persistSession(positionMs: Long = playbackPositionMs()) {
        val (items, index) = synchronized(queueLock) { queue.toList() to queueIndex }
        val shuffle = shuffleEnabled
        val repeat = repeatMode
        runCatching {
            persistExecutor.execute {
                runCatching {
                    val prefs = getSharedPreferences("auralis.session", Context.MODE_PRIVATE)
                    val current = items.getOrNull(index)
                    if (current == null || current.uri.authority != "media") {
                        prefs.edit().clear().apply()
                        return@runCatching
                    }
                    val media = items.filter { it.uri.authority == "media" }
                    val mediaIndex = items.subList(0, index).count { it.uri.authority == "media" }
                    val from = (mediaIndex - 20).coerceAtLeast(0)
                    val window = media.drop(from).take(1500)
                    val array = org.json.JSONArray()
                    window.forEach { e ->
                        array.put(org.json.JSONObject().put("uri", e.uri.toString()).put("title", e.title)
                            .put("artist", e.artist).put("album", e.album).put("duration", e.durationMs))
                    }
                    prefs.edit().putString("queue", array.toString()).putInt("index", mediaIndex - from)
                        .putLong("position", positionMs).putBoolean("shuffle", shuffle).putInt("repeat", repeat).apply()
                }
            }
        }
    }

    /** Restores the saved queue paused at the saved position; returns false if there is nothing to restore. */
    fun restoreSession(): Boolean {
        if (synchronized(queueLock) { queue.isNotEmpty() }) return false
        val prefs = getSharedPreferences("auralis.session", Context.MODE_PRIVATE)
        val raw = prefs.getString("queue", null) ?: return false
        val entries = runCatching {
            val array = org.json.JSONArray(raw)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                QueueEntry(Uri.parse(o.getString("uri")), o.getString("title"), o.getString("artist"),
                    o.getString("album"), o.optLong("duration", 0L))
            }
        }.getOrNull()
        if (entries.isNullOrEmpty()) return false
        val index = prefs.getInt("index", 0).coerceIn(0, entries.lastIndex)
        synchronized(queueLock) {
            queue.clear()
            queue.addAll(entries)
            queueIndex = index
        }
        shuffleEnabled = prefs.getBoolean("shuffle", false)
        repeatMode = prefs.getInt("repeat", REPEAT_OFF).coerceIn(REPEAT_OFF, REPEAT_ONE)
        startQueueEntry(index, prefs.getLong("position", 0L), false)
        return true
    }

    fun removeFromQueue(index: Int) {
        var restartAt = -1
        var emptied = false
        synchronized(queueLock) {
            if (index !in queue.indices) return
            queue.removeAt(index)
            when {
                queue.isEmpty() -> { queueIndex = -1; emptied = true }
                index < queueIndex -> queueIndex--
                index == queueIndex -> { queueIndex = index.coerceAtMost(queue.lastIndex); restartAt = queueIndex }
                else -> Unit
            }
        }
        if (emptied) {
            stopCurrent()
            updateNotification("Queue cleared")
        } else if (restartAt >= 0) {
            startQueueEntry(restartAt, 0L, isPlaying())
        } else {
            invalidatePreload()
        }
        persistSession()
    }

    fun moveQueueItem(from: Int, to: Int) {
        synchronized(queueLock) {
            if (from !in queue.indices || to !in queue.indices || from == to) return
            val item = queue.removeAt(from)
            queue.add(to, item)
            queueIndex = when {
                queueIndex == from -> to
                from < queueIndex && to >= queueIndex -> queueIndex - 1
                from > queueIndex && to <= queueIndex -> queueIndex + 1
                else -> queueIndex
            }
        }
        invalidatePreload()
        persistSession()
    }

    fun pausePlayback() {
        val track = outputTrack ?: return
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) return
        if (transitionSettings.fadePlayPause && transitionSettings.shortFadeMs > 0) {
            requestOutputFade(1.0f, 0.0f, transitionSettings.shortFadeMs, pauseAfter = true)
        } else {
            finishPause(track)
        }
    }

    fun resumePlayback() {
        val active = session
        if (active == null) {
            val index = synchronized(queueLock) { queueIndex }
            if (index >= 0) startQueueEntry(index, 0L)
            return
        }
        val track = outputTrack ?: active.audioTrack ?: return
        if (!requestAudioFocus()) return
        acquireWakeLock()
        pausedForFocus = false
        pauseAfterFade = false
        stopAfterFade = false
        track.play()
        if (transitionSettings.fadePlayPause && transitionSettings.shortFadeMs > 0) {
            requestOutputFade(0.0f, 1.0f, transitionSettings.shortFadeMs)
        } else {
            track.setVolume(1.0f)
        }
        updatePlaybackState(PlaybackState.STATE_PLAYING)
        updateNotification("Playing")
    }

    fun stopPlayback() {
        val track = outputTrack
        if (track != null && track.playState == AudioTrack.PLAYSTATE_PLAYING &&
            transitionSettings.fadePlayPause && transitionSettings.shortFadeMs > 0) {
            requestOutputFade(1.0f, 0.0f, transitionSettings.shortFadeMs, stopAfter = true)
        } else {
            stopCurrent()
            updatePlaybackState(PlaybackState.STATE_STOPPED)
            updateNotification("Stopped")
        }
    }

    fun skipToNext() = advanceQueue(userInitiated = true)

    fun skipToPrevious() {
        if (playbackPositionMs() > 3000L) {
            val wasPlaying = isPlaying()
            val index = synchronized(queueLock) { queueIndex }
            if (index >= 0) requestManualQueueChange(index, 0, wasPlaying)
            return
        }
        val target = synchronized(queueLock) {
            if (queue.isEmpty()) -1 else when {
                queueIndex > 0 -> queueIndex - 1
                repeatMode == REPEAT_ALL -> queue.lastIndex
                else -> -1
            }
        }
        if (target >= 0) requestManualQueueChange(target, transitionSettings.manualCrossfadeMs(), isPlaying())
    }

    fun seekTo(positionMs: Long) {
        val index = synchronized(queueLock) { queueIndex }
        if (index < 0) return
        val duration = session?.durationMs ?: 0L
        val target = positionMs.coerceIn(0L, if (duration > 0L) duration else Long.MAX_VALUE)
        val wasPlaying = isPlaying()
        if (wasPlaying && transitionSettings.fadeSeek && transitionSettings.seekFadeMs > 0) {
            pendingSeekAfterFade = target to true
            fadeInOnStartMs = transitionSettings.seekFadeMs
            requestOutputFade(1.0f, 0.0f, transitionSettings.seekFadeMs)
        } else {
            startQueueEntry(index, target, wasPlaying)
        }
    }

    fun setShuffle(enabled: Boolean) { shuffleEnabled = enabled; invalidatePreload() }
    fun isShuffleEnabled(): Boolean = shuffleEnabled
    fun setRepeatMode(mode: Int) { repeatMode = mode.coerceIn(REPEAT_OFF, REPEAT_ONE); invalidatePreload() }
    fun getRepeatMode(): Int = repeatMode
    fun queueSize(): Int = synchronized(queueLock) { queue.size }
    fun currentQueueIndex(): Int = queueIndex
    internal fun currentEntry(): QueueEntry? = synchronized(queueLock) { queue.getOrNull(queueIndex) }
    internal fun queueSnapshot(): List<QueueEntry> = synchronized(queueLock) { queue.toList() }
    fun skipToQueueIndex(index: Int) {
        val autoPlay = isPlaying()
        val valid = synchronized(queueLock) { index in queue.indices }
        if (valid) requestManualQueueChange(index, transitionSettings.manualCrossfadeMs(), autoPlay)
    }

    fun clearQueue() {
        synchronized(queueLock) { queue.clear(); queueIndex = -1 }
        stopCurrent()
        persistSession()
        updateNotification("Queue cleared")
    }
    fun isPlaying(): Boolean = outputTrack?.playState == AudioTrack.PLAYSTATE_PLAYING
    fun playbackPositionMs(): Long {
        val s = session ?: return 0L
        val track = outputTrack ?: return s.startPositionMs
        val frames = (track.playbackHeadPosition.toLong() and 0xffffffffL)
        val relative = (frames - s.outputHeadStartFrames).coerceAtLeast(0L)
        return s.startPositionMs + relative * 1000L / s.sampleRate.coerceAtLeast(1)
    }
    fun playbackDurationMs(): Long = session?.durationMs ?: 0L

    fun getTransitionSettings(): PowerampTransitionSettings = transitionSettings

    fun setTransitionSettings(settings: PowerampTransitionSettings) {
        val normalized = settings.normalized()
        transitionSettings = normalized
        normalized.write(this)
    }

    fun setStereoWide(enabled: Boolean, percent: Int) {
        synchronized(configLock) {
            stereoEnabled = enabled
            stereoPercent = percent.coerceIn(0, 100).toDouble()
            session?.engine?.setStereoWide(stereoEnabled, stereoPercent)
        }
    }

    fun setBalance(enabled: Boolean, normalized: Double) {
        synchronized(configLock) {
            balanceEnabled = enabled
            balanceNormalized = normalized.coerceIn(-1.0, 1.0)
            session?.engine?.setBalance(balanceEnabled, balanceNormalized)
        }
    }

    fun setMono(enabled: Boolean) {
        synchronized(configLock) {
            monoEnabled = enabled
            session?.engine?.setMono(enabled)
        }
    }

    // path 0 = hard 1.0 ceiling; path 1 = gain-derived protection ceiling.
    fun setLimiter(enabled: Boolean, path: Int) {
        synchronized(configLock) {
            limiterEnabled = enabled
            limiterPath = path.coerceIn(0, 1)
            session?.engine?.setLimiter(limiterEnabled, limiterPath)
        }
    }

    fun setBassBoost(enabled: Boolean, amount: Double) {
        synchronized(configLock) {
            bassBoostEnabled = enabled
            bassBoostAmount = amount.coerceIn(0.0, 1.0)
            session?.engine?.setBassBoost(bassBoostEnabled, bassBoostAmount)
        }
    }

    fun setReverbEnabled(enabled: Boolean) {
        synchronized(configLock) {
            reverbEnabled = enabled
            session?.engine?.setReverbEnabled(enabled)
        }
    }

    fun setReverbParameter(index: Int, value: Double) {
        if (index !in 0..6) return
        synchronized(configLock) {
            val normalized = value.coerceIn(0.0, 1.0)
            reverbParameters[index] = normalized
            session?.engine?.setReverbParameter(index, normalized)
        }
    }

    fun setReplayGain(type: Int, source: Int, defaultGainMb: Int) {
        synchronized(configLock) {
            replayGainType = type.coerceIn(0, 2)
            replayGainSource = source.coerceIn(0, 1)
            replayGainDefaultMb = defaultGainMb.coerceIn(-3000, 3000)
            session?.engine?.setReplayGain(replayGainType, replayGainSource, replayGainDefaultMb, 0)
        }
    }

    /** Independent master preamp. It applies even when ReplayGain mode is None. */
    fun setMasterGainDb(db: Double) {
        if (!db.isFinite()) return
        synchronized(configLock) {
            masterGainDb = db.coerceIn(-30.0, 30.0)
            session?.engine?.setMasterGainDb(masterGainDb)
        }
    }

    fun setEqBand(index: Int, enabled: Boolean, type: Int, frequencyHz: Int,
                  q: Double, gainDb: Double, channel: Int = 0): Boolean {
        synchronized(configLock) {
            val setting = EqSetting(index, enabled, type, frequencyHz, q, gainDb, channel)
            eqSettings[index] = setting
            return session?.engine?.setEqBand(index, enabled, type, frequencyHz, q, gainDb, channel) ?: true
        }
    }

    private fun prepareAndStart(s: PlaybackSession, forPreload: Boolean = false) {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        try {
            extractor = MediaExtractor()
            s.extractor = extractor
            extractor.setDataSource(this, s.uri, null)
            var selectedTrack = -1
            var sourceFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    selectedTrack = i
                    sourceFormat = format
                    break
                }
            }
            val format = sourceFormat ?: throw IOException("The selected file has no supported audio track")
            extractor.selectTrack(selectedTrack)
            if (s.startPositionMs > 0L) extractor.seekTo(s.startPositionMs * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: throw IOException("Missing audio MIME type")
            val sourceRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val sampleRate = if (forPreload && s.targetRate > 0) s.targetRate else sourceRate
            s.sourceRate = sourceRate
            s.sampleRate = sampleRate
            s.durationMs = (if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000L else s.entry.durationMs).coerceAtLeast(0L)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            if (channels > 2) throw IOException("This stereo DSP path accepts mono or stereo input only")

            codec = MediaCodec.createDecoderByType(mime)
            s.codec = codec
            codec.configure(format, null, null, 0)
            codec.start()
            val replayGainTags = ReplayGainReader.read(this, s.uri) ?: ReplayGainTags()
            val nativeEngine = NativeAudioEngine(sampleRate)
            s.engine = nativeEngine
            synchronized(configLock) {
                nativeEngine.setStereoWide(stereoEnabled, stereoPercent)
                nativeEngine.setBalance(balanceEnabled, balanceNormalized)
                nativeEngine.setMono(monoEnabled)
                nativeEngine.setLimiter(limiterEnabled, limiterPath)
                nativeEngine.setBassBoost(bassBoostEnabled, bassBoostAmount)
                reverbParameters.forEachIndexed { index, value -> nativeEngine.setReverbParameter(index, value) }
                nativeEngine.setReverbEnabled(reverbEnabled)
                nativeEngine.setReplayGain(replayGainType, replayGainSource, replayGainDefaultMb, 0)
                nativeEngine.setMasterGainDb(masterGainDb)
                nativeEngine.setReplayGainMetadata(replayGainTags)
                eqSettings.values.forEach { e ->
                    nativeEngine.setEqBand(e.index, e.enabled, e.type, e.frequencyHz, e.q, e.gainDb, e.channel)
                }
            }

            val track = if (forPreload) null else {
                val minBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
                val bufferBytes = max(if (minBytes > 0) minBytes else 16384, sampleRate * 2 * 4 / 4)
                AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                    .setAudioFormat(AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build())
                    .setBufferSizeInBytes(bufferBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            }
            s.audioTrack = track
            synchronized(sessionLock) {
                if (forPreload) {
                    if (preloadedSession !== s) throw IOException("Preload request was superseded")
                } else if (session !== s) {
                    throw IOException("Playback request was superseded")
                }
                s.running.set(true)
                if (!forPreload) {
                    outputTrack = track
                    s.outputHeadStartFrames = 0L
                    if (s.autoPlay && requestAudioFocus()) {
                        acquireWakeLock()
                        track!!.play()
                    } else {
                        pausedForFocus = s.autoPlay
                        if (s.autoPlay) acquireWakeLock()
                    }
                }
                s.dspThread = Thread({ runDsp(s) }, "Auralis-DSP")
                s.decoderThread = Thread({ runDecoder(s, channels) }, "Auralis-decoder")
                s.dspThread?.start()
                s.decoderThread?.start()
                if (!forPreload) {
                    val generation = ++outputGeneration
                    val output = track!!
                    val pump = Thread({ runOutput(generation, output) }, "Auralis-output")
                    outputPumpThread = pump
                    s.outputThread = pump
                    val fadeIn = fadeInOnStartMs
                    fadeInOnStartMs = 0
                    if (fadeIn > 0 && s.autoPlay) requestOutputFade(0.0f, 1.0f, fadeIn)
                    pump.start()
                    endSilenceAppliedForSession = false
                    endSilenceRemainingFrames = 0L
                    updateNowPlaying(s.entry)
                    updatePlaybackState(if (output.playState == AudioTrack.PLAYSTATE_PLAYING) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED)
                    updateNotification("Playing audio")
                }
            }
        } catch (t: Throwable) {
            s.running.set(false)
            try { s.audioTrack?.pause(); s.audioTrack?.stop() } catch (_: Throwable) { }
            val failedCurrent = synchronized(sessionLock) {
                if (session === s) { session = null; true } else false
            }
            val failedPreload = synchronized(sessionLock) {
                if (preloadedSession === s) { preloadedSession = null; preloadedQueueIndex = -1; true } else false
            }
            if (failedCurrent) {
                outputGeneration++
                outputTrack = null
                releaseWakeLock()
                abandonAudioFocus()
                updatePlaybackState(PlaybackState.STATE_STOPPED)
                updateNotification("Unable to play this audio")
            }
            if (failedPreload) preloadBlockedUntil = SystemClock.elapsedRealtime() + 5_000L
            if (failedPreload && pendingManualTarget >= 0) {
                val target = pendingManualTarget
                val shouldPlay = isPlaying()
                pendingManualTarget = -1
                Handler(Looper.getMainLooper()).post { startQueueEntry(target, 0L, shouldPlay) }
            }
            scheduleSessionCleanup(s)
        }
    }

    private fun runDecoder(s: PlaybackSession, sourceChannels: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val decoder = s.codec ?: return
        val extractor = s.extractor ?: return
        val staging = ByteBuffer.allocateDirect(1024 * 2 * 4).order(ByteOrder.LITTLE_ENDIAN)
        var stagedFrames = 0
        var inputEos = false
        var outputEos = false
        var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
        var actualChannels = sourceChannels.coerceAtLeast(1)
        val info = MediaCodec.BufferInfo()
        var decoderRate = if (s.sourceRate > 0) s.sourceRate else s.sampleRate
        var resampler: StreamResampler? =
            if (decoderRate != s.sampleRate) StreamResampler(decoderRate, s.sampleRate) else null

        fun publishStaging() {
            if (stagedFrames == 0) return
            staging.position(0)
            staging.limit(stagedFrames * 2 * 4)
            val engine = s.engine ?: return
            while (s.running.get() && !engine.enqueueInput(staging, stagedFrames)) Thread.yield()
            staging.clear()
            stagedFrames = 0
        }

        try {
            while (s.running.get() && !outputEos) {
                if (!inputEos) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputIndex)
                            ?: throw IOException("Decoder returned an empty input buffer")
                        inputBuffer.clear()
                        val size = extractor.readSampleData(inputBuffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEos = true
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = decoder.dequeueOutputBuffer(info, 10_000)
                when {
                    outputIndex >= 0 -> {
                        val output = decoder.getOutputBuffer(outputIndex)
                        if (output != null && info.size > 0) {
                            val outputFormat = decoder.outputFormat
                            pcmEncoding = outputFormat.integerOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                            actualChannels = outputFormat.integerOr(MediaFormat.KEY_CHANNEL_COUNT, sourceChannels).coerceAtLeast(1)
                            if (actualChannels !in 1..2) throw IOException("Decoder output is not mono or stereo")
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            convertPcmToStereo(output.slice().order(ByteOrder.LITTLE_ENDIAN), pcmEncoding, actualChannels,
                                staging, { stagedFrames = it }, { publishStaging() }, s, resampler)
                        }
                        decoder.releaseOutputBuffer(outputIndex, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputEos = true
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val changed = decoder.outputFormat
                        val changedRate = changed.integerOr(MediaFormat.KEY_SAMPLE_RATE, decoderRate)
                        if (changedRate != decoderRate) {
                            decoderRate = changedRate
                            resampler = if (decoderRate != s.sampleRate) StreamResampler(decoderRate, s.sampleRate) else null
                        }
                        pcmEncoding = changed.integerOr(MediaFormat.KEY_PCM_ENCODING, pcmEncoding)
                        actualChannels = changed.integerOr(MediaFormat.KEY_CHANNEL_COUNT, actualChannels).coerceAtLeast(1)
                        if (actualChannels !in 1..2) throw IOException("Decoder output is not mono or stereo")
                    }
                }
            }
            publishStaging()
        } catch (_: Throwable) {
            // Stop requests and codec shutdown can interrupt dequeue operations.
            if (s.running.get()) {
                s.running.set(false)
                if (session === s) updateNotification("Unable to decode this audio")
            }
        } finally {
            s.decoderDone.set(true)
            try { decoder.stop() } catch (_: Throwable) { }
            try { decoder.release() } catch (_: Throwable) { }
            try { extractor.release() } catch (_: Throwable) { }
        }
    }

    private fun convertPcmToStereo(
        pcm: ByteBuffer,
        encoding: Int,
        channels: Int,
        staging: ByteBuffer,
        setFrameCount: (Int) -> Unit,
        flush: () -> Unit,
        s: PlaybackSession,
        resampler: StreamResampler?
    ) {
        if (channels !in 1..2) throw IOException("Auralis DSP input must be mono or stereo")
        val bytesPerSample = when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            AudioFormat.ENCODING_PCM_8BIT -> 1
            else -> 2
        }
        val bytesPerFrame = bytesPerSample * channels
        val frameCount = pcm.remaining() / bytesPerFrame
        var framesInChunk = 0
        val put: (Float, Float) -> Unit = { l, r ->
            staging.putFloat(l)
            staging.putFloat(r)
            framesInChunk++
            if (framesInChunk == 1024) {
                setFrameCount(1024)
                flush()
                framesInChunk = 0
            }
        }
        repeat(frameCount) {
            val left = readPcmSample(pcm, encoding)
            val right = if (channels == 2) readPcmSample(pcm, encoding) else left
            if (resampler == null) put(left, right) else resampler.push(left, right, put)
        }
        setFrameCount(framesInChunk)
        if (framesInChunk > 0 && s.running.get()) {
            // The converter is called once per output buffer. Flush the partial block now.
            flush()
        }
    }

    private fun readPcmSample(buffer: ByteBuffer, encoding: Int): Float = when (encoding) {
        AudioFormat.ENCODING_PCM_FLOAT -> buffer.float
        AudioFormat.ENCODING_PCM_8BIT -> ((buffer.get().toInt() and 0xFF) - 128) / 128.0f
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
            val b0 = buffer.get().toInt() and 0xFF
            val b1 = buffer.get().toInt() and 0xFF
            val b2 = buffer.get().toInt()
            val value = (b2 shl 16) or (b1 shl 8) or b0
            value / 8388608.0f
        }
        AudioFormat.ENCODING_PCM_32BIT -> buffer.int / 2147483648.0f
        else -> buffer.short / 32768.0f
    }

    private fun runDsp(s: PlaybackSession) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val engine = s.engine ?: return
        while (s.running.get()) {
            val result = engine.processOne()
            if (result > 0) continue
            if (result == 0 && s.decoderDone.get()) break
            try { Thread.sleep(1) } catch (_: InterruptedException) { break }
        }
        s.dspDone.set(true)
    }

    private fun runOutput(generation: Long, track: AudioTrack) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        var currentBuffer = ByteBuffer.allocateDirect(1024 * 2 * 4).order(ByteOrder.LITTLE_ENDIAN)
        var incomingBuffer = ByteBuffer.allocateDirect(1024 * 2 * 4).order(ByteOrder.LITTLE_ENDIAN)
        val mixedBuffer = ByteBuffer.allocateDirect(1024 * 2 * 4).order(ByteOrder.LITTLE_ENDIAN)
        val fadeBuffer = ByteBuffer.allocateDirect(1024 * 2 * 4).order(ByteOrder.LITTLE_ENDIAN)
        val zeroBuffer = ByteBuffer.allocateDirect(1024 * 2 * 4).order(ByteOrder.LITTLE_ENDIAN)
        var currentFrames = 0
        var currentOffset = 0
        var incomingFrames = 0
        var incomingOffset = 0
        var outputBytesWritten = 0L
        var fadeDrainTargetFrames = -1L
        var fadeStartFrame = -1L

        fun takeStartFrame(): Long {
            val f = if (fadeStartFrame >= 0L) fadeStartFrame else outputBytesWritten / 8L
            fadeStartFrame = -1L
            return f
        }

        fun fill(engine: NativeAudioEngine?, buffer: ByteBuffer): Int {
            if (engine == null) return 0
            buffer.clear()
            return engine.dequeueOutput(buffer, 1024)
        }

        fun slice(buffer: ByteBuffer, frameOffset: Int, frames: Int): ByteBuffer {
            val view = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            view.position(frameOffset * 8)
            view.limit((frameOffset + frames) * 8)
            return view.slice().order(ByteOrder.LITTLE_ENDIAN)
        }

        fun writeBlock(source: ByteBuffer, frames: Int) {
            val fade = outputFadeMixer
            val block = if (fade != null && !fade.singleFadeComplete) {
                fadeBuffer.clear()
                fade.processSingle(source, fadeBuffer, frames)
                fadeBuffer.position(0)
                fadeBuffer.limit(frames * 8)
                fadeBuffer
            } else {
                source.position(0)
                source.limit(frames * 8)
                source
            }
            var bytesRemaining = frames * 8
            while (bytesRemaining > 0 && outputGeneration == generation) {
                val written = track.write(block, bytesRemaining, AudioTrack.WRITE_NON_BLOCKING)
                if (written < 0) break
                if (written == 0) {
                    try { Thread.sleep(1) } catch (_: InterruptedException) { return }
                } else {
                    outputBytesWritten += written.toLong()
                    bytesRemaining -= written
                }
            }
            val activeFade = outputFadeMixer
            if (activeFade != null && activeFade.singleFadeComplete) {
                outputFadeMixer = null
                activeFade.close()
                if (pauseAfterFade || stopAfterFade || pendingSeekAfterFade != null) {
                    // The fade block is queued in AudioTrack. Defer pause/stop/seek
                    // until playbackHeadPosition has actually passed that block.
                    fadeDrainTargetFrames = outputBytesWritten / 8L
                }
            }
        }

        fun startFadeIfReady(active: PlaybackSession, incoming: PlaybackSession) {
            if (transitionMixer == null) {
                val ramp = NativeTransitionMixer(active.sampleRate)
                if (ramp.startCrossfade(transitionDurationMs, 1024)) {
                    transitionMixer = ramp
                } else {
                    ramp.close()
                    transitionMixer = null
                }
            }
        }

        while (outputGeneration == generation) {
            if (fadeDrainTargetFrames >= 0L) {
                while (outputGeneration == generation) {
                    val head = track.playbackHeadPosition.toLong() and 0xffffffffL
                    if (head >= fadeDrainTargetFrames) break
                    try { Thread.sleep(1) } catch (_: InterruptedException) { break }
                }
                if (outputGeneration != generation) break
                fadeDrainTargetFrames = -1L
                when {
                    pauseAfterFade -> {
                        pauseAfterFade = false
                        finishPause(track)
                    }
                    stopAfterFade -> {
                        stopAfterFade = false
                        Handler(Looper.getMainLooper()).post {
                            stopCurrent()
                            updatePlaybackState(PlaybackState.STATE_STOPPED)
                            updateNotification("Stopped")
                        }
                        break
                    }
                    pendingSeekAfterFade != null -> {
                        val (position, wasPlaying) = pendingSeekAfterFade!!
                        pendingSeekAfterFade = null
                        val index = synchronized(queueLock) { queueIndex }
                        Handler(Looper.getMainLooper()).post {
                            if (index >= 0) startQueueEntry(index, position, wasPlaying)
                        }
                        break
                    }
                }
            }
            if (track.playState == AudioTrack.PLAYSTATE_PAUSED) {
                try { Thread.sleep(10) } catch (_: InterruptedException) { break }
                continue
            }
            val active = session ?: break
            val activeEngine = active.engine
            maybePreloadNext(active)

            val pendingTarget = pendingManualTarget
            val prepared = preloadedSession
            if (pendingTarget >= 0 && prepared != null && prepared.running.get() && prepared.engine != null) {
                if (prepared.sampleRate == active.sampleRate) {
                    transitionIncoming = prepared
                    transitionDurationMs = pendingManualDurationMs
                    pendingManualTarget = -1
                    pendingManualDurationMs = 0
                } else {
                    val target = pendingTarget
                    val wasPlaying = track.playState == AudioTrack.PLAYSTATE_PLAYING
                    pendingManualTarget = -1
                    pendingManualDurationMs = 0
                    Handler(Looper.getMainLooper()).post { startQueueEntry(target, 0L, wasPlaying) }
                    break
                }
            }

            maybeBeginAutomaticCrossfade(active)
            val incoming = transitionIncoming

            if (currentFrames == 0) {
                currentFrames = fill(activeEngine, currentBuffer)
                currentOffset = 0
            }

            if (incoming != null) {
                if (incoming.sampleRate != active.sampleRate) {
                    transitionIncoming = null
                    transitionMixer?.close()
                    transitionMixer = null
                } else {
                    if (incomingFrames == 0) {
                        incomingFrames = fill(incoming.engine, incomingBuffer)
                        incomingOffset = 0
                    }
                    if (incomingFrames == 0 && transitionMixer?.crossfadeComplete == false &&
                        !(incoming.decoderDone.get() && incoming.dspDone.get())) {
                        // Incoming slot is briefly behind: wait instead of letting the
                        // outgoing song jump back to full gain mid-fade.
                        try { Thread.sleep(1) } catch (_: InterruptedException) { break }
                        continue
                    }
                    val outgoingEnded = currentFrames == 0 && active.decoderDone.get() && active.dspDone.get()
                    if (incomingFrames > 0 && (currentFrames > 0 || outgoingEnded)) {
                        if (fadeStartFrame < 0L) fadeStartFrame = outputBytesWritten / 8L
                        startFadeIfReady(active, incoming)
                        val frames = if (currentFrames > 0) minOf(currentFrames - currentOffset, incomingFrames - incomingOffset)
                            else incomingFrames - incomingOffset
                        val outSlice = if (currentFrames > 0) slice(currentBuffer, currentOffset, frames) else null
                        val inSlice = slice(incomingBuffer, incomingOffset, frames)
                        mixedBuffer.clear()
                        mixedBuffer.limit(frames * 8)
                        transitionMixer?.processCrossfade(outSlice, inSlice, mixedBuffer, frames)
                        if (transitionMixer == null) {
                            mixedBuffer.position(0)
                            mixedBuffer.put(inSlice)
                            mixedBuffer.flip()
                        }
                        mixedBuffer.position(0)
                        writeBlock(mixedBuffer, frames)
                        if (currentFrames > 0) {
                            active.framesConsumed += frames
                            currentOffset += frames
                        }
                        incoming.framesConsumed += frames
                        incomingOffset += frames
                        if (currentFrames > 0 && currentOffset >= currentFrames) { currentFrames = 0; currentOffset = 0 }
                        if (incomingOffset >= incomingFrames) { incomingFrames = 0; incomingOffset = 0 }
                        if (transitionMixer?.crossfadeComplete == true) {
                            val old = active
                            finishTransition(old, incoming, track, takeStartFrame())
                            // Keep any unread incoming frames as the new current stream.
                            val oldCurrentBuffer = currentBuffer
                            currentBuffer = incomingBuffer
                            incomingBuffer = oldCurrentBuffer
                            currentFrames = incomingFrames
                            currentOffset = incomingOffset
                            incomingFrames = 0
                            incomingOffset = 0
                        }
                        continue
                    }
                }
            }

            if (currentFrames > 0) {
                val frames = currentFrames - currentOffset
                val block = slice(currentBuffer, currentOffset, frames)
                writeBlock(block, frames)
                active.framesConsumed += frames
                currentFrames = 0
                currentOffset = 0
                continue
            }

            if (active.decoderDone.get() && active.dspDone.get()) {
                val fadingIn = transitionIncoming
                if (fadingIn != null && transitionMixer?.crossfadeComplete == false &&
                    !(fadingIn.decoderDone.get() && fadingIn.dspDone.get())) {
                    try { Thread.sleep(1) } catch (_: InterruptedException) { break }
                    continue
                }
                if (!endSilenceAppliedForSession && transitionIncoming == null &&
                    transitionSettings.trackEndSilenceMs > 0) {
                    endSilenceAppliedForSession = true
                    endSilenceRemainingFrames = active.sampleRate.toLong() * transitionSettings.trackEndSilenceMs / 1000L
                }
                if (endSilenceRemainingFrames > 0L && transitionIncoming == null) {
                    val frames = minOf(1024L, endSilenceRemainingFrames).toInt()
                    zeroBuffer.clear()
                    zeroBuffer.limit(frames * 8)
                    for (i in 0 until frames * 2) zeroBuffer.putFloat(0.0f)
                    zeroBuffer.flip()
                    writeBlock(zeroBuffer, frames)
                    endSilenceRemainingFrames -= frames
                    continue
                }

                val readyNext = preloadedSession
                if (transitionIncoming != null && readyNext === transitionIncoming) {
                    // The outgoing slot ended before the fade could complete. Poweramp's
                    // native path explicitly has a FADE_OUT_CRITICAL no-frames case.
                    finishTransition(active, readyNext!!, track, takeStartFrame())
                    val oldCurrentBuffer = currentBuffer
                    currentBuffer = incomingBuffer
                    incomingBuffer = oldCurrentBuffer
                    currentFrames = incomingFrames
                    currentOffset = incomingOffset
                    incomingFrames = 0
                    incomingOffset = 0
                    continue
                }
                if (readyNext != null && readyNext.running.get() && readyNext.engine != null &&
                    readyNext.sampleRate == active.sampleRate) {
                    finishTransition(active, readyNext!!, track, takeStartFrame())
                    val oldCurrentBuffer = currentBuffer
                    currentBuffer = incomingBuffer
                    incomingBuffer = oldCurrentBuffer
                    currentFrames = incomingFrames
                    currentOffset = incomingOffset
                    incomingFrames = 0
                    incomingOffset = 0
                    continue
                }
                if (readyNext != null && !readyNext.decoderDone.get() && !readyNext.dspDone.get()) {
                    try { Thread.sleep(1) } catch (_: InterruptedException) { break }
                    continue
                }
                val old = active
                outputGeneration++
                try { track.pause(); track.flush(); track.stop() } catch (_: Throwable) { }
                Handler(Looper.getMainLooper()).post {
                    if (session === old) onPlaybackEnded(old)
                }
                break
            }
            try { Thread.sleep(1) } catch (_: InterruptedException) { break }
        }
    }

    private fun stopCurrent() {
        val pair = synchronized(sessionLock) {
            val current = session
            val preload = preloadedSession
            session = null
            preloadedSession = null
            preloadedQueueIndex = -1
            transitionIncoming = null
            pendingManualTarget = -1
            pendingManualDurationMs = 0
            outputGeneration++
            current?.running?.set(false)
            preload?.running?.set(false)
            current to preload
        }
        val old = pair.first
        val preload = pair.second
        transitionMixer?.close()
        transitionMixer = null
        outputFadeMixer?.close()
        outputFadeMixer = null
        pauseAfterFade = false
        stopAfterFade = false
        pendingSeekAfterFade = null
        endSilenceRemainingFrames = 0L
        endSilenceAppliedForSession = false
        outputTrack = null
        outputPumpThread?.interrupt()
        releaseWakeLock()
        abandonAudioFocus()
        updatePlaybackState(PlaybackState.STATE_STOPPED)
        updateNotification("Auralis is ready")
        if (old != null) scheduleSessionCleanup(old)
        if (preload != null && preload !== old) scheduleSessionCleanup(preload)
    }

    /**
     * Decoder shutdown and resource release happen off the main thread. Track changes
     * must never wait synchronously for codec, DSP, or AudioTrack workers to exit.
     */
    private fun scheduleSessionCleanup(old: PlaybackSession) {
        if (!old.cleanupQueued.compareAndSet(false, true)) return
        val cleanup = Runnable {
            val complete = cleanupSession(old)
            old.cleanupQueued.set(false)
            if (!complete) scheduleSessionCleanup(old)
        }
        try {
            cleanupExecutor.execute(cleanup)
        } catch (_: Throwable) {
            Thread({
                while (!cleanupSession(old)) {
                    try { Thread.sleep(100) } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
                old.cleanupQueued.set(false)
            }, "Auralis-cleanup-fallback").apply { isDaemon = true; start() }
        }
    }

    private fun cleanupSession(old: PlaybackSession): Boolean {
        val threads = listOf(old.prepareThread, old.decoderThread, old.dspThread, old.outputThread)
        threads.forEach { thread ->
            if (thread != null && thread !== Thread.currentThread() && thread.isAlive) {
                try { thread.join(1200) } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
        }
        if (threads.any { it != null && it !== Thread.currentThread() && it.isAlive }) return false

        if (old.resourcesReleased.compareAndSet(false, true)) {
            try { old.audioTrack?.pause(); old.audioTrack?.flush(); old.audioTrack?.stop() } catch (_: Throwable) { }
            try { old.audioTrack?.release() } catch (_: Throwable) { }
            try { old.codec?.stop() } catch (_: Throwable) { }
            try { old.codec?.release() } catch (_: Throwable) { }
            try { old.extractor?.release() } catch (_: Throwable) { }
            try { old.engine?.close() } catch (_: Throwable) { }
        }
        return true
    }

    private fun startQueueEntry(index: Int, positionMs: Long, autoPlay: Boolean = true) {
        val entry = synchronized(queueLock) {
            if (index !in queue.indices) return
            queueIndex = index
            queue[index]
        }
        stopCurrent()
        preloadBlockedUntil = 0L
        val next = PlaybackSession(entry, positionMs.coerceAtLeast(0L), autoPlay)
        synchronized(sessionLock) {
            session = next
            val thread = Thread({ prepareAndStart(next, false) }, "Auralis-prepare")
            next.prepareThread = thread
            thread.start()
        }
        updateNowPlaying(entry)
        persistSession(positionMs.coerceAtLeast(0L))
        if (autoPlay) PlayHistory.record(this, entry.uri)
    }

    private fun advanceQueue(userInitiated: Boolean) {
        val target = nextQueueIndex(userInitiated)
        if (target >= 0) {
            if (userInitiated) requestManualQueueChange(target, transitionSettings.manualCrossfadeMs(), isPlaying())
            else startQueueEntry(target, 0L, true)
        } else {
            stopCurrent()
            updateNotification("Queue finished")
        }
    }

    private fun nextQueueIndex(userInitiated: Boolean): Int = synchronized(queueLock) {
        if (queue.isEmpty()) return@synchronized -1
        when {
            !userInitiated && repeatMode == REPEAT_ONE -> queueIndex
            shuffleEnabled && queue.size > 1 -> queue.indices.filter { it != queueIndex }.random()
            queueIndex + 1 < queue.size -> queueIndex + 1
            repeatMode == REPEAT_ALL -> 0
            else -> -1
        }
    }

    private fun requestManualQueueChange(index: Int, crossfadeMs: Int, autoPlay: Boolean) {
        val entry = synchronized(queueLock) { queue.getOrNull(index) } ?: return
        if (crossfadeMs <= 0 || !autoPlay || !isPlaying()) {
            startQueueEntry(index, 0L, autoPlay)
            return
        }
        val existing = preloadedSession
        if (existing != null && existing.entry.uri == entry.uri && existing.running.get()) {
            if (session?.sampleRate == existing.sampleRate) {
                pendingManualTarget = index
                pendingManualDurationMs = crossfadeMs
            } else {
                startQueueEntry(index, 0L, autoPlay)
            }
            return
        }
        cancelPreload()
        pendingManualTarget = index
        pendingManualDurationMs = crossfadeMs
        startPreload(index)
    }

    private fun startPreload(index: Int) {
        val entry = synchronized(queueLock) { queue.getOrNull(index) } ?: return
        synchronized(sessionLock) {
            if (preloadedSession != null || index !in queue.indices) return
            val next = PlaybackSession(entry, 0L, true).also { it.targetRate = session?.sampleRate ?: 0 }
            preloadedSession = next
            preloadedQueueIndex = index
            val thread = Thread({ prepareAndStart(next, true) }, "Auralis-preload")
            next.prepareThread = thread
            thread.start()
        }
    }

    private fun cancelPreload() {
        val stale = synchronized(sessionLock) {
            val old = preloadedSession
            preloadedSession = null
            preloadedQueueIndex = -1
            transitionIncoming = null
            old?.running?.set(false)
            old
        }
        if (stale != null) scheduleSessionCleanup(stale)
    }

    private fun remainingFrames(active: PlaybackSession): Long {
        val rate = active.sampleRate.toLong().coerceAtLeast(1L)
        val total = active.durationMs * rate / 1000L
        val start = active.startPositionMs * rate / 1000L
        return total - start - active.framesConsumed
    }

    private fun invalidatePreload() {
        if (transitionIncoming == null && pendingManualTarget < 0) cancelPreload()
    }

    private fun maybePreloadNext(active: PlaybackSession) {
        if (preloadedSession != null || transitionIncoming != null || pendingManualTarget >= 0) return
        if (SystemClock.elapsedRealtime() < preloadBlockedUntil) return
        val fadeMs = transitionSettings.automaticCrossfadeMs(shuffleEnabled)
        val leadMs = max(max(transitionSettings.gaplessPreloadMs, fadeMs + 1500), DEFAULT_PRELOAD_LEAD_MS)
        if (active.durationMs <= 0L) {
            // Unknown length: preload as soon as the decoder has read the whole file.
            if (!active.decoderDone.get()) return
        } else {
            val remainingMs = remainingFrames(active) * 1000L / active.sampleRate.coerceAtLeast(1)
            if (remainingMs > leadMs) return
        }
        val next = nextQueueIndex(false)
        if (next >= 0) startPreload(next)
    }

    private fun maybeBeginAutomaticCrossfade(active: PlaybackSession) {
        if (transitionIncoming != null || pendingManualTarget >= 0 || transitionMixer != null) return
        val next = preloadedSession ?: return
        if (!next.running.get() || next.engine == null || next.sampleRate != active.sampleRate) return
        val fadeMs = transitionSettings.automaticCrossfadeMs(shuffleEnabled)
        if (fadeMs <= 0 || active.durationMs <= 0L) return
        // Frame-accurate: counted from audio actually handed to the output, not the playback head,
        // so the fade ends exactly when the outgoing song does.
        val fadeFrames = active.sampleRate.toLong() * fadeMs / 1000L
        if (remainingFrames(active) <= fadeFrames) {
            transitionDurationMs = fadeMs
            transitionIncoming = next
        }
    }

    private fun finishTransition(old: PlaybackSession, incoming: PlaybackSession, track: AudioTrack, startFrame: Long) {
        val validIndex = synchronized(queueLock) {
            when {
                preloadedQueueIndex in queue.indices && queue[preloadedQueueIndex].uri == incoming.entry.uri -> preloadedQueueIndex
                else -> queue.indexOfFirst { it.uri == incoming.entry.uri }
            }
        }
        if (validIndex < 0) {
            transitionIncoming = null
            transitionMixer?.close()
            transitionMixer = null
            cancelPreload()
            return
        }
        synchronized(sessionLock) {
            if (session !== old || preloadedSession !== incoming) return
            session = incoming
            queueIndex = validIndex
            preloadedSession = null
            preloadedQueueIndex = -1
            transitionIncoming = null
            incoming.audioTrack = track
            incoming.outputHeadStartFrames = startFrame
            incoming.outputThread = outputPumpThread
            old.audioTrack = null
            old.outputThread = null
        }
        transitionMixer?.close()
        transitionMixer = null
        transitionDurationMs = 0
        endSilenceRemainingFrames = 0L
        endSilenceAppliedForSession = false
        old.running.set(false)
        updateNowPlaying(incoming.entry)
        updatePlaybackState(if (track.playState == AudioTrack.PLAYSTATE_PLAYING) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED)
        updateNotification("Playing audio")
        persistSession(0L)
        PlayHistory.record(this, incoming.entry.uri)
        scheduleSessionCleanup(old)
    }

    private fun requestOutputFade(start: Float, end: Float, durationMs: Int,
                                  pauseAfter: Boolean = false, stopAfter: Boolean = false) {
        val rate = session?.sampleRate ?: outputTrack?.sampleRate ?: return
        outputFadeMixer?.close()
        val fade = NativeTransitionMixer(rate)
        outputFadeMixer = fade
        pauseAfterFade = pauseAfter
        stopAfterFade = stopAfter
        if (!fade.startSingleFade(start, end, durationMs, 1024)) {
            outputFadeMixer = null
            fade.close()
            when {
                pauseAfter -> outputTrack?.let { finishPause(it) }
                stopAfter -> {
                    stopCurrent()
                    updatePlaybackState(PlaybackState.STATE_STOPPED)
                    updateNotification("Stopped")
                }
                pendingSeekAfterFade != null -> {
                    val (position, wasPlaying) = pendingSeekAfterFade!!
                    pendingSeekAfterFade = null
                    val index = synchronized(queueLock) { queueIndex }
                    if (index >= 0) startQueueEntry(index, position, wasPlaying)
                }
            }
        }
    }

    private fun finishPause(track: AudioTrack) {
        try { track.pause() } catch (_: Throwable) { }
        releaseWakeLock()
        persistSession()
        updatePlaybackState(PlaybackState.STATE_PAUSED)
        updateNotification("Paused")
    }

    private fun onPlaybackEnded(s: PlaybackSession) {
        if (session !== s) return
        advanceQueue(userInitiated = false)
    }

    private fun createMediaSession() {
        val launch = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        mediaSession = MediaSession(this, "Auralis").apply {
            setSessionActivity(launch)
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = resumePlayback()
                override fun onPause() = pausePlayback()
                override fun onSkipToNext() = skipToNext()
                override fun onSkipToPrevious() = skipToPrevious()
                override fun onSeekTo(pos: Long) = seekTo(pos)
            })
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            isActive = true
        }
    }

    private fun updateNowPlaying(entry: QueueEntry) {
        mediaSession?.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, entry.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, entry.artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, entry.album)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, session?.durationMs?.takeIf { it > 0L } ?: entry.durationMs)
            .build())
    }

    private fun updatePlaybackState(state: Int) {
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO
        mediaSession?.setPlaybackState(PlaybackState.Builder().setActions(actions)
            .setState(state, playbackPositionMs(), if (state == PlaybackState.STATE_PLAYING) 1.0f else 0.0f)
            .build())
    }

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                outputTrack?.setVolume(1.0f)
                if (pausedForFocus) {
                    if (outputTrack != null) acquireWakeLock()
                    outputTrack?.play()
                    pausedForFocus = false
                    updatePlaybackState(PlaybackState.STATE_PLAYING)
                    updateNotification("Playing")
                }
            }
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                pausedForFocus = change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                outputTrack?.pause()
                releaseWakeLock()
                updatePlaybackState(PlaybackState.STATE_PAUSED)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> outputTrack?.setVolume(0.2f)
        }
    }

    private fun requestAudioFocus(): Boolean {
        val manager = audioManager ?: return true
        val result = if (Build.VERSION.SDK_INT >= 26) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setOnAudioFocusChangeListener(audioFocusListener)
                .setWillPauseWhenDucked(false)
                .build()
            audioFocusRequest = request
            manager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(audioFocusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT >= 26) audioFocusRequest?.let { manager.abandonAudioFocusRequest(it) }
        else {
            @Suppress("DEPRECATION")
            manager.abandonAudioFocus(audioFocusListener)
        }
    }

    private fun acquireWakeLock() {
        val lock = wakeLock ?: return
        if (!lock.isHeld) runCatching { lock.acquire(4 * 60 * 60 * 1000L) }
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        if (lock.isHeld) runCatching { lock.release() }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Auralis playback", NotificationManager.IMPORTANCE_LOW))
        }
    }

    @Volatile private var notificationArtUri: Uri? = null
    @Volatile private var notificationArt: Bitmap? = null
    @Volatile private var lastNotificationText: String = "Auralis is ready"

    private fun loadNotificationArt(uri: Uri) {
        notificationArtUri = uri
        notificationArt = null
        if (Build.VERSION.SDK_INT < 29) return
        runCatching {
            cleanupExecutor.execute {
                val bitmap = runCatching { contentResolver.loadThumbnail(uri, android.util.Size(320, 320), null) }.getOrNull()
                if (bitmap != null && notificationArtUri == uri) {
                    notificationArt = bitmap
                    Handler(Looper.getMainLooper()).post { updateNotification(lastNotificationText) }
                }
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val launch = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val entry = currentEntry()
        fun action(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
            this, requestCode, Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val playing = isPlaying()
        if (entry != null && notificationArtUri != entry.uri) loadNotificationArt(entry.uri)
        val largeArt = if (entry != null && notificationArtUri == entry.uri) notificationArt else null
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_auralis_mark)
            .setColor(0xFF1B3D44.toInt())
            .setLargeIcon(largeArt)
            .setContentTitle(entry?.title ?: "Auralis")
            .setContentText(entry?.artist?.let { "$it · $text" } ?: text)
            .setContentIntent(launch)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(session != null)
            .addAction(Notification.Action.Builder(R.drawable.ic_skip_previous, "Previous", action(ACTION_PREVIOUS, 1)).build())
            .addAction(Notification.Action.Builder(if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                if (playing) "Pause" else "Play", action(if (playing) ACTION_PAUSE else ACTION_PLAY, 2)).build())
            .addAction(Notification.Action.Builder(R.drawable.ic_skip_next, "Next", action(ACTION_NEXT, 3)).build())
            .setStyle(Notification.MediaStyle().setMediaSession(mediaSession?.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private fun updateNotification(text: String) {
        lastNotificationText = text
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        sleepHandler.removeCallbacks(sleepRunnable)
        if (noisyReceiverRegistered) {
            runCatching { unregisterReceiver(noisyReceiver) }
            noisyReceiverRegistered = false
        }
        persistSession()
        stopCurrent()
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        cleanupExecutor.shutdown()
        persistExecutor.shutdown()
        super.onDestroy()
    }

    private data class EqSetting(val index: Int, val enabled: Boolean, val type: Int,
                                 val frequencyHz: Int, val q: Double, val gainDb: Double, val channel: Int)

    internal data class QueueEntry(val uri: Uri, val title: String, val artist: String, val album: String, val durationMs: Long)

    private inner class PlaybackSession(val entry: QueueEntry, val startPositionMs: Long = 0L, val autoPlay: Boolean = true) {
        val uri: Uri = entry.uri
        @Volatile var sampleRate: Int = 48000
        @Volatile var sourceRate: Int = 0
        @Volatile var targetRate: Int = 0
        @Volatile var framesConsumed: Long = 0L
        @Volatile var durationMs: Long = entry.durationMs
        val running = AtomicBoolean(false)
        val decoderDone = AtomicBoolean(false)
        val dspDone = AtomicBoolean(false)
        val cleanupQueued = AtomicBoolean(false)
        val resourcesReleased = AtomicBoolean(false)
        @Volatile var prepareThread: Thread? = null
        @Volatile var extractor: MediaExtractor? = null
        @Volatile var codec: MediaCodec? = null
        @Volatile var engine: NativeAudioEngine? = null
        @Volatile var audioTrack: AudioTrack? = null
        @Volatile var outputHeadStartFrames: Long = 0L
        @Volatile var decoderThread: Thread? = null
        @Volatile var dspThread: Thread? = null
        @Volatile var outputThread: Thread? = null
    }

    private fun MediaFormat.integerOr(key: String, fallback: Int): Int =
        try { if (containsKey(key)) getInteger(key) else fallback } catch (_: Throwable) { fallback }

    companion object {
        const val REPEAT_OFF = 0
        const val REPEAT_ALL = 1
        const val REPEAT_ONE = 2
        private const val ACTION_PLAY = "com.auralis.action.PLAY"
        private const val ACTION_PAUSE = "com.auralis.action.PAUSE"
        private const val ACTION_NEXT = "com.auralis.action.NEXT"
        private const val ACTION_PREVIOUS = "com.auralis.action.PREVIOUS"
        private const val CHANNEL_ID = "auralis-playback"
        private const val NOTIFICATION_ID = 41
        private const val DEFAULT_PRELOAD_LEAD_MS = 3_000
    }
}
