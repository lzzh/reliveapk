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
 * 把「照片 + 文字条」合成一张适配屏幕的画页。
 *
 * 布局规则（照片占长边 75%，留白区占 25%）：
 *  - **横屏**：照片居中裁切铺在**左侧**，右侧留白条内放文字（文字条旋转 90° 竖排）
 *  - **竖屏**：照片居中裁切铺在**上方**，下方留白条内放文字（横排）
 *
 * 留白区为纯白底 + 深色文字，与 Relive 相框的信息区风格一致。
 * 文字来源：[EInkDecoder] 解出的 480×800 相框中**底部 160px 文字条**（服务端已渲染好的文案 + 日期）。
 */
object PageComposer {

    /** 照片占长边的比例。 */
    private const val PHOTO_FRACTION = 0.75f

    /** 留白条底色（白）与内边距比例。 */
    private const val MARGIN_COLOR = Color.WHITE

    /**
     * 合成画页。
     *
     * @param photo    原照片
     * @param band     文字条（来自服务端相框底部 160px；可为 null → 仅留白无字）
     * @param screenW  屏幕宽（像素）
     * @param screenH  屏幕高（像素）
     */
    fun compose(
        photo: ImageBitmap,
        band: ImageBitmap?,
        screenW: Int,
        screenH: Int
    ): ImageBitmap {
        val outW = screenW.coerceAtLeast(1)
        val outH = screenH.coerceAtLeast(1)
        val landscape = outW >= outH

        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(MARGIN_COLOR)

        // 照片与留白区的划分
        val photoW: Int
        val photoH: Int
        val bandLeft: Int
        val bandTop: Int
        val bandW: Int
        val bandH: Int

        if (landscape) {
            photoW = (outW * PHOTO_FRACTION).toInt()
            photoH = outH
            bandLeft = photoW
            bandTop = 0
            bandW = outW - photoW
            bandH = outH
        } else {
            photoW = outW
            photoH = (outH * PHOTO_FRACTION).toInt()
            bandLeft = 0
            bandTop = photoH
            bandW = outW
            bandH = outH - photoH
        }

        // 1) 照片：居中裁切（CENTER_CROP）铺满照片区
        val src = photo.asAndroidBitmap()
        val srcRect = centerCropSrc(src.width, src.height, photoW, photoH)
        val dstRect = Rect(0, 0, photoW, photoH)
        val photoPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        canvas.drawBitmap(src, srcRect, dstRect, photoPaint)

        // 2) 文字条：铺进留白区
        band?.let {
            val b = it.asAndroidBitmap()
            val bandDst = Rect(bandLeft, bandTop, bandLeft + bandW, bandTop + bandH)
            val bandPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            if (landscape) {
                // 横屏：留白条是竖条，把文字条顺时针转 90° 后铺满
                val rotated = Bitmap.createBitmap(
                    b, 0, 0, b.width, b.height,
                    Matrix().apply { postRotate(90f) }, true
                )
                val inner = innerRect(bandLeft, bandTop, bandW, bandH)
                canvas.drawBitmap(rotated, null, inner, bandPaint)
                rotated.recycle()
            } else {
                // 竖屏：留白条是横条，文字条原方向铺满
                val inner = innerRect(bandLeft, bandTop, bandW, bandH)
                canvas.drawBitmap(b, null, inner, bandPaint)
            }
        }

        return out.asImageBitmap()
    }

    /** 留白区内边距：留出 12% 的呼吸空间，文字不贴边。 */
    private fun innerRect(left: Int, top: Int, w: Int, h: Int): Rect {
        val padX = (w * 0.10f).toInt()
        val padY = (h * 0.10f).toInt()
        return Rect(left + padX, top + padY, left + w - padX, top + h - padY)
    }

    /** 计算居中裁切（CENTER_CROP）所需的源矩形。 */
    private fun centerCropSrc(sw: Int, sh: Int, dw: Int, dh: Int): Rect {
        if (sw <= 0 || sh <= 0) return Rect(0, 0, sw, sh)
        val srcRatio = sw.toFloat() / sh
        val dstRatio = dw.toFloat() / dh
        return if (srcRatio > dstRatio) {
            // 源更宽 → 裁左右
            val newW = (sh * dstRatio).toInt().coerceAtLeast(1)
            val x = (sw - newW) / 2
            Rect(x, 0, x + newW, sh)
        } else {
            // 源更高 → 裁上下
            val newH = (sw / dstRatio).toInt().coerceAtLeast(1)
            val y = (sh - newH) / 2
            Rect(0, y, sw, y + newH)
        }
    }
}
