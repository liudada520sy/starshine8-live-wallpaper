package com.galaxy.starshine8.wallpaper.ui.workbench

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.galaxy.starshine8.wallpaper.core.contract.BaseVisualMode
import com.galaxy.starshine8.wallpaper.core.contract.CockpitWorkbenchUiState
import com.galaxy.starshine8.wallpaper.core.contract.ComputeNodeEndpoint
import com.galaxy.starshine8.wallpaper.core.contract.ParticlePreset
import com.galaxy.starshine8.wallpaper.core.contract.WallpaperManifest
import com.galaxy.starshine8.wallpaper.core.contract.WallpaperStyleConfig
import com.galaxy.starshine8.wallpaper.feature.transfer.LanFastTransferServer
import com.galaxy.starshine8.wallpaper.ui.theme.LocalStarshineGlassPalette
import com.galaxy.starshine8.wallpaper.ui.theme.obsidianGlassCard
import kotlinx.coroutines.delay
import java.io.File

/**
 * 15.4 寸 2560×1600 大屏主控抽屉与参数调音台（CockpitWorkbenchScreen）。
 * P1 升级特性：
 * 1. 零硬编码色值逃逸：全部前景色统一使用 MaterialTheme.colorScheme.onPrimary / LocalStarshineGlassPalette。
 * 2. 5-State UI 交互闭环：RecoverableError 附带「重新绑定服务」CTA，EmptyCustomGallery 附带「立即扫码传图」CTA。
 * 3. 30 套 LRU 缩略图画廊：使用横向 LazyRow 渲染 thumb_640x400.webp 真实缩略图预览，支持 30 套滚动与标星锁定。
 */
