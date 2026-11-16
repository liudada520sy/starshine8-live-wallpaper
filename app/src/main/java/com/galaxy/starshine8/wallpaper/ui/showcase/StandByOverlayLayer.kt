package com.galaxy.starshine8.wallpaper.ui.showcase

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.galaxy.starshine8.wallpaper.core.contract.CockpitEnvironmentState
import com.galaxy.starshine8.wallpaper.ui.theme.LocalStarshineGlassPalette
import com.galaxy.starshine8.wallpaper.ui.theme.obsidianGlassCard
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 驻车画报 StandBy 悬浮组件层（StandByOverlayLayer）。
 * P1 修复：消除硬编码 硬编码色值，统一使用 MaterialTheme.colorScheme.onPrimary 语义 Token，并保证按钮 >= 48dp 车规热区。
 */
@Composable
fun StandByOverlayLayer(
    wallpaperTitle: String,
    cockpitState: CockpitEnvironmentState,
    isOverlayVisible: Boolean,
    isWallpaperBindingLocked: Boolean,
    onToggleOverlayVisibility: () -> Unit,
    onOpenWorkbenchDrawer: () -> Unit,
    onOpenQrFastTransfer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalStarshineGlassPalette.current
    val systemBarInsets = WindowInsets.systemBars.asPaddingValues()
    var currentEpochMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            currentEpochMs = System.currentTimeMillis()
            delay(1000L)
        }
    }

    val timeString = remember(currentEpochMs) {
        SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(currentEpochMs))
    }
    val dateString = remember(currentEpochMs) {
        SimpleDateFormat("yyyy年MM月dd日 EEEE", Locale.CHINA).format(Date(currentEpochMs))
    }
    val solarTermBadge = remember(currentEpochMs) {
        resolveCurrentSolarTermBadge(currentEpochMs)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggleOverlayVisibility
            )
            .padding(systemBarInsets)
            .padding(horizontal = 48.dp, vertical = 32.dp)
    ) {
        AnimatedVisibility(
            visible = isOverlayVisible,
            enter = fadeIn(tween(420, easing = FastOutSlowInEasing)) +
                slideInVertically(tween(420, easing = FastOutSlowInEasing)) { -32 },
            exit = fadeOut(tween(320, easing = FastOutSlowInEasing)) +
                slideOutVertically(tween(320, easing = FastOutSlowInEasing)) { -32 },
            modifier = Modifier.align(Alignment.TopStart)
        ) {
            Column(
                modifier = Modifier
                    .width(460.dp)
                    .obsidianGlassCard(cornerRadius = 28.dp, glowIntensity = 0.25f)
                    .padding(horizontal = 28.dp, vertical = 24.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(palette.vibrantAccent.copy(alpha = 0.22f))
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = solarTermBadge,
                            style = MaterialTheme.typography.labelLarge,
                            color = palette.vibrantAccent
                        )
                    }
                    Text(
                        text = wallpaperTitle,
                        style = MaterialTheme.typography.bodyLarge,
                        color = palette.secondaryText
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = timeString,
                    style = MaterialTheme.typography.displayLarge,
                    color = palette.lightVibrantText
                )

                Text(
                    text = dateString,
                    style = MaterialTheme.typography.bodyLarge,
                    color = palette.secondaryText
                )

                Spacer(modifier = Modifier.height(18.dp))

                AudioSpectrumEqualizerBar(
                    bins = cockpitState.audioSpectrumBins,
                    isProceduralFallback = cockpitState.isAudioProceduralFallback,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                )
            }
        }

        AnimatedVisibility(
            visible = isOverlayVisible,
            enter = fadeIn(tween(420, easing = FastOutSlowInEasing)) +
                slideInVertically(tween(420, easing = FastOutSlowInEasing)) { 40 },
            exit = fadeOut(tween(320, easing = FastOutSlowInEasing)) +
                slideOutVertically(tween(320, easing = FastOutSlowInEasing)) { 40 },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier
                    .obsidianGlassCard(cornerRadius = 50.dp, glowIntensity = 0.20f)
                    .padding(horizontal = 28.dp, vertical = 14.dp)
            ) {
                StatusIndicatorPill(
                    dotColor = if (cockpitState.isNightMode) palette.vibrantAccent else palette.lightVibrantText,
                    label = if (cockpitState.isNightMode) {
                        "夜间防眩光已开启 (-35%)"
                    } else {
                        "日间 2.5K 全域光感"
                    }
                )

                if (isWallpaperBindingLocked) {
                    StatusIndicatorPill(
                        dotColor = palette.vibrantAccent,
                        label = "驻车画报 StandBy 守护模式"
                    )
                }

                DockActionButton(
                    text = "手机扫码快传",
                    isPrimary = true,
                    onClick = onOpenQrFastTransfer
                )

                DockActionButton(
                    text = "画卷美学调音台",
                    isPrimary = false,
                    onClick = onOpenWorkbenchDrawer
                )
            }
        }
    }
}

