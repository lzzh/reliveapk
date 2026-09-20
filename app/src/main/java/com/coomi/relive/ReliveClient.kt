package com.coomi.relive

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Relive 客户端。两条取图路径：
 *
 *  1) **相框模式**：GET /api/v1/device/display.bin（480×800 4-bit 墨水屏位图，含推荐照片+文字）
 *  2) **原图铺满模式**：
 *       - GET /api/v1/device/display         → JSON，取当前推荐的 photo_id / asset_id
 *       - GET /api/v1/photos/{id}/image      → 原始照片（未裁剪，可铺满屏幕）
 *
 *  两个接口都支持 `X-API-Key`（设备 Key）。
 */
class ReliveClient(
    baseUrl: String = DEFAULT_BASE,
    apiKey: String
) {

    @Volatile
    var baseUrl: String = baseUrl

    @Volatile
    var apiKey: String = apiKey

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

    /** 设备端 JSON：当前推荐的照片/资产信息。 */
    data class DeviceDisplayInfo(
        val photoId: Long,
        val assetId: Long,
        val renderProfile: String,
        val sequence: Int,
        val totalCount: Int,
        val batchDate: String
    )

    data class TestResult(val ok: Boolean, val message: String)

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun base(): String = normalizeBase(baseUrl.ifBlank { DEFAULT_BASE })

    private fun stamp(): Long = System.currentTimeMillis()

    /** 把底层网络异常转成中文可读提示。 */
    private fun humanError(t: Throwable): String {
        val m = (t.localizedMessage ?: t.message ?: "").lowercase()
        return when {
            m.contains("unable to resolve host") || m.contains("no address associated") ->
                "无法解析域名，请检查服务器地址"
            m.contains("failed to connect") || m.contains("connection refused") ->
                "无法连接服务器，请检查地址与网络"
            m.contains("timeout") || m.contains("timed out") -> "连接超时，请检查网络"
            m.contains("ssl") || m.contains("certificate") -> "HTTPS 证书错误（可尝试 http://）"
            m.contains("cleartext") -> "明文 HTTP 被系统拦截"
            else -> t.localizedMessage ?: t.message ?: "未知错误"
        }
    }

    // ---------------- 相框模式（display.bin） ----------------

    private fun displayBinUrl(): String = "${base()}$DISPLAY_BIN?_t=${stamp()}"

    /** 同步拉一次 480×800 墨水屏位图。 */
    fun fetchDisplayBlocking(): ReliveDisplay {
        val req = Request.Builder()
            .url(displayBinUrl())
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

    // ---------------- 原图铺满模式 ----------------

    /** 取当前推荐照片的元信息（photo_id 等）。 */
    fun fetchDeviceDisplayInfo(): DeviceDisplayInfo {
        val req = Request.Builder()
            .url("${base()}$DEVICE_DISPLAY?_t=${stamp()}")
            .header("X-API-Key", apiKey.trim())
            .header("Cache-Control", "no-cache")
            .get()
            .build()
        return http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("Relive ${resp.code}: ${resp.message}")
            val body = resp.body!!.string()
            val root = JSONObject(body)
            val data = root.optJSONObject("data")
                ?: throw RuntimeException("返回缺少 data 字段")
            DeviceDisplayInfo(
                photoId = data.optLong("photo_id", -1L),
                assetId = data.optLong("asset_id", -1L),
                renderProfile = data.optString("render_profile", ""),
                sequence = data.optInt("sequence", 0),
                totalCount = data.optInt("total_count", 0),
                batchDate = data.optString("batch_date", "")
            )
        }
    }

    /** 取原始照片字节（未裁剪）。 */
    fun fetchPhotoImageBytes(photoId: Long): ByteArray {
        val req = Request.Builder()
            .url("${base()}/api/v1/photos/$photoId/image?_t=${stamp()}")
            .header("X-API-Key", apiKey.trim())
            .header("Cache-Control", "no-cache")
            .get()
            .build()
        return http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("Relive ${resp.code}: ${resp.message}")
            resp.body!!.bytes()
        }
    }

    /**
     * 按 asset_id 取该「展示资产」的 480×800 4-bit 位图。
     * 用 asset_id 而非 /device/display.bin：后者每次调用会让推荐序号前进，
     * 导致照片与文字条不是同一张。
     */
    fun fetchAssetBin(assetId: Long): ByteArray {
        val req = Request.Builder()
            .url("${base()}/api/v1/display/assets/$assetId/bin?_t=${stamp()}")
            .header("X-API-Key", apiKey.trim())
            .header("Cache-Control", "no-cache")
            .get()
            .build()
        return http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("Relive ${resp.code}: ${resp.message}")
            resp.body!!.bytes()
        }
    }

    // ---------------- 连接测试 ----------------

    /**
     * 测试服务器地址 + API Key（用带 cache-buster 的 GET，确保服务端真正校验 Key）。
     */
    fun testConnection(): TestResult {
        val b = base()
        if (b.isEmpty()) return TestResult(false, "服务器地址为空")
        if (b == "https://" || b == "http://") return TestResult(false, "服务器地址不完整")
        if (apiKey.trim().isEmpty()) return TestResult(false, "API Key 为空")

        return try {
            val req = Request.Builder()
                .url(displayBinUrl())
                .header("X-API-Key", apiKey.trim())
                .header("Cache-Control", "no-cache")
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                when {
                    resp.code == 401 || resp.code == 403 -> TestResult(false, "API Key 无效（HTTP ${resp.code}）")
                    resp.code == 404 -> TestResult(false, "接口不存在（HTTP 404），请检查服务器地址")
                    !resp.isSuccessful -> TestResult(false, "HTTP ${resp.code} ${resp.message}")
                    else -> {
                        val profile = resp.header("X-Render-Profile")
                        val assetId = resp.header("X-Asset-ID")
                        val len = resp.body?.contentLength() ?: -1L
                        if (profile.isNullOrEmpty()) {
                            TestResult(false, "返回的不是设备位图，请检查地址")
                        } else if (len in 0 until EInkDecoder.FRAME_BYTES.toLong()) {
                            TestResult(false, "数据长度异常（$len 字节）")
                        } else {
                            TestResult(true, "连接成功 · 规格 $profile · asset ${assetId ?: "-"}")
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            TestResult(false, humanError(t))
        }
    }

    companion object {
        /** 通用版：不预设任何服务器地址，由用户在设置页填写。 */
        const val DEFAULT_BASE = ""
        const val DISPLAY_BIN = "/api/v1/device/display.bin"
        const val DEVICE_DISPLAY = "/api/v1/device/display"
    }
}
