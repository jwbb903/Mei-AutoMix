package com.ljyh.mei.playback.automix

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * 一次完整的 AutoMix 会话 = 离线解码两段 PCM + [BeatDetector] 拍点对齐 +
 * [AutoMixEngine] 混音 + [MixerOutput] 出声 + 交接回 ExoPlayer。
 *
 * ## 时间线（lead = 1.0s 预备量）
 *
 * ```
 * A 播放进度       aExit-lead        aExit            aExit+overlap
 *                  |                 |                |
 * ExoPlayer A      ──── 有声 ────────X 静音(仅推进时间轴) ─────→ stop
 * ExoPlayer B  静音，等 bEntry ──────────────────────────→ seek + 有声
 * MixerOutput      ──── A ───────────┼──── A+B 混音 ──┼── B ──→ 淡出
 *                                    ↑ handover       ↑ 交接点
 * ```
 *
 * 混音期间两个 ExoPlayer 都在跑但音量为 0：它们只负责推进时间轴与提供
 * MediaSession 的 position，出声完全由 [MixerOutput] 负责（Android 没有
 * 上游 WASAPI 那样的共享混音总线，报告里的方案 A）。
 *
 * ## 为什么要在解码后再算一遍 plan
 *
 * 上游 HyPlayer 用 BeatThis(ONNX) 在选歌阶段就知道两首歌的 BPM，它据此把
 * 退出点、进入点、淡化长度都对齐到整数拍，并把 B 的变速量取成两首歌拍周期的
 * 比值 —— 这样两条音轨的拍点在整个重叠期同相，听感才「自然」。
 *
 * Mei 自带 BeatNet 模型（`assets/beatnet/model_1_f32.bin`，纯权重 + 手写推理），
 * 所以对齐优先用模型的拍点/小节点；模型不可用时回退到内置 [BeatDetector] 的
 * 频谱通量 + 自相关估计。两条路径都归一成 [BeatGridMarks]，由 [alignToBeats] 统一处理。
 */
