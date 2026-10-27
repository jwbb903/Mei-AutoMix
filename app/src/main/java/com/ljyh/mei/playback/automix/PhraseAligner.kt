package com.ljyh.mei.playback.automix

import kotlin.math.abs

/**
 * 换句点选择：在拍网格上找「乐句边界 + 低能量」的落点。
 *
 * DJ 换句看的是 8/16 小节（32/64 拍），而不是单小节。只按 4 拍对齐的话，
 * 混合窗口经常会压在一个乐句的正中间，听感就是「切进来太急」。
 *
 * 这里按优先级从 16 → 8 → 4 小节枚举候选小节线，用三项打分：
 * - 偏离目标点的距离（越接近「剩余时间够」的位置越好）
 * - 该窗口的能量（越低越好 —— 尾奏/间奏里换最自然）
 * - 乐句长度的偏好（16 小节最好，8 次之，4 最后）
 */
internal object PhraseAligner {

    data class Choice(
        val exit: Double,
        val bars: Int,
        val energy: Double,
        val score: Double,
    )

    private val PHRASE_BARS = intArrayOf(16, 8, 4)

    fun chooseExit(
        target: Double,
        grid: BeatGridMarks,
        structure: StructureAnalyzer,
        aStart: Double,
        aEnd: Double,
        overlap: Double,
    ): Choice? {
        val downbeats = grid.downbeats
        if (downbeats.isEmpty()) return null
        val lowerBound = aStart + 1.5
        val upperBound = aEnd - overlap - 0.3
        if (upperBound <= lowerBound) return null

        var best: Choice? = null
        var bestPhrase: Choice? = null
        for (phraseBars in PHRASE_BARS) {
            for ((index, time) in downbeats.withIndex()) {
                if (index % phraseBars != 0) continue
                if (time < lowerBound || time > upperBound) continue
                val energy = structure.energyBetween(time, (time + overlap).coerceAtMost(aEnd))
                val drift = abs(time - target) / 4.0
                val phrasePenalty = when (phraseBars) {
                    16 -> 0.0
                    8 -> 0.25
                    else -> 0.55
                }
                val score = drift + energy * 1.5 + phrasePenalty
                val choice = Choice(time, phraseBars, energy, score)
                if (best == null || score < best.score) best = choice
                // 只在「低能量」候选里再挑一个：尾奏优先
                if (energy <= 0.55 && (bestPhrase == null || score < bestPhrase.score)) {
                    bestPhrase = choice
                }
            }
        }
        return bestPhrase ?: best
    }
}
