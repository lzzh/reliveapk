package com.coomi.relive

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * 把「高清原照片 + 服务端文字条」合成一张适配屏幕的画页。
 *
 * **文字条统一贴在底部短边**（无论照片横竖）：
 *  - 都是底部一条窄横条，占面积小、显示统一
 *  - 照片居中裁切填满上方区域
 *
 * 文字条（服务端渲染好的 480×160）本来就是横向排版，放底部横条最自然，无需旋转。
 */
object PageComposer {

    /** 底部文字条占画面高度的比例。 */
    private const val BAND_HEIGHT_RATIO = 0.10f

    /** 文字条四周留白比例。 */
    private const val BAND_PADDING_RATIO = 0.08f

    private const val MARGIN_COLOR = Color.WHITE

    fun composeFull(
        photo: ImageBitmap,
        band: ImageBitmap?,
        screenW: Int,
        screenH: Int
    ): ImageBitmap {
        val outW = screenW.coerceAtLeast(1)
        val outH = screenH.coerceAtLeast(1)

        // 底部文字条高度（统一短边窄条）
        val bandH = (outH * BAND_HEIGHT_RATIO).toInt().coerceAtLeast(1)
        val photoW = outW
        val photoH = (outH - bandH).coerceAtLeast(1)

        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(MARGIN_COLOR)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        // 1) 照片：居中裁切填满上方（不旋转，原方向）
        drawCenterCrop(canvas, photo, Rect(0, 0, photoW, photoH), paint)

        // 2) 文字条：横向，等比缩放居中放进底部窄条
        band?.let {
            drawFitted(canvas, it.asAndroidBitmap(), bandRect(0, photoH, outW, bandH), paint)
        }

        return out.asImageBitmap()
    }

    private fun bandRect(left: Int, top: Int, w: Int, h: Int): Rect {
        val padX = (w * BAND_PADDING_RATIO).toInt()
        val padY = (h * BAND_PADDING_RATIO).toInt()
        return Rect(left + padX, top + padY, left + w - padX, top + h - padY)
    }

    private fun drawCenterCrop(canvas: Canvas, src: ImageBitmap, dst: Rect, paint: Paint) {
        val b = src.asAndroidBitmap()
        val srcRect = centerCropSrc(b.width, b.height, dst.width(), dst.height())
        canvas.drawBitmap(b, srcRect, dst, paint)
    }

    private fun drawFitted(canvas: Canvas, src: Bitmap, dst: Rect, paint: Paint) {
        if (src.width <= 0 || src.height <= 0) return
        val sw = src.width.toFloat()
        val sh = src.height.toFloat()
        val dw = dst.width().toFloat()
        val dh = dst.height().toFloat()
        if (dw <= 0 || dh <= 0) return
        // 等比缩放，铺满底部条宽度
        val scale = minOf(dw / sw, dh / sh)
        val w = (sw * scale).toInt().coerceAtLeast(1)
        val h = (sh * scale).toInt().coerceAtLeast(1)
        val left = dst.left + ((dst.width() - w) / 2)
        val top = dst.top + ((dst.height() - h) / 2)
        canvas.drawBitmap(src, null, Rect(left, top, left + w, top + h), paint)
    }

    private fun centerCropSrc(sw: Int, sh: Int, dw: Int, dh: Int): Rect {
        if (sw <= 0 || sh <= 0) return Rect(0, 0, sw, sh)
        val srcRatio = sw.toFloat() / sh
        val dstRatio = dw.toFloat() / dh
        return if (srcRatio > dstRatio) {
            val newW = (sh * dstRatio).toInt().coerceAtLeast(1)
            val x = (sw - newW) / 2
            Rect(x, 0, x + newW, sh)
        } else {
            val newH = (sw / dstRatio).toInt().coerceAtLeast(1)
            val y = (sh - newH) / 2
            Rect(0, y, sw, y + newH)
        }
    }
}
