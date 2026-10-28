package com.ljyh.mei.playback.automix

import kotlin.math.roundToLong

/**
 * 一段已离线解码的 PCM 区域（交错浮点，-1..1）。
 * 对应 C# `AutoMix/MixRegion.cs`。
 */
internal class MixRegion(
    val samples: FloatArray,
    val sampleRate: Int,
    val startSeconds: Double,
    val channelCount: Int = 2,
) {
    val frames: Int get() = samples.size / channelCount
    val endSeconds: Double get() = startSeconds + frames / sampleRate.toDouble()

    fun indexOf(contentSeconds: Double): Long =
        ((contentSeconds - startSeconds) * sampleRate).roundToLong()

    fun sampleAt(frame: Long, channel: Int): Double {
        if (frame < 0 || frame >= frames) return 0.0
        val index = (frame * channelCount + channel).toInt()
        return if (index in samples.indices) samples[index].toDouble() else 0.0
    }

    /** 越界时钳位到 [0, frames-1]，供插值读取使用（避免首尾出现 0 值凹陷）。 */
    fun safeSample(frame: Int, channel: Int): Double {
        val clamped = frame.coerceIn(0, (frames - 1).coerceAtLeast(0))
        val index = (clamped.toLong() * channelCount + channel).toInt()
        return if (index in samples.indices) samples[index].toDouble() else 0.0
    }

    companion object {
        fun empty(sampleRate: Int, startSeconds: Double): MixRegion =
            MixRegion(FloatArray(0), sampleRate, startSeconds)
    }
}
