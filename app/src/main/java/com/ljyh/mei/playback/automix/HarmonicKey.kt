package com.ljyh.mei.playback.automix

import kotlin.math.abs
import kotlin.math.min

/**
 * 调性距离（Camelot / 五度圈）。
 *
 * C# 侧使用 `KeyEstimator.CamelotDistance(a.Key, b.Key)`；Mei 的调性来自
 * 元数据（`TrackAudioFeatures.keyMode` 之类的字符串），因此这里只需要把
 * 常见写法（"C major" / "Am" / "8A" / "F#m"）映射到 Camelot 轮盘后求距离。
 */
internal object HarmonicKey {

    /** Camelot 轮盘: 1A..12A 小调, 1B..12B 大调。 */
    private val cameloWheel = mapOf(
        "ab minor" to (1 to 'A'), "g# minor" to (1 to 'A'),
        "b major" to (1 to 'B'),
        "eb minor" to (2 to 'A'), "d# minor" to (2 to 'A'),
        "f# major" to (2 to 'B'), "gb major" to (2 to 'B'),
        "bb minor" to (3 to 'A'), "a# minor" to (3 to 'A'),
        "db major" to (3 to 'B'), "c# major" to (3 to 'B'),
        "f minor" to (4 to 'A'),
        "ab major" to (4 to 'B'), "g# major" to (4 to 'B'),
        "c minor" to (5 to 'A'),
        "eb major" to (5 to 'B'), "d# major" to (5 to 'B'),
        "g minor" to (6 to 'A'),
        "bb major" to (6 to 'B'), "a# major" to (6 to 'B'),
        "d minor" to (7 to 'A'),
        "f major" to (7 to 'B'),
        "a minor" to (8 to 'A'),
        "c major" to (8 to 'B'),
        "e minor" to (9 to 'A'),
        "g major" to (9 to 'B'),
        "b minor" to (10 to 'A'),
        "d major" to (10 to 'B'),
        "f# minor" to (11 to 'A'), "gb minor" to (11 to 'A'),
        "a major" to (11 to 'B'),
        "c# minor" to (12 to 'A'), "db minor" to (12 to 'A'),
        "e major" to (12 to 'B'),
    )

    /** 无法识别时返回一个"中性"值，调用方据此跳过调性判断。 */
    fun camelotDistance(first: String, second: String): Int {
        val a = resolve(first) ?: return 0
        val b = resolve(second) ?: return 0
        val wheel = min(
            abs(a.first - b.first),
            12 - abs(a.first - b.first),
        )
        return if (a.second == b.second) wheel else wheel + 1
    }

    private fun resolve(raw: String): Pair<Int, Char>? {
        val text = raw.trim().lowercase().replace("♯", "#").replace("♭", "b")
        if (text.isEmpty()) return null
        // 已经是 Camelot 记号。
        val cameloMatch = Regex("^(\\d{1,2})\\s*([ab])$").find(text)
        if (cameloMatch != null) {
            val number = cameloMatch.groupValues[1].toIntOrNull() ?: return null
            if (number in 1..12) return number to cameloMatch.groupValues[2].uppercase()[0]
        }
        val mode = when {
            text.contains("minor") || text.endsWith("m") || text.contains("min") -> "minor"
            text.contains("major") || text.contains("maj") -> "major"
            else -> return null
        }
        val tonic = text
            .replace("minor", "")
            .replace("major", "")
            .replace("min", "")
            .replace("maj", "")
            .replace("m", "")
            .trim()
            .replace(" ", "")
        if (tonic.isEmpty()) return null
        return cameloWheel["$tonic $mode"]
    }
}
