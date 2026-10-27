package com.ljyh.mei.playback.automix

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sign
import kotlin.math.sin

/**
 * 混音阶段状态机，与 C# `AutoMixStage` 一一对应。
 *
 * Waiting   等 A 播到交接点
 * Handover  已经接管渲染，等 A 播到预定退场点
 * Overlap   两首歌同时出声，按技法做增益/EQ 曲线
 * RampBack  A 已结束，B 从拉伸速率回落到 1.0
 * Aligning  用 tap 对齐 B 的节点位置，对齐后交还播放引擎
 * Done/Aborted 结束
 */
internal enum class AutoMixStage { Waiting, Handover, Overlap, RampBack, Aligning, Done, Aborted }

/**
 * 混音引擎需要播放器提供的最小能力（C# `IAutoMixHost` 的 Kotlin 版）。
 *
 * Mei 侧由 [AutoMixController] 用两个真实 ExoPlayer 实例实现：
 * A 是被淡出的旧曲、B 是接上的新曲。
 */
internal interface AutoMixHost {
    /** 每次回调渲染的帧数（ExoPlayer 用 1024 帧的 AudioTrack 缓冲）。 */
    val samplesPerQuantum: Int

    fun setVolume(voiceId: Int, volume: Double)
    fun setSpeed(voiceId: Int, speed: Double)
    fun play(voiceId: Int)
    fun seek(voiceId: Int, positionSeconds: Double)
}

/**
 * AutoMix 渲染引擎。
 *
 * 逐行移植自 HyPlayer-Nextgen `AutoMix/AutoMixEngine.cs`（作者 Gaimoydev）。
 * 不变的部分：状态机、过渡曲线（含 BeatMix 低频交换、FilterCut 高通扫频）、
 * 软限幅器常数、Align 阶段的追赶/回速逻辑。
 *
 * 与上游的差异只在宿主抽象：C# 把 AudioGraph 的两个 PlaybackSource「静音」后
 * 由引擎接管全部 PCM 输出；Mei 同样由引擎接管输出，但用 [AutoMixHost] 控制两个
 * ExoPlayer 的音量与速率，B 播到对齐点后再把音量还给播放器。
 */
