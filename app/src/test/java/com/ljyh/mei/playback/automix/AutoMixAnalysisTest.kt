package com.ljyh.mei.playback.automix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** 响度计：只验证相对关系（绝对标定依赖 K 加权，不适合在单元测试里钉死）。 */
class LoudnessMeterTest {

    private fun sine(amplitude: Double, seconds: Double, rate: Int = 48_000): FloatArray {
        val frames = (seconds * rate).toInt()
        val out = FloatArray(frames * 2)
        for (frame in 0 until frames) {
            val value = (amplitude * sin(2 * PI * 440.0 * frame / rate)).toFloat()
            out[frame * 2] = value
            out[frame * 2 + 1] = value
        }
        return out
    }

    @Test
    fun `doubling amplitude adds about 6 LU`() {
        val quiet = LoudnessMeter.integratedLufs(sine(0.05, 3.0), 48_000, 2)
        val loud = LoudnessMeter.integratedLufs(sine(0.10, 3.0), 48_000, 2)
        assertTrue("quiet=$quiet", quiet < loud)
        assertEquals("260% 增益应该是 +6 LU", 6.0, loud - quiet, 0.4)
    }

    @Test
    fun `silence returns the silent sentinel`() {
        val silence = FloatArray(48_000 * 2)
        assertEquals(LoudnessMeter.SILENT_LUFS, LoudnessMeter.integratedLufs(silence, 48_000, 2), 0.001)
    }

    @Test
    fun `trim is clamped and sign-correct`() {
        // A 比 B 响 8 LU → A 应该被压 8 dB
        assertEquals(-8.0, LoudnessMeter.trimDb(referenceLufs = -10.0, targetLufs = -18.0), 0.001)
        // 极端差值被夹在 -12..+6
        assertEquals(-12.0, LoudnessMeter.trimDb(referenceLufs = 0.0, targetLufs = -30.0), 0.001)
        assertEquals(6.0, LoudnessMeter.trimDb(referenceLufs = -30.0, targetLufs = 0.0), 0.001)
        // 任一侧静音 → 不补偿
        assertEquals(0.0, LoudnessMeter.trimDb(LoudnessMeter.SILENT_LUFS, -10.0), 0.001)
    }
}

/** 结构分析：能量包络要能反映段落能量变化。 */
class StructureAnalyzerTest {

    @Test
    fun `energy reflects amplitude change`() {
        val rate = 48_000
        val seconds = 10
        val samples = FloatArray(seconds * rate * 2)
        for (frame in 0 until seconds * rate) {
            // 前 5 秒响，后 5 秒轻（10 倍幅度差 → 能量差 10 倍）
            val amplitude = if (frame < 5 * rate) 0.4 else 0.04
            val value = (amplitude * sin(2 * PI * 220.0 * frame / rate)).toFloat()
            samples[frame * 2] = value
            samples[frame * 2 + 1] = value
        }
        val analyser = StructureAnalyzer.analyze(samples, rate, 0.0)
        val loudPart = analyser.energyBetween(0.0, 4.5)
        val quietPart = analyser.energyBetween(5.5, 9.5)
        assertTrue("loud=$loudPart quiet=$quietPart", loudPart > quietPart * 3)
        assertTrue("尾段应判为低能量", analyser.isLowEnergy(5.5, 9.5, threshold = 0.8))
    }
}

/** 换句点选择：优先 16 小节，且必须落在小节线上。 */
class PhraseAlignerTest {

    private fun grid(bpm: Double, from: Double, to: Double): BeatGridMarks {
        val bar = 60.0 * 4 / bpm
        val downbeats = generateSequence(from) { it + bar }
            .takeWhile { it <= to }
            .toList()
            .toDoubleArray()
        return BeatGridMarks(
            bpm = bpm,
            confidence = 0.9,
            beats = downbeats,
            downbeats = downbeats,
            source = "test",
        )
    }

    private fun flatStructure(rate: Int = 48_000): StructureAnalyzer {
        val frames = 60 * rate
        val samples = FloatArray(frames * 2)
        for (frame in 0 until frames) {
            val value = (0.3 * sin(2 * PI * 220.0 * frame / rate)).toFloat()
            samples[frame * 2] = value
            samples[frame * 2 + 1] = value
        }
        return StructureAnalyzer.analyze(samples, rate, 0.0)
    }

    @Test
    fun `picks a 16 bar boundary when everything is equal`() {
        val choice = PhraseAligner.chooseExit(
            target = 30.0,
            grid = grid(120.0, 0.0, 60.0),
            structure = flatStructure(),
            aStart = 0.0,
            aEnd = 60.0,
            overlap = 4.0,
        )
        assertTrue("没有选出候选", choice != null)
        assertEquals("应该优先 16 小节乐句", 16, choice!!.bars)
        assertEquals("120BPM 的 16 小节 = 32s", 32.0, choice.exit, 0.01)
    }

    @Test
    fun `never leaves the usable region`() {
        val choice = PhraseAligner.chooseExit(
            target = 100.0,
            grid = grid(128.0, 0.0, 200.0),
            structure = flatStructure(),
            aStart = 0.0,
            aEnd = 60.0,
            overlap = 6.0,
        )
        assertTrue(choice != null)
        assertTrue("exit=${choice!!.exit} 越界", choice.exit <= 60.0 - 6.0 - 0.3 + 1e-6)
        assertTrue("exit=${choice.exit} 太靠前", choice.exit >= 1.5 - 1e-6)
    }
}
