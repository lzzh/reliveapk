# Relive 照片 App（Android）

连接你自部署的 [Relive](https://github.com/davidhoo/relive) 服务器，
把「往年今日」照片以**全屏沉浸式**方式展示在 Android 设备上。

## 功能

| 功能 | 说明 |
|---|---|
| **全屏无边框** | 隐藏状态栏 / 导航栏，内容铺满整屏 |
| **触屏显隐控件** | 控件默认隐藏，点一下屏幕才出现（再点收起） |
| **高清原图** | 直接取服务端原始照片（全彩、不裁切、不降色），非墨屏降色位图 |
| **EXIF 自动转正** | 手机竖拍照片（存成横向像素 + Orientation 标签）会自动转正，不会横躺 |
| **底部文字条** | 照片铺满上方，底部一条窄横条放服务端渲染的文案 + 日期（高度可调 6%~30%，默认 12%） |
| **设置页填写** | 服务器地址 + API Key，均持久化；首次启动自动弹出 |
| **连接测试** | 「测试连接」按钮，中文提示成功/失败原因；**且不会推进轮播序号** |
| **失败回退** | 原图取不到时，自动显示服务端 480×800 相框位图（按设备规格选调色板） |
| **自动跳过 CDN 缓存** | 请求附加 `?_t=<时间戳>`，避免拿到 4 小时旧缓存 |
| **相框模式** | 屏幕常亮（`FLAG_KEEP_SCREEN_ON`）+ 每 30 分钟自动刷新 |

> **通用版**：不预设任何服务器地址与 Key，首次启动必须自行填写。

## 关键概念：原图 vs 相框规格

很多人会混淆「原图」和「4 色 / 6 色」，这里讲清楚：

| 概念 | 是什么 | 是否全彩高清 |
|---|---|---|
| **原图模式（本 App 主路径）** | 直接取服务器 `photos/{id}/image` 的**原始照片** | ✅ 是，全彩高清，跟你在相册里看到的一样 |
| **相框规格** | Relive 服务端按设备渲染的 **480×800 墨水屏位图**，给墨水屏相框用 | ❌ 是降色（4 色 / 6 色）墨屏稿 |

**关键**：4 色 / 6 色（`gdem075f52_480x800_4color`、`spectra6_480x800`）是服务端给**墨水屏相框**的渲染档位，
由你在 Relive 后台给设备选的 `RenderProfile` 决定。

本 App **主路径走原图模式，完全不依赖它们** → 永远全彩高清。
（相框规格只在**回退**时用得到，那时会按设备的 `X-Render-Profile` 选对应调色板。）

## 排版规则

**统一为「照片铺满上方 + 底部一条窄横条」**，不区分照片横竖、也不旋转照片：

```
┌─────────────────────────────┐
│                             │
│      原照片 CENTER_CROP      │   ← 铺满上方，按屏幕比例裁切填满
│      （占 100% - 白条高度）   │
│                             │
├─────────────────────────────┤
│   文字条（横排居中，含文案+日期） │   ← 白条高度默认占屏幕 12%
└─────────────────────────────┘      设置里可调 6%~30%
```

- 白条为**纯白底**，文字条等比缩放居中
- 文字来源：服务端 480×800 相框**底部 160px 文字条**（含 AI 文案 + 日期 + 地点）
- 照片与文字条**保证来自同一张**（见下方「陷阱 2」）
- 白条高度在设置里用滑块调整，**即时生效**（存 `band_ratio`）

## 快速构建

```bash
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

> `gradle/wrapper/gradle-wrapper.jar` 已替换为 Gradle 8.7 官方完整版（此前仓库里那份是残缺的，
> 只有一个 `GradleWrapperMain.class` + 一个嵌套 jar，`./gradlew` 会直接报错）。
> 若你的环境仍无法用 wrapper，可直接用系统 gradle：
> ```bash
> gradle assembleDebug --no-daemon
> ```

CI 自动出包：推送到 `master` 后进入
**Actions → Build APK → 最新 run → Artifacts → `relive-photo-apk`**，解压即得 APK。

> ⚠️ 每个 run 都会产出**同名** artifact，Artifacts 页里可能同时存在多个。
> 请只在**对应 run 的页面底部**下载。App 内**设置页顶部会显示版本号**（`v1.0-<日期>-<commit>`），
> 装完对一下版本号即可确认是不是最新版。

## 使用

1. 安装后首次打开会自动弹出设置页
2. 填 **服务器地址**（如 `https://relive.example.com`，可省略 `https://`）与 **API Key**
3. 点 **「测试连接」** 确认（成功显示「✓ 连接成功：地址与 API Key 均有效 · Relive vX.Y.Z」）
4. 点 **「保存」**，即开始展示

> API Key 获取：Relive 后台 →「设备管理」→ 新建 `embedded` 类型设备 → 复制生成的 Key。
>
> ⚠️ **手机 App 与真实墨水屏相框建议用不同的设备 Key**。同一设备 Key 共用一套
> `current_sequence` 轮播状态，两边会互相推进、互相"抢"照片。

## 接口使用

| 用途 | 端点 | 说明 |
|---|---|---|
| 取推荐照片元信息 | `GET /api/v1/device/display` | JSON：`photo_id` / `asset_id` / `render_profile`（**会推进轮播序号**） |
| 取原图 | `GET /api/v1/photos/{photo_id}/image` | 原始照片（实测可达 3840×5760，**只读**） |
| 取文字条 | `GET /api/v1/display/assets/{asset_id}/bin` | 480×800 位图，底部 160px 即文字条（**只读**） |
| 回退：相框位图 | `GET /api/v1/device/display.bin` | 192,000 字节 4-bit 双像素流（**会推进轮播序号**） |
| 连接测试①：验地址 | `GET /api/v1/system/health` | 公开接口，返回 `{success, data:{version,...}}`（**只读**） |
| 连接测试②：验 Key | `GET /api/v1/photos/{不存在id}/image` | 带 `X-API-Key`，401=Key 无效，404=Key 有效（**只读**） |

鉴权统一为 Header `X-API-Key: sk-relive-...`（源码 `router.go` 中 `PhotoAuth` 同时接受 JWT 与 API Key；
`APIKeyAuth` 只接受 API Key）。

## 核心技术细节

### 1. 调色板（仅回退模式用，nibble → RGB，对齐 Relive 源码 `display_assets.go`）

| nibble | Spectra6 全彩 | GDEM075F52 四色 |
|---|---|---|
| 0 | 黑 (0,0,0) | 黑 (0,0,0) |
| 1 | 白 (255,255,255) | 白 (255,255,255) |
| 2 | 黄 (164,154,49) | 黄 (233,188,41) |
| 3 | 红 (126,39,39) | 红 (196,44,29) |
| 4 | 硬件保留（占位） | — |
| 5 | 蓝 (31,71,139) | — |
| 6 | 绿 (54,78,68) | — |

**按设备规格自动选择**：`X-Render-Profile` 含 `spectra6` → 六色；否则（如 `gdem075f52_480x800_4color`）→ 四色。
（注意：服务端 `DefaultRenderProfile()` 返回的就是 **四色** GDEM，写死 Spectra6 会让黄/红偏色。）

越界索引（nibble 8–15、调色板缺项）自动兜底为黑色，**不崩溃**。

### 2. 解码：还原正向竖版图（仅回退模式）

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

### 3. 三个陷阱（已解决）

**陷阱 1：文字从哪来？**
设备 Key **没有**可访问的 JSON 文字接口。但服务端 480×800 位图**底部 160px 就是白底黑字文字条**
（源码 `quantizeInfoRegionBlackWhite` 对这一区域做纯黑白量化）→
`EInkDecoder.decodeInfoBand()` 只解码这条，**复用服务端已排版好的中文**，App 内无需造字体。

**陷阱 2：图文不一致**
`/device/display` 与 `/device/display.bin` 每次调用都会让**推荐序号前进一格**
（源码 `display_daily_service.go` 里 `current_sequence + 1`，到末尾回绕）。若照片与文字条分两次取，会拿到两张不同的图。
→ 解决：**一次** `/device/display` 拿 `photo_id` 与 `asset_id`，
再用 `photo_id` 取原图、用 `asset_id` 调 `/display/assets/{id}/bin` 取**同一张**的文字条。

**陷阱 3：EXIF 方向**
`/photos/{id}/image` 对非 HEIC 文件是 `c.File(photo.FilePath)` —— **直接把磁盘原始文件吐回来，不做方向校正**，
而 `BitmapFactory` 不会自动应用 EXIF 旋转标签。→ 解决：`ImageDecode.decodeDownsampled()` 解码后
按 `ExifInterface` 的 Orientation 把图转正（也顺带修正了排版用的横竖判断）。

### 4. 其他

- 原图长边**降采样到 1920**（2 的幂采样）后再合成，避免 19MB 大图 OOM
- 合成时用 `FilterQuality.Medium`；回退位图按 `ContentScale.Fit` 居中（保持文字正向）
- 响应校验：相框位图长度必须 ≥ 192,000 字节；`X-Checksum` 与上次相同则标记 `unchanged`

## 文件结构

```
relive-photo-app/
├── .github/workflows/build-apk.yml   # GitHub Actions 自动出包
├── settings.gradle.kts               # 仓库 + 插件源（google/mavenCentral）
├── build.gradle.kts                  # AGP 8.5.0 / Kotlin 1.9.22
├── gradle.properties
├── gradlew                           # wrapper 启动脚本
├── gradle/wrapper/gradle-wrapper.jar # Gradle 8.7 官方 wrapper（已修复）
└── app/
    ├── build.gradle.kts              # Compose(BOM 2024.02) + OkHttp + lifecycle + core-ktx
    └── src/main/
        ├── AndroidManifest.xml       # 图标 + INTERNET + 明文 HTTP + allowBackup=false
        ├── res/mipmap-*/ic_launcher.png
        ├── res/values/{strings,themes}.xml
        └── java/com/coomi/relive/
            ├── MainActivity.kt       # 全屏 + 触屏显隐 + 设置弹窗 + 合成接线 + 回退渲染
            ├── ReliveViewModel.kt    # 状态管理（原图+文字条、回退、换配置、自动刷新）
            ├── ReliveClient.kt       # OkHttp + X-API-Key + cache-buster + 只读连接测试
            ├── PageComposer.kt       # 照片铺满 + 底部白条文字合成（高度可调）
            ├── EInkDecoder.kt        # 4-bit 流 → 竖版 Bitmap / 文字条（按规格选调色板）
            └── ImageDecode.kt        # 原图 2 的幂降采样 + EXIF 方向校正
```

## 注意

- App **跟随设备物理方向**（Manifest 未锁定 `screenOrientation`，声明了 `configChanges`，
  旋转不会重建 Activity、不会重新拉图）。
- 服务器地址与 API Key **均无默认值**，首次启动必须填写。
- **CDN 缓存**：`display.bin` 常被 Cloudflare 缓存 4 小时且**缓存键不含 API Key**，
  故所有请求附加 `?_t=<时间戳>`；否则改规格 / 换 Key 后仍拿到旧图。
- **规格与 Key 绑定**：不同 Key 可绑定不同 RenderProfile
  （`spectra6_480x800` 六色 / `gdem075f52_480x800_4color` 四色）。
  颜色不对时，先在 Relive 后台确认该设备的 RenderProfile。
- 原图模式**不受** 4 色 / 6 色规格影响（它直接用原始照片，永远是全彩高清）。
- **旧配置清理**：若升级后仍显示旧默认地址，是 `SharedPreferences` 里存了旧值，
  卸载重装或用系统「清除应用数据」清零即可。
- 服务端切换 RenderProfile 后，需在 Relive 后台**触发一次展示批次生成**才会产出新资产。
- 每次刷新会拉一次原图（实测约 19MB），30 分钟一次 ≈ 一天 1GB 量级，注意流量。
