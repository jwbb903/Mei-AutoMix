package com.ljyh.mei.playback.transition

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.ljyh.mei.constants.SmartTransitionDurationKey
import com.ljyh.mei.constants.SmartTransitionEnabledKey
import com.ljyh.mei.constants.UsbExclusivePlaybackKey
import com.ljyh.mei.constants.SmartTransitionMode
import com.ljyh.mei.constants.SmartTransitionModeKey
import com.ljyh.mei.data.model.domain.metadata
import com.ljyh.mei.extensions.mediaItems
import com.ljyh.mei.playback.automix.AutoMixBridge
import com.ljyh.mei.playback.automix.AutoMixSession
import com.ljyh.mei.playback.automix.BeatGridMarks
import com.ljyh.mei.playback.automix.MixPlan
import com.ljyh.mei.playback.automix.PcmRegionDecoder
import com.ljyh.mei.utils.preferences.dataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import timber.log.Timber

/** Manages two decoders while keeping the public Player and MediaSession stable. */
@UnstableApi
internal class SmartTransitionController(
    context: Context,
    private val decks: TransitionDeckPlayer,
    private val probes: Map<Player, TransitionAudioProbe>,
    private val scope: CoroutineScope,
    private val stopVolumeFade: () -> Unit,
    private val canTransition: () -> Boolean,
    private val resolveSource: suspend (String) -> Uri,
) {
    private data class Settings(
        val enabled: Boolean = false,
        val mode: SmartTransitionMode = SmartTransitionMode.Smart,
        val fixedMs: Long = 6_000L,
    )

    private data class Fade(
        val outgoing: ExoPlayer,
        val incoming: ExoPlayer,
        val startPositionMs: Long,
        val durationMs: Long,
        val target: String,
        val baseSpeed: Float,
        val incomingRate: Float,
        /** AutoMix 模式下的混音方案；非 AutoMix 为 null，走原有等功率曲线。 */
        val autoMix: MixPlan? = null,
    )

    private var settings = Settings()
    private var target: String? = null
    private var targetItem: MediaItem? = null
    private var failedTarget: String? = null
    private var previewStartedAt = 0L
    private var previewFinished = false
    private var incomingEnvelope: List<Float> = emptyList()
    private var fade: Fade? = null
    private val beatNet = BeatNetAnalyzer(context)
    private var analysisJob: Job? = null
    private var analysisAttempt: String? = null
    private var beatPlan: BeatNetTransitionPlan? = null
    private var preparedBeatStartMs = 0L
    private val regionDecoder by lazy { PcmRegionDecoder(context) }
    private var mixSession: AutoMixSession? = null
    private var beatSources: Pair<Uri, Uri>? = null
    private var failedMixPair: String? = null
    val isTransitioning: Boolean
        get() = fade != null || mixSession?.isStarted == true ||
            SystemClock.elapsedRealtime() < promoteGuardUntil

    /**
     * 交接后的一小段保护窗口。
     *
     * [com.ljyh.mei.playback.MusicService.onEvents] 用 `isTransitioning` 决定要不要
     * 调 `pauseSmooth()`。会话在 handback 时 `isStarted` 已经是 false、`fade` 也是 null，
     * 于是 promote 瞬间的 `PLAY_WHEN_READY_CHANGED` / `PLAYBACK_STATE_CHANGED` 会被
     * 当成「普通暂停」，把音量淡到 0 —— 表现就是切歌时突然没声。
     * 所以交接后 2.5 秒内仍然报告为过渡中，让 MusicService 不要插手音量。
     */
    @Volatile
    private var promoteGuardUntil = 0L

    private val preferenceJob: Job = scope.launch {
        context.dataStore.data.collectLatest { prefs ->
            val previousMode = settings.mode
            settings = Settings(
                // USB 独占已从本构建中移除：不再因为 USB 输出而禁用过渡。
                enabled = prefs[SmartTransitionEnabledKey] ?: false,
                mode = runCatching { SmartTransitionMode.valueOf(prefs[SmartTransitionModeKey] ?: "Smart") }
                    .getOrDefault(SmartTransitionMode.Smart),
                fixedMs = (prefs[SmartTransitionDurationKey] ?: 6).coerceIn(2, 12) * 1_000L,
            )
            if (!settings.enabled) cancel()
            else if (previousMode != settings.mode) cancel()
        }
    }
    private val monitorJob: Job = scope.launch {
        while (isActive) {
            try { tick() } catch (error: Exception) {
                Timber.tag("SmartTransition").w(error, "Transition skipped")
                failedTarget = target
                cancel()
            }
            delay(if (settings.enabled && target != null) 100L else 500L)
        }
    }

    init {
        decks.onQueueEdited = { cancel() }
        decks.onSeekRequested = { cancel() }
    }

    private fun tick() {
        fade?.let { updateFade(it); return }
        mixSession?.let { session ->
            if (session.isStarted) {
                // PCM 由 MixerOutput 驱动，tick 只做安全中止判断。
                if (!settings.enabled) session.abort("transitions disabled")
                return
            }
        }
        val active = decks.active
        val nextIndex = active.nextMediaItemIndex
        val duration = active.duration
        val currentIndex = active.currentMediaItemIndex
        val item = if (nextIndex != C.INDEX_UNSET && nextIndex in 0 until active.mediaItemCount)
            active.getMediaItemAt(nextIndex) else null
        val currentAlbum = active.currentMediaItem?.metadata?.album?.id
        val nextAlbum = item?.metadata?.album?.id
        val eligible = settings.enabled && canTransition() &&
            active.repeatMode != Player.REPEAT_MODE_ONE && duration != C.TIME_UNSET && duration > 0 &&
            item != null && item.mediaId != active.currentMediaItem?.mediaId &&
            !(currentAlbum != null && currentAlbum != 0L && currentAlbum == nextAlbum)
        if (!eligible) { if (target != null || analysisAttempt != null) cancel(); return }
        if (!active.isPlaying) {
            if (previewStartedAt != 0L && !previewFinished) {
                decks.standby.pause()
                incomingEnvelope = probes[decks.standby]?.envelope.orEmpty()
                decks.standby.seekTo(0L)
                previewFinished = true
            }
            return
        }
        val key = "$currentIndex:$nextIndex:${item.mediaId}"
        if (target != null && (target != key || targetItem != item)) cancel()
        if (analysisAttempt != null && analysisAttempt != key) clearAnalysis()
        val remaining = duration - active.currentPosition
        if (settings.mode != SmartTransitionMode.Fixed && remaining in 1_000L..65_000L &&
            analysisAttempt != key && failedTarget != key
        ) launchAnalysis(key, active.currentMediaItem!!.mediaId, item.mediaId, duration)
        if (remaining > 25_000L || remaining < 1_000L || failedTarget == key) return
        if (target == null) prepare(key, item, nextIndex)
        val incoming = decks.standby
        if (incoming.playerError != null) {
            failedTarget = key
            cancel()
            return
        }
        if (incoming.playbackState != Player.STATE_READY) return

        val rhythmPlan = beatPlan
        val rhythmMode = settings.mode == SmartTransitionMode.Smart ||
            settings.mode == SmartTransitionMode.AutoMix
        if (rhythmMode && rhythmPlan != null &&
            rhythmPlan.confidence >= 0.35
        ) {
            if (preparedBeatStartMs != rhythmPlan.incomingStartMs) {
                incoming.pause()
                incoming.seekTo(rhythmPlan.incomingStartMs)
                preparedBeatStartMs = rhythmPlan.incomingStartMs
                previewFinished = true
                return
            }
            if (active.currentPosition >= rhythmPlan.outgoingStartMs && remaining > 2_000L) {
                val autoMixPlan = if (settings.mode == SmartTransitionMode.AutoMix) {
                    AutoMixBridge.planFromBeatNet(
                        beat = rhythmPlan,
                        outgoingDurationMs = duration,
                        incomingDurationMs = incoming.duration,
                    )
                } else {
                    null
                }
                if (autoMixPlan != null && failedMixPair != key) {
                    val existing = mixSession
                    val session = existing ?: buildMixSession(key, autoMixPlan)?.also {
                        mixSession = it
                        Timber.tag("SmartTransition").i(
                            "AutoMix session prepared: technique=%s rate=%.3f overlap=%.1fs aExit=%.1fs bEntry=%.1fs",
                            it.let { _ -> autoMixPlan.technique }, autoMixPlan.rate,
                            autoMixPlan.overlap, autoMixPlan.aExit, autoMixPlan.bEntry,
                        )
                    }
                    if (session != null) {
                        val startMs = (session.startAtSeconds * 1000).toLong()
                        if (session.isReady && active.currentPosition >= startMs) {
                            beginAutoMix(session, active, incoming)
                            // PCM 混音已接管：beginAutoMix 会把两个 ExoPlayer 静音，
                            // 之后绝不能再走 beginFade/applyEnvelopeGains，否则会把
                            // 引擎算好的增益覆盖成 deck 淡入淡出曲线。
                            return
                        } else if (remaining < 3_000L && !session.isReady) {
                            Timber.tag("SmartTransition").w(
                                "AutoMix regions not ready in time, falling back to envelope",
                            )
                            failedMixPair = key
                            session.release()
                            mixSession = null
                        } else {
                            // 解码中 / 还没到起播点：保持静音等待，别走普通淡入淡出。
                            return
                        }
                    }
                }
                val autoMix = autoMixPlan
                beginFade(key, TransitionPlan(min(rhythmPlan.durationMs, remaining - 150L),
                    rhythmPlan.incomingStartMs), rhythmPlan.incomingRate, autoMix)
            }
            return
        }

        // BeatNet 没给出可用计划（置信度不够 / BPM 差 >10%）时，AutoMix 仍然走 PCM 混音，
        // 只是不做拍点吸附 —— 会话解码后会用模型/DSP 网格自行对齐。
        if (settings.mode == SmartTransitionMode.AutoMix) {
            startAutoMixWithoutBeat(key, active, incoming, remaining, duration)
            return
        }

        if (settings.mode == SmartTransitionMode.Smart && !previewFinished && remaining > 12_000L) {
            if (previewStartedAt == 0L) {
                incoming.volume = 0f
                incoming.play()
                previewStartedAt = android.os.SystemClock.elapsedRealtime()
            } else if (android.os.SystemClock.elapsedRealtime() - previewStartedAt >= 2_500L) {
                incoming.pause()
                incomingEnvelope = probes[incoming]?.envelope.orEmpty()
                incoming.seekTo(0L)
                previewFinished = true
            }
            return
        }
        if (previewStartedAt != 0L && !previewFinished) {
            incoming.pause()
            incomingEnvelope = probes[incoming]?.envelope.orEmpty()
            incoming.seekTo(0L)
            previewFinished = true
        }
        val plan = if (settings.mode == SmartTransitionMode.Smart) {
            SmartTransitionPlanner.plan(probes[active]?.envelope.orEmpty(), incomingEnvelope, remaining)
        } else TransitionPlan(settings.fixedMs)
        if (plan == null) {
            // Nothing worth blending here; wait for BeatNet if it is still analysing this pair.
            if (settings.mode == SmartTransitionMode.Smart && analysisAttempt == key) return
            cancel()
            return
        }
        if (remaining <= plan.durationMs + 150L) beginFade(key, plan)
    }

    /**
     * 没有拍网格时的 AutoMix：用固定重叠长度先把混音跑起来。
     *
     * 会话内部依旧会尝试 BeatNet 网格与 DSP 拍点检测，所以实际听感仍是拍点对齐的
     * 版本（只是退出点由「剩多少时间」决定，而不是模型给的小节线）。
     */
    private fun startAutoMixWithoutBeat(
        key: String,
        active: ExoPlayer,
        incoming: ExoPlayer,
        remaining: Long,
        duration: Long,
    ) {
        if (failedMixPair == key) return
        val plan = mixSession?.activePlan ?: AutoMixBridge.planSynthetic(duration, settings.fixedMs)
        if (plan == null) {
            if (remaining <= settings.fixedMs + 150L) beginFade(key, TransitionPlan(settings.fixedMs))
            return
        }
        val session = mixSession ?: buildMixSession(key, plan)?.also {
            mixSession = it
            Timber.tag("SmartTransition").i(
                "AutoMix session prepared (no beat grid): overlap=%.1fs aExit=%.1fs bEntry=%.1fs",
                plan.overlap, plan.aExit, plan.bEntry,
            )
        }
        if (session == null) {
            failedMixPair = key
            if (remaining <= settings.fixedMs + 150L) beginFade(key, TransitionPlan(settings.fixedMs))
            return
        }
        val startMs = (session.startAtSeconds * 1000).toLong()
        if (session.isReady && active.currentPosition >= startMs) {
            beginAutoMix(session, active, incoming)
            // PCM 混音已接管：beginAutoMix 会把两个 ExoPlayer 静音，
            // 之后绝不能再走 beginFade/applyEnvelopeGains，否则会把
            // 引擎算好的增益覆盖成 deck 淡入淡出曲线。
            return
        } else if (remaining < 3_000L && !session.isReady) {
            Timber.tag("SmartTransition").w(
                "AutoMix regions not ready in time (no beat grid), falling back to envelope",
            )
            failedMixPair = key
            session.release()
            mixSession = null
            beginFade(key, TransitionPlan(min(settings.fixedMs, remaining - 150L)))
        } else {
            // 解码中 / 还没到起播点：保持静音等待，别走普通淡入淡出。
            return
        }
    }

    private fun prepare(key: String, item: MediaItem, index: Int) {
        val active = decks.active
        val incoming = decks.standby
        target = key
        targetItem = item
        previewStartedAt = 0L
        previewFinished = false
        incomingEnvelope = emptyList()
        incoming.pause()
        incoming.volume = 0f
        preparedBeatStartMs = beatPlan?.incomingStartMs ?: 0L
        if (beatPlan != null) previewFinished = true
        incoming.setMediaItems(active.mediaItems.toList(), index, preparedBeatStartMs)
        incoming.setShuffleOrder(active.shuffleOrder)
        incoming.shuffleModeEnabled = active.shuffleModeEnabled
        incoming.repeatMode = active.repeatMode
        incoming.playbackParameters = active.playbackParameters
        incoming.skipSilenceEnabled = active.skipSilenceEnabled
        incoming.prepare()
    }

    private fun beginFade(
        key: String,
        plan: TransitionPlan,
        incomingRate: Float = 1f,
        autoMix: MixPlan? = null,
    ) {
        val outgoing = decks.active
        val incoming = decks.standby
        if (incoming.playbackState != Player.STATE_READY || !outgoing.isPlaying) return
        stopVolumeFade()
        outgoing.volume = 1f
        outgoing.setPauseAtEndOfMediaItems(true)
        if (plan.incomingStartMs > 0L && abs(incoming.currentPosition - plan.incomingStartMs) > 100L) {
            incoming.seekTo(plan.incomingStartMs)
        }
        val baseSpeed = outgoing.playbackParameters.speed
        if (incomingRate != 1f) incoming.playbackParameters = PlaybackParameters(baseSpeed * incomingRate)
        incoming.volume = 0f
        fade = Fade(outgoing, incoming, outgoing.currentPosition, plan.durationMs, key,
            baseSpeed, incomingRate, autoMix)
        incoming.play()
    }

    private fun updateFade(state: Fade) {
        if (decks.active !== state.outgoing || target != state.target || !canTransition()) {
            cancel()
            return
        }
        state.incoming.playWhenReady = state.outgoing.playWhenReady
        if (state.incoming.playerError != null) {
            failedTarget = state.target
            cancel()
            return
        }
        val progress = ((state.outgoing.currentPosition - state.startPositionMs).toFloat() /
            state.durationMs).coerceIn(0f, 1f)
        val autoMix = state.autoMix
        if (autoMix == null) {
            val speed = state.baseSpeed * (state.incomingRate + (1f - state.incomingRate) * progress)
            if (abs(state.incoming.playbackParameters.speed - speed) > 0.005f) {
                state.incoming.playbackParameters = PlaybackParameters(speed)
            }
            state.outgoing.volume = cos(progress * PI / 2).toFloat()
            state.incoming.volume = sin(progress * PI / 2).toFloat()
        } else {
            // AutoMix：重叠期保持 BPM 匹配速率，最后 28% 用 smoothstep 回落到原速
            // （对应引擎 RampBack 阶段的 t²(3-2t)）。
            val rampT = ((progress - 0.72f) / 0.28f).coerceIn(0f, 1f)
            val smooth = rampT * rampT * (3 - 2 * rampT)
            val rate = state.incomingRate + (1f - state.incomingRate) * smooth
            val speed = state.baseSpeed * rate
            if (abs(state.incoming.playbackParameters.speed - speed) > 0.005f) {
                state.incoming.playbackParameters = PlaybackParameters(speed)
            }
            state.outgoing.volume = AutoMixBridge.gainOut(autoMix, progress.toDouble()).toFloat()
            state.incoming.volume = AutoMixBridge.gainIn(autoMix, progress.toDouble()).toFloat()
        }
        if (progress >= 1f || state.outgoing.playbackState == Player.STATE_ENDED ||
            state.outgoing.currentPosition >= state.outgoing.duration - 150L
        ) finish(state)
    }

    private fun finish(state: Fade) {
        state.incoming.volume = 1f
        state.incoming.playbackParameters = PlaybackParameters(state.baseSpeed)
        state.incoming.playWhenReady = state.outgoing.playWhenReady
        decks.promote()
        fade = null
        state.outgoing.stop()
        state.outgoing.clearMediaItems()
        state.outgoing.setPauseAtEndOfMediaItems(false)
        state.outgoing.volume = 0f
        target = null
        targetItem = null
        previewStartedAt = 0L
        previewFinished = false
        incomingEnvelope = emptyList()
        clearAnalysis()
    }


    /**
     * 为当前这一对歌曲建立 AutoMix 会话（先离线解码两段 PCM）。
     * 返回 null 表示条件不足（音源没解析出来 / 时长未知 / 变速播放）。
     */
    private fun buildMixSession(key: String, plan: MixPlan): AutoMixSession? {
        val sources = beatSources
        if (sources == null) {
            Timber.tag("SmartTransition").w("AutoMix skipped: source uris unavailable")
            failedMixPair = key
            return null
        }
        val outgoing = decks.active
        val incoming = decks.standby
        val duration = outgoing.duration
        if (duration == C.TIME_UNSET || duration <= 0L) {
            Timber.tag("SmartTransition").w("AutoMix skipped: unknown duration")
            failedMixPair = key
            return null
        }
        val baseSpeed = outgoing.playbackParameters.speed
        if (abs(baseSpeed - 1f) > 0.01f) {
            Timber.tag("SmartTransition").w("AutoMix skipped: playback speed %.2f != 1.0", baseSpeed)
            failedMixPair = key
            return null
        }
        val session = AutoMixSession(
            scope = scope,
            decoder = regionDecoder,
            initialPlan = plan,
            outgoing = outgoing,
            incoming = incoming,
            outgoingUri = sources.first,
            incomingUri = sources.second,
            outgoingDurationMs = duration,
            baseSpeed = baseSpeed,
            beatProvider = {
                // BeatNet 模型拍网格（Mei 自带权重）。任一侧失败返回 null，会话自动回退 DSP。
                runCatching {
                    beatNet.rhythms(sources.first, duration, sources.second)
                }.getOrNull()?.let { (outgoingRhythm, incomingRhythm) ->
                    outgoingRhythm?.let { BeatGridMarks.fromModel(it, "beatnet") } to
                        incomingRhythm?.let { BeatGridMarks.fromModel(it, "beatnet") }
                }
            },
            callbacks = object : AutoMixSession.Callbacks {
                override fun onHandback(contentSecondsB: Double) {
                    Timber.tag("SmartTransition").i(
                        "AutoMix handback complete, B continues at %.2fs", contentSecondsB,
                    )
                    completeAutoMix()
                }

                override fun onAborted(reason: String) {
                    Timber.tag("SmartTransition").w("AutoMix aborted: %s", reason)
                    failedMixPair = key
                    mixSession?.release()
                    mixSession = null
                    runCatching {
                        decks.active.volume = 1f
                        decks.standby.volume = 0f
                        decks.standby.playbackParameters =
                            PlaybackParameters(decks.active.playbackParameters.speed)
                    }
                    cancel()
                }
            },
        )
        session.prepare()
        return session
    }

    /** 交棒给 AutoMix：两个 deck 全部静音，只保留时间轴推进。 */
    private fun beginAutoMix(session: AutoMixSession, outgoing: ExoPlayer, incoming: ExoPlayer) {
        stopVolumeFade()
        val offset = (outgoing.currentPosition / 1000.0 - session.startAtSeconds).coerceAtLeast(0.0)
        Timber.tag("SmartTransition").i(
            "AutoMix begin at A=%.2fs (region offset %.3fs, duration=%dms)",
            outgoing.currentPosition / 1000.0, offset, outgoing.duration,
        )
        outgoing.setPauseAtEndOfMediaItems(true)
        outgoing.volume = 0f
        incoming.volume = 0f
        // 先把 B 粗定位到进入点，避免在 preseek 前把整首歌从头放一遍（白白浪费解码）。
        runCatching {
            incoming.seekTo((session.activePlan.bEntry * 1000).toLong().coerceAtLeast(0L))
        }
        runCatching { incoming.play() }
        session.start(offset)
    }

    /** 混音结束：立刻把 B 提为 active，旧的 A 停掉清空，准备下一轮。 */
    private fun completeAutoMix() {
        val outgoing = decks.active
        val incoming = decks.standby
        val speed = outgoing.playbackParameters.speed
        incoming.volume = 1f
        incoming.playbackParameters = PlaybackParameters(speed)
        // 注意：交接后 B 必须无条件继续播放。
        // A 的歌曲结尾早于交接点时，setPauseAtEndOfMediaItems 会让 A 的
        // playWhenReady 变成 false；把它抄给 B 就变成「新歌接班即暂停」。
        // 用户主动暂停时 MusicService 会另行发 pause，不需要在这里传递状态。
        incoming.playWhenReady = true
        promoteGuardUntil = SystemClock.elapsedRealtime() + 2_500L
        decks.promote()
        runCatching {
            outgoing.stop()
            outgoing.clearMediaItems()
            outgoing.setPauseAtEndOfMediaItems(false)
            outgoing.volume = 0f
        }
        // promote() 之后主动确认一次播放：incoming 可能在混音期间被 seek 到缓冲态，
        // 这里把音量与播放态钉定，避免被其它组件（音量平滑/音频焦点）淡掉。
        runCatching {
            incoming.volume = 1f
            incoming.play()
        }
        mixSession = null
        fade = null
        target = null
        targetItem = null
        previewStartedAt = 0L
        previewFinished = false
        incomingEnvelope = emptyList()
        clearAnalysis()
        Timber.tag("SmartTransition").i("AutoMix finished, deck promoted")
    }

    private fun cancel() {
        mixSession?.let { session ->
            if (session.isStarted) session.abort("controller cancel")
            session.release()
        }
        mixSession = null
        fade = null
        decks.active.setPauseAtEndOfMediaItems(false)
        decks.active.volume = 1f
        decks.standby.pause()
        decks.standby.stop()
        decks.standby.clearMediaItems()
        decks.standby.volume = 0f
        target = null
        targetItem = null
        previewStartedAt = 0L
        previewFinished = false
        incomingEnvelope = emptyList()
        clearAnalysis()
    }

    private fun clearAnalysis() {
        analysisJob?.cancel()
        analysisJob = null
        analysisAttempt = null
        beatPlan = null
        beatSources = null
        mixSession?.release()
        mixSession = null
    }

    private fun launchAnalysis(key: String, outgoingId: String, incomingId: String, durationMs: Long) {
        analysisAttempt = key
        analysisJob = scope.launch {
            try {
                val outgoingUri = resolveSource(outgoingId)
                val incomingUri = resolveSource(incomingId)
                if (analysisAttempt == key) beatSources = outgoingUri to incomingUri
                val result = beatNet.plan(outgoingId, outgoingUri, durationMs, incomingId, incomingUri)
                if (analysisAttempt == key) {
                    beatPlan = result
                    Timber.tag("SmartTransition").i(
                        "BeatNet plan: confidence=%.2f outgoingStart=%dms incomingStart=%dms duration=%dms rate=%.3f",
                        result?.confidence ?: -1f,
                        result?.outgoingStartMs ?: -1L,
                        result?.incomingStartMs ?: -1L,
                        result?.durationMs ?: -1L,
                        result?.incomingRate ?: -1f,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: LinkageError) {
                Timber.tag("SmartTransition").w(error, "BeatNet native linkage unavailable; using audio envelope")
            } catch (error: Exception) {
                Timber.tag("SmartTransition").w(error, "BeatNet analysis unavailable; using audio envelope")
            } finally {
                if (analysisAttempt == key) analysisJob = null
            }
        }
    }

    fun release() {
        preferenceJob.cancel()
        monitorJob.cancel()
        decks.onQueueEdited = null
        decks.onSeekRequested = null
        cancel()
    }
}
