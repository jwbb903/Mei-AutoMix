package com.ljyh.mei.playback.automix

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * AutoMix 混音规划器。
 *
 * 逐条移植自 HyPlayer-Nextgen `AutoMix/AutoMixPlanner.cs`（作者 Gaimoydev，
 * 原实现为 C#）。决策顺序、阈值、回退条件均与上游保持一致，差异只在数据来源：
 *
 * - C# 的 BPM/节拍来自 `BeatTracker`（DSP 自研），Mei 用 BeatNet 神经模型输出；
 * - C# 的人声区间来自歌词时间轴 + 模型融合，Mei 由 [VocalActivity] 提供；
 * - C# 的响度是本地积分的 LUFS，Mei 用网易云的 `TrackAudioFeatures` 或本地估算。
 */
internal object AutoMixPlanner {

    fun plan(
        a: TrackAnalysis,
        b: TrackAnalysis,
        vocalsA: List<VocalSpan>,
        vocalsB: List<VocalSpan>,
        options: AutoMixOptions,
    ): MixPlan {
        val soundEndA = if (a.soundEnd > 0) a.soundEnd else a.duration
        val soundStartB = maxOf(0.0, b.soundStart)

        if (options.albumContinuation && a.duration - soundEndA < 0.4 && soundStartB < 0.4) {
            return MixPlan(
                technique = MixTechnique.Gapless,
                aExit = a.duration,
                overlap = 0.0,
                bEntry = 0.0,
                reason = "album continuation",
            )
        }

        val gainB = loudnessMatch(a, b)
        val vocalEndA = lastVocalEnd(vocalsA, soundEndA)

        val beatOk = a.hasBeatGrid && b.hasBeatGrid &&
            a.beatConfidence >= 0.55 && b.beatConfidence >= 0.55 &&
            a.downbeats.size >= 8 && b.downbeats.size >= 8
        if (!beatOk) {
            return crossfade(vocalsB, soundEndA, soundStartB, vocalEndA, gainB, options, "beat grid unreliable")
        }

        val (bestRate, multiplier) = bestRate(a.bpm, b.bpm)
        val maxDeviation = if (options.allowTimeStretch) options.maxTempoDeviation else 0.008
        var technique = if (abs(bestRate - 1) <= maxDeviation) MixTechnique.BeatMix else MixTechnique.FilterCut
        var rate = bestRate
        if (technique == MixTechnique.FilterCut) rate = 1.0
        if (!options.allowTimeStretch) rate = 1.0

        val beat = 60 / a.bpm
        val bar = 4 * beat
        val outroBars = if (a.outroStart > 0) (soundEndA - a.outroStart) / bar else 8.0
        val firstDownbeatB = b.downbeats.firstOrNull { it >= soundStartB - 0.05 } ?: 0.0
        val barB = 4 * 60 / (b.bpm * multiplier)
        val introBars = if (b.introEnd > firstDownbeatB) (b.introEnd - firstDownbeatB) / barB else 8.0

        var limit = min(options.preferredBars.toDouble(), floor(options.maxOverlapSeconds / bar))
        limit = min(limit, maxOf(4.0, outroBars))
        limit = min(limit, maxOf(4.0, introBars))

        val keyDistance = HarmonicKey.camelotDistance(a.key, b.key)
        val keyClash = keyDistance > 2 && a.keyConfidence > 0.03 && b.keyConfidence > 0.03
        if (keyClash || technique == MixTechnique.FilterCut) limit = min(limit, 8.0)

        val longest = snapBars(limit)
        if (longest < 4 || longest * bar > options.maxOverlapSeconds) {
            return crossfade(vocalsB, soundEndA, soundStartB, vocalEndA, gainB, options, "overlap too short for beat mix")
        }

        val reason = mutableListOf<String>()
        reason += "bpm ${format(a.bpm, 1)}->${format(b.bpm, 1)} x${format(multiplier, 2)}"
        reason += "rate ${format(rate, 3)}"
        if (keyClash) reason += "key distance $keyDistance"
        if (vocalEndA > 0) reason += "vocals end ${format(vocalEndA, 1)}s"
        val tolerance = min(beat, 0.8)

        fun build(overlap: Double, span: String, exit: Double, handoff: Double): MixPlan {
            val entryB = chooseEntry(b, vocalsB, firstDownbeatB, overlap * rate, barB)
            if (entryB > firstDownbeatB + 0.05) reason += "skip intro to ${format(entryB, 1)}s"
            reason += span
            return MixPlan(
                technique = technique,
                aExit = exit,
                overlap = overlap,
                bEntry = entryB,
                rate = rate,
                rampBack = if (abs(rate - 1) > 0.0005) min(16 * barB, 20.0) else 0.0,
                handoff = handoff,
                gainB = gainB,
                bars = (overlap / bar).roundToInt(),
                reason = reason.joinToString(", "),
            )
        }

        if (technique == MixTechnique.BeatMix) {
            var bars = longest
            while (bars >= 2) {
                val overlap = bars * bar
                var exit = chooseExit(a, soundEndA, overlap, Double.MIN_VALUE)
                if (exit < 0 || vocalEndA + beat > exit + overlap) {
                    val covering = chooseCoveringExit(a, overlap, vocalEndA + beat, a.duration + bar)
                    if (covering >= 0) {
                        exit = covering
                    } else if (vocalEndA > exit + overlap + tolerance) {
                        exit = -1.0
                    }
                }
                if (exit < 0) {
                    bars = if (bars > 4) bars / 2 else bars - 1
                    continue
                }
                val handoff = if (vocalEndA > exit) clamp((vocalEndA - exit) / overlap, 0.5, 0.9) else 0.5
                val bOverlap = overlap * rate
                val entryB = chooseEntry(b, vocalsB, firstDownbeatB, bOverlap, barB)
                val audible = overlap * (handoff + 0.7 * (1 - handoff))
                val clash = vocalClash(vocalsA, exit, exit + audible, vocalsB, entryB, entryB + bOverlap, rate)
                if (clash > 1) {
                    bars = if (bars > 4) bars / 2 else bars - 1
                    continue
                }
                return build(overlap, "$bars bars", exit, handoff)
            }
        }

        val shortest = if (technique == MixTechnique.BeatMix) 1 else 4
        var bars = longest
        while (bars >= shortest) {
            val exit = chooseExit(a, soundEndA, bars * bar, vocalEndA - tolerance)
            if (exit >= 0) return build(bars * bar, "$bars bars", exit, 0.5)
            bars = if (bars > 4) bars / 2 else bars - 1
        }

        if (technique == MixTechnique.FilterCut) {
            val from = maxOf(vocalEndA - tolerance, soundEndA - longest * bar)
            val exit = a.beats.firstOrNull { it >= from } ?: -1.0
            val beatCount = if (exit < 0) 0 else floor((soundEndA - exit) / beat).toInt()
            if (beatCount >= 2) return build(beatCount * beat, "$beatCount beats", exit, 0.5)
        }

        return crossfade(vocalsB, soundEndA, soundStartB, vocalEndA, gainB, options, "vocals run to the end")
    }

