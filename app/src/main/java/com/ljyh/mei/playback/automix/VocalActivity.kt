package com.ljyh.mei.playback.automix

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** 一段人声区间（曲内秒）。 */
internal data class VocalSpan(val start: Double, val end: Double)

/**
 * 人声区间提取。
 *
 * C# 侧由 `AutoMixTransition.VocalActivity.Fuse` 把歌词时间轴与静音检测结果融合；
 * Mei 上没有等价的模型输出，因此这里用两级近似：
 *
 * 1. [fromLyricLines] —— 歌词行的时间轴（最贴近"有人声"的定义，Mei 已有歌词系统）；
 * 2. [fromEnvelope] —— 没有歌词时退化为能量检测：连续有声段视为人声段。
 *
 * 两种来源都会做"短段合并"，避免把换气停顿切成几十个碎片。
 */
internal object VocalActivity {

    /** 两段间隔小于该值就合并（秒）。 */
    private const val MERGE_GAP = 0.45

    /** 短于该值的段直接丢弃（秒）。 */
    private const val MIN_SPAN = 0.4

    /** 歌词行：[开始毫秒, 结束毫秒] 列表。 */
    fun fromLyricLines(lines: List<Pair<Long, Long>>): List<VocalSpan> {
        if (lines.isEmpty()) return emptyList()
        val spans = lines
            .filter { it.second > it.first }
            .map { VocalSpan(it.first / 1000.0, it.second / 1000.0) }
            .sortedBy { it.start }
        return merge(spans)
    }

    /**
     * 能量法：把包络里高于阈值的连续窗口合并成段。
     *
     * @param envelope 每 [stepSeconds] 一个 RMS 值（线性幅度，0..1）。
     * @param stepSeconds 窗口步长。
     */
    fun fromEnvelope(envelope: List<Float>, stepSeconds: Double = 0.1): List<VocalSpan> {
        if (envelope.isEmpty()) return emptyList()
        val peak = envelope.max().toDouble()
        if (peak <= 1e-5) return emptyList()
        // 相对峰值取 -18dB 作为"有声"门限，再与绝对门限取大者。
        val threshold = max(peak * 0.125, 0.0035)
        val spans = mutableListOf<VocalSpan>()
        var start = -1
        for ((index, value) in envelope.withIndex()) {
            val voiced = value >= threshold
            if (voiced && start < 0) start = index
            if (!voiced && start >= 0) {
                spans += VocalSpan(start * stepSeconds, index * stepSeconds)
                start = -1
            }
        }
        if (start >= 0) spans += VocalSpan(start * stepSeconds, envelope.size * stepSeconds)
        return merge(spans.filter { it.end - it.start >= MIN_SPAN })
    }

    private fun merge(spans: List<VocalSpan>): List<VocalSpan> {
        if (spans.isEmpty()) return emptyList()
        val merged = mutableListOf<VocalSpan>()
        var current = spans.first()
        for (span in spans.drop(1)) {
            current = if (span.start - current.end <= MERGE_GAP) {
                VocalSpan(current.start, max(current.end, span.end))
            } else {
                merged += current
                span
            }
        }
        merged += current
        return merged
    }

    /** 按每 100ms 计算 RMS 包络，供 [fromEnvelope] 与 [SoundBoundsEstimator] 使用。 */
    fun envelope(samples: FloatArray, sampleRate: Int, channels: Int, windowMs: Int = 100): FloatArray {
        if (sampleRate <= 0 || channels <= 0 || samples.isEmpty()) return FloatArray(0)
        val framesPerWindow = max(1, sampleRate * windowMs / 1000)
        val frameCount = samples.size / channels
        val windows = max(1, frameCount / framesPerWindow)
        val result = FloatArray(windows)
        var frame = 0
        for (window in 0 until windows) {
            var power = 0.0
            var counted = 0
            val end = min(frameCount, frame + framesPerWindow)
            while (frame < end) {
                var sum = 0.0
                for (channel in 0 until channels) sum += samples[frame * channels + channel].toDouble()
                val mono = sum / channels
                power += mono * mono
                counted++
                frame++
            }
            result[window] = if (counted == 0) 0f else sqrt(power / counted).toFloat()
        }
        return result
    }
}

