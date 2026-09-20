package com.coomi.relive

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.Bitmap
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

/** 展示布局模式。 */
enum class LayoutMode {
    /** 相框模式：480×800 墨水屏位图（含文字），方向不一致时旋转铺满。 */
    FRAMED,

    /** 原图铺满模式：直接取原图，居中裁切铺满全屏（无文字，最沉浸）。 */
    FULLBLEED
}

class ReliveViewModel(
    private val client: ReliveClient,
    sampleBytes: ByteArray
) : ViewModel() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _display = MutableStateFlow<ImageBitmap?>(sampleBytes.decodeSafely(false))
    val display: StateFlow<ImageBitmap?> = _display.asStateFlow()

    private val _assetId = MutableStateFlow("")
    val assetId: StateFlow<String> = _assetId.asStateFlow()

    private val _serverTimeSec = MutableStateFlow(0L)
    val serverTimeSec: StateFlow<Long> = _serverTimeSec.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _lastRefreshMs = MutableStateFlow(0L)
    val lastRefreshMs: StateFlow<Long> = _lastRefreshMs.asStateFlow()

    private val _conn = MutableStateFlow(ConnTest())
    val conn: StateFlow<ConnTest> = _conn.asStateFlow()

    /** true = 屏幕鲜艳配色，false = 墨水屏原色。 */
    private val _screenColors = MutableStateFlow(false)
    val screenColors: StateFlow<Boolean> = _screenColors.asStateFlow()

    /** 布局模式。 */
    private val _layoutMode = MutableStateFlow(LayoutMode.FRAMED)
    val layoutMode: StateFlow<LayoutMode> = _layoutMode.asStateFlow()

    /** 横版铺满模式的照片与文字条（由 MainActivity 按屏幕尺寸合成）。 */
    private val _fullBleedPhoto = MutableStateFlow<ImageBitmap?>(null)
    val fullBleedPhoto: StateFlow<ImageBitmap?> = _fullBleedPhoto.asStateFlow()

    private val _fullBleedBand = MutableStateFlow<ImageBitmap?>(null)
    val fullBleedBand: StateFlow<ImageBitmap?> = _fullBleedBand.asStateFlow()

    /** 相框模式最近一次原始字节（切换配色时本地重解码，免重下载）。 */
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
        _display.value = b.decodeSafely(enabled)
    }

    fun setLayoutMode(mode: LayoutMode) {
        if (_layoutMode.value == mode) return
        _layoutMode.value = mode
        refresh()
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

    /** 按当前模式拉取一次。 */
    fun refresh() {
        if (_isRefreshing.value) return
        _isRefreshing.value = true
        _error.value = null
        scope.launch {
            try {
                when (_layoutMode.value) {
                    LayoutMode.FRAMED -> refreshFramed()
                    LayoutMode.FULLBLEED -> refreshFullBleed()
                }
                _lastRefreshMs.value = System.currentTimeMillis()
            } catch (t: Throwable) {
                _error.value = "离线：${t.message}"
                _lastRefreshMs.value = System.currentTimeMillis()
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    private suspend fun refreshFramed() {
        val r = withContext(Dispatchers.IO) { client.fetchDisplayBlocking() }
        lastFrameBytes = r.bytes
        if (!r.unchanged) {
            val bmp = withContext(Dispatchers.IO) { r.bytes.decodeSafely(_screenColors.value) }
            if (bmp != null) _display.value = bmp
        }
        _assetId.value = r.assetId
        _serverTimeSec.value = r.serverTimeSec
    }

    private suspend fun refreshFullBleed() {
        // 一次 JSON：拿到同一张的 photo_id + asset_id（避免序号前进导致图/文不一致）
        val info = withContext(Dispatchers.IO) { client.fetchDeviceDisplayInfo() }
        if (info.photoId <= 0) throw RuntimeException("未取到推荐照片")

        val photoBytes = withContext(Dispatchers.IO) { client.fetchPhotoImageBytes(info.photoId) }
        val photo = withContext(Dispatchers.IO) { photoBytes.decodeDownsampled(2048) }
            ?: throw RuntimeException("原图解码失败")
        _fullBleedPhoto.value = photo

        // 文字条来自同一 asset 的 480×800 相框底部 160px
        val band = try {
            val frameBytes = withContext(Dispatchers.IO) { client.fetchAssetBin(info.assetId) }
            withContext(Dispatchers.IO) {
                EInkDecoder.decodeInfoBand(frameBytes).asImageBitmap()
            }
        } catch (_: Throwable) {
            null
        }
        _fullBleedBand.value = band

        _assetId.value = "photo ${info.photoId}"
        _serverTimeSec.value = 0L
        // 同时给出一个整图兜底（未合成时也能显示）
        _display.value = photo
    }
}

/** 解码内置/网络字节为正向竖版位图（相框模式）。 */
private fun ByteArray.decodeSafely(screenColors: Boolean): ImageBitmap? = try {
    val palette = if (screenColors) EInkDecoder.SPECTRA6_SCREEN else EInkDecoder.SPECTRA6_EINK
    EInkDecoder.decode(this, palette).asImageBitmap()
} catch (_: Throwable) {
    null
}
