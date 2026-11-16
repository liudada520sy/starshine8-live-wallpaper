# ADR-001：2025款吉利银河星耀8 高美感多风格动态壁纸系统架构决策记录

- **文档编号**：ADR-001
- **状态**：已锁定（Locked / Approved）
- **决策日期**：2026-10-15
- **目标硬件与系统**：2025款吉利银河星耀8（银河 Flyme Auto 智能座舱系统 / 龍鹰一号 7nm SoC / 15.4英寸 `2560×1600` 16:10 超清中控横屏）
- **遵循规范**：`garrytan-gstack`（`/office-hours`, `/plan-ceo-review`, `/plan-eng-review`, `/design-review`）与 `mattpocock-skills`（`/grill-me`, `design-an-interface`）

## 1. 路线1（端侧实时闭环）优先交付，路线2（外部算力视频重绘）通过统一渲染契约无缝扩展

系统采用 **“路线1先行、路线2无缝扩展”** 的双阶段渐进式架构，确保车主在地下车库无公网环境下也能在 3 秒内获得“扫码传图即上屏”的十星级座舱视觉体验，同时严守龍鹰一号 7nm SoC 算力与温控红线（实时渲染 GPU 负载 < 40 GFLOPS，后台/锁屏 0% 占用）。

| 审计维度 | Phase A（路线1：纯车机端独立闭环） | Phase B（路线2：外部算力重绘协同） | 架构收敛裁定 |
| :--- | :--- | :--- | :--- |
| **首屏响应延迟** | 局域网直传 + 端侧 INT8 深度推理（< 0.8s）+ 60fps 实时 GLSL/AGSL 着色器，3 秒内上屏 | 外部 FastAPI + ComfyUI 生成 H.265 循环视频耗时 15s~60s，异步完成后热切换 | **Phase A 为体验兜底基石**：即使触发 Phase B 重绘，也必须先以 Phase A 实时着色器立即渲染上屏 |
| **弱网/地下车库可用性** | 仅依赖车机热点或局域网 Wi-Fi，100% 离线闭环可用 | 依赖局域网 PC（如 RTX 4060 Ti）或云端节点，探测在线时动态激活 | **Offline-First 离线优先**：核心传图、深度提取与四大美学风格绝不依赖公网连接 |
| **座舱原生视觉融合** | 提取 Palette 五色注入黑曜石毛玻璃卡片（`#0B0E14` 75% Alpha + 1px 高光描边） | H.265 `2560×1600` 无缝循环 MP4 交由车机 VPU（`MediaCodec`）零 GPU 负担硬解 | **统一领域渲染接口**：通过 `DynamicSceneRenderer` 屏蔽实时 Shader 与视频硬解的底层差异 |
| **SoC 算力与温控保护** | 深度推理仅在导入时运行一次并立即释放 NPU/CPU；实时渲染帧率上限 60fps | 重度扩散生成 100% 剥离至外部算力节点，严禁在车机 SoC 运行扩散模型 | **严守导航与 ADAS 算力红线**：杜绝车机过热降频，切入全屏导航或锁屏时 100% 挂起渲染线程 |

## 2. 核心架构决策总表（Locked Architecture Decisions）

基于 `/grill-me` 架构拷问确认结果，以下 9 项核心架构决策（含 2 项基础架构基线与 7 项确认默认项）已正式锁定：

