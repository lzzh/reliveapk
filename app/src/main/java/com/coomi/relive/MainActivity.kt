package com.coomi.relive

import android.content.Context
import android.os.Bundle
import android.view.WindowManager
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

private const val PREFS = "relive_prefs"
private const val KEY_BASE = "server_base"
private const val KEY_API = "api_key"

/** 规范化服务器地址：去空格、补协议、去尾部斜杠。 */
fun normalizeBase(raw: String): String {
    var b = raw.trim()
    if (b.isEmpty()) return b
    if (!b.startsWith("http://") && !b.startsWith("https://")) b = "https://$b"
    while (b.endsWith("/")) b = b.dropLast(1)
    return b
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedBase = prefs.getString(KEY_BASE, "") ?: ""
        val savedKey = prefs.getString(KEY_API, "") ?: ""

        val client = ReliveClient(baseUrl = savedBase, apiKey = savedKey)
        val vm = ReliveViewModel(client)

        setContent {
            MaterialTheme(colors = darkColors()) {
                ReliveScreen(
                    vm = vm,
                    initialBase = savedBase,
                    initialKey = savedKey,
                    firstRun = savedBase.isBlank() || savedKey.isBlank(),
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
}

@Composable
fun ReliveScreen(
    vm: ReliveViewModel,
    initialBase: String,
    initialKey: String,
    firstRun: Boolean,
    onSaveConfig: (String, String) -> Unit
) {
    val context = LocalContext.current

    val photo by vm.photo.collectAsStateWithLifecycle()
    val band by vm.band.collectAsStateWithLifecycle()
    val caption by vm.caption.collectAsStateWithLifecycle()
    val isRefreshing by vm.isRefreshing.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val lastRefreshMs by vm.lastRefreshMs.collectAsStateWithLifecycle()
    val conn by vm.conn.collectAsStateWithLifecycle()

    var controlsVisible by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(firstRun) }
    var pageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { controlsVisible = !controlsVisible }
    ) {
        if (photo != null) {
            // 原图 + 自动排版：横图右白边、竖图下白边
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val wPx = constraints.maxWidth
                val hPx = constraints.maxHeight
                LaunchedEffect(photo, band, wPx, hPx) {
                    val p = photo ?: return@LaunchedEffect
                    pageBitmap = withContext(Dispatchers.Default) {
                        PageComposer.composeFull(p, band, wPx, hPx)
                    }
                }
                val pb = pageBitmap
                if (pb != null) {
                    Image(
                        bitmap = pb,
                        contentDescription = "往年今日照片",
                        contentScale = ContentScale.FillBounds,
                        filterQuality = FilterQuality.Medium,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = Color.White
                    )
                }
            }
        } else {
            Text(
                text = if (firstRun) "尚未配置\n点屏幕 → ⚙ 填写服务器地址与 API Key"
                       else "加载中…",
                color = Color.White,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center)
            )
        }

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
                Text("Relive · 往年今日", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
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
                    Text(error!!, color = Color(0xFFFF5252), fontSize = 12.sp, maxLines = 2)
                } else {
                    Text(
                        text = "$caption · 刷新 ${formatTime(lastRefreshMs)}",
                        color = Color(0xFFB0B0B0),
                        fontSize = 12.sp,
                        maxLines = 1
                    )
                }
            }
        }

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

    val fieldColors = TextFieldDefaults.outlinedTextFieldColors(
        textColor = Color.White,
        cursorColor = Color(0xFF40C4FF),
        focusedBorderColor = Color(0xFF40C4FF),
        unfocusedBorderColor = Color(0xFF808080),
        placeholderColor = Color(0xFF808080),
        focusedLabelColor = Color(0xFF40C4FF),
        unfocusedLabelColor = Color(0xFFB0B0B0)
    )

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = Color(0xFF1E1E22),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .widthIn(max = 600.dp)
                .fillMaxWidth(0.94f)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text("设置", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                // 显示当前 App 版本号，方便确认是否为最新构建
                val appVersion = remember {
                    try {
                        val ctx = LocalContext.current
                        val pkg = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
                        "v${pkg.versionName} (code ${pkg.versionCode})"
                    } catch (_: Throwable) { "unknown" }
                }
                Text(
                    text = "版本 $appVersion",
                    color = Color(0xFF9E9E9E),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Spacer(Modifier.height(16.dp))

                Text("服务器地址", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = base,
                    onValueChange = { base = it },
                    singleLine = true,
                    placeholder = { Text("https://relive.example.com", color = Color(0xFF808080)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))

                Text("API Key", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    singleLine = true,
                    placeholder = { Text("sk-relive-xxxxxxxx", color = Color(0xFF808080)) },
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "在 Relive 后台「设备管理」创建 embedded 设备可获得 API Key",
                    color = Color(0xFF9E9E9E),
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(14.dp))

                Text(
                    text = "将使用：${normalizeBase(base).ifEmpty { "(未填写)" }}",
                    color = Color(0xFF9E9E9E),
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(10.dp))

                when {
                    conn.testing -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color(0xFF40C4FF)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("测试中…", color = Color.White, fontSize = 13.sp)
                    }
                    conn.message != null -> Text(
                        text = (if (conn.ok == true) "✓ " else "✗ ") + conn.message,
                        color = if (conn.ok == true) Color(0xFF4CAF50) else Color(0xFFFF5252),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) { Text("取消", color = Color(0xFFB0B0B0)) }
                    Spacer(Modifier.width(4.dp))
                    TextButton(
                        enabled = !conn.testing,
                        onClick = { onSave(normalizeBase(base), key.trim()) }
                    ) { Text("保存", color = Color(0xFF40C4FF), fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.width(4.dp))
                    TextButton(
                        enabled = !conn.testing,
                        onClick = { onTest(normalizeBase(base), key.trim()) }
                    ) { Text("测试连接", color = Color(0xFF40C4FF)) }
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String =
    if (ms == 0L) "—" else SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

private fun darkColors(): Colors = lightColors().copy(
    primary = Color(0xFF40C4FF),
    onPrimary = Color(0xFF003345),
    background = Color(0xFF000000),
    surface = Color(0xFF1E1E22),
    onSurface = Color.White,
    onBackground = Color.White,
    onSecondary = Color.White,
    onError = Color.White
)
