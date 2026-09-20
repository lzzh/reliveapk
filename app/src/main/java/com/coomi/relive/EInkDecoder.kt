package com.coomi.relive

import android.graphics.Bitmap

/**
 * 把 Relive 设备端 display.bin 的 4-bit 双像素流解码为正的竖版 [Bitmap]。
 *
 * 服务端编码（对齐 Relive 官方源码 display_assets.go / encodeIndexedBinary）：
 *  - 画布是 480宽×800高 竖版
 *  - 写盘前逆时针旋转 90°，按 800列×480行（SW×SH）逐行打包 4-bit
 *    rotateIndexed90CCW: dstX = srcHeight-1-srcY, dstY = srcX  ⇒  landscape[srcX][799-srcY] = portrait(srcX,srcY)
 *  - 每字节 2 像素：高 4 位=左像素，低 4 位=右像素
 *
 * 还原：先按 800列×480行 解出 landscape，再令 portrait(x,y) = landscape[x][SW-1-y]
 */
object EInkDecoder {

    const val STREAM_W = 800   // 流：列数
    const val STREAM_H = 480   // 流：行数
    const val WIDTH = 480      // 还原后竖版宽
    const val HEIGHT = 800     // 还原后竖版高
    const val PIXELS_PER_BYTE = 2

    // Spectra6 全彩 6 色（对齐源码 paletteSpectra6；4 为硬件保留位）
    val SPECTRA6: IntArray = intArrayOf(
        0xFF000000.toInt(), 0xFFFFFFFF.toInt(),
        0xFFA49A31.toInt(), 0xFF7E2727.toInt(),
        0xFF010101.toInt(), 0xFF1F478B.toInt(),
        0xFF364E44.toInt(), 0xFF010101.toInt()
    )

    // GDEM075F52 四色（对齐源码 paletteGDEM075F52；4/5/6 兜底灰）
    val GDEM4: IntArray = intArrayOf(
        0xFF000000.toInt(), 0xFFFFFFFF.toInt(),
        0xFFE9BC29.toInt(), 0xFFC42C1D.toInt(),
        0xFF808080.toInt(), 0xFF808080.toInt(),
        0xFF808080.toInt(), 0xFF808080.toInt()
    )

    /** 解码为正的竖版 480×800 位图。 */
    fun decode(data: ByteArray, palette: IntArray = SPECTRA6): Bitmap {
        val need = STREAM_W * STREAM_H / PIXELS_PER_BYTE
        require(data.size >= need) { "display.bin too short: ${data.size} < $need" }

        // 解出 landscape[row=0..479][col=0..799]
        val landscape = IntArray(STREAM_W * STREAM_H)
        var i = 0
        var idx = 0
        while (i < landscape.size) {
            val b = data[idx++].toInt() and 0xFF
            landscape[i++] = palette[(b shr 4) and 0x0F]
            landscape[i++] = palette[b and 0x0F]
        }

        // portrait(x,y) = landscape[x][STREAM_W-1-y]
        val out = IntArray(WIDTH * HEIGHT)
        for (y in 0 until HEIGHT) {
            val srcCol = STREAM_W - 1 - y
            for (x in 0 until WIDTH) {
                out[y * WIDTH + x] = landscape[x * STREAM_W + srcCol]
            }
        }
        return Bitmap.createBitmap(out, WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
    }
}
