package com.coomi.relive

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Relive 设备端客户端。
 *
 *  - 拉取：GET {baseUrl}/api/v1/device/display.bin，Header `X-API-Key`
 *  - [baseUrl] / [apiKey] 可在运行时更新（设置页填入）
 *  - 请求带 cache-buster（`?_t=...`），绕开 Cloudflare 等 CDN 的 4 小时缓存
 *  - 校验响应：长度必须等于 [EInkDecoder.FRAME_BYTES]；若 `X-Checksum` 与上次相同，
 *    标记 [ReliveDisplay.unchanged] = true，调用方可跳过重绘
 */
class ReliveClient(
    baseUrl: String = DEFAULT_BASE,
    apiKey: String
) {

    @Volatile
    var baseUrl: String = baseUrl

    @Volatile
    var apiKey: String = apiKey

    /** 上一次成功拉取的 checksum，用于"内容未变则跳过重绘"。 */
    @Volatile
    private var lastChecksum: String? = null

    data class ReliveDisplay(
        val bytes: ByteArray,
        val assetId: String,
        val serverTimeSec: Long,
        val checksum: String?,
        val renderProfile: String,
        val unchanged: Boolean
    )

    data class TestResult(val ok: Boolean, val message: String)

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun buildUrl(): String {
        val base = baseUrl.trim().trimEnd('/')
        return "$base$DISPLAY_BIN?_t=${System.currentTimeMillis()}"
    }

    /**
     * 测试服务器地址 + API Key。
     * 用带 cache-buster 的 GET，确保服务端真正校验 Key 并返回设备位图。
     */
    fun testConnection(): TestResult {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return TestResult(false, "失败：服务器地址为空")
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            return TestResult(false, "失败：地址需以 http:// 或 https:// 开头")
        }
        if (apiKey.trim().isEmpty()) return TestResult(false, "失败：API Key 为空")

        return try {
            val req = Request.Builder()
                .url(buildUrl())
                .header("X-API-Key", apiKey.trim())
                .header("Cache-Control", "no-cache")
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                when {
                    resp.code == 401 || resp.code == 403 ->
                        TestResult(false, "失败：API Key 无效（HTTP ${resp.code}）")
                    resp.code == 404 ->
                        TestResult(false, "失败：接口不存在（HTTP 404），请检查服务器地址")
                    !resp.isSuccessful ->
                        TestResult(false, "失败：HTTP ${resp.code} ${resp.message}")
                    else -> {
                        val profile = resp.header("X-Render-Profile")
                        val assetId = resp.header("X-Asset-ID")
                        val len = resp.body?.contentLength() ?: -1L
                        if (profile.isNullOrEmpty()) {
                            TestResult(false, "失败：返回内容不是设备位图（请检查地址）")
                        } else if (len in 0 until EInkDecoder.FRAME_BYTES.toLong()) {
                            TestResult(false, "失败：数据长度异常（$len 字节）")
                        } else {
                            TestResult(
                                true,
                                "连接成功 · 规格 $profile · asset ${assetId ?: "-"} · ${EInkDecoder.FRAME_BYTES} 字节"
                            )
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            TestResult(false, "失败：${t.localizedMessage ?: t.message ?: "未知错误"}")
        }
    }

    /** 同步拉一次展示位图（带 cache-buster + 长度/checksum 校验）。 */
    fun fetchDisplayBlocking(): ReliveDisplay {
        val req = Request.Builder()
            .url(buildUrl())
            .header("X-API-Key", apiKey.trim())
            .header("Cache-Control", "no-cache")
            .get()
            .build()
        return http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("Relive ${resp.code}: ${resp.message}")
            val bytes = resp.body!!.bytes()
            if (bytes.size < EInkDecoder.FRAME_BYTES) {
                throw RuntimeException("数据长度异常：${bytes.size} < ${EInkDecoder.FRAME_BYTES}")
            }
            val checksum = resp.header("X-Checksum")
            val unchanged = checksum != null && checksum == lastChecksum
            if (!unchanged) lastChecksum = checksum
            ReliveDisplay(
                bytes = bytes,
                assetId = resp.header("X-Asset-ID") ?: "",
                serverTimeSec = resp.header("X-Server-Time")?.toLongOrNull() ?: 0L,
                checksum = checksum,
                renderProfile = resp.header("X-Render-Profile") ?: "spectra6_480x800",
                unchanged = unchanged
            )
        }
    }

    companion object {
        const val DEFAULT_BASE = "https://relive.luckyson.online"
        const val DISPLAY_BIN = "/api/v1/device/display.bin"
    }
}
