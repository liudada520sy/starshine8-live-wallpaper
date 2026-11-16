package com.galaxy.starshine8.wallpaper.core.contract

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Matt Pocock Gate 4 — Contract & Serialization Round-Trip Unit Tests
 */
class StarshineCoreContractsTest {

    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `DomainResult fold dispatches Success and Failure cleanly`() {
        val ok: DomainResult<Int, WallpaperDomainError> = DomainResult.Success(2560)
        val err: DomainResult<Int, WallpaperDomainError> = DomainResult.Failure(
            WallpaperDomainError.InvalidImagePayload("corrupted header")
        )

        val okMsg = ok.fold(onSuccess = { "width=$it" }, onFailure = { "err" })
        val errMsg = err.fold(onSuccess = { "width=$it" }, onFailure = { "failed:${(it as WallpaperDomainError.InvalidImagePayload).reason}" })

        assertEquals("width=2560", okMsg)
        assertEquals("failed:corrupted header", errMsg)
    }

    @Test
    fun `WallpaperManifest serialization round-trip preserves 16-10 resolution and style config`() {
        val original = WallpaperManifest(
            assetId = "wp_20261116_test01",
            title = "西湖晨雾·星耀画卷",
            createdAtEpochMs = 1794836000000L,
            lastUsedEpochMs = 1794836000000L,
            isPinned = true,
            styleConfig = WallpaperStyleConfig(
                baseMode = BaseVisualMode.HYBRID_RIPPLE_PARALLAX,
                parallaxMaxUvOffset = 0.022f,
                rippleHorizonThreshold = 0.72f,
                tyndallRayIntensity = 0.15f,
                enableAudioVisualizerOverlay = true,
                particleOverlayPreset = ParticlePreset.MISTY_RAIN_DRIZZLE
            )
        )

        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<WallpaperManifest>(encoded)

        assertEquals(original, decoded)
        assertEquals(2560, decoded.resolution.width)
        assertEquals(1600, decoded.resolution.height)
        assertTrue(decoded.isPinned)
    }

    @Test
    fun `CockpitEnvironmentState structural equality compares 32-band float array`() {
        val binsA = FloatArray(32) { it / 31.0f }
        val binsB = FloatArray(32) { it / 31.0f }
        val binsC = FloatArray(32) { 0.5f }

        val stateA = CockpitEnvironmentState(false, 1.0f, binsA, true, true)
        val stateB = CockpitEnvironmentState(false, 1.0f, binsB, true, true)
        val stateC = CockpitEnvironmentState(false, 1.0f, binsC, true, true)

        assertEquals(stateA, stateB)
        assertEquals(stateA.hashCode(), stateB.hashCode())
        assertNotEquals(stateA, stateC)
    }
}
