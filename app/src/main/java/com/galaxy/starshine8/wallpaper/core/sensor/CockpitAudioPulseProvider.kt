package com.galaxy.starshine8.wallpaper.core.sensor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.Visualizer
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 兼容架构规划文档中的 AudioSpectrumTracker 命名别名
 */
typealias AudioSpectrumTracker = CockpitAudioPulseProvider

/**
 * 三层渐进式 Flyme Sound 音乐频谱律动采集器（CockpitAudioPulseProvider / AudioSpectrumTracker）。
 * Tier 1: Visualizer(0) 全局混音 FFT 采集（Bark 对数频段映射 + AGC 自适应峰值增益）
 * Tier 2: AudioRecord 麦克风环境拾音离散傅里叶能量分析
 * Tier 3: 生成式东方呼吸节拍发生器（100% 免权限/静音兜底）
 */
class CockpitAudioPulseProvider(
    private val context: Context,
    private val externalScope: CoroutineScope,
    private val onSpectrumUpdated: (bins32: FloatArray, isProceduralFallback: Boolean) -> Unit
) {

    enum class ActiveTier {
        TIER_1_SYSTEM_VISUALIZER,
        TIER_2_MIC_AUDIO_RECORD,
        TIER_3_PROCEDURAL_HARMONIC
    }

    @Volatile
    var currentTier: ActiveTier = ActiveTier.TIER_3_PROCEDURAL_HARMONIC
        private set

    private var systemVisualizer: Visualizer? = null
    private var audioRecord: AudioRecord? = null
    private var pulseLoopJob: Job? = null

    private val rawBins = FloatArray(32)
    private val smoothedBins = FloatArray(32)
    private var rollingAgcPeak: Float = 0.35f
    private var consecutiveZeroFrames: Int = 0
    private val maxZeroFramesBeforeDowngrade = 120

    fun start() {
        stop()
        consecutiveZeroFrames = 0
        val tier1Started = tryStartTier1SystemVisualizer()
        if (!tier1Started) {
            val tier2Started = tryStartTier2MicAudioRecord()
            if (!tier2Started) {
                currentTier = ActiveTier.TIER_3_PROCEDURAL_HARMONIC
            }
        }

        pulseLoopJob = externalScope.launch(Dispatchers.Default) {
            val startMs = SystemClock.elapsedRealtime()
            val pcmBuffer = ShortArray(256)
            while (isActive) {
                val elapsedSec = (SystemClock.elapsedRealtime() - startMs) / 1000.0f
                when (currentTier) {
                    ActiveTier.TIER_1_SYSTEM_VISUALIZER -> {
                        if (consecutiveZeroFrames >= maxZeroFramesBeforeDowngrade) {
                            releaseVisualizerSafely()
                            if (!tryStartTier2MicAudioRecord()) {
                                currentTier = ActiveTier.TIER_3_PROCEDURAL_HARMONIC
                            }
                        }
                    }
                    ActiveTier.TIER_2_MIC_AUDIO_RECORD -> {
                        val readCount = audioRecord?.read(pcmBuffer, 0, pcmBuffer.size) ?: -1
                        if (readCount > 0) {
                            val hasSignal = compute32BandsFromPcm(pcmBuffer, readCount, rawBins)
                            if (!hasSignal) {
                                consecutiveZeroFrames++
                                if (consecutiveZeroFrames >= maxZeroFramesBeforeDowngrade) {
                                    computeProceduralHarmonicEnvelope(elapsedSec, rawBins)
                                }
                            } else {
                                consecutiveZeroFrames = 0
                            }
                        } else {
                            releaseAudioRecordSafely()
                            currentTier = ActiveTier.TIER_3_PROCEDURAL_HARMONIC
                        }
                    }
                    ActiveTier.TIER_3_PROCEDURAL_HARMONIC -> {
                        computeProceduralHarmonicEnvelope(elapsedSec, rawBins)
                    }
                }

                for (i in 0 until 32) {
                    val target = rawBins[i].coerceIn(0.02f, 1.0f)
                    val alpha = if (target > smoothedBins[i]) 0.42f else 0.15f
                    smoothedBins[i] = smoothedBins[i] + (target - smoothedBins[i]) * alpha
                }

                val isFallback = (currentTier == ActiveTier.TIER_3_PROCEDURAL_HARMONIC) ||
                    (consecutiveZeroFrames >= maxZeroFramesBeforeDowngrade)
                onSpectrumUpdated(smoothedBins.copyOf(), isFallback)
                delay(16L)
            }
        }
    }

    fun stop() {
        pulseLoopJob?.cancel()
        pulseLoopJob = null
        releaseVisualizerSafely()
        releaseAudioRecordSafely()
    }

    private fun tryStartTier1SystemVisualizer(): Boolean {
        val hasRecordPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasRecordPerm) {
            return false
        }
        return runCatching {
            val captureSizeRange = Visualizer.getCaptureSizeRange()
            val captureSize = 256.coerceIn(captureSizeRange[0], captureSizeRange[1])
            val vis = Visualizer(0).apply {
                enabled = false
                setCaptureSize(captureSize)
                setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(
                            visualizer: Visualizer?,
                            waveform: ByteArray?,
                            samplingRate: Int
                        ) = Unit

                        override fun onFftDataCapture(
                            visualizer: Visualizer?,
                            fft: ByteArray?,
                            samplingRate: Int
                        ) {
                            if (fft == null || fft.size < 64) {
                                consecutiveZeroFrames++
                                return
                            }
                            val maxComplexBins = (fft.size / 2).coerceAtLeast(32)
                            var framePeak = 0.0f

                            for (band in 0 until 32) {
                                val t0 = (band / 32.0).pow(1.45)
                                val t1 = ((band + 1) / 32.0).pow(1.45)
                                val startBin = (1 + (t0 * (maxComplexBins - 2)).toInt()).coerceIn(1, maxComplexBins - 1)
                                val endBin = (startBin + 1).coerceAtLeast((1 + (t1 * (maxComplexBins - 2)).toInt()).coerceAtMost(maxComplexBins - 1))

                                var sumMag = 0.0f
                                var count = 0
                                for (k in startBin..endBin) {
                                    val re = fft[k * 2].toFloat()
                                    val im = fft[k * 2 + 1].toFloat()
                                    sumMag += hypot(re, im) / 128.0f
                                    count++
                                }
                                val trebleBoost = 1.0f + (band / 31.0f) * 0.65f
                                val mag = ((sumMag / count.coerceAtLeast(1)) * trebleBoost)
                                if (mag > framePeak) framePeak = mag
                                rawBins[band] = mag
                            }

                            rollingAgcPeak = (rollingAgcPeak * 0.96f + framePeak * 0.04f).coerceIn(0.18f, 1.0f)
                            for (band in 0 until 32) {
                                rawBins[band] = (rawBins[band] / rollingAgcPeak).coerceIn(0.0f, 1.0f)
                            }

                            if (framePeak < 0.008f) {
                                consecutiveZeroFrames++
                            } else {
                                consecutiveZeroFrames = 0
                            }
                        }
                    },
                    Visualizer.getMaxCaptureRate() / 2,
                    false,
                    true
                )
                enabled = true
            }
            systemVisualizer = vis
            currentTier = ActiveTier.TIER_1_SYSTEM_VISUALIZER
            true
        }.getOrDefault(false)
    }

    private fun tryStartTier2MicAudioRecord(): Boolean {
        val hasRecordPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasRecordPerm) {
            return false
        }
        return runCatching {
            val sampleRate = 16000
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) return false
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                return false
            }
            recorder.startRecording()
            audioRecord = recorder
            consecutiveZeroFrames = 0
            currentTier = ActiveTier.TIER_2_MIC_AUDIO_RECORD
            true
        }.getOrDefault(false)
    }

    private fun compute32BandsFromPcm(pcm: ShortArray, length: Int, outBins: FloatArray): Boolean {
        var maxEnergy = 0.0f
        val chunkSize = (length / 32).coerceAtLeast(1)
        for (band in 0 until 32) {
            var sumSq = 0.0
            val startIdx = band * chunkSize
            val endIdx = minOf(startIdx + chunkSize, length)
            for (i in startIdx until endIdx) {
                val normalized = pcm[i] / 32768.0
                sumSq += normalized * normalized
            }
            val rms = sqrt(sumSq / (endIdx - startIdx).coerceAtLeast(1)).toFloat()
            val scaled = (rms * 4.2f).coerceIn(0.0f, 1.0f)
            outBins[band] = scaled
            if (scaled > maxEnergy) maxEnergy = scaled
        }
        return maxEnergy > 0.015f
    }

    private fun computeProceduralHarmonicEnvelope(timeSec: Float, outBins: FloatArray) {
        val slowBreath = 0.55f + 0.45f * sin(timeSec * 0.78f)
        for (i in 0 until 32) {
            val normBand = i / 31.0f
            val centerWeight = 1.0f - kotlin.math.abs(normBand - 0.38f) * 1.15f
            val wave1 = sin(timeSec * 1.45f + i * 0.28f) * 0.5f + 0.5f
            val wave2 = cos(timeSec * 0.92f - i * 0.41f) * 0.5f + 0.5f
            val harmonic = (wave1 * 0.62f + wave2 * 0.38f) * slowBreath * centerWeight.coerceAtLeast(0.25f)
            outBins[i] = harmonic.coerceIn(0.06f, 0.82f)
        }
    }

    private fun releaseVisualizerSafely() {
        runCatching {
            systemVisualizer?.enabled = false
            systemVisualizer?.release()
        }
        systemVisualizer = null
    }

    private fun releaseAudioRecordSafely() {
        runCatching {
            audioRecord?.stop()
            audioRecord?.release()
        }
        audioRecord = null
    }
}
