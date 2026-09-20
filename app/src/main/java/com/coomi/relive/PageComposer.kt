package com.coomi.relive

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * 把「原照片 + 文字条」合成一张适配屏幕的画页。
 *
 * **白边位置由照片自身的横竖决定**（不是由屏幕方向决定）：
 *  - **横构图照片**（宽 > 高）→ 白边在**右侧**，文字条旋转 90° 竖排
 *  - **竖构图照片**（高 ≥ 宽）→ 白边在**下方**，文字横排
 *
 * 照片在其区域内居中裁切（CENTER_CROP）填满；留白区纯白底，文字条等比缩放居中。
 */
object PageComposer {

    /** 留白区占该方向的比例。 */
    private const val LANDSCAPE_BAND_RATIO = 0.26f   // 横图：右侧白边占宽度 26%
    private const val PORTRAIT_BAND_RATIO = 0.22f    // 竖图：底部白边占高度 22%

    /** 留白区内的留白（文字不贴边）。 */
    private const val BAND_PADDING_RATIO = 0.10f

    private const val MARGIN_COLOR = Color.WHITE

    /**
     * 合成画页。
     *
     * @param photo   原照片（未裁切）
     * @param band    文字条（服务端相框底部 160px；可为 null → 仅留白无字）
     * @param screenW 屏幕宽（像素）
     * @param screenH 屏幕高（像素）
     */
    fun compose(
        photo: ImageBitmap,
        band: ImageBitmap?,
        screenW: Int,
        screenH: Int
    ): ImageBitmap {
        val outW = screenW.coerceAtLeast(1)
        val outH = screenH.coerceAtLeast(1)

        // ★ 按照片自身横竖决定布局
        val photoIsLandscape = photo.width >= photo.height

        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(MARGIN_COLOR)

        val photoPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        if (photoIsLandscape) {
            // 横图：照片占左侧，白边在右
            val bandW = (outW * LANDSCAPE_BAND_RATIO).toInt().coerceAtLeast(1)
            val photoW = (outW - bandW).coerceAtLeast(1)
            val photoH = outH

            drawCenterCrop(canvas, photo, Rect(0, 0, photoW, photoH), photoPaint)

            band?.let {
                // 文字条是横向的 480×160 → 转 90° 变竖向，再等比适配进右侧白边
                val rotated = Bitmap.createBitmap(
                    it.asAndroidBitmap(), 0, 0,
                    it.width, it.height,
                    Matrix().apply { postRotate(90f) }, true
                )
                drawFitted(
                    canvas, rotated,
                    bandRect(photoW, 0, bandW, outH),
                    photoPaint
                )
                rotated.recycle()
            }
        } else {
            // 竖图：照片占上方，白边在下
            val bandH = (outH * PORTRAIT_BAND_RATIO).toInt().coerceAtLeast(1)
            val photoW = outW
            val photoH = (outH - bandH).coerceAtLeast(1)

            drawCenterCrop(canvas, photo, Rect(0, 0, photoW, photoH), photoPaint)

            band?.let {
                // 文字条本来就是横向 → 直接等比适配进底部白边
                drawFitted(
                    canvas, it.asAndroidBitmap(),
                    bandRect(0, photoH, outW, bandH),
                    photoPaint
                )
            }
        }

        return out.asImageBitmap()
    }

    /** 留白区去掉内边距后的实际绘制矩形。 */
    private fun bandRect(left: Int, top: Int, w: Int, h: Int): Rect {
        val padX = (w * BAND_PADDING_RATIO).toInt()
        val padY = (h * BAND_PADDING_RATIO).toInt()
        return Rect(left + padX, top + padY, left + w - padX, top + h - padY)
    }

    /** 居中裁切填满目标矩形。 */
    private fun drawCenterCrop(canvas: Canvas, src: ImageBitmap, dst: Rect, paint: Paint) {
        val b = src.asAndroidBitmap()
        val srcRect = centerCropSrc(b.width, b.height, dst.width(), dst.height())
        canvas.drawBitmap(b, srcRect, dst, paint)
    }

    /** 等比缩放并居中放进目标矩形（不裁切，保持完整）。 */
    private fun drawFitted(canvas: Canvas, src: Bitmap, dst: Rect, paint: Paint) {
        if (src.width <= 0 || src.height <= 0) return
        val sw = src.width.toFloat()
        val sh = src.height.toFloat()
        val dw = dst.width().toFloat()
        val dh = dst.height().toFloat()
        if (dw <= 0 || dh <= 0) return

        val scale = minOf(dw / sw, dh / sh)
        val w = (sw * scale).toInt().coerceAtLeast(1)
        val h = (sh * scale).toInt().coerceAtLeast(1)
        val left = dst.left + ((dst.width() - w) / 2)
        val top = dst.top + ((dst.height() - h) / 2)
        canvas.drawBitmap(src, null, Rect(left, top, left + w, top + h), paint)
    }

    /** 计算居中裁切（CENTER_CROP）所需的源矩形。 */
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