@Composable
fun CockpitWorkbenchScreen(
    workbenchUiState: CockpitWorkbenchUiState,
    savedManifests: List<WallpaperManifest>,
    activeAssetId: String,
    quotaUsedBytes: Long,
    currentStyleConfig: WallpaperStyleConfig,
    transferState: LanFastTransferServer.TransferServerState,
    discoveredPhaseBNodes: List<ComputeNodeEndpoint>,
    phaseBStatusText: String,
    isPhaseBVideoAvailable: Boolean,
    isDrawerOpen: Boolean,
    isQrDialogOpen: Boolean,
    resolveThumbnailFile: (String) -> File,
    onCloseDrawer: () -> Unit,
    onCloseQrDialog: () -> Unit,
    onRetryRecoverableError: () -> Unit,
    onOpenQrFromEmpty: () -> Unit,
    onSelectSavedWallpaper: (String) -> Unit,
    onTogglePinWallpaper: (String) -> Unit,
    onStyleConfigChangedRealtime: (WallpaperStyleConfig) -> Unit,
    onStyleConfigDebouncedPersist: (WallpaperStyleConfig) -> Unit,
    onTriggerSystemWallpaperBind: () -> Unit,
    onTriggerPhaseBRedraw: (ComputeNodeEndpoint) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalStarshineGlassPalette.current
    val onPrimaryColor = MaterialTheme.colorScheme.onPrimary

    var parallaxOffset by remember(currentStyleConfig.parallaxMaxUvOffset) {
        mutableFloatStateOf(currentStyleConfig.parallaxMaxUvOffset.coerceIn(0.018f, 0.028f))
    }
    var rippleHorizon by remember(currentStyleConfig.rippleHorizonThreshold) {
        mutableFloatStateOf(currentStyleConfig.rippleHorizonThreshold.coerceIn(0.50f, 0.90f))
    }
    var tyndallIntensity by remember(currentStyleConfig.tyndallRayIntensity) {
        mutableFloatStateOf(currentStyleConfig.tyndallRayIntensity.coerceIn(0.12f, 0.18f))
    }
    var selectedBaseMode by remember(currentStyleConfig.baseMode) {
        mutableStateOf(currentStyleConfig.baseMode)
    }
    var audioOverlayEnabled by remember(currentStyleConfig.enableAudioVisualizerOverlay) {
        mutableStateOf(currentStyleConfig.enableAudioVisualizerOverlay)
    }
    var selectedParticle by remember(currentStyleConfig.particleOverlayPreset) {
        mutableStateOf(currentStyleConfig.particleOverlayPreset)
    }

    val composedConfig = remember(
        selectedBaseMode,
        parallaxOffset,
        rippleHorizon,
        tyndallIntensity,
        audioOverlayEnabled,
        selectedParticle
    ) {
        WallpaperStyleConfig(
            baseMode = selectedBaseMode,
            parallaxMaxUvOffset = parallaxOffset.coerceIn(0.018f, 0.028f),
            rippleHorizonThreshold = rippleHorizon.coerceIn(0.50f, 0.90f),
            tyndallRayIntensity = tyndallIntensity.coerceIn(0.12f, 0.18f),
            enableAudioVisualizerOverlay = audioOverlayEnabled,
            particleOverlayPreset = selectedParticle
        )
    }

    LaunchedEffect(composedConfig) {
        delay(250L)
        onStyleConfigDebouncedPersist(composedConfig)
    }

    Box(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = isDrawerOpen,
            enter = fadeIn(tween(360, easing = FastOutSlowInEasing)) +
                slideInHorizontally(tween(360, easing = FastOutSlowInEasing)) { it },
            exit = fadeOut(tween(280, easing = FastOutSlowInEasing)) +
                slideOutHorizontally(tween(280, easing = FastOutSlowInEasing)) { it },
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            Column(
                modifier = Modifier
                    .width(640.dp)
                    .fillMaxHeight()
                    .padding(28.dp)
                    .obsidianGlassCard(cornerRadius = 32.dp, glowIntensity = 0.28f)
                    .padding(horizontal = 32.dp, vertical = 26.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "星耀8 · 东方画卷美学调音台",
                        style = MaterialTheme.typography.headlineMedium,
                        color = palette.lightVibrantText
                    )
                    Text(
                        text = "收起",
                        style = MaterialTheme.typography.labelLarge,
                        color = palette.vibrantAccent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable(onClick = onCloseDrawer)
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                WorkbenchStateBanner(
                    uiState = workbenchUiState,
                    onRetry = onRetryRecoverableError,
                    onOpenQr = onOpenQrFromEmpty
                )

                Text(
                    text = "主视觉基底（单 Pass 融合管线）",
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.vibrantAccent
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ModeSelectChip(
                        label = "①+② 融合画卷",
                        selected = selectedBaseMode == BaseVisualMode.HYBRID_RIPPLE_PARALLAX,
                        onClick = {
                            selectedBaseMode = BaseVisualMode.HYBRID_RIPPLE_PARALLAX
                            onStyleConfigChangedRealtime(composedConfig.copy(baseMode = selectedBaseMode))
                        },
                        modifier = Modifier.weight(1f)
                    )
                    ModeSelectChip(
                        label = "① 中式涟漪",
                        selected = selectedBaseMode == BaseVisualMode.EASTERN_RIPPLE,
                        onClick = {
                            selectedBaseMode = BaseVisualMode.EASTERN_RIPPLE
                            onStyleConfigChangedRealtime(composedConfig.copy(baseMode = selectedBaseMode))
                        },
                        modifier = Modifier.weight(1f)
                    )
                    ModeSelectChip(
                        label = "② 3D 景深视差",
                        selected = selectedBaseMode == BaseVisualMode.PARALLAX_3D,
                        onClick = {
                            selectedBaseMode = BaseVisualMode.PARALLAX_3D
                            onStyleConfigChangedRealtime(composedConfig.copy(baseMode = selectedBaseMode))
                        },
                        modifier = Modifier.weight(1f)
                    )
                }

                if (isPhaseBVideoAvailable) {
                    ModeSelectChip(
                        label = "路线2 · Phase B H.265 VPU 零负担硬解视频流",
                        selected = selectedBaseMode == BaseVisualMode.PHASE_B_HARDWARE_VIDEO,
                        onClick = {
                            selectedBaseMode = BaseVisualMode.PHASE_B_HARDWARE_VIDEO
                            onStyleConfigChangedRealtime(composedConfig.copy(baseMode = selectedBaseMode))
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                ParameterSliderRow(
                    title = "3D 景深呼吸视差幅度 (0.018 ~ 0.028)",
                    valueText = String.format("%.3f", parallaxOffset),
                    value = parallaxOffset,
                    valueRange = 0.018f..0.028f,
                    onValueChange = { v ->
                        parallaxOffset = v.coerceIn(0.018f, 0.028f)
                        onStyleConfigChangedRealtime(composedConfig.copy(parallaxMaxUvOffset = parallaxOffset))
                    }
                )

                ParameterSliderRow(
                    title = "中式水波折射地平线 (0.50 ~ 0.90)",
                    valueText = String.format("%.2f", rippleHorizon),
                    value = rippleHorizon,
                    valueRange = 0.50f..0.90f,
                    onValueChange = { v ->
                        rippleHorizon = v.coerceIn(0.50f, 0.90f)
                        onStyleConfigChangedRealtime(composedConfig.copy(rippleHorizonThreshold = rippleHorizon))
                    }
                )

                ParameterSliderRow(
                    title = "丁达尔晨光体积束浓度 (0.12 ~ 0.18)",
                    valueText = String.format("%.2f", tyndallIntensity),
                    value = tyndallIntensity,
                    valueRange = 0.12f..0.18f,
                    onValueChange = { v ->
                        tyndallIntensity = v.coerceIn(0.12f, 0.18f)
                        onStyleConfigChangedRealtime(composedConfig.copy(tyndallRayIntensity = tyndallIntensity))
                    }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "③ Flyme Sound 32频段流光音频律动",
                        style = MaterialTheme.typography.bodyLarge,
                        color = palette.lightVibrantText
                    )
                    Switch(
                        checked = audioOverlayEnabled,
                        onCheckedChange = { checked ->
                            audioOverlayEnabled = checked
                            onStyleConfigChangedRealtime(composedConfig.copy(enableAudioVisualizerOverlay = checked))
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = onPrimaryColor,
                            checkedTrackColor = palette.vibrantAccent
                        )
                    )
                }

                Text(
                    text = "④ 节气与天气微粒流转叠加层",
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.vibrantAccent
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ParticlePreset.entries.forEach { preset ->
                        val title = when (preset) {
                            ParticlePreset.NONE -> "关闭"
                            ParticlePreset.SOLAR_TERM_GOLD_DUST -> "晨光流萤"
                            ParticlePreset.SPRING_CHERRY_BLOSSOM -> "春分落樱"
                            ParticlePreset.MISTY_RAIN_DRIZZLE -> "江南烟雨"
                            ParticlePreset.WINTER_SNOWFLAKE -> "霁雪初晴"
                        }
                        ModeSelectChip(
                            label = title,
                            selected = selectedParticle == preset,
                            onClick = {
                                selectedParticle = preset
                                onStyleConfigChangedRealtime(composedConfig.copy(particleOverlayPreset = preset))
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // 4. 本地 512MB / 30套 LRU 缩略图画卷资产库（支持完整 30 套横向滑动预览与标星锁定）
                val usedMb = String.format("%.1f", quotaUsedBytes / (1024f * 1024f))
                Text(
                    text = "本地画卷资产库 (${savedManifests.size}/30 套 · 已用 $usedMb / 512 MB)",
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.vibrantAccent
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(savedManifests, key = { it.assetId }) { item ->
                        val isCurrent = item.assetId == activeAssetId
                        val thumbBitmap = remember(item.assetId) {
                            val file = resolveThumbnailFile(item.assetId)
                            if (file.exists()) {
                                BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
                            } else null
                        }
                        Column(
                            modifier = Modifier
                                .width(208.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    if (isCurrent) palette.vibrantAccent.copy(alpha = 0.22f)
                                    else palette.dominantAccent.copy(alpha = 0.45f)
                                )
                                .border(
                                    width = if (isCurrent) 1.5.dp else 1.dp,
                                    color = if (isCurrent) palette.vibrantAccent else palette.specularBottomBorder,
                                    shape = RoundedCornerShape(16.dp)
                                )
                                .clickable { onSelectSavedWallpaper(item.assetId) }
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(118.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(palette.darkVibrantAccent)
                            ) {
                                if (thumbBitmap != null) {
                                    Image(
                                        bitmap = thumbBitmap,
                                        contentDescription = item.title,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(6.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(palette.obsidianDeep)
                                        .clickable { onTogglePinWallpaper(item.assetId) }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = if (item.isPinned) "★ 已锁定" else "☆ 标星",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = if (item.isPinned) palette.vibrantAccent else palette.secondaryText
                                    )
                                }
                            }
                            Text(
                                text = if (isCurrent) "● ${item.title}" else item.title,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (isCurrent) palette.vibrantAccent else palette.lightVibrantText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // 5. Phase B 外部算力节点协同（mDNS 自动发现）与系统桌面壁纸绑定
                Text(
                    text = "路线2 · 外部算力协同 (FastAPI + ComfyUI + MediaCodec H.265)",
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.vibrantAccent
                )
                Text(
                    text = phaseBStatusText,
                    style = MaterialTheme.typography.bodyLarge,
                    color = palette.secondaryText
                )
                discoveredPhaseBNodes.forEach { node ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(palette.dominantAccent.copy(alpha = 0.55f))
                            .clickable { onTriggerPhaseBRedraw(node) }
                            .padding(horizontal = 20.dp, vertical = 14.dp)
                    ) {
                        Text(
                            text = "触发 H.265 2560×1600 视频重绘 · ${node.nodeName} (${node.gpuModel})",
                            style = MaterialTheme.typography.bodyLarge,
                            color = palette.lightVibrantText
                        )
                    }
                }

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(palette.vibrantAccent)
                        .clickable(onClick = onTriggerSystemWallpaperBind)
                        .padding(vertical = 16.dp)
                ) {
                    Text(
                        text = "设为 Flyme Auto 系统桌面动态壁纸",
                        style = MaterialTheme.typography.labelLarge,
                        color = onPrimaryColor
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = isQrDialogOpen,
            enter = fadeIn(tween(320, easing = FastOutSlowInEasing)),
            exit = fadeOut(tween(240, easing = FastOutSlowInEasing)),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier
                    .width(580.dp)
                    .obsidianGlassCard(cornerRadius = 32.dp, glowIntensity = 0.32f)
                    .padding(36.dp)
            ) {
                Text(
                    text = "手机扫码免U盘快传 · 16:10 交互式取景",
                    style = MaterialTheme.typography.headlineMedium,
                    color = palette.lightVibrantText
                )
                Text(
                    text = "请将手机连接车机热点或同一 Wi-Fi，扫码打开内置 2560×1600 裁剪页",
                    style = MaterialTheme.typography.bodyLarge,
                    color = palette.secondaryText
                )

                transferState.qrCodeBitmap?.let { qrBmp ->
                    Image(
                        bitmap = qrBmp.asImageBitmap(),
                        contentDescription = "LAN Fast Transfer QR Code",
                        modifier = Modifier
                            .size(260.dp)
                            .clip(RoundedCornerShape(20.dp))
                    )
                }

                Text(
                    text = "多网卡自愈监听 IP: ${
                        transferState.candidateIps.joinToString(" / ").ifBlank { "192.168.43.1" }
                    }:${LanFastTransferServer.LISTEN_PORT}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = palette.vibrantAccent
                )

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LinearProgressIndicator(
                        progress = { transferState.stage.progressPercent / 100f },
                        color = palette.vibrantAccent,
                        trackColor = palette.dominantAccent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                    )
                    Text(
                        text = "${transferState.stage.label} (${transferState.stage.progressPercent}%)",
                        style = MaterialTheme.typography.bodyLarge,
                        color = palette.lightVibrantText
                    )
                }

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(50))
                        .background(palette.dominantAccent.copy(alpha = 0.7f))
                        .clickable(onClick = onCloseQrDialog)
                        .padding(horizontal = 32.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = "关闭扫码窗口",
                        style = MaterialTheme.typography.labelLarge,
                        color = palette.lightVibrantText
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkbenchStateBanner(
    uiState: CockpitWorkbenchUiState,
    onRetry: () -> Unit,
    onOpenQr: () -> Unit
) {
    val palette = LocalStarshineGlassPalette.current
    val onPrimaryColor = MaterialTheme.colorScheme.onPrimary
    val statusText = when (uiState) {
        is CockpitWorkbenchUiState.IdealReady ->
            "就绪态 · 当前激活 ${uiState.activeAssetId}（共收藏 ${uiState.totalSavedCount} 套画卷）"
        is CockpitWorkbenchUiState.LoadingPipeline ->
            "管线处理中 · ${uiState.stageLabel} (${uiState.progressPercent}%)"
        is CockpitWorkbenchUiState.EmptyCustomGallery ->
            "初始画卷态 · 当前展示内置「${uiState.defaultAssetTitle}」"
        is CockpitWorkbenchUiState.RecoverableError ->
            "可恢复提示 · ${uiState.userFriendlyMessage}"
        is CockpitWorkbenchUiState.ThermalProtectedMode ->
            "座舱温控守护 Tier ${uiState.thermalTier} · ${uiState.statusDescription}"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(palette.dominantAccent.copy(alpha = 0.48f))
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodyLarge,
            color = palette.lightVibrantText,
            modifier = Modifier.weight(1f)
        )
        if (uiState is CockpitWorkbenchUiState.RecoverableError && uiState.canRetry) {
            Spacer(modifier = Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(palette.vibrantAccent)
                    .clickable(onClick = onRetry)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "重试",
                    style = MaterialTheme.typography.labelLarge,
                    color = onPrimaryColor
                )
            }
        } else if (uiState is CockpitWorkbenchUiState.EmptyCustomGallery) {
            Spacer(modifier = Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(palette.vibrantAccent)
                    .clickable(onClick = onOpenQr)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "扫码传图",
                    style = MaterialTheme.typography.labelLarge,
                    color = onPrimaryColor
                )
            }
        }
    }
}

@Composable
private fun ModeSelectChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalStarshineGlassPalette.current
    val bgColor = if (selected) palette.vibrantAccent else palette.dominantAccent.copy(alpha = 0.50f)
    val textColor = if (selected) MaterialTheme.colorScheme.onPrimary else palette.lightVibrantText
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ParameterSliderRow(
    title: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    val palette = LocalStarshineGlassPalette.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = palette.lightVibrantText
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.bodyLarge,
                color = palette.vibrantAccent
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = palette.vibrantAccent,
                activeTrackColor = palette.vibrantAccent,
                inactiveTrackColor = palette.dominantAccent
            )
        )
    }
}
