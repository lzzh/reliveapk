package com.coomi.relive

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Relive 客户端。两条取图路径：
 *
 *  1) **相框模式（回退用）**：GET /api/v1/device/display.bin（480×800 4-bit 墨水屏位图，含推荐照片+文字）
 *  2) **原图铺满模式（主路径）**：
 *       - GET /api/v1/device/display         → JSON，取当前推荐的 photo_id / asset_id
 *       - GET /api/v1/photos/{id}/image      → 原始照片（未裁剪，可铺满屏幕）
 *
 *  ⚠ 注意：`/device/display` 与 `/device/display.bin` 在后端**每次调用都会推进轮播序号**
 *  （display_daily_service.go 里 `current_sequence + 1`），所以一次刷新只能调用其中一个，
 *  再靠返回的 asset_id 去取同一张的文字条。
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
            if (bytes.size != EInkDecoder.FRAME_BYTES) {
                throw RuntimeException("数据长度异常：${bytes.size} ≠ ${EInkDecoder.FRAME_BYTES}")
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
     * 测试服务器地址 + API Key。
     *
     * **不推进轮播序列**：后端 `GetDeviceDisplay` / `GetDeviceDisplayBin` 每次调用都会把
     * 设备的 `current_sequence` 前进一格（见 display_daily_service.go），所以不能拿
     * `display.bin` 当探针，否则每点一次"测试连接"就白跳一张照片。
     *
     * 改为两步（都只读）：
     *  1. `GET /api/v1/system/health`（公开）→ 确认地址确实是 Relive 服务器
     *  2. `GET /api/v1/photos/{不存在的id}/image` 带 X-API-Key → 走 PhotoAuth 校验 Key：
     *     401=Key 无效/设备禁用；404=Key 有效（鉴权已通过，只是照片不存在）
     */
    fun testConnection(): TestResult {
        val b = base()
        if (b.isEmpty()) return TestResult(false, "服务器地址为空")
        if (b == "https://" || b == "http://") return TestResult(false, "服务器地址不完整")
        if (apiKey.trim().isEmpty()) return TestResult(false, "API Key 为空")

        // 1) 用公开健康检查确认地址确实指向 Relive（只读，不改动任何状态）
        var version = ""
        try {
            val healthReq = Request.Builder()
                .url("$b/api/v1/system/health?_t=${stamp()}")
                .header("Cache-Control", "no-cache")
                .get()
                .build()
            http.newCall(healthReq).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return TestResult(false, "无法访问 $b/api/v1/system/health（HTTP ${resp.code}），请检查服务器地址")
                }
                val root = try {
                    JSONObject(resp.body?.string() ?: "")
                } catch (_: Throwable) {
                    null
                }
                if (root == null || !root.optBoolean("success", false)) {
                    return TestResult(false, "该地址返回的内容不像是 Relive 服务")
                }
                version = root.optJSONObject("data")?.optString("version", "") ?: ""
            }
        } catch (t: Throwable) {
            return TestResult(false, humanError(t))
        }

        // 2) 用 PhotoAuth 保护的只读接口校验 API Key：
        //    401 = Key 无效/设备禁用；404 = 鉴权已通过（照片不存在而已）→ Key 有效
        return try {
            val probeReq = Request.Builder()
                .url("$b/api/v1/photos/999999999/image?_t=${stamp()}")
                .header("X-API-Key", apiKey.trim())
                .header("Cache-Control", "no-cache")
                .get()
                .build()
            val code = http.newCall(probeReq).execute().use { resp ->
                resp.body?.close()
                resp.code
            }
            val v = if (version.isNotBlank()) " · Relive $version" else ""
            when {
                code == 401 -> TestResult(false, "API Key 无效或设备已禁用（HTTP 401）")
                code == 403 -> TestResult(false, "设备已禁用（HTTP 403）")
                code in 200..299 || code == 404 -> TestResult(true, "连接成功：地址与 API Key 均有效$v")
                else -> TestResult(false, "HTTP $code")
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
