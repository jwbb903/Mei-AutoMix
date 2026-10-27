package com.ljyh.mei.playback.automix

import kotlin.math.log10

/**
 * ITU-R BS.1770 的轻量实现：K 加权 + 门限块 → 积分响度（LUFS）。
 *
 * 为什么要它：两条音轨的母带响度能差 6~12 LU，硬切过去会听到明显的音量台阶。
 * 上游 HyPlayer 用 NAudio 的 LUFS 计量做这件事；这里只保留「积分响度」这一项，
 * 不需要真峰值与短时最大，所以可以写得很小。
 *
 * 系数是 48kHz 的规范值 —— [PcmRegionDecoder] 统一把区域重采样到 48k，
 * 所以不需要为其它采样率做双线性变换。
 */
internal object LoudnessMeter {

    /** 近静音时低于这个值就没有比较意义。 */
    const val SILENT_LUFS = -40.0

    // Stage 1：高架滤波
    private val SHELF_B = doubleArrayOf(1.53512485958697, -2.69169618940638, 1.19839281085285)
    private val SHELF_A = doubleArrayOf(1.0, -1.69065929318241, 0.73248077421585)

    // Stage 2：RLB 高通
    private val HIGHPASS_B = doubleArrayOf(1.0, -2.0, 1.0)
    private val HIGHPASS_A = doubleArrayOf(1.0, -1.99004745483398, 0.99007225036621)

    private const val BLOCK_SECONDS = 0.4
    private const val HOP_SECONDS = 0.1
    private const val ABSOLUTE_GATE_LUFS = -70.0
    private const val RELATIVE_GATE_LU = 10.0

    /**
     * @param samples 交错 PCM（float，[-1,1]）
     * @param channels 声道数（1 或 2）
     * @return 积分响度（LUFS），静音返回 [SILENT_LUFS]
     */
    fun integratedLufs(samples: FloatArray, sampleRate: Int, channels: Int): Double {
        require(sampleRate == 48_000) { "LoudnessMeter 只支持 48kHz，实际 $sampleRate" }
        require(channels in 1..2) { "只支持单/双声道，实际 $channels" }
        val frames = samples.size / channels
        if (frames == 0) return SILENT_LUFS

        // K 加权（两级 biquad，按声道独立状态）
        val weighted = Array(channels) { DoubleArray(frames) }
        for (channel in 0 until channels) {
            val shelfState = BiquadState()
            val highPassState = BiquadState()
            val target = weighted[channel]
            for (frame in 0 until frames) {
                val stage1 = biquad(samples[frame * channels + channel].toDouble(), SHELF_B, SHELF_A, shelfState)
                target[frame] = biquad(stage1, HIGHPASS_B, HIGHPASS_A, highPassState)
            }
        }

        val blockFrames = (BLOCK_SECONDS * sampleRate).toInt()
        val hopFrames = (HOP_SECONDS * sampleRate).toInt()
        val powers = ArrayList<Double>((frames / hopFrames).coerceAtLeast(1))

        if (frames < blockFrames) {
            powers += meanSquare(weighted, 0, frames, channels)
        } else {
            var start = 0
            while (start + blockFrames <= frames) {
                powers += meanSquare(weighted, start, blockFrames, channels)
                start += hopFrames
            }
        }

        // 绝对门限
        val aboveAbsolute = powers.filter { it > 1e-9 && lufsFromPower(it) > ABSOLUTE_GATE_LUFS }
        if (aboveAbsolute.isEmpty()) return SILENT_LUFS
        val gatedMean = aboveAbsolute.average()

        // 相对门限
        val threshold = gatedMean * Math.pow(10.0, -RELATIVE_GATE_LU / 10.0)
        val aboveRelative = aboveAbsolute.filter { it >= threshold }
        val finalMean = if (aboveRelative.isEmpty()) gatedMean else aboveRelative.average()
        return lufsFromPower(finalMean)
    }

    /** 两段音频的响度差（LU），用于把 A 压到与 B 同响度。 */
    fun trimDb(referenceLufs: Double, targetLufs: Double): Double {
        if (referenceLufs <= SILENT_LUFS || targetLufs <= SILENT_LUFS) return 0.0
        return (targetLufs - referenceLufs).coerceIn(-12.0, 6.0)
    }

    private fun meanSquare(weighted: Array<DoubleArray>, start: Int, count: Int, channels: Int): Double {
        var sum = 0.0
        for (channel in 0 until channels) {
            val data = weighted[channel]
            val end = (start + count).coerceAtMost(data.size)
            for (frame in start until end) {
                sum += data[frame] * data[frame]
            }
        }
        return sum / (count * channels).coerceAtLeast(1)
    }

    private fun lufsFromPower(meanSquare: Double): Double =
        if (meanSquare <= 0.0) SILENT_LUFS else -0.691 + 10.0 * log10(meanSquare)

    private class BiquadState {
        var x1 = 0.0
        var x2 = 0.0
        var y1 = 0.0
        var y2 = 0.0
    }

    private fun biquad(input: Double, b: DoubleArray, a: DoubleArray, state: BiquadState): Double {
        val output = b[0] * input + b[1] * state.x1 + b[2] * state.x2 -
            a[1] * state.y1 - a[2] * state.y2
        state.x2 = state.x1
        state.x1 = input
        state.y2 = state.y1
        state.y1 = output
        return output
    }
}
