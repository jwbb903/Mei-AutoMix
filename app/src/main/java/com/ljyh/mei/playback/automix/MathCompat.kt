package com.ljyh.mei.playback.automix

/**
 * 移植自 C# 的算法代码沿用 `Math.Clamp(value, min, max)` 的前缀写法。
 * Kotlin 只有 `coerceIn`，这里补一组顶层函数（注意不能用扩展函数，
 * 否则与同名顶层声明产生 JVM 签名冲突）。
 */
internal fun clamp(value: Double, min: Double, max: Double): Double =
    if (value < min) min else if (value > max) max else value

internal fun clamp(value: Float, min: Float, max: Float): Float =
    if (value < min) min else if (value > max) max else value

internal fun clamp(value: Long, min: Long, max: Long): Long =
    if (value < min) min else if (value > max) max else value

internal fun clamp(value: Int, min: Int, max: Int): Int =
    if (value < min) min else if (value > max) max else value
