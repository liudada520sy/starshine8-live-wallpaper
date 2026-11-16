package com.galaxy.starshine8.wallpaper.core.render

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.opengl.Matrix
import android.os.SystemClock
import android.view.Surface
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Phase B 专用车规级 H.265 (HEVC) / H.264 零拷贝硬件视频循环解码管线（PhaseBHardwareVideoDecoder）。
 *
 * P1 加固特性：
 * 1. 基于 MediaExtractor + MediaCodec 零拷贝渲染至 OES SurfaceTexture，EOS 处通过 SEEK_TO_PREVIOUS_SYNC + flush() 0ms 无缝循环。
 * 2. 使用 ReentrantLock + Condition 替代 Thread.sleep(32L) 轮询，实现后台挂起 0% CPU 唤醒。
 * 3. 每次 updateTexImageIfAvailable(outTransformMatrix16) 同步输出 SurfaceTexture.getTransformMatrix()，
 *    消除部分车机 VPU 解码帧 Y 轴翻转或内存对齐绿边问题。
 */
class PhaseBHardwareVideoDecoder(
    private val oesTextureId: Int
) {

    private var surfaceTexture: SurfaceTexture? = null
    private var outputSurface: Surface? = null
    private var decoderThread: Thread? = null
    private var fallbackMediaPlayer: MediaPlayer? = null

    private val isRunning = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    private val hasNewFrameAvailable = AtomicBoolean(false)

    private val pauseLock = ReentrantLock()
    private val pauseCondition = pauseLock.newCondition()

    @Volatile
    var activeVideoPath: String? = null
        private set

    fun startPlayback(videoFile: File, startPaused: Boolean) {
        if (!videoFile.exists() || videoFile.length() <= 0L) {
            stopAndRelease()
            return
        }
        if (activeVideoPath == videoFile.absolutePath && (isRunning.get() || fallbackMediaPlayer != null)) {
            setPaused(startPaused)
            return
        }

        stopAndRelease()
        isPaused.set(startPaused)
        activeVideoPath = videoFile.absolutePath

        val tex = SurfaceTexture(oesTextureId).apply {
            setOnFrameAvailableListener {
                hasNewFrameAvailable.set(true)
            }
        }
        val surf = Surface(tex)
        surfaceTexture = tex
        outputSurface = surf

        isRunning.set(true)
        val worker = Thread(
            {
                val mediaCodecSucceeded = runLowLevelMediaCodecLoop(videoFile, surf)
                if (!mediaCodecSucceeded && isRunning.get()) {
                    startFallbackMediaPlayer(videoFile, surf)
                }
            },
            "Starshine-H265-VPU-Decoder"
        ).apply {
            isDaemon = true
            start()
        }
        decoderThread = worker
    }

    /**
     * 在 OpenGL ES 3.0 渲染线程的 renderFrame() 中调用，更新 OES 帧并输出 4x4 SurfaceTexture 变换矩阵
     */
    fun updateTexImageIfAvailable(outTransformMatrix16: FloatArray) {
        val tex = surfaceTexture
        if (tex == null) {
            Matrix.setIdentityM(outTransformMatrix16, 0)
            return
        }
        if (hasNewFrameAvailable.compareAndSet(true, false) || fallbackMediaPlayer != null) {
            runCatching {
                tex.updateTexImage()
                tex.getTransformMatrix(outTransformMatrix16)
            }.onFailure {
                Matrix.setIdentityM(outTransformMatrix16, 0)
            }
        }
    }

    fun setPaused(paused: Boolean) {
        pauseLock.withLock {
            isPaused.set(paused)
            if (!paused) {
                pauseCondition.signalAll()
            }
        }
        runCatching {
            val player = fallbackMediaPlayer ?: return@runCatching
            if (paused && player.isPlaying) {
                player.pause()
            } else if (!paused && !player.isPlaying) {
                player.start()
            }
        }
    }

    fun stopAndRelease() {
        pauseLock.withLock {
            isRunning.set(false)
            isPaused.set(false)
            pauseCondition.signalAll()
        }
        hasNewFrameAvailable.set(false)

        runCatching {
            decoderThread?.interrupt()
            decoderThread?.join(350L)
        }
        decoderThread = null

        runCatching {
            fallbackMediaPlayer?.stop()
            fallbackMediaPlayer?.release()
        }
        fallbackMediaPlayer = null

        runCatching { outputSurface?.release() }
        outputSurface = null

        runCatching {
            surfaceTexture?.setOnFrameAvailableListener(null)
            surfaceTexture?.release()
        }
        surfaceTexture = null
        activeVideoPath = null
    }

    private fun runLowLevelMediaCodecLoop(videoFile: File, targetSurface: Surface): Boolean {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(videoFile.absolutePath)
            val trackIndex = selectVideoTrack(extractor)
            if (trackIndex < 0) {
                return false
            }
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return false

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, targetSurface, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var isInputEos = false
            var loopStartRealtimeUs = SystemClock.elapsedRealtimeNanos() / 1000L

            while (isRunning.get() && !Thread.currentThread().isInterrupted) {
                if (isPaused.get()) {
                    pauseLock.withLock {
                        while (isPaused.get() && isRunning.get()) {
                            pauseCondition.await()
                        }
                    }
                    loopStartRealtimeUs = (SystemClock.elapsedRealtimeNanos() / 1000L) - bufferInfo.presentationTimeUs
                    continue
                }

                if (!isInputEos) {
                    val inIndex = codec.dequeueInputBuffer(10_000L)
                    if (inIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inIndex)
                        if (inputBuffer != null) {
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                extractor.seekTo(0L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                                isInputEos = true
                                codec.queueInputBuffer(
                                    inIndex,
                                    0,
                                    0,
                                    0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                            } else {
                                val ptsUs = extractor.sampleTime.coerceAtLeast(0L)
                                codec.queueInputBuffer(inIndex, 0, sampleSize, ptsUs, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                when {
                    outIndex >= 0 -> {
                        val doRender = (bufferInfo.size > 0)
                        if (doRender) {
                            val nowUs = SystemClock.elapsedRealtimeNanos() / 1000L
                            val expectedUs = loopStartRealtimeUs + bufferInfo.presentationTimeUs
                            val sleepUs = expectedUs - nowUs
                            if (sleepUs in 1_000L..100_000L) {
                                Thread.sleep(sleepUs / 1000L, ((sleepUs % 1000L) * 1000L).toInt())
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, doRender)

                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            codec.flush()
                            isInputEos = false
                            loopStartRealtimeUs = SystemClock.elapsedRealtimeNanos() / 1000L
                        }
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                }
            }
            return true
        } catch (_: InterruptedException) {
            return true
        } catch (_: Throwable) {
            return false
        } finally {
            runCatching {
                codec?.stop()
                codec?.release()
            }
            runCatching { extractor.release() }
        }
    }

    private fun selectVideoTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) {
                return i
            }
        }
        return -1
    }

    private fun startFallbackMediaPlayer(videoFile: File, targetSurface: Surface) {
        runCatching {
            val player = MediaPlayer().apply {
                setDataSource(videoFile.absolutePath)
                setSurface(targetSurface)
                isLooping = true
                setVolume(0f, 0f)
                prepare()
                if (!isPaused.get()) {
                    start()
                }
            }
            fallbackMediaPlayer = player
        }
    }
}
