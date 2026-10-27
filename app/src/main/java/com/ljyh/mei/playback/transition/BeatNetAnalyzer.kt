package com.ljyh.mei.playback.transition

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.LinkedHashMap

/** Analyzes the end and start of two songs with the published BeatNet model. */
internal class BeatNetAnalyzer(context: Context) {
    private val decoder = BeatNetAudioDecoder(context)
    private val features = BeatNetFeatures()
    private val runtime by lazy { BeatNetRuntime(context) }
    private val mutex = Mutex()
    private val cache = object : LinkedHashMap<String, BeatNetRhythm>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BeatNetRhythm>?) = size > 8
    }

    suspend fun plan(
        outgoingId: String,
        outgoingUri: Uri,
        outgoingDurationMs: Long,
        incomingId: String,
        incomingUri: Uri,
    ): BeatNetTransitionPlan? = mutex.withLock {
        val outgoingStart = (outgoingDurationMs - 32_000L).coerceAtLeast(0L)
        val outgoing = analyze("$outgoingId@$outgoingStart@$outgoingUri", outgoingUri, outgoingStart)
        val incoming = analyze("$incomingId@0@$incomingUri", incomingUri, 0L)
        BeatNetRhythmPlanner.plan(outgoing, incoming, outgoingDurationMs)
    }

    /**
     * 只跑模型、不做规划：把两首歌的拍点/小节点原样交给 AutoMix。
     *
     * AutoMix 要的是拍网格本身（它自己按拍点对齐退出点、进入点和重叠长度），
     * 而不是 [BeatNetRhythmPlanner.plan] 里写死的四小节方案。任一侧失败返回 null，
     * 调用方回退到内置 DSP 检测。
     */
    suspend fun rhythms(
        outgoingUri: Uri,
        outgoingDurationMs: Long,
        incomingUri: Uri,
    ): Pair<BeatNetRhythm?, BeatNetRhythm?> = mutex.withLock {
        val outgoingStart = (outgoingDurationMs - 32_000L).coerceAtLeast(0L)
        val outgoing = runCatching {
            analyze("out@$outgoingStart@$outgoingUri", outgoingUri, outgoingStart)
        }.getOrNull()
        val incoming = runCatching {
            analyze("in@0@$incomingUri", incomingUri, 0L)
        }.getOrNull()
        Timber.tag("BeatNet").i(
            "rhythms: outgoing=%s incoming=%s",
            outgoing?.let {
                "%.1fBPM conf=%.2f beats=%d bars=%d".format(
                    it.bpm, it.confidence, it.beatsMs.size, it.downbeatsMs.size,
                )
            } ?: "失败",
            incoming?.let {
                "%.1fBPM conf=%.2f beats=%d bars=%d".format(
                    it.bpm, it.confidence, it.beatsMs.size, it.downbeatsMs.size,
                )
            } ?: "失败",
        )
        outgoing to incoming
    }

    private suspend fun analyze(key: String, uri: Uri, startMs: Long): BeatNetRhythm {
        cache[key]?.let { return it }
        val pcm = try {
            decoder.decode(uri, startMs)
        } catch (error: Throwable) {
            Timber.tag("BeatNet").w(error, "decode failed: uri=%s start=%dms", uri, startMs)
            throw error
        }
        currentCoroutineContext().ensureActive()
        val activations = try {
            withContext(Dispatchers.Default) {
                runtime.run(features.extract(pcm.samples, pcm.sampleRate))
            }
        } catch (error: Throwable) {
            Timber.tag("BeatNet").w(
                error, "inference failed: samples=%d rate=%d", pcm.samples.size, pcm.sampleRate,
            )
            throw error
        }
        currentCoroutineContext().ensureActive()
        val rhythm = BeatNetRhythmPlanner.decode(activations, startMs)
        if (rhythm == null) {
            val beats = FloatArray(activations.size / 2) { activations[2 * it] }
            Timber.tag("BeatNet").w(
                "no stable pulse: start=%dms samples=%d rate=%d beatActivation(mean=%.4f max=%.4f)",
                startMs, pcm.samples.size, pcm.sampleRate, beats.average(), beats.maxOrNull() ?: 0f,
            )
            error("BeatNet did not find a stable pulse")
        }
        cache[key] = rhythm
        return rhythm
    }
}
