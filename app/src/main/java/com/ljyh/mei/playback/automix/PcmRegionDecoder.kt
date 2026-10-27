package com.ljyh.mei.playback.automix

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.ljyh.mei.constants.UserAgent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * 只解码指定时间窗口的音频，输出**交错立体声 float32**。
 *
 * 模式沿用 `playback/transition/BeatNetAudioDecoder`（MediaExtractor + MediaCodec
 * 增量解码、按 PTS 裁窗），区别是这里保留双声道并为混音统一重采样到 [targetSampleRate]。
 */
internal class PcmRegionDecoder(private val context: Context) {

    suspend fun decode(
        uri: Uri,
        startSeconds: Double,
        endSeconds: Double,
        targetSampleRate: Int,
        targetChannels: Int = 2,
    ): MixRegion? = withContext(Dispatchers.IO) {
        runCatching {
            decodeBlocking(uri, startSeconds, endSeconds, targetSampleRate, targetChannels)
        }.getOrElse { null }
    }

    private suspend fun decodeBlocking(
        uri: Uri,
        startSeconds: Double,
        endSeconds: Double,
        targetSampleRate: Int,
        targetChannels: Int,
    ): MixRegion? {
        val startUs = (startSeconds * 1_000_000.0).toLong()
        val endUs = (endSeconds * 1_000_000.0).toLong()
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            if (uri.scheme == "http" || uri.scheme == "https") {
                extractor.setDataSource(uri.toString(), mapOf("User-Agent" to UserAgent))
            } else {
                extractor.setDataSource(context, uri, emptyMap())
            }
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return null
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            extractor.selectTrack(track)
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            decoder = MediaCodec.createDecoderByType(mime).apply {
                configure(format, null, null, 0)
                start()
            }
            val codec = decoder
            val info = MediaCodec.BufferInfo()
            var sourceRate = format.intOr(MediaFormat.KEY_SAMPLE_RATE, 44_100)
            var sourceChannels = format.intOr(MediaFormat.KEY_CHANNEL_COUNT, 2)
            var encoding = AudioFormat.ENCODING_PCM_16BIT

            val chunks = ArrayList<FloatArray>()
            val starts = ArrayList<Double>()
            var total = 0
            var inputEnded = false
            var outputEnded = false
            var emptyPolls = 0

            while (!outputEnded && emptyPolls < 800) {
                currentCoroutineContext().ensureActive()
                if (!inputEnded) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)
                        val timestamp = extractor.sampleTime
                        val dataSize = if (timestamp < 0 || timestamp >= endUs) -1
                        else if (buffer != null) extractor.readSampleData(buffer, 0) else -1
                        if (dataSize < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(index, 0, dataSize, timestamp, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val index = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val decoded = codec.outputFormat
                        sourceRate = decoded.intOr(MediaFormat.KEY_SAMPLE_RATE, sourceRate)
                        sourceChannels = decoded.intOr(MediaFormat.KEY_CHANNEL_COUNT, sourceChannels)
                        encoding = decoded.intOr(MediaFormat.KEY_PCM_ENCODING, encoding)
                        emptyPolls = 0
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> emptyPolls++
                    else -> if (index >= 0) {
                        emptyPolls = 0
                        codec.getOutputBuffer(index)?.let { output ->
                            val pair = readStereo(
                                output, info, sourceChannels, sourceRate, encoding, startUs, endUs,
                            )
                            if (pair != null && pair.first.isNotEmpty()) {
                                chunks.add(pair.first)
                                starts.add(pair.second)
                                total += pair.first.size
                            }
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(index, false)
                    }
                }
            }
            if (total == 0) return null

            val raw = FloatArray(total)
            var cursor = 0
            for (chunk in chunks) {
                chunk.copyInto(raw, cursor)
                cursor += chunk.size
            }
            val resampled = if (sourceRate != targetSampleRate) {
                resample(raw, sourceRate, targetSampleRate, sourceChannels)
            } else {
                raw
            }
            val shaped = if (sourceChannels == targetChannels) {
                resampled
            } else {
                remapChannels(resampled, sourceChannels, targetChannels)
            }
            return MixRegion(shaped, targetSampleRate, startSeconds, targetChannels)
        } finally {
            runCatching { decoder?.stop() }
            decoder?.release()
            extractor.release()
        }
    }

