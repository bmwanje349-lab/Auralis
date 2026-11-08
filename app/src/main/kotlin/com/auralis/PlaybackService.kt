package com.auralis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Process
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/** Playback is split into decoder, native DSP, and output threads. */
class PlaybackService : Service() {
    inner class LocalBinder : Binder() { fun service(): PlaybackService = this@PlaybackService }

    private val binder = LocalBinder()
    // Serializes configuration producers only; the native DSP consumer never takes this lock.
    private val configLock = Any()
    private val sessionLock = Any()
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
    @Volatile private var replayGainPreampMb = 0
    private val reverbParameters = doubleArrayOf(0.55, 0.50, 0.45, 0.15, 1.00, 0.50, 0.50)
    private val eqSettings = ConcurrentHashMap<Int, EqSetting>()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Auralis is ready"))
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    fun load(uri: Uri) {
        stopCurrent()
        val next = PlaybackSession(uri)
        synchronized(sessionLock) {
            session = next
            val thread = Thread({ prepareAndStart(next) }, "Auralis-prepare")
            next.prepareThread = thread
            thread.start()
        }
    }

    fun pausePlayback() { session?.audioTrack?.pause() }
    fun resumePlayback() { session?.audioTrack?.play() }
    fun stopPlayback() { stopCurrent() }

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

    fun setReplayGain(type: Int, source: Int, defaultGainMb: Int, preampMb: Int) {
        synchronized(configLock) {
            replayGainType = type.coerceIn(0, 2)
            replayGainSource = source.coerceIn(0, 1)
            replayGainDefaultMb = defaultGainMb.coerceIn(-3000, 3000)
            replayGainPreampMb = preampMb.coerceIn(-3000, 3000)
            session?.engine?.setReplayGain(replayGainType, replayGainSource, replayGainDefaultMb, replayGainPreampMb)
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
            val mime = format.getString(MediaFormat.KEY_MIME) ?: throw IOException("Missing audio MIME type")
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            if (channels > 2) throw IOException("This stereo DSP path accepts mono or stereo input only")

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            s.extractor = extractor
            s.codec = codec
            // Tags are read once per track; the engine keeps them, so later preference changes
            // only need the new settings.
            val replayGainTags = ReplayGainReader.read(this, s.uri) ?: ReplayGainTags()
            val nativeEngine = NativeAudioEngine(sampleRate)
            synchronized(configLock) {
                nativeEngine.setStereoWide(stereoEnabled, stereoPercent)
                nativeEngine.setBalance(balanceEnabled, balanceNormalized)
                nativeEngine.setMono(monoEnabled)
                nativeEngine.setLimiter(limiterEnabled, limiterPath)
                reverbParameters.forEachIndexed { index, value -> nativeEngine.setReverbParameter(index, value) }
                nativeEngine.setReverbEnabled(reverbEnabled)
                nativeEngine.setReplayGain(replayGainType, replayGainSource, replayGainDefaultMb, replayGainPreampMb)
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
                track.play()
                s.dspThread = Thread({ runDsp(s) }, "Auralis-DSP")
                s.outputThread = Thread({ runOutput(s) }, "Auralis-output")
                s.decoderThread = Thread({ runDecoder(s, channels) }, "Auralis-decoder")
                s.dspThread?.start()
                s.outputThread?.start()
                s.decoderThread?.start()
                updateNotification("Playing audio")
            }
        } catch (t: Throwable) {
            s.running.set(false)
            try { codec?.stop() } catch (_: Throwable) { }
            try { codec?.release() } catch (_: Throwable) { }
            try { extractor?.release() } catch (_: Throwable) { }
            try { s.audioTrack?.release() } catch (_: Throwable) { }
            s.engine?.close()
            if (session === s) updateNotification("Unable to play this audio")
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
    }

    private fun stopCurrent() {
        val old = synchronized(sessionLock) {
            val current = session
            session = null
            current?.running?.set(false)
            current
        } ?: return
        try { old.audioTrack?.pause(); old.audioTrack?.stop() } catch (_: Throwable) { }
        listOf(old.prepareThread, old.decoderThread, old.dspThread, old.outputThread).forEach { thread ->
            if (thread != null && thread !== Thread.currentThread()) {
                try { thread.join(1200) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            }
        }
        try { old.audioTrack?.release() } catch (_: Throwable) { }
        try { old.codec?.release() } catch (_: Throwable) { }
        try { old.extractor?.release() } catch (_: Throwable) { }
        old.engine?.close()
        updateNotification("Auralis is ready")
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
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Auralis")
            .setContentText(text)
            .setContentIntent(launch)
            .setOngoing(session != null)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        stopCurrent()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private data class EqSetting(val index: Int, val enabled: Boolean, val type: Int,
                                 val frequencyHz: Int, val q: Double, val gainDb: Double, val channel: Int)

    private inner class PlaybackSession(val uri: Uri) {
        val running = AtomicBoolean(false)
        val decoderDone = AtomicBoolean(false)
        val dspDone = AtomicBoolean(false)
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
        private const val CHANNEL_ID = "auralis-playback"
        private const val NOTIFICATION_ID = 41
    }
}
