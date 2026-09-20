package com.coomi.relive

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Relive 设备端客户端。
 *
 *  - 拉取：GET {baseUrl}/api/v1/device/display.bin，Header `X-API-Key`
 *  - [baseUrl] / [apiKey] 可在运行时更新（设置页填入）
 *  - 请求带 cache-buster（`?_t=...`），绕开 Cloudflare 等 CDN 的 4 小时缓存，
 *    确保"改了规格 / 换了 Key"能立即拿到最新资产
 */
class ReliveClient(
    baseUrl: String = DEFAULT_BASE,
    apiKey: String
) {

    @Volatile
    var baseUrl: String = baseUrl

    @Volatile
    var apiKey: String = apiKey

    data class ReliveDisplay(
        val bytes: ByteArray,
        val assetId: String,
        val serverTimeSec: Long,
        val checksum: String?,
        val renderProfile: String
    )

    /** 连接测试结果。 */
    data class TestResult(val ok: Boolean, val message: String)

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun buildUrl(): String {
        val base = baseUrl.trim().trimEnd('/')
        // 加 cache-buster，避免 CDN 返回旧缓存（关键！Cloudflare 默认缓存 4h）
        return "$base$DISPLAY_BIN?_t=${System.currentTimeMillis()}"
    }

    /**
     * 测试服务器地址 + API Key 是否可用。
     * 用带 cache-buster 的 GET，确保服务端**真正校验** Key 并返回设备位图。
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
                        val bytes = resp.body?.bytes() ?: ByteArray(0)
                        if (profile.isNullOrEmpty()) {
                            TestResult(false, "失败：返回内容不是设备位图（请检查地址）")
                        } else if (bytes.isEmpty()) {
                            TestResult(false, "失败：返回数据为空")
                        } else {
                            TestResult(
                                true,
                                "连接成功 · 规格 $profile · asset ${assetId ?: "-"} · ${bytes.size} 字节"
                            )
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            TestResult(false, "失败：${t.localizedMessage ?: t.message ?: "未知错误"}")
        }
    }

    /** 同步拉一次展示位图（带 cache-buster）。供 [ReliveViewModel] 在 IO 协程里调用。 */
    fun fetchDisplayBlocking(): ReliveDisplay {
        val req = Request.Builder()
            .url(buildUrl())
            .header("X-API-Key", apiKey.trim())
            .header("Cache-Control", "no-cache")
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
