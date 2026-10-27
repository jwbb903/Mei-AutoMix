package com.ljyh.mei.playback.automix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * AutoMix 引擎的离线验证：不需要真机，直接把两段合成的 PCM 喂进 [AutoMixEngine]，
 * 按 1024 帧一个 quantum 跑完整条时间线，检查混音输出是否连续、有界、增益单调。
 *
 * 这层测试存在的意义：AutoMix 的数学（交叉淡化曲线、WSOLA 变速、软限幅、回速）
 * 一旦有偏差，在真机上只会表现为「听起来有点怪」，很难定位。用合成信号可以在
 * CI 里就卡住这类回归。
 */
class AutoMixEngineTest {

    private val sampleRate = 48_000

    private class RecordingHost(override val samplesPerQuantum: Int) : AutoMixHost {
        val speeds = mutableListOf<Double>()
        val plays = mutableListOf<Int>()
        override fun setVolume(voiceId: Int, volume: Double) = Unit
        override fun setSpeed(voiceId: Int, speed: Double) {
            speeds += speed
        }

        override fun play(voiceId: Int) {
            plays += voiceId
        }

        override fun seek(voiceId: Int, positionSeconds: Double) = Unit
    }

    private fun sineRegion(
        frequency: Double,
        seconds: Double,
        startSeconds: Double = 0.0,
        amplitude: Float = 0.4f,
    ): MixRegion {
        val frames = (seconds * sampleRate).toInt()
        val samples = FloatArray(frames * 2)
        for (frame in 0 until frames) {
            val value = (amplitude * sin(2 * PI * frequency * frame / sampleRate)).toFloat()
            samples[frame * 2] = value
            samples[frame * 2 + 1] = value
        }
        return MixRegion(samples, sampleRate, startSeconds)
    }

    private fun runTimeline(
        plan: MixPlan,
        regionA: MixRegion,
        regionB: MixRegion,
        maxSeconds: Double = 25.0,
    ): Triple<FloatArray, AutoMixEngine, RecordingHost> {
        val host = RecordingHost(1024)
        val engine = AutoMixEngine(
            host = host,
            plan = plan,
            regionA = regionA,
            regionB = regionB,
            voiceA = 1,
            voiceB = 2,
            volumeA = 1.0,
            volumeB = 1.0,
        )
        engine.startOffline(0L)
        val quanta = (maxSeconds * sampleRate / 1024).toInt()
        var collected = FloatArray(0)
        val chunk = FloatArray(1024 * 2)
        repeat(quanta) {
            if (engine.stage == AutoMixStage.Done || engine.stage == AutoMixStage.Aborted) return@repeat
            engine.onQuantum()
            java.util.Arrays.fill(chunk, 0f)
            engine.render(chunk, 1024)
            collected += chunk.copyOf()
        }
        return Triple(collected, engine, host)
    }

    private fun standardPlan(
        technique: MixTechnique = MixTechnique.Crossfade,
        rate: Double = 1.06,
        rampBack: Double = 2.0,
        handoff: Double = 0.5,
    ) = MixPlan(
        technique = technique,
        aExit = 10.0,
        overlap = 3.0,
        bEntry = 1.5,
        rate = rate,
        rampBack = rampBack,
        handoff = handoff,
        gainB = 1.0,
    )

    @Test
    fun `output is finite and bounded`() {
        val (output, engine, _) = runTimeline(standardPlan(), sineRegion(220.0, 13.0), sineRegion(330.0, 13.0))
        assertTrue("engine rendered nothing", output.isNotEmpty())
        var peak = 0f
        output.forEach { sample ->
            assertTrue("non-finite sample $sample", sample.isFinite())
            peak = maxOf(peak, abs(sample))
        }
        // 软限幅 + 0.97 天花板：任何时刻都不该超过满刻度。
        assertTrue("peak $peak exceeded limiter ceiling", peak <= 1.0001f)
        assertTrue("peak $peak suspiciously silent (mix produced nothing)", peak > 0.05f)
        println("AutoMix peak=$peak rendered=${engine.renderedSeconds}s stage=${engine.stage}")
    }

