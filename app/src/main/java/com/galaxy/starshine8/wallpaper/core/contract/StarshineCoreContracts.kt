package com.galaxy.starshine8.wallpaper.core.contract

import android.graphics.Bitmap
import kotlinx.serialization.Serializable
import java.io.File

/**
 * 强类型领域结果密封接口（遵循 mattpocock-skills Gate 2 "Errors as Values" 规范）
 */
sealed interface DomainResult<out T, out E> {
    data class Success<out T>(val value: T) : DomainResult<T, Nothing>
    data class Failure<out E>(val error: E) : DomainResult<Nothing, E>

    fun <R> fold(onSuccess: (T) -> R, onFailure: (E) -> R): R = when (this) {
        is Success -> onSuccess(value)
        is Failure -> onFailure(error)
    }
}

sealed interface WallpaperDomainError {
    data class DecodeOutOfMemory(val maxAllowedBytes: Long, val detail: String) : WallpaperDomainError
    data class InvalidImagePayload(val reason: String) : WallpaperDomainError
    data class DepthInferenceTimeout(val timeoutMs: Long) : WallpaperDomainError
    data class StorageQuotaError(val currentBytes: Long, val maxBytes: Long) : WallpaperDomainError
    data class ExternalComputeUnreachable(val endpoint: String, val cause: String) : WallpaperDomainError
    data class VideoIntegrityMismatch(val expectedSha256: String, val actualSha256: String) : WallpaperDomainError
}

/**
 * 遵循 garrytan-gstack Phase 2 的 5-State UI 状态矩阵可辨识联合体
 */
sealed interface CockpitWorkbenchUiState {
    data class IdealReady(val activeAssetId: String, val totalSavedCount: Int) : CockpitWorkbenchUiState
    data class LoadingPipeline(val stageLabel: String, val progressPercent: Int) : CockpitWorkbenchUiState
    data class EmptyCustomGallery(val defaultAssetTitle: String) : CockpitWorkbenchUiState
    data class RecoverableError(val userFriendlyMessage: String, val canRetry: Boolean = true) : CockpitWorkbenchUiState
    data class ThermalProtectedMode(val thermalTier: Int, val statusDescription: String) : CockpitWorkbenchUiState
}

interface DynamicSceneRenderer {
    fun onSurfaceReady(width: Int, height: Int)
    fun bindWallpaperBundle(bundle: LoadedWallpaperBundle)
    fun updateStyleConfig(config: WallpaperStyleConfig)
    fun updateCockpitState(state: CockpitEnvironmentState)
    fun setRenderPaused(paused: Boolean)
    fun renderFrame(elapsedTimeSec: Float)
    fun release()
}

data class CockpitEnvironmentState(
    val isNightMode: Boolean,
    val nightDimmingFactor: Float,
    val audioSpectrumBins: FloatArray,
    val isAudioProceduralFallback: Boolean,
    val isParked: Boolean
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CockpitEnvironmentState) return false
        return isNightMode == other.isNightMode &&
            nightDimmingFactor == other.nightDimmingFactor &&
            audioSpectrumBins.contentEquals(other.audioSpectrumBins) &&
            isAudioProceduralFallback == other.isAudioProceduralFallback &&
            isParked == other.isParked
    }

    override fun hashCode(): Int {
        var result = isNightMode.hashCode()
        result = 31 * result + nightDimmingFactor.hashCode()
        result = 31 * result + audioSpectrumBins.contentHashCode()
        result = 31 * result + isAudioProceduralFallback.hashCode()
        result = 31 * result + isParked.hashCode()
        return result
    }
}

@Serializable
data class WallpaperStyleConfig(
    val baseMode: BaseVisualMode = BaseVisualMode.HYBRID_RIPPLE_PARALLAX,
    val parallaxMaxUvOffset: Float = 0.022f,
    val rippleHorizonThreshold: Float = 0.72f,
    val tyndallRayIntensity: Float = 0.15f,
    val enableAudioVisualizerOverlay: Boolean = true,
    val particleOverlayPreset: ParticlePreset = ParticlePreset.SOLAR_TERM_GOLD_DUST
)

@Serializable
enum class BaseVisualMode(val shaderCode: Int) {
    EASTERN_RIPPLE(0),
    PARALLAX_3D(1),
    HYBRID_RIPPLE_PARALLAX(2),
    PHASE_B_HARDWARE_VIDEO(3)
}

@Serializable
enum class ParticlePreset(val shaderCode: Int) {
    NONE(0),
    SOLAR_TERM_GOLD_DUST(1),
    SPRING_CHERRY_BLOSSOM(2),
    MISTY_RAIN_DRIZZLE(3),
    WINTER_SNOWFLAKE(4)
}

interface ExternalComputeAdapter {
    suspend fun discoverNodes(timeoutMs: Long = 2500L): List<ComputeNodeEndpoint>
    suspend fun submitVideoRedrawJob(
        assetId: String,
        normalizedWebpFile: File,
        promptPreset: String
    ): RemoteJobHandle
    suspend fun pollAndDownloadMp4(
        handle: RemoteJobHandle,
        destinationMp4File: File
    ): Result<Unit>
}

@Serializable
data class ComputeNodeEndpoint(
    val nodeName: String,
    val baseUrl: String,
    val gpuModel: String = "NVIDIA GeForce RTX 4060 Ti",
    val isMdnsDiscovered: Boolean = true
)

@Serializable
data class RemoteJobHandle(
    val jobId: String,
    val assetId: String,
    val endpointUrl: String,
    val submittedAtEpochMs: Long
)

@Serializable
data class ResolutionSpec(
    val width: Int = 2560,
    val height: Int = 1600,
    val aspectRatio: String = "16:10"
)

@Serializable
data class WallpaperPaletteColors(
    val dominantHex: String = "#1B2A38",
    val vibrantHex: String = "#D49B54",
    val mutedHex: String = "#4A6070",
    val darkVibrantHex: String = "#0B131B",
    val lightVibrantHex: String = "#F2DFC4"
)

@Serializable
data class PhaseBVideoMetadata(
    val isAvailable: Boolean = false,
    val relativeFilePath: String? = null,
    val sha256: String? = null,
    val sourceNodeUrl: String? = null
)

@Serializable
data class WallpaperManifest(
    val assetId: String,
    val title: String,
    val createdAtEpochMs: Long,
    val lastUsedEpochMs: Long,
    val isPinned: Boolean = false,
    val resolution: ResolutionSpec = ResolutionSpec(),
    val palette: WallpaperPaletteColors = WallpaperPaletteColors(),
    val styleConfig: WallpaperStyleConfig = WallpaperStyleConfig(),
    val phaseBVideo: PhaseBVideoMetadata = PhaseBVideoMetadata()
)

data class LoadedWallpaperBundle(
    val manifest: WallpaperManifest,
    val colorBitmap: Bitmap,
    val depthBitmap: Bitmap,
    val phaseBVideoFile: File? = null
)