| Decision Area | Locked Choice | Rejected Alternative | Technical Rationale |
| :--- | :--- | :--- | :--- |
| **Runtime Mode（双宿主运行模式）** | 双宿主智能联动：`WallpaperService`（系统桌面级动态壁纸）+ `ImmersiveShowcaseActivity`（全屏沉浸驻车画报兜底模式），自动探测权限并支持 P 挡/充电自动唤醒 | 仅实现 `WallpaperService` 或双模式完全孤立手动切换 | Flyme Auto 固件更新可能限制第三方桌面壁纸绑定；智能回退与驻车画报可将受限场景转化为高仪式感座舱 StandBy 体验。 |
| **Compute Split（算力切分边界）** | 车机端执行一次性 INT8 深度推理（< 0.8s）+ 60fps OpenGL ES 3.0 / AGSL 实时着色器；外部 PC/云端执行 AI 视频扩散重绘 + 车机 VPU 硬解 | 在车机 SoC 上直接运行重度图像/视频生成模型 | 防止龍鹰一号 7nm SoC 热节流（Thermal Throttling），确保高德导航与 ADAS 渲染零卡顿。 |
| **Asset Transfer & Crop（16:10 裁剪落点）** | 手机 H5 端交互式 `16:10` 框选预览并通过 Canvas 导出 `2560×1600` 上传 + 车机端 `inSampleSize` 焦点居中裁剪兜底 | 纯车机端自动居中裁剪或原图无压缩直传 | 避免手机竖屏照片（`9:16`/`4:3`）直接居中裁剪切掉人物/车身主体；前端规整至 `2560×1600` 将传输体积从 20MB+ 降至 1.5~3MB，根除车机解码 OOM。 |
| **Storage Layout & Quota（本地资产包与配额）** | 私有目录 `files/wallpapers/<asset_id>/` 独立文件夹结构（含 `color_2560x1600.webp`、`depth_8bit.png`、`video_loop.mp4`、`manifest.json`）+ **512MB / 30套 LRU 自动淘汰（保留标星收藏）** | SQLite BLOB 存储或无配额上限散落存储 | 独立目录支持原子级读写与清理；512MB 硬上限防止挤占车机 OTA 升级与行车记录仪存储空间。 |
| **Depth Map Pipeline（端侧深度图规格）** | `Depth-Anything-V2-Small (INT8)` 输入 `518×336` 推理 → 持久化为 `640×400` 8-bit 灰度 PNG → GPU `GL_LINEAR` 硬件插值 + Shader 3-Tap 双边边缘柔化 | CPU 插值保存 `2560×1600` 全尺寸深度图或推理后常驻内存 | 低频平滑深度场可消除视差边缘撕裂，显存与磁盘占用降低 93%（单张 ~80KB），推理耗时稳控在 0.8s 内且完成后立即释放 `OrtSession`/`Interpreter`。 |
| **Audio Visualizer（音乐律动采集与降级）** | 三层渐进式律动源：`Visualizer(0)` 系统混音 FFT → 可选 `RECORD_AUDIO` 麦克风环境拾音 → **生成式东方呼吸节拍发生器（Procedural Harmonic Envelope）** | 仅依赖 `Visualizer(0)`，无权限或采零时报错禁用 | 规避 Android 13/14 权限限制与 Flyme Sound 硬件 DSP 混音绕过 Session 0 的问题，确保频谱与微粒在任何权限状态下 100% 灵动可用。 |
| **Phase B Discovery & Sync（外部算力发现与同步）** | `NsdManager` mDNS（`_galaxy-wallpaper._tcp`）局域网自动发现 + H5 扫码/手动 URL 绑定；重绘视频经 SHA-256 校验后下载落盘，由本地 `MediaCodec` 循环硬解 | 固定 IP 硬编码或实时 RTSP 视频流推流 | 车辆驶离家庭 Wi-Fi 进入地下车库或行驶途中时，本地落盘的 H.265 视频仍可零网络依赖、零 GPU 负担无限循环播放。 |
| **Shader Composition（四大风格组合架构）** | **“主视觉基底 + 可选氛围叠加层”**单/双 Pass 融合管线：基底支持①中式涟漪 + ②3D景深视差融合（或 Phase B 视频流），叠加层支持独立开关③音乐频谱与④节气/天气微粒 | 四大风格严格互斥四选一或拆分为多个独立全屏渲染 Pass | 组合式 Uniform 管线在单次 Fragment Pass 内完成折射、视差、丁达尔光束与粒子合成，避免多 Pass 纹理读写带宽爆炸，GPU 稳控 < 40 GFLOPS。 |
| **Cockpit Safety（行车安全与防眩光守护）** | 监听 `UiModeManager` 夜间/隧道大灯状态自动触发 `u_NightModeDimming`（整体亮度乘以 `0.65` 即压暗 35%，高光峰值压暗 35%）；`onVisibilityChanged(false)` 时 100% 挂起渲染 | 固定全天候亮度或后台继续空转渲染循环 | 彻底消除夜间行车时 15.4 英寸屏幕在前挡风玻璃上的眩光反射，并在切入导航/锁屏时释放 100% GPU/CPU 资源。 |

## 3. 车机工程故障模式与防御矩阵（Engineering Failure-Mode Matrix）

针对车载复杂电磁、网络与 OS 权限环境，系统在架构层强制实施以下防御契约：