@OptIn(UnstableApi::class)
internal class AutoMixSession(
    private val scope: CoroutineScope,
    private val decoder: PcmRegionDecoder,
    private val initialPlan: MixPlan,
    private val outgoing: ExoPlayer,
    private val incoming: ExoPlayer,
    private val outgoingUri: Uri,
    private val incomingUri: Uri,
    private val outgoingDurationMs: Long,
    private val baseSpeed: Float,
    private val sampleRate: Int = 48_000,
    private val beatProvider: (suspend () -> Pair<BeatGridMarks?, BeatGridMarks?>?)? = null,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        /** 混音结束，声音已交回 ExoPlayer B。参数是 B 当前内容位置（秒）。 */
        fun onHandback(contentSecondsB: Double)

        /** 混音被放弃（异常/用户操作），调用方应恢复音量并回到普通路径。 */
        fun onAborted(reason: String)
    }

    companion object {
        private const val TAG = "AutoMix"
        /** 混音开始到 A 退出点的提前量。 */
        private const val LEAD_SECONDS = 1.0
        /** 解码区在退出点之前的余量，给拍点微调留空间。 */
        private const val MARGIN_BEFORE_EXIT = 2.5
        private const val MARGIN_AFTER_EXIT = 8.0
        private const val MARGIN_AFTER_ENTRY = 12.0
        private const val QUANTUM = 1024
        private const val HANDBACK_FADE = 0.15
        private const val PRESEEK_LEAD = 1.0
        private const val MAX_STRETCH = 0.08
        private const val BEAT_MODEL_TIMEOUT_MS = 25_000L
        private const val VOICE_A = 1
        private const val VOICE_B = 2
    }

    /** 解码后按拍点重算的最终 plan；解码完成前就是上游给的原 plan。 */
    var activePlan: MixPlan = initialPlan
        private set

    var beatInfo: String = "未检测"
        private set

    /** A 轨相对 B 轨的响度补偿（dB）；负值 = 把 A 压小。 */
    private var loudnessTrimDb = 0.0

    private var regionA: MixRegion? = null
    private var regionB: MixRegion? = null
    private var modelA: BeatGridMarks? = null
    private var modelB: BeatGridMarks? = null
    private var engine: AutoMixEngine? = null
    private var output: MixerOutput? = null
    private var prepareJob: Job? = null
    private var monitorJob: Job? = null
    private var preseekDone = false
    private var handbackStarted = false
    private var aborted = false

    /**
     * ExoPlayer 只允许在 application（主）线程访问。引擎的回调全部来自
     * `AutoMix-Mixer` 线程，所以每个播放器操作都要封送回主线程，
     * 否则会抛 `IllegalStateException: Player is accessed on the wrong thread`。
     */
    private val mainHandler = Handler(Looper.getMainLooper())
    private var speedPosted = 1.0

    @Volatile
    private var outgoingPlaying = true

    private fun onPlayerThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    val isReady: Boolean get() = regionA != null && regionB != null
    val isStarted: Boolean get() = output?.isRunning == true

    /** 需要提前 [LEAD_SECONDS] 开始混音的 A 内容位置（秒）。 */
    val startAtSeconds: Double get() = activePlan.aExit - LEAD_SECONDS

    /** 会话预计持续时长（秒），用于超时保护。 */
    val estimatedSeconds: Double
        get() = LEAD_SECONDS + activePlan.overlap + activePlan.rampBack + 1.0

    /** 提前解码两段 PCM；可重复调用，已在解码时直接返回。 */
    fun prepare() {
        if (prepareJob != null || isReady) return
        prepareJob = scope.launch {
            val aStart = (initialPlan.aExit - MARGIN_BEFORE_EXIT).coerceAtLeast(0.0)
            val aEnd = (initialPlan.aExit + MARGIN_AFTER_EXIT)
                .coerceAtMost(outgoingDurationMs / 1000.0)
            val bStart = initialPlan.bEntry.coerceAtLeast(0.0)
            val bEnd = initialPlan.bEntry + MARGIN_AFTER_ENTRY
            Timber.tag(TAG).i(
                "prepare: decode A=[%.2f,%.2f]s B=[%.2f,%.2f]s (raw plan technique=%s aExit=%.2f bEntry=%.2f rate=%.3f overlap=%.2f)",
                aStart, aEnd, bStart, bEnd,
                initialPlan.technique, initialPlan.aExit, initialPlan.bEntry,
                initialPlan.rate, initialPlan.overlap,
            )
            val startedAt = System.currentTimeMillis()
            val (decodedA, decodedB) = coroutineScope {
                val a = async { decoder.decode(outgoingUri, aStart, aEnd, sampleRate) }
                val b = async { decoder.decode(incomingUri, bStart, bEnd, sampleRate) }
                a.await() to b.await()
            }
            val elapsed = System.currentTimeMillis() - startedAt
            if (decodedA == null || decodedB == null) {
                Timber.tag(TAG).e(
                    "prepare: decode failed after %d ms (A=%s B=%s)",
                    elapsed, decodedA != null, decodedB != null,
                )
                callbacks.onAborted("region decode failed")
                return@launch
            }
            regionA = decodedA
            regionB = decodedB
            Timber.tag(TAG).i(
                "prepare: decoded in %d ms A=%.2fs B=%.2fs @%d Hz",
                elapsed,
                decodedA.frames / decodedA.sampleRate.toDouble(),
                decodedB.frames / decodedB.sampleRate.toDouble(),
                decodedA.sampleRate,
            )
            // 模型（BeatNet）拍网格：优先级高于内置 DSP，拿不到就回退。
            val marksStarted = System.currentTimeMillis()
            val marks = runCatching {
                withTimeoutOrNull(BEAT_MODEL_TIMEOUT_MS) { beatProvider?.invoke() }
            }.onFailure { Timber.tag(TAG).w(it, "beat provider failed") }.getOrNull()
            modelA = marks?.first
            modelB = marks?.second
            Timber.tag(TAG).i(
                "prepare: model grids in %d ms A=%s B=%s",
                System.currentTimeMillis() - marksStarted,
                modelA?.describe() ?: "无",
                modelB?.describe() ?: "无",
            )
            activePlan = alignToBeats(initialPlan, decodedA, decodedB)
        }
    }

    private fun alignToBeats(plan: MixPlan, a: MixRegion, b: MixRegion): MixPlan {
        alignWithModel(plan, a, b)?.let { return it }
        return alignWithDsp(plan, a, b)
    }

    /**
     * 模型网格对齐：重叠取 A 的 4 小节；退出点吸附 A 最近的小节起点；进入点取 B 的
     * 第一个小节起点；变速比取两者拍周期之比。这样两条轨的小节线在重叠区里重合，
     * 听感才像 DJ 混音而不是「两个声音叠在一起」。
     */
    private fun alignWithModel(plan: MixPlan, a: MixRegion, b: MixRegion): MixPlan? {
        val gridA = modelA ?: return null
        val gridB = modelB ?: return null
        val minConfidence = minOf(gridA.confidence, gridB.confidence)
        if (minConfidence < 0.5) {
            Timber.tag(TAG).i("model align skipped: 置信度 %.2f < 0.50", minConfidence)
            return null
        }
        val periodA = 60.0 / gridA.bpm
        val periodB = 60.0 / gridB.bpm
        if (!periodA.isFinite() || !periodB.isFinite() || periodA < 0.2 || periodB < 0.2) return null

        val aStart = a.startSeconds
        val aEnd = aStart + a.frames / a.sampleRate.toDouble()
        val bStart = b.startSeconds
        val bEnd = bStart + b.frames / b.sampleRate.toDouble()

        var overlap = (4 * gridA.barSeconds).coerceIn(3.0, 8.0)
        val wantedExit = plan.aExit.coerceIn(
            aStart + 1.0,
            (aEnd - overlap - 0.3).coerceAtLeast(aStart + 1.0),
        )

        // 结构视图：能量包络用来避开高潮，优先在尾奏/间奏换句。
        val structure = StructureAnalyzer.analyze(a.samples, a.sampleRate, aStart)
        val phrase = PhraseAligner.chooseExit(
            target = wantedExit,
            grid = gridA,
            structure = structure,
            aStart = aStart,
            aEnd = aEnd,
            overlap = overlap,
        )
        var aExit = phrase?.exit
            ?: gridA.nearestDownbeat(wantedExit, 1.2)
            ?: gridA.nearestBeat(wantedExit, 0.6)
            ?: wantedExit
        if (aExit + overlap > aEnd - 0.2) {
            overlap = (aEnd - 0.2 - aExit).coerceAtLeast(1.5)
        }
        val bEntry = gridB.firstDownbeatAfter(bStart - 0.1, 8.0)
            ?: gridB.nearestBeat(plan.bEntry, 1.0)
            ?: plan.bEntry
        val rate = (periodB / periodA).coerceIn(1.0 - MAX_STRETCH, 1.0 + MAX_STRETCH)
        val rampBack = (4 * periodB).coerceIn(0.4, 2.0)

        // 响度匹配：把 A 压到与 B 同响度，交接点就不会有音量台阶。
        // B 保持 unity（交接后由 ExoPlayer 接手，两边必须一致）。
        val lufsA = LoudnessMeter.integratedLufs(a.samples, a.sampleRate, 2)
        val lufsB = LoudnessMeter.integratedLufs(b.samples, b.sampleRate, 2)
        loudnessTrimDb = LoudnessMeter.trimDb(referenceLufs = lufsA, targetLufs = lufsB)

        if (bEntry + overlap + rampBack + 1.0 > bEnd) {
            Timber.tag(TAG).i("model align skipped: B 可用区不足")
            return null
        }
        if (aExit <= aStart + 0.5) {
            Timber.tag(TAG).i("model align skipped: A 可用区不足")
            return null
        }

        beatInfo = buildString {
            append("模型 ").append(gridA.describe()).append(" / ").append(gridB.describe())
            append(" rate=%.3f ".format(rate))
            if (phrase != null) {
                append("乐句=${phrase.bars}小节 能量=%.2f ".format(phrase.energy))
            }
            append("overlap=%.2fs ".format(overlap))
            append("aExit %.2f→%.2f ".format(plan.aExit, aExit))
            append("bEntry %.2f→%.2f ".format(plan.bEntry, bEntry))
            append("响度 A=%.1fLUFS B=%.1fLUFS trim=%+.1fdB".format(lufsA, lufsB, loudnessTrimDb))
        }
        Timber.tag(TAG).i("model align: %s", beatInfo)
        return plan.copy(
            aExit = aExit,
            bEntry = bEntry,
            overlap = overlap,
            rate = rate,
            rampBack = rampBack,
        )
    }

    /** DSP 回退路径：频谱通量 + 自相关估 BPM，重叠取整数拍。 */
    private fun alignWithDsp(plan: MixPlan, a: MixRegion, b: MixRegion): MixPlan {
        val gridA = BeatDetector.detect(downmix(a.samples), a.sampleRate)
        val gridB = BeatDetector.detect(downmix(b.samples), b.sampleRate)
        if (gridA == null || gridB == null) {
            beatInfo = "检测失败(A=${gridA != null},B=${gridB != null})，沿用原 plan"
            Timber.tag(TAG).w("beat align skipped: %s", beatInfo)
            return plan
        }

        val periodA = gridA.periodMs / 1000.0
        val periodB = gridB.periodMs / 1000.0
        val rawRate = periodB / periodA
        val rate = rawRate.coerceIn(1.0 - MAX_STRETCH, 1.0 + MAX_STRETCH)

        val aEndSeconds = a.startSeconds + a.frames / a.sampleRate.toDouble()
        val bEndSeconds = b.startSeconds + b.frames / b.sampleRate.toDouble()

        // 重叠长度：优先 8 拍（4/4 的两小节），取第一个落在 1.2~6s 的。
        var overlap = plan.overlap
        var beats = 0
        for (candidate in intArrayOf(8, 4, 2)) {
            val seconds = candidate * periodA
            if (seconds in 1.2..6.0) {
                overlap = seconds
                beats = candidate
                break
            }
        }
        // 必须给 A 的尾巴留够重叠空间。
        val maxOverlap = (aEndSeconds - 1.0) - (a.startSeconds + MARGIN_BEFORE_EXIT - LEAD_SECONDS)
        if (overlap > maxOverlap) {
            overlap = maxOverlap.coerceAtLeast(1.0)
            beats = (overlap / periodA).roundToInt().coerceAtLeast(2)
            overlap = beats * periodA
        }

        val aExit = clampTo(gridA.nearest(plan.aExit), a.startSeconds + 1.2, aEndSeconds - overlap - 0.5)
        val rampBack = (4 * periodA).coerceIn(0.4, 3.0)
        val bEntry = clampTo(
            gridB.nearest(plan.bEntry),
            b.startSeconds,
            (bEndSeconds - overlap - rampBack - 1.0).coerceAtLeast(b.startSeconds),
        )

        beatInfo = buildString {
            append("A=%.1fBPM/%.3fs B=%.1fBPM/%.3fs ".format(gridA.bpm, periodA, gridB.bpm, periodB))
            append("rate=%.3f(原%.3f) ".format(rate, plan.rate))
            append("overlap=${beats}拍/%.2fs ".format(overlap))
            append("aExit %.2f→%.2f ".format(plan.aExit, aExit))
            append("bEntry %.2f→%.2f".format(plan.bEntry, bEntry))
        }
        Timber.tag(TAG).i("beat align: %s", beatInfo)

        return plan.copy(
            aExit = aExit,
            bEntry = bEntry,
            overlap = overlap,
            rate = rate,
            rampBack = rampBack,
        )
    }

    /** coerceIn 在 lo > hi 时会抛异常（短音频时可能出现），这里做安全退化。 */
    private fun clampTo(value: Double, low: Double, high: Double): Double =
        if (high <= low) low else value.coerceIn(low, high)

    private fun downmix(samples: FloatArray): FloatArray {        val frames = samples.size / 2
        val mono = FloatArray(frames)
        for (frame in 0 until frames) {
            mono[frame] = 0.5f * (samples[frame * 2] + samples[frame * 2 + 1])
        }
        return mono
    }

    /** 真正开始混音播放（A 已经播到 [startAtSeconds]）。 */
    fun start(offsetSeconds: Double = 0.0) {
        val a = regionA
        val b = regionB
        if (a == null || b == null) {
            Timber.tag(TAG).e("start() called before regions ready")
            callbacks.onAborted("regions not ready")
            return
        }
        if (isStarted) return
        if (abs(baseSpeed - 1f) > 0.01f) {
            Timber.tag(TAG).w("playback speed %.2f != 1.0, AutoMix 不支持变速下混音", baseSpeed)
            callbacks.onAborted("non-1.0 playback speed")
            return
        }

        val plan = activePlan
        val host = object : AutoMixHost {
            override val samplesPerQuantum: Int = QUANTUM

            override fun setVolume(voiceId: Int, volume: Double) = Unit

            override fun setSpeed(voiceId: Int, speed: Double) {
                if (voiceId != VOICE_B) return
                // 只在速率真的变了才派发，避免每个 quantum 都往主线程扔一个任务。
                if (abs(speed - speedPosted) < 1e-3) return
                speedPosted = speed
                onPlayerThread {
                    runCatching {
                        incoming.playbackParameters = PlaybackParameters((baseSpeed * speed).toFloat())
                    }.onFailure { Timber.tag(TAG).w(it, "setSpeed failed") }
                }
            }

            override fun play(voiceId: Int) {
                if (voiceId != VOICE_B) return
                Timber.tag(TAG).i("voiceB play (muted, timeline only)")
                onPlayerThread {
                    runCatching {
                        incoming.volume = 0f
                        incoming.play()
                    }.onFailure { Timber.tag(TAG).w(it, "play failed") }
                }
            }

            override fun seek(voiceId: Int, positionSeconds: Double) {
                onPlayerThread {
                    runCatching {
                        val target = (positionSeconds * 1000).toLong().coerceAtLeast(0L)
                        val targetPlayer = if (voiceId == VOICE_A) outgoing else incoming
                        targetPlayer.seekTo(target)
                    }.onFailure { Timber.tag(TAG).w(it, "seek failed") }
                }
            }
        }

        val engine = AutoMixEngine(
            host = host,
            plan = plan,
            regionA = a,
            regionB = b,
            voiceA = VOICE_A,
            voiceB = VOICE_B,
            volumeA = Math.pow(10.0, loudnessTrimDb / 20.0),
            volumeB = 1.0,
        )
        // 从「退出点前 LEAD_SECONDS」开始出声，保证与 ExoPlayer A 的内容位置一致；
        // offsetSeconds 是调度晚到的量（A 已经多播了一点），必须一起加上，
        // 否则混音内容会比 ExoPlayer 晚，交接时会出现跳回。
        val startIndexA = ((plan.aExit - LEAD_SECONDS - a.startSeconds + offsetSeconds) * sampleRate)
            .roundToLong()
            .coerceIn(0L, (a.frames - 1).toLong())
        engine.startOffline(startIndexA)
        this.engine = engine

        val output = MixerOutput(sampleRate, 2, QUANTUM)
        this.output = output
        output.start(object : MixerOutput.Source {
            override val finished: Boolean get() = engine.stage == AutoMixStage.Done ||
                engine.stage == AutoMixStage.Aborted

            override fun onQuantum(frames: Int) {
                engine.onQuantum()
            }

            override fun render(mix: FloatArray, frames: Int) {
                java.util.Arrays.fill(mix, 0f)
                engine.render(mix, frames)
            }
        })
        Timber.tag(TAG).i(
            "session started at A=%.2fs (exit=%.2f entryB=%.2f rate=%.3f overlap=%.2fs rampBack=%.2fs) %s",
            engine.outputStartSeconds, plan.aExit, plan.bEntry, plan.rate, plan.overlap, plan.rampBack, beatInfo,
        )
        startMonitor(engine, output)
    }

    private fun startMonitor(engine: AutoMixEngine, output: MixerOutput) {
        monitorJob = scope.launch {
            val startedAt = System.currentTimeMillis()
            // A 的真实结尾必须早于交接点，否则 setPauseAtEndOfMediaItems 会让 A
            // 在混音中途自动暂停（playWhenReady 变 false），monitor 误判成用户暂停，
            // 整个混音输出被连带停掉 —— 这就是「切歌时突然暂停」的直接原因。
            val plan = activePlan
            val aEndTimeline = outgoingDurationMs / 1000.0 - plan.aExit + LEAD_SECONDS
            val effectiveHandback = minOf(estimatedSeconds - 0.9, aEndTimeline - 0.4)
            Timber.tag(TAG).i(
                "monitor: handback=%.2fs (A 结尾在 %.2fs)",
                effectiveHandback, aEndTimeline,
            )
            while (!handbackStarted && !aborted) {
                delay(50)
                var positionMs = 0L
                onPlayerThread {
                    outgoingPlaying = runCatching { outgoing.isPlaying }.getOrDefault(true)
                    positionMs = runCatching { outgoing.currentPosition }.getOrDefault(0L)
                }
                val aEnded = !outgoingPlaying && positionMs >= outgoingDurationMs - 300
                if (!outgoingPlaying) {
                    if (aEnded && engine.bStarted) {
                        // A 自然播完：不是用户暂停，直接进入交接，别把混音停掉。
                        handback(engine, output, "A ended")
                        return@launch
                    }
                    if (!aEnded) {
                        output.pause()
                        continue
                    }
                } else if (output.isRunning && output.isPaused) {
                    output.resume()
                }
                val rendered = engine.renderedSeconds
                if (!preseekDone && engine.bStarted && rendered >= effectiveHandback - PRESEEK_LEAD) {
                    preseekDone = true
                    presekIncoming(output, engine)
                }
                if (engine.needsForcedHandBack) {
                    handback(engine, output, "forced")
                    return@launch
                }
                if (engine.bStarted && rendered >= effectiveHandback) {
                    handback(engine, output, "timeline")
                    return@launch
                }
                if (aEnded && !engine.bStarted && rendered > LEAD_SECONDS + plan.overlap) {
                    // A 已到尾但 B 还没接上（极端短歌）：放弃混音，走普通淡出。
                    abort("A ended before overlap")
                    return@launch
                }
                if (System.currentTimeMillis() - startedAt > (estimatedSeconds + 10) * 1000) {
                    abort("overtime")
                    return@launch
                }
                if (engine.stage == AutoMixStage.Aborted) {
                    abort("engine aborted")
                    return@launch
                }
            }
        }
    }

    /** 交接前 1 秒把 B seek 到目标内容位置（保持静音），让解码管线预热。 */
    private fun presekIncoming(output: MixerOutput, engine: AutoMixEngine) {
        val latency = output.latencySeconds()
        val target = engine.contentSecondsB - latency
        Timber.tag(TAG).i(
            "preseek B: content=%.3fs latency=%.0fms target=%.3fs",
            engine.contentSecondsB, latency * 1000, target,
        )
        onPlayerThread {
            runCatching {
                incoming.volume = 0f
                incoming.playbackParameters = PlaybackParameters(baseSpeed)
                incoming.seekTo((target * 1000).roundToLong().coerceAtLeast(0L))
                incoming.play()
            }.onFailure { Timber.tag(TAG).e(it, "preseek failed") }
        }
    }

    private fun handback(engine: AutoMixEngine, output: MixerOutput, reason: String) {
        if (handbackStarted) return
        handbackStarted = true
        val contentB = engine.contentSecondsB
        Timber.tag(TAG).i(
            "handback (%s): B content=%.3fs rendered=%.2fs stage=%s",
            reason, contentB, engine.renderedSeconds, engine.stage,
        )
        // 混音 150ms 线性淡出，同时 B 从 0 拉到 1（内容相同，线性交叉避免 +6dB 峰）。
        output.requestFadeOut(HANDBACK_FADE)
        scope.launch {
            val steps = 15
            for (step in 0..steps) {
                onPlayerThread { runCatching { incoming.volume = step.toFloat() / steps } }
                delay((HANDBACK_FADE * 1000 / steps).toLong())
            }
            Timber.tag(TAG).i("handback crossfade done, mixer stopped")
            output.stop()
            engine.dispose()
            callbacks.onHandback(contentB)
        }
    }

    /** 用户操作（暂停/切歌/seek/退出）时放弃混音。 */
    fun abort(reason: String) {
        if (aborted) return
        aborted = true
        Timber.tag(TAG).w("session abort: %s", reason)
        monitorJob?.cancel()
        output?.stop()
        output = null
        engine?.dispose()
        engine = null
        callbacks.onAborted(reason)
    }

    fun release() {
        monitorJob?.cancel()
        prepareJob?.cancel()
        output?.stop()
        output = null
        engine?.dispose()
        engine = null
    }

    fun currentContentSecondsB(): Double = engine?.contentSecondsB ?: Double.NaN
}
