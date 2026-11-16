package com.galaxy.starshine8.wallpaper.core.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Build
import com.galaxy.starshine8.wallpaper.core.contract.LoadedWallpaperBundle
import com.galaxy.starshine8.wallpaper.core.contract.PhaseBVideoMetadata
import com.galaxy.starshine8.wallpaper.core.contract.WallpaperManifest
import com.galaxy.starshine8.wallpaper.core.contract.WallpaperPaletteColors
import com.galaxy.starshine8.wallpaper.core.contract.WallpaperStyleConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

typealias LocalWallpaperRepository = WallpaperQuotaManager

/**
 * 原子资产包与 512MB / 30套 LRU 配额管家（WallpaperQuotaManager / LocalWallpaperRepository）。
 */
class WallpaperQuotaManager(private val context: Context) {

    companion object {
        const val MAX_STORAGE_QUOTA_BYTES = 512L * 1024L * 1024L
        const val MAX_WALLPAPER_COUNT = 30
        const val FILE_COLOR_WEBP = "color_2560x1600.webp"
        const val FILE_DEPTH_PNG = "depth_8bit.png"
        const val FILE_THUMB_WEBP = "thumb_640x400.webp"
        const val FILE_VIDEO_MP4 = "video_loop.mp4"
        const val FILE_MANIFEST_JSON = "manifest.json"
        const val FILE_INDEX_LRU_JSON = "index_lru.json"
    }

    @Serializable
    data class LruIndexEntry(
        val assetId: String,
        val title: String,
        val createdAtEpochMs: Long,
        val lastUsedEpochMs: Long,
        val isPinned: Boolean,
        val sizeBytes: Long
    )

    @Serializable
    data class GlobalLruIndex(
        val activeAssetId: String? = null,
        val totalBytes: Long = 0L,
        val entries: List<LruIndexEntry> = emptyList()
    )

    private val storageMutex = Mutex()
    private val jsonCodec = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val wallpapersRootDir: File
        get() = File(context.filesDir, "wallpapers").apply { mkdirs() }

    suspend fun commitNewWallpaperBundle(
        title: String,
        colorBitmap2560x1600: Bitmap,
        thumbBitmap640x400: Bitmap,
        depthBitmap640x400: Bitmap,
        paletteColors: WallpaperPaletteColors,
        styleConfig: WallpaperStyleConfig = WallpaperStyleConfig()
    ): LoadedWallpaperBundle = storageMutex.withLock {
        val nowMs = System.currentTimeMillis()
        val dateStr = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(nowMs))
        val shortHash = UUID.randomUUID().toString().replace("-", "").take(6)
        val assetId = "wp_${dateStr}_$shortHash"

