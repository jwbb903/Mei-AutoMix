package com.ljyh.mei.playback.automix

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 立体声双二阶滤波器（RBJ cookbook）。
 * 直接移植 HyPlayer `AutoMix/Biquad.cs` 的 `StereoBiquad`，用于过渡期的
 * 高通扫频（把正在离开的曲目逐渐"抠掉"低频）和低频架衰减。
 */
internal class StereoBiquad {
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0

    private var x1L = 0.0; private var x2L = 0.0; private var y1L = 0.0; private var y2L = 0.0
    private var x1R = 0.0; private var x2R = 0.0; private var y1R = 0.0; private var y2R = 0.0

    var bypassed = true
        private set

    fun setBypass() {
        bypassed = true
    }

    fun setHighPass(frequency: Double, sampleRate: Double, q: Double = 0.7071) {
        val w0 = 2 * PI * frequency.coerceIn(10.0, sampleRate * 0.45) / sampleRate
        val cosW = cos(w0)
        val alpha = sin(w0) / (2 * q)
        val a0 = 1 + alpha
        configure(
            (1 + cosW) / 2 / a0,
            -(1 + cosW) / a0,
            (1 + cosW) / 2 / a0,
            -2 * cosW / a0,
            (1 - alpha) / a0,
        )
    }

    fun setLowShelf(frequency: Double, gainDb: Double, sampleRate: Double) {
        if (abs(gainDb) < 0.01) {
            setBypass()
            return
        }
        val a = 10.0.pow(gainDb / 40)
        val w0 = 2 * PI * frequency / sampleRate
        val cosW = cos(w0)
        val sinW = sin(w0)
        val alpha = sinW / 2 * sqrt(2.0)
        val sqrtA = sqrt(a)
        val a0 = (a + 1) + (a - 1) * cosW + 2 * sqrtA * alpha
        configure(
            a * ((a + 1) - (a - 1) * cosW + 2 * sqrtA * alpha) / a0,
            2 * a * ((a - 1) - (a + 1) * cosW) / a0,
            a * ((a + 1) - (a - 1) * cosW - 2 * sqrtA * alpha) / a0,
            -2 * ((a - 1) + (a + 1) * cosW) / a0,
            ((a + 1) + (a - 1) * cosW - 2 * sqrtA * alpha) / a0,
        )
    }

    private fun configure(nb0: Double, nb1: Double, nb2: Double, na1: Double, na2: Double) {
        if (bypassed) {
            x1L = 0.0; x2L = 0.0; y1L = 0.0; y2L = 0.0
            x1R = 0.0; x2R = 0.0; y1R = 0.0; y2R = 0.0
        }
        b0 = nb0; b1 = nb1; b2 = nb2; a1 = na1; a2 = na2
        bypassed = false
    }

    /** 原地处理交错立体声 [frames] 帧（按 2 声道步进）。 */
    fun process(interleaved: FloatArray, frames: Int) {
        if (bypassed) return
        for (i in 0 until frames) {
            val left = interleaved[i * 2].toDouble()
            val yl = b0 * left + b1 * x1L + b2 * x2L - a1 * y1L - a2 * y2L
            x2L = x1L; x1L = left; y2L = y1L; y1L = yl

            val right = interleaved[i * 2 + 1].toDouble()
            val yr = b0 * right + b1 * x1R + b2 * x2R - a1 * y1R - a2 * y2R
            x2R = x1R; x1R = right; y2R = y1R; y1R = yr

            interleaved[i * 2] = yl.toFloat()
            interleaved[i * 2 + 1] = yr.toFloat()
        }
    }
}
