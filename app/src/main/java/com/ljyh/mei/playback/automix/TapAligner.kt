package com.ljyh.mei.playback.automix

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 原始 PCM 环形缓冲，用来把"正在播放的那 250ms"和离线解码的区域做互相关。
 *
 * 对应 C# `MixPrimitives.PlaybackTap`（`CopyLatest` / `TotalFrames`）。
 * 写入方是音频线程（[write]），读取方是分析线程（[copyLatest]），
 * 因此内部用锁保护；每块只有几十 KB，锁竞争可以忽略。
 */
internal class TapBuffer(sampleRate: Int, capacitySeconds: Double = 4.0, channels: Int = 2) {
    private val channelCount = channels
    private val capacityFrames = max(1, (sampleRate * capacitySeconds).toInt())
    private val storage = FloatArray(capacityFrames * channelCount)
    private val lock = Any()

    @Volatile
    private var writeFrames = 0L

    @Volatile
    var sampleRate: Int = sampleRate
        private set

    @Volatile
    var channelCountValue: Int = channels
        private set

    val totalFrames: Long get() = writeFrames

    fun reset() {
        synchronized(lock) {
            writeFrames = 0L
            storage.fill(0f)
        }
    }

    fun configure(newSampleRate: Int, newChannels: Int) {
        if (newSampleRate <= 0 || newChannels <= 0) return
        sampleRate = newSampleRate
        channelCountValue = newChannels
        reset()
    }

    /** 写入交错样本（帧数 = interleaved.size / channels）。 */
    fun write(interleaved: FloatArray, frames: Int) {
        if (frames <= 0) return
        val channels = channelCount
        synchronized(lock) {
            var frame = 0
            while (frame < frames) {
                val slot = ((writeFrames + frame) % capacityFrames).toInt()
                for (channel in 0 until channels) {
                    val source = frame * channels + channel
                    storage[slot * channels + channel] =
                        if (source < interleaved.size) interleaved[source] else 0f
                }
                frame++
            }
            writeFrames += frames
        }
    }

    /** 写入 16bit 小端 PCM。 */
    fun writeInt16(buffer: java.nio.ByteBuffer, frames: Int) {
        val channels = channelCount
        val scratch = FloatArray(frames * channels)
        for (i in scratch.indices) {
            scratch[i] = buffer.short / 32768f
        }
        write(scratch, frames)
    }

    /**
     * 拷贝最近 [frames] 帧。
     * @return 拷贝的起始绝对帧号；不足 [frames] 帧时返回 -1。
     */
    fun copyLatest(destination: FloatArray, frames: Int): Long {
        val channels = channelCount
        if (frames <= 0 || destination.size < frames * channels) return -1
        synchronized(lock) {
            if (writeFrames < frames) return -1
            val start = writeFrames - frames
            for (i in 0 until frames) {
                val slot = ((start + i) % capacityFrames).toInt()
                for (channel in 0 until channels) {
                    destination[i * channels + channel] = storage[slot * channels + channel]
                }
            }
            return start
        }
    }
}

/**
 * 互相关对齐器。
 *
 * 逐行移植自 HyPlayer-Nextgen `AutoMix/TapAligner.cs`：
 * 取实时 tap 最近 250ms 作为 needle，在离线解码的 region 里先以 ±2s 粗搜
 * （步长 4、每 8 样本采样打分，阈值 0.8），失败再放宽到 ±8s（阈值 0.9），
 * 最后在最佳点附近 ±8 帧精搜。
 *
 * 返回值为 "tap 帧号 ↔ region 帧号" 的映射偏移，供引擎推算
 * "此刻播放头在 region 的第几帧"。
 */
internal object TapAligner {

    fun findMap(tap: TapBuffer, region: MixRegion, expectedRegionIndex: Long): Double? {
        val needleFrames = region.sampleRate / 4
        if (needleFrames <= 0 || region.frames <= needleFrames) return null
        val channels = region.channelCount
        val needle = FloatArray(needleFrames * channels)
        val tapStart = tap.copyLatest(needle, needleFrames)
        if (tapStart < 0) return null
        if (tap.totalFrames - tapStart < needleFrames) return null

        var energy = 0.0
        for (i in 0 until needleFrames) {
            val sample = needle[i * channels].toDouble()
            energy += sample * sample
        }
        if (energy < 1e-6) return null

        val expectedStart = expectedRegionIndex - needleFrames
        var offset = search(region, needle, needleFrames, expectedStart, region.sampleRate * 2L, 0.8)
        if (offset < 0) offset = search(region, needle, needleFrames, expectedStart, region.sampleRate * 8L, 0.9)
        if (offset < 0) return null
        return (offset - tapStart).toDouble()
    }

    private fun search(
        region: MixRegion,
        needle: FloatArray,
        needleFrames: Int,
        expectedStart: Long,
        radius: Long,
        threshold: Double,
    ): Long {
        val from = max(0L, expectedStart - radius)
        val to = min((region.frames - needleFrames).toLong(), expectedStart + radius)
        if (to <= from) return -1

        var bestOffset = -1L
        var bestScore = Double.NEGATIVE_INFINITY
        var offset = from
        while (offset <= to) {
            val score = score(region, offset, needle, needleFrames, 8)
            if (score > bestScore) {
                bestScore = score
                bestOffset = offset
            }
            offset += 4
        }
        if (bestOffset < 0) return -1
        val refineFrom = max(from, bestOffset - 8)
        val refineTo = min(to, bestOffset + 8)
        bestScore = Double.NEGATIVE_INFINITY
        var refined = refineFrom
        while (refined <= refineTo) {
            val score = score(region, refined, needle, needleFrames, 1)
            if (score > bestScore) {
                bestScore = score
                bestOffset = refined
            }
            refined++
        }
        return if (bestScore < threshold) -1 else bestOffset
    }

    private fun score(region: MixRegion, offset: Long, needle: FloatArray, frames: Int, step: Int): Double {
        val channels = region.channelCount
        var dot = 0.0
        var regionPower = 0.0
        var needlePower = 0.0
        var i = 0
        while (i < frames) {
            val r = region.sampleAt(offset + i, 0)
            val n = needle[i * channels].toDouble()
            dot += r * n
            regionPower += r * r
            needlePower += n * n
            i += step
        }
        return dot / sqrt(regionPower * needlePower + 1e-12)
    }

    /** 供日志/测试使用的相似度上限：完全相同时为 1.0。 */
    internal fun similarity(a: Float, b: Float): Double = abs(a - b).toDouble()
}
