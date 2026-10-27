package com.ljyh.mei.playback.automix

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * WSOLA 时间伸缩（变速不变调）。
 *
 * C# 用 `signalsmith-stretch` 原生库；Mei 这里给一个纯 Kotlin 的
 * waveform-similarity overlap-add 实现，质量足够覆盖 AutoMix 的 ±6% 范围，
 * 好处是不引入 NDK 依赖（模块保持纯 Kotlin、单 APK）。
 *
 * 算法要点：
 * - 合成跳 Hs = 帧长/2，周期性 Hann 窗 50% 重叠叠加后窗和恒为 1；
 * - 分析跳 Ha = Hs / rate，rate < 1 读得慢（拉长），> 1 读得快（压缩）；
 * - 每帧在 ±searchRadius 内做归一化互相关，选出与上一帧尾部最相似的片段，
 *   这是 WSOLA 与朴素 OLA 的关键差别（避免相位跳变产生的金属音）。
 */
internal class WsolaStretcher(
    private val region: MixRegion,
    private val frameSize: Int = 2048,
    private val searchRadius: Int = 256,
) {
    private val channels = region.channelCount
    private val hop = frameSize / 2
    private val window = FloatArray(frameSize) {
        0.5f - 0.5f * cos(2.0 * Math.PI * it / frameSize).toFloat()
    }

    /** 合成累加区，长度 2*hop：前半是本次可输出的完整样本，后半留给下一帧。 */
    private var staging = FloatArray(2 * hop * channels)
    private var prevTail: FloatArray? = null
    private var analysisCursor = 0.0

    /** 已合成但未被取走的输出。 */
    private var output = FloatArray(hop * channels * 4)
    private var outputFrames = 0
    private var outputRead = 0

    val exhausted: Boolean get() = analysisCursor >= region.frames - 1

    fun seek(contentFrame: Long) {
        analysisCursor = contentFrame.toDouble().coerceIn(0.0, region.frames.toDouble())
        prevTail = null
        staging.fill(0f)
        outputFrames = 0
        outputRead = 0
    }

    fun reset() = seek(0)

    /** 当前拉伸流大致"读到"的内容帧号。 */
    fun consumedFrames(): Double = analysisCursor

    /**
     * 填充 [output]（交错，长度须 >= [frames]*channels）。
     * 素材耗尽后的部分填 0。
     */
    fun render(output: FloatArray, frames: Int, rate: Double) {
        val sink = output
        var written = 0
        while (written < frames) {
            if (outputRead >= outputFrames) {
                if (!synthesize(rate)) {
                    java.util.Arrays.fill(sink, written * channels, frames * channels, 0f)
                    return
                }
            }
            val available = outputFrames - outputRead
            val take = min(available, frames - written)
            System.arraycopy(this.output, outputRead * channels, sink, written * channels, take * channels)
            outputRead += take
            written += take
        }
    }

    private fun synthesize(rate: Double): Boolean {
        val safeRate = rate.coerceIn(0.5, 2.0)
        val analysisHop = hop / safeRate
        val base = analysisCursor.roundToLong()

        var bestOffset = 0L
        val reference = prevTail
        if (reference != null) {
            var bestScore = Double.NEGATIVE_INFINITY
            val limit = searchRadius.toLong()
            var offset = -limit
            while (offset <= limit) {
                val candidate = base + offset
                if (candidate >= 0 && candidate + frameSize <= region.frames) {
                    val score = correlation(candidate, reference)
                    if (score > bestScore) {
                        bestScore = score
                        bestOffset = offset
                    }
                }
                offset += 2
            }
        }

        val start = (base + bestOffset).coerceIn(0L, max(0L, region.frames - 1L))
        if (start >= region.frames) return false

        // 把本帧（加窗后）叠进 staging：前半接在上一帧留下的后半上。
        for (i in 0 until frameSize) {
            val gain = window[i]
            val targetFrame = i
            if (targetFrame >= 2 * hop) break
            val sourceFrame = start + i
            for (channel in 0 until channels) {
                val sample = if (sourceFrame < region.frames) {
                    region.sampleAt(sourceFrame, channel).toFloat() * gain
                } else {
                    0f
                }
                staging[targetFrame * channels + channel] += sample
            }
        }

        ensureOutputCapacity(outputFrames + hop)
        System.arraycopy(staging, 0, output, outputFrames * channels, hop * channels)
        outputFrames += hop

        // 后半搬到前半，后半清零，等待下一帧叠加。
        System.arraycopy(staging, hop * channels, staging, 0, hop * channels)
        java.util.Arrays.fill(staging, hop * channels, staging.size, 0f)

        val nextTail = FloatArray(hop * channels)
        for (i in 0 until hop) {
            val sourceFrame = start + hop + i
            for (channel in 0 until channels) {
                nextTail[i * channels + channel] = if (sourceFrame < region.frames) {
                    region.sampleAt(sourceFrame, channel).toFloat()
                } else {
                    0f
                }
            }
        }
        prevTail = nextTail
        analysisCursor = min(analysisCursor + analysisHop, region.frames.toDouble())
        if (analysisCursor >= region.frames) return false
        return true
    }

    private fun ensureOutputCapacity(frames: Int) {
        val needed = frames * channels
        if (output.size >= needed) return
        // 先把未消费部分前移，避免无限增长。
        if (outputRead > 0) {
            val remain = (outputFrames - outputRead) * channels
            System.arraycopy(output, outputRead * channels, output, 0, remain)
            outputFrames -= outputRead
            outputRead = 0
        }
        if (output.size < outputFrames * channels + needed) {
            output = output.copyOf(max(outputFrames * channels + needed, output.size * 2))
        }
    }

    private fun correlation(candidateStart: Long, reference: FloatArray): Double {
        var dot = 0.0
        var referencePower = 0.0
        var candidatePower = 0.0
        var i = 0
        while (i < hop) {
            val r = reference[i * channels].toDouble()
            val c = region.sampleAt(candidateStart + i, 0)
            dot += r * c
            referencePower += r * r
            candidatePower += c * c
            i += 2
        }
        if (abs(referencePower) < 1e-12 || abs(candidatePower) < 1e-12) return 0.0
        return dot / sqrt(referencePower * candidatePower)
    }
}
