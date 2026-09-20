package com.coomi.relive

import android.graphics.Bitmap

/**
 * 把 Relive 设备端 display.bin 的 4-bit 双像素流解码为正的竖版 [Bitmap]。
 *
 * 服务端编码（对齐 Relive 源码 display_assets.go / encodeIndexedBinary）：
 *  - 画布 480宽×800高；写盘前逆时针转 90°，按 800列×480行 逐行打 4-bit
 *  - rotateIndexed90CCW: dstX = srcHeight-1-srcY, dstY = srcX
 *    ⇒ landscape[srcX][SW-1-srcY] = portrait(srcX, srcY)
 *
 * 还原（已用真数据出图验证）：
 *  portrait(x, y) = landscape[x][STREAM_W - 1 - y]
 *
 * 每字节 2 像素：高 4 位 = 左像素，低 4 位 = 右像素。
 */
object EInkDecoder {

    const val STREAM_W = 800   // 流：列数
    const val STREAM_H = 480   // 流：行数
    const val WIDTH = 480      // 还原后竖版宽
    const val HEIGHT = 800     // 还原后竖版高
    const val PIXELS_PER_BYTE = 2

    /** 单帧字节数（4-bit 双像素）。 */
    const val FRAME_BYTES = STREAM_W * STREAM_H / PIXELS_PER_BYTE   // 192000

    /** 相框底部信息区（文字条）高度，与 Relive 源码 displayInfoHeight 一致。 */
    const val INFO_BAND_HEIGHT = 160

    /** 墨水屏原色（暗，贴近真实 Spectra6）。 */
    val SPECTRA6_EINK: IntArray = intArrayOf(
        0xFF000000.toInt(), 0xFFFFFFFF.toInt(),
        0xFFA49A31.toInt(), 0xFF7E2727.toInt(),
        0xFF010101.toInt(), 0xFF1F478B.toInt(),
        0xFF364E44.toInt(), 0xFF010101.toInt()
    )

    /** GDEM075F52 四色（墨水屏原色）。 */
    val GDEM4_EINK: IntArray = intArrayOf(
        0xFF000000.toInt(), 0xFFFFFFFF.toInt(),
        0xFFE9BC29.toInt(), 0xFFC42C1D.toInt(),
        0xFF808080.toInt(), 0xFF808080.toInt(),
        0xFF808080.toInt(), 0xFF808080.toInt()
    )


    /** 安全取色：索引越界（如 nibble 8..15 或调色板缺项）时兜底为黑色，绝不崩溃。 */
    private fun safe(palette: IntArray, index: Int): Int =
        if (index in palette.indices) palette[index] else 0xFF000000.toInt()

    /**
     * 按设备渲染规格名选调色板。
     *
     * 服务端 `BuiltinRenderProfiles()` 里默认设备（`DefaultForDevice`）用的是
     * `gdem075f52_480x800_4color`（四色 bwry4），只有显式选了 Spectra 6 才是六色。
     * 用错调色板会让黄/红明显偏色（164,154,49 vs 233,188,41）。
     */
    fun paletteFor(renderProfile: String): IntArray =
        if (renderProfile.contains("spectra6", ignoreCase = true)) SPECTRA6_EINK else GDEM4_EINK

    /**
     * 只解码相框**底部信息区**（文字条）：480×[INFO_BAND_HEIGHT]，纯白底黑字。
     * 服务端把文案 + 日期渲染在这条里，App 复用它做留白区文字。
     */
    fun decodeInfoBand(data: ByteArray, palette: IntArray = SPECTRA6_EINK): Bitmap {
        require(data.size >= FRAME_BYTES) { "display.bin too short: ${data.size} < $FRAME_BYTES" }

        val landscape = IntArray(STREAM_W * STREAM_H)
        var i = 0
        var idx = 0
        while (i < landscape.size) {
            val b = data[idx++].toInt() and 0xFF
            landscape[i++] = safe(palette, (b shr 4) and 0x0F)
            landscape[i++] = safe(palette, b and 0x0F)
        }

        // 目标 y 区间：[HEIGHT - INFO_BAND_HEIGHT, HEIGHT)
        val y0 = HEIGHT - INFO_BAND_HEIGHT
        val out = IntArray(WIDTH * INFO_BAND_HEIGHT)
        for (y in y0 until HEIGHT) {
            val srcCol = STREAM_W - 1 - y
            val rowBase = (y - y0) * WIDTH
            for (x in 0 until WIDTH) {
                out[rowBase + x] = landscape[x * STREAM_W + srcCol]
            }
        }
        return Bitmap.createBitmap(out, WIDTH, INFO_BAND_HEIGHT, Bitmap.Config.ARGB_8888)
    }

    /**
     * 解码为正的竖版 480×800 位图。
     * @param palette nibble→RGB 调色板（不足 16 项自动兜底，不越界）
     */
    fun decode(data: ByteArray, palette: IntArray = SPECTRA6_EINK): Bitmap {
        require(data.size >= FRAME_BYTES) {
            "display.bin too short: ${data.size} < $FRAME_BYTES"
        }

        // 解出 landscape[row=0..479][col=0..799]
        val landscape = IntArray(STREAM_W * STREAM_H)
        var i = 0
        var idx = 0
        while (i < landscape.size) {
            val b = data[idx++].toInt() and 0xFF
            landscape[i++] = safe(palette, (b shr 4) and 0x0F)
            landscape[i++] = safe(palette, b and 0x0F)
        }

        // portrait(x,y) = landscape[x][STREAM_W-1-y]
        val out = IntArray(WIDTH * HEIGHT)
        for (y in 0 until HEIGHT) {
            val srcCol = STREAM_W - 1 - y
            val rowBase = y * WIDTH
            for (x in 0 until WIDTH) {
                out[rowBase + x] = landscape[x * STREAM_W + srcCol]
            }
        }
        return Bitmap.createBitmap(out, WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
    }
}
