package com.auralis

import android.app.Notification
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
    @Volatile private var reverbEnabled = false
    @Volatile private var replayGainType = 0          // 0 None, 1 Apply, 2 Apply Peak
    @Volatile private var replayGainSource = 0        // 0 Album, 1 Track
    @Volatile private var replayGainDefaultMb = 0
    @Volatile private var masterGainDb = 0.0
    private val reverbParameters = doubleArrayOf(0.55, 0.50, 0.45, 0.15, 1.00, 0.50, 0.50)
    private val eqSettings = ConcurrentHashMap<Int, EqSetting>()

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
        else updateNotification("Added to queue")
    }

    fun pausePlayback() {
        session?.audioTrack?.pause()
        releaseWakeLock()
        updatePlaybackState(PlaybackState.STATE_PAUSED)
        updateNotification("Paused")
    }

    fun resumePlayback() {
        val active = session
        if (active == null) {
            val index = synchronized(queueLock) { queueIndex }
            if (index >= 0) startQueueEntry(index, 0L)
            return
        }
        val track = active.audioTrack ?: return
        if (!requestAudioFocus()) return
        acquireWakeLock()
        track.setVolume(1.0f)
        track.play()
        pausedForFocus = false
        updatePlaybackState(PlaybackState.STATE_PLAYING)
        updateNotification("Playing")
    }

    fun stopPlayback() {
        stopCurrent()
        updatePlaybackState(PlaybackState.STATE_STOPPED)
        updateNotification("Stopped")
    }

    fun skipToNext() = advanceQueue(userInitiated = true)

    fun skipToPrevious() {
        if (playbackPositionMs() > 3000L) {
            val wasPlaying = isPlaying()
            val index = synchronized(queueLock) { queueIndex }
            if (index >= 0) startQueueEntry(index, 0L, wasPlaying)
            return
        }
        val target = synchronized(queueLock) {
            if (queue.isEmpty()) -1 else when {
                queueIndex > 0 -> queueIndex - 1
                repeatMode == REPEAT_ALL -> queue.lastIndex
                else -> -1
            }
        }
        if (target >= 0) startQueueEntry(target, 0L, isPlaying())
    }

    fun seekTo(positionMs: Long) {
        val index = synchronized(queueLock) { queueIndex }
        if (index < 0) return
        val duration = session?.durationMs ?: 0L
        startQueueEntry(index, positionMs.coerceIn(0L, if (duration > 0L) duration else Long.MAX_VALUE), isPlaying())
    }

    fun setShuffle(enabled: Boolean) { shuffleEnabled = enabled }
    fun isShuffleEnabled(): Boolean = shuffleEnabled
    fun setRepeatMode(mode: Int) { repeatMode = mode.coerceIn(REPEAT_OFF, REPEAT_ONE) }
    fun getRepeatMode(): Int = repeatMode
    fun queueSize(): Int = synchronized(queueLock) { queue.size }
    fun currentQueueIndex(): Int = queueIndex
    internal fun currentEntry(): QueueEntry? = synchronized(queueLock) { queue.getOrNull(queueIndex) }
    internal fun queueSnapshot(): List<QueueEntry> = synchronized(queueLock) { queue.toList() }
    fun skipToQueueIndex(index: Int) {
        val autoPlay = isPlaying()
        val valid = synchronized(queueLock) { index in queue.indices }
        if (valid) startQueueEntry(index, 0L, autoPlay)
    }

    fun clearQueue() {
        synchronized(queueLock) { queue.clear(); queueIndex = -1 }
        stopCurrent()
        updateNotification("Queue cleared")
    }
    fun isPlaying(): Boolean = session?.audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING
    fun playbackPositionMs(): Long {
        val s = session ?: return 0L
        val frames = (s.audioTrack?.playbackHeadPosition?.toLong() ?: 0L) and 0xffffffffL
        return s.startPositionMs + frames * 1000L / s.sampleRate.coerceAtLeast(1)
    }
    fun playbackDurationMs(): Long = session?.durationMs ?: 0L

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

    private fun prepareAndStart(s: PlaybackSession) {
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
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            s.sampleRate = sampleRate
            s.durationMs = (if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000L else s.entry.durationMs).coerceAtLeast(0L)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            if (channels > 2) throw IOException("This stereo DSP path accepts mono or stereo input only")

            codec = MediaCodec.createDecoderByType(mime)
            s.codec = codec
            codec.configure(format, null, null, 0)
            codec.start()
            // Tags are read once per track; the engine keeps them, so later preference changes
            // only need the new settings.
            val replayGainTags = ReplayGainReader.read(this, s.uri) ?: ReplayGainTags()
            val nativeEngine = NativeAudioEngine(sampleRate)
            s.engine = nativeEngine
            synchronized(configLock) {
                nativeEngine.setStereoWide(stereoEnabled, stereoPercent)
                nativeEngine.setBalance(balanceEnabled, balanceNormalized)
                nativeEngine.setMono(monoEnabled)
                nativeEngine.setLimiter(limiterEnabled, limiterPath)
                reverbParameters.forEachIndexed { index, value -> nativeEngine.setReverbParameter(index, value) }
                nativeEngine.setReverbEnabled(reverbEnabled)
                nativeEngine.setReplayGain(replayGainType, replayGainSource, replayGainDefaultMb, 0)
                nativeEngine.setMasterGainDb(masterGainDb)
                nativeEngine.setReplayGainMetadata(replayGainTags)
                eqSettings.values.forEach { e ->
                    nativeEngine.setEqBand(e.index, e.enabled, e.type, e.frequencyHz, e.q, e.gainDb, e.channel)
                }
                // Publish the engine after initial controls are queued. Thereafter UI changes
                // are the sole producer and the DSP thread remains the sole consumer.
                s.engine = nativeEngine
            }
            val minBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
            val bufferBytes = max(if (minBytes > 0) minBytes else 16384, sampleRate * 2 * 4 / 4)
            val track = AudioTrack.Builder()
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
            s.audioTrack = track
            synchronized(sessionLock) {
                if (session !== s) throw IOException("Playback request was superseded")
                s.running.set(true)
                if (s.autoPlay && requestAudioFocus()) {
                    acquireWakeLock()
                    track.play()
                } else {
                    pausedForFocus = s.autoPlay
                    if (s.autoPlay) acquireWakeLock()
                }
                s.dspThread = Thread({ runDsp(s) }, "Auralis-DSP")
                s.outputThread = Thread({ runOutput(s) }, "Auralis-output")
                s.decoderThread = Thread({ runDecoder(s, channels) }, "Auralis-decoder")
                s.dspThread?.start()
                s.outputThread?.start()
                s.decoderThread?.start()
                updateNowPlaying(s.entry)
                updatePlaybackState(if (track.playState == AudioTrack.PLAYSTATE_PLAYING) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED)
                updateNotification("Playing audio")
            }
        } catch (t: Throwable) {
            s.running.set(false)
            try { s.audioTrack?.pause(); s.audioTrack?.stop() } catch (_: Throwable) { }
            val failedCurrent = synchronized(sessionLock) {
                if (session === s) {
                    session = null
                    true
                } else false
            }
            if (failedCurrent) {
                releaseWakeLock()
                abandonAudioFocus()
                updatePlaybackState(PlaybackState.STATE_STOPPED)
                updateNotification("Unable to play this audio")
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
                                staging, { stagedFrames = it }, { publishStaging() }, s)
                        }
                        decoder.releaseOutputBuffer(outputIndex, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputEos = true
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val changed = decoder.outputFormat
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

    private inline fun convertPcmToStereo(
        pcm: ByteBuffer,
        encoding: Int,
        channels: Int,
        staging: ByteBuffer,
        setFrameCount: (Int) -> Unit,
        flush: () -> Unit,
        s: PlaybackSession
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
        repeat(frameCount) {
            val left = readPcmSample(pcm, encoding)
            val right = if (channels == 2) readPcmSample(pcm, encoding) else left
            staging.putFloat(left)
            staging.putFloat(right)
            framesInChunk++
            if (framesInChunk == 1024) {
                setFrameCount(1024)
                flush()
                framesInChunk = 0
            }
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

    private fun runOutput(s: PlaybackSession) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val engine = s.engine ?: return
        val track = s.audioTrack ?: return
        val output = ByteBuffer.allocateDirect(1024 * 2 * 4).order(ByteOrder.LITTLE_ENDIAN)
        while (s.running.get() || !s.dspDone.get()) {
            output.clear()
            val frames = engine.dequeueOutput(output, 1024)
            if (frames > 0) {
                output.position(0)
                output.limit(frames * 2 * 4)
                var bytesRemaining = frames * 2 * 4
                while (bytesRemaining > 0 && s.running.get()) {
                    val written = track.write(output, bytesRemaining, AudioTrack.WRITE_NON_BLOCKING)
                    if (written < 0) break
                    if (written == 0) {
                        try { Thread.sleep(1) } catch (_: InterruptedException) { break }
                    } else {
                        bytesRemaining -= written
                    }
                }
            } else {
                if (s.dspDone.get()) break
                try { Thread.sleep(1) } catch (_: InterruptedException) { break }
            }
        }
        try { track.pause(); track.flush(); track.stop() } catch (_: Throwable) { }
        if (s.decoderDone.get() && s.dspDone.get() && session === s && s.running.get()) {
            onPlaybackEnded(s)
        }
    }

    private fun stopCurrent() {
        val old = synchronized(sessionLock) {
            val current = session
            session = null
            current?.running?.set(false)
            current
        } ?: return

        try { old.audioTrack?.pause(); old.audioTrack?.stop() } catch (_: Throwable) { }
        releaseWakeLock()
        abandonAudioFocus()
        updatePlaybackState(PlaybackState.STATE_STOPPED)
        updateNotification("Auralis is ready")
        scheduleSessionCleanup(old)
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
        val next = PlaybackSession(entry, positionMs.coerceAtLeast(0L), autoPlay)
        synchronized(sessionLock) {
            session = next
            val thread = Thread({ prepareAndStart(next) }, "Auralis-prepare")
            next.prepareThread = thread
            thread.start()
        }
        updateNowPlaying(entry)
    }

    private fun advanceQueue(userInitiated: Boolean) {
        val target = synchronized(queueLock) {
            if (queue.isEmpty()) return
            when {
                !userInitiated && repeatMode == REPEAT_ONE -> queueIndex
                shuffleEnabled && queue.size > 1 -> queue.indices.filter { it != queueIndex }.random()
                queueIndex + 1 < queue.size -> queueIndex + 1
                repeatMode == REPEAT_ALL -> 0
                else -> -1
            }
        }
        if (target >= 0) startQueueEntry(target, 0L, if (userInitiated) isPlaying() else true)
        else {
            stopCurrent()
            updateNotification("Queue finished")
        }
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
                session?.audioTrack?.setVolume(1.0f)
                if (pausedForFocus) {
                    if (session?.audioTrack != null) acquireWakeLock()
                    session?.audioTrack?.play()
                    pausedForFocus = false
                    updatePlaybackState(PlaybackState.STATE_PLAYING)
                    updateNotification("Playing")
                }
            }
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                pausedForFocus = change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                session?.audioTrack?.pause()
                releaseWakeLock()
                updatePlaybackState(PlaybackState.STATE_PAUSED)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> session?.audioTrack?.setVolume(0.2f)
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

    private fun buildNotification(text: String): Notification {
        val launch = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val entry = currentEntry()
        fun action(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
            this, requestCode, Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val playing = isPlaying()
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(entry?.title ?: "Auralis")
            .setContentText(entry?.artist?.let { "$it · $text" } ?: text)
            .setContentIntent(launch)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(session != null)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_previous, "Previous", action(ACTION_PREVIOUS, 1)).build())
            .addAction(Notification.Action.Builder(if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "Pause" else "Play", action(if (playing) ACTION_PAUSE else ACTION_PLAY, 2)).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_next, "Next", action(ACTION_NEXT, 3)).build())
            .setStyle(Notification.MediaStyle().setMediaSession(mediaSession?.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        sleepHandler.removeCallbacks(sleepRunnable)
        if (noisyReceiverRegistered) {
            runCatching { unregisterReceiver(noisyReceiver) }
            noisyReceiverRegistered = false
        }
        stopCurrent()
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        cleanupExecutor.shutdown()
        super.onDestroy()
    }

    private data class EqSetting(val index: Int, val enabled: Boolean, val type: Int,
                                 val frequencyHz: Int, val q: Double, val gainDb: Double, val channel: Int)

    internal data class QueueEntry(val uri: Uri, val title: String, val artist: String, val album: String, val durationMs: Long)

    private inner class PlaybackSession(val entry: QueueEntry, val startPositionMs: Long = 0L, val autoPlay: Boolean = true) {
        val uri: Uri = entry.uri
        @Volatile var sampleRate: Int = 48000
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
    }
}
