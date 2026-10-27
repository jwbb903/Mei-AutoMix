package com.ljyh.mei.playback.automix

/**
 * AutoMix 数据模型。
 *
 * 算法移植自 HyPlayer-Nextgen（Gaimoydev/HyPlayer-Nextgen，
 * `HyPlayer/Services/Playback/AutoMix/AutoMixPlanner.cs`）的 MixPlan / MixTechnique。
 * 原实现基于 Windows AudioGraph，这里只保留与平台无关的决策与渲染描述。
 */

internal enum class MixTechnique {
    /** 等功率交叉淡化：最保守，任何两首歌都能用。 */
    Crossfade,

    /** 低频交换（bass swap）+ 等功率，两首歌 tempo 接近时使用。 */
    BeatMix,

    /** 高通扫频：A 从 300Hz 一路推到 18kHz 退场，B 在后半段进入。 */
    FilterCut,

    /** 专辑连播：首尾相接，完全不过渡。 */
    Gapless,
}

internal data class MixPlan(
    val technique: MixTechnique,
    /** 主源（A）开始退场的曲内位置，单位秒。 */
    val aExit: Double,
    /** 两首歌重叠时长，单位秒。 */
    val overlap: Double,
    /** 副源（B）进入时在 B 曲内的位置，单位秒。 */
    val bEntry: Double,
    /** B 的播放速率，1 = 不做时间伸缩。 */
    val rate: Double = 1.0,
    /** 回速时长（秒）。0 表示不回速。 */
    val rampBack: Double = 0.0,
    /** 人声交接点（0..1 的重叠进度），A 在此点后开始让位。 */
    val handoff: Double = 0.5,
    /** B 的增益修正（响度匹配）。 */
    val gainB: Double = 1.0,
    val bars: Int = 0,
    val reason: String = "",
) {
    val aFadeEnd: Double get() = aExit + overlap
    val bContentAtOverlapEnd: Double get() = bEntry + overlap * rate
}

internal data class AutoMixOptions(
    val maxOverlapSeconds: Double = 40.0,
    val preferredBars: Int = 16,
    val crossfadeSeconds: Double = 6.0,
    val allowTimeStretch: Boolean = true,
    val maxTempoDeviation: Double = 0.06,
    val albumContinuation: Boolean = false,
)

/**
 * 单曲分析结果。对应 C# 的 `TrackAnalysis`。
 *
 * 时间单位统一为秒，与 C# 一致；BeatNet 输出的是毫秒，构造时转换。
 */
internal data class TrackAnalysis(
    val duration: Double,
    val bpm: Double = 0.0,
    val beatConfidence: Double = 0.0,
    val beats: DoubleArray = DoubleArray(0),
    val downbeats: DoubleArray = DoubleArray(0),
    /** 第一个有声内容的位置；-1 表示未知。 */
    val soundStart: Double = -1.0,
    /** 最后一个有声内容的位置；-1 表示未知。 */
    val soundEnd: Double = -1.0,
    /** 前奏结束位置；-1 表示未知。 */
    val introEnd: Double = -1.0,
    /** 尾奏开始位置；-1 表示未知。 */
    val outroStart: Double = -1.0,
    /** 整曲响度（LUFS）；大于 -60 才认为是有效测量。 */
    val integratedLoudness: Double = -70.0,
    val key: String = "",
    val keyConfidence: Double = 0.0,
) {
    val hasBeatGrid: Boolean get() = beats.size >= 2 && bpm > 20.0
}
