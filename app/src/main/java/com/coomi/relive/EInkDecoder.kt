package com.coomi.relive

import android.graphics.Bitmap

/**
 * 把 Relive 设备端 display.bin 的 4-bit 双像素流解码为 [Bitmap]。
 *
 * 布局（与 ESP32 墨水屏 README 完全一致）：
 *  - 800 × 480，每像素 4 bit
 *  - 每字节 2 个像素：高 4 位 = 第 1 个（左），低 4 位 = 第 2 个（右）
 *  - 行主序，x 从 0..799，y 从 0..479
 *
 * 调色板（E Ink Spectra 6，6 色）：
 *  0 黑 / 1 白 / 2 黄 / 3 红 / 5 蓝 / 6 绿
 *  4、7 未定义，按灰 0x808080 兜底
 */
object EInkDecoder {

    const val WIDTH = 800
    const val HEIGHT = 480
    const val PIXELS_PER_BYTE = 2

    private val PALETTE: Array<Int> = intArrayOf(
        0xFF000000.toInt(), // 0x0 黑
        0xFFFFFFFF.toInt(), // 0x1 白
        0xFFFFFF00.toInt(), // 0x2 黄
        0xFFFF0000.toInt(), // 0x3 红
        0xFF808080.toInt(), // 0x4 兜底
        0xFF0000FF.toInt(), // 0x5 蓝
        0xFF00C800.toInt(), // 0x6 绿
        0xFF808080.toInt()  // 0x7 兜底
    )

    fun decode(data: ByteArray): Bitmap {
        val min = WIDTH * HEIGHT / PIXELS_PER_BYTE
        require(data.size >= min) { "display.bin too short: ${data.size} < $min" }

        val pixels = IntArray(WIDTH * HEIGHT)
        var i = 0
        var idx = 0
        while (i < pixels.size) {
            val byte = data[idx++].toInt() and 0xFF
            pixels[i++] = PALETTE[(byte shr 4) and 0x0F]
            pixels[i++] = PALETTE[byte and 0x0F]
        }
        return Bitmap.createBitmap(pixels, WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
    }
}