/**
 * 声音边界估计（曲首/曲尾的静音、前奏结束、尾奏开始）。
 *
 * C# 侧由 `AudioAnalysisService` 从整曲包络里推出 `SoundStart/SoundEnd/IntroEnd/OutroStart`；
 * 这里对一段已解码的 PCM 区域做同样的事：
 *
 * - `soundStart` / `soundEnd`：第一个/最后一个持续有声窗口；
 * - `introEnd`：曲首第一次出现"持续响段"之后第一次显著回落；
 * - `outroStart`：曲尾最后一次显著回落之前。
 */
internal object SoundBoundsEstimator {

    data class Bounds(
        val soundStart: Double,
        val soundEnd: Double,
        val introEnd: Double,
        val outroStart: Double,
    )

    private const val WINDOW_MS = 100

    fun estimate(samples: FloatArray, sampleRate: Int, channels: Int, regionStartSeconds: Double): Bounds {
        val envelope = VocalActivity.envelope(samples, sampleRate, channels, WINDOW_MS)
        if (envelope.isEmpty()) {
            return Bounds(regionStartSeconds, regionStartSeconds, -1.0, -1.0)
        }
        val step = WINDOW_MS / 1000.0
        val peak = envelope.max().toDouble()
        if (peak <= 1e-5) {
            return Bounds(regionStartSeconds, regionStartSeconds, -1.0, -1.0)
        }
        val threshold = max(peak * 0.06, 0.0025)

        var first = envelope.indexOfFirst { it >= threshold }
        var last = envelope.indexOfLast { it >= threshold }
        if (first < 0) {
            first = 0
            last = envelope.lastIndex
        }
        // 边界向内外各允许 200ms 的抖动，避免把起音前的气声算成静音。
        val soundStart = regionStartSeconds + max(0, first - 2) * step
        val soundEnd = regionStartSeconds + min(envelope.size, last + 3) * step

        var bodySum = 0.0
        var bodyCount = 0
        for (i in first..last) {
            if (envelope[i] >= threshold) {
                bodySum += envelope[i]
                bodyCount++
            }
        }
        val bodyLevel = if (bodyCount > 0) bodySum / bodyCount else threshold
        val dropThreshold = max(bodyLevel * 0.45, threshold)

        // 前奏结束：曲首 1.5s 之后第一次连续 4 个窗口低于 dropThreshold。
        var introEnd = -1.0
        var quietRun = 0
        for (i in first..last) {
            if (envelope[i] < dropThreshold) {
                quietRun++
                if (quietRun >= 4 && i - 4 > first + 15) {
                    introEnd = regionStartSeconds + (i - 4) * step
                    break
                }
            } else {
                quietRun = 0
            }
        }

        // 尾奏开始：曲尾 1.5s 之前最后一次连续 4 个窗口低于 dropThreshold。
        var outroStart = -1.0
        quietRun = 0
        for (i in last downTo first) {
            if (envelope[i] < dropThreshold) {
                quietRun++
                if (quietRun >= 4 && last - 4 - i > 15) {
                    outroStart = regionStartSeconds + (i + 4) * step
                    break
                }
            } else {
                quietRun = 0
            }
        }

        return Bounds(soundStart, soundEnd, introEnd, outroStart)
    }

    /** 近似整体响度（LUFS-ish）：K 计权的简化版，只做 RMS + -0.691 常数。 */
    fun approximateLoudness(samples: FloatArray, channels: Int): Double {
        if (samples.isEmpty() || channels <= 0) return -70.0
        var power = 0.0
        val frames = samples.size / channels
        if (frames == 0) return -70.0
        for (frame in 0 until frames) {
            var sum = 0.0
            for (channel in 0 until channels) sum += samples[frame * channels + channel].toDouble()
            val mono = sum / channels
            power += mono * mono
        }
        val rms = sqrt(power / frames)
        if (rms <= 1e-7) return -70.0
        return 20 * kotlin.math.log10(rms) - 0.691
    }
}
