package com.coomi.relive

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 设置页"测试连接"的状态。 */
data class ConnTest(
    val testing: Boolean = false,
    val ok: Boolean? = null,
    val message: String? = null
)

class ReliveViewModel(
    private val client: ReliveClient,
    sampleBytes: ByteArray
) : ViewModel() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _display = MutableStateFlow<ImageBitmap?>(sampleBytes.decodeSafely())
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

    init {
        refresh()
    }

    /** 设置页：测试给定地址 + Key（用临时客户端，不影响当前配置）。 */
    fun testConfig(baseUrl: String, apiKey: String) {
        _conn.value = ConnTest(testing = true)
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                ReliveClient(baseUrl = baseUrl, apiKey = apiKey).testConnection()
            }
            _conn.value = ConnTest(testing = false, ok = res.ok, message = res.message)
        }
    }

    /** 清空测试状态（打开设置页时）。 */
    fun resetConnTest() {
        _conn.value = ConnTest()
    }

    /** 应用新配置并立即重新拉取。 */
    fun applyConfig(baseUrl: String, apiKey: String) {
        client.baseUrl = baseUrl
        client.apiKey = apiKey
        refresh()
    }

    /** 拉一次网络；失败保留旧图并在 error 标记。 */
    fun refresh() {
        if (_isRefreshing.value) return
        _isRefreshing.value = true
        _error.value = null
        scope.launch {
            try {
                val r = withContext(Dispatchers.IO) { client.fetchDisplayBlocking() }
                val bmp = withContext(Dispatchers.IO) { EInkDecoder.decode(r.bytes, EInkDecoder.SPECTRA6) }
                _display.value = bmp.asImageBitmap()
                _assetId.value = r.assetId
                _serverTimeSec.value = r.serverTimeSec
                _lastRefreshMs.value = System.currentTimeMillis()
            } catch (t: Throwable) {
                _error.value = "离线：${t.message}"
                _lastRefreshMs.value = System.currentTimeMillis()
            } finally {
                _isRefreshing.value = false
            }
        }
    }
}

/** 离线兜底：解码内置 display.bin（Spectra6 全彩，还原为正的竖版 480×800）。 */
private fun ByteArray.decodeSafely(): ImageBitmap? = try {
    EInkDecoder.decode(this, EInkDecoder.SPECTRA6).asImageBitmap()
} catch (_: Throwable) {
    null
}
