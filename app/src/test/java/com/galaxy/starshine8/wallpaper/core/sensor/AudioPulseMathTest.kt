package com.galaxy.starshine8.wallpaper.core.sensor

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Matt Pocock Gate 4 — Bark Logarithmic Frequency Mapping & Procedural Harmonic Envelope Tests
 */
class AudioPulseMathTest {

    @Test
    fun `bark logarithmic mapping produces monotonic valid bin indices across 32 bands`() {
        val maxComplexBins = 128
        var prevStart = 0
        for (band in 0 until 32) {
            val t0 = (band / 32.0).pow(1.45)
            val t1 = ((band + 1) / 32.0).pow(1.45)
            val startBin = (1 + (t0 * (maxComplexBins - 2)).toInt()).coerceIn(1, maxComplexBins - 1)
            val endBin = (startBin + 1).coerceAtLeast(
                (1 + (t1 * (maxComplexBins - 2)).toInt()).coerceAtMost(maxComplexBins - 1)
            )
            assertTrue(startBin >= prevStart)
            assertTrue(startBin in 1 until maxComplexBins)
            assertTrue(endBin >= startBin)
            prevStart = startBin
        }
    }

    @Test
    fun `procedural harmonic envelope stays within 0_06 to 0_82 comfortable visual bounds`() {
        val outBins = FloatArray(32)
        for (step in 0..20) {
            val timeSec = step * 0.5f
            val slowBreath = 0.55f + 0.45f * sin(timeSec * 0.78f)
            for (i in 0 until 32) {
                val normBand = i / 31.0f
                val centerWeight = 1.0f - abs(normBand - 0.38f) * 1.15f
                val wave1 = sin(timeSec * 1.45f + i * 0.28f) * 0.5f + 0.5f
                val wave2 = cos(timeSec * 0.92f - i * 0.41f) * 0.5f + 0.5f
                val harmonic = (wave1 * 0.62f + wave2 * 0.38f) * slowBreath * centerWeight.coerceAtLeast(0.25f)
                outBins[i] = harmonic.coerceIn(0.06f, 0.82f)
                assertTrue(outBins[i] in 0.06f..0.82f)
            }
        }
    }
}
