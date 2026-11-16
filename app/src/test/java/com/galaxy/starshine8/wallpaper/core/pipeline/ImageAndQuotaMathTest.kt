package com.galaxy.starshine8.wallpaper.core.pipeline

import com.galaxy.starshine8.wallpaper.core.storage.WallpaperQuotaManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Matt Pocock Gate 4 — 16:10 Focal Crop, Power-of-Two Subsampling & 512MB/30-Item LRU Eviction Tests
 */
class ImageAndQuotaMathTest {

    private val targetAspect = 2560.0f / 1600.0f // 1.6f

    private fun computePowerOfTwoSampleSize(w: Int, h: Int, reqW: Int, reqH: Int): Int {
        var inSampleSize = 1
        if (h > reqH || w > reqW) {
            val halfH = h / 2
            val halfW = w / 2
            while ((halfH / inSampleSize) >= reqH && (halfW / inSampleSize) >= reqW) {
                inSampleSize *= 2
            }
        }
        return inSampleSize.coerceAtLeast(1)
    }

    private fun compute16By10CropDimensions(srcW: Int, srcH: Int): Triple<Int, Int, Int> {
        val aspect = srcW.toFloat() / srcH.toFloat()
        return if (aspect > targetAspect) {
            val cropW = (srcH * targetAspect).toInt().coerceAtMost(srcW)
            Triple(cropW, srcH, 0)
        } else {
            val cropH = (srcW / targetAspect).toInt().coerceAtMost(srcH)
            val top = ((srcH - cropH) * 0.42f).toInt().coerceIn(0, srcH - cropH)
            Triple(srcW, cropH, top)
        }
    }

    @Test
    fun `power of two sample size downscales 48MP image safely without dropping below 2560x1600`() {
        // 8000x6000 (48MP) -> inSampleSize=2 -> 4000x3000 (>= 2560x1600)
        val sampleSize = computePowerOfTwoSampleSize(8000, 6000, 2560, 1600)
        assertEquals(2, sampleSize)
        assertTrue(8000 / sampleSize >= 2560)
        assertTrue(6000 / sampleSize >= 1600)
    }

    @Test
    fun `portrait 9-16 photo crops to exact 16-10 aspect ratio biased to 42 percent upper focal point`() {
        val (cropW, cropH, topOffset) = compute16By10CropDimensions(2160, 3840)
        assertEquals(2160, cropW)
        assertEquals(1350, cropH) // 2160 / 1.6 = 1350
        assertTrue(abs((cropW.toFloat() / cropH.toFloat()) - targetAspect) < 0.001f)
        // 42% bias should be strictly above 50% center ((3840 - 1350) * 0.5 = 1245)
        assertTrue(topOffset < 1245)
    }

    @Test
    fun `LRU eviction preserves pinned entries and evicts oldest unpinned entries beyond 30 items`() {
        val entries = (1..32).map { idx ->
            WallpaperQuotaManager.LruIndexEntry(
                assetId = "wp_$idx",
                title = "Wallpaper $idx",
                createdAtEpochMs = idx * 1000L,
                lastUsedEpochMs = idx * 1000L,
                isPinned = (idx == 1), // Oldest item is pinned!
                sizeBytes = 10L * 1024L * 1024L
            )
        }.toMutableList()

        val activeId = "wp_32"
        val evictCandidates = entries
            .filter { !it.isPinned && it.assetId != activeId }
            .sortedBy { it.lastUsedEpochMs }
            .toMutableList()

        while (entries.size > WallpaperQuotaManager.MAX_WALLPAPER_COUNT && evictCandidates.isNotEmpty()) {
            val victim = evictCandidates.removeAt(0)
            entries.removeAll { it.assetId == victim.assetId }
        }

        assertEquals(30, entries.size)
        assertTrue(entries.any { it.assetId == "wp_1" }) // Pinned item survived
        assertFalse(entries.any { it.assetId == "wp_2" }) // Oldest unpinned evicted
        assertFalse(entries.any { it.assetId == "wp_3" }) // Second oldest unpinned evicted
    }
}
