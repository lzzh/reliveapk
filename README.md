# Relive 照片 Android App

连接自部署的 [Relive](https://github.com/davidhoo/relive) 服务器，
把「往年今日」照片以**全屏沉浸式**方式展示在 Android 设备（手机 / 平板 / 相框）上。

## 功能

| 功能 | 说明 |
|---|---|
| **全屏无边框** | 隐藏状态栏 / 导航栏，内容铺满整屏 |
| **触屏显隐控件** | 控件默认隐藏，点一下屏幕才出现（再点收起） |
| **两种展示布局** | ① **相框竖版**：用服务端 480×800 位图（含日期/文案）<br>② **横版铺满**：取原图 + 服务端文字条，App 内排版（见下） |
| **方向自适应** | 相框模式下图与屏幕方向不一致时自动旋转 90° |
| **设置页可填** | 服务器地址 + API Key，均持久化到 `SharedPreferences` |
| **连接测试** | 「测试连接」按钮，成功/失败给出中文提示（如「API Key 无效（HTTP 401）」） |
| **多规格自适应** | 支持 Spectra6 全彩 6 色 / GDEM075F52 4 色，读 `X-Render-Profile` 自动选调色板 |
| **配色可切换** | 墨水屏原色（暗，贴近真机）/ 屏幕鲜艳色（LCD、OLED 更亮） |
| **相框模式** | 屏幕常亮（`FLAG_KEEP_SCREEN_ON`）+ 每 30 分钟自动刷新 |
| **离线兜底** | 拉不到网时显示内置位图 |
| **自动跳过 CDN 缓存** | 请求附加 `?_t=<时间戳>`，避免拿到 4 小时旧缓存 |

## 展示布局详解

「横版铺满」模式解决了一个核心矛盾：**Relive 服务端按 480×800 竖版渲染并裁剪**，
横构图照片会被裁成 3:4 竖幅，在横屏设备上丢失大量内容。

App 的做法是**绕开服务端的竖版裁剪**，自己排版：

```
┌─────────────────────────┬──────────┐
│                         │          │
│                         │  文字条   │
│      原照片（未裁剪）      │ （旋转   │
│      居中裁切 CENTER_CROP │   90°）  │
│      占长边 75%          │          │
│                         │  占 25%  │
└─────────────────────────┴──────────┘
        横屏：照片在左，白边文字条在右
┌─────────────────────────┐
│                         │
│    原照片（居中裁切）      │
│      占长边 75%          │
├─────────────────────────┤
│      文字条（横排居中）     │
│        占 25%            │
└─────────────────────────┘
        竖屏：照片在上，白边文字条在下
```

- 留白区为**纯白底 + 深色文字**，与 Relive 相框信息区风格一致
- 文字来源：服务端 480×800 相框**底部 160px 文字条**（服务端已渲染好 AI 文案 + 日期 + 地点）
- 照片与文字条**保证来自同一张**（见下方「两个陷阱」）

## 快速构建

```bash
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

或在 GitHub Actions 自动出包：推送到 `master` 后进入
**Actions → Build APK → 最新 run → Artifacts → `relive-photo-apk`**，解压即得 APK。

## 接口使用

### 相框模式（480×800 位图）

| 项 | 值 |
|---|---|
| 端点 | `GET /api/v1/device/display.bin` |
| 鉴权 | Header `X-API-Key: sk-relive-...` |
| 返回 | 192,000 字节 4-bit 双像素流 |
| 响应头 | `X-Asset-ID` / `X-Checksum`(sha256) / `X-Server-Time` / `X-Render-Profile` |

### 横版铺满模式

| 用途 | 端点 | 说明 |
|---|---|---|
| 取推荐照片元信息 | `GET /api/v1/device/display` | JSON：`photo_id` / `asset_id` / `render_profile` / `sequence` |
| 取原图 | `GET /api/v1/photos/{photo_id}/image` | 未裁剪原图（实测可达 4032×2688、6MB） |
| 取文字条 | `GET /api/v1/display/assets/{asset_id}/bin` | 480×800 位图，底部 160px 即文字条 |

> 这三个接口都接受**设备 API Key**（源码 `router.go` 里 `PhotoAuth` 同时接受 JWT 与 API Key）。

## 核心技术细节

### 1. 调色板（nibble → RGB，对齐 Relive 源码 `display_assets.go`）

| nibble | Spectra6 全彩 | GDEM075F52 四色 |
|---|---|---|
| 0 | 黑 (0,0,0) | 黑 (0,0,0) |
| 1 | 白 (255,255,255) | 白 (255,255,255) |
| 2 | 黄 (164,154,49) | 黄 (233,188,41) |
| 3 | 红 (126,39,39) | 红 (196,44,29) |
| 4 | 硬件保留（占位） | — |
| 5 | 蓝 (31,71,139) | — |
| 6 | 绿 (54,78,68) | — |

越界索引（如 nibble 8–15、调色板缺项）自动兜底为黑色，**不崩溃**。

### 2. 解码：还原正向竖版图

服务端写盘前把 **480宽×800高** 画布**逆时针旋转 90°**，再按 **800列×480行** 打包
（源码 `encodeIndexedBinary` / `rotateIndexed90CCW`）：

```go
// rotateIndexed90CCW(srcWidth=480, srcHeight=800)
dstX := srcHeight - 1 - srcY   // = 799 - srcY
dstY := srcX
rotated[dstY*dstWidth + dstX] = indexed[srcY*srcWidth + srcX]
```

对应关系为 `landscape[x][799 - y] = portrait(x, y)`，App 端还原公式：

```
portrait(x, y) = landscape[x][STREAM_W - 1 - y]     // x∈[0,479], y∈[0,799]
```

> ⚠️ 易错点：不要按"逆时针转 90°"直接反推成 `landscape[479-x][y]`——
> 那会得到镜像 + 倒置的图。**以源码公式为准**（已用真数据出图验证）。

### 3. 两个陷阱（踩过，已解决）

**陷阱 1：文字从哪来？**
设备 Key **没有**任何可访问的 JSON 元数据接口（`GET /photos/{id}` 详情在 JWT 分组里）。
但服务端 480×800 位图的**底部 160px 就是白底黑字文字条** → 用
`EInkDecoder.decodeInfoBand()` 只解码这条，**复用服务端已排版好的中文**，无需在 App 内造字体。

**陷阱 2：图文不一致**
`/device/display.bin` 与 `/device/display` 每次调用都会让**推荐序号前进一格**
（源码 `current_sequence + 1`）。若照片与文字条分两次取，会拿到两张不同的图。
→ 解决：先调一次 `/device/display` 拿 `photo_id` **和** `asset_id`，
再用 `photo_id` 取原图、用 **`asset_id`** 调 `/display/assets/{id}/bin` 取**同一张**的文字条。

## 文件结构

```
relive-photo-app/
├── .github/workflows/build-apk.yml   # GitHub Actions 自动出包
├── settings.gradle.kts               # 仓库 + 插件源（google/mavenCentral）
├── build.gradle.kts                  # AGP 8.5.0 / Kotlin 1.9.22
├── gradle.properties
└── app/
    ├── build.gradle.kts              # Compose(BOM 2024.02) + OkHttp + lifecycle + core-ktx
    └── src/main/
        ├── AndroidManifest.xml       # 图标 + INTERNET + 明文 HTTP + configChanges
        ├── assets/sample/display.bin # 离线兜底图（192,000 字节）
        ├── res/mipmap-*/ic_launcher.png
        ├── res/values/{strings,themes}.xml
        └── java/com/coomi/relive/
            ├── MainActivity.kt       # 全屏 + 触屏显隐 + 设置弹窗 + 布局选择 + 合成接线
            ├── ReliveViewModel.kt    # 状态管理（相框/铺满两模式、解码、兜底、换配置）
            ├── ReliveClient.kt       # OkHttp + X-API-Key + cache-buster + 连接测试
            ├── EInkDecoder.kt        # 4-bit 流 → 正向竖版 Bitmap / 文字条
            ├── PageComposer.kt       # 照片 + 白边文字条 → 适配屏幕的画页
            ├── ImageDecode.kt        # 原图 2 的幂降采样解码（防 OOM）
            └── ImageRotate.kt        # 顺时针 90° 旋转（方向跟随）
```

## 注意

- App **跟随设备物理方向**（Manifest 未锁定 `screenOrientation`）。
- API Key **不硬编码**：首次启动自动弹出设置页，必须手动填写。
- 相框模式缩放用 `FilterQuality.None`（最近邻），保持墨水屏抖动点锐利。
- 原图模式：长边降采样到 2048 再合成，避免大图 OOM。
- 响应校验：相框位图长度必须 = 192,000 字节；`X-Checksum` 与上次相同则跳过重绘。
- **CDN 缓存**：`display.bin` 被 Cloudflare 缓存 4 小时且**缓存键不含 API Key**，
  故所有请求附加 `?_t=<时间戳>`；否则改规格 / 换 Key 后仍拿到旧图。
- **规格与 Key 绑定**：不同 Key 可能绑定不同 RenderProfile
  （如某 Key 是 `spectra6_480x800` 全彩，另一个是 `gdem075f52_480x800_4color` 四色）。
  颜色不对时，先在 Relive 后台确认该设备的 RenderProfile。
- 服务端切换 RenderProfile 后，需在 Relive 后台**触发一次展示批次生成**才会产出新资产。