internal class AutoMixEngine(
    private val host: AutoMixHost,
    private val plan: MixPlan,
    private val regionA: MixRegion,
    private val regionB: MixRegion,
    private val voiceA: Int,
    private val voiceB: Int,
    private val volumeA: Double,
    private val volumeB: Double,
) {
    private val sampleRate = regionA.sampleRate
    private val clickRamp = maxOf(1, (sampleRate * 0.001).roundToInt())
    private val exitIndexA = regionA.indexOf(plan.aExit)
    private val overlapFrames = maxOf(1L, (plan.overlap * sampleRate).roundToLong())
    private val handoverIndexA = maxOf(0L, exitIndexA - sampleRate * 2L)

    private var mixerA: MixVoice? = null
    private var mixerB: MixVoice? = null
    private var pendingHandover = false
    private var pendingHandBack = false
    private var pendingAbort = false
    private var overlapEndRendered = -1L
    private var rendered = 0L
    private var nodeSpeedB = 1.0
    private var predictedNodeIndexB = Double.NaN
    private var alignStableQuanta = 0
    private var alignChased = false
    private var alignRefined = false
    private var startRenderedB = -1L
    private var limiterGain = 1.0
    private var forceHandBack = false
    private var forcedFadeSeconds = 0.0
    private var fadeOutFrames = 0
    private var fadeOutDone = 0L
    private var fadeOutBase = 0.0

    var tapA: TapBuffer? = null
    var tapB: TapBuffer? = null
    var mapA: Double = Double.NaN
    var mapB: Double = Double.NaN

    var stage: AutoMixStage = AutoMixStage.Waiting
    var midpointReached = false
    var outgoingFinished = false
    var bStarted = false
    var needsMapB = false
    var needsForcedHandBack = false
    var progress = 0.0
        private set

    val contentSecondsB: Double get() = mixerB?.contentSeconds ?: Double.NaN
    val contentIndexB: Double get() = mixerB?.contentIndex ?: Double.NaN
    val volumeOfB: Double get() = volumeB
    val handoverIndex: Long get() = handoverIndexA

    private var startIndexA = 0L

    /** 混音输出的起始播放位置（秒），由控制器加上渲染进度得到当前进度。 */
    var outputStartSeconds: Double = regionA.startSeconds + startIndexA
        private set

    val renderedSeconds: Double get() = rendered / sampleRate.toDouble()
    val positionSeconds: Double get() = outputStartSeconds + renderedSeconds

    fun requestAbort() {
        pendingAbort = true
    }

    fun beginForcedHandBack(fadeSeconds: Double) {
        forcedFadeSeconds = fadeSeconds
        forceHandBack = true
    }

    /** 直接把 A 放在某个位置开始渲染（离线混音或跳过等待阶段时用）。 */
    fun startOffline(regionIndexA: Long) {
        startIndexA = regionIndexA
        outputStartSeconds = regionA.startSeconds + regionIndexA / sampleRate.toDouble()
        mixerA = MixVoice(
            region = regionA,
            startContentSeconds = outputStartSeconds,
            useStretcher = false,
        ).also { it.setGainImmediate(volumeA) }
        stage = AutoMixStage.Handover
    }

    fun onQuantum() {
        if (stage == AutoMixStage.Done || stage == AutoMixStage.Aborted) return
        if (pendingAbort) {
            abort()
            return
        }
        if (forceHandBack || fadeOutFrames > 0) return

        val b = mixerB
        if ((stage == AutoMixStage.RampBack || stage == AutoMixStage.Aligning) && b != null &&
            b.contentIndex > regionB.frames - sampleRate * 1.5
        ) {
            needsForcedHandBack = true
        }

        when (stage) {
            AutoMixStage.Waiting -> {
                val tap = tapA ?: return
                if (mapA.isNaN()) return
                val nodeIndex = (tap.totalFrames + mapA).roundToLong()
                if (nodeIndex < handoverIndexA) return
                startIndexA = nodeIndex
                outputStartSeconds = regionA.startSeconds + nodeIndex / sampleRate.toDouble()
                mixerA = MixVoice(regionA, outputStartSeconds, false).also {
                    it.setGainImmediate(volumeA)
                }
                host.setVolume(voiceA, 0.0)
                pendingHandover = true
                stage = AutoMixStage.Handover
            }

            AutoMixStage.Handover, AutoMixStage.Overlap -> {
                val voice = mixerB
                if (bStarted && voice != null && nodeSpeedB != voice.rate) setNodeSpeed(voice.rate)
            }

            AutoMixStage.RampBack -> {
                val voice = mixerB ?: return
                val elapsed = (rendered - overlapEndRendered) / sampleRate.toDouble()
                val t = if (plan.rampBack <= 0) 1.0 else clamp(elapsed / plan.rampBack, 0.0, 1.0)
                val smooth = t * t * (3 - 2 * t)
                voice.rate = plan.rate + (1 - plan.rate) * smooth
                val gainT = clamp(elapsed / 2.0, 0.0, 1.0)
                voice.targetGain = volumeB * (plan.gainB + (1 - plan.gainB) * gainT)
                setNodeSpeed(voice.rate)
                if (t >= 1 && gainT >= 1) {
                    if (voice.isStretching) {
                        voice.beginRawBlend(0.05)
                    } else if (mapB.isNaN()) {
                        needsMapB = true
                    } else {
                        stage = AutoMixStage.Aligning
                    }
                }
            }

            AutoMixStage.Aligning -> align()
            else -> Unit
        }
    }

    private fun setNodeSpeed(speed: Double) {
        if (abs(speed - nodeSpeedB) < 1e-6) return
        nodeSpeedB = speed
        runCatching { host.setSpeed(voiceB, speed) }
    }

    /** 用 tap 与 B 的 PCM 做互相关对齐，避免交接瞬间的速度突变造成咔哒。 */
    private fun align() {
        val voice = mixerB ?: return
        val tap = tapB ?: return
        if (mapB.isNaN()) return
        val quantum = host.samplesPerQuantum
        if (predictedNodeIndexB.isNaN()) {
            predictedNodeIndexB = tap.totalFrames + mapB
            setNodeSpeed(1.0)
            alignChased = abs(predictedNodeIndexB - voice.contentIndex) > sampleRate * 0.05
        } else {
            predictedNodeIndexB += quantum * nodeSpeedB
        }
        val delta = predictedNodeIndexB - voice.contentIndex
        if (abs(delta) < 0.5) {
            setNodeSpeed(1.0)
            if (++alignStableQuanta < 2) return
            if (alignChased && !alignRefined) {
                alignRefined = true
                alignStableQuanta = 0
                predictedNodeIndexB = Double.NaN
                mapB = Double.NaN
                stage = AutoMixStage.RampBack
                return
            }
            host.setVolume(voiceB, volumeB)
            pendingHandBack = true
            return
        }
        alignStableQuanta = 0
        var correction = clamp(-delta / (sampleRate * 0.25), -0.5, 1.0)
        val maxStep = abs(delta) / quantum
        if (abs(correction) > maxStep) correction = sign(correction) * maxStep
        setNodeSpeed(1 + correction)
    }

    private fun abort() {
        stage = AutoMixStage.Aborted
        runCatching {
            if (mixerA != null && !outgoingFinished && !midpointReached) {
                host.setVolume(voiceA, volumeA)
            }
            if (mixerB != null && midpointReached) {
                setNodeSpeed(1.0)
                host.setVolume(voiceB, volumeB)
            }
        }
    }

    /** 核心渲染：把 [mix]（交错立体声）填满 [frames] 帧。调用方按 quantum 驱动。 */
    fun render(mix: FloatArray, frames: Int) {
        if (stage == AutoMixStage.Aborted) {
            mixerA?.let { it.renderRampOut(mix, 0, frames); it.dispose() }
            mixerB?.let { it.renderRampOut(mix, 0, frames); it.dispose() }
            mixerA = null
            mixerB = null
            rendered += frames
            return
        }

        if (forceHandBack && mixerB != null && fadeOutFrames == 0) {
            forceHandBack = false
            fadeOutFrames = maxOf(1, (forcedFadeSeconds * sampleRate).toInt())
            fadeOutDone = 0
            fadeOutBase = mixerB?.targetGain ?: 0.0
        }

        if (fadeOutFrames > 0) {
            fadeOutDone += frames
            val fade = clamp(1 - fadeOutDone.toDouble() / fadeOutFrames, 0.0, 1.0)
            mixerB?.let {
                it.targetGain = fadeOutBase * fade
                it.render(mix, 0, frames)
                limit(mix, frames)
            }
            rendered += frames
            if (fade <= 0 || mixerB == null) {
                mixerB?.dispose()
                mixerB = null
                stage = AutoMixStage.Done
            }
            return
        }

        if (pendingHandBack && mixerB != null) {
            mixerB?.renderRampOut(mix, 0, frames)
            mixerB?.dispose()
            mixerB = null
            pendingHandBack = false
            stage = AutoMixStage.Done
            rendered += frames
            return
        }

        var offset = 0
        val a = mixerA
        if (a != null && mixerB == null) {
            val aIndex = a.contentIndex.roundToLong()
            if (aIndex + frames > exitIndexA) {
                val split = clamp(exitIndexA - aIndex, 0L, frames.toLong()).toInt()
                renderA(mix, split)
                startB()
                offset = split
            }
        }

        val remaining = frames - offset
        if (remaining > 0) {
            updateOverlap()
            if (offset == 0) {
                mixerA?.let { renderA(mix, remaining) }
                mixerB?.let { it.render(mix, 0, remaining) }
            } else {
                // 切点之后的片段需要错位写入：先渲染到临时区再拷回。
                if (sliceBuffer.size < remaining * 2) sliceBuffer = FloatArray(remaining * 2)
                java.util.Arrays.fill(sliceBuffer, 0, remaining * 2, 0f)
                mixerA?.let { it.render(sliceBuffer, 0, remaining) }
                mixerB?.let { it.render(sliceBuffer, 0, remaining) }
                System.arraycopy(sliceBuffer, 0, mix, offset * 2, remaining * 2)
            }
        }

        limit(mix, frames)
        rendered += frames

        val voiceAInstance = mixerA
        if (voiceAInstance != null && (voiceAInstance.finished ||
                (progress >= 1 && voiceAInstance.targetGain <= 0.0001))
        ) {
            voiceAInstance.dispose()
            mixerA = null
            outgoingFinished = true
            if (overlapEndRendered < 0) overlapEndRendered = rendered
            if (stage == AutoMixStage.Overlap) stage = AutoMixStage.RampBack
        }
    }

    private var sliceBuffer = FloatArray(0)

    /** 软限幅：阈値 0.97（约 -0.26 dBFS），120ms 释放，避免叠加后削波失真。 */
    private fun limit(mix: FloatArray, frames: Int) {
        val threshold = 0.97
        val release = 1 - exp(-1.0 / (0.12 * sampleRate))
        for (i in 0 until frames) {
            val left = abs(mix[i * 2])
            val right = abs(mix[i * 2 + 1])
            val peak = maxOf(left, right)
            val needed = if (peak * limiterGain > threshold && peak > 0) {
                threshold / peak
            } else {
                1.0
            }
            limiterGain = if (needed < limiterGain) needed else limiterGain + (1 - limiterGain) * release
            mix[i * 2] = (mix[i * 2] * limiterGain).toFloat()
            mix[i * 2 + 1] = (mix[i * 2 + 1] * limiterGain).toFloat()
        }
    }

    private fun renderA(mix: FloatArray, frames: Int) {
        val voice = mixerA ?: return
        if (frames <= 0) return
        voice.render(mix, 0, frames)
        if (pendingHandover) pendingHandover = false
    }

    private fun startB() {
        if (mixerB != null) return
        val stretch = abs(plan.rate - 1) > 0.0005
        val voice = MixVoice(regionB, plan.bEntry, stretch)
        voice.rate = plan.rate
        voice.setGainImmediate(0.0)
        if (plan.technique == MixTechnique.BeatMix) voice.lowShelfDb = -24.0
        mixerB = voice
        startRenderedB = rendered
        bStarted = true
        stage = AutoMixStage.Overlap
        runCatching {
            setNodeSpeed(plan.rate)
            host.play(voiceB)
        }
    }

    private fun updateOverlap() {
        val a = mixerA
        if (a == null || !bStarted) {
            progress = 0.0
            return
        }
        val x = clamp((a.contentIndex - exitIndexA) / overlapFrames, 0.0, 1.0)
        progress = x
        if (x >= 0.5) midpointReached = true

        val gainB = volumeB * plan.gainB
        val handoff = clamp(plan.handoff, 0.0, 0.95)
        val release = if (x < handoff) 0.0 else (x - handoff) / (1 - handoff)
        val b = mixerB

        when (plan.technique) {
            MixTechnique.BeatMix -> {
                a.targetGain = volumeA * cos(release * PI / 2)
                b?.targetGain = gainB * if (x < handoff) sin(x / handoff * PI / 2) else 1.0
                if (x >= handoff) {
                    a.lowShelfDb = -24.0
                    b?.lowShelfDb = 0.0
                }
            }

            MixTechnique.FilterCut -> {
                a.highPassHz = 300 * 60.0.pow(x)
                a.targetGain = volumeA * if (x < 0.7) 1.0 else cos((x - 0.7) / 0.3 * PI / 2)
                b?.targetGain = gainB * if (x < 0.5) 0.0 else sin((x - 0.5) / 0.5 * PI / 2)
            }

            else -> {
                if (handoff < 0.1) {
                    a.targetGain = volumeA * cos(x * PI / 2)
                    b?.targetGain = gainB * sin(x * PI / 2)
                } else {
                    a.targetGain = volumeA * cos(release * PI / 2)
                    b?.targetGain = gainB * if (x < handoff) {
                        0.6 * sin(x / handoff * PI / 2)
                    } else {
                        0.6 + 0.4 * sin(release * PI / 2)
                    }
                }
            }
        }
        if (x >= 1) a.targetGain = 0.0
    }

    fun dispose() {
        mixerA?.dispose()
        mixerB?.dispose()
        mixerA = null
        mixerB = null
    }
}

private fun Double.pow(exponent: Double): Double = Math.pow(this, exponent)
