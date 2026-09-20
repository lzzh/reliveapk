package com.coomi.relive

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
 *  2. `/photos/{photo_id}/image` 拿**原图**（高清、不裁切、不降色，按 EXIF 转正）
 *  3. 同时取 `/display/assets/{asset_id}/bin` 的底部文字条 → 复用服务端排版好的文案/日期
 *  4. 由 [PageComposer] 合成：照片铺满上方 + 底部窄文字条（高度可在设置里调）
 *
 * 任一步失败 → 回退到服务端 480×800 相框位图（按设备规格选调色板）。
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

    /** ViewModel 释放时停掉自动刷新协程，避免作用域泄漏。 */
    override fun onCleared() {
        super.onCleared()
        scope.cancel()
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
                    _display.value = null
                }
                _lastRefreshMs.value = System.currentTimeMillis()
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    /** 原图 + 文字条（高清、自动排版）。 */
    private suspend fun refreshComposed() {
        val info = withContext(Dispatchers.IO) { client.fetchDeviceDisplayInfo() }
        if (info.photoId <= 0) throw RuntimeException("未取到推荐照片")

        val photoBytes = withContext(Dispatchers.IO) { client.fetchPhotoImageBytes(info.photoId) }
        val photo = withContext(Dispatchers.IO) { photoBytes.decodeDownsampled(1920) }
            ?: throw RuntimeException("原图解码失败")

        val band = try {
            val frameBytes = withContext(Dispatchers.IO) { client.fetchAssetBin(info.assetId) }
            withContext(Dispatchers.IO) {
                // 文字条是纯黑白区域，调色板用哪种都不影响；仍按规格选，保持一致。
                val palette = EInkDecoder.paletteFor(info.renderProfile)
                EInkDecoder.decodeInfoBand(frameBytes, palette).asImageBitmap()
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
        _error.value = null
        _lastRefreshMs.value = System.currentTimeMillis()
    }

    /**
     * 相框回退（服务端渲染好的 480×800 位图，含照片 + 文字，方向已由服务端校正）。
     * 回退**成功**不算错误：只在底部信息条显示提示，不点亮"离线"红色告警。
     */
    private suspend fun refreshFramed(trigger: Throwable) {
        val r = withContext(Dispatchers.IO) { client.fetchDisplayBlocking() }
        val palette = EInkDecoder.paletteFor(r.renderProfile)
        val bmp = withContext(Dispatchers.IO) { r.bytes.decodeFrameSafely(palette) }
            ?: throw RuntimeException("相框位图解码失败")
        _display.value = bmp
        _photo.value = null
        _band.value = null
        _caption.value = "回退相框（${r.renderProfile}）· 原图不可用：${trigger.message}"
        _error.value = null
    }
}

/** 解码 480×800 相框位图（调色板按设备渲染规格选择）。 */
private fun ByteArray.decodeFrameSafely(palette: IntArray): ImageBitmap? = try {
    EInkDecoder.decode(this, palette).asImageBitmap()
} catch (_: Throwable) {
    null
}