    private fun readStereo(
        output: ByteBuffer,
        info: MediaCodec.BufferInfo,
        channels: Int,
        sampleRate: Int,
        encoding: Int,
        startUs: Long,
        endUs: Long,
    ): Pair<FloatArray, Double>? {
        val sampleBytes = when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_16BIT -> 2
            else -> return null
        }
        val safeChannels = channels.coerceAtLeast(1)
        val count = info.size / (sampleBytes * safeChannels)
        if (count <= 0) return null
        val first = (((startUs - info.presentationTimeUs) * sampleRate) / 1_000_000L)
            .toInt().coerceIn(0, count)
        val last = (((endUs - info.presentationTimeUs) * sampleRate) / 1_000_000L)
            .toInt().coerceIn(first, count)
        val frames = last - first
        if (frames <= 0) return null

        val source = output.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        source.position(info.offset + first * sampleBytes * safeChannels)
        val out = FloatArray(frames * 2)
        val startSecondsOfChunk = info.presentationTimeUs / 1_000_000.0 +
            first.toDouble() / sampleRate
        for (frame in 0 until frames) {
            if (safeChannels == 1) {
                val mono = source.readSample(encoding)
                out[frame * 2] = mono
                out[frame * 2 + 1] = mono
            } else {
                out[frame * 2] = source.readSample(encoding)
                out[frame * 2 + 1] = source.readSample(encoding)
                // 更多声道时丢弃多余数据
                repeat(safeChannels - 2) { source.readSample(encoding) }
            }
        }
        return out to startSecondsOfChunk
    }

    private fun remapChannels(input: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to) return input
        val frames = input.size / from
        val out = FloatArray(frames * to)
        for (frame in 0 until frames) {
            for (channel in 0 until to) {
                val sourceChannel = if (channel < from) channel else 0
                out[frame * to + channel] = input[frame * from + sourceChannel]
            }
        }
        return out
    }

    /** 线性插值重采样；AutoMix 只对齐时间轴，这点精度足够。 */
    private fun resample(input: FloatArray, from: Int, to: Int, channels: Int): FloatArray {
        val inFrames = input.size / channels
        if (inFrames == 0) return FloatArray(0)
        val outFrames = (inFrames.toDouble() * to / from).roundToInt().coerceAtLeast(1)
        val out = FloatArray(outFrames * channels)
        val step = from.toDouble() / to
        for (frame in 0 until outFrames) {
            val position = frame * step
            val i0 = position.toInt().coerceIn(0, inFrames - 1)
            val i1 = (i0 + 1).coerceAtMost(inFrames - 1)
            val frac = (position - i0).toFloat()
            for (channel in 0 until channels) {
                val a = input[i0 * channels + channel]
                val b = input[i1 * channels + channel]
                out[frame * channels + channel] = a + (b - a) * frac
            }
        }
        return out
    }

    private fun ByteBuffer.readSample(encoding: Int): Float = when (encoding) {
        AudioFormat.ENCODING_PCM_FLOAT -> float.takeIf(Float::isFinite) ?: 0f
        AudioFormat.ENCODING_PCM_32BIT -> int / 2_147_483_648f
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
            val low = get().toInt() and 0xff
            val middle = (get().toInt() and 0xff) shl 8
            val high = get().toInt() shl 16
            (low or middle or high) / 8_388_608f
        }
        AudioFormat.ENCODING_PCM_8BIT -> ((get().toInt() and 0xff) - 128) / 128f
        else -> short / 32768f
    }

    private fun MediaFormat.intOr(key: String, fallback: Int): Int =
        if (containsKey(key)) getInteger(key) else fallback
}