| 故障域 | 故障触发场景（Failure Scenario） | 强制防御与自愈机制（Required Mitigation Strategy） |
| :--- | :--- | :--- |
| **局域网快传网络层** | 车机切换 Wi-Fi / 手机热点或 DHCP 刷新导致 IP 变化，二维码失效 | 注册 `ConnectivityManager.NetworkCallback` 动态刷新二维码；二维码 URL 携带多网卡候选 IP 与一次性会话 Token；内置 HTTP Server 绑定 `0.0.0.0:8980` 并支持断点重传。 |
| **图像解码内存层** | 用户跳过 H5 裁剪直接上传 24MP~50MP（6000×4000+，35MB+）超清 PNG/JPG | 严禁直接全图解码入内存；强制流式写入临时文件 → `BitmapFactory.Options.inJustDecodeBounds = true` 读取尺寸 → 计算 2 的幂次 `inSampleSize` 降采样 → 显著性焦点居中裁剪至精确 `2560×1600` `ARGB_8888`。 |
| **深度推理算力层** | 连续快速上传多张图片引发 NPU/CPU 并发推理争抢与内存飙升 | 使用单并发协程通道（`Channel(capacity = 2)` + `Mutex`）串行化推理任务；单次推理完成后立即调用 `close()` 销毁模型会话并持久化 `depth_8bit.png`。 |
| **着色器边缘伪影** | 3D 景深呼吸视差在人物发丝或车身轮廓处产生拉伸撕裂（Haloing） | 将最大 UV 偏移量严格钳制在 `0.018 ~ 0.028` 黄金感知区间；相机轨迹采用李萨如（Lissajous）阻尼正弦曲线（`sin(uTime * 0.45) * 0.02`）；在 Shader 内对深度图采样执行 3-Tap 双边平滑。 |
| **外部算力网络中断** | Phase B 触发 ComfyUI 视频重绘期间车辆驶离家庭局域网或请求超时（> 15s） | 任务提交后立即返回 Phase A 实时着色器画面（3D 视差 + 中式涟漪）；后台异步轮询支持指数退避与超时自动取消，绝不阻塞车机前台交互。 |

## 4. 本地壁纸资产包（Wallpaper Asset Bundle）标准目录规范

每套导入或重绘的壁纸在车机应用私有存储中均以自包含（Self-Contained）原子目录存放，全局配额管理器（`WallpaperQuotaManager`）监控总目录体积不超过 `512 MB`（或 `30` 套）：

```text
/data/user/0/com.galaxy.starshine8.wallpaper/files/wallpapers/
├── index_lru.json                     # 全局 LRU 索引、当前激活 asset_id 与配额统计（上限 512MB / 30套）
└── wp_20261015_a8f9c2/                # 单套壁纸原子目录（<asset_id>）
    ├── color_2560x1600.webp           # 归一化 16:10 主图（2560×1600，WebP 95% 质量，约 1.2MB~2.0MB）
    ├── depth_8bit.png                 # 端侧 Depth-Anything-V2-Small 生成的 8-bit 灰度深度图（640×400，约 80KB）
    ├── thumb_640x400.webp             # 车机壁纸工坊列表缩略图（640×400，约 35KB）
    ├── video_loop.mp4                 # [Phase B 可选] 外部算力生成的 H.265 2560×1600 无缝循环视频（约 8MB~15MB）
    └── manifest.json                  # 元数据契约：Palette 五色、默认主风格与叠加层配置、SHA-256、标星状态
```

### `manifest.json` 数据契约示例

```json
{
  "assetId": "wp_20261015_a8f9c2",
  "title": "西湖晨雾·星耀画卷",
  "createdAtEpochMs": 1792067701000,
  "lastUsedEpochMs": 1792067701000,
  "isPinned": true,
  "resolution": { "width": 2560, "height": 1600, "aspectRatio": "16:10" },
  "palette": {
    "dominantHex": "#1B2A38",
    "vibrantHex": "#D49B54",
    "mutedHex": "#4A6070",
    "darkVibrantHex": "#0B131B",
    "lightVibrantHex": "#F2DFC4"
  },
  "styleConfig": {
    "baseMode": "HYBRID_RIPPLE_PARALLAX",
    "parallaxIntensity": 0.022,
    "breathingPeriodSec": 8.0,
    "rippleHorizonY": 0.72,
    "tyndallLightOpacity": 0.15,
    "enableAudioVisualizerOverlay": true,
    "weatherParticlePreset": "SOLAR_TERM_MIST"
  },
  "phaseBVideo": {
    "isAvailable": false,
    "relativeFilePath": null,
    "sha256": null,
    "sourceNodeUrl": null
  }
}
```

## 5. 核心模块解耦接口契约（Decoupled Interface Contracts）

依据 `design-an-interface` 规范，系统的显示宿主（`WallpaperService` vs `ImmersiveShowcaseActivity`）、实时着色器渲染引擎与 Phase A/B 资产管线通过以下纯领域接口完全解耦：

