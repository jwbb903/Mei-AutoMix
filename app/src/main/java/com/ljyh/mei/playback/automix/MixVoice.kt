package com.ljyh.mei.playback.automix

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * 混音中的一路"声音"。
 *
 * 移植自 HyPlayer-Nextgen `AutoMix/MixVoice.cs`。一个 voice 负责：
 * 1. 从 [MixRegion] 按 [rate] 取出（必要时时间伸缩）PCM；
 * 2. 应用过渡 EQ（高通扫频 / 低频架衰减）；
 * 3. 按 [targetGain] 做块内线性渐变的增益，并叠加到总线 mix。
 */
internal class MixVoice(
    private val region: MixRegion,
    startContentSeconds: Double,
    useStretcher: Boolean,
) {
    private val channels = region.channelCount
    private val sampleRate = region.sampleRate
    private val stretcher: WsolaStretcher? = if (useStretcher) WsolaStretcher(region) else null
    private val highPass = StereoBiquad()
    private val lowShelf = StereoBiquad()

    private var gain = 0.0
    private var dry = FloatArray(0)

    private var blendFramesTotal = 0
    private var blendFramesDone = -1
    private var blendRaw = FloatArray(0)

    /** 到原始 PCM 的交叉淡化做完后置位：此后完全绕过拉伸器。 */
    private var rawOnly = false

    var contentIndex: Double = region.indexOf(startContentSeconds).toDouble()
        private set

    var rate: Double = 1.0
    var targetGain: Double = 0.0
    var highPassHz: Double = 0.0
    var lowShelfDb: Double = 0.0

    val contentSeconds: Double get() = region.startSeconds + contentIndex / sampleRate
    val isStretching: Boolean get() = stretcher != null && blendFramesDone < 0 && !rawOnly
    val finished: Boolean get() = contentIndex >= region.frames - 1

    init {
        stretcher?.seek(contentIndex.toLong())
        setGainImmediate(0.0)
    }

    fun setGainImmediate(value: Double) {
        gain = value
        targetGain = value
    }

    /** 结束时间伸缩，用 50ms 交叉淡入到原始 PCM（C# 同名方法）。 */
    fun beginRawBlend(seconds: Double) {
        if (stretcher == null || blendFramesDone >= 0 || rawOnly) return
        blendFramesTotal = max(1, (seconds * sampleRate).toInt())
        blendFramesDone = 0
        rate = 1.0
    }

    /** 叠加渲染 [frames] 帧到 [mix]。 */
    fun render(mix: FloatArray, mixOffsetFrames: Int, frames: Int) {
        if (frames <= 0) return
        ensureCapacity(frames)
        val channelsHere = channels

        val activeStretcher = stretcher
        if (activeStretcher != null && !rawOnly) {
            // 关键：raw 交叉淡化的读数必须和拉伸器**本块**产出对齐（用推进前的
            // contentIndex），否则会和下一块的纯 raw 读数相差一个 quantum，
            // 交接瞬间就是一记明显的咔哒声。
            val startIndex = contentIndex
            activeStretcher.render(dry, frames, rate)
            contentIndex = startIndex + frames * rate
            if (blendFramesDone >= 0) {
                readRaw(blendRaw, frames, startIndex)
                for (i in 0 until frames) {
                    val weight = min(1.0, (blendFramesDone + i).toDouble() / blendFramesTotal)
                    for (channel in 0 until channelsHere) {
                        val index = i * channelsHere + channel
                        dry[index] = (dry[index] * (1 - weight) + blendRaw[index] * weight).toFloat()
                    }
                }
                blendFramesDone += frames
            }
        } else {
            // raw 直读路径也必须按 rate 推进：beginRawBlend 只是"不再做时间伸缩"，
            // 并不改变这条轨相对输出的播放速率。若这里退回 contentIndex += frames，
            // B 会在交叉淡化结束的瞬间从 rate 突变成 1.0 —— BPM 不同的两首歌
            // 每秒漂移几十毫秒，交接时 contentSecondsB 与 ExoPlayer B 的真实
            // 位置就对不上了，表现为切歌跳音/卡顿。
            readRawInterpolated(dry, frames, contentIndex)
            contentIndex += frames * rate
            if (blendFramesDone >= 0) blendFramesDone += frames
        }

        applyFilters(frames)
        applyGain(mix, mixOffsetFrames, frames)
        if (blendFramesTotal > 0 && blendFramesDone >= blendFramesTotal) {
            // 淡化完成：彻底切到原始 PCM，不再回到拉伸器（否则会反复重入淡化）。
            blendFramesDone = -1
            rawOnly = true
        }
    }

    /**
     * 输出侧淡出（C# `RenderRampOut`），用于交接瞬间避免咔哒声。
     *
     * 两个坑都踩过：
     * 1. 斜坡必须是**跨 quantum 连续**的。原来只在单个 quantum 内做 1ms 斜坡，
     *    等于一帧内硬切到 0 —— 那一下就是可闻的咔哒声。改成 20ms 斜坡，
     *    由调用方按 quantum 逐块喂进来，[rampOutRemaining] 跨块递减。
     * 2. [readRaw] 必须换成插值版。交接时 B 通常还带着 rate（变速播放中），
     *    取整读位会叠加一层细碎噪声；同时 contentIndex 也要按 rate 推进，
     *    否则斜坡期间内容位置和真实播放位置脱钩。
     */
    private var rampOutRemaining = 0

    fun beginRampOut(seconds: Double) {
        rampOutRemaining = max(1, (seconds * sampleRate).toInt())
    }

    fun renderRampOut(mix: FloatArray, mixOffsetFrames: Int, frames: Int) {
        ensureCapacity(frames)
        readRawInterpolated(dry, frames, contentIndex)
        val rampFrames = min(frames, rampOutRemaining)
        for (i in 0 until rampFrames) {
            val g = gain * (1 - (i + 1).toDouble() / rampFrames)
            for (channel in 0 until channels) {
                val index = i * channels + channel
                mix[(mixOffsetFrames + i) * channels + channel] += (dry[index] * g).toFloat()
            }
        }
        contentIndex += frames * rate
        rampOutRemaining = max(0, rampOutRemaining - frames)
        gain = 0.0
        targetGain = 0.0
    }

    private fun applyFilters(frames: Int) {
        if (highPassHz > 20) {
            highPass.setHighPass(highPassHz, sampleRate.toDouble())
        } else {
            highPass.setBypass()
        }
        highPass.process(dry, frames)
        if (lowShelfDb != 0.0) {
            lowShelf.setLowShelf(180.0, lowShelfDb, sampleRate.toDouble())
        } else {
            lowShelf.setBypass()
        }
        lowShelf.process(dry, frames)
    }

    private fun applyGain(mix: FloatArray, mixOffsetFrames: Int, frames: Int) {
        val startGain = gain
        val endGain = targetGain
        for (i in 0 until frames) {
            val t = (i + 1).toDouble() / frames
            val g = startGain + (endGain - startGain) * t
            for (channel in 0 until channels) {
                val index = i * channels + channel
                mix[(mixOffsetFrames + i) * channels + channel] += (dry[index] * g).toFloat()
            }
        }
        gain = endGain
    }

    private fun readRaw(destination: FloatArray, frames: Int, index: Double) {
        val start = index.roundToLong()
        for (i in 0 until frames) {
            val source = start + i
            for (channel in 0 until channels) {
                destination[i * channels + channel] =
                    if (source < 0 || source >= region.frames) 0f
                    else region.sampleAt(source, channel).toFloat()
            }
        }
    }

    /**
     * 按连续（非整数）位置读 PCM，帧间做线性插值。
     *
     * [readRaw] 用的是 `index.roundToLong()`，当 [rate] != 1.0 时每帧前进量是
     * 小数，取整后会形成 1,1,2,2,2,1... 的读位抖动 —— 这是叠加在速率漂移之上
     * 的细碎咔哒声来源。插值让读取位置连续，彻底消除这一类噪声。
     */
    private fun readRawInterpolated(destination: FloatArray, frames: Int, index: Double) {
        if (rate == 1.0) {
            readRaw(destination, frames, index)
            return
        }
        val last = region.frames - 1
        for (i in 0 until frames) {
            val position = index + i * rate
            val base = position.toInt()
            val frac = position - base
            for (channel in 0 until channels) {
                val index0 = i * channels + channel
                val a = region.safeSample(base, channel)
                if (frac < 1e-9 || base >= last) {
                    destination[index0] = a.toFloat()
                } else {
                    val b = region.safeSample(base + 1, channel)
                    destination[index0] = (a + (b - a) * frac).toFloat()
                }
            }
        }
    }

    /** 释放内部缓冲（与 C# `MixVoice.Dispose` 对应）。 */
    fun dispose() {
        dry = FloatArray(0)
        blendRaw = FloatArray(0)
        blendFramesDone = -1
    }

    private fun ensureCapacity(frames: Int) {
        val needed = frames * channels
        if (dry.size < needed) dry = FloatArray(needed)
        if (blendFramesDone >= 0 && blendRaw.size < needed) blendRaw = FloatArray(needed)
    }
}
