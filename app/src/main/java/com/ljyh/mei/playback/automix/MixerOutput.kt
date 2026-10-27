package com.ljyh.mei.playback.automix

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AutoMix 的 PCM 输出侧：一个 float32 [AudioTrack]，以高优先级线程阻塞式写入。
 *
 * 上游 C# 靠 WASAPI 的 `GraphMixHost` 直接拿到一条混音总线；Android 上没有等价物，
 * 只能自己开一路 AudioTrack 出声（报告 `HyPlayer-Nextgen AutoMix 移植可行性分析` 的
 * 方案 A）。代价是混音期间必须把两个 ExoPlayer 的音量压到 0，否则会双份出声。
 */
internal class MixerOutput(
    private val sampleRate: Int,
    private val channels: Int = 2,
    private val quantumFrames: Int = 1024,
) {
    /** 混音源：每个 quantum 调用一次 [onQuantum]，再由 [render] 填 PCM。 */
    interface Source {
        val finished: Boolean
        fun onQuantum(frames: Int)
        fun render(mix: FloatArray, frames: Int)
    }

    private var track: AudioTrack? = null
    private var thread: Thread? = null
    private val running = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private var source: Source? = null
    private var fadeOutTotal = 0
    private var fadeOutDone = 0
    private var writtenFrames = 0L

    val isRunning: Boolean get() = running.get()
    val isPaused: Boolean get() = paused.get()
    val framesWritten: Long get() = writtenFrames

    /** 已写入但还没被听到的时长（秒）。 */
    fun latencySeconds(): Double {
        val audioTrack = track ?: return 0.0
        return try {
            latencyFromTrack(audioTrack)
        } catch (error: Throwable) {
            quantumFrames * 2.0 / sampleRate
        }
    }

    private fun latencyFromTrack(audioTrack: AudioTrack): Double {
        val timestamp = android.media.AudioTimestamp()
        return if (audioTrack.getTimestamp(timestamp)) {
            val playedFrames = timestamp.framePosition
            val bufferDelay = (writtenFrames - playedFrames).coerceAtLeast(0L)
            bufferDelay / sampleRate.toDouble()
        } else {
            quantumFrames * 2.0 / sampleRate
        }
    }

    fun start(source: Source) {
        if (running.getAndSet(true)) return
        this.source = source
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        val bufferBytes = maxOf(minBuffer, quantumFrames * channels * 4 * 4)
        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = audioTrack
        audioTrack.play()
        writtenFrames = 0L
        fadeOutTotal = 0
        fadeOutDone = 0
        Timber.tag(TAG).i(
            "MixerOutput start: %d Hz, buffer %d bytes (%.0f ms), quantum %d",
            sampleRate, bufferBytes, bufferBytes * 1000.0 / (sampleRate * channels * 4), quantumFrames,
        )
        thread = Thread({ pump() }, "AutoMix-Mixer").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun pump() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val mix = FloatArray(quantumFrames * channels)
        while (running.get()) {
            if (paused.get()) {
                Thread.sleep(5)
                continue
            }
            val src = source
            if (src == null || src.finished) {
                Timber.tag(TAG).i("MixerOutput source finished after %d frames", writtenFrames)
                break
            }
            java.util.Arrays.fill(mix, 0f)
            try {
                src.onQuantum(quantumFrames)
                src.render(mix, quantumFrames)
            } catch (error: Throwable) {
                Timber.tag(TAG).e(error, "MixerOutput render failed")
                break
            }
            // 交接淡出：把最后一段乘一条线性斜坡，避免硬切出咔哒声。
            if (fadeOutTotal > 0) {
                for (i in 0 until quantumFrames) {
                    val remaining = fadeOutTotal - fadeOutDone - i
                    val gain = if (remaining <= 0) 0f else remaining.toFloat() / fadeOutTotal
                    mix[i * 2] *= gain
                    mix[i * 2 + 1] *= gain
                }
                fadeOutDone += quantumFrames
            }
            val audioTrack = track ?: break
            val written = audioTrack.write(mix, 0, mix.size, AudioTrack.WRITE_BLOCKING)
            if (written <= 0) {
                Timber.tag(TAG).w("MixerOutput write returned %d", written)
                break
            }
            writtenFrames += written / channels
            if (fadeOutTotal > 0 && fadeOutDone >= fadeOutTotal) {
                Timber.tag(TAG).i("MixerOutput fade-out complete after %d frames", writtenFrames)
                break
            }
        }
        release()
    }

    fun pause() {
        paused.set(true)
        runCatching { track?.pause() }
    }

    fun resume() {
        paused.set(false)
        runCatching { track?.play() }
    }

    /** 请求淡出 [seconds] 后停止（用于把声音交还给 ExoPlayer）。 */
    fun requestFadeOut(seconds: Double) {
        fadeOutTotal = maxOf(1, (seconds * sampleRate).toInt() / quantumFrames * quantumFrames)
        fadeOutDone = 0
        Timber.tag(TAG).i("MixerOutput fade-out requested: %.0f ms", seconds * 1000)
    }

    fun stop() {
        if (!running.getAndSet(false)) {
            release()
            return
        }
        thread?.join(400)
        release()
    }

    private fun release() {
        paused.set(false)
        runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null
        thread = null
        Timber.tag(TAG).i("MixerOutput released")
    }

    companion object {
        private const val TAG = "AutoMix"
    }
}
