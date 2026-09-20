package com.coomi.relive

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayInputStream

/**
 * 解码图片字节流为位图，长边限制在 [maxSide] 内（自动 2 的幂降采样，避免 OOM）。
 *
 * 用于"原图铺满"模式：原图可达 4912×3264（约 6MB），必须降采样后再交给 Compose。
 *
 * 注意：Relive 后端 `/photos/{id}/image` 是把磁盘上的**原始文件**直接返回
 * （非 HEIC 时走 `c.File(photo.FilePath)`，不做方向校正），而 `BitmapFactory`
 * 不会自动应用 EXIF 旋转标签。因此这里必须自己按 EXIF 方向把图转正，
 * 否则手机竖拍（存成横向像素 + Orientation=6/8）的照片会横躺，
 * 且 [PageComposer] 依据宽高判断"横竖"的排版也会跟着错。
 */
fun ByteArray.decodeDownsampled(maxSide: Int = 2048): ImageBitmap? {
    return try {
        val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(this, 0, size, probe)
        val w = probe.outWidth
        val h = probe.outHeight
        if (w <= 0 || h <= 0) return null

        var sample = 1
        while (maxOf(w, h) / sample > maxSide) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeByteArray(this, 0, size, opts) ?: return null

        // 按 EXIF 方向转正（读不到标签时视作正常，不做任何处理）
        applyExifOrientation(bmp, readExifOrientation(this)).asImageBitmap()
    } catch (_: Throwable) {
        null
    }
}

/** 读取 EXIF Orientation 标签；任何异常都当作"正常方向"。 */
@Suppress("DEPRECATION")
private fun readExifOrientation(bytes: ByteArray): Int = try {
    val exif = ExifInterface(ByteArrayInputStream(bytes))
    exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
} catch (_: Throwable) {
    ExifInterface.ORIENTATION_NORMAL
}

/** 按 EXIF 方向把位图校正为正向；方向正常时原样返回（不复制）。 */
@Suppress("DEPRECATION")
private fun applyExifOrientation(src: Bitmap, orientation: Int): Bitmap {
    val m = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> m.setRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.setScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            m.setRotate(90f)
            m.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_90 -> m.setRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            m.setRotate(-90f)
            m.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_270 -> m.setRotate(-90f)
        else -> return src
    }
    return try {
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (out !== src) src.recycle()
        out
    } catch (_: Throwable) {
        src
    }
}
