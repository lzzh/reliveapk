package com.coomi.relive

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val client = ReliveClient(apiKey = "sk-relive-REDACTED-2")
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
    val serverTimeSec by vm.serverTimeSec.collectAsStateWithLifecycle()
    val isRefreshing by vm.isRefreshing.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val lastRefreshMs by vm.lastRefreshMs.collectAsStateWithLifecycle()

    Scaffold(
        backgroundColor = Color(0xFF101010),
        floatingActionButton = {
            FloatingActionButton(
                onClick = { vm.refresh() }
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "刷新")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
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

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                if (display != null) {
                    Image(
                        bitmap = display!!,
                        contentDescription = "往年今日照片",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF1A1A1A), shape = RoundedCornerShape(12.dp))
                    )
                } else {
                    Text(
                        text = "无数据",
                        color = Color.Gray,
                        fontSize = 14.sp
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
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
                    Text(
                        text = "离线，显示内置图",
                        color = Color(0xFFFF5252),
                        fontSize = 12.sp
                    )
                } else {
                    Text(
                        text = "asset ${assetId} · 刷新 ${formatTime(lastRefreshMs)}",
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
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
    background = Color(0xFF101010),
    surface = Color(0xFF101010),
    onSurface = Color.White
)
