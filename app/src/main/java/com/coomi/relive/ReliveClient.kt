package com.coomi.relive

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Relive 设备端客户端。
 *
 *  - 拉取：GET {baseUrl}/api/v1/device/display.bin，Header `X-API-Key`
 *  - [baseUrl] / [apiKey] 均可在运行时更新（设置页填入）
 *  - [testConnection] 用于设置页"测试连接"
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

    /**
     * 测试服务器地址 + API Key 是否可用。
     * 先用 HEAD（轻量）探测；服务端不支持 HEAD 时回退 GET。
     */
    fun testConnection(): TestResult {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return TestResult(false, "失败：服务器地址为空")
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            return TestResult(false, "失败：地址需以 http:// 或 https:// 开头")
        }
        if (apiKey.trim().isEmpty()) return TestResult(false, "失败：API Key 为空")

        val url = base + DISPLAY_BIN

        // 1) 先试 HEAD
        try {
            val head = Request.Builder()
                .url(url)
                .header("X-API-Key", apiKey.trim())
                .head()
                .build()
            http.newCall(head).execute().use { resp ->
                when {
                    resp.isSuccessful -> {
                        val p = resp.header("X-Render-Profile")
                        return TestResult(true, "连接成功" + if (!p.isNullOrEmpty()) " · 规格 $p" else "")
                    }
                    resp.code == 401 || resp.code == 403 ->
                        return TestResult(false, "失败：API Key 无效（HTTP ${resp.code}）")
                    resp.code == 404 ->
                        return TestResult(false, "失败：接口不存在（HTTP 404），请检查服务器地址")
                    resp.code == 405 || resp.code == 501 || resp.code == 400 -> {
                        // 不支持 HEAD，回退 GET
                    }
                    else ->
                        return TestResult(false, "失败：HTTP ${resp.code} ${resp.message}")
                }
            }
        } catch (_: Throwable) {
            // 忽略，走 GET 兜底
        }

        // 2) GET 兜底
        return try {
            val r = fetchDisplayBlocking()
            TestResult(true, "连接成功 · 规格 ${r.renderProfile} · ${r.bytes.size} 字节")
        } catch (t: Throwable) {
            TestResult(false, "失败：${t.localizedMessage ?: t.message ?: "未知错误"}")
        }
    }

    /** 同步拉一次展示位图。供 [ReliveViewModel] 在 IO 协程里调用。 */
    fun fetchDisplayBlocking(): ReliveDisplay {
        val url = baseUrl.trim().trimEnd('/') + DISPLAY_BIN
        val req = Request.Builder()
            .url(url)
            .header("X-API-Key", apiKey.trim())
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
