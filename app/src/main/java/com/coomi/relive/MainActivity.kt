package com.coomi.relive

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.*

private const val PREFS = "relive_prefs"
private const val KEY_BASE = "server_base"
private const val KEY_API = "api_key"
const val DEFAULT_API_KEY = "sk-relive-REDACTED-1"

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 全屏沉浸
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedBase = prefs.getString(KEY_BASE, ReliveClient.DEFAULT_BASE) ?: ReliveClient.DEFAULT_BASE
        val savedKey = prefs.getString(KEY_API, DEFAULT_API_KEY) ?: DEFAULT_API_KEY

        val client = ReliveClient(baseUrl = savedBase, apiKey = savedKey)
        val sample = loadSample()
        val vm = ReliveViewModel(client, sample)

        setContent {
            MaterialTheme(colors = darkColors()) {
                ReliveScreen(
                    vm = vm,
                    initialBase = savedBase,
                    initialKey = savedKey,
                    onSaveConfig = { base, key ->
                        prefs.edit()
                            .putString(KEY_BASE, base)
                            .putString(KEY_API, key)
                            .apply()
                        vm.applyConfig(base, key)
                    }
                )
            }
        }
    }

    private fun loadSample(): ByteArray = try {
        assets.open("sample/display.bin").use { it.readBytes() }
    } catch (_: Exception) {
        ByteArray(0)
    }
}

@Composable
fun ReliveScreen(
    vm: ReliveViewModel,
    initialBase: String,
    initialKey: String,
    onSaveConfig: (String, String) -> Unit
) {
    val context = LocalContext.current

    val display by vm.display.collectAsStateWithLifecycle()
    val assetId by vm.assetId.collectAsStateWithLifecycle()
    val isRefreshing by vm.isRefreshing.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val lastRefreshMs by vm.lastRefreshMs.collectAsStateWithLifecycle()
    val conn by vm.conn.collectAsStateWithLifecycle()

    var controlsVisible by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    // 设备方向：横屏 / 竖屏
    val configuration = LocalConfiguration.current
    val deviceLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // 方向不一致则旋转 90°，让图片跟随屏幕方向铺满
    val oriented: ImageBitmap? = remember(display, deviceLandscape) {
        val d = display ?: return@remember null
        val imageLandscape = d.width > d.height
        if (deviceLandscape != imageLandscape) d.rotate90() else d
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { controlsVisible = !controlsVisible }
    ) {
        if (oriented != null) {
            Image(
                bitmap = oriented!!,
                contentDescription = "往年今日照片",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = "无数据",
                color = Color.Gray,
                fontSize = 14.sp,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // 顶部栏（控件可见时）
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x88000000))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Relive · 往年今日",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    IconButton(onClick = {
                        vm.resetConnTest()
                        showSettings = true
                    }) {
                        Icon(Icons.Default.Settings, contentDescription = "设置", tint = Color.White)
                    }
                }
            }
        }

        // 底部信息条（控件可见时）
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x88000000))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (error != null) {
                    Icon(
                        Icons.Default.WifiOff,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = Color(0xFFFF5252)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("离线，显示内置图", color = Color(0xFFFF5252), fontSize = 12.sp)
                } else {
                    Text(
                        text = "asset $assetId · ${if (deviceLandscape) "横屏" else "竖屏"} · 刷新 ${formatTime(lastRefreshMs)}",
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                }
            }
        }

        // 刷新按钮（控件可见时，右下）
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp)
        ) {
            FloatingActionButton(
                onClick = { vm.refresh() },
                backgroundColor = MaterialTheme.colors.primary,
                contentColor = MaterialTheme.colors.onPrimary
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "刷新")
            }
        }
    }

    if (showSettings) {
        SettingsDialog(
            currentBase = initialBase,
            currentKey = initialKey,
            conn = conn,
            onTest = { base, key -> vm.testConfig(base, key) },
            onSave = { base, key ->
                onSaveConfig(base, key)
                showSettings = false
                Toast.makeText(context, "已保存并刷新", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showSettings = false }
        )
    }
}

@Composable
private fun SettingsDialog(
    currentBase: String,
    currentKey: String,
    conn: ConnTest,
    onTest: (String, String) -> Unit,
    onSave: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var base by remember { mutableStateOf(currentBase) }
    var key by remember { mutableStateOf(currentKey) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置") },
        text = {
            Column {
                Text("服务器地址", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = base,
                    onValueChange = { base = it },
                    singleLine = true,
                    placeholder = { Text("https://relive.example.com") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(12.dp))

                Text("API Key", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    singleLine = true,
                    placeholder = { Text("sk-relive-xxxxxxxx") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(12.dp))

                when {
                    conn.testing -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("测试中…", fontSize = 13.sp)
                    }
                    conn.message != null -> Text(
                        text = (if (conn.ok == true) "✓ " else "✗ ") + conn.message,
                        color = if (conn.ok == true) Color(0xFF2E7D32) else Color(0xFFC62828),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !conn.testing,
                onClick = { onTest(base.trim(), key.trim()) }
            ) { Text("测试连接") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text("取消") }
                TextButton(
                    enabled = !conn.testing,
                    onClick = { onSave(base.trim(), key.trim()) }
                ) { Text("保存") }
            }
        }
    )
}

private fun formatTime(ms: Long): String =
    if (ms == 0L) "—" else SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

private fun darkColors(): Colors = lightColors().copy(
    primary = Color(0xFF40C4FF),
    onPrimary = Color(0xFF003345),
    background = Color(0xFF000000),
    surface = Color(0xFF000000),
    onSurface = Color.White
)
