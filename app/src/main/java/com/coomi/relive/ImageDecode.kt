package com.coomi.relive

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * 解码图片字节流为位图，长边限制在 [maxSide] 内（自动 2 的幂降采样，避免 OOM）。
 * 用于"原图铺满"模式：原图可达 4912×3264（约 6MB），必须降采样后再交给 Compose。
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
        bmp.asImageBitmap()
    } catch (_: Throwable) {
        null
    }
}
