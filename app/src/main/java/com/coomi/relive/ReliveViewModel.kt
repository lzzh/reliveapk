package com.coomi.relive

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 设置页"测试连接"的状态。 */
data class ConnTest(
    val testing: Boolean = false,
    val ok: Boolean? = null,
    val message: String? = null
)

/**
 * 状态中枢。取图策略（自动）：
 *  1. `/device/display` 拿推荐照片的 photo_id + asset_id
 *  2. `/photos/{photo_id}/image` 拿**原图**（高清、不裁切、不降色）
 *  3. 同时取 `/display/assets/{asset_id}/bin` 的底部文字条 → 解析出标题 + 日期/地点
 *  4. 由 [PageComposer] 按**照片自身横竖**排版（横图右白边、竖图下白边）
 *
 * 解析文字：从文字条 Bitmap 中提取纯文本（OCR 太贵，改为从 JSON 里没有，
 * 故直接读取文字条并作为位图贴进留白区——保留服务端排版）。
 * 任一步失败 → 回退到服务端 480×800 相框位图。
 */
class ReliveViewModel(
    private val client: ReliveClient
) : ViewModel() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 合成用：原照片（高清）。 */
    private val _photo = MutableStateFlow<ImageBitmap?>(null)
    val photo: StateFlow<ImageBitmap?> = _photo.asStateFlow()

    /** 文字条位图（服务端排版好的文案 + 日期）。 */
    private val _band = MutableStateFlow<ImageBitmap?>(null)
    val band: StateFlow<ImageBitmap?> = _band.asStateFlow()

    /** 附件文案（用于底部信息条）。 */
    private val _caption = MutableStateFlow("")
    val caption: StateFlow<String> = _caption.asStateFlow()

    /** 相框回退位图。 */
    private val _display = MutableStateFlow<ImageBitmap?>(null)
    val display: StateFlow<ImageBitmap?> = _display.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _lastRefreshMs = MutableStateFlow(0L)
    val lastRefreshMs: StateFlow<Long> = _lastRefreshMs.asStateFlow()

    private val _conn = MutableStateFlow(ConnTest())
    val conn: StateFlow<ConnTest> = _conn.asStateFlow()

    init {
        refresh()
        startAutoRefresh()
    }

    private fun startAutoRefresh() {
        scope.launch {
            while (isActive) {
                delay(30 * 60 * 1000L)
                refresh()
            }
        }
    }

    fun testConfig(baseUrl: String, apiKey: String) {
        _conn.value = ConnTest(testing = true)
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                ReliveClient(baseUrl = baseUrl, apiKey = apiKey).testConnection()
            }
            _conn.value = ConnTest(testing = false, ok = res.ok, message = res.message)
        }
    }

    fun resetConnTest() {
        _conn.value = ConnTest()
    }

    fun applyConfig(baseUrl: String, apiKey: String) {
        client.baseUrl = baseUrl
        client.apiKey = apiKey
        refresh()
    }

    fun refresh() {
        if (_isRefreshing.value) return
        _isRefreshing.value = true
        _error.value = null
        scope.launch {
            try {
                refreshComposed()
            } catch (t: Throwable) {
                try {
                    refreshFramed(t)
                } catch (t2: Throwable) {
                    _error.value = "离线：${t2.message}"
                    _photo.value = null
                    _band.value = null
                }
                _lastRefreshMs.value = System.currentTimeMillis()
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    /** 原图 + 文字条（高清，自动排版）。 */
    private suspend fun refreshComposed() {
        val info = withContext(Dispatchers.IO) { client.fetchDeviceDisplayInfo() }
        if (info.photoId <= 0) throw RuntimeException("未取到推荐照片")

        val photoBytes = withContext(Dispatchers.IO) { client.fetchPhotoImageBytes(info.photoId) }
        val photo = withContext(Dispatchers.IO) { photoBytes.decodeDownsampled(1920) }
            ?: throw RuntimeException("原图解码失败")

        val band = try {
            val frameBytes = withContext(Dispatchers.IO) { client.fetchAssetBin(info.assetId) }
            withContext(Dispatchers.IO) {
                EInkDecoder.decodeInfoBand(frameBytes).asImageBitmap()
            }
        } catch (_: Throwable) {
            null
        }

        _photo.value = photo
        _band.value = band
        _caption.value = buildString {
            append("photo ${info.photoId}")
            info.batchDate.takeIf { it.isNotBlank() }?.let { append(" · $it") }
        }
        _display.value = null
        _lastRefreshMs.value = System.currentTimeMillis()
    }

    /** 相框回退（480×800 位图）。 */
    private suspend fun refreshFramed(trigger: Throwable) {
        val r = withContext(Dispatchers.IO) { client.fetchDisplayBlocking() }
        val bmp = withContext(Dispatchers.IO) { r.bytes.decodeFrameSafely() }
        _display.value = bmp
        _photo.value = null
        _band.value = null
        _caption.value = "回退相框（${r.renderProfile}）"
        _error.value = "原图不可用，已回退：${trigger.message}"
    }
}

/** 解码 480×800 相框位图（Spectra6 全彩）。 */
private fun ByteArray.decodeFrameSafely(): ImageBitmap? = try {
    EInkDecoder.decode(this, EInkDecoder.SPECTRA6_EINK).asImageBitmap()
} catch (_: Throwable) {
    null
}
