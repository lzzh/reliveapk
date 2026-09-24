package com.coomi.relive

import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.asAndroidBitmap
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

private const val PREFS = "relive_prefs"
private const val KEY_BASE = "server_base"
private const val KEY_API = "api_key"
private const val KEY_RATIO = "band_ratio"
private const val DEFAULT_RATIO = 0.12f

/** 规范化服务器地址：去空格、补协议、去尾部斜杠。 */
fun normalizeBase(raw: String): String {
    var b = raw.trim()
    if (b.isEmpty()) return b
    if (!b.startsWith("http://") && !b.startsWith("https://")) b = "https://$b"
    while (b.endsWith("/")) b = b.dropLast(1)
    return b
}

class MainActivity : ComponentActivity() {

    /**
     * 挂到 ViewModelStore 上（而不是直接 `new`），这样：
     *  - Activity 重建时复用同一个 VM，不会出现两个自动刷新循环；
     *  - 真正销毁时 [ReliveViewModel.onCleared] 会被调用，协程作用域被取消，不会泄漏。
     */
    private val vm: ReliveViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val p = this@MainActivity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                return ReliveViewModel(
                    ReliveClient(
                        baseUrl = p.getString(KEY_BASE, "") ?: "",
                        apiKey = p.getString(KEY_API, "") ?: ""
                    )
                ) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 全屏沉浸：双保险隐藏系统栏（Android 13+ 下 hide() 单独调用可能残留状态栏空白，
        // 叠加 SYSTEM_UI_FLAG 强制全屏，让照片真正占满整屏）
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window.decorView.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_LOW_PROFILE or
            android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 关键：让内容画进刘海/挖孔区。窗口默认 LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT，
        // 即使状态栏已隐藏，顶部刘海高度区域仍是黑边（Compose 内容没铺到那里）。
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            val mode = if (android.os.Build.VERSION.SDK_INT >= 30)
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            else
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = mode }
        }

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedBase = prefs.getString(KEY_BASE, "") ?: ""
        val savedKey = prefs.getString(KEY_API, "") ?: ""
        val savedRatio = prefs.getFloat(KEY_RATIO, DEFAULT_RATIO)

        setContent {
            MaterialTheme(colors = darkColors()) {
                ReliveScreen(
                    vm = vm,
                    initialBase = savedBase,
                    initialKey = savedKey,
                    initialRatio = savedRatio,
                    onSaveConfig = { base, key ->
                        prefs.edit()
                            .putString(KEY_BASE, base)
                            .putString(KEY_API, key)
                            .apply()
                        vm.applyConfig(base, key)
                    },
                    onSaveRatio = { ratio ->
                        prefs.edit().putFloat(KEY_RATIO, ratio).apply()
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
    initialRatio: Float,
    onSaveConfig: (String, String) -> Unit,
    onSaveRatio: (Float) -> Unit
) {
    val context = LocalContext.current

    val photo by vm.photo.collectAsStateWithLifecycle()
    val band by vm.band.collectAsStateWithLifecycle()
    val display by vm.display.collectAsStateWithLifecycle()
    val caption by vm.caption.collectAsStateWithLifecycle()
    val isRefreshing by vm.isRefreshing.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val lastRefreshMs by vm.lastRefreshMs.collectAsStateWithLifecycle()
    val conn by vm.conn.collectAsStateWithLifecycle()

    // 当前生效的配置（保存后立刻更新，避免再次打开设置时看到启动时的旧值）
    var curBase by remember { mutableStateOf(initialBase) }
    var curKey by remember { mutableStateOf(initialKey) }
    val configured = curBase.isNotBlank() && curKey.isNotBlank()

    var controlsVisible by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(!configured) }
    var pageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    // 真全屏像素尺寸（含状态栏/导航栏整屏）。用 onSizeChanged 取 Compose 实际渲染像素，
    // 用它做合成位图，确保 FillBounds 1:1 铺满、零黑边。
    var fullW by remember { mutableStateOf(0) }
    var fullH by remember { mutableStateOf(0) }
    // 底部文字白条占屏比例（可在设置里自定义）
    var bandRatio by remember { mutableStateOf(initialRatio) }

    // 用标准 Android API（ViewTreeObserver 布局监听）取整屏像素，
    // 彻底绕开不确定的 Compose 尺寸扩展（onSizeChanged/onGloballyPositioned 在此 BOM 解析不到）
    val rootView = androidx.compose.ui.platform.LocalView.current
    LaunchedEffect(rootView) {
        val listener = object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                fullW = rootView.width
                fullH = rootView.height
                if (rootView.width > 0 && rootView.height > 0) {
                    rootView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                }
            }
        }
        rootView.viewTreeObserver.addOnGlobalLayoutListener(listener)
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
        if (photo != null) {
            // 主路径：原图铺满上方 + 底部窄文字条（白条高度可在设置里调）
            // 用 View 真全屏像素合成位图，FillBounds 1:1 铺满，零黑边
            // 旋转/尺寸变化时先清空旧方向位图，避免旧位图在新画幅下短暂拉伸造成变形
            LaunchedEffect(photo, band, fullW, fullH, bandRatio) {
                pageBitmap = null
                val p = photo ?: return@LaunchedEffect
                if (fullW <= 0 || fullH <= 0) return@LaunchedEffect
                pageBitmap = withContext(Dispatchers.Default) {
                    PageComposer.composeFull(p, band, fullW, fullH, bandRatio)
                }
            }
            val pb = pageBitmap
            if (pb != null) {
                // 位图宽高比与当前容器一致时用 FillBounds（不变形、文字条完整）；
                // 不一致（旋转过渡帧的旧位图）时用 Crop，避免旧位图被拉伸变形
                val containerRatio = fullW.toFloat() / fullH.coerceAtLeast(1)
                val bm = pb.asAndroidBitmap()
                val bitmapRatio = bm.width.toFloat() / bm.height.coerceAtLeast(1f)
                val match = Math.abs(containerRatio - bitmapRatio) / containerRatio < 0.02f
                Image(
                    bitmap = pb,
                    contentDescription = "往年今日照片",
                    contentScale = if (match) ContentScale.FillBounds else ContentScale.Crop,
                    filterQuality = FilterQuality.Medium,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.White
                )
            }
        } else if (display != null) {
            // 回退路径：服务端渲染好的 480×800 相框位图（已含照片+文字，方向由服务端校正）
            // 竖版位图在横屏设备上按 Fit 居中显示，文字仍保持正向可读（不旋转）。
            Image(
                bitmap = display!!,
                contentDescription = "回退相框位图",
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.Medium,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = if (!configured) "尚未配置\n点屏幕 → ⚙ 填写服务器地址与 API Key"
                       else "加载中…",
                color = Color.White,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // 顶部任务栏已去掉：照片占满全屏，设置入口改为左上角独立小齿轮
        if (controlsVisible) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
            ) {
                IconButton(
                    onClick = {
                        vm.resetConnTest()
                        showSettings = true
                    },
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color(0x88000000), CircleShape)
                ) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "设置",
                        modifier = Modifier.size(22.dp),
                        tint = Color.White
                    )
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
            currentBase = curBase,
            currentKey = curKey,
            currentRatio = bandRatio,
            conn = conn,
            onTest = { base, key -> vm.testConfig(base, key) },
            onRatioChange = { r ->
                bandRatio = r
                onSaveRatio(r)
            },
            onSave = { base, key ->
                curBase = base
                curKey = key
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
    currentRatio: Float,
    conn: ConnTest,
    onTest: (String, String) -> Unit,
    onRatioChange: (Float) -> Unit,
    onSave: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var base by remember { mutableStateOf(currentBase) }
    var key by remember { mutableStateOf(currentKey) }
    var ratio by remember { mutableStateOf(currentRatio) }

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
                // 注意：LocalContext.current 是 composable 调用，必须先取出再进 remember 的 lambda
                val ctx = LocalContext.current
                val appVersion = remember(ctx) {
                    try {
                        val pkg = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
                        "v${pkg.versionName} (code ${pkg.versionCode})"
                    } catch (_: Throwable) {
                        "unknown"
                    }
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
                Spacer(Modifier.height(16.dp))

                // 底部文字白条高度（可自定义）
                Text(
                    text = "文字条高度：${(ratio * 100).toInt()}%",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Slider(
                    value = ratio,
                    onValueChange = {
                        ratio = it
                        onRatioChange(it)   // 实时保存并即时预览
                    },
                    valueRange = 0.06f..0.30f,
                    steps = 23,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF40C4FF),
                        activeTrackColor = Color(0xFF40C4FF),
                        inactiveTrackColor = Color(0xFF505050)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "越小文字条越窄（6%~30%，默认 12%）",
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
