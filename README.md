# Relive 照片 App（Android）

连接你自部署的 [Relive](https://github.com/davidhoo/relive) 服务器，
把「往年今日」照片以**全屏沉浸式**方式展示在 Android 设备上。

## 功能

| 功能 | 说明 |
|---|---|
| **全屏无边框** | 隐藏状态栏 / 导航栏，内容铺满整屏 |
| **触屏显隐控件** | 控件默认隐藏，点一下屏幕才出现（再点收起） |
| **布局自动适配** | **按照片自身横竖**自动排版，无需选择：<br>· 横构图照片 → 右侧白边 + 文字<br>· 竖构图照片 → 下方白边 + 文字 |
| **设置页填写** | 服务器地址 + API Key，均持久化；首次启动自动弹出 |
| **连接测试** | 「测试连接」按钮，成功 / 失败给中文提示（如「API Key 无效（HTTP 401）」） |
| **多规格自适应** | Spectra6 全彩 6 色 / GDEM075F52 4 色，读 `X-Render-Profile` 自动选调色板 |
| **失败回退** | 原图取不到时回退到服务端 480×800 相框位图 |
| **自动跳过 CDN 缓存** | 请求附加 `?_t=<时间戳>`，避免拿到 4 小时旧缓存 |
| **相框模式** | 屏幕常亮（`FLAG_KEEP_SCREEN_ON`）+ 每 30 分钟自动刷新 |

> **通用版**：不预设任何服务器地址与 Key，首次启动必须自行填写。

## 排版规则

白边位置由**照片自身的横竖**决定（不是由屏幕方向决定）：

```
横构图照片 (w > h)
┌────────────────────┬────────┐
│                    │        │
│    原照片            │ 文字条  │
│    CENTER_CROP     │ 旋转90° │
│      占 74%         │ 占 26%  │
└────────────────────┴────────┘

竖构图照片 (h ≥ w)
┌─────────────────────────────┐
│      原照片 CENTER_CROP      │
│          占 78%              │
├─────────────────────────────┤
│      文字条（横排居中）        │
│          占 22%              │
└─────────────────────────────┘
```

- 留白区为**纯白底**，文字条等比缩放居中，四周留 10% 内边距
- 文字来源：服务端 480×800 相框**底部 160px 文字条**（含 AI 文案 + 日期 + 地点）
- 照片与文字条**保证来自同一张**（见下方「陷阱 2」）

## 快速构建

```bash
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

或在 GitHub Actions 自动出包：推送到 `master` 后进入
**Actions → Build APK → 最新 run → Artifacts → `relive-photo-apk`**，解压即得 APK。

## 使用

1. 安装后首次打开会自动弹出设置页
2. 填 **服务器地址**（如 `https://relive.example.com`，可省略 `https://`）与 **API Key**
3. 点 **「测试连接」** 确认（成功显示「✓ 连接成功 · 规格 spectra6_480x800 · asset 682」）
4. 点 **「保存」**，即开始展示

> API Key 获取：Relive 后台 →「设备管理」→ 新建 `embedded` 类型设备 → 复制生成的 Key。

## 接口使用

| 用途 | 端点 | 说明 |
|---|---|---|
| 取推荐照片元信息 | `GET /api/v1/device/display` | JSON：`photo_id` / `asset_id` / `render_profile` |
| 取原图 | `GET /api/v1/photos/{photo_id}/image` | 未裁切原图（实测可达 4032×2688） |
| 取文字条 | `GET /api/v1/display/assets/{asset_id}/bin` | 480×800 位图，底部 160px 即文字条 |
| 回退：相框位图 | `GET /api/v1/device/display.bin` | 192,000 字节 4-bit 双像素流 |

鉴权统一为 Header `X-API-Key: sk-relive-...`（源码 `router.go` 中 `PhotoAuth` 同时接受 JWT 与 API Key）。

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

越界索引（nibble 8–15、调色板缺项）自动兜底为黑色，**不崩溃**。

### 2. 解码：还原正向竖版图

服务端写盘前把 **480宽×800高** 画布**逆时针旋转 90°**，再按 **800列×480行** 打包
（源码 `encodeIndexedBinary` / `rotateIndexed90CCW`）：

```go
// rotateIndexed90CCW(srcWidth=480, srcHeight=800)
dstX := srcHeight - 1 - srcY   // = 799 - srcY
dstY := srcX
rotated[dstY*dstWidth + dstX] = indexed[srcY*srcWidth + srcX]
```

对应关系 `landscape[x][799 - y] = portrait(x, y)`，App 端还原公式：

```
portrait(x, y) = landscape[x][STREAM_W - 1 - y]     // x∈[0,479], y∈[0,799]
```

> ⚠️ 易错点：不要按"逆时针转 90°"直接反推成 `landscape[479-x][y]`——
> 那会得到镜像 + 倒置的图。**以源码公式为准**（已用真数据出图验证）。

### 3. 两个陷阱（已解决）

**陷阱 1：文字从哪来？**
设备 Key **没有**可访问的 JSON 元数据接口（`GET /photos/{id}` 详情在 JWT 分组）。
但服务端 480×800 位图**底部 160px 就是白底黑字文字条** →
`EInkDecoder.decodeInfoBand()` 只解码这条，**复用服务端已排版好的中文**，App 内无需造字体。

**陷阱 2：图文不一致**
`/device/display` 与 `/device/display.bin` 每次调用都会让**推荐序号前进一格**
（源码 `current_sequence + 1`）。若照片与文字条分两次取，会拿到两张不同的图。
→ 解决：**一次** `/device/display` 拿 `photo_id` 与 `asset_id`，
再用 `photo_id` 取原图、用 `asset_id` 调 `/display/assets/{id}/bin` 取**同一张**的文字条。

### 4. 其他

- 原图长边**降采样到 2048**（2 的幂采样）后再合成，避免大图 OOM
- 相框回退位图缩放用 `FilterQuality.None`（最近邻），保持墨水屏抖动点锐利
- 响应校验：相框位图长度必须 = 192,000 字节；`X-Checksum` 与上次相同则跳过重绘

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
        ├── AndroidManifest.xml       # 图标 + INTERNET + 明文 HTTP
        ├── res/mipmap-*/ic_launcher.png
        ├── res/values/{strings,themes}.xml
        └── java/com/coomi/relive/
            ├── MainActivity.kt       # 全屏 + 触屏显隐 + 设置弹窗 + 合成接线
            ├── ReliveViewModel.kt    # 状态管理（原图+文字条、回退、换配置、自动刷新）
            ├── ReliveClient.kt       # OkHttp + X-API-Key + cache-buster + 连接测试
            ├── PageComposer.kt       # 按照片横竖排版（照片 + 白边文字）
            ├── EInkDecoder.kt        # 4-bit 流 → 正向竖版 Bitmap / 文字条
            ├── ImageDecode.kt        # 原图 2 的幂降采样解码（防 OOM）
            └── ImageRotate.kt        # 顺时针 90° 旋转（回退模式方向适配）
```

## 注意

- App **跟随设备物理方向**（Manifest 未锁定 `screenOrientation`）。
- 服务器地址与 API Key **均无默认值**，首次启动必须填写。
- **CDN 缓存**：`display.bin` 常被 Cloudflare 缓存 4 小时且**缓存键不含 API Key**，
  故所有请求附加 `?_t=<时间戳>`；否则改规格 / 换 Key 后仍拿到旧图。
- **规格与 Key 绑定**：不同 Key 可能绑定不同 RenderProfile
  （如某 Key 是 `spectra6_480x800` 全彩，另一个是 `gdem075f52_480x800_4color` 四色）。
  颜色不对时，先在 Relive 后台确认该设备的 RenderProfile。
- 服务端切换 RenderProfile 后，需在 Relive 后台**触发一次展示批次生成**才会产出新资产。
