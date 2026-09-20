# Relive 照片 Android App

连接自部署的 [Relive](https://github.com/davidhoo/relive) 服务器，
拉取「往年今日」预渲染的墨水屏位图（4-bit 双像素流），解码后**全屏沉浸式**展示。

- **全屏无边框**：隐藏状态栏/导航栏
- **触屏显隐控件**：默认隐藏，点一下屏幕才出现标题 / 状态 / 刷新 / 设置
- **跟随屏幕方向**：设备横屏 + 图片竖版时自动旋转 90°，`Crop` 铺满屏幕
- **设置内填 API Key**：点屏幕 → ⚙ → 输入 Key → 保存（持久化，立即生效）
- **多规格自适应**：支持 Spectra6 全彩 6 色 / GDEM075F52 4 色，读 `X-Render-Profile` 自动选调色板
- **离线兜底**：拉不到网时显示内置位图

## 快速构建

```bash
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

或在 GitHub Actions 里自动出包：推送到 `master` 后，进入
**Actions → Build APK → 最新 run → Artifacts → `relive-photo-apk`**，解压即得 APK。

## 关键约定（与 Relive ESP32 固件完全一致）

| 项 | 值 |
|---|---|
| 端点 | `GET /api/v1/device/display.bin` |
| 鉴权 | Header `X-API-Key: sk-relive-...` |
| 返回 | 192,000 字节 4-bit 双像素流 |
| 响应头 | `X-Asset-ID` / `X-Checksum`(sha256) / `X-Server-Time` / `X-Render-Profile` |
| 打包 | 每字节 2 像素：高 4 位 = 左，低 4 位 = 右 |

### 调色板（nibble → RGB，对齐 Relive 源码 `display_assets.go`）

| nibble | Spectra6 全彩 | GDEM075F52 四色 |
|---|---|---|
| 0 | 黑 (0,0,0) | 黑 (0,0,0) |
| 1 | 白 (255,255,255) | 白 (255,255,255) |
| 2 | 黄 (164,154,49) | 黄 (233,188,41) |
| 3 | 红 (126,39,39) | 红 (196,44,29) |
| 4 | 硬件保留（占位） | — |
| 5 | 蓝 (31,71,139) | — |
| 6 | 绿 (54,78,68) | — |

### 解码：还原正向竖版图

服务端写盘前会把 **480宽×800高** 的画布**逆时针旋转 90°**，再按 **800列×480行** 打包
（源码 `encodeIndexedBinary` / `rotateIndexed90CCW`，供 ESP32 横屏直接 `display()`）：

```go
// rotateIndexed90CCW(srcWidth=480, srcHeight=800)
dstX := srcHeight - 1 - srcY   // = 799 - srcY
dstY := srcX
rotated[dstY * dstWidth + dstX] = indexed[srcY*srcWidth + srcX]
```

因此 `landscape[col][row]` 与竖版像素的对应关系为
`landscape[x][799 - y] = portrait(x, y)`，App 端还原公式即：

```
portrait(x, y) = landscape[x][STREAM_W - 1 - y]     // x∈[0,479], y∈[0,799]
```

> ⚠️ 易错点：不要按"逆时针转 90°"直接反推成 `landscape[479-x][y]`——
> 那会得到镜像+倒置的图。以源码公式为准（已用真数据出图验证）。

## 文件结构

```
relive-photo-app/
├── .github/workflows/build-apk.yml   # GitHub Actions 自动出包
├── settings.gradle.kts               # 仓库 + 插件源（google/mavenCentral）
├── build.gradle.kts                  # AGP 8.5.0 / Kotlin 1.9.22
├── gradle.properties
└── app/
    ├── build.gradle.kts              # Compose + OkHttp + lifecycle + core-ktx
    └── src/main/
        ├── AndroidManifest.xml       # 横屏 + 图标 + INTERNET
        ├── assets/sample/display.bin # 离线兜底图（192,000 字节）
        ├── res/mipmap-*/ic_launcher.png
        ├── res/values/{strings,themes}.xml
        └── java/com/coomi/relive/
            ├── MainActivity.kt       # 全屏 + 触屏显隐 + 设置弹窗 + 方向自适应
            ├── ReliveViewModel.kt    # 状态管理（拉取/解码/兜底/换 Key）
            ├── ReliveClient.kt       # OkHttp + X-API-Key（Key 可运行时更新）
            ├── EInkDecoder.kt        # 4-bit 流 → 正向竖版 Bitmap
            └── ImageRotate.kt        # 顺时针 90° 旋转（方向跟随）
```

## 注意

- App **跟随设备物理方向**（Manifest 未锁定方向）；图片方向与屏幕不一致时自动旋转 90° + `Crop` 铺满。
- API Key **不再硬编码**：首次启动弹出设置页，必须手动填写（避免公开仓库泄露 Key）。
- 缩放用 `FilterQuality.None`（最近邻），保持墨水屏抖动点锐利。
- 调色板可切换：**墨水屏原色** / **屏幕鲜艳色**（设置页勾选，LCD/OLED 上更亮）。
- 响应校验：长度必须等于 192,000 字节；`X-Checksum` 与上次相同时跳过重绘。
- 相框模式：屏幕常亮 + 每 30 分钟自动刷新。
- **CDN 缓存**：`display.bin` 会被 Cloudflare 缓存 4 小时且缓存键不含 API Key，
  因此 App 请求自动附加 `?_t=<时间戳>` 绕开缓存；否则改规格/换 Key 后拿到的仍是旧图。
- 服务端切换 RenderProfile 后（如 `spectra6_480x800` → 全彩），
  需在 Relive 后台**触发一次展示批次生成**，`display.bin` 才会更新为新规格资产。