@Composable
private fun AudioSpectrumEqualizerBar(
    bins: FloatArray,
    isProceduralFallback: Boolean,
    modifier: Modifier = Modifier
) {
    val palette = LocalStarshineGlassPalette.current
    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(26.dp)
        ) {
            val count = 32
            val spacing = 4.dp.toPx()
            val barWidth = ((size.width - spacing * (count - 1)) / count).coerceAtLeast(2f)
            val brush = Brush.verticalGradient(
                colors = listOf(palette.lightVibrantText, palette.vibrantAccent)
            )
            for (i in 0 until count) {
                val energy = if (i < bins.size) bins[i].coerceIn(0.06f, 1.0f) else 0.1f
                val barHeight = size.height * energy
                val x = i * (barWidth + spacing)
                val y = size.height - barHeight
                drawRoundRect(
                    brush = brush,
                    topLeft = Offset(x, y),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = if (isProceduralFallback) {
                "律动源：东方简谐呼吸包络（静音/免权限守护）"
            } else {
                "律动源：Flyme Sound 实时 32 频段频谱"
            },
            style = MaterialTheme.typography.bodyLarge,
            color = palette.secondaryText.copy(alpha = 0.72f)
        )
    }
}

@Composable
private fun StatusIndicatorPill(
    dotColor: Color,
    label: String
) {
    val palette = LocalStarshineGlassPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = palette.lightVibrantText
        )
    }
}

@Composable
private fun DockActionButton(
    text: String,
    isPrimary: Boolean,
    onClick: () -> Unit
) {
    val palette = LocalStarshineGlassPalette.current
    val bg = if (isPrimary) {
        palette.vibrantAccent
    } else {
        palette.dominantAccent.copy(alpha = 0.65f)
    }
    val fg = if (isPrimary) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        palette.lightVibrantText
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 10.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = fg
        )
    }
}

private fun resolveCurrentSolarTermBadge(epochMs: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = epochMs }
    val month = cal.get(Calendar.MONTH) + 1
    val day = cal.get(Calendar.DAY_OF_MONTH)
    return when (month) {
        1 -> if (day < 20) "节气 · 小寒凝霜" else "节气 · 大寒瑞雪"
        2 -> if (day < 19) "节气 · 立春初融" else "节气 · 雨水润物"
        3 -> if (day < 20) "节气 · 惊蛰春雷" else "节气 · 春分落樱"
        4 -> if (day < 20) "节气 · 清明烟雨" else "节气 · 谷雨茶香"
        5 -> if (day < 21) "节气 · 立夏风清" else "节气 · 小满盈水"
        6 -> if (day < 21) "节气 · 芒种听荷" else "节气 · 夏至流萤"
        7 -> if (day < 22) "节气 · 小暑竹风" else "节气 · 大暑观星"
        8 -> if (day < 23) "节气 · 立秋云淡" else "节气 · 处暑知秋"
        9 -> if (day < 23) "节气 · 白露蒹葭" else "节气 · 秋分丹桂"
        10 -> if (day < 23) "节气 · 寒露枫林" else "节气 · 霜降流金"
        11 -> if (day < 22) "节气 · 立冬藏秀" else "节气 · 小雪初晴"
        else -> if (day < 21) "节气 · 大雪寒梅" else "节气 · 冬至阳生"
    }
}