    private fun crossfade(
        vocalsB: List<VocalSpan>,
        soundEndA: Double,
        soundStartB: Double,
        vocalEndA: Double,
        gainB: Double,
        options: AutoMixOptions,
        baseReason: String,
    ): MixPlan {
        var overlap = clamp(options.crossfadeSeconds, 2.0, 12.0)
        overlap = min(overlap, maxOf(1.0, soundEndA - 1))
        var exit = maxOf(0.0, soundEndA - overlap)
        var handoff = 0.0
        var reason = baseReason
        if (vocalEndA > exit) {
            val lead = clamp(firstVocalStart(vocalsB, soundStartB) - soundStartB, 0.0, 3.0)
            exit = maxOf(exit, min(vocalEndA - lead, soundEndA - 1.2))
            overlap = soundEndA - exit
            handoff = clamp((vocalEndA - exit) / overlap, 0.0, 0.8)
            reason += ", vocals end ${format(vocalEndA, 1)}s"
        }
        return MixPlan(
            technique = MixTechnique.Crossfade,
            aExit = exit,
            overlap = overlap,
            bEntry = soundStartB,
            rate = 1.0,
            handoff = handoff,
            gainB = gainB,
            reason = reason,
        )
    }

    private fun lastVocalEnd(vocals: List<VocalSpan>, soundEnd: Double): Double {
        var last = -1.0
        for (span in vocals) {
            if (span.end - span.start >= 0.8 && span.start < soundEnd && span.end > soundEnd - 60 && span.end > last) {
                last = span.end
            }
        }
        return min(last, soundEnd)
    }

    private fun firstVocalStart(vocals: List<VocalSpan>, soundStart: Double): Double {
        var first = Double.MAX_VALUE
        for (span in vocals) {
            if (span.end - span.start >= 0.4 && span.start >= soundStart - 0.5 && span.start < first) {
                first = span.start
            }
        }
        return first
    }