        val tempDir = File(wallpapersRootDir, ".tmp_$assetId").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }

        val colorFile = File(tempDir, FILE_COLOR_WEBP)
        val thumbFile = File(tempDir, FILE_THUMB_WEBP)
        val depthFile = File(tempDir, FILE_DEPTH_PNG)
        val manifestFile = File(tempDir, FILE_MANIFEST_JSON)

        compressToWebp(colorBitmap2560x1600, colorFile, quality = 95)
        compressToWebp(thumbBitmap640x400, thumbFile, quality = 88)
        FileOutputStream(depthFile).use { fos ->
            depthBitmap640x400.compress(Bitmap.CompressFormat.PNG, 100, fos)
            fos.flush()
        }

        val manifest = WallpaperManifest(
            assetId = assetId,
            title = title,
            createdAtEpochMs = nowMs,
            lastUsedEpochMs = nowMs,
            isPinned = false,
            palette = paletteColors,
            styleConfig = styleConfig,
            phaseBVideo = PhaseBVideoMetadata(isAvailable = false)
        )
        manifestFile.writeText(jsonCodec.encodeToString(manifest), Charsets.UTF_8)

        val finalDir = File(wallpapersRootDir, assetId)
        if (finalDir.exists()) {
            finalDir.deleteRecursively()
        }
        val renamed = tempDir.renameTo(finalDir)
        if (!renamed) {
            tempDir.copyRecursively(finalDir, overwrite = true)
            tempDir.deleteRecursively()
        }

        enforceQuotaAndRebuildIndex(newActiveAssetId = assetId)

        LoadedWallpaperBundle(
            manifest = manifest,
            colorBitmap = colorBitmap2560x1600,
            depthBitmap = depthBitmap640x400,
            phaseBVideoFile = null
        )
    }

    suspend fun loadActiveOrDefaultWallpaperBundle(): LoadedWallpaperBundle? = storageMutex.withLock {
        val index = readGlobalLruIndex()
        val targetId = index.activeAssetId ?: index.entries.maxByOrNull { it.lastUsedEpochMs }?.assetId
        if (targetId != null) {
            loadBundleByIdInternal(targetId)?.let { return@withLock it }
        }
        return@withLock createBuiltInDefaultBundleInternal()
    }

    fun getThumbnailFile(assetId: String): File {
        return File(File(wallpapersRootDir, assetId), FILE_THUMB_WEBP)
    }

    suspend fun activateWallpaperById(assetId: String): LoadedWallpaperBundle? = storageMutex.withLock {
        val assetDir = File(wallpapersRootDir, assetId)
        val manifestFile = File(assetDir, FILE_MANIFEST_JSON)
        if (!manifestFile.exists()) return@withLock null
        runCatching {
            val currentManifest = jsonCodec.decodeFromString<WallpaperManifest>(manifestFile.readText(Charsets.UTF_8))
            val updated = currentManifest.copy(lastUsedEpochMs = System.currentTimeMillis())
            val tmpFile = File(assetDir, "$FILE_MANIFEST_JSON.tmp")
            tmpFile.writeText(jsonCodec.encodeToString(updated), Charsets.UTF_8)
            tmpFile.renameTo(manifestFile)
            enforceQuotaAndRebuildIndex(newActiveAssetId = assetId)
        }
        return@withLock loadBundleByIdInternal(assetId)
    }

    suspend fun togglePinStatus(assetId: String): List<WallpaperManifest> = storageMutex.withLock {
        val assetDir = File(wallpapersRootDir, assetId)
        val manifestFile = File(assetDir, FILE_MANIFEST_JSON)
        if (manifestFile.exists()) {
            runCatching {
                val current = jsonCodec.decodeFromString<WallpaperManifest>(manifestFile.readText(Charsets.UTF_8))
                val updated = current.copy(isPinned = !current.isPinned)
                val tmpFile = File(assetDir, "$FILE_MANIFEST_JSON.tmp")
                tmpFile.writeText(jsonCodec.encodeToString(updated), Charsets.UTF_8)
                tmpFile.renameTo(manifestFile)
                enforceQuotaAndRebuildIndex(newActiveAssetId = readGlobalLruIndex().activeAssetId)
            }
        }
        return@withLock scanAllManifestsInternal().sortedByDescending { it.lastUsedEpochMs }
    }

    suspend fun getQuotaSummary(): Pair<Long, Int> = storageMutex.withLock {
        val idx = readGlobalLruIndex()
        idx.totalBytes to idx.entries.size
    }

    suspend fun updateStyleConfigForAsset(assetId: String, newConfig: WallpaperStyleConfig) = storageMutex.withLock {
        val assetDir = File(wallpapersRootDir, assetId)
        val manifestFile = File(assetDir, FILE_MANIFEST_JSON)
        if (!manifestFile.exists()) return@withLock
        runCatching {
            val currentManifest = jsonCodec.decodeFromString<WallpaperManifest>(manifestFile.readText(Charsets.UTF_8))
            val updated = currentManifest.copy(
                lastUsedEpochMs = System.currentTimeMillis(),
                styleConfig = newConfig
            )
            val tmpFile = File(assetDir, "$FILE_MANIFEST_JSON.tmp")
            tmpFile.writeText(jsonCodec.encodeToString(updated), Charsets.UTF_8)
            tmpFile.renameTo(manifestFile)
            enforceQuotaAndRebuildIndex(newActiveAssetId = assetId)
        }
    }

    suspend fun attachVerifiedPhaseBVideo(
        assetId: String,
        downloadedMp4TempFile: File,
        expectedSha256: String,
        sourceNodeUrl: String
    ): LoadedWallpaperBundle? = storageMutex.withLock {
        val actualSha256 = computeFileSha256(downloadedMp4TempFile)
        if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
            downloadedMp4TempFile.delete()
            return@withLock null
        }

        val assetDir = File(wallpapersRootDir, assetId)
        if (!assetDir.exists()) return@withLock null

        val targetVideoFile = File(assetDir, FILE_VIDEO_MP4)
        if (targetVideoFile.exists()) targetVideoFile.delete()
        if (!downloadedMp4TempFile.renameTo(targetVideoFile)) {
            downloadedMp4TempFile.copyTo(targetVideoFile, overwrite = true)
            downloadedMp4TempFile.delete()
        }

        val manifestFile = File(assetDir, FILE_MANIFEST_JSON)
        val currentManifest = jsonCodec.decodeFromString<WallpaperManifest>(manifestFile.readText(Charsets.UTF_8))
        val updatedManifest = currentManifest.copy(
            lastUsedEpochMs = System.currentTimeMillis(),
            phaseBVideo = PhaseBVideoMetadata(
                isAvailable = true,
                relativeFilePath = FILE_VIDEO_MP4,
                sha256 = actualSha256,
                sourceNodeUrl = sourceNodeUrl
            )
        )
        val tmpManifest = File(assetDir, "$FILE_MANIFEST_JSON.tmp")
        tmpManifest.writeText(jsonCodec.encodeToString(updatedManifest), Charsets.UTF_8)
        tmpManifest.renameTo(manifestFile)

        enforceQuotaAndRebuildIndex(newActiveAssetId = assetId)
        return@withLock loadBundleByIdInternal(assetId)
    }

    suspend fun listAllManifests(): List<WallpaperManifest> = storageMutex.withLock {
        scanAllManifestsInternal().sortedByDescending { it.lastUsedEpochMs }
    }

    private fun loadBundleByIdInternal(assetId: String): LoadedWallpaperBundle? {
        val dir = File(wallpapersRootDir, assetId)
        val manifestFile = File(dir, FILE_MANIFEST_JSON)
        val colorFile = File(dir, FILE_COLOR_WEBP)
        val depthFile = File(dir, FILE_DEPTH_PNG)
        if (!manifestFile.exists() || !colorFile.exists() || !depthFile.exists()) {
            return null
        }
        return runCatching {
            val manifest = jsonCodec.decodeFromString<WallpaperManifest>(manifestFile.readText(Charsets.UTF_8))
            val colorBmp = BitmapFactory.decodeFile(colorFile.absolutePath) ?: return null
            val depthBmp = BitmapFactory.decodeFile(depthFile.absolutePath) ?: return null
            val videoFile = File(dir, FILE_VIDEO_MP4).takeIf { manifest.phaseBVideo.isAvailable && it.exists() }
            LoadedWallpaperBundle(
                manifest = manifest,
                colorBitmap = colorBmp,
                depthBitmap = depthBmp,
                phaseBVideoFile = videoFile
            )
        }.getOrNull()
    }

    private fun enforceQuotaAndRebuildIndex(newActiveAssetId: String?) {
        val manifests = scanAllManifestsInternal().toMutableList()
        val dirSizes = manifests.associate { m ->
            m.assetId to computeDirectorySizeBytes(File(wallpapersRootDir, m.assetId))
        }.toMutableMap()

        var totalBytes = dirSizes.values.sum()

        val evictCandidates = manifests
            .filter { !it.isPinned && it.assetId != newActiveAssetId }
            .sortedBy { it.lastUsedEpochMs }
            .toMutableList()

        while ((totalBytes > MAX_STORAGE_QUOTA_BYTES || manifests.size > MAX_WALLPAPER_COUNT) && evictCandidates.isNotEmpty()) {
            val victim = evictCandidates.removeAt(0)
            val victimDir = File(wallpapersRootDir, victim.assetId)
            val removedBytes = dirSizes.remove(victim.assetId) ?: 0L
            victimDir.deleteRecursively()
            manifests.removeAll { it.assetId == victim.assetId }
            totalBytes = (totalBytes - removedBytes).coerceAtLeast(0L)
        }

        val entries = manifests.map { m ->
            LruIndexEntry(
                assetId = m.assetId,
                title = m.title,
                createdAtEpochMs = m.createdAtEpochMs,
                lastUsedEpochMs = m.lastUsedEpochMs,
                isPinned = m.isPinned,
                sizeBytes = dirSizes[m.assetId] ?: 0L
            )
        }.sortedByDescending { it.lastUsedEpochMs }

        val activeId = newActiveAssetId ?: entries.firstOrNull()?.assetId
        val newIndex = GlobalLruIndex(
            activeAssetId = activeId,
            totalBytes = totalBytes,
            entries = entries
        )
        val indexFile = File(wallpapersRootDir, FILE_INDEX_LRU_JSON)
        val tmpIndexFile = File(wallpapersRootDir, "$FILE_INDEX_LRU_JSON.tmp")
        tmpIndexFile.writeText(jsonCodec.encodeToString(newIndex), Charsets.UTF_8)
        tmpIndexFile.renameTo(indexFile)
    }

    private fun scanAllManifestsInternal(): List<WallpaperManifest> {
        val children = wallpapersRootDir.listFiles() ?: return emptyList()
        return children.mapNotNull { dir ->
            if (!dir.isDirectory || dir.name.startsWith(".")) return@mapNotNull null
            val manifestFile = File(dir, FILE_MANIFEST_JSON)
            if (!manifestFile.exists()) return@mapNotNull null
            runCatching {
                jsonCodec.decodeFromString<WallpaperManifest>(manifestFile.readText(Charsets.UTF_8))
            }.getOrNull()
        }
    }

    private fun readGlobalLruIndex(): GlobalLruIndex {
        val file = File(wallpapersRootDir, FILE_INDEX_LRU_JSON)
        if (!file.exists()) return GlobalLruIndex()
        return runCatching {
            jsonCodec.decodeFromString<GlobalLruIndex>(file.readText(Charsets.UTF_8))
        }.getOrDefault(GlobalLruIndex())
    }

    private fun computeDirectorySizeBytes(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun compressToWebp(bitmap: Bitmap, outFile: File, quality: Int) {
        FileOutputStream(outFile).use { fos ->
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
            bitmap.compress(format, quality, fos)
            fos.flush()
        }
    }

    private fun createBuiltInDefaultBundleInternal(): LoadedWallpaperBundle {
        val colorBmp = Bitmap.createBitmap(2560, 1600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(colorBmp)
        val bgPaint = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, 1600f,
                intArrayOf(
                    Color.parseColor("#0B131B"),
                    Color.parseColor("#1B2A38"),
                    Color.parseColor("#2E4456"),
                    Color.parseColor("#13202B")
                ),
                floatArrayOf(0f, 0.48f, 0.72f, 1.0f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, 2560f, 1600f, bgPaint)

        val sunPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#44D49B54")
        }
        canvas.drawCircle(1820f, 520f, 260f, sunPaint)

        val thumbBmp = Bitmap.createScaledBitmap(colorBmp, 640, 400, true)
        val depthBmp = Bitmap.createBitmap(640, 400, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until 400) {
                val v = (y * 255) / 399
                val gray = Color.rgb(v, v, v)
                for (x in 0 until 640) {
                    setPixel(x, y, gray)
                }
            }
        }

        val nowMs = System.currentTimeMillis()
        val assetId = "wp_20261015_a8f9c2"
        val dir = File(wallpapersRootDir, assetId).apply { mkdirs() }
        compressToWebp(colorBmp, File(dir, FILE_COLOR_WEBP), 95)
        compressToWebp(thumbBmp, File(dir, FILE_THUMB_WEBP), 88)
        FileOutputStream(File(dir, FILE_DEPTH_PNG)).use { fos ->
            depthBmp.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }

        val manifest = WallpaperManifest(
            assetId = assetId,
            title = "西湖晨雾·星耀画卷",
            createdAtEpochMs = nowMs,
            lastUsedEpochMs = nowMs,
            isPinned = true,
            palette = WallpaperPaletteColors(
                dominantHex = "#1B2A38",
                vibrantHex = "#D49B54",
                mutedHex = "#4A6070",
                darkVibrantHex = "#0B131B",
                lightVibrantHex = "#F2DFC4"
            )
        )
        File(dir, FILE_MANIFEST_JSON).writeText(jsonCodec.encodeToString(manifest), Charsets.UTF_8)
        enforceQuotaAndRebuildIndex(newActiveAssetId = assetId)

        return LoadedWallpaperBundle(
            manifest = manifest,
            colorBitmap = colorBmp,
            depthBitmap = depthBmp,
            phaseBVideoFile = null
        )
    }

    fun computeFileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { fis ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val read = fis.read(buf)
                if (read <= 0) break
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
