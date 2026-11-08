package com.auralis

import android.content.Context
import android.net.Uri
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Reads ReplayGain tags for a track. Tag parsing itself is native (and unit-tested on the host);
 * this class only decides which bytes of the file to hand over: the head of the file for MP3, FLAC
 * and Ogg, or the 'moov' box for MP4/M4A, which is often stored after the audio data.
 */
internal object ReplayGainReader {
    private const val HEAD_BYTES = 4 * 1024 * 1024L   // ID3v2 tags carrying cover art can be large
    private const val MOOV_LIMIT = 32 * 1024 * 1024L

    /** Returns the tags, or null when the file has none or cannot be read. Never throws. */
    fun read(context: Context, uri: Uri): ReplayGainTags? {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { stream ->
                    val channel = stream.channel
                    val size = channel.size()
                    val head = readAt(channel, 0L, minOf(size, HEAD_BYTES).toInt())
                    if (isMp4(head)) {
                        val moov = findMoov(channel, size)
                        if (moov == null) null else NativeAudioEngine.parseReplayGain(moov, moov.size)
                    } else {
                        NativeAudioEngine.parseReplayGain(head, head.size)
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun isMp4(head: ByteArray): Boolean =
        head.size >= 12 && head[4] == 'f'.code.toByte() && head[5] == 't'.code.toByte() &&
            head[6] == 'y'.code.toByte() && head[7] == 'p'.code.toByte()

    private fun readAt(channel: FileChannel, position: Long, length: Int): ByteArray {
        if (length <= 0) return ByteArray(0)
        val buffer = ByteBuffer.allocate(length)
        var pos = position
        while (buffer.hasRemaining()) {
            val n = channel.read(buffer, pos)
            if (n <= 0) break
            pos += n
        }
        return buffer.array().copyOf(buffer.position())
    }

    private fun be32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xff) shl 24) or ((b[o + 1].toLong() and 0xff) shl 16) or
            ((b[o + 2].toLong() and 0xff) shl 8) or (b[o + 3].toLong() and 0xff)

    /** Walks the top-level MP4 boxes and returns the whole 'moov' box, or null if there is none. */
    private fun findMoov(channel: FileChannel, fileSize: Long): ByteArray? {
        var pos = 0L
        while (pos + 8 <= fileSize) {
            val header = readAt(channel, pos, 16)
            if (header.size < 8) return null
            var boxSize = be32(header, 0)
            if (boxSize == 1L) {
                if (header.size < 16) return null
                boxSize = (be32(header, 8) shl 32) or be32(header, 12)
            } else if (boxSize == 0L) {
                boxSize = fileSize - pos
            }
            if (boxSize < 8 || pos + boxSize > fileSize) return null
            val isMoov = header[4] == 'm'.code.toByte() && header[5] == 'o'.code.toByte() &&
                header[6] == 'o'.code.toByte() && header[7] == 'v'.code.toByte()
            if (isMoov) {
                if (boxSize > MOOV_LIMIT) return null
                return readAt(channel, pos, boxSize.toInt())
            }
            pos += boxSize
        }
        return null
    }
}
