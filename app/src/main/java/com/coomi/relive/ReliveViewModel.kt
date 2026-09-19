package com.coomi.relive

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationCallback
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.initializer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayInputStream

/**
 * App 状态中枢：
 *  - [display] 当前要显示的 4-bit 解码位图（主线程 UI 直接读）
 *  - [assetId] / [serverTimeSec] 响应头里的元数据
 *  - [isRefreshing] 网络正在拉
 *  - [error] 最近一次失败信息（null = 成功或无操作）
 *
 *  首次进入：尝试拉一次；失败就切到 assets/sample/display.bin 离线兜底图。
 *  点刷新：重新拉，成功覆盖，失败保留旧图并在 error 里提示。
 */
class ReliveViewModel(
    private val client: ReliveClient,
    private val sampleBytes: ByteArray
) : ViewModel() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _display = MutableStateFlow<ImageBitmap?>(null)
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
        // 先塞离线兜底图，保证首帧有东西看
        _display.value = sampleBytes.decodeSafely()
        // 后台拉一次
        refresh()
    }

    /** 拉一次网络；失败时保留旧图并在 error 标记。 */
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

// —— 辅助 ——

private fun EInkDecoder.decodeToBitmap(bytes: ByteArray): Bitmap = decode(bytes)

private fun Bitmap.asImageBitmap(): androidx.compose.ui.graphics.ImageBitmap =
    androidx.compose.ui.graphics.asImageBitmap()

private fun ByteArray.decodeSafely(): androidx.compose.ui.graphics.ImageBitmap? = try {
    EInkDecoder.decode(this).asImageBitmap()
} catch (_: Throwable) { null }

/** 工厂：把 sample bytes 注入 ViewModel。 */
object ReliveViewModelFactory : ViewModelProvider.Factory {
    fun create(
        client: ReliveClient,
        sample: ByteArray
    ): ReliveViewModel = ReliveViewModel(client, sample)
}
