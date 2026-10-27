package com.ljyh.mei.playback.automix

import com.ljyh.mei.playback.transition.BeatNetTransitionPlan
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * AutoMix 与 Mei 双 deck 过渡之间的桥。
 *
 * ## 为什么需要这一层
 *
 * 上游 HyPlayer 的 AutoMix 直接把两条 PCM 流交给自己的 `MixedAudioProvider`
 * 渲染（[AutoMixEngine] 就是这个渲染器，已完整移植）。但 Mei 基于 Media3，
 * **每个 ExoPlayer 自带独立 AudioSink**，引擎无法把两个解码器的 PCM 抽到同一条
 * 输出里，除非替换整套 RenderersFactory。
 *
 * 因此这里采用「同一套决策曲线，落到 deck 级控制」的方案：
 * - 决策层（关键帧位置、BPM 匹配速率、技法、交接点）完全复用
 *   [AutoMixPlanner] / [MixPlan]；
 * - 表现层把引擎的等功率/低频交换/滤波扫频曲线换算成两个 deck 的音量包络，
 *   速率回落到 1.0 的过程交给 ExoPlayer 自带的 Sonic 时间伸缩（同样变速不变调）。
 *
 * 代价（明确记录）：没有 PCM 级的双流软限幅和真正的低频交换 EQ。
 * 一旦将来把 [AutoMixEngine] 接到自定义 AudioSink 上，这个桥可以直接退役。
 */
internal object AutoMixBridge {

    /** BeatNet 的节奏方案 → AutoMix 的混音方案。信息不足时返回 null（宁可不混）。 */
    fun planFromBeatNet(
        beat: BeatNetTransitionPlan?,
        outgoingDurationMs: Long,
        incomingDurationMs: Long,
    ): MixPlan? {
        if (beat == null) return null
        if (beat.confidence < 0.35) return null
        if (outgoingDurationMs <= 0L || incomingDurationMs <= 0L) return null

        val overlap = (beat.durationMs / 1000.0).coerceIn(1.5, 40.0)
        val rate = beat.incomingRate.toDouble().coerceIn(0.94, 1.06)
        // tempo 已经很接近时用低频交换，否则交给保守的等功率曲线。
        val technique = if (kotlin.math.abs(rate - 1.0) < 0.012) {
            MixTechnique.BeatMix
        } else {
            MixTechnique.Crossfade
        }
        return MixPlan(
            technique = technique,
            aExit = (beat.outgoingStartMs / 1000.0).coerceAtLeast(0.0),
            overlap = overlap,
            bEntry = (beat.incomingStartMs / 1000.0).coerceAtLeast(0.0),
            rate = rate,
            // 重叠期结束后用 2 秒把 B 拉回原速（对应引擎的 RampBack 阶段）。
            rampBack = 2.0,
            handoff = if (technique == MixTechnique.BeatMix) 0.55 else 0.5,
            gainB = 1.0,
            reason = "BeatNet confidence=${(beat.confidence * 100).toInt()}%",
        )
    }

    /**
     * BeatNet 没给出可用计划（置信度不够 / 两首歌 BPM 差 >10% / 模型没跑通）时的兜底方案。
     *
     * 这个方案不做拍点吸附，但 **仍然走 PCM 混音** —— [AutoMixSession] 解码后会用
     * 模型网格（若可用）或内置 DSP 再对齐一次拍点，所以听感依旧接近真正的 beatmix，
     * 而不是退化成固定时长的等功率交叉。
     */
    fun planSynthetic(outgoingDurationMs: Long, overlapMs: Long): MixPlan? {
        if (outgoingDurationMs < 20_000L) return null
        val overlap = (overlapMs / 1000.0).coerceIn(2.0, 12.0)
        // 结尾余量必须盖过「overlap + rampBack - 交接提前量」，否则 A 会先于
        // 交接点自然播完，被 setPauseAtEndOfMediaItems 自动暂停。
        // （handback 在 LEAD + overlap + rampBack - 0.9s，A 结尾在 LEAD + aExit + overlap + 0.6s，
        //   需要 aExit >= rampBack + 1.5s 才能保证 A 活到交接。）
        val rampBack = 2.0
        val tail = (rampBack + 1.5).coerceAtLeast(2.0)
        val aExit = (outgoingDurationMs / 1000.0 - overlap - tail).coerceAtLeast(0.0)
        if (aExit < 5.0) return null
        return MixPlan(
            technique = MixTechnique.BeatMix,
            aExit = aExit,
            overlap = overlap,
            bEntry = 1.5,
            rate = 1.0,
            rampBack = rampBack,
            handoff = 0.55,
            gainB = 1.0,
            reason = "PCM overlap without beat grid",
        )
    }

    /** A（正在离开的曲目）在重叠进度 [progress]（0..1）处应有的音量。 */
    fun gainOut(plan: MixPlan, progress: Double): Double {
        val x = progress.coerceIn(0.0, 1.0)
        val handoff = plan.handoff.coerceIn(0.0, 0.95)
        val release = if (x < handoff) 0.0 else (x - handoff) / (1 - handoff)
        return when (plan.technique) {
            MixTechnique.BeatMix -> cos(release * PI / 2)
            MixTechnique.FilterCut ->
                if (x < 0.7) 1.0 else cos((x - 0.7) / 0.3 * PI / 2)
            MixTechnique.Gapless -> if (x < 1.0) 1.0 else 0.0
            MixTechnique.Crossfade ->
                if (handoff < 0.1) {
                    cos(x * PI / 2)
                } else {
                    cos(release * PI / 2)
                }
        }
    }

    /** B（接上的曲目）在重叠进度 [progress] 处应有的音量。 */
    fun gainIn(plan: MixPlan, progress: Double): Double {
        val x = progress.coerceIn(0.0, 1.0)
        val handoff = plan.handoff.coerceIn(0.0, 0.95)
        val release = if (x < handoff) 0.0 else (x - handoff) / (1 - handoff)
        return when (plan.technique) {
            MixTechnique.BeatMix -> if (x < handoff) sin(x / handoff * PI / 2) else 1.0
            MixTechnique.FilterCut -> if (x < 0.5) 0.0 else sin((x - 0.5) / 0.5 * PI / 2)
            MixTechnique.Gapless -> if (x >= 1.0) 1.0 else 0.0
            MixTechnique.Crossfade ->
                if (handoff < 0.1) {
                    sin(x * PI / 2)
                } else {
                    if (x < handoff) 0.6 * sin(x / handoff * PI / 2)
                    else 0.6 + 0.4 * sin(release * PI / 2)
                }
        }
    }

    /**
     * B 的速率：重叠期保持 [MixPlan.rate]，结束后在 [MixPlan.rampBack] 秒内
     * 平滑回落到 1.0（与引擎 RampBack 阶段的 t²(3-2t) 一致）。
     */
    fun rateAt(plan: MixPlan, elapsedSecondsAfterOverlap: Double): Double {
        if (plan.rampBack <= 0.0) return 1.0
        val t = (elapsedSecondsAfterOverlap / plan.rampBack).coerceIn(0.0, 1.0)
        val smooth = t * t * (3 - 2 * t)
        return plan.rate + (1 - plan.rate) * smooth
    }
}
