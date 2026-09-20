package com.coomi.relive

import android.content.Context
import android.content.res.Configuration
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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
private const val KEY_SCREEN_COLORS = "screen_colors"
private const val KEY_LAYOUT = "layout_mode"

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
        val savedBase = prefs.getString(KEY_BASE, ReliveClient.DEFAULT_BASE) ?: ReliveClient.DEFAULT_BASE
        val savedKey = prefs.getString(KEY_API, "") ?: ""
        val savedScreenColors = prefs.getBoolean(KEY_SCREEN_COLORS, false)
        val savedLayout = runCatching {
            LayoutMode.valueOf(prefs.getString(KEY_LAYOUT, LayoutMode.FRAMED.name)!!)
        }.getOrDefault(LayoutMode.FRAMED)

        val client = ReliveClient(baseUrl = savedBase, apiKey = savedKey)
        val sample = loadSample()
        val vm = ReliveViewModel(client, sample)
        vm.setScreenColors(savedScreenColors)
        vm.setLayoutMode(savedLayout)

        setContent {
            MaterialTheme(colors = darkColors()) {
                ReliveScreen(
                    vm = vm,
                    initialBase = savedBase,
                    initialKey = savedKey,
                    initialScreenColors = savedScreenColors,
                    initialLayout = savedLayout,
                    firstRun = savedKey.isBlank(),
                    onSaveConfig = { base, key, screenColors, layout ->
                        prefs.edit()
                            .putString(KEY_BASE, base)
                            .putString(KEY_API, key)
                            .putBoolean(KEY_SCREEN_COLORS, screenColors)
                            .putString(KEY_LAYOUT, layout.name)
                            .apply()
                        vm.applyConfig(base, key)
                        vm.setScreenColors(screenColors)
                        vm.setLayoutMode(layout)
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
    initialScreenColors: Boolean,
    initialLayout: LayoutMode,
    firstRun: Boolean,
    onSaveConfig: (String, String, Boolean, LayoutMode) -> Unit
) {
    val context = LocalContext.current

    val display by vm.display.collectAsStateWithLifecycle()
    val assetId by vm.assetId.collectAsStateWithLifecycle()
    val isRefreshing by vm.isRefreshing.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val lastRefreshMs by vm.lastRefreshMs.collectAsStateWithLifecycle()
    val conn by vm.conn.collectAsStateWithLifecycle()
    val screenColors by vm.screenColors.collectAsStateWithLifecycle()
    val layoutMode by vm.layoutMode.collectAsStateWithLifecycle()

    var controlsVisible by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(firstRun) }

    val configuration = LocalConfiguration.current
    val deviceLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val fullBleedPhoto by vm.fullBleedPhoto.collectAsStateWithLifecycle()
    val fullBleedBand by vm.fullBleedBand.collectAsStateWithLifecycle()

    // 相框模式：图含文字，方向不一致时旋转 90° 以保证文字可读
    // 原图模式：由 PageComposer 按屏幕尺寸排版（照片 + 留白文字条）
    val oriented: ImageBitmap? = remember(display, deviceLandscape, layoutMode) {
        val d = display ?: return@remember null
        if (layoutMode == LayoutMode.FRAMED) {
            val imageLandscape = d.width > d.height
            if (deviceLandscape != imageLandscape) d.rotate90() else d
        } else {
            d
        }
    }

    // 横版铺满模式：按容器实际像素尺寸合成
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
        if (layoutMode == LayoutMode.FULLBLEED && fullBleedPhoto != null) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val wPx = constraints.maxWidth
                val hPx = constraints.maxHeight
                LaunchedEffect(fullBleedPhoto, fullBleedBand, wPx, hPx) {
                    pageBitmap = withContext(Dispatchers.Default) {
                        PageComposer.compose(fullBleedPhoto!!, fullBleedBand, wPx, hPx)
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
        } else if (oriented != null) {
            Image(
                bitmap = oriented!!,
                contentDescription = "往年今日照片",
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.None,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = "无数据\n点屏幕 → ⚙ 设置 API Key",
                color = Color.White,
                fontSize = 14.sp,
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
                    val modeText = if (layoutMode == LayoutMode.FRAMED) "相框竖版" else "原图铺满"
                    Text(
                        text = "$modeText · $assetId · ${if (deviceLandscape) "横屏" else "竖屏"} · 刷新 ${formatTime(lastRefreshMs)}",
                        color = Color(0xFFB0B0B0),
                        fontSize = 12.sp
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
            currentScreenColors = screenColors,
            currentLayout = layoutMode,
            conn = conn,
            onTest = { base, key -> vm.testConfig(base, key) },
            onSave = { base, key, sc, layout ->
                onSaveConfig(base, key, sc, layout)
                showSettings = false
                Toast.makeText(context, "已保存并刷新", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showSettings = false }
        )
    }
}

/**
 * 自定义设置对话框（不用 AlertDialog，避免深色主题下文字不可见）。
 * 所有文字显式指定颜色。
 */
@Composable
private fun SettingsDialog(
    currentBase: String,
    currentKey: String,
    currentScreenColors: Boolean,
    currentLayout: LayoutMode,
    conn: ConnTest,
    onTest: (String, String) -> Unit,
    onSave: (String, String, Boolean, LayoutMode) -> Unit,
    onDismiss: () -> Unit
) {
    var base by remember { mutableStateOf(currentBase) }
    var key by remember { mutableStateOf(currentKey) }
    var screenColors by remember { mutableStateOf(currentScreenColors) }
    var layout by remember { mutableStateOf(currentLayout) }

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

                Spacer(Modifier.height(16.dp))

                Text("服务器地址", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = base,
                    onValueChange = { base = it },
                    singleLine = true,
                    placeholder = { Text("relive.example.com", color = Color(0xFF808080)) },
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

                Spacer(Modifier.height(18.dp))

                Text("展示布局", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                LayoutOption(
                    label = "相框竖版（含日期/文案，480×800）",
                    selected = layout == LayoutMode.FRAMED,
                    onSelect = { layout = LayoutMode.FRAMED }
                )
                LayoutOption(
                    label = "横版铺满（原图 + 留白文字，无裁切）",
                    selected = layout == LayoutMode.FULLBLEED,
                    onSelect = { layout = LayoutMode.FULLBLEED }
                )

                Spacer(Modifier.height(10.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = screenColors,
                        onCheckedChange = { screenColors = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = Color(0xFF40C4FF),
                            uncheckedColor = Color(0xFF9E9E9E),
                            checkmarkColor = Color(0xFF003345)
                        )
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("屏幕鲜艳配色（仅相框模式）", color = Color.White, fontSize = 13.sp)
                }

                Spacer(Modifier.height(8.dp))

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
                        onClick = { onSave(normalizeBase(base), key.trim(), screenColors, layout) }
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

@Composable
private fun LayoutOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelect,
            colors = RadioButtonDefaults.colors(
                selectedColor = Color(0xFF40C4FF),
                unselectedColor = Color(0xFF9E9E9E)
            )
        )
        Spacer(Modifier.width(4.dp))
        Text(label, color = Color.White, fontSize = 13.sp)
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
