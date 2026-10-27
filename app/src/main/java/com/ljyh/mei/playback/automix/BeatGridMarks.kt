package com.ljyh.mei.playback.automix

import com.ljyh.mei.playback.transition.BeatNetRhythm
import kotlin.math.abs

/**
 * 一首歌的拍网格（秒，绝对内容时间）。
 *
 * 优先来自 BeatNet 模型（Mei 自带的 `assets/beatnet/model_1_f32.bin`），
 * 模型失败时由 [BeatDetector] 的 DSP 结果兜底 —— 两者都规整成这个结构，
 * [AutoMixSession.alignToBeats] 只认它，不关心来源。
 */
internal class BeatGridMarks(
    val bpm: Double,
    val confidence: Double,
    val beats: DoubleArray,
    val downbeats: DoubleArray,
    val source: String,
) {
    /** 一个 4/4 小节 = 4 拍。 */
    val barSeconds: Double get() = 60.0 * 4 / bpm

    /** 拍网格覆盖的时间范围（秒）。 */
    val from: Double get() = beats.firstOrNull() ?: 0.0
    val to: Double get() = beats.lastOrNull() ?: 0.0

    fun nearestBeat(time: Double, tolerance: Double): Double? {
        val hit = nearest(beats, time) ?: return null
        return hit.takeIf { abs(it - time) <= tolerance }
    }

    /** 距 [time] 最近的小节起点（允许跨过 [time] 一小段）。 */
    fun nearestDownbeat(time: Double, tolerance: Double): Double? {
        val hit = nearest(downbeats, time) ?: return null
        return hit.takeIf { abs(it - time) <= tolerance }
    }

    /** [from] 之后（含）的第一个小节起点，最多看 [horizon] 秒。 */
    fun firstDownbeatAfter(from: Double, horizon: Double): Double? {
        if (downbeats.isEmpty()) return null
        val candidate = downbeats.firstOrNull { it >= from } ?: return null
        return candidate.takeIf { it - from <= horizon }
    }

    private fun nearest(values: DoubleArray, time: Double): Double? {
        if (values.isEmpty()) return null
        var best = values[0]
        var bestDistance = abs(values[0] - time)
        for (value in values) {
            val distance = abs(value - time)
            if (distance < bestDistance) {
                best = value
                bestDistance = distance
            }
        }
        return best
    }

    fun describe(): String = "%.1fBPM conf=%.2f bars=%d(%s)".format(bpm, confidence, downbeats.size, source)

    companion object {
        /** BeatNet 模型的输出（毫秒）→ 秒制网格。 */
        fun fromModel(rhythm: BeatNetRhythm, source: String = "model"): BeatGridMarks = BeatGridMarks(
            bpm = rhythm.bpm,
            confidence = rhythm.confidence,
            beats = DoubleArray(rhythm.beatsMs.size) { rhythm.beatsMs[it] / 1000.0 },
            downbeats = DoubleArray(rhythm.downbeatsMs.size) { rhythm.downbeatsMs[it] / 1000.0 },
            source = source,
        )

        /** [BeatDetector] 的 DSP 结果 → 同一结构（无小节点，用每 4 拍充当）。 */
        fun fromDetector(
            grid: BeatDetector.BeatGrid,
            startSeconds: Double,
            endSeconds: Double,
            source: String = "dsp",
        ): BeatGridMarks {
            val beats = grid.beatsIn(startSeconds * 1000, endSeconds * 1000).map { it / 1000.0 }.toDoubleArray()
            return BeatGridMarks(
                bpm = grid.bpm,
                confidence = grid.confidence,
                beats = beats,
                downbeats = beats.filterIndexed { index, _ -> index % 4 == 0 }.toDoubleArray(),
                source = source,
            )
        }
    }
}