```kotlin
package com.galaxy.starshine8.wallpaper.core.contract

import android.graphics.Bitmap

/**
 * 统一动态场景渲染契约：彻底解耦渲染管线与宿主生命周期（WallpaperService.Engine 或 Activity SurfaceView）。
 */
interface DynamicSceneRenderer {
    fun onSurfaceReady(width: Int, height: Int)
    fun bindWallpaperBundle(bundle: LoadedWallpaperBundle)
    fun updateStyleConfig(config: WallpaperStyleConfig)
    fun updateCockpitState(state: CockpitEnvironmentState)
    fun setRenderPaused(paused: Boolean) // true 时 100% 挂起渲染循环，保障导航 0% GPU 争抢
    fun renderFrame(elapsedTimeSec: Float)
    fun release()
}

/**
 * 座舱环境与行车安全传感器状态包
 */
data class CockpitEnvironmentState(
    val isNightMode: Boolean,          // 来自 UiModeManager，夜间/隧道开启大灯时为 true
    val nightDimmingFactor: Float,     // 日间 1.0f，夜间强制乘以 0.65f（压暗 35% 防前挡风玻璃反光）
    val audioSpectrumBins: FloatArray, // 32 频段归一化音频能量（来自 Visualizer / 麦克风 / 生成式呼吸包络）
    val isAudioProceduralFallback: Boolean,
    val isParked: Boolean              // P 挡驻车状态标识
)

/**
 * 主视觉基底与叠加层组合配置（支持单 Pass 融合渲染）
 */
data class WallpaperStyleConfig(
    val baseMode: BaseVisualMode,
    val parallaxMaxUvOffset: Float = 0.022f,     // 严格钳制在 0.018f..0.028f
    val rippleHorizonThreshold: Float = 0.72f,   // 下方 28% 区域水波镜像折射
    val tyndallRayIntensity: Float = 0.15f,      // 12%~18% 丁达尔柔光体积束
    val enableAudioVisualizerOverlay: Boolean = true,
    val particleOverlayPreset: ParticlePreset = ParticlePreset.SOLAR_TERM_GOLD_DUST
)

enum class BaseVisualMode {
    EASTERN_RIPPLE,           // ① 中式涟漪美学（水面折射 + 丁达尔晨光）
    PARALLAX_3D,              // ② 裸眼 3D 景深呼吸视差
    HYBRID_RIPPLE_PARALLAX,   // ① + ② 融合画卷（默认推荐）
    PHASE_B_HARDWARE_VIDEO    // Phase B：H.265 2560×1600 VPU 零 GPU 负担硬解循环视频
}

enum class ParticlePreset {
    NONE,
    SOLAR_TERM_GOLD_DUST,     // 节气微尘/晨光流萤
    SPRING_CHERRY_BLOSSOM,    // 春分落樱
    MISTY_RAIN_DRIZZLE,       // 江南烟雨
    WINTER_SNOWFLAKE          // 霁雪初晴
}

/**
 * Phase A & Phase B 统一外部算力扩展契约（路线2无缝挂接点）
 */
interface ExternalComputeAdapter {
    suspend fun discoverNodes(timeoutMs: Long = 2500L): List<ComputeNodeEndpoint>
    suspend fun submitVideoRedrawJob(
        assetId: String,
        normalizedWebpFile: java.io.File,
        promptPreset: String
    ): RemoteJobHandle
    suspend fun pollAndDownloadMp4(
        handle: RemoteJobHandle,
        destinationMp4File: java.io.File
    ): Result<Unit>
}
```

## 6. 视觉美学与反 AI 廉价感红线（`/design-review` Aesthetic Guardrails）

为匹配 2025款吉利银河星耀8 豪华智能座舱定位，所有 UI 控件与 Shader 必须通过以下 4 项美学红线校验：

1. **座舱材质与色彩（Smoked Obsidian Glass）**：严禁使用高饱和霓虹紫/蓝渐变或纯白刺眼背景；所有悬浮控制栏与驻车画报时钟/音乐卡片均采用深曜石毛玻璃（`#0B0E14` 75% 不透明度 + 高斯模糊 `24dp` + `1px` `#1FFFFFFF` 顶部微高光描边），强调色动态取自当前壁纸 Palette 的 `vibrantHex` / `lightVibrantHex`。
2. **极简无边框画布（Zero-Chrome Canvas）**：全屏 `2560×1600` 零顶部导航栏（No Top ActionBar）；驻车画报与设置浮层在无操作 `3.0 秒` 后自动平滑隐退为底部微光胶囊 Dock。
3. **阻尼简谐运动曲线（Harmonic Motion Physics）**：严禁使用生硬的线性插值（`LinearEasing`）；3D 景深呼吸周期设定在 `6.0s ~ 12.0s` 冥想级阻尼正弦区间，水波折射采用随纵向距离衰减的简谐波函数。
4. **远距一瞥可读性（Glanceability at Arm's Length）**：驻车画报数字时钟采用 `72sp` 等宽细体（Tabular Light Numerals），正文与状态标签字号不低于 `18sp`，确保驾驶位一臂距离外清晰易读。
