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
 * 状态中枢。
 *
 * 取图策略（自动，无需用户选择）：
 *  1. `/device/display` 拿推荐照片的 photo_id + asset_id
 *  2. `/photos/{photo_id}/image` 拿**原图**（不裁切）
 *  3. `/display/assets/{asset_id}/bin` 拿同源文字条（底部 160px）
 *  → 交由 [PageComposer] 按**照片自身横竖**决定白边位置（横图右、竖图下）
 *
 * 任一步失败 → 回退到相框模式（`/device/display.bin` 的 480×800 位图）。
 */
class ReliveViewModel(
    private val client: ReliveClient,
    sampleBytes: ByteArray
) : ViewModel() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 相框模式兜底位图（也是离线内置图）。 */
    private val _display = MutableStateFlow<ImageBitmap?>(sampleBytes.decodeFrameSafely(false))
    val display: StateFlow<ImageBitmap?> = _display.asStateFlow()

    /** 合成用：原照片。 */
    private val _photo = MutableStateFlow<ImageBitmap?>(null)
    val photo: StateFlow<ImageBitmap?> = _photo.asStateFlow()

    /** 合成用：文字条。 */
    private val _band = MutableStateFlow<ImageBitmap?>(null)
    val band: StateFlow<ImageBitmap?> = _band.asStateFlow()

    private val _assetId = MutableStateFlow("")
    val assetId: StateFlow<String> = _assetId.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _lastRefreshMs = MutableStateFlow(0L)
    val lastRefreshMs: StateFlow<Long> = _lastRefreshMs.asStateFlow()

    private val _conn = MutableStateFlow(ConnTest())
    val conn: StateFlow<ConnTest> = _conn.asStateFlow()

    /** true = 屏幕鲜艳配色，false = 墨水屏原色（仅相框兜底位图用）。 */
    private val _screenColors = MutableStateFlow(false)
    val screenColors: StateFlow<Boolean> = _screenColors.asStateFlow()

    @Volatile
    private var lastFrameBytes: ByteArray? = null

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

    fun setScreenColors(enabled: Boolean) {
        _screenColors.value = enabled
        val b = lastFrameBytes ?: return
        _display.value = b.decodeFrameSafely(enabled)
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
                // 回退：相框模式
                try {
                    refreshFramed()
                    _error.value = "已回退相框模式：${t.message}"
                } catch (t2: Throwable) {
                    _error.value = "离线：${t2.message}"
                }
                _lastRefreshMs.value = System.currentTimeMillis()
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    /** 原图 + 文字条（自动按图片横竖排版）。 */
    private suspend fun refreshComposed() {
        val info = withContext(Dispatchers.IO) { client.fetchDeviceDisplayInfo() }
        if (info.photoId <= 0) throw RuntimeException("未取到推荐照片")

        val photoBytes = withContext(Dispatchers.IO) { client.fetchPhotoImageBytes(info.photoId) }
        val photo = withContext(Dispatchers.IO) { photoBytes.decodeDownsampled(2048) }
            ?: throw RuntimeException("原图解码失败")

        val band = try {
            val frameBytes = withContext(Dispatchers.IO) { client.fetchAssetBin(info.assetId) }
            lastFrameBytes = frameBytes
            withContext(Dispatchers.IO) {
                EInkDecoder.decodeInfoBand(frameBytes).asImageBitmap()
            }
        } catch (_: Throwable) {
            null
        }

        _photo.value = photo
        _band.value = band
        _assetId.value = "photo ${info.photoId}"
        _lastRefreshMs.value = System.currentTimeMillis()
    }

    /** 相框模式（480×800 位图）。 */
    private suspend fun refreshFramed() {
        val r = withContext(Dispatchers.IO) { client.fetchDisplayBlocking() }
        lastFrameBytes = r.bytes
        if (!r.unchanged) {
            val bmp = withContext(Dispatchers.IO) { r.bytes.decodeFrameSafely(_screenColors.value) }
            if (bmp != null) _display.value = bmp
        }
        _photo.value = null
        _band.value = null
        _assetId.value = r.assetId
        _lastRefreshMs.value = System.currentTimeMillis()
    }
}

/** 解码 480×800 相框位图。 */
private fun ByteArray.decodeFrameSafely(screenColors: Boolean): ImageBitmap? = try {
    val palette = if (screenColors) EInkDecoder.SPECTRA6_SCREEN else EInkDecoder.SPECTRA6_EINK
    EInkDecoder.decode(this, palette).asImageBitmap()
} catch (_: Throwable) {
    null
}
