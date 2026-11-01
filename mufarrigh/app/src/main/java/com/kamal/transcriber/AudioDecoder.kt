package com.kamal.transcriber

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** Thrown to unwind work when the user asked to stop. */
class StopRequested : RuntimeException("stopped")

/** Writes 16-bit little-endian samples to a file. */
private class PcmSink(file: File) {
    private val out = BufferedOutputStream(FileOutputStream(file), 1 shl 16)
    private val buf = ByteArray(8192)
    private var n = 0

    var count: Long = 0L
        private set

    fun put(x: Float) {
        var v = x * 32767f
        if (v > 32767f) v = 32767f
        if (v < -32768f) v = -32768f
        val s = v.toInt()
        buf[n++] = (s and 0xFF).toByte()
        buf[n++] = ((s shr 8) and 0xFF).toByte()
        count++
        if (n == buf.size) {
            out.write(buf, 0, n)
            n = 0
        }
    }

    fun close() {
        try {
            if (n > 0) out.write(buf, 0, n)
            n = 0
            out.flush()
        } finally {
            out.close()
        }
    }
}

/** Streaming box-filter resampler (averages the input samples that fall into each output sample). */
private class Resampler(inRate: Int) {
    private val ratio: Double = inRate.toDouble() / AudioDecoder.TARGET_RATE
    private var acc = 0.0
    private var accW = 0.0

    fun push(x: Float, sink: PcmSink) {
        var w = 1.0
        while (w > 1e-9) {
            val need = ratio - accW
            if (w < need - 1e-9) {
                acc += x * w
                accW += w
                w = 0.0
            } else {
                acc += x * need
                sink.put((acc / ratio).toFloat())
                w -= need
                acc = 0.0
                accW = 0.0
            }
        }
    }
}

object AudioDecoder {
    const val TARGET_RATE = 16000

    /**
     * Decodes the first audio track of [src] (any format Android can play, audio or video)
     * to 16 kHz mono 16-bit PCM in [dest]. Returns the number of samples written.
     */
    fun decode(
        src: File,
        dest: File,
        onProgress: (Float) -> Unit,
        stopped: () -> Boolean
    ): Long {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val sink = PcmSink(dest)
        try {
            extractor.setDataSource(src.absolutePath)

            var trackIndex = -1
            var found: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME)
                if (m != null && m.startsWith("audio/")) {
                    trackIndex = i
                    found = f
                    break
                }
            }
            val format: MediaFormat = found ?: throw IOException("لا يوجد مسار صوتي في هذا الملف")
            extractor.selectTrack(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME) ?: throw IOException("صيغة غير معروفة")
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L

            val dec = MediaCodec.createDecoderByType(mime)
            codec = dec
            dec.configure(format, null, null, 0)
            dec.start()

            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var resampler = Resampler(rate)

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var lastReport = 0L

            while (!outputDone) {
                if (stopped()) throw StopRequested()

                if (!inputDone) {
                    val inIndex = dec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inBuf = dec.getInputBuffer(inIndex) ?: throw IOException("خطأ في مفكّك الصوت")
                        val size = extractor.readSampleData(inBuf, 0)
                        if (size < 0) {
                            dec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = dec.dequeueOutputBuffer(info, 10_000)
                if (outIndex >= 0) {
                    if (info.size > 0) {
                        val ob = dec.getOutputBuffer(outIndex)
                        if (ob != null) {
                            ob.position(info.offset)
                            ob.limit(info.offset + info.size)
                            val bb = ob.order(ByteOrder.LITTLE_ENDIAN)
                            val ch = if (channels > 0) channels else 1
                            if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                val fb = bb.asFloatBuffer()
                                val frames = fb.remaining() / ch
                                for (fr in 0 until frames) {
                                    var s = 0f
                                    for (c in 0 until ch) s += fb.get()
                                    resampler.push(s / ch, sink)
                                }
                            } else {
                                val sb = bb.asShortBuffer()
                                val frames = sb.remaining() / ch
                                for (fr in 0 until frames) {
                                    var s = 0f
                                    for (c in 0 until ch) s += sb.get() / 32768f
                                    resampler.push(s / ch, sink)
                                }
                            }
                        }
                    }
                    dec.releaseOutputBuffer(outIndex, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true

                    val now = System.currentTimeMillis()
                    if (durationUs > 0 && now - lastReport > 500) {
                        lastReport = now
                        val frac = (info.presentationTimeUs.toDouble() / durationUs.toDouble()).toFloat()
                        onProgress(frac.coerceIn(0f, 1f))
                    }
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val nf = dec.outputFormat
                    channels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    rate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    encoding = if (nf.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        nf.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    } else {
                        AudioFormat.ENCODING_PCM_16BIT
                    }
                    resampler = Resampler(rate)
                }
            }
        } finally {
            try {
                sink.close()
            } catch (e: Exception) {
                // ignore
            }
            try {
                codec?.stop()
            } catch (e: Exception) {
                // ignore
            }
            try {
                codec?.release()
            } catch (e: Exception) {
                // ignore
            }
            try {
                extractor.release()
            } catch (e: Exception) {
                // ignore
            }
        }
        if (sink.count == 0L) throw IOException("لم يُستخرج أي صوت من هذا الملف")
        return sink.count
    }
}

/** Random access to the decoded PCM file and chunk planning. */
object Pcm {
    private const val CHUNK_SAMPLES = 300L * AudioDecoder.TARGET_RATE        // ~5 minutes
    private const val SEARCH_SAMPLES = 20L * AudioDecoder.TARGET_RATE        // look for silence in the last 20 s
    private const val MIN_TAIL_SAMPLES = 45L * AudioDecoder.TARGET_RATE      // avoid a tiny last chunk

    fun read(file: File, startSample: Long, count: Int): FloatArray {
        val bytes = ByteArray(count * 2)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(startSample * 2)
            raf.readFully(bytes)
        }
        val sb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val out = FloatArray(count)
        for (i in 0 until count) out[i] = sb.get(i) / 32768f
        return out
    }

    /** Center of the quietest 100 ms frame between [from] and [to]. */
    private fun quietestPoint(file: File, from: Long, to: Long): Long {
        val count = (to - from).toInt()
        val bytes = ByteArray(count * 2)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(from * 2)
            raf.readFully(bytes)
        }
        val sb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val frame = 1600
        var best = from + count / 2
        var bestEnergy = Long.MAX_VALUE
        var i = 0
        while (i + frame <= count) {
            var e = 0L
            for (j in 0 until frame) e += abs(sb.get(i + j).toInt())
            if (e < bestEnergy) {
                bestEnergy = e
                best = from + i + frame / 2
            }
            i += frame / 2
        }
        return best
    }

    /** Where the chunk that starts at [start] should end. */
    fun planEnd(file: File, start: Long, total: Long): Long {
        if (total - start <= CHUNK_SAMPLES + MIN_TAIL_SAMPLES) return total
        val target = start + CHUNK_SAMPLES
        return quietestPoint(file, target - SEARCH_SAMPLES, target)
    }
}