    private fun bestRate(bpmA: Double, bpmB: Double): Pair<Double, Double> {
        if (bpmB <= 0.0) return 1.0 to 1.0
        var bestRate = bpmA / bpmB
        var bestMultiplier = 1.0
        for (multiplier in doubleArrayOf(2.0, 0.5)) {
            val candidate = bpmA / (bpmB * multiplier)
            if (abs(candidate - 1) < abs(bestRate - 1)) {
                bestRate = candidate
                bestMultiplier = multiplier
            }
        }
        return bestRate to bestMultiplier
    }

    private fun snapBars(bars: Double): Int {
        for (candidate in intArrayOf(32, 16, 8, 4)) {
            if (bars >= candidate) return candidate
        }
        return 0
    }

    private fun chooseExit(a: TrackAnalysis, soundEndA: Double, overlap: Double, earliest: Double): Double {
        val downbeats = a.downbeats
        val anchor = if (a.outroStart > 0) downbeats.indexOfFirst { it >= a.outroStart - 0.05 } else -1
        val limit = soundEndA - overlap
        val minimum = maxOf(maxOf(0.0, a.duration - 45), earliest)
        val candidates = mutableListOf<Int>()
        for (i in downbeats.indices.reversed()) {
            if (downbeats[i] > limit + 0.05) continue
            if (downbeats[i] < minimum) break
            candidates.add(i)
        }
        if (candidates.isEmpty()) return -1.0
        val phase = if (anchor >= 0) anchor else 0
        val phrase = candidates.firstOrNull { ((it - phase) % 4 + 4) % 4 == 0 } ?: -1
        return downbeats[if (phrase >= 0) phrase else candidates[0]]
    }

    private fun chooseCoveringExit(a: TrackAnalysis, overlap: Double, cover: Double, latestEnd: Double): Double {
        val minimum = maxOf(0.0, a.duration - 45)
        for (downbeat in a.downbeats) {
            if (downbeat < minimum || downbeat + overlap < cover) continue
            return if (downbeat + overlap <= latestEnd) downbeat else -1.0
        }
        return -1.0
    }

    private fun chooseEntry(
        b: TrackAnalysis,
        vocalsB: List<VocalSpan>,
        firstDownbeat: Double,
        bOverlap: Double,
        barB: Double,
    ): Double {
        val firstVocal = firstVocalStart(vocalsB, firstDownbeat)
        if (firstVocal == Double.MAX_VALUE) return firstDownbeat
        if (firstVocal - firstDownbeat < bOverlap + 2 * barB) return firstDownbeat
        val downbeats = b.downbeats
        val vocalBar = downbeats.indexOfLast { it <= firstVocal + barB * 0.25 }
        if (vocalBar < 0) return firstDownbeat
        val entryIndex = vocalBar - maxOf(1, (bOverlap / barB).roundToInt())
        if (entryIndex < 0) return firstDownbeat
        val entry = downbeats[entryIndex]
        if (entry <= firstDownbeat + barB || entry > b.duration * 0.4) return firstDownbeat
        return entry
    }

    /**
     * 人声重叠时长（秒）。C# 里同一个函数返回累计冲突秒数，planner 只要 >1s 就放弃这一档。
     */
    private fun vocalClash(
        vocalsA: List<VocalSpan>,
        aStart: Double,
        aEnd: Double,
        vocalsB: List<VocalSpan>,
        bStart: Double,
        bEnd: Double,
        rate: Double,
    ): Double {
        if (vocalsA.isEmpty() || vocalsB.isEmpty()) return 0.0
        var total = 0.0
        for (va in vocalsA) {
            val s1 = maxOf(va.start, aStart)
            val e1 = min(va.end, aEnd)
            if (e1 <= s1) continue
            for (vb in vocalsB) {
                val s2 = maxOf(vb.start, bStart)
                val e2 = min(vb.end, bEnd)
                if (e2 <= s2) continue
                val outS2 = aStart + (s2 - bStart) / rate
                val outE2 = aStart + (e2 - bStart) / rate
                val overlap = min(e1, outE2) - maxOf(s1, outS2)
                if (overlap > 0) total += overlap
            }
        }
        return total
    }

    private fun loudnessMatch(a: TrackAnalysis, b: TrackAnalysis): Double {
        if (a.integratedLoudness <= -60 || b.integratedLoudness <= -60) return 1.0
        val db = clamp(a.integratedLoudness - b.integratedLoudness, -6.0, 6.0)
        return 10.0.pow(db / 20)
    }

    private fun format(value: Double, digits: Int): String =
        if (digits <= 0) ceil(value).toLong().toString()
        else String.format("%.${digits}f", value)
}
