package com.galaxy.starshine8.wallpaper.core.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import androidx.exifinterface.media.ExifInterface
import androidx.palette.graphics.Palette
import com.galaxy.starshine8.wallpaper.core.contract.WallpaperPaletteColors
import java.io.File
import java.io.FileInputStream
import kotlin.math.max
import kotlin.math.min

/**
 * 车机端防 OOM 解码兜底、EXIF 旋转校正、Display P3 广色域归一化与 Palette 五色提取器（SafeImageNormalizer）。
 * 严格落实 ADR-001 第 2、3 节及车规级补充规范：
 * 1. 读取 ExifInterface (TAG_ORIENTATION)，自动校正手机竖拍照片 90°/180°/270° 旋转角与镜像翻转。
 * 2. 指定 inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)，将 iPhone/旗舰安卓 Display P3 广色域照片
 *    平滑映射至标准 sRGB，杜绝车机大屏偏色或发灰。
 * 3. 针对跳过 H5 裁剪直接上传的 24MP~50MP（6000×4000+）超清大图，强制先用 inJustDecodeBounds=true 读取尺寸，
 *    计算 2 的幂次 inSampleSize 并执行 16:10 显著性居中裁剪，精确输出 2560×1600 ARGB_8888。
 */
class SafeImageNormalizer(private val context: Context) {

    companion object {
        const val TARGET_WIDTH = 2560
        const val TARGET_HEIGHT = 1600
        const val THUMB_WIDTH = 640
        const val THUMB_HEIGHT = 400
        private const val TARGET_ASPECT = 2560.0f / 1600.0f // 1.6 (16:10)
    }

    data class NormalizedImageOutput(
        val bitmap2560x1600: Bitmap,
        val thumbnail640x400: Bitmap,
        val paletteColors: WallpaperPaletteColors
    )

    private data class ExifTransform(
        val rotationDegrees: Int,
        val flipHorizontal: Boolean
    )

    fun normalizeTo16By10AndExtractPalette(sourceImageFile: File): NormalizedImageOutput {
        // 1. 解析 EXIF 方向标签（处理手机竖拍 90°/270° 旋转）
        val exifTransform = readExifTransform(sourceImageFile)
        val isSwappedDimensions = (exifTransform.rotationDegrees == 90 || exifTransform.rotationDegrees == 270)

        // 2. 第一遍仅读取边界，0 像素内存分配
        val boundsOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        FileInputStream(sourceImageFile).use { fis ->
            BitmapFactory.decodeStream(fis, null, boundsOptions)
        }

        val rawWidth = boundsOptions.outWidth.coerceAtLeast(1)
        val rawHeight = boundsOptions.outHeight.coerceAtLeast(1)
        val orientedWidth = if (isSwappedDimensions) rawHeight else rawWidth
        val orientedHeight = if (isSwappedDimensions) rawWidth else rawHeight

        // 3. 计算 2 的幂次 inSampleSize，并强制绑定 sRGB 色域防止 Display P3 广色域失真
        val sampleSize = calculatePowerOfTwoSampleSize(
            cropWidth = orientedWidth,
            cropHeight = orientedHeight,
            reqWidth = TARGET_WIDTH,
            reqHeight = TARGET_HEIGHT
        )

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
            inMutable = false
        }

        // 4. 若无 EXIF 旋转且无翻转，直接使用 BitmapRegionDecoder 局部解码 16:10 焦点区域；
        //    若存在 EXIF 旋转，则先按 inSampleSize 降采样解码并应用 EXIF 旋转矩阵，再执行 16:10 黄金焦点裁剪
        val decodedOrientedBitmap: Bitmap = if (exifTransform.rotationDegrees == 0 && !exifTransform.flipHorizontal) {
            val cropRect = compute16By10FocalCropRect(rawWidth, rawHeight)
            runCatching {
                FileInputStream(sourceImageFile).use { fis ->
                    val decoder = BitmapRegionDecoder.newInstance(fis, false)
                    val bmp = decoder?.decodeRegion(cropRect, decodeOptions)
                    decoder?.recycle()
                    bmp
                }
            }.getOrNull() ?: FileInputStream(sourceImageFile).use { fis ->
                BitmapFactory.decodeStream(fis, null, decodeOptions)
            } ?: throw IllegalArgumentException("Unable to decode uploaded image file: ${sourceImageFile.name}")
        } else {
            val subsampled = FileInputStream(sourceImageFile).use { fis ->
                BitmapFactory.decodeStream(fis, null, decodeOptions)
            } ?: throw IllegalArgumentException("Unable to decode EXIF image file: ${sourceImageFile.name}")
            applyExifMatrixTransform(subsampled, exifTransform)
        }

        // 5. 精确缩放与 16:10 黄金焦点居中规整至 2560x1600
        val exact2560x1600 = if (decodedOrientedBitmap.width == TARGET_WIDTH && decodedOrientedBitmap.height == TARGET_HEIGHT) {
            decodedOrientedBitmap
        } else {
            val scaled = scaleFocalCenterCropExact(decodedOrientedBitmap, TARGET_WIDTH, TARGET_HEIGHT)
            if (scaled !== decodedOrientedBitmap) {
                decodedOrientedBitmap.recycle()
            }
            scaled
        }

