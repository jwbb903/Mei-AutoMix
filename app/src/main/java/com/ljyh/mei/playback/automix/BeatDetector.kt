package com.ljyh.mei.playback.automix

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 节拍检测：把一段 PCM 变成「拍点时间表」。
 *
 * 上游 HyPlayer 用的是 BeatThis（ONNX 模型）做这件事；这里用不需要模型、
 * 只依赖信号本身的经典做法，效果够用且能离线单测：
 *
 * 1. **谱通量（spectral flux）** → 起音包络：每帧做 FFT，累加「比上一帧响」的频段增量，
 *    把它当作「有新声音进来」的强度。
 * 2. **自相关求周期** → 在 60~200 BPM 对应的滞后范围里找自相关峰值，得到拍周期。
 * 3. **梳状搜索求相位** → 固定周期后，试一遍相位偏移，挑让拍点落在起音峰上的那个。
 *
 * 输出的是等间隔拍点（因为流行乐绝大多数是恒定 BPM）。AutoMix 用它做两件事：
 * 让两条音轨的拍点同相、把淡化区间对齐到整数拍，听感自然度的差别就来自这里。
 */
internal object BeatDetector {

    /** 结果：拍周期（毫秒）与首拍时间（相对输入起点，毫秒）。 */
    data class BeatGrid(
        val periodMs: Double,
        val firstBeatMs: Double,
        val bpm: Double,
        val confidence: Double,
    ) {
        fun beatsIn(startMs: Double, endMs: Double): List<Double> {
            if (periodMs <= 0.0) return emptyList()
            val result = ArrayList<Double>()
            var beat = firstBeatMs
            while (beat < startMs) beat += periodMs
            while (beat <= endMs) {
                result += beat
                beat += periodMs
            }
            return result
        }

        /** 离给定时间最近的拍点。 */
        fun nearest(timeMs: Double): Double {
            if (periodMs <= 0.0) return timeMs
            val k = ((timeMs - firstBeatMs) / periodMs).roundToInt()
            return firstBeatMs + k * periodMs
        }
    }

    private const val FRAME = 1024
    private const val HOP = 256
    private const val MIN_BPM = 60.0
    private const val MAX_BPM = 200.0

