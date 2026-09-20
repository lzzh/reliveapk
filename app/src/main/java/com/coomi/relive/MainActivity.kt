package com.coomi.relive

import android.os.Bundle
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 全屏沉浸：隐藏状态栏与导航栏，内容延伸到系统栏区域
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        val client = ReliveClient(apiKey = "sk-relive-REDACTED-1")
        val sample = loadSample()
        val vm = ReliveViewModel(client, sample)

        setContent {
            MaterialTheme(colors = darkColors()) {
                ReliveScreen(vm)
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
fun ReliveScreen(vm: ReliveViewModel) {
    val display by vm.display.collectAsStateWithLifecycle()
    val assetId by vm.assetId.collectAsStateWithLifecycle()
    val isRefreshing by vm.isRefreshing.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val lastRefreshMs by vm.lastRefreshMs.collectAsStateWithLifecycle()

    // 控件默认隐藏，点屏幕后显示
    var controlsVisible by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { controlsVisible = !controlsVisible }
    ) {
        // 主图：全屏铺满（保持比例，居中）
        if (display != null) {
            Image(
                bitmap = display!!,
                contentDescription = "往年今日照片",
                contentScale = ContentScale.Fit,
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

        // 顶部标题（仅控件可见时）
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
                if (isRefreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                }
            }
        }

        // 底部信息条（仅控件可见时）
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
                        text = "asset $assetId · 刷新 ${formatTime(lastRefreshMs)}",
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                }
            }
        }

        // 刷新按钮（仅控件可见时，右下角）
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
