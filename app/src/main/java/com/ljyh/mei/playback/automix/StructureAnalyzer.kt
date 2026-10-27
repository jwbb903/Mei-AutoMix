package com.ljyh.mei.playback.automix

import kotlin.math.sqrt

/**
 * 粗略的音乐结构视图：只保留「能量包络」这一项。
 *
 * 用途有两个：
 * 1. 选退出点时避开高潮/填充，优先落在尾奏或间奏（能量明显下降的位置）；
 * 2. 为日志提供可解释的依据（换句点能量 0.3 vs 0.9，一听就知道选得对不对）。
 *
 * 上游 HyPlayer 用 PANNs/结构分析模型做这件事，代价是几十 MB 模型 + 秒级推理；
 * 对「选一个落点」这个用途来说，能量包络已经能覆盖绝大多数流行乐的尾奏形态。
 */
internal class StructureAnalyzer private constructor(
    private val envelope: DoubleArray,
    private val stepSeconds: Double,
    private val startSeconds: Double,
    /** 中位数能量，作为归一化基准。 */
    private val reference: Double,
) {

    /** [from, to] 区间内的平均能量（相对中位数归一化）。 */
    fun energyBetween(from: Double, to: Double): Double {
        if (envelope.isEmpty()) return 1.0
        val startIndex = ((from - startSeconds) / stepSeconds).toInt().coerceIn(0, envelope.size - 1)
        val endIndex = ((to - startSeconds) / stepSeconds).toInt().coerceIn(startIndex + 1, envelope.size)
        var sum = 0.0
        var count = 0
        for (index in startIndex until endIndex) {
            sum += envelope[index]
            count++
        }
        if (count == 0) return 1.0
        return if (reference <= 1e-9) 1.0 else (sum / count) / reference
    }

    /** 尾段是否已经进入低能量区（用于判断「现在退出是否会踩到高潮」）。 */
    fun isLowEnergy(from: Double, to: Double, threshold: Double = 0.55): Boolean =
        energyBetween(from, to) <= threshold

    fun describe(): String = "envelope=%.2f/步(%d点)".format(stepSeconds, envelope.size)

    companion object {
        private const val STEP_SECONDS = 0.2

        fun analyze(samples: FloatArray, sampleRate: Int, startSeconds: Double): StructureAnalyzer {
            val frames = samples.size / 2
            val stepFrames = (STEP_SECONDS * sampleRate).toInt().coerceAtLeast(1)
            val count = (frames / stepFrames).coerceAtLeast(1)
            val envelope = DoubleArray(count)
            for (index in 0 until count) {
                var sum = 0.0
                val start = index * stepFrames * 2
                val end = ((index + 1) * stepFrames * 2).coerceAtMost(samples.size)
                var sample = start
                while (sample < end) {
                    val value = samples[sample].toDouble()
                    sum += value * value
                    sample += 2 // 只取左声道，能量趋势足够
                }
                val framesInBlock = ((end - start) / 2).coerceAtLeast(1)
                envelope[index] = sqrt(sum / framesInBlock)
            }
            val sorted = envelope.sortedArray()
            val reference = if (sorted.isEmpty()) 0.0 else sorted[sorted.size / 2]
            return StructureAnalyzer(envelope, STEP_SECONDS, startSeconds, reference)
        }
    }
}
