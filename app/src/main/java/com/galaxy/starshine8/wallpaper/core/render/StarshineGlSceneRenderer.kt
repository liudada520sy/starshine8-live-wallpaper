package com.galaxy.starshine8.wallpaper.core.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.SystemClock
import com.galaxy.starshine8.wallpaper.core.contract.BaseVisualMode
import com.galaxy.starshine8.wallpaper.core.contract.CockpitEnvironmentState
import com.galaxy.starshine8.wallpaper.core.contract.DynamicSceneRenderer
import com.galaxy.starshine8.wallpaper.core.contract.LoadedWallpaperBundle
import com.galaxy.starshine8.wallpaper.core.contract.WallpaperStyleConfig
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * 2025款吉利银河星耀8 统一单 Pass OpenGL ES 3.0 渲染引擎 + Phase B VPU 硬解视频协同实现（车规级加固版）。
 * P0/P1 加固特性：
 * 1. OES 零拷贝视频流绑定 + SurfaceTexture.getTransformMatrix() 矩阵同步传入 uVideoTexMatrix，防止硬件帧倒置或绿边。
 * 2. STR (Suspend-to-RAM) 深度休眠唤醒自愈：保留 lastBoundBundleRef 强引用快照自动重传纹理。
 * 3. 三级温控动态限帧：支持 60fps（正常）/ 30fps（中温节流）/ 静态暂停（高温保护）动态切换。
 */
