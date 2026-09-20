package com.coomi.relive

import android.graphics.Bitmap
import android.graphics.Matrix

/**
 * 把 Relive 设备端 display.bin 的 4-bit 双像素流解码为 [Bitmap]，并可旋转 90° 适配横屏。
 *
 * 数据布局（与 Relive 官方源码 display_assets.go 完全一致）：
 *  - 竖版规格 480(宽) x 800(高)，每像素 4 bit
 *  - 每字节 2 个像素：高 4 位 = 第 1 个（左），低 4 位 = 第 2 个（右）
 *  - 行主序：y 从 0..799，x 从 0..479，每个像素 nibble 值索引调色板
 *
 * 调色板（nibble → RGB，对齐 Relive 源码）：
 *  Spectra6 全彩（6 色）：0 黑 / 1 白 / 2 黄 / 3 红 / 4 无效 / 5 蓝 / 6 绿
 *  GDEM4 四色：          0 黑 / 1 白 / 2 黄 / 3 红（4/5/6 兜底为灰/近似色）
 */
object EInkDecoder {

    // 默认竖版规格
    const val WIDTH = 480
    const val HEIGHT = 800
    const val PIXELS_PER_BYTE = 2

    // Spectra6 全彩 6 色（nibble 0..7，4 为硬件保留位）
    val SPECTRA6: IntArray = intArrayOf(
        0xFF000000.toInt(), // 0 黑
        0xFFFFFFFF.toInt(), // 1 白
        0xFFA49A31.toInt(), // 2 黄 (164,154,49)
        0xFF7E2727.toInt(), // 3 红 (126,39,39)
        0xFF010101.toInt(), // 4 无效占位（接近黑）
        0xFF1F478B.toInt(), // 5 蓝 (31,71,139)
        0xFF364E44.toInt(), // 6 绿 (54,78,68)
        0xFF010101.toInt()  // 7 兜底
    )

    // GDEM075F52 四色（4/5/6 在该硬件不存在，兜底为灰避免花点）
    val GDEM4: IntArray = intArrayOf(
        0xFF000000.toInt(), // 0 黑
        0xFFFFFFFF.toInt(), // 1 白
        0xFFE9BC29.toInt(), // 2 黄 (233,188,41)
        0xFFC42C1D.toInt(), // 3 红 (196,44,29)
        0xFF808080.toInt(), // 4 兜底灰
        0xFF808080.toInt(), // 5 兜底灰
        0xFF808080.toInt(), // 6 兜底灰
        0xFF808080.toInt()  // 7 兜底灰
    )

    /** 按 [palette] 解码 192000 字节流为 480x800 竖版位图。 */
    fun decode(data: ByteArray, palette: IntArray = SPECTRA6): Bitmap {
        val min = WIDTH * HEIGHT / PIXELS_PER_BYTE
        require(data.size >= min) { "display.bin too short: ${data.size} < $min" }
        val pixels = IntArray(WIDTH * HEIGHT)
        var i = 0
        var idx = 0
        while (i < pixels.size) {
            val byte = data[idx++].toInt() and 0xFF
            pixels[i++] = palette[(byte shr 4) and 0x0F]
            pixels[i++] = palette[byte and 0x0F]
        }
        return Bitmap.createBitmap(pixels, WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
    }

    /** 解码并把竖版图顺时针旋转 90°（用于横屏手机显示：480x800 竖 → 800x480 横）。 */
    fun decodeRotated90(data: ByteArray, palette: IntArray = SPECTRA6): Bitmap {
        val base = decode(data, palette)
        val m = Matrix().apply { postRotate(90f) }
        return Bitmap.createBitmap(base, 0, 0, base.width, base.height, m, true).also { base.recycle() }
    }
}
