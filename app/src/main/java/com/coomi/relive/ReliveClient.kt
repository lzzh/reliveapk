package com.coomi.relive

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 极简客户端，调 Relive 设备端展示接口。
 *
 *  拉取：GET {baseUrl}/api/v1/device/display.bin，Header `X-API-Key`
 *  返回：[ReliveDisplay]（4-bit 双像素流 + assetId + 服务端时间）
 */
class ReliveClient(
    private val baseUrl: String = DEFAULT_BASE,
    private val apiKey: String
) {

    data class ReliveDisplay(
        val bytes: ByteArray,
        val assetId: String,
        val serverTimeSec: Long,
        val checksum: String?,
        val renderProfile: String
    )

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 同步拉一次。供 [ReliveViewModel] 在 IO 协程里调用。 */
    fun fetchDisplayBlocking(): ReliveDisplay {
        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + DISPLAY_BIN)
            .header("X-API-Key", apiKey)
            .get()
            .build()
        return http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("Relive ${resp.code}: ${resp.message}")
            ReliveDisplay(
                bytes = resp.body!!.bytes(),
                assetId = resp.header("X-Asset-ID") ?: "",
                serverTimeSec = resp.header("X-Server-Time")?.toLongOrNull() ?: 0L,
                checksum = resp.header("X-Checksum"),
                renderProfile = resp.header("X-Render-Profile") ?: "spectra6_480x800"
            )
        }
    }

    companion object {
        const val DEFAULT_BASE = "https://relive.luckyson.online"
        const val DISPLAY_BIN = "/api/v1/device/display.bin"
    }
}