class StarshineGlSceneRenderer(
    private val appContext: Context
) : DynamicSceneRenderer, GLSurfaceView.Renderer {

    private var surfaceWidth: Int = 2560
    private var surfaceHeight: Int = 1600
    private var shaderProgramId: Int = 0
    private var colorTextureId: Int = 0
    private var depthTextureId: Int = 0
    private var videoOesTextureId: Int = 0

    // Uniform 寄存器位置缓存
    private var uTexColorLoc: Int = -1
    private var uTexDepthLoc: Int = -1
    private var uTexVideoOesLoc: Int = -1
    private var uVideoTexMatrixLoc: Int = -1
    private var uTimeLoc: Int = -1
    private var uResolutionLoc: Int = -1
    private var uBaseModeLoc: Int = -1
    private var uThermalTierLoc: Int = -1
    private var uParallaxMaxOffsetLoc: Int = -1
    private var uRippleHorizonYLoc: Int = -1
    private var uTyndallRayIntensityLoc: Int = -1
    private var uTouchRippleCenterLoc: Int = -1
    private var uTouchRippleAgeLoc: Int = -1
    private var uColorDominantLoc: Int = -1
    private var uColorVibrantLoc: Int = -1
    private var uColorLightVibrantLoc: Int = -1
    private var uEnableAudioOverlayLoc: Int = -1
    private var uAudioBinsLoc: Int = -1
    private var uParticlePresetLoc: Int = -1
    private var uNightDimmingFactorLoc: Int = -1

    private val videoTexMatrix = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }

    // 预分配顶点与纹理坐标直接缓冲区（Zero-Allocation in renderFrame）
    private val quadVertices: FloatBuffer = ByteBuffer
        .allocateDirect(4 * 4 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(
                floatArrayOf(
                    // X, Y, U, V
                    -1.0f, -1.0f, 0.0f, 0.0f,
                     1.0f, -1.0f, 1.0f, 0.0f,
                    -1.0f,  1.0f, 0.0f, 1.0f,
                     1.0f,  1.0f, 1.0f, 1.0f
                )
            )
            position(0)
        }

    // 线程安全的状态交换槽与 STR 唤醒恢复快照
    private val pendingBundleRef = AtomicReference<LoadedWallpaperBundle?>(null)
    private val lastBoundBundleRef = AtomicReference<LoadedWallpaperBundle?>(null)
    private val activeConfigRef = AtomicReference(WallpaperStyleConfig())
    private val isPausedFlag = AtomicBoolean(false)
    private val thermalTierAtomic = AtomicInteger(0) // 0=60fps, 1=30fps, 2=static pause

    private val audioBinsWorkingBuffer = FloatArray(32)
    private val dominantRgb = floatArrayOf(0.106f, 0.165f, 0.220f)
    private val vibrantRgb = floatArrayOf(0.831f, 0.608f, 0.329f)
    private val lightVibrantRgb = floatArrayOf(0.949f, 0.875f, 0.769f)

    @Volatile
    private var currentNightDimming: Float = 1.0f

    @Volatile
    private var touchCenterX: Float = 0.5f

    @Volatile
    private var touchCenterY: Float = 0.5f

    @Volatile
    private var touchTimestampSec: Float = -10.0f

    private var startRealtimeMs: Long = SystemClock.elapsedRealtime()
    private var lastFrameRenderedMs: Long = 0L

    private var hardwareVideoDecoder: PhaseBHardwareVideoDecoder? = null

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glClearColor(0.043f, 0.055f, 0.078f, 1.0f)

        val vertSource = readShaderAsset("shaders/starshine_master_scene.vert")
        val fragSource = readShaderAsset("shaders/starshine_master_scene.frag")
        shaderProgramId = buildProgram(vertSource, fragSource)
        GLES30.glUseProgram(shaderProgramId)

        cacheUniformLocations()
        initTexturesWithFallback()

        val cachedBundle = lastBoundBundleRef.get()
        if (cachedBundle != null && pendingBundleRef.get() == null) {
            hardwareVideoDecoder?.stopAndRelease()
            hardwareVideoDecoder = null
            pendingBundleRef.set(cachedBundle)
        }
        startRealtimeMs = SystemClock.elapsedRealtime()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        onSurfaceReady(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val nowMs = SystemClock.elapsedRealtime()
        val tier = thermalTierAtomic.get()
        if (tier == 1 && (nowMs - lastFrameRenderedMs) < 32L) {
            return
        }
        lastFrameRenderedMs = nowMs
        val elapsedSec = (nowMs - startRealtimeMs) / 1000.0f
        renderFrame(elapsedSec)
    }

    override fun onSurfaceReady(width: Int, height: Int) {
        surfaceWidth = width.coerceAtLeast(1)
        surfaceHeight = height.coerceAtLeast(1)
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
    }

    override fun bindWallpaperBundle(bundle: LoadedWallpaperBundle) {
        lastBoundBundleRef.set(bundle)
        pendingBundleRef.set(bundle)
        activeConfigRef.set(bundle.manifest.styleConfig)
        parseHexToFloatRgb(bundle.manifest.palette.dominantHex, dominantRgb)
        parseHexToFloatRgb(bundle.manifest.palette.vibrantHex, vibrantRgb)
        parseHexToFloatRgb(bundle.manifest.palette.lightVibrantHex, lightVibrantRgb)
    }

    override fun updateStyleConfig(config: WallpaperStyleConfig) {
        activeConfigRef.set(config)
        val isPhaseBActive = (config.baseMode == BaseVisualMode.PHASE_B_HARDWARE_VIDEO)
        hardwareVideoDecoder?.setPaused(isPausedFlag.get() || !isPhaseBActive)
    }

    override fun updateCockpitState(state: CockpitEnvironmentState) {
        currentNightDimming = state.nightDimmingFactor.coerceIn(0.50f, 1.0f)
        synchronized(audioBinsWorkingBuffer) {
            val copyLen = minOf(state.audioSpectrumBins.size, audioBinsWorkingBuffer.size)
            System.arraycopy(state.audioSpectrumBins, 0, audioBinsWorkingBuffer, 0, copyLen)
        }
    }

    override fun setRenderPaused(paused: Boolean) {
        isPausedFlag.set(paused)
        val shouldPauseVideo = paused || (activeConfigRef.get().baseMode != BaseVisualMode.PHASE_B_HARDWARE_VIDEO)
        hardwareVideoDecoder?.setPaused(shouldPauseVideo)
    }

    fun setThermalStatusTier(tier: Int) {
        thermalTierAtomic.set(tier.coerceIn(0, 2))
    }

    fun notifyStrWakeUpSelfCheck() {
        val cached = lastBoundBundleRef.get() ?: return
        pendingBundleRef.compareAndSet(null, cached)
    }

    fun notifyTouchRipple(normalizedX: Float, normalizedY: Float) {
        touchCenterX = normalizedX.coerceIn(0.0f, 1.0f)
        touchCenterY = normalizedY.coerceIn(0.0f, 1.0f)
        touchTimestampSec = (SystemClock.elapsedRealtime() - startRealtimeMs) / 1000.0f
    }

    override fun renderFrame(elapsedTimeSec: Float) {
        if (isPausedFlag.get() || shaderProgramId == 0) {
            return
        }

        val newBundle = pendingBundleRef.getAndSet(null)
        if (newBundle != null) {
            uploadBundleTexturesToGpu(newBundle)
            configurePhaseBHardwareDecoderIfNeeded(newBundle.phaseBVideoFile)
        }

        val style = activeConfigRef.get()
        if (style.baseMode == BaseVisualMode.PHASE_B_HARDWARE_VIDEO) {
            hardwareVideoDecoder?.updateTexImageIfAvailable(videoTexMatrix)
        }

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(shaderProgramId)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, colorTextureId)
        GLES30.glUniform1i(uTexColorLoc, 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, depthTextureId)
        GLES30.glUniform1i(uTexDepthLoc, 1)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoOesTextureId)
        GLES30.glUniform1i(uTexVideoOesLoc, 2)
        GLES30.glUniformMatrix4fv(uVideoTexMatrixLoc, 1, false, videoTexMatrix, 0)

        GLES30.glUniform1f(uTimeLoc, elapsedTimeSec)
        GLES30.glUniform2f(uResolutionLoc, surfaceWidth.toFloat(), surfaceHeight.toFloat())
        GLES30.glUniform1i(uBaseModeLoc, style.baseMode.shaderCode)
        GLES30.glUniform1i(uThermalTierLoc, thermalTierAtomic.get())
        GLES30.glUniform1f(uParallaxMaxOffsetLoc, style.parallaxMaxUvOffset.coerceIn(0.018f, 0.028f))
        GLES30.glUniform1f(uRippleHorizonYLoc, style.rippleHorizonThreshold.coerceIn(0.50f, 0.90f))
        GLES30.glUniform1f(uTyndallRayIntensityLoc, style.tyndallRayIntensity.coerceIn(0.12f, 0.18f))

        val rippleAge = (elapsedTimeSec - touchTimestampSec).coerceAtLeast(-1.0f)
        GLES30.glUniform2f(uTouchRippleCenterLoc, touchCenterX, touchCenterY)
        GLES30.glUniform1f(uTouchRippleAgeLoc, rippleAge)

        GLES30.glUniform3f(uColorDominantLoc, dominantRgb[0], dominantRgb[1], dominantRgb[2])
        GLES30.glUniform3f(uColorVibrantLoc, vibrantRgb[0], vibrantRgb[1], vibrantRgb[2])
        GLES30.glUniform3f(uColorLightVibrantLoc, lightVibrantRgb[0], lightVibrantRgb[1], lightVibrantRgb[2])

        GLES30.glUniform1i(uEnableAudioOverlayLoc, if (style.enableAudioVisualizerOverlay) 1 else 0)
        synchronized(audioBinsWorkingBuffer) {
            GLES30.glUniform1fv(uAudioBinsLoc, 32, audioBinsWorkingBuffer, 0)
        }
        GLES30.glUniform1i(uParticlePresetLoc, style.particleOverlayPreset.shaderCode)
        GLES30.glUniform1f(uNightDimmingFactorLoc, currentNightDimming)

        quadVertices.position(0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, quadVertices)

        quadVertices.position(2)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, quadVertices)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
    }

    override fun release() {
        hardwareVideoDecoder?.stopAndRelease()
        hardwareVideoDecoder = null
        val texArray = intArrayOf(colorTextureId, depthTextureId, videoOesTextureId)
        GLES30.glDeleteTextures(3, texArray, 0)
        colorTextureId = 0
        depthTextureId = 0
        videoOesTextureId = 0
        if (shaderProgramId != 0) {
            GLES30.glDeleteProgram(shaderProgramId)
            shaderProgramId = 0
        }
    }

    private fun cacheUniformLocations() {
        uTexColorLoc = GLES30.glGetUniformLocation(shaderProgramId, "uTextureColor")
        uTexDepthLoc = GLES30.glGetUniformLocation(shaderProgramId, "uTextureDepth")
        uTexVideoOesLoc = GLES30.glGetUniformLocation(shaderProgramId, "uTextureVideoOes")
        uVideoTexMatrixLoc = GLES30.glGetUniformLocation(shaderProgramId, "uVideoTexMatrix")
        uTimeLoc = GLES30.glGetUniformLocation(shaderProgramId, "uTime")
        uResolutionLoc = GLES30.glGetUniformLocation(shaderProgramId, "uResolution")
        uBaseModeLoc = GLES30.glGetUniformLocation(shaderProgramId, "uBaseMode")
        uThermalTierLoc = GLES30.glGetUniformLocation(shaderProgramId, "uThermalTier")
        uParallaxMaxOffsetLoc = GLES30.glGetUniformLocation(shaderProgramId, "uParallaxMaxOffset")
        uRippleHorizonYLoc = GLES30.glGetUniformLocation(shaderProgramId, "uRippleHorizonY")
        uTyndallRayIntensityLoc = GLES30.glGetUniformLocation(shaderProgramId, "uTyndallRayIntensity")
        uTouchRippleCenterLoc = GLES30.glGetUniformLocation(shaderProgramId, "uTouchRippleCenter")
        uTouchRippleAgeLoc = GLES30.glGetUniformLocation(shaderProgramId, "uTouchRippleAge")
        uColorDominantLoc = GLES30.glGetUniformLocation(shaderProgramId, "uColorDominant")
        uColorVibrantLoc = GLES30.glGetUniformLocation(shaderProgramId, "uColorVibrant")
        uColorLightVibrantLoc = GLES30.glGetUniformLocation(shaderProgramId, "uColorLightVibrant")
        uEnableAudioOverlayLoc = GLES30.glGetUniformLocation(shaderProgramId, "uEnableAudioOverlay")
        uAudioBinsLoc = GLES30.glGetUniformLocation(shaderProgramId, "uAudioBins")
        uParticlePresetLoc = GLES30.glGetUniformLocation(shaderProgramId, "uParticlePreset")
        uNightDimmingFactorLoc = GLES30.glGetUniformLocation(shaderProgramId, "uNightDimmingFactor")
    }

    private fun initTexturesWithFallback() {
        val textures = IntArray(3)
        GLES30.glGenTextures(3, textures, 0)
        colorTextureId = textures[0]
        depthTextureId = textures[1]
        videoOesTextureId = textures[2]

        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoOesTextureId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        val defaultColorBmp = Bitmap.createBitmap(64, 40, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until 40) {
                for (x in 0 until 64) {
                    val ratioY = y / 39.0f
                    val r = (18 + ratioY * 45).toInt()
                    val g = (32 + ratioY * 58).toInt()
                    val b = (48 + ratioY * 68).toInt()
                    setPixel(x, y, Color.rgb(r, g, b))
                }
            }
        }
        val defaultDepthBmp = Bitmap.createBitmap(64, 40, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until 40) {
                for (x in 0 until 64) {
                    val v = (y * 255) / 39
                    setPixel(x, y, Color.rgb(v, v, v))
                }
            }
        }
        uploadBitmapToTexture(colorTextureId, defaultColorBmp)
        uploadBitmapToTexture(depthTextureId, defaultDepthBmp)
        defaultColorBmp.recycle()
        defaultDepthBmp.recycle()
    }

    private fun uploadBundleTexturesToGpu(bundle: LoadedWallpaperBundle) {
        if (!bundle.colorBitmap.isRecycled) {
            uploadBitmapToTexture(colorTextureId, bundle.colorBitmap)
        }
        if (!bundle.depthBitmap.isRecycled) {
            uploadBitmapToTexture(depthTextureId, bundle.depthBitmap)
        }
    }

    private fun uploadBitmapToTexture(texId: Int, bitmap: Bitmap) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
    }

    private fun configurePhaseBHardwareDecoderIfNeeded(videoFile: File?) {
        if (videoFile == null || !videoFile.exists()) {
            hardwareVideoDecoder?.stopAndRelease()
            hardwareVideoDecoder = null
            return
        }
        val decoder = hardwareVideoDecoder ?: PhaseBHardwareVideoDecoder(videoOesTextureId).also {
            hardwareVideoDecoder = it
        }
        val shouldStartPaused = isPausedFlag.get() ||
            (activeConfigRef.get().baseMode != BaseVisualMode.PHASE_B_HARDWARE_VIDEO)
        decoder.startPlayback(videoFile, startPaused = shouldStartPaused)
    }

    private fun readShaderAsset(assetPath: String): String {
        return appContext.assets.open(assetPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vertShader = compileShader(GLES30.GL_VERTEX_SHADER, vertexSrc)
        val fragShader = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSrc)
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vertShader)
        GLES30.glAttachShader(program, fragShader)
        GLES30.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linkStatus, 0)
        GLES30.glDeleteShader(vertShader)
        GLES30.glDeleteShader(fragShader)
        if (linkStatus[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(program)
            GLES30.glDeleteProgram(program)
            throw IllegalStateException("OpenGL ES 3.0 Program Link Error: $log")
        }
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            throw IllegalStateException("OpenGL ES 3.0 Shader Compile Error: $log")
        }
        return shader
    }

    private fun parseHexToFloatRgb(hexString: String, targetRgb: FloatArray) {
        runCatching {
            val colorInt = Color.parseColor(hexString)
            targetRgb[0] = Color.red(colorInt) / 255.0f
            targetRgb[1] = Color.green(colorInt) / 255.0f
            targetRgb[2] = Color.blue(colorInt) / 255.0f
        }
    }
}