    @Test
    fun `no clicks at handover boundary`() {
        val (output, _, _) = runTimeline(standardPlan(), sineRegion(220.0, 13.0), sineRegion(330.0, 13.0))
        var maxDelta = 0f
        var maxDeltaAt = 0
        for (index in 2 until output.size step 2) {
            val delta = abs(output[index] - output[index - 2])
            if (delta > maxDelta) {
                maxDelta = delta
                maxDeltaAt = index / 2
            }
        }
        val seconds = maxDeltaAt / sampleRate.toDouble()
        // 220Hz@0.4 与 330Hz@0.4 的自然逐样本差分 ~0.023；交叉淡化期允许更大但仍须远小于 0.5。
        assertTrue(
            "discontinuity $maxDelta at ${"%.3f".format(seconds)}s looks like a click",
            maxDelta < 0.5f,
        )
        println("AutoMix maxDelta=$maxDelta at ${"%.3f".format(seconds)}s")
    }

    @Test
    fun `incoming gain rises monotonically through overlap`() {
        val plan = standardPlan()
        val host = RecordingHost(1024)
        val engine = AutoMixEngine(
            host = host,
            plan = plan,
            regionA = sineRegion(220.0, 13.0),
            regionB = sineRegion(330.0, 13.0),
            voiceA = 1,
            voiceB = 2,
            volumeA = 1.0,
            volumeB = 1.0,
        )
        engine.startOffline(0L)
        val chunk = FloatArray(1024 * 2)
        var lastVolume = -1.0
        var samples = 0
        var violations = 0
        repeat((13.0 * sampleRate / 1024).toInt()) {
            if (engine.stage == AutoMixStage.Done || engine.stage == AutoMixStage.Aborted) return@repeat
            engine.onQuantum()
            java.util.Arrays.fill(chunk, 0f)
            engine.render(chunk, 1024)
            if (engine.bStarted && engine.stage == AutoMixStage.Overlap) {
                val volume = engine.volumeOfB
                if (lastVolume >= 0.0 && volume + 1e-6 < lastVolume) violations++
                lastVolume = volume
                samples++
            }
        }
        assertTrue("B 的增益在重叠期回退了 $violations 次", violations == 0)
        assertTrue("重叠期没有采样到 B 的增益", samples > 0)
        assertEquals("重叠结束时 B 应该已经满音量", 1.0, lastVolume, 0.05)
    }

    @Test
    fun `content position of B follows the stretch rate`() {
        val plan = standardPlan(rate = 1.06, rampBack = 0.0)
        val host = RecordingHost(1024)
        val engine = AutoMixEngine(
            host = host,
            plan = plan,
            regionA = sineRegion(220.0, 13.0),
            regionB = sineRegion(330.0, 13.0),
            voiceA = 1,
            voiceB = 2,
            volumeA = 1.0,
            volumeB = 1.0,
        )
        engine.startOffline(0L)
        val chunk = FloatArray(1024 * 2)
        var contentAtStart = Double.NaN
        var renderedAtStart = Double.NaN
        var lastContent = Double.NaN
        var lastRendered = Double.NaN
        repeat((12.0 * sampleRate / 1024).toInt()) {
            if (engine.stage == AutoMixStage.Done || engine.stage == AutoMixStage.Aborted) return@repeat
            engine.onQuantum()
            java.util.Arrays.fill(chunk, 0f)
            engine.render(chunk, 1024)
            if (engine.bStarted) {
                val content = engine.contentSecondsB
                if (contentAtStart.isNaN()) {
                    contentAtStart = content
                    renderedAtStart = engine.renderedSeconds
                }
                lastContent = content
                lastRendered = engine.renderedSeconds
            }
        }
        val elapsed = lastRendered - renderedAtStart
        val advanced = lastContent - contentAtStart
        assertTrue("B 的内容位置没有推进", advanced > 0.0)
        // 重叠期 B 按 plan.rate 推进（1 quantum 的误差容差）。
        val expected = elapsed * plan.rate
        assertTrue(
            "B 推进 ${"%.3f".format(advanced)}s，期望约 ${"%.3f".format(expected)}s（rate=${plan.rate}）",
            abs(advanced - expected) < 0.15,
        )
    }

    @Test
    fun `every technique renders without throwing`() {
        MixTechnique.entries.forEach { technique ->
            val (output, engine, _) = runTimeline(
                standardPlan(technique = technique),
                sineRegion(220.0, 13.0),
                sineRegion(330.0, 13.0),
            )
            assertTrue("$technique produced no audio", output.isNotEmpty())
            assertTrue("$technique stage=${engine.stage}", engine.stage != AutoMixStage.Aborted)
            var peak = 0f
            output.forEach { peak = maxOf(peak, abs(it)) }
            assertTrue("$technique peak=$peak out of range", peak > 0.01f && peak <= 1.0001f)
        }
    }
}