    /**
     * @param mono 单声道样本（[-1,1]），长度为采样率 × 秒数
     * @param sampleRate 采样率
     */
    fun detect(mono: FloatArray, sampleRate: Int): BeatGrid? {
        if (mono.size < sampleRate * 2) return null
        // 周期用全频段（稳健），相位用低频段（底鼓/贝斯能量决定拍点位置）。
        // 只看全频段时，反拍上的踩镲/高频会和对数谱通量里的底鼓打平,
        // 梳状搜索会锁到反拍（实测 128BPM 时相位偏半拍）。低频段用线性幅度,
        // 让底鼓的能量优势直接体现出来。
        val envelope = onsetEnvelope(mono, sampleRate, maxBinHz = null, logScale = true)
        if (envelope.size < 16) return null
        val lowEnvelope = onsetEnvelope(mono, sampleRate, maxBinHz = 250.0, logScale = false)
        val lowEnergy = lowEnvelope.sum()
        val phaseEnvelope = if (lowEnergy > 1e-6) lowEnvelope else envelope

        val hopSeconds = HOP.toDouble() / sampleRate
        val minLag = (60.0 / MAX_BPM / hopSeconds).roundToInt().coerceAtLeast(2)
        val maxLag = (60.0 / MIN_BPM / hopSeconds).roundToInt()
        if (maxLag <= minLag + 2 || maxLag >= envelope.size) return null

        val mean = envelope.average()
        val centered = DoubleArray(envelope.size) { envelope[it] - mean }
        var variance = 0.0
        centered.forEach { variance += it * it }
        variance /= centered.size
        if (variance <= 1e-12) return null

        // 自相关：对每个候选滞后算归一化相关系数。
        var bestLag = -1
        var bestScore = Double.NEGATIVE_INFINITY
        val scores = DoubleArray(maxLag + 2)
        for (lag in minLag..maxLag) {
            var sum = 0.0
            var normA = 0.0
            var normB = 0.0
            var index = 0
            while (index + lag < centered.size) {
                val a = centered[index]
                val b = centered[index + lag]
                sum += a * b
                normA += a * a
                normB += b * b
                index++
            }
            val denominator = sqrt(normA * normB)
            val score = if (denominator > 1e-12) sum / denominator else 0.0
            scores[lag] = score
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        if (bestLag < 0 || bestScore <= 0.02) return null

        // 抛物线插值细化周期，避免量化到 256 样本的 hop。
        val refinedLag = parabolicPeak(scores, bestLag).coerceIn(minLag.toDouble(), maxLag.toDouble())
        val periodFrames = refinedLag
        val periodMs = periodFrames * hopSeconds * 1000.0

        // 梳状搜索相位：让拍点尽量落在起音包络的峰上。
        var bestPhase = 0.0
        var bestPhaseScore = Double.NEGATIVE_INFINITY
        val phaseSteps = 64
        for (step in 0 until phaseSteps) {
            val phase = step.toDouble() / phaseSteps * periodFrames
            var score = 0.0
            var k = 0
            while (true) {
                val index = (phase + k * periodFrames).roundToInt()
                if (index >= phaseEnvelope.size) break
                // 取峰值附近的局部最大，容忍半帧抖动。
                val lo = (index - 2).coerceAtLeast(0)
                val hi = (index + 2).coerceAtMost(phaseEnvelope.size - 1)
                var local = Double.NEGATIVE_INFINITY
                for (probe in lo..hi) local = maxOf(local, phaseEnvelope[probe])
                score += local
                k++
            }
            if (score > bestPhaseScore) {
                bestPhaseScore = score
                bestPhase = phase
            }
        }

        return BeatGrid(
            periodMs = periodMs,
            firstBeatMs = bestPhase * hopSeconds * 1000.0,
            bpm = 60_000.0 / periodMs,
            confidence = bestScore,
        )
    }

    /** 谱通量起音包络（逐帧）。[maxBinHz] 非空时只累加该频率以下的频段。 */
    private fun onsetEnvelope(
        mono: FloatArray,
        sampleRate: Int,
        maxBinHz: Double?,
        logScale: Boolean,
    ): DoubleArray {
        val fft = Fft(FRAME)
        val window = DoubleArray(FRAME) { 0.5 - 0.5 * cos(2.0 * Math.PI * it / (FRAME - 1)) }
        val real = DoubleArray(FRAME)
        val imag = DoubleArray(FRAME)
        val previous = DoubleArray(FRAME / 2)
        val binLimit = maxBinHz?.let { hz ->
            ((hz / sampleRate) * FRAME).roundToInt().coerceIn(2, FRAME / 2 - 1)
        } ?: (FRAME / 2 - 1)
        val frames = (mono.size - FRAME) / HOP + 1
        if (frames <= 0) return DoubleArray(0)
        val envelope = DoubleArray(frames)
        var previousReady = false
        for (frameIndex in 0 until frames) {
            val offset = frameIndex * HOP
            for (index in 0 until FRAME) {
                real[index] = mono[offset + index] * window[index]
                imag[index] = 0.0
            }
            fft.transform(real, imag)
            var flux = 0.0
            for (bin in 1..binLimit) {
                val magnitude = sqrt(real[bin] * real[bin] + imag[bin] * imag[bin])
                val value = if (logScale) ln(1.0 + 1000.0 * magnitude) else magnitude
                if (previousReady) {
                    val delta = value - previous[bin]
                    if (delta > 0) flux += delta
                }
                previous[bin] = value
            }
            envelope[frameIndex] = flux
            previousReady = true
        }
        // 去掉前几帧的冷启动。
        for (index in 0 until minOf(4, envelope.size)) envelope[index] = 0.0
        // 轻度平滑，抑制单帧毛刺。
        val smoothed = DoubleArray(envelope.size)
        for (index in envelope.indices) {
            val lo = (index - 1).coerceAtLeast(0)
            val hi = (index + 1).coerceAtMost(envelope.size - 1)
            var sum = 0.0
            for (probe in lo..hi) sum += envelope[probe]
            smoothed[index] = sum / (hi - lo + 1)
        }
        return smoothed
    }

    private fun parabolicPeak(values: DoubleArray, index: Int): Double {
        val left = values[(index - 1).coerceAtLeast(0)]
        val center = values[index]
        val right = values[(index + 1).coerceAtMost(values.size - 1)]
        val denominator = left - 2 * center + right
        if (abs(denominator) < 1e-12) return index.toDouble()
        return index + 0.5 * (left - right) / denominator
    }

    /** 迭代式 radix-2 FFT（原地，输入长度必须是 2 的幂）。 */
    private class Fft(private val size: Int) {
        private val cosTable = DoubleArray(size / 2) { cos(2.0 * Math.PI * it / size) }
        private val sinTable = DoubleArray(size / 2) { sin(2.0 * Math.PI * it / size) }

        fun transform(real: DoubleArray, imag: DoubleArray) {
            var j = 0
            for (i in 1 until size) {
                var bit = size shr 1
                while (j and bit != 0) {
                    j = j xor bit
                    bit = bit shr 1
                }
                j = j or bit
                if (i < j) {
                    real[i] = real[j].also { real[j] = real[i] }
                    imag[i] = imag[j].also { imag[j] = imag[i] }
                }
            }
            var length = 2
            while (length <= size) {
                val half = length / 2
                val step = size / length
                var start = 0
                while (start < size) {
                    var k = 0
                    var index = start
                    while (k < half) {
                        val cosValue = cosTable[k * step]
                        val sinValue = sinTable[k * step]
                        val evenReal = real[index]
                        val evenImag = imag[index]
                        val oddReal = real[index + half]
                        val oddImag = imag[index + half]
                        val rotatedReal = oddReal * cosValue + oddImag * sinValue
                        val rotatedImag = oddImag * cosValue - oddReal * sinValue
                        real[index] = evenReal + rotatedReal
                        imag[index] = evenImag + rotatedImag
                        real[index + half] = evenReal - rotatedReal
                        imag[index + half] = evenImag - rotatedImag
                        index++
                        k++
                    }
                    start += length
                }
                length = length shl 1
            }
        }
    }
}
