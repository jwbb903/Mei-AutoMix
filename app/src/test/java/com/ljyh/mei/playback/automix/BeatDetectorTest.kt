package com.ljyh.mei.playback.automix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** [BeatDetector] 的离线验证：合成固定 BPM 的鼓点，看能否找回周期与相位。 */
class BeatDetectorTest {

    private val sampleRate = 48_000

    /** 生成一段「底鼓 + 踩镲」的节奏，bpm 固定，首拍在 firstBeatMs。 */
    private fun drumLoop(
        bpm: Double,
        seconds: Double,
        firstBeatMs: Double = 0.0,
        seed: Int = 7,
    ): FloatArray {
        val frames = (seconds * sampleRate).toInt()
        val buffer = FloatArray(frames)
        val random = Random(seed)
        val beatInterval = 60.0 / bpm
        var beatTime = firstBeatMs / 1000.0
        while (beatTime < seconds) {
            val start = (beatTime * sampleRate).toInt()
            // 底鼓：低频衰减正弦 ~120ms
            val kickLength = (0.12 * sampleRate).toInt()
            for (index in 0 until kickLength) {
                val position = start + index
                if (position !in buffer.indices) break
                val t = index.toDouble() / sampleRate
                val envelope = exp(-t * 28.0)
                val tone = sin(2 * PI * (55.0 + 90.0 * exp(-t * 40.0)) * t)
                buffer[position] += (0.55 * envelope * tone).toFloat()
            }
            // 踩镲：短噪声，放在拍点后半拍（8 分音符）
            val hatStart = ((beatTime + beatInterval / 2) * sampleRate).toInt()
            val hatLength = (0.04 * sampleRate).toInt()
            for (index in 0 until hatLength) {
                val position = hatStart + index
                if (position !in buffer.indices) break
                val envelope = exp(-index.toDouble() / sampleRate * 90.0)
                buffer[position] += ((random.nextFloat() * 2f - 1f) * 0.16 * envelope).toFloat()
            }
            beatTime += beatInterval
        }
        return buffer
    }

    @Test
    fun `detects 120 bpm`() {
        val grid = BeatDetector.detect(drumLoop(bpm = 120.0, seconds = 12.0), sampleRate)
        assertNotNull("120 BPM 没检测出来", grid)
        grid!!
        println("120bpm -> bpm=${grid.bpm} period=${grid.periodMs}ms first=${grid.firstBeatMs}ms conf=${grid.confidence}")
        assertEquals("BPM 偏差过大", 120.0, grid.bpm, 4.0)
        assertEquals("拍周期偏差过大", 500.0, grid.periodMs, 20.0)
    }

    @Test
    fun `detects 96 bpm`() {
        val grid = BeatDetector.detect(drumLoop(bpm = 96.0, seconds = 14.0), sampleRate)
        assertNotNull("96 BPM 没检测出来", grid)
        grid!!
        println("96bpm -> bpm=${grid.bpm} period=${grid.periodMs}ms first=${grid.firstBeatMs}ms conf=${grid.confidence}")
        assertEquals("BPM 偏差过大", 96.0, grid.bpm, 4.0)
    }

    @Test
    fun `keeps beat phase close to the real downbeat`() {
        val firstBeat = 320.0
        val grid = BeatDetector.detect(
            drumLoop(bpm = 128.0, seconds = 12.0, firstBeatMs = firstBeat),
            sampleRate,
        )
        assertNotNull(grid)
        grid!!
        // 相位可能存在 ±1 拍的整体偏移（梳状搜索的固有多解），但映射回拍点集合要一致。
        val nearest = grid.nearest(firstBeat)
        val drift = abs(((nearest - firstBeat) % grid.periodMs + grid.periodMs) % grid.periodMs)
        val error = minOf(drift, grid.periodMs - drift)
        println("128bpm phase: first=${grid.firstBeatMs} nearestToTrue=$nearest error=${error}ms")
        assertTrue("拍点相位偏离 ${error}ms", error < 30.0)
    }

    @Test
    fun `beatsIn returns a regular grid`() {
        val grid = BeatDetector.detect(drumLoop(bpm = 120.0, seconds = 12.0), sampleRate)!!
        val beats = grid.beatsIn(1_000.0, 4_000.0)
        assertTrue("拍点数量异常: ${beats.size}", beats.size in 5..8)
        beats.zipWithNext().forEach { (a, b) ->
            assertEquals("拍点间隔不均匀", grid.periodMs, b - a, 1e-6)
        }
    }

    @Test
    fun `silence yields no grid`() {
        assertNull(BeatDetector.detect(FloatArray(sampleRate * 5), sampleRate))
    }

    @Test
    fun `too short input yields no grid`() {
        assertNull(BeatDetector.detect(FloatArray(1024), sampleRate))
    }
}
