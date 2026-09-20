package com.coomi.relive

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.Bitmap
import android.graphics.Matrix

/** 顺时针旋转 90°，用于让图片方向跟随屏幕方向。 */
fun ImageBitmap.rotate90(): ImageBitmap {
    val src = this.asAndroidBitmap()
    val m = Matrix().apply { postRotate(90f) }
    val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    return out.asImageBitmap()
}
