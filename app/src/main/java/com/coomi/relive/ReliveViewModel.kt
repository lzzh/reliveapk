package com.coomi.relive

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    init {
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
                val bmp = withContext(Dispatchers.IO) { EInkDecoder.decode(r.bytes) }
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

private fun ByteArray.decodeSafely(): ImageBitmap? = try {
    EInkDecoder.decode(this).asImageBitmap()
} catch (_: Throwable) {
    null
}