        // 6. 生成 640x400 缩略图供车机壁纸工坊列表使用并快速提取 Palette 五色
        val thumb640x400 = Bitmap.createScaledBitmap(exact2560x1600, THUMB_WIDTH, THUMB_HEIGHT, true)
        val paletteColors = extractFiveColorPalette(thumb640x400)

        return NormalizedImageOutput(
            bitmap2560x1600 = exact2560x1600,
            thumbnail640x400 = thumb640x400,
            paletteColors = paletteColors
        )
    }

    private fun readExifTransform(file: File): ExifTransform {
        return runCatching {
            val exif = ExifInterface(file.absolutePath)
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> ExifTransform(90, false)
                ExifInterface.ORIENTATION_ROTATE_180 -> ExifTransform(180, false)
                ExifInterface.ORIENTATION_ROTATE_270 -> ExifTransform(270, false)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> ExifTransform(0, true)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> ExifTransform(180, true)
                ExifInterface.ORIENTATION_TRANSPOSE -> ExifTransform(90, true)
                ExifInterface.ORIENTATION_TRANSVERSE -> ExifTransform(270, true)
                else -> ExifTransform(0, false)
            }
        }.getOrDefault(ExifTransform(0, false))
    }

    private fun applyExifMatrixTransform(source: Bitmap, transform: ExifTransform): Bitmap {
        val matrix = Matrix()
        if (transform.flipHorizontal) {
            matrix.postScale(-1f, 1f, source.width / 2f, source.height / 2f)
        }
        if (transform.rotationDegrees != 0) {
            matrix.postRotate(transform.rotationDegrees.toFloat())
        }
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (rotated !== source) {
            source.recycle()
        }
        return rotated
    }

    private fun compute16By10FocalCropRect(width: Int, height: Int): Rect {
        val aspect = width.toFloat() / height.toFloat()
        return if (aspect > TARGET_ASPECT) {
            val cropW = (height * TARGET_ASPECT).toInt().coerceAtMost(width)
            val left = (width - cropW) / 2
            Rect(left, 0, left + cropW, height)
        } else {
            val cropH = (width / TARGET_ASPECT).toInt().coerceAtMost(height)
            // 竖构图保留偏上 42% 黄金视觉焦点，避免裁切掉人物头部或景物主体
            val top = ((height - cropH) * 0.42f).toInt().coerceIn(0, height - cropH)
            Rect(0, top, width, top + cropH)
        }
    }

    private fun calculatePowerOfTwoSampleSize(
        cropWidth: Int,
        cropHeight: Int,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        var inSampleSize = 1
        if (cropHeight > reqHeight || cropWidth > reqWidth) {
            val halfHeight = cropHeight / 2
            val halfWidth = cropWidth / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize.coerceAtLeast(1)
    }

    private fun scaleFocalCenterCropExact(source: Bitmap, targetW: Int, targetH: Int): Bitmap {
        val output = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val scale = max(targetW.toFloat() / source.width, targetH.toFloat() / source.height)
        val dx = (targetW - source.width * scale) * 0.5f
        // 纵向采用 0.42f 黄金焦点偏移
        val dy = (targetH - source.height * scale) * 0.42f
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postTranslate(dx, dy)
        }
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        canvas.drawBitmap(source, matrix, paint)
        return output
    }

    private fun extractFiveColorPalette(bitmap: Bitmap): WallpaperPaletteColors {
        val palette = Palette.from(bitmap).maximumColorCount(24).generate()
        val defaultDominant = Color.parseColor("#1B2A38")
        val dominantInt = palette.getDominantColor(defaultDominant)

        val vibrantInt = palette.getVibrantColor(shiftHsv(dominantInt, hueDelta = 28f, satScale = 1.35f, valScale = 1.25f))
        val mutedInt = palette.getMutedColor(shiftHsv(dominantInt, hueDelta = -12f, satScale = 0.65f, valScale = 0.85f))
        val darkVibrantInt = palette.getDarkVibrantColor(shiftHsv(dominantInt, hueDelta = 0f, satScale = 1.1f, valScale = 0.35f))
        val lightVibrantInt = palette.getLightVibrantColor(shiftHsv(vibrantInt, hueDelta = 8f, satScale = 0.55f, valScale = 1.45f))

        return WallpaperPaletteColors(
            dominantHex = formatHexColor(dominantInt),
            vibrantHex = formatHexColor(vibrantInt),
            mutedHex = formatHexColor(mutedInt),
            darkVibrantHex = formatHexColor(darkVibrantInt),
            lightVibrantHex = formatHexColor(lightVibrantInt)
        )
    }

    private fun shiftHsv(colorInt: Int, hueDelta: Float, satScale: Float, valScale: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(colorInt, hsv)
        hsv[0] = (hsv[0] + hueDelta + 360f) % 360f
        hsv[1] = min(1.0f, max(0.12f, hsv[1] * satScale))
        hsv[2] = min(0.96f, max(0.10f, hsv[2] * valScale))
        return Color.HSVToColor(hsv)
    }

    private fun formatHexColor(colorInt: Int): String {
        return String.format("#%06X", 0xFFFFFF and colorInt)
    }
}
