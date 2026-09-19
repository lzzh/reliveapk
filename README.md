# Relive 照片 Android App

连接 Relive 服务器（`relive.luckyson.online`），拉取**往年今日**的预渲染墨水屏位图，
用 4-bit 双像素解码后全屏展示。离线时兜底显示内置图。

## 快速构建

```bash
cd relive-photo-app
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

本机 proot 缺 Android SDK + Java，无法直接出 APK。
推荐：把整个目录拷到有 Android 工具链的机器上跑 `./gradlew assembleDebug`，
或 `./gradlew installDebug` 直连手机。

## 文件结构

```
relive-photo-app/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── assets/sample/display.bin        # 离线兜底图（192000 字节真数据）
        ├── res/values/{strings,themes}.xml
        └── java/com/coomi/relive/
            ├── MainActivity.kt              # Compose 主界面 + ViewModel 接线
            ├── ReliveViewModel.kt           # 状态管理（拉取/解码/兜底）
            ├── ReliveClient.kt              # OkHttp 调 X-API-Key 接口
            └── EInkDecoder.kt               # 4-bit 双像素流 → Bitmap
```

## 关键约定（与 Relive ESP32 完全一致）

| 项 | 值 |
|---|---|
| 端点 | `GET /api/v1/device/display.bin` |
| 鉴权 | Header `X-API-Key: sk-relive-...` |
| 返回 | 192,000 字节 4-bit 双像素流（800×480） |
| 响应头 | `X-Asset-ID` / `X-Checksum`(sha256) / `X-Server-Time` |
| 调色板 | 0 黑 / 1 白 / 2 黄 / 3 红 / 5 蓝 / 6 绿 |

## 注意

- 图片解码后是 **800×480 横屏**位图，竖屏 App 里用 `ContentScale.Fit` 自动适配；
- 如需横屏满屏显示，把 `AndroidManifest` 里 `android:screenOrientation` 改成 `landscape`；
- 当前 `apiKey` 硬编码在 `MainActivity.onCreate` 里，生产应移到 `SharedPreferences` / 配置；
- 内置兜底图是真实拉取过的 `display.bin`（本次会话已验证解码出 2024-02-11 聚餐照）。
